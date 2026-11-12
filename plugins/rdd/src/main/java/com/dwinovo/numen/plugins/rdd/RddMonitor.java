package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.WorldContextStamp;
import com.google.gson.Gson;
import java.util.Map;

/**
 * RDD observation adapter to the host's one journal queue (2026-11-12).
 * WHY: the former private rdd.jsonl writer omitted world/server/run/sequence and
 * could not join strict cross-category evidence. Do not write that file twice.
 * The public facade keeps internal journal classes outside the slim plugin API.
 * A caller must supply its producer-captured exact-server token; legacy calls are
 * UNKNOWN, never stamped from a later process-global world or a previous log row.
 * Queue acceptance is not persistence, delivery or evidence of model visibility.
 */
public final class RddMonitor {
    private static final Gson GSON = new Gson();
    static final String BUILD_STAMP = "2026-11-12Tjournal-producer-token";
    static final long HEARTBEAT_MS = 10L * 60 * 1000;
    private static final RddMonitorGate GATE = new RddMonitorGate(HEARTBEAT_MS);

    private RddMonitor() {}

    /** Legacy signature: no producer token means unknown world/server identity. */
    public static boolean publishIfChanged(String type, Map<String, ?> data) {
        return publishIfChanged(type, data, null);
    }

    /**
     * Commit a dedup fingerprint only AFTER the shared queue accepts the event.
     * Refusal/closed/host-unavailable stays retryable; accepted can still be lost
     * before persistence. World/server/epoch and UNKNOWN are separate dedup domains.
     */
    public static synchronized boolean publishIfChanged(String type, Map<String, ?> data,
                                                        WorldContextStamp.Token token) {
        String key = GSON.toJson(java.util.List.of(type == null ? "" : type,
                token == null || token.identity() == null ? "UNKNOWN" : new java.util.TreeMap<>(token.identity()),
                token == null || token.identity() == null ? 0L : token.epoch()));
        String sig = signature(data);
        long now = System.currentTimeMillis();
        if (!GATE.shouldEmit(key, sig, now) || !publish(type, data, token)) return false;
        GATE.commit(key, sig, now); // accepted only: NOT a persistence receipt
        return true;
    }

    /**
     * 内容指纹：先把**对象图**转成 JSON 树，再在树上剥掉易变字段并规范化。
     *
     * <p>为什么不在对象图上剥（2026-11-05 实机教训）：旧实现只检查 Map 的 key，
     * 而 RddAssetContext.worldAssets 返回的是 **POJO（AssetEntry）**，字段名不在 Map key 里，
     * 于是 observationId/observedAt 一路进了签名 → 每拍都变 → 去重永不命中。
     * 落盘 JSON 看起来「内容一样」，是因为对比方在 JSON 层剥了；两边不在同一层剥，结论就不一样。
     *
     * <p>在 JSON 树上做还有一个好处：Map / POJO / record / 数组一律类型无关，剥的就是要写出去的那份内容。
     */
    static String signature(Object value) {
        return canonical(toJsonTree(value)).toString();
    }

    private static com.google.gson.JsonElement toJsonTree(Object value) {
        try {
            return GSON.toJsonTree(value);
        } catch (RuntimeException e) {
            return new com.google.gson.JsonPrimitive(String.valueOf(value));
        }
    }

    /** 规范化：对象按 key 排序并跳过易变字段；数组元素递归后按字符串排序（顺序无关）。 */
    private static com.google.gson.JsonElement canonical(com.google.gson.JsonElement el) {
        if (el == null || el.isJsonNull() || el.isJsonPrimitive()) return el;
        if (el.isJsonObject()) {
            java.util.TreeMap<String, com.google.gson.JsonElement> sorted = new java.util.TreeMap<>();
            for (Map.Entry<String, com.google.gson.JsonElement> e : el.getAsJsonObject().entrySet()) {
                String k = e.getKey();
                if (VOLATILE_FIELDS.contains(k)) continue;
                sorted.put(k, canonical(e.getValue()));
            }
            com.google.gson.JsonObject out = new com.google.gson.JsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> e : sorted.entrySet()) {
                out.add(e.getKey(), e.getValue());
            }
            return out;
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (com.google.gson.JsonElement child : el.getAsJsonArray()) {
            parts.add(canonical(child).toString());
        }
        parts.sort(java.util.Comparator.naturalOrder());
        com.google.gson.JsonArray out = new com.google.gson.JsonArray();
        for (String part : parts) {
            out.add(com.google.gson.JsonParser.parseString(part));
        }
        return out;
    }

    /** 每拍都会变、但不代表内容变化的字段。 */
    private static final java.util.Set<String> VOLATILE_FIELDS =
            java.util.Set.of("observationId", "observedAt");
    /** Legacy signature retained; callers without captured provenance stay UNKNOWN. */
    public static boolean publish(String type, Map<String, ?> data) {
        return publish(type, data, null);
    }

    /** @return true only when the real host queue accepted this observation. */
    public static boolean publish(String type, Map<String, ?> data, WorldContextStamp.Token token) {
        try {
            return NumenPlugins.tryPublishMonitor("rdd", type, data, token);
        } catch (RuntimeException | LinkageError unavailable) {
            // Missing/old host is not permission to reconstruct identity or open a second writer.
            return false;
        }
    }
}
