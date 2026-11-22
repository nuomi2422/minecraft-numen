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

    private record Ranked(VillageRank.Candidate candidate, AssetRegistry.AssetEntry entry) {}

    static String blockFor(AssetRegistry registry) {
        List<Ranked> all = new ArrayList<>();
        for (var e : RddAssetContext.worldAssets(registry)) {
            var c = candidate(e);
            if (c != null) all.add(new Ranked(c, e));
        }
        if (all.isEmpty()) return "";
        all.sort(java.util.Comparator.comparingInt((Ranked r) -> VillageRank.score(r.candidate())).reversed()
                .thenComparing(r -> r.candidate().id() == null ? "" : r.candidate().id()));
        StringBuilder sb = new StringBuilder("<village_candidates>\n");
        sb.append("历史候选，不代表当前数量或准入；使用前到场重查。coverage=UNKNOWN。按「村民>箱子>作物>铁傀儡>书架」打分排序；这只是候选顺序，不是准入。\n");
        int n = 0;
        for (Ranked row : all) {
            if (n++ >= MAX_CANDIDATES) break;
            var c = row.candidate(); var e = row.entry();
            sb.append("- id=").append(c.id())
                    .append(" dim=").append(c.dimension())
                    .append(" pos=").append(c.x()).append(',').append(c.y()).append(',').append(c.z())
                    .append(" observed_at=").append(c.observedAtMillis() > 0 ? c.observedAtMillis() : "UNKNOWN")
                    .append(" world=").append(safe(e.observation().value().get("world_id")))
                    .append(" source=").append(safe(e.observation().source()))
                    .append(" score=").append(VillageRank.score(c))
                    .append(" villagers=").append(num(c, "villagers"))
                    .append(" chests=").append(num(c, "chests"))
                    .append(" crops=").append(num(c, "crops"))
                    .append(" golems=").append(num(c, "iron_golem"))
                    .append('\n');
        }
        return sb.append("</village_candidates>").toString();
    }

    private static String safe(Object raw) {
        return raw instanceof String s && !s.isBlank() ? s.replaceAll("[\\r\\n<>]", "_") : "UNKNOWN";
    }

    private static int num(VillageRank.Candidate c, String key) {
        Map<String, Integer> r = c.resources();
        if (r == null) return 0;
        Integer v = r.get(key);
        return v == null ? 0 : v;
    }

    static List<VillageRank.Candidate> candidates(AssetRegistry registry) {
        List<VillageRank.Candidate> out = new ArrayList<>();
        for (var e : RddAssetContext.worldAssets(registry)) {
            var c = candidate(e);
            if (c != null) out.add(c);
        }
        return out;
    }

    private static VillageRank.Candidate candidate(AssetRegistry.AssetEntry e) {
        Map<String, Object> v = e.observation().value();
        if (!"village".equals(String.valueOf(v.get("kind")))) return null;
        Map<String, Integer> res = new java.util.LinkedHashMap<>();
        if (v.get("resources") instanceof Map<?, ?> m) {
            for (var x : m.entrySet()) {
                if (x.getValue() instanceof Number n) res.put(String.valueOf(x.getKey()), n.intValue());
            }
        }
        int x = v.get("x") instanceof Number nx ? nx.intValue() : 0;
        int y = v.get("y") instanceof Number ny ? ny.intValue() : 0;
        int z = v.get("z") instanceof Number nz ? nz.intValue() : 0;
        return new VillageRank.Candidate(String.valueOf(v.getOrDefault("structure", e.assetId())),
                String.valueOf(v.getOrDefault("dimension", "unknown")), x, y, z, res, e.observation().observedAt());
    }
}
