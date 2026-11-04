package com.dwinovo.numen.plugins.worldstate.tool;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.plugins.worldstate.WorldStateMonitor;
import com.dwinovo.numen.plugins.worldstate.WorldStateService;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.worldstate.core.model.Bounds;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.ObservationPayload;
import com.dwinovo.numen.worldstate.core.observation.payload.EntityCountPayload;
import com.dwinovo.numen.worldstate.core.observation.payload.EnvironmentPayload;
import com.dwinovo.numen.worldstate.core.query.ReadResult;
import com.google.gson.JsonArray;
import com.dwinovo.numen.worldstate.core.refresh.RefreshResult;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * ★ 世界观测统一入口 —— 「读事实 / 取事实」。
 *
 * <pre>
 * world_state action=read    topic=entities  dimension=minecraft:overworld  x1..z2 ...
 * world_state action=refresh topic=environment dimension=minecraft:overworld
 * </pre>
 *
 * <h2>读与取，接口上明确分开</h2>
 * <ul>
 *   <li>{@code read} —— 只读已有观测，<b>不扫描世界</b>；没有就明确返回没有。</li>
 *   <li>{@code refresh} —— 主动到世界采集最新信息，更新观测并返回本次结果。</li>
 * </ul>
 * 两者都<b>不修改游戏世界</b>；刷新只更新本模块自己的缓存/存档。
 *
 * <p><b>刷新失败 ≠ 上一次观测被清空</b>：失败时 read 仍返回旧快照 + 时间，
 * 并把当前内容标为"无法确认"；只有完整检查过范围才敢说"这里有 0 只"。
 */
public final class WorldStateTool implements NumenTool {

    private final WorldStateService service;

    public WorldStateTool(WorldStateService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return "world_state";
    }

