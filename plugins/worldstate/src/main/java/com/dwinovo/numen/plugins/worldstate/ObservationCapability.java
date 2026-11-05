package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.api.observation.WorldObservationService;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.model.Bounds;
import com.dwinovo.numen.worldstate.core.observation.ObservationPayload;
import com.dwinovo.numen.worldstate.core.observation.payload.*;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;
import net.minecraft.server.MinecraftServer;
import java.util.*;
import java.util.function.Supplier;

/**
 * Existing world-state cache exposed through the common API (039/B1, 2026-11-05).
 * WHY: tools and plugins must not interpret freshness/identity differently. Every wired topic uses
 * ObservationResponses, including empty containers. read never scans; refresh stays explicit and
 * server-thread-only. Invalid selectors return ERROR instead of escaping before the old try block.
 * Trusted in-process API: model-facing adapters must bind the caller, not trust consumer labels.
 */
public final class ObservationCapability implements WorldObservationService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ObservationCapability.class);
    private final WorldStateService host;
    private final Supplier<MinecraftServer> server;

    public ObservationCapability(WorldStateService host, Supplier<MinecraftServer> server) {
        this.host = host;
        this.server = server;
    }

    @Override public List<String> catalog() {
        return host.topics().stream().map(Topic::id).sorted().toList();
    }

    @Override public com.dwinovo.numen.api.observation.ChangesV2Response changes(
            com.dwinovo.numen.api.observation.ChangesQuery query) {
        try { return VersionedChangesMapper.map(query, host.changeFeedV2()); }
        catch (RuntimeException e) {
            return com.dwinovo.numen.api.observation.ChangesV2Response.error("versioned changes failed: " + e);
        }
    }

    @Override public ChangesResponse changes(long afterRevision, List<String> topics, int limit) {
        try {
            var log = host.changeLog();
            if (log == null) return ChangesResponse.error("change log not configured");
            var r = log.after(afterRevision, topics == null || topics.isEmpty() ? null : new HashSet<>(topics), limit);
            var out = r.changes().stream().map(c -> new Change(c.revision(), c.topic(), c.key(), c.kind().name(), c.epochMs())).toList();
            return new ChangesResponse(Status.OK, out, r.nextCursor(), r.gap(), r.oldestRetainedRevision(), null);
        } catch (RuntimeException e) { return ChangesResponse.error("changes failed: " + e); }
    }

    /** Without a common store/feed lock, watermark MUST precede the read: repeat is safe, omission is not. */
    private long watermark() {
        try { return host.changeFeedV2().watermark(); }
        catch (RuntimeException e) {
            LOG.warn("[worldstate] watermark unavailable; replay from zero: {}", e.toString());
            return 0;
        }
    }

    @Override public Response read(Request request) {
        if (request == null) return Response.error("", "missing request");
        long start = System.nanoTime();
        try {
            long wm = watermark();
            ObservationRequest req = toCore(request);
            MinecraftServer mc = server.get();
            if (mc == null) return Response.error(request.topic(), "no server");
            var rr = host.read(mc, req);
            List<Item> items = rr.present() ? items(rr.observation().payload()) : List.of();
            Response result = ObservationResponses.map(rr, request, items, wm, System.currentTimeMillis(),
                    req.keyFor(WorldStateService.worldId(mc)), host.topicTtlMs(req.topic()));
            return checkDeadline(request, result, start);
        } catch (RuntimeException e) { return Response.error(request.topic(), "read failed: " + e.getMessage()); }
    }

    @Override public Response refresh(Request request) {
        if (request == null) return Response.error("", "missing request");
        long start = System.nanoTime();
        try {
            ObservationRequest req = toCore(request);
            MinecraftServer mc = server.get();
            if (mc == null) return Response.error(request.topic(), "no server");
            if (!mc.isSameThread()) return Response.error(request.topic(), "refresh requires server thread; read is cache-only");
            var result = host.refresh(mc, req);
            WorldStateMonitor.publish("cap_refresh", Map.of("topic", request.topic(),
                    "ok", result != null && result.success(), "status", result == null ? "null" : result.status().name()));
            if (result == null || !result.success())
                return Response.error(request.topic(), result == null ? "refresh returned null"
                        : "refresh failed: " + result.message() + " (" + result.reason() + ")");
            return checkDeadline(request, read(request), start);
        } catch (RuntimeException e) { return Response.error(request.topic(), "refresh failed: " + e.getMessage()); }
    }

    /** Cooperative elapsed-time guard; not a claim that an MC collector can be forcibly interrupted. */
    private static Response checkDeadline(Request request, Response result, long startNanos) {
        long limit = request.deadlineMs() <= 0 ? 50 : Math.min(1_000, request.deadlineMs());
        if (System.nanoTime() - startNanos <= limit * 1_000_000L) return result;
        return Response.error(request.topic(), "deadline exceeded; cache may have been refreshed; explicit read may recover");
    }

    static ObservationRequest toCore(Request request) {
        Topic topic = Topic.byId(request.topic());
        if (topic == null) throw new IllegalArgumentException("unknown topic: " + request.topic());
        var s = request.selector();
        return switch (topic) {
            case ENVIRONMENT -> ObservationRequest.environment(request.dimension());
            case CONTAINERS -> ObservationRequest.containers(request.dimension(), s.get("container"));
            case ENTITIES -> ObservationRequest.entities(request.dimension(), box(s.get("box")),
                    s.get("type"), s.getOrDefault("category", "all"));
            case BLOCKS -> {
                if (!s.containsKey("blocks")) throw new IllegalArgumentException("blocks requires selector blocks");
                int samples = Integer.parseInt(s.getOrDefault("samples", "0"));
                if (samples < 0 || samples > 32) throw new IllegalArgumentException("samples must be 0..32");
                yield ObservationRequest.blocks(request.dimension(), box(s.get("box")), s.get("blocks"), samples);
            }
            default -> throw new IllegalArgumentException("topic not wired yet: " + topic.id());
        };
    }

    private static Bounds box(String raw) {
        if (raw == null) throw new IllegalArgumentException("selector box=x1,y1,z1,x2,y2,z2 required");
        String[] p = raw.split(",", -1);
        if (p.length != 6) throw new IllegalArgumentException("box requires six integers");
        int[] c = new int[6];
        for (int i = 0; i < 6; i++) c[i] = Integer.parseInt(p[i].trim());
        return new Bounds(c[0], c[1], c[2], c[3], c[4], c[5]);
    }

    private static List<Item> items(ObservationPayload payload) {
        List<Item> out = new ArrayList<>();
        if (payload instanceof EnvironmentPayload e) {
            out.add(Item.of(Map.of("dimension", e.dimension(), "day_time", e.dayTime(),
                    "weather", e.weather(), "raining", e.raining(), "thundering", e.thundering())));
        } else if (payload instanceof ContainerPayload c) {
            c.items().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
                    out.add(Item.of(Map.of("item", e.getKey(), "count", e.getValue()))));
        } else if (payload instanceof BlockCountPayload b) {
            b.counts().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("block", e.getKey()); v.put("count", e.getValue());
                if (!b.samplePositions().isEmpty()) v.put("samples", b.samplePositions().stream()
                        .filter(p -> p.startsWith(e.getKey() + "@"))
                        .map(p -> p.substring(e.getKey().length() + 1)).toList());
                out.add(Item.of(v));
            });
        } else if (payload instanceof EntityCountPayload e) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("count", e.count());
            if (e.entityType() != null) v.put("entity_type", e.entityType());
            else { v.put("category", e.category()); if (!e.typeCounts().isEmpty()) v.put("type_counts", e.typeCounts()); }
            out.add(Item.of(v));
        } else throw new IllegalArgumentException("unsupported payload type");
        return List.copyOf(out);
    }
}
