package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.policy.VillageRank;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 村庄候选（167 §B2）：把已有 world_village 观测排成**稳定的候选顺序**给规划器看。
 *
 * <p>只陈述事实与分数：不作准入判断（那是 HomeGate / settlement 的事），
 * 也不替模型挑——模型可以推翻排序，但要推翻的是一个写出来的分数，而不是凭印象。
 */
final class RddVillageFacts {

    private RddVillageFacts() {}

    private static final int MAX_CANDIDATES = 3;

    static String blockFor(AssetRegistry registry) {
        List<VillageRank.Candidate> all = candidates(registry);
        if (all.isEmpty()) return "";
        List<VillageRank.Candidate> ordered = VillageRank.order(all);
        StringBuilder sb = new StringBuilder("<village_candidates>\n");
        sb.append("历史候选，不代表当前数量或准入；使用前到场重查。coverage=UNKNOWN。按「村民>箱子>作物>铁傀儡>书架」打分排序；这只是候选顺序，不是准入。\n");
        int n = 0;
        for (VillageRank.Candidate c : ordered) {
            if (n++ >= MAX_CANDIDATES) break;
            sb.append("- id=").append(c.id())
                    .append(" dim=").append(c.dimension())
                    .append(" pos=").append(c.x()).append(',').append(c.y()).append(',').append(c.z())
                    .append(" observed_at=").append(c.observedAtMillis() > 0 ? c.observedAtMillis() : "UNKNOWN")
                    .append(" world=").append(worldFor(registry, c))
                    .append(" source=").append(sourceFor(registry, c))
                    .append(" score=").append(VillageRank.score(c))
                    .append(" villagers=").append(num(c, "villagers"))
                    .append(" chests=").append(num(c, "chests"))
                    .append(" crops=").append(num(c, "crops"))
                    .append(" golems=").append(num(c, "iron_golem"))
                    .append('\n');
        }
        sb.append("</village_candidates>");
        return sb.toString();
    }

    private static String sourceFor(AssetRegistry registry, VillageRank.Candidate c) {
        String source = null;
        int matches = 0;
        for (var e : RddAssetContext.worldAssets(registry)) {
            var v = e.observation().value();
            if (!"village".equals(String.valueOf(v.get("kind")))) continue;
            String id = String.valueOf(v.getOrDefault("structure", e.assetId()));
            if (!id.equals(c.id()) || e.observation().observedAt() != c.observedAtMillis()) continue;
            int x = v.get("x") instanceof Number n ? n.intValue() : 0;
            int y = v.get("y") instanceof Number n ? n.intValue() : 0;
            int z = v.get("z") instanceof Number n ? n.intValue() : 0;
            if (x != c.x() || y != c.y() || z != c.z() || !String.valueOf(v.getOrDefault("dimension", "unknown")).equals(c.dimension())) continue;
            matches++;
            String raw = e.observation().source();
            source = raw == null || raw.isBlank() ? "UNKNOWN" : raw.replaceAll("[\r\n<>]", "_");
        }
        return matches == 1 ? source : "UNKNOWN";
    }

    private static String worldFor(AssetRegistry registry, VillageRank.Candidate c) {
        // A structure name can be shared by many records: only a unique exact candidate is safe.
        String world = null;
        int matches = 0;
        for (var e : RddAssetContext.worldAssets(registry)) {
            var v = e.observation().value();
            if (!"village".equals(String.valueOf(v.get("kind")))) continue;
            String id = String.valueOf(v.getOrDefault("structure", e.assetId()));
            if (!id.equals(c.id()) || e.observation().observedAt() != c.observedAtMillis()) continue;
            int x = v.get("x") instanceof Number n ? n.intValue() : 0;
            int y = v.get("y") instanceof Number n ? n.intValue() : 0;
            int z = v.get("z") instanceof Number n ? n.intValue() : 0;
            if (x != c.x() || y != c.y() || z != c.z() || !String.valueOf(v.getOrDefault("dimension", "unknown")).equals(c.dimension())) continue;
            matches++;
            Object raw = v.get("world_id");
            world = raw instanceof String s && !s.isBlank() ? s.replaceAll("[\\r\\n<>]", "_") : "UNKNOWN";
        }
        return matches == 1 ? world : "UNKNOWN";
    }

    private static int num(VillageRank.Candidate c, String key) {
        Map<String, Integer> r = c.resources();
        if (r == null) return 0;
        Integer v = r.get(key);
        return v == null ? 0 : v;
    }

    static List<VillageRank.Candidate> candidates(AssetRegistry registry) {
        if (registry == null) return List.of();
        List<VillageRank.Candidate> out = new ArrayList<>();
        for (var e : RddAssetContext.worldAssets(registry)) {
            Map<String, Object> v = e.observation().value();
            if (!"village".equals(String.valueOf(v.get("kind")))) continue;
            Map<String, Integer> res = new java.util.LinkedHashMap<>();
            if (v.get("resources") instanceof Map<?, ?> m) {
                for (var x : m.entrySet()) {
                    if (x.getValue() instanceof Number n) res.put(String.valueOf(x.getKey()), n.intValue());
                }
            }
            int x = v.get("x") instanceof Number nx ? nx.intValue() : 0;
            int y = v.get("y") instanceof Number ny ? ny.intValue() : 0;
            int z = v.get("z") instanceof Number nz ? nz.intValue() : 0;
            out.add(new VillageRank.Candidate(
                    String.valueOf(v.getOrDefault("structure", e.assetId())),
                    String.valueOf(v.getOrDefault("dimension", "unknown")),
                    x, y, z, res, e.observation().observedAt()));
        }
        return out;
    }
}
