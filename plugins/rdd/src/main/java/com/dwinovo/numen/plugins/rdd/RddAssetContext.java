package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.AssetRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Bounded, explicit projection of persisted world assets for model and monitor consumers.
 *
 * <p>2026-10-28：同一处村庄/要塞/矿井会被反复扫描，观测点差几格就生成新的 assetId
 * （实测 village_savanna 56 条、fortress 32 条、mineshaft 18 条）。原先按 observedAt
 * 倒序逐条渲染，最近几次扫描就能把注入块占满，真正有用的基地/仓储反而被挤掉。现在先按
 * (kind,label,dimension) + 邻近半径聚成一簇，每簇只留**信息最全**的一次观测（村庄看
 * 村民/箱子/作物，基地看设施/库存），再按时间倒序渲染。
 */
final class RddAssetContext {
    private RddAssetContext() {}

    static List<AssetRegistry.AssetEntry> worldAssets(AssetRegistry registry) {
        if (registry == null) return List.of();
        List<AssetRegistry.AssetEntry> out = new ArrayList<>();
        for (var entry : registry.snapshot()) {
            if (RddAssetStore.isWorldAsset(entry)) out.add(entry);
        }
        out.sort(Comparator.comparingLong((AssetRegistry.AssetEntry e) -> e.observation().observedAt()).reversed());
        return List.copyOf(out);
    }

    static String render(AssetRegistry registry, int maxChars) {
        if (maxChars < 64) return "";
        List<AssetRegistry.AssetEntry> assets = worldAssets(registry);
        if (assets.isEmpty()) return "";
        Map<String, com.dwinovo.numen.rdd.core.AssetClaim> claims =
                com.dwinovo.numen.rdd.core.PlanningAssetSnapshot.worldClaims(registry);
        StringBuilder out = new StringBuilder("<known_world_assets>\n");
        out.append("Last observed reusable world assets. LAZY entries are leads; re-check before relying on them.\n");
        for (Cluster cluster : cluster(assets)) {
            var entry = cluster.entry;
            Map<String, Object> value = entry.observation().value();
            com.dwinovo.numen.rdd.core.AssetClaim claim = claims.get(entry.assetId());
            String claimMeta = claim == null ? ""
                    : " source=" + claim.source() + " verified_at=" + claim.verifiedAtMillis();
            String line = "- kind=" + text(value.get("kind"))
                    + " label=" + text(value.getOrDefault("label", entry.assetId()))
                    + " dimension=" + text(value.get("dimension"))
                    + " pos=" + text(value.get("x")) + "," + text(value.get("y")) + "," + text(value.get("z"))
                    + " refresh=" + text(value.getOrDefault("refresh_policy", "LAZY"))
                    + " status=" + entry.status()
                    + claimMeta
                    + details(value) + "\n";
            if (out.length() + line.length() + 22 > maxChars) break;
            out.append(line);
        }
        out.append("</known_world_assets>");
        return out.toString();
    }

    /** 一次聚簇：同一 (kind,label,dimension)、观测点邻近的资产算同一处，保留信息最全的一条。 */
    private static List<Cluster> cluster(List<AssetRegistry.AssetEntry> assets) {
        List<Cluster> clusters = new ArrayList<>();
        for (var entry : assets) {
            Map<String, Object> value = entry.observation().value();
            String key = text(value.get("kind")) + '|' + text(value.getOrDefault("label", entry.assetId()))
                    + '|' + text(value.get("dimension"));
            double x = number(value.get("x"));
            double z = number(value.get("z"));
            Cluster hit = null;
            for (Cluster c : clusters) {
                if (!c.key.equals(key)) continue;
                if (!Double.isFinite(x) || !Double.isFinite(z)
                        || Math.hypot(x - c.x, z - c.z) <= radius(value)) {
                    hit = c;
                    break;
                }
            }
            long score = richness(value);
            long at = entry.observation().observedAt();
            if (hit == null) {
                clusters.add(new Cluster(key, x, z, entry, score, at));
            } else if (score > hit.score || (score == hit.score && at > hit.at)) {
                hit.entry = entry;
                hit.score = score;
                hit.at = at;
            }
        }
        clusters.sort(Comparator.comparingLong((Cluster c) -> c.at).reversed());
        return clusters;
    }

    /** 已渲染的资产簇；可变的是「留哪一条」，簇本身不搬家。 */
    private static final class Cluster {
        final String key;
        final double x;
        final double z;
        AssetRegistry.AssetEntry entry;
        long score;
        long at;

        Cluster(String key, double x, double z, AssetRegistry.AssetEntry entry, long score, long at) {
            this.key = key;
            this.x = x;
            this.z = z;
            this.entry = entry;
            this.score = score;
            this.at = at;
        }
    }

    /** 折叠半径（格）：基地只容许重生点小漂移，村庄/结构按体量取大一些。 */
    private static double radius(Map<String, Object> value) {
        String kind = text(value.get("kind"));
        if ("base".equals(kind)) return 96.0;
        if ("village".equals(kind)) return 160.0;
        return 128.0;
    }

    /**
     * 「这条观测有多有用」：同一簇里优先保留见过村民/箱子/作物最多的一次，
     * 而不是最后一次（最后一次常常是路过时的空扫描，villagers=0、chests=0）。
     */
    private static long richness(Map<String, Object> value) {
        String kind = text(value.get("kind"));
        if ("village".equals(kind)) {
            if (value.get("resources") instanceof Map<?, ?> r) {
                return number(r.get("villagers")) * 1000 + number(r.get("iron_golem")) * 200
                        + number(r.get("chests")) * 50 + number(r.get("crops"));
            }
            return 0;
        }
        if ("base".equals(kind)) {
            long facilities = value.get("facilities") instanceof List<?> l ? l.size() : 0;
            long items = value.get("important_items") instanceof Map<?, ?> m ? m.size() : 0;
            return facilities * 100 + items * 50;
        }
        return 0;
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0;
    }

    private static String details(Map<String, Object> value) {
        Object summary = value.get("summary");
        Object contents = value.get("important_items");
        if (summary == null && contents == null) return "";
        return " details=" + text(summary != null ? summary : contents);
    }

    private static String text(Object value) {
        if (value == null) return "unknown";
        return String.valueOf(value).replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\n", " ").replace("\r", " ");
    }
}
