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
        sb.append("按「村民>箱子>作物>铁傀儡>书架」打分排序；这只是候选顺序，不是准入。\n");
        int n = 0;
        for (VillageRank.Candidate c : ordered) {
            if (n++ >= MAX_CANDIDATES) break;
            sb.append("- id=").append(c.id())
                    .append(" dim=").append(c.dimension())
                    .append(" pos=").append(c.x()).append(',').append(c.y()).append(',').append(c.z())
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
