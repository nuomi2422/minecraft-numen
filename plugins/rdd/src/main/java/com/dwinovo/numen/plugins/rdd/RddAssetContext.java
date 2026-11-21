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
 * 当前记录缺少可证明相同采样范围的元数据，不合并不同记录，避免历史丰富线索
 * 盖掉更新的局部观测。仍按时间倒序、预算有界展示。
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
        boolean truncated = false;
        for (var entry : assets) {
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
            if (out.length() + line.length() + 37 > maxChars) { truncated = true; break; }
            out.append(line);
        }
        if (truncated) out.append("truncated=YES\n");
        out.append("</known_world_assets>");
        return out.toString();
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