    @Override
    public String description() {
        return "★ 世界观测统一入口：读事实 / 取事实（都不改世界）。\n"
                + "action=read —— 只读已有观测，不扫描世界；没有就明确返回没有。返回带时间、覆盖、新鲜度。\n"
                + "action=refresh —— 主动到世界采集最新信息并更新观测；失败时明确报原因，且不清空旧观测。\n"
                + "topic=environment —— 维度/游戏时间/天气。\n"
                + "topic=entities —— 范围内的动物/实体计数，需要 x1/y1/z1/x2/y2/z2；可选 entity_type"
                + "（如 minecraft:sheep）或 category=passive/all。区块未加载不强制加载，也不误报 0。\n"
                + "topic=blocks —— 范围内方块计数，需要 x1..z2；可选 block=id 或 id[属性=值]（逗号分隔多个），"
                + "samples=N 可带回最多 N 个命中坐标。\n"
                + "topic=containers —— 容器内容，需要 container=block:x,y,z 或 player:<uuid>。\n"
                + "需要难度/游戏模式/玩家列表/光照/自身状态等诊断事实时，用 world_probe action=list。\n"
                + "典型用法：先 refresh 采集，再 read 读取；要求交易/取物/完工前再 refresh 一次。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        // schema 抽到 WorldStateSchema（纯类，无 MC 依赖）——042 R1 要求它能被离线测试钉住
        return WorldStateSchema.build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        try {
            if (self == null || self.serverLevel() == null || self.getServer() == null) {
                reply.accept(TaskResult.fail("world_state: not on a server level").toJson());
                return;
            }
            String action = args.has("action")
                    ? args.get("action").getAsString().toLowerCase(Locale.ROOT) : "";
            // 增量读不需要 topic/范围，必须先于主题校验
            if ("changes".equals(action)) {
                handleChanges(args, reply);
                return;
            }
            Topic topic = Topic.byId(args.has("topic") ? args.get("topic").getAsString() : null);
            if (topic == null) {
                reply.accept(TaskResult.fail(
                        "world_state: topic is required for action=read|refresh"
                                + " (environment|entities|blocks|containers)").toJson());
                return;
            }
            String dimension = args.has("dimension") && !args.get("dimension").isJsonNull()
                    ? args.get("dimension").getAsString()
                    : self.serverLevel().dimension().location().toString();

            ObservationRequest request = buildRequest(args, topic, dimension);
            if (request == null) {
                // 042 R1：缺字段必须说清是哪个字段，别只回"参数非法"
                reply.accept(TaskResult.fail(missingFieldMessage(topic)).toJson());
                return;
            }

            switch (action) {
                case "read" -> handleRead(self, request, reply);
                case "refresh" -> handleRefresh(self, request, reply);
                default -> reply.accept(TaskResult.fail(
                        "world_state: action must be read|refresh|changes").toJson());
            }
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("world_state error: " + ex.getMessage()).toJson());
        }
    }

    /** 缺字段的明确报错（042 R1：非法 selector / 缺字段要能一眼看出来）。 */
    private String missingFieldMessage(Topic topic) {
        return switch (topic) {
            case CONTAINERS -> "world_state: containers needs container=block:x,y,z or player:<uuid> (got empty)";
            case ENTITIES, BLOCKS -> "world_state: " + topic.id() + " needs a box: x1,y1,z1,x2,y2,z2 (got empty or invalid)";
            default -> "world_state: invalid parameters for topic " + topic.id();
        };
    }

    private ObservationRequest buildRequest(JsonObject args, Topic topic, String dimension) {
        return switch (topic) {
            case ENVIRONMENT -> ObservationRequest.environment(dimension);
            case ENTITIES -> {
                Bounds box = boundsFrom(args);
                if (box == null) yield null;
                String type = optionalString(args, "entity_type");
                String category = optionalString(args, "category");
                yield ObservationRequest.entities(dimension, box, type, category);
            }
            case BLOCKS -> {
                Bounds box = boundsFrom(args);
                if (box == null) yield null;
                int samples = 0;
                if (args.has("samples") && !args.get("samples").isJsonNull()) {
                    try {
                        samples = Math.max(0, Math.min(32, args.get("samples").getAsInt()));
                    } catch (RuntimeException ignored) {
                        samples = 0;
                    }
                }
                yield ObservationRequest.blocks(dimension, box, optionalString(args, "block"), samples);
            }
            case CONTAINERS -> {
                String target = optionalString(args, "container");
                if (target == null || target.isBlank()) yield null;
                try {
                    yield ObservationRequest.containers(dimension, target);
                } catch (IllegalArgumentException e) {
                    yield null;
                }
            }
            // facilities 尚未接入采集器：明确不支持，别假装成功。
            default -> null;
        };
    }

    /**
     * 增量读（041 U4c）：`action=changes after=<游标> [topics=a,b] [limit=N]`。
     *
     * <p><b>gap=true 必须让调用方看得见</b>：据此做一次全量 read，而不是把空列表当"没变"。
     */
    private void handleChanges(JsonObject args, Consumer<String> reply) {
        long after = 0L;
        int limit = 64;
        try {
            if (args.has("after") && !args.get("after").isJsonNull()) {
                after = Math.max(0L, args.get("after").getAsLong());
            }
            if (args.has("limit") && !args.get("limit").isJsonNull()) {
                limit = Math.max(1, Math.min(256, args.get("limit").getAsInt()));
            }
        } catch (RuntimeException ignored) {
            // 用默认值
        }
        java.util.Set<String> topics = null;
        if (args.has("topics") && !args.get("topics").isJsonNull()) {
            String raw = args.get("topics").getAsString();
            if (raw != null && !raw.isBlank()) {
                topics = new java.util.HashSet<>();
                for (String t : raw.split(",")) {
                    if (!t.isBlank()) topics.add(t.trim());
                }
            }
        }
        var result = service.changeLog().after(after, topics, limit);
        JsonObject data = new JsonObject();
        data.addProperty("after", after);
        data.addProperty("next_cursor", result.nextCursor());
        data.addProperty("gap", result.gap());
        data.addProperty("oldest_retained_revision", result.oldestRetainedRevision());
        data.addProperty("count", result.changes().size());
        JsonArray arr = new JsonArray();
        for (var c : result.changes()) {
            JsonObject o = new JsonObject();
            o.addProperty("revision", c.revision());
            o.addProperty("topic", c.topic());
            o.addProperty("key", c.key());
            o.addProperty("kind", c.kind().name());
            o.addProperty("epoch_ms", c.epochMs());
            arr.add(o);
        }
        data.add("changes", arr);
        if (result.gap()) {
            data.addProperty("note", "历史断档：请做一次全量 read 重新同步，不要把空列表当成'没变化'");
        }
        reply.accept(TaskResult.ok(data.toString()).toJson());
    }

    private void handleRead(NumenPlayer self, ObservationRequest request, Consumer<String> reply) {
        ReadResult result = service.read(self.getServer(), request);
        Map<String, Object> data = baseData(request);
        data.put("present", result.present());
        data.put("freshness", result.freshness().name());
        data.put("can_prove_absence", result.canProveAbsence());
        if (result.present()) {
            data.put("age_ms", result.ageMillis());
            if (result.coverage() != null) data.put("coverage", result.coverage().name());
            data.put("source", result.observation().source());
            appendPayload(data, result.observation().payload());
        }
        WorldStateMonitor.publish("read", Map.of(
                "topic", request.topic().id(),
                "dimension", request.dimension(),
                "present", result.present(),
                "freshness", result.freshness().name()));
        reply.accept(TaskResult.ok(result.note(), data).toJson());
    }

    private void handleRefresh(NumenPlayer self, ObservationRequest request, Consumer<String> reply) {
        RefreshResult result = service.refresh(self.getServer(), request);
        Map<String, Object> data = baseData(request);
        data.put("status", result.status().name());
        data.put("elapsed_ms", result.elapsedMillis());
        if (result.success()) {
            data.put("coverage", result.observation().coverage().name());
            data.put("source", result.observation().source());
            appendPayload(data, result.observation().payload());
            WorldStateMonitor.publish("refresh", Map.of(
                    "topic", request.topic().id(),
                    "dimension", request.dimension(),
                    "status", result.status().name(),
                    "coverage", result.observation().coverage().name()));
            reply.accept(TaskResult.ok("refreshed " + request.describe() + " ("
                    + result.observation().coverage() + ")", data).toJson());
        } else {
            data.put("reason", result.reason().name());
            WorldStateMonitor.publish("refresh_failed", Map.of(
                    "topic", request.topic().id(),
                    "dimension", request.dimension(),
                    "reason", result.reason().name(),
                    "message", String.valueOf(result.message())));
            reply.accept(TaskResult.fail("refresh " + request.describe() + " failed: "
                    + result.reason() + " (" + result.message() + ")", data).toJson());
        }
    }

    private static Map<String, Object> baseData(ObservationRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("topic", request.topic().id());
        data.put("dimension", request.dimension());
        return data;
    }

    private static void appendPayload(Map<String, Object> data, ObservationPayload payload) {
        if (payload instanceof EnvironmentPayload env) {
            data.put("day_time", env.dayTime());
            data.put("weather", env.weather());
            data.put("raining", env.raining());
            data.put("thundering", env.thundering());
        } else if (payload instanceof com.dwinovo.numen.worldstate.core.observation.payload.BlockCountPayload blocks) {
            data.put("counts", blocks.counts());
            data.put("scanned_columns", blocks.scannedColumns());
            data.put("skipped_columns", blocks.skippedColumns());
            if (!blocks.samplePositions().isEmpty()) data.put("samples", blocks.samplePositions());
        } else if (payload instanceof com.dwinovo.numen.worldstate.core.observation.payload.ContainerPayload container) {
            data.put("container", container.target());
            data.put("items", container.items());
            data.put("slots", container.slots());
            data.put("total_items", container.totalItems());
        } else if (payload instanceof EntityCountPayload counts) {
            data.put("count", counts.count());
            if (counts.entityType() != null) {
                data.put("entity_type", counts.entityType());
            } else {
                data.put("category", counts.category());
                if (!counts.typeCounts().isEmpty()) data.put("type_counts", counts.typeCounts());
            }
            data.put("scanned_columns", counts.scannedColumns());
            data.put("skipped_columns", counts.skippedColumns());
        }
    }

    private static Bounds boundsFrom(JsonObject args) {
        if (!has(args, "x1") || !has(args, "y1") || !has(args, "z1")
                || !has(args, "x2") || !has(args, "y2") || !has(args, "z2")) {
            return null;
        }
        return Bounds.of(args.get("x1").getAsInt(), args.get("y1").getAsInt(), args.get("z1").getAsInt(),
                args.get("x2").getAsInt(), args.get("y2").getAsInt(), args.get("z2").getAsInt());
    }

    private static boolean has(JsonObject args, String key) {
        return args.has(key) && !args.get(key).isJsonNull();
    }

    private static String optionalString(JsonObject args, String key) {
        return has(args, key) ? args.get(key).getAsString() : null;
    }
}
