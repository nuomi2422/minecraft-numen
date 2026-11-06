package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.api.observation.WorldObservationService.Item;
import com.dwinovo.numen.worldstate.core.observation.payload.SelfPayload;
import com.google.gson.*;
import java.util.*;

/**
 * Pure SELF projection shared by capability and real tool output (2026-11-06).
 * WHY: TaskResult top-level records become strings; explicit maps retain structured JSON.
 * USAGE: item(payload), with unknown field paths preserved rather than zero-filled.
 * No second data source: all values come from the detached DTO, not live world reads.
 * BodyTask elapsedMs is a sampled game-tick estimate (50ms/tick), not a wall-clock timer.
 */
public final class SelfObservationProjection {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private SelfObservationProjection() {}

    public static Item item(SelfPayload payload) {
        Set<String> unknown = new TreeSet<>();
        JsonObject tree = JSON.toJsonTree(payload).getAsJsonObject();
        tree.add("mainHand", JSON.toJsonTree(payload.mainHand()));
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) value(tree, "", unknown);
        return new Item(values, unknown, Set.of());
    }

    public static Map<String, Object> toolData(com.dwinovo.numen.api.observation.WorldObservationService.Response response) {
        return Map.of("status", response.status().name(), "topic", response.topic(),
                "snapshotId", response.snapshotId(), "observedTick", response.observedTick(),
                "coverage", response.coverage().name(), "reason", response.reason() == null ? "" : response.reason(),
                "items", response.items(), "metadata", JSON.fromJson(JSON.toJsonTree(response.metadata()), Map.class), "watermark", response.watermark());
    }

    /** Exact explicit SELF action path used by WorldStateTool; transport success is not task completion. */
    public static String toolReply(String action, com.dwinovo.numen.api.observation.WorldObservationService.Request request,
                                   com.dwinovo.numen.api.observation.WorldObservationService service) {
        var response = switch (action) {
            case "read" -> service.read(request);
            case "refresh" -> service.refresh(request);
            default -> throw new IllegalArgumentException("self action must be read or refresh");
        };
        var data = toolData(response);
        return (response.status() == com.dwinovo.numen.api.observation.WorldObservationService.Status.ERROR
                ? com.dwinovo.numen.task.TaskResult.fail(response.reason(), data)
                : com.dwinovo.numen.task.TaskResult.ok("explicit self observation", data)).toJson();
    }

    private static Object value(JsonElement element, String path, Set<String> unknown) {
        if (element.isJsonNull()) { unknown.add(path); return null; }
        if (element.isJsonObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (var entry : element.getAsJsonObject().entrySet()) {
                // A known non-riding vehicle deliberately has no UUID, not an unknown identity.
                if ("vehicle".equals(path) && "uuid".equals(entry.getKey())
                        && element.getAsJsonObject().get("type").getAsString().isEmpty()) continue;
                // Infinite effects have no finite duration/expiry; absence here is known, not missing.
                if (element.getAsJsonObject().has("infinite") && element.getAsJsonObject().get("infinite").isJsonPrimitive()
                        && element.getAsJsonObject().get("infinite").getAsBoolean()
                        && Set.of("observedDuration", "expiryTick").contains(entry.getKey())) continue;
                Object mapped = value(entry.getValue(), path.isEmpty() ? entry.getKey() : path + "." + entry.getKey(), unknown);
                if (mapped != null) out.put(entry.getKey(), mapped);
            }
            return Collections.unmodifiableMap(out);
        }
        if (element.isJsonArray()) {
            List<Object> out = new ArrayList<>();
            int index = 0;
            for (var entry : element.getAsJsonArray()) out.add(value(entry, path + "[" + index++ + "]", unknown));
            return List.copyOf(out);
        }
        var primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) return primitive.getAsBoolean();
        if (primitive.isNumber()) return primitive.getAsBigDecimal();
        return primitive.getAsString();
    }
}
