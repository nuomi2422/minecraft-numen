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
 * 仅在世界、类型、维度和观测位置均可证且完全一致时折叠；未知范围或不同位置
 * 不合并，避免历史丰富线索盖掉更新的局部观测。仍按时间倒序、预算有界展示。
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
                    + " world=" + text(value.get("world_id"))
                    + " observed_at=" + (entry.observation().observedAt() > 0 ? entry.observation().observedAt() : "UNKNOWN")
                    + " source=" + text(entry.observation().source())
                    + " scope=UNKNOWN coverage=UNKNOWN"
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

    /** Only identical, known world and observation origin can be folded. Unknown scope stays separate. */
    private static List<Cluster> cluster(List<AssetRegistry.AssetEntry> assets) {
        List<Cluster> clusters = new ArrayList<>();
        for (var entry : assets) {
            Map<String, Object> value = entry.observation().value();
            Object world = value.get("world_id"), x = value.get("x"), y = value.get("y"), z = value.get("z");
            boolean known = world instanceof String w && !w.isBlank()
                    && x instanceof Number && y instanceof Number && z instanceof Number
                    && Double.isFinite(((Number) x).doubleValue()) && Double.isFinite(((Number) y).doubleValue())
                    && Double.isFinite(((Number) z).doubleValue());
            String key = known ? text(world) + '|' + text(value.get("kind")) + '|'
                    + text(value.getOrDefault("label", entry.assetId())) + '|' + text(value.get("dimension"))
                    + '|' + x + '|' + y + '|' + z : null;
            Cluster match = null;
            if (key != null) for (Cluster c : clusters) if (key.equals(c.key)) { match = c; break; }
            if (match == null) clusters.add(new Cluster(key, entry));
            else if (entry.observation().observedAt() > match.entry.observation().observedAt()) match.entry = entry;
        }
        return clusters;
    }

    private static final class Cluster {
        final String key;
        AssetRegistry.AssetEntry entry;
        Cluster(String key, AssetRegistry.AssetEntry entry) { this.key = key; this.entry = entry; }
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
