package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.api.observation.WorldObservationService;
import com.dwinovo.numen.worldstate.core.model.Freshness;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.payload.EnvironmentPayload;
import com.dwinovo.numen.worldstate.core.query.ReadResult;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;

import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 把宿主已有的观测服务接成公开的 {@link WorldObservationService}（039 U1 后半）。
 *
 * <p><b>只读</b>：本能力只调 {@code read}（读缓存），**不**顺手 refresh。需要新数据由调用方
 * 显式刷新——039 §6 明令禁止"读失败就同步扫世界兜底"。
 *
 * <p>本轮只接 {@code environment} 一个主题；其它主题明确返回 ERROR（未接线），不假装成功。
 */
public final class ObservationCapability implements WorldObservationService {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(ObservationCapability.class);

    private final WorldStateService host;
    private final Supplier<MinecraftServer> server;

    public ObservationCapability(WorldStateService host, Supplier<MinecraftServer> server) {
        this.host = host;
        this.server = server;
    }

    @Override
    public com.dwinovo.numen.api.observation.ChangesV2Response changes(
            com.dwinovo.numen.api.observation.ChangesQuery query) {
        try {
            return VersionedChangesMapper.map(query, host.changeFeedV2());
        } catch (RuntimeException e) {
            return com.dwinovo.numen.api.observation.ChangesV2Response.error("versioned changes failed: " + e);
        }
    }

    @Override
    public ChangesResponse changes(long afterRevision, java.util.List<String> topics, int limit) {
        try {
            var log = host.changeLog();
            if (log == null) return ChangesResponse.error("change log not configured");
            var r = log.after(afterRevision,
                    topics == null || topics.isEmpty() ? null : new java.util.HashSet<>(topics), limit);
            java.util.List<Change> out = new java.util.ArrayList<>();
            for (var c : r.changes()) {
                out.add(new Change(c.revision(), c.topic(), c.key(), c.kind().name(), c.epochMs()));
            }
            return new ChangesResponse(Status.OK, out, r.nextCursor(), r.gap(),
                    r.oldestRetainedRevision(), null);
        } catch (RuntimeException e) {
            return ChangesResponse.error("changes failed: " + e);
        }
    }

    /**
     * 取水位：**必须在快照读取之前**取。
     *
     * <p>先读快照再取水位的话，两次之间写入的事件 streamSeq ≤ 水位，会被 changes(after=水位) 过滤掉——
     * 正是 R3-3 要防的夹缝。先取水位最多导致"同一个变化既在快照里又在增量里"（重复，消费者按游标去重），
     * 不会漏。代价是重复，收益是不丢。
     */
    private long watermark() {
        try {
            var feed = host.changeFeedV2();
            if (feed == null) {
                LOG.warn("[worldstate] change feed 缺席，watermark 记 0（消费者会做一次全量增量读）");
                return 0L;
            }
            return feed.watermark();
        } catch (RuntimeException e) {
            // 0 会让 changes(after=0) 全量扫：安全但重，而且静默——打一条，别让"取水位失败"和"水位真是 0"长得一样
            LOG.warn("[worldstate] 取 watermark 失败，回退 0：{}", e.toString());
            return 0L;
        }
    }

    @Override
    public Response read(Request request) {
        if (request == null) {
            return Response.error("", "missing request");
        }
        long wm = watermark();   // 先取水位，再读快照（见 watermark() 注释）
        Topic topic = Topic.byId(request.topic());
        if (topic == null) {
            return Response.error(request.topic(), "unknown topic: " + request.topic());
        }
        if (topic == Topic.BLOCKS) {
            return readBlocks(request, wm);
        }
        if (topic == Topic.CONTAINERS) {
            return readContainer(request, wm);
        }
        if (topic == Topic.ENTITIES) {
            return readEntities(request, wm);
        }
        if (topic != Topic.ENVIRONMENT) {
            return Response.error(request.topic(), "topic not wired yet: " + topic.id());
        }
        MinecraftServer mc = server.get();
        if (mc == null) {
            return Response.error(request.topic(), "no server");
        }
        try {
            ReadResult rr = host.read(mc, ObservationRequest.environment(request.dimension()));
            if (!rr.present()) {
                // 没有观测 ≠ 世界是空的：明确 PENDING，让调用方决定要不要刷新
                return new Response(Status.PENDING, request.topic(), "", 0L, Coverage.UNKNOWN,
                        rr.note() == null ? "no observation yet; refresh first" : rr.note(), List.of(), wm);
            }
            if (!(rr.observation().payload() instanceof EnvironmentPayload env)) {
                return Response.error(request.topic(), "unexpected payload type");
            }
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("dimension", env.dimension());
            values.put("day_time", env.dayTime());
            values.put("weather", env.weather());
            values.put("raining", env.raining());
            values.put("thundering", env.thundering());

            boolean stale = rr.freshness() == Freshness.STALE || rr.freshness() == Freshness.HISTORICAL;
            Item item = stale
                    ? new Item(values, Set.of(), values.keySet())   // 有值但过期：全部标 stale，不许当当前事实
                    : Item.of(values);

            // 状态判定统一走 ObservationMap（纯函数、可离线测）：覆盖不全或过期都不许 OK。
            var mapped = ObservationMap.map(rr.coverage(), rr.freshness(), true);
            Coverage out = mapped.coverage();
            Status status = mapped.status();
            String reason = mapped.reason();
            long observedTick = rr.observation().gameTick();
            return new Response(status, request.topic(), snapshotId(rr), observedTick, out, reason, List.of(item), wm);
        } catch (RuntimeException ex) {
            // 契约：实现不得抛异常
            return Response.error(request.topic(), "read failed: " + ex);
        }
    }

