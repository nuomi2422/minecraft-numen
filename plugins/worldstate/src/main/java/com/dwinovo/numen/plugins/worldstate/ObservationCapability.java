package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.api.observation.WorldObservationService;
import com.dwinovo.numen.worldstate.core.model.Freshness;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.payload.EnvironmentPayload;
import com.dwinovo.numen.worldstate.core.query.ReadResult;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;

import net.minecraft.server.MinecraftServer;

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

            // 注意：world-state-core 的 Coverage 与本接口的 Coverage 是两个枚举（接口成员名会被继承，
            // 所以这里的 core 枚举必须写全限定名，否则编译期会解析到接口那一套）。
            com.dwinovo.numen.worldstate.core.model.Coverage cov = rr.coverage() == null
                    ? com.dwinovo.numen.worldstate.core.model.Coverage.UNLOADED : rr.coverage();
            // 注意：core 的 Coverage 与 API 的 Coverage 是两套枚举，这里做显式映射（不猜、不合并）。
            Coverage out = switch (cov) {
                case COMPLETE -> Coverage.FULL;
                case PARTIAL -> Coverage.PARTIAL;
                case UNLOADED -> Coverage.UNKNOWN;
            };
            Status status = out == Coverage.FULL && !stale ? Status.OK : Status.PARTIAL;
            String reason = status == Status.PARTIAL
                    ? (stale ? "observation is " + rr.freshness() : "coverage is " + cov)
                    : null;
            long observedTick = rr.observation().gameTick();
            return new Response(status, request.topic(), snapshotId(rr), observedTick, out, reason, List.of(item));
        } catch (RuntimeException ex) {
            // 契约：实现不得抛异常
            return Response.error(request.topic(), "read failed: " + ex);
        }
    }

    @Override
    public Response refresh(Request request) {
        if (request == null) {
            return Response.error("", "missing request");
        }
        Topic topic = Topic.byId(request.topic());
        if (topic != null && topic != Topic.ENVIRONMENT) {
            return Response.error(request.topic(), "topic not wired yet: " + topic.id());
        }
        MinecraftServer mc = server.get();
        if (mc == null) {
            return Response.error(request.topic(), "no server");
        }
        try {
            var result = host.refresh(mc, ObservationRequest.environment(request.dimension()));
            if (result != null && !result.success()) {
                return Response.error(request.topic(), "refresh failed: " + result.message()
                        + " (" + result.reason() + ")");
            }
            return read(request);
        } catch (RuntimeException ex) {
            return Response.error(request.topic(), "refresh failed: " + ex);
        }
    }

    private static String snapshotId(ReadResult rr) {
        try {
            return rr.observation().epochMs() + "/" + rr.observation().gameTick();
        } catch (RuntimeException ignored) {
            return "";
        }
    }
}
