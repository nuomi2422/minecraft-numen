package com.dwinovo.numen.plugins.acx;

import com.dwinovo.numen.acx.core.NumenToolCatalog;
import com.dwinovo.numen.acx.api.AcxCompletionProbe;
import com.dwinovo.numen.acx.api.AcxPortSchema;
import com.dwinovo.numen.acx.api.AcxStepOutcome;
import com.dwinovo.numen.acx.api.AcxToolPort;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.dwinovo.numen.task.TaskRecord;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 把 Numen 执行层工具桥接成 ACX 端口（契约 ACX-N1 的宿主侧实现）。
 *
 * <p>与旧 {@code NumenToolBridge} 的关键差别：旧桥走 {@code tool.invoke()} →
 * {@code ServerToolTransport.ship}，那是<b>客户端网络路径</b>，服务端线程下走不通。
 * 本桥直接在服务端线程调 {@code tool.onServerCall(...)}（与
 * {@code ExecuteToolPayload.handleCompanion} 同路径），再用回执映射端口结果。</p>
 *
 * <p>受理 ≠ 完成：{@code success=true && data.async=true} 映射为
 * {@link AcxToolPort.Result#accepted}（绝不当 COMPLETED）。真正完成靠
 * {@code task_finished}（推模型）或 {@link #probeFor}（拉模型，查
 * {@code CompanionTickDispatcher}）。</p>
 *
 * <p>外部调用标记：callId 用 {@code mcp-acx-<uuid>} 前缀，Numen 据此走
 * 「外部调用不扰内置大脑、完成不发 task_finished」的路径，由驱动方轮询。</p>
 */
public final class NumenToolBridgeX implements AcxToolPort {

    // 同步工具(runSync)的结果经 TaskResultPayload 发给客户端；ACX 直调(服务端)拿不到那条回执，
    // 会一直等到超时。5s 足够让「点击/破坏/放置」这类当场动作真正发生，又不至于每步白等 30s。
    // 需要长等的异步工具走 accepted 回执(立即返回)，不受这个值影响。
    public static final long DEFAULT_TIMEOUT_MS = 5_000;
    public static final String CALL_PREFIX = "mcp-acx-";

    private static final Gson GSON = new Gson();

    private final NumenTool tool;
    private final AcxPlugin plugin;
    private final long timeoutMs;
    private final AcxPortSchema schema;

    public NumenToolBridgeX(NumenTool tool, AcxPlugin plugin) {
        this(tool, plugin, DEFAULT_TIMEOUT_MS);
    }

    public NumenToolBridgeX(NumenTool tool, AcxPlugin plugin, long timeoutMs) {
        if (tool == null || plugin == null) {
            throw new IllegalArgumentException("tool / plugin 不能为空");
        }
        this.tool = tool;
        this.plugin = plugin;
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        this.schema = buildSchema(tool);
    }

    @Override
    public String name() {
        return tool.name();
    }

    @Override
    public String description() {
        String d = tool.description();
        return d == null ? "" : d;
    }

    @Override
    public AcxPortSchema schema() {
        return schema;
    }

    @Override
    public Result invoke(Map<String, Object> params) {
        NumenPlayer companion = plugin.currentCompanion();
        if (companion == null) {
            return Result.failed("尚无绑定同伴（先调一次 acx_* 门面再执行）");
        }
        String callId = CALL_PREFIX + UUID.randomUUID();
        String argsJson = params == null || params.isEmpty() ? "{}" : GSON.toJson(params);
        JsonObject args;
        try {
            JsonElement el = JsonParser.parseString(argsJson);
            args = el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return Result.failed("参数不是 JSON 对象: " + e.getMessage());
        }

        CompletableFuture<String> done = new CompletableFuture<>();
        try {
            plugin.gate().call(() -> {
                tool.onServerCall(callId, args, companion, reply -> {
                    if (!done.isDone()) {
                        done.complete(reply);
                    }
                });
                return AcxStepOutcome.success(Map.of());
            });
        } catch (Throwable t) {
            return Result.failed("宿主派发失败: " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()) + " (tool=" + name() + ")");
        }

        String reply;
        try {
            reply = done.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return Result.failed("等待工具回执超时(" + timeoutMs + "ms): " + name());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failed("等待工具回执被中断: " + name());
        } catch (ExecutionException e) {
            return Result.failed("宿主执行抛异常: " + e.getCause() + " (tool=" + name() + ")");
        }
        return mapReply(reply, name());
    }

    /** 把宿主回执 JSON 映射成端口结果（照旧 BridgeResultMapper 的口径，收进 ACX）。 */
    @SuppressWarnings("unchecked")
    static Result mapReply(String reply, String toolName) {
        if (reply == null || reply.isBlank()) {
            return Result.failed("空回执: " + toolName);
        }
        JsonObject obj;
        try {
            JsonElement el = JsonParser.parseString(reply);
            if (!el.isJsonObject()) {
                throw new IllegalStateException("非对象");
            }
            obj = el.getAsJsonObject();
        } catch (RuntimeException e) {
            return Result.failed("回执非 JSON 对象: " + toolName + " -> " + clip(reply));
        }

        if (!obj.has("success")) {
            // 裸 JSON（get_self_status 等）：整个对象就是产出
            Map<String, Object> bare = GSON.fromJson(obj, Map.class);
            return Result.completed(bare, "ok");
        }

        boolean success = obj.get("success").isJsonPrimitive() && obj.get("success").getAsBoolean();
        String message = obj.has("message") && obj.get("message").isJsonPrimitive()
                ? obj.get("message").getAsString() : "";

        if (obj.has("timed_out") && obj.get("timed_out").isJsonPrimitive() && obj.get("timed_out").getAsBoolean()) {
            return Result.failed("宿主任务超时" + (message.isBlank() ? "" : ": " + message));
        }
        if (obj.has("interrupted") && obj.get("interrupted").isJsonPrimitive() && obj.get("interrupted").getAsBoolean()) {
            return Result.failed("宿主任务被中断" + (message.isBlank() ? "" : ": " + message));
        }

        Map<String, Object> data = obj.has("data") && obj.get("data").isJsonObject()
                ? GSON.fromJson(obj.get("data"), Map.class) : Map.of();

        if (!success) {
            return Result.failed(message.isBlank() ? "工具失败: " + toolName : message);
        }
        boolean async = Boolean.TRUE.equals(data.get("async"));
        if (async) {
            String taskId = data.get("task_id") == null ? "" : String.valueOf(data.get("task_id"));
            boolean standing = Boolean.TRUE.equals(data.get("standing"));
            return standing
                    ? Result.acceptedStanding(taskId, data, message)
                    : Result.accepted(taskId, data, message);
        }
        return Result.completed(data, message);
    }

    /**
     * 完成探针（契约 ACX-P1）：按任务号查终态，**槽空不再等于成功，槽被占也不删账**。
     *
     * <p>三种槽状态：槽里还有**本任务** → {@code running}；槽空 → 按 id 查终态表（有记录按六分支映射，
     * 查不到 → {@code unknown}）；槽被**别的任务**占用 → 先按 id 查终态表（acb12b3：查到照常映射，
     * 查不到 → {@code unknown}，**不再直接 failed** —— 那会把真正成功的任务报成失败并删账）。
     */
    public static AcxCompletionProbe probeFor(Supplier<NumenPlayer> companionSupplier) {
        return taskId -> {
            NumenPlayer p = companionSupplier == null ? null : companionSupplier.get();
            if (p == null) {
                return AcxCompletionProbe.Snapshot.running("无绑定同伴，无法探测");
            }
            TaskRecord rec;
            try {
                rec = CompanionTickDispatcher.currentTaskFor(p.getUUID());
            } catch (Throwable t) {
                return AcxCompletionProbe.Snapshot.running("探测异常: " + t.getClass().getSimpleName());
            }
            if (rec == null) {
                // ═══ AC-B12b（2026-10-21）：槽空**不等于成功** ═══
                // 修前这里一律 done → 任务 FAILED 时 AC 也会拿到 SUCCESS（假完成）。
                // 现在去问「最近一次终态」：SUCCESS → done；FAILED/TIMEOUT/CANCELLED → failed；
                // **查不到（外部调用结束后没有记录）→ unknown** ——
                // 「不知道成败」不许当成成功，也不许立刻当失败删账（acb12b3）。
                TaskRecord last = null;
                try {
                    // 有任务号 → 按 id 精确查（避免被之后结算的别的任务覆盖成「不属于本任务」）；
                    // 没有任务号 → 退回「最近一次终态」，但下方 map 仍要求 SUCCESS 且会校验 id。
                    last = (taskId == null || taskId.isBlank())
                            ? CompanionTickDispatcher.lastFinishedTaskFor(p.getUUID())
                            : CompanionTickDispatcher.finishedTaskFor(p.getUUID(), taskId);
                } catch (Throwable ignored) {
                    // 读不到就按「没有终态记录」处理
                }
                return com.dwinovo.numen.acx.core.AcxFinishedTask.map(taskId,
                        last == null ? null : last.publicId(),
                        last == null || last.getState() == null ? null : last.getState().name());
            }
            if (taskId != null && !taskId.isBlank() && !taskId.equals(rec.publicId())) {
                // ═══ acb12b3（B 复审 MAJOR-1）：槽被**别的任务**占着 ≠ 本任务失败 ═══
                // 修前这里直接 failed → PortToolAdapter 删账 → 重试重发物理动作。
                // 现在先按 id 查终态表：t1 的 SUCCESS 还在就照常 DONE；
                // 查不到才 unknown（PortToolAdapter 保留挂账 + 30s 宽限）。
                TaskRecord last = null;
                try {
                    last = CompanionTickDispatcher.finishedTaskFor(p.getUUID(), taskId);
                } catch (Throwable ignored) {
                    // 读不到就按「没有终态记录」处理
                }
                if (last != null) {
                    return com.dwinovo.numen.acx.core.AcxFinishedTask.map(taskId,
                            last.publicId(),
                            last.getState() == null ? null : last.getState().name());
                }
                return AcxCompletionProbe.Snapshot.unknown(
                        "槽被 " + rec.publicId() + " 占用，本任务终态未知（保留挂账等待重试）");
            }
            return AcxCompletionProbe.Snapshot.running("仍在执行: " + rec.publicId());
        };
    }

    // ── schema ──────────────────────────────────────────────────────────

    private static AcxPortSchema buildSchema(NumenTool tool) {
        // 优先用实测目录（带输出字段，加载期 $ref 校验靠它）；目录没有再从 Numen 的 JSON Schema 转
        NumenToolCatalog.ToolSpec spec = NumenToolCatalog.find(tool.name());
        if (spec != null) {
            return spec.schema();
        }
        AcxPortSchema.Builder b = AcxPortSchema.builder().allowUnknown(false);
        Map<String, Object> raw = tool.parameterSchema();
        if (raw == null || raw.isEmpty()) {
            return b.build();
        }
        Set<String> required = new HashSet<>();
        if (raw.get("required") instanceof List<?> list) {
            for (Object o : list) {
                required.add(String.valueOf(o));
            }
        }
        if (raw.get("properties") instanceof Map<?, ?> props) {
            for (Map.Entry<?, ?> e : props.entrySet()) {
                String pname = String.valueOf(e.getKey());
                if (!(e.getValue() instanceof Map<?, ?> ps)) {
                    continue;
                }
                AcxPortSchema.Type type = mapType(ps);
                AcxPortSchema.Param param = required.contains(pname)
                        ? AcxPortSchema.Param.req(type)
                        : AcxPortSchema.Param.opt(type);
                if (isNullable(ps)) {
                    param = param.nullable();
                }
                if (ps.get("description") instanceof String d && !d.isBlank()) {
                    param = param.desc(d);
                }
                if (ps.get("enum") instanceof List<?> vals && !vals.isEmpty()) {
                    String[] arr = new String[vals.size()];
                    for (int i = 0; i < vals.size(); i++) {
                        arr[i] = String.valueOf(vals.get(i));
                    }
                    param = param.withEnum(arr);
                }
                if (ps.get("minimum") instanceof Number lo && ps.get("maximum") instanceof Number hi) {
                    param = param.range(lo.doubleValue(), hi.doubleValue());
                }
                b.param(pname, param);
            }
        }
        return b.build();
    }

    private static AcxPortSchema.Type mapType(Map<?, ?> ps) {
        Object t = ps.get("type");
        if (t instanceof List<?> list) {
            for (Object o : list) {
                String v = o == null ? "" : String.valueOf(o);
                if (!"null".equals(v)) {
                    return simpleType(v);
                }
            }
            return AcxPortSchema.Type.ANY;
        }
        return simpleType(t == null ? "" : String.valueOf(t));
    }

    private static AcxPortSchema.Type simpleType(String t) {
        return switch (t) {
            case "string" -> AcxPortSchema.Type.STRING;
            case "integer" -> AcxPortSchema.Type.INTEGER;
            case "number" -> AcxPortSchema.Type.NUMBER;
            case "boolean" -> AcxPortSchema.Type.BOOLEAN;
            case "array" -> AcxPortSchema.Type.STRING_ARRAY;
            case "object" -> AcxPortSchema.Type.OBJECT;
            default -> AcxPortSchema.Type.ANY;
        };
    }

    private static boolean isNullable(Map<?, ?> ps) {
        if (ps.get("type") instanceof List<?> list) {
            for (Object o : list) {
                if ("null".equals(String.valueOf(o))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String clip(String s) {
        String one = s.replace('\n', ' ');
        return one.length() <= 120 ? one : one.substring(0, 120) + "…";
    }
}