    /**
     * blocks 主题：selector 里必须有 {@code box=x1,y1,z1,x2,y2,z2} 与 {@code blocks=a,b}。
     * 只读缓存，不刷新。
     */
    private Response readBlocks(Request request, long wm) {
        MinecraftServer mc = server.get();
        if (mc == null) return Response.error(request.topic(), "no server");
        ObservationRequest req = blocksRequest(request);
        if (req == null) {
            return Response.error(request.topic(), "blocks needs selector box=… and blocks=…");
        }
        ReadResult rr = host.read(mc, req);
        if (!rr.present()) {
            return new Response(Status.PENDING, request.topic(), "", 0L, Coverage.UNKNOWN,
                    rr.note() == null ? "no observation yet; refresh first" : rr.note(), List.of(), wm);
        }
        if (!(rr.observation().payload() instanceof
                com.dwinovo.numen.worldstate.core.observation.payload.BlockCountPayload payload)) {
            return Response.error(request.topic(), "unexpected payload type");
        }
        boolean stale = rr.freshness() == Freshness.STALE || rr.freshness() == Freshness.HISTORICAL;
        List<Item> items = new ArrayList<>();
        for (var e : payload.counts().entrySet()) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("block", e.getKey());
            values.put("count", e.getValue());
            if (!payload.samplePositions().isEmpty()) {
                List<String> mine = new ArrayList<>();
                for (String s : payload.samplePositions()) {
                    if (s.startsWith(e.getKey() + "@")) mine.add(s.substring(e.getKey().length() + 1));
                }
                values.put("samples", mine);
            }
            items.add(stale ? new Item(values, Set.of(), values.keySet()) : Item.of(values));
        }
        var mapped = ObservationMap.map(rr.coverage(), rr.freshness(), true);
        return new Response(mapped.status(), request.topic(), snapshotId(rr),
                rr.observation().gameTick(), mapped.coverage(), mapped.reason(), items, wm);
    }

    @Override
    public Response refresh(Request request) {
        if (request == null) {
            return Response.error("", "missing request");
        }
        Topic topic = Topic.byId(request.topic());
        if (topic == null) {
            return Response.error(request.topic(), "unknown topic: " + request.topic());
        }
        if (topic != Topic.ENVIRONMENT && topic != Topic.BLOCKS
                && topic != Topic.CONTAINERS && topic != Topic.ENTITIES) {
            return Response.error(request.topic(), "topic not wired yet: " + topic.id());
        }
        MinecraftServer mc = server.get();
        if (mc == null) {
            return Response.error(request.topic(), "no server");
        }
        ObservationRequest req;
        if (topic == Topic.BLOCKS) {
            ObservationRequest built = blocksRequest(request);
            if (built == null) {
                return Response.error(request.topic(), "blocks needs selector box=… and blocks=…");
            }
            req = built;
        } else if (topic == Topic.ENTITIES) {
            ObservationRequest built = entitiesRequest(request);
            if (built == null) {
                return Response.error(request.topic(), "entities needs selector box=x1,y1,z1,x2,y2,z2");
            }
            req = built;
        } else if (topic == Topic.CONTAINERS) {
            String target = request.selector().get("container");
            if (target == null || target.isBlank()) {
                return Response.error(request.topic(), "containers needs selector container=block:x,y,z");
            }
            try {
                req = ObservationRequest.containers(request.dimension(), target);
            } catch (IllegalArgumentException e) {
                return Response.error(request.topic(), e.getMessage());
            }
        } else {
            req = ObservationRequest.environment(request.dimension());
        }
        try {
            var result = host.refresh(mc, req);
            // 039 诊断（限频在调用方）：只报结果，不刷屏
            com.dwinovo.numen.plugins.worldstate.WorldStateMonitor.publish("cap_refresh", Map.of(
                    "topic", request.topic(),
                    "ok", result != null && result.success(),
                    "status", result == null ? "null" : result.status().name()));
            if (result != null && !result.success()) {
                return Response.error(request.topic(), "refresh failed: " + result.message()
                        + " (" + result.reason() + ")");
            }
            return read(request);
        } catch (RuntimeException ex) {
            return Response.error(request.topic(), "refresh failed: " + ex);
        }
    }

    /**
     * entities 主题：selector 要 {@code box=x1,y1,z1,x2,y2,z2}，可选 {@code category}/{@code type}。
     * 返回一条 item：`{count, category, type_counts}`（按类型明细可能为空表）。
     */
    private Response readEntities(Request request, long wm) {
        MinecraftServer mc = server.get();
        if (mc == null) return Response.error(request.topic(), "no server");
        var req = entitiesRequest(request);
        if (req == null) {
            return Response.error(request.topic(), "entities needs selector box=x1,y1,z1,x2,y2,z2");
        }
        ReadResult rr = host.read(mc, req);
        if (!rr.present()) {
            return new Response(Status.PENDING, request.topic(), "", 0L, Coverage.UNKNOWN,
                    rr.note() == null ? "no observation yet; refresh first" : rr.note(), List.of(), wm);
        }
        if (!(rr.observation().payload() instanceof
                com.dwinovo.numen.worldstate.core.observation.payload.EntityCountPayload payload)) {
            return Response.error(request.topic(), "unexpected payload type");
        }
        var mapped = ObservationMap.map(rr.coverage(), rr.freshness(), true);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("count", payload.count());
        if (payload.entityType() != null) {
            values.put("entity_type", payload.entityType());
        } else {
            values.put("category", payload.category());
            if (!payload.typeCounts().isEmpty()) values.put("type_counts", payload.typeCounts());
        }
        Item item = mapped.stale() ? new Item(values, Set.of(), values.keySet()) : Item.of(values);
        return new Response(mapped.status(), request.topic(), snapshotId(rr),
                rr.observation().gameTick(), mapped.coverage(), mapped.reason(), List.of(item), wm);
    }

    private static ObservationRequest entitiesRequest(Request request) {
        String boxRaw = request.selector().get("box");
        if (boxRaw == null) return null;
        String[] parts = boxRaw.split(",");
        if (parts.length != 6) return null;
        int[] c = new int[6];
        try {
            for (int i = 0; i < 6; i++) c[i] = Integer.parseInt(parts[i].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        var bounds = new com.dwinovo.numen.worldstate.core.model.Bounds(c[0], c[1], c[2], c[3], c[4], c[5]);
        return ObservationRequest.entities(request.dimension(), bounds,
                request.selector().get("type"), request.selector().getOrDefault("category", "all"));
    }

    /**
     * containers 主题：selector 要 {@code container=block:x,y,z}。
     * 每个物品一条 {@code items[{item,count}]}；空容器 = 空表 + COMPLETE（"已知为空"），
     * 读不到 = ERROR/PENDING（"不知道"），两者不混。
     */
    private Response readContainer(Request request, long wm) {
        MinecraftServer mc = server.get();
        if (mc == null) return Response.error(request.topic(), "no server");
        String target = request.selector().get("container");
        if (target == null || target.isBlank()) {
            return Response.error(request.topic(), "containers needs selector container=block:x,y,z");
        }
        var req = ObservationRequest.containers(request.dimension(), target);
        ReadResult rr = host.read(mc, req);
        if (!rr.present()) {
            return new Response(Status.PENDING, request.topic(), "", 0L, Coverage.UNKNOWN,
                    rr.note() == null ? "no observation yet; refresh first" : rr.note(), List.of(), wm);
        }
        if (!(rr.observation().payload() instanceof
                com.dwinovo.numen.worldstate.core.observation.payload.ContainerPayload payload)) {
            return Response.error(request.topic(), "unexpected payload type");
        }
        var mapped = ObservationMap.map(rr.coverage(), rr.freshness(), true);
        List<Item> items = new ArrayList<>();
        for (var e : payload.items().entrySet()) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("item", e.getKey());
            values.put("count", e.getValue());
            items.add(mapped.stale() ? new Item(values, Set.of(), values.keySet()) : Item.of(values));
        }
        return new Response(mapped.status(), request.topic(), snapshotId(rr),
                rr.observation().gameTick(), mapped.coverage(), mapped.reason(), items, wm);
    }

    /** 把 selector 的 box/blocks 组成 core 的方块请求；缺字段返回 null（不猜默认范围）。 */
    private static ObservationRequest blocksRequest(Request request) {
        String boxRaw = request.selector().get("box");
        String idsRaw = request.selector().get("blocks");
        if (boxRaw == null || idsRaw == null) return null;
        String[] parts = boxRaw.split(",");
        if (parts.length != 6) return null;
        int[] c = new int[6];
        try {
            for (int i = 0; i < 6; i++) c[i] = Integer.parseInt(parts[i].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        var bounds = new com.dwinovo.numen.worldstate.core.model.Bounds(c[0], c[1], c[2], c[3], c[4], c[5]);
        int samples = 0;
        try {
            samples = Integer.parseInt(request.selector().getOrDefault("samples", "0").trim());
        } catch (NumberFormatException ignored) {
            samples = 0;
        }
        return ObservationRequest.blocks(request.dimension(), bounds, idsRaw, Math.max(0, Math.min(32, samples)));
    }

    private static String snapshotId(ReadResult rr) {
        try {
            return rr.observation().epochMs() + "/" + rr.observation().gameTick();
        } catch (RuntimeException ignored) {
            return "";
        }
    }
}