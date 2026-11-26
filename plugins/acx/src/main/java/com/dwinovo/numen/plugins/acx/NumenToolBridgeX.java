package com.dwinovo.numen.plugins.acx;

import com.dwinovo.numen.acx.core.AcxFinishedTask;
import com.dwinovo.numen.acx.core.NumenToolCatalog;
import com.dwinovo.numen.acx.api.AcxBodyAction;
import com.dwinovo.numen.acx.api.AcxCompletionProbe;
import com.dwinovo.numen.acx.api.AcxPortSchema;
import com.dwinovo.numen.acx.api.AcxStepOutcome;
import com.dwinovo.numen.acx.api.AcxToolPort;
import com.dwinovo.numen.acx.api.AcxTool;
import com.dwinovo.numen.acx.core.AcxSessionManager;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.api.WorldContextStamp;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.TaskState;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    //
    // 2026-10-28 实机（nether_retreat 第一次真机跑）：`use_portal` 是「走到传送门再进去」的长动作，
    // 它同步等终态、5 秒回不来 → 脚本判 FAIL，而身体其实已经在走了（坐标 166,45,717 → 87,33,630 确实在变）。
    // 所以 5s 对走路类积木明显不够：短动作（查状态/扫描）毫秒级返回不受影响，
    // 长动作多等几秒，比"误判失败、整段脚本作废"划算得多。改 30s。
    public static final long DEFAULT_TIMEOUT_MS = 30_000;
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
        return Result.failed("宿主工具需要原始运行上下文，不能使用全局同伴");
    }

    private record HostReply(String json, Result refusal) {
        Optional<Result> result(String toolName) {
            return refusal == null ? mapReply(json, toolName) : Optional.of(refusal);
        }
    }

    @Override
    public Result invoke(Map<String, Object> params, AcxTool.AcxCallContext ctx) {
        AcxSessionManager.Session session = plugin.sessionFor(ctx);
        if (session == null || !(session.bodyToken() instanceof NumenPlayer companion)) {
            return Result.failed("运行没有原始宿主同伴绑定");
        }
        if (session.isCancelRequested()) {
            throw new CancelledBeforeDispatchException("运行已取消，未派发工具");
        }
        if (ctx.isCancelRequested()) {
            return Result.failed("执行上下文已失效，未派发工具");
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

        CompletableFuture<HostReply> done = new CompletableFuture<>();
        // First migrated producer; other ports retain their existing completion contract.
        boolean watchBody = "mine".equals(name());
        AtomicReference<WorldContextStamp.Token> issuedIn = new AtomicReference<>();
        ReplyReceipt receipt = () -> {
            if (!done.isDone()) confirmOriginalSyncTerminal(session, companion, ctx, callId, issuedIn.get())
                    .ifPresent(json -> done.complete(new HostReply(json, null)));
            return Optional.ofNullable(done.getNow(null))
                .flatMap(reply -> reply.result(name())).map(result -> {
            if (result.isAccepted()) {
                session.noteAcceptedTask(result.taskId());
                if (watchBody && !result.standing()) {
                    return result.withBodyAction(bodyRef(result.taskId(), callId, name(), session.ownerId(), issuedIn.get()));
                }
            }
            return result;
        });
        };
        // The host may have entered the gate without yet entering the tool. The worker
        // can prove no dispatch only by closing the same boundary the host must claim.
        AtomicBoolean dispatchDecided = new AtomicBoolean();
        AcxStepOutcome dispatch;
        try {
            dispatch = AcxBoundHostCall.call(plugin.gate(), session, ctx, () -> {
                var server = companion.getServer();
                return server != null && !companion.isRemoved() && companion.isAlive()
                        && NumenPlayer.findByUuid(server, companion.getUUID()) == companion;
            }, () -> {
                if ("task_stop".equals(name())) {
                    String wanted = args.has("task_id") && args.get("task_id").isJsonPrimitive()
                            ? args.get("task_id").getAsString().strip() : "";
                    TaskRecord active = CompanionTickDispatcher.currentTaskFor(companion.getUUID());
                    if (!session.ownsTask(wanted) || active == null || !wanted.equals(active.publicId())) {
                        throw new IllegalArgumentException("task_stop只允许停止本run已受理且准确匹配的task_id");
                    }
                }
                // Capture on the host thread before claiming dispatch; if the wait ends
                // during observation, the same CAS still prevents late tool entry.
                try { issuedIn.set(WorldContextStamp.capture(companion.getServer())); }
                catch (RuntimeException unavailable) { /* Missing identity cannot confirm a sync result. */ }
                if (watchBody) {
                    if (session.isCancelRequested()) {
                        return AcxStepOutcome.paused("运行已取消，未派发工具", Map.of());
                    }
                    if (ctx.isCancelRequested()) {
                        throw new IllegalStateException("执行上下文已失效，未派发工具");
                    }
                }
                if (!dispatchDecided.compareAndSet(false, true)) {
                    return AcxStepOutcome.paused("宿主等待已结束，未派发工具", Map.of());
                }
                try {
                    TaskDispatch.withCallerOwnedRecovery(companion, callId, () -> {
                        if ("rdd_report_yield".equals(name()) && tool instanceof com.dwinovo.numen.agent.tool.TrustedYieldTool y)
                            y.onTrustedHostCall(callId, args, companion, session.yieldBinding(), json -> done.complete(new HostReply(json, null)));
                        else tool.onServerCall(callId, args, companion, json -> done.complete(new HostReply(json, null)));
                    });
                } catch (TaskDispatch.BodyBusyException busy) {
                    // Complete the original future even if the worker already timed out.
                    done.complete(new HostReply(null, Result.bodyBusy(busy.blockingTaskId(), busy.getMessage())));
                }
                return AcxStepOutcome.success(Map.of());
            });
        } catch (TimeoutException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            if (dispatchDecided.compareAndSet(false, true)) {
                return Result.failed("宿主等待已结束，未派发工具: " + name());
            }
            throw new ReplyPendingAfterDispatchException("宿主可能已派发，等待原回执: " + name(), receipt);
        } catch (Throwable t) {
            // A host exception after entering the tool does not prove its effects failed.
            if (!dispatchDecided.compareAndSet(false, true)) {
                return receipt.poll().orElseThrow(() -> new ReplyPendingAfterDispatchException(
                        "工具已进入执行，异常后等待原回执: " + name()
                                + " (" + t.getClass().getSimpleName() + ")", receipt));
            }
            return Result.failed("宿主派发失败: " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()) + " (tool=" + name() + ")");
        }

        if (dispatch.status() == com.dwinovo.numen.acx.api.AcxStatus.PAUSED) {
            throw new CancelledBeforeDispatchException(dispatch.message());
        }

        try {
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (!done.isDone() && !ctx.isCancelRequested()) {
                if (receipt.poll().isPresent()) break;
                if (System.nanoTime() >= until) throw new TimeoutException("original host reply pending");
                Thread.sleep(50L);
            }
        } catch (TimeoutException e) {
            throw new ReplyPendingAfterDispatchException("等待原工具回执超时: " + name(), receipt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReplyPendingAfterDispatchException("等待原工具回执被中断: " + name(), receipt);
        }
        return receipt.poll().orElseThrow(() ->
                new ReplyPendingAfterDispatchException("原工具回执未能确认结果: " + name(), receipt));
    }

    /** runSync settles through TaskResultPayload, not the supplied callback. Read only its
     * exact original terminal on the host thread; an idle slot or another call proves nothing. */
    private Optional<String> confirmOriginalSyncTerminal(AcxSessionManager.Session session,
            NumenPlayer original, AcxTool.AcxCallContext ctx, String callId, WorldContextStamp.Token issued) {
        if (issued == null || issued.epoch() <= 0 || issued.identity() == null
                || issued.identity().getOrDefault("world_id", "").isBlank()
                || issued.identity().getOrDefault("server_session", "").isBlank()
                || ctx.isCancelRequested()) return Optional.empty();
        AtomicReference<String> result = new AtomicReference<>();
        try {
            AcxBoundHostCall.call(plugin.gate(), session, ctx, () -> {
                var server = original.getServer();
                return session.bodyToken() == original && server != null && original.isAlive() && !original.isRemoved()
                        && NumenPlayer.findByUuid(server, original.getUUID()) == original;
            }, () -> {
                var server = original.getServer();
                if (!issued.equals(WorldContextStamp.capture(server))) return AcxStepOutcome.success(Map.of());
                TaskRecord terminal = CompanionTickDispatcher.lastFinishedTaskFor(original.getUUID());
                // runSync settles through TaskResultPayload and never calls the callback; every
                // real terminal (SUCCESS/FAILED/TIMEOUT/CANCELLED) is the original receipt. A missing
                // result stays UNKNOWN and must not complete the future.
                if (terminal != null && callId.equals(terminal.getToolCallId()) && name().equals(terminal.getToolName())
                        && issued.equals(terminal.identityToken()) && terminal.getState().isTerminal()
                        && terminal.getResult() != null) result.set(terminal.getResult().toJson());
                return AcxStepOutcome.success(Map.of());
            });
        } catch (Exception unavailable) { /* Keep the original receipt UNKNOWN; never redispatch. */ }
        return Optional.ofNullable(result.get());
    }

    /** Map a confirmed callback. Missing, malformed or unidentifiable replies prove no outcome. */
    @SuppressWarnings("unchecked")
    private static Optional<Result> mapReply(String reply, String toolName) {
        if (reply == null || reply.isBlank()) return Optional.empty();
        JsonObject obj;
        try {
            JsonElement el = JsonParser.parseString(reply);
            if (!el.isJsonObject()) return Optional.empty();
            obj = el.getAsJsonObject();
        } catch (RuntimeException e) {
            return Optional.empty();
        }

        if (!obj.has("success")) {
            // 裸 JSON（get_self_status 等）：整个对象就是产出
            Map<String, Object> bare = GSON.fromJson(obj, Map.class);
            return Optional.of(Result.completed(bare, "ok"));
        }

        JsonElement flag = obj.get("success");
        if (!flag.isJsonPrimitive() || !flag.getAsJsonPrimitive().isBoolean()) return Optional.empty();
        boolean success = flag.getAsBoolean();
        String message = obj.has("message") && obj.get("message").isJsonPrimitive()
                ? obj.get("message").getAsString() : "";

        if (obj.has("timed_out") && obj.get("timed_out").isJsonPrimitive() && obj.get("timed_out").getAsBoolean()) {
            return Optional.of(Result.failed("宿主任务超时" + (message.isBlank() ? "" : ": " + message)));
        }
        if (obj.has("interrupted") && obj.get("interrupted").isJsonPrimitive() && obj.get("interrupted").getAsBoolean()) {
            return Optional.of(Result.failed("宿主任务被中断" + (message.isBlank() ? "" : ": " + message)));
        }

        Map<String, Object> data = obj.has("data") && obj.get("data").isJsonObject()
                ? GSON.fromJson(obj.get("data"), Map.class) : Map.of();

        if (!success) {
            return Optional.of(Result.failed(message.isBlank() ? "工具失败: " + toolName : message));
        }
        if (Boolean.TRUE.equals(data.get("async"))) {
            if (!(data.get("task_id") instanceof String taskId) || taskId.isBlank()) return Optional.empty();
            return Optional.of(Boolean.TRUE.equals(data.get("standing"))
                    ? Result.acceptedStanding(taskId, data, message)
                    : Result.accepted(taskId, data, message));
        }
        return Optional.of(Result.completed(data, message));
    }

    /**
     * 完成探针（契约 ACX-P1）：按任务号查终态，**槽空不再等于成功，槽被占也不删账**。
     *
     * <p>三种槽状态：槽里还有**本任务** → {@code running}；槽空 → 按 id 查终态表（有记录按六分支映射，
     * 查不到 → {@code unknown}）；槽被**别的任务**占用 → 先按 id 查终态表（acb12b3：查到照常映射，
     * 查不到 → {@code unknown}，**不再直接 failed** —— 那会把真正成功的任务报成失败并删账）。
     */
    static AcxCompletionProbe probeFor(AcxPlugin plugin) {
        return new AcxCompletionProbe() {
            @Override
            public Snapshot probe(String taskId) {
                return Snapshot.unknown("缺少原始运行上下文");
            }

            @Override
            public Snapshot probe(String taskId, AcxTool.AcxCallContext ctx) {
                AcxSessionManager.Session session = plugin.sessionFor(ctx);
                if (session == null || !(session.bodyToken() instanceof NumenPlayer original)) {
                    return Snapshot.unknown("找不到原始会话同伴");
                }
                if (!session.ownsTask(taskId)) {
                    return Snapshot.unknown("该任务号没有此运行的受理回执");
                }
                return probeFor(() -> original).probe(taskId);
            }

            @Override
            public AcxBodyAction.Snapshot bodyAction(AcxBodyAction.Ref wanted, AcxTool.AcxCallContext ctx) {
                AcxSessionManager.Session session = plugin.sessionFor(ctx);
                if (wanted == null || !wanted.complete() || session == null || !session.ownsTask(wanted.taskId())
                        || !(session.bodyToken() instanceof NumenPlayer original)
                        || !session.ownerId().equals(wanted.companion())) return AcxBodyAction.Snapshot.unknown();

                AtomicReference<AcxBodyAction.Snapshot> answer = new AtomicReference<>(AcxBodyAction.Snapshot.unknown());
                try {
                    plugin.gate().call(() -> {
                        var server = original.getServer();
                        if (session.bodyToken() != original || !session.ownsTask(wanted.taskId())
                                || server == null || !server.isSameThread() || original.isRemoved() || !original.isAlive()
                                || NumenPlayer.findByUuid(server, original.getUUID()) != original
                                || !wanted.equals(bodyRef(wanted.taskId(), wanted.callId(), wanted.tool(), original.getUUID().toString(),
                                        WorldContextStamp.capture(server)))) return AcxStepOutcome.success(Map.of());

                        TaskRecord last = CompanionTickDispatcher.finishedTaskFor(original.getUUID(), wanted.taskId());
                        if (last != null) {
                            var state = last.getState();
                            if (state != null && state.isTerminal()) {
                                var evidence = AcxBodyAction.fromTask(wanted, bodyRef(last, original), state.name());
                                var result = last.getResult();
                                if (evidence.state() == AcxBodyAction.State.DONE && result != null) {
                                    evidence = new AcxBodyAction.Snapshot(evidence.action(), evidence.state(),
                                            evidence.progress(), evidence.route(), result.data());
                                }
                                answer.set(evidence);
                            }
                        } else {
                            TaskRecord active = CompanionTickDispatcher.currentTaskFor(original.getUUID());
                            if (active != null && active.getState() == TaskState.RUNNING) {
                                AcxBodyAction.Ref ref = bodyRef(active, original);
                                if (wanted.equals(ref)) {
                                    // 2026-11-19: only the same RUNNING current task may contribute its
                                    // fresh sample; absent/stale keeps the old UNKNOWN body_action.
                                    answer.set(AcxBodyAction.fromTask(wanted, ref, null));
                                }
                            }
                        }
                        return AcxStepOutcome.success(Map.of());
                    });
                    return answer.get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return AcxBodyAction.Snapshot.unknown();
                } catch (Exception unavailable) {
                    return AcxBodyAction.Snapshot.unknown();
                }
            }
        };
    }

    private static AcxBodyAction.Ref bodyRef(TaskRecord task, NumenPlayer companion) {
        return bodyRef(task.publicId(), task.getToolCallId(), task.getToolName(), companion.getUUID().toString(), task.identityToken());
    }

    private static AcxBodyAction.Ref bodyRef(String task, String call, String tool, String companion,
                                            WorldContextStamp.Token token) {
        Map<String, String> ids = token == null ? null : token.identity();
        return new AcxBodyAction.Ref(task, call, tool, companion,
                ids == null ? null : ids.get("world_id"), ids == null ? null : ids.get("server_session"),
                token == null ? 0 : token.epoch());
    }

    public static AcxCompletionProbe probeFor(Supplier<NumenPlayer> companionSupplier) {
        // Capture once: a caller changing its supplier must not retarget this probe.
        NumenPlayer original = companionSupplier == null ? null : companionSupplier.get();
        return taskId -> {
            NumenPlayer p = original;
            if (taskId == null || taskId.isBlank()) {
                return AcxCompletionProbe.Snapshot.unknown("缺少准确任务号，不能探测其它任务");
            }
            if (p == null) {
                return AcxCompletionProbe.Snapshot.unknown("无绑定同伴，无法探测");
            }
            try {
                TaskRecord current = CompanionTickDispatcher.currentTaskFor(p.getUUID());
                if (current != null && taskId.equals(current.publicId())) {
                    return AcxCompletionProbe.Snapshot.running("仍在执行: " + taskId);
                }
                // The slot may be empty or already occupied by a different action. Only
                // the exact terminal record for this body and task can settle this call.
                TaskRecord terminal = CompanionTickDispatcher.finishedTaskFor(p.getUUID(), taskId);
                if (terminal == null && current != null) {
                    return AcxCompletionProbe.Snapshot.unknown(
                            "槽被 " + current.publicId() + " 占用，本任务终态未知（保留挂账等待重试）");
                }
                // 2026-11-25: the terminal record already knows why it failed (TaskResult.message,
                // e.g. BuildClearSafety's falling-block refusal). Pass it to the failed snapshot so the
                // model sees the real reason instead of blind-retrying; identity checks inside map()
                // still run before any message is included, and SUCCESS/CANCELLED keep their own branches.
                var snapshot = AcxFinishedTask.map(taskId, terminal == null ? null : terminal.publicId(),
                        terminal == null || terminal.getState() == null ? null : terminal.getState().name(),
                        terminal == null || terminal.getResult() == null ? null : terminal.getResult().message());
                if (snapshot.state() != AcxCompletionProbe.State.DONE) return snapshot;
                var result = terminal.getResult();
                return result == null ? snapshot : AcxCompletionProbe.Snapshot.done(result.data(), snapshot.message());
            } catch (Throwable t) {
                return AcxCompletionProbe.Snapshot.unknown("探测异常: " + t.getClass().getSimpleName());
            }
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

}
