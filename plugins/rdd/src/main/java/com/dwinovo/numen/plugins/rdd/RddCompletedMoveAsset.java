package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.WorldContextStamp;
import com.dwinovo.numen.rdd.api.AssetScope;
import com.dwinovo.numen.rdd.api.Observation;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.TaskState;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Historical completed endpoints, not a navigable route or continuous track. */
final class RddCompletedMoveAsset {
    private static final int MAX_COMPLETED_MOVES = 32;
    private RddCompletedMoveAsset() {}

    static boolean project(UUID companion, String taskId, TaskRecord record, WorldContextStamp.Token current,
                           String dimension, AssetRegistry registry, long now) {
        if (companion == null || taskId == null || taskId.isBlank() || record == null || registry == null
                || record.getState() != TaskState.SUCCESS || record.getResult() == null
                || !record.getResult().success() || !taskId.equals(record.publicId())
                || !"goto".equals(record.getToolName()) || current == null || current.identity() == null
                || current.epoch() <= 0 || !current.equals(record.identityToken()) || dimension == null
                || record.getResult().data() == null) return false;
        Object raw = record.getResult().data().get("completed_move");
        if (!(raw instanceof Map<?, ?> fact)) return false;
        String world = current.identity().get("world_id"), session = current.identity().get("server_session");
        if (!known(world) || !known(session) || !known(dimension)
                || !world.equals(fact.get("world_id")) || !session.equals(fact.get("server_session"))
                || !(fact.get("epoch") instanceof Number epoch) || !exactEpoch(epoch, current.epoch())
                || !dimension.equals(fact.get("dimension")) || !Boolean.FALSE.equals(fact.get("route_proven"))
                || !"start_to_end_only".equals(fact.get("direction"))) return false;
        Map<String, Double> start = point(fact.get("start")), end = point(fact.get("end"));
        if (start == null || end == null || start.equals(end)) return false;
        String key = "move|" + UUID.nameUUIDFromBytes((world + "\0" + companion + "\0" + session + "\0" + current.epoch() + "\0" + taskId).getBytes(StandardCharsets.UTF_8));
        synchronized (registry) {
            if (registry.get(key).isPresent()) return false;
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("kind", "completed_move"); value.put("label", "historical completed movement endpoints");
            value.put("world_id", world); value.put("server_session", session); value.put("epoch", current.epoch());
            value.put("companion_id", companion.toString()); value.put("task_id", taskId); value.put("dimension", dimension);
            value.put("start", start); value.put("end", end); value.put("direction", "start_to_end_only");
            value.put("route_proven", false); value.put("summary", "Historical single movement endpoints; not a continuous track or proof of route passability.");
            registry.apply(new Observation("obs-" + key, "world_completed_move", "task_finished", dimension, now, value), key, AssetScope.GLOBAL, null);
            var moves = registry.snapshot().stream()
                    .filter(e -> "world_completed_move".equals(e.observation().type()))
                    .sorted(java.util.Comparator.comparingLong((AssetRegistry.AssetEntry e) -> e.observation().observedAt())
                            .thenComparing(AssetRegistry.AssetEntry::assetId))
                    .toList();
            for (int i = 0; i < moves.size() - MAX_COMPLETED_MOVES; i++) registry.forget(moves.get(i).assetId());
            return true;
        }
    }

    private static boolean exactEpoch(Number value, long expected) {
        try { return new java.math.BigDecimal(value.toString()).longValueExact() == expected; }
        catch (NumberFormatException | ArithmeticException invalid) { return false; }
    }
    private static boolean known(String s) {
        return s != null && !s.isBlank() && !s.toLowerCase(java.util.Locale.ROOT).startsWith("unknown");
    }
    private static Map<String, Double> point(Object raw) {
        if (!(raw instanceof Map<?, ?> p)) return null;
        Map<String, Double> result = new LinkedHashMap<>();
        for (String key : new String[]{"x", "y", "z"}) {
            if (!(p.get(key) instanceof Number n) || !Double.isFinite(n.doubleValue())) return null;
            result.put(key, n.doubleValue());
        }
        return Map.copyOf(result);
    }
}
