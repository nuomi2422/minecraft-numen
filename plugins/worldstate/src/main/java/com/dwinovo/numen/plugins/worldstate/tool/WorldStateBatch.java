package com.dwinovo.numen.plugins.worldstate.tool;

import com.dwinovo.numen.api.observation.ObservationBatch;
import com.dwinovo.numen.api.observation.WorldObservationService;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.google.gson.JsonObject;
import java.util.*;

/**
 * Model-facing batch parser and caller-bound scope (039/B1, 2026-11-05).
 * WHY: consumer=admin or another player's UUID is not an authorization credential.
 * Reuses WorldStateArgs for the existing four topics. No scanner, transport, or second store.
 * Enhanced near-field server reads are marked SERVER_LOADED, not human line-of-sight vision.
 */
public final class WorldStateBatch {
    private WorldStateBatch() {}
    public record Caller(String companionId, String worldId, String serverSession, String dimension, int x, int y, int z) {}

    public static ObservationBatch.Query parse(JsonObject args, Caller caller, String requestId) {
        if (caller == null || caller.companionId() == null || caller.companionId().isBlank())
            throw new IllegalArgumentException("authenticated companion required");
        if (!args.has("queries") || !args.get("queries").isJsonArray())
            throw new IllegalArgumentException("batch_read requires queries array");
        var input = args.getAsJsonArray("queries");
        if (input.isEmpty() || input.size() > ObservationBatch.MAX_REQUESTS)
            throw new IllegalArgumentException("queries must contain 1..16 entries");
        long maxAge = number(args, "max_age_ms", 2_000, 1, 60_000);
        int maxItems = (int) number(args, "max_items", 64, 1, 256);
        long deadline = number(args, "deadline_ms", 50, 1, 1_000);
        List<WorldObservationService.Request> requests = new ArrayList<>();
        for (var entry : input) {
            if (!entry.isJsonObject()) throw new IllegalArgumentException("each query must be an object");
            JsonObject q = entry.getAsJsonObject();
            Set<String> allowed = Set.of("topic", "dimension", "container", "block", "samples", "entity_type", "category", "x1", "y1", "z1", "x2", "y2", "z2");
            for (String key : q.keySet()) if (!allowed.contains(key))
                throw new IllegalArgumentException("unknown query field: " + key);
            String dim = text(q, "dimension", caller.dimension());
            if (!dim.equals(caller.dimension())) throw new IllegalArgumentException("DENIED: batch scope is current dimension");
            Topic topic = Topic.byId(text(q, "topic", ""));
            var parsed = WorldStateArgs.forReadRefresh(topic, q, dim);
            if (!parsed.ok()) throw new IllegalArgumentException(parsed.error());
            var core = parsed.request();
            Map<String, String> selector = new LinkedHashMap<>();
            if (core.bounds() != null) {
                // Parse the same six coordinates; check BEFORE passing to the trusted plugin API.
                String[] keys = {"x1", "y1", "z1", "x2", "y2", "z2"};
                int[] centre = {caller.x(), caller.y(), caller.z()};
                for (int i = 0; i < keys.length; i++) {
                    long value = number(q, keys[i], 0, i % 3 == 1 ? -64 : -30_000_000, i % 3 == 1 ? 320 : 30_000_000);
                    if (Math.abs(value - centre[i % 3]) > 32)
                        throw new IllegalArgumentException("DENIED: query exceeds companion 32-block near field");
                }
                selector.put("box", core.bounds().canonical().replace("..", ","));
            }
            switch (topic) {
                case CONTAINERS -> {
                    String target = core.target();
                    if (target.startsWith("player:")) {
                        if (!target.equals("player:" + caller.companionId()))
                            throw new IllegalArgumentException("DENIED: another companion inventory");
                    } else {
                        String[] p = target.substring("block:".length()).split(",", -1);
                        if (p.length != 3) throw new IllegalArgumentException("container requires three coordinates");
                        int[] centre = {caller.x(), caller.y(), caller.z()};
                        for (int i = 0; i < 3; i++) {
                            long v = Long.parseLong(p[i].trim());
                            if (v < -30_000_000 || v > 30_000_000 || Math.abs(v - centre[i]) > 32)
                                throw new IllegalArgumentException("DENIED: container outside near field");
                        }
                    }
                    selector.put("container", target);
                }
                case BLOCKS -> {
                    selector.put("blocks", text(q, "block", ""));
                    selector.put("samples", String.valueOf(number(q, "samples", 0, 0, 32)));
                }
                case ENTITIES -> {
                    if (q.has("entity_type")) {
                        String type = text(q, "entity_type", "");
                        if (type.isEmpty()) throw new IllegalArgumentException("entity_type must not be empty");
                        selector.put("type", type);
                    } else {
                        String category = text(q, "category", "all");
                        if (!Set.of("all", "passive", "hostile").contains(category))
                            throw new IllegalArgumentException("unsupported entity category");
                        selector.put("category", category);
                    }
                }
                case ENVIRONMENT -> { }
                default -> throw new IllegalArgumentException("topic not wired");
            }
            requests.add(new WorldObservationService.Request("world-state-tool", dim, topic.id(), selector,
                    maxAge, maxItems, deadline));
        }
        return new ObservationBatch.Query(requestId, caller.worldId(), caller.serverSession(), requests,
                number(args, "max_skew_ms", 100, 0, 60_000), deadline);
    }

    /** TaskResult serializes top-level records with toString; wrap in a real map at the tool boundary. */
    public static Map<String, Object> resultData(ObservationBatch.Result result) {
        return Map.of("requestId", result.requestId(), "status", result.status().name(),
                "consistency", result.consistency().name(), "observedSkewMs", result.observedSkewMs(),
                "responses", result.responses(), "reason", result.reason() == null ? "" : result.reason());
    }

    private static long number(JsonObject o, String key, long fallback, long min, long max) {
        if (!o.has(key)) return fallback;
        try {
            var n = o.get(key);
            if (!n.isJsonPrimitive() || !n.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
            long v = n.getAsBigDecimal().longValueExact();
            if (v < min || v > max) throw new IllegalArgumentException();
            return v;
        } catch (RuntimeException e) { throw new IllegalArgumentException(key + " must be an integer " + min + ".." + max); }
    }
    private static String text(JsonObject o, String key, String fallback) {
        if (!o.has(key)) return fallback;
        if (!o.get(key).isJsonPrimitive() || !o.get(key).getAsJsonPrimitive().isString())
            throw new IllegalArgumentException(key + " must be a string");
        return o.get(key).getAsString().trim();
    }
}
