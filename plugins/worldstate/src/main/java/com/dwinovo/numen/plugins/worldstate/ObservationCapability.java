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

    private final WorldStateService host;
    private final Supplier<MinecraftServer> server;

    public ObservationCapability(WorldStateService host, Supplier<MinecraftServer> server) {
        this.host = host;
        this.server = server;
    }

    @Override
    public Response read(Request request) {
        if (request == null) {
            return Response.error("", "missing request");
        }
        Topic topic = Topic.byId(request.topic());
        if (topic == null) {
            return Response.error(request.topic(), "unknown topic: " + request.topic());
        }
        if (topic == Topic.BLOCKS) {
            return readBlocks(request);
        }
        if (topic == Topic.CONTAINERS) {
            return readContainer(request);
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
                        rr.note() == null ? "no observation yet; refresh first" : rr.note(), List.of());
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
            return new Response(status, request.topic(), snapshotId(rr), observedTick, out, reason, List.of(item));
        } catch (RuntimeException ex) {
            // 契约：实现不得抛异常
            return Response.error(request.topic(), "read failed: " + ex);
        }
    }

    /**
     * blocks 主题：selector 里必须有 {@code box=x1,y1,z1,x2,y2,z2} 与 {@code blocks=a,b}。
     * 只读缓存，不刷新。
     */
    private Response readBlocks(Request request) {
        MinecraftServer mc = server.get();
        if (mc == null) return Response.error(request.topic(), "no server");
        ObservationRequest req = blocksRequest(request);
        if (req == null) {
            return Response.error(request.topic(), "blocks needs selector box=… and blocks=…");
        }
        ReadResult rr = host.read(mc, req);
        if (!rr.present()) {
            return new Response(Status.PENDING, request.topic(), "", 0L, Coverage.UNKNOWN,
                    rr.note() == null ? "no observation yet; refresh first" : rr.note(), List.of());
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
            items.add(stale ? new Item(values, Set.of(), values.keySet()) : Item.of(values));
        }
        var mapped = ObservationMap.map(rr.coverage(), rr.freshness(), true);
        return new Response(mapped.status(), request.topic(), snapshotId(rr),
                rr.observation().gameTick(), mapped.coverage(), mapped.reason(), items);
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
        if (topic != Topic.ENVIRONMENT && topic != Topic.BLOCKS && topic != Topic.CONTAINERS) {
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
     * containers 主题：selector 要 {@code container=block:x,y,z}。
     * 每个物品一条 {@code items[{item,count}]}；空容器 = 空表 + COMPLETE（"已知为空"），
     * 读不到 = ERROR/PENDING（"不知道"），两者不混。
     */
    private Response readContainer(Request request) {
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
                    rr.note() == null ? "no observation yet; refresh first" : rr.note(), List.of());
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
                rr.observation().gameTick(), mapped.coverage(), mapped.reason(), items);
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
        return ObservationRequest.blocks(request.dimension(), bounds, idsRaw);
    }

    private static String snapshotId(ReadResult rr) {
        try {
            return rr.observation().epochMs() + "/" + rr.observation().gameTick();
        } catch (RuntimeException ignored) {
            return "";
        }
    }
}
