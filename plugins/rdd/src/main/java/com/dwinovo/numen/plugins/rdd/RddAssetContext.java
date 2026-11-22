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
        String open = "<known_world_assets>\n", close = "</known_world_assets>";
        String warning = "historical; not current; re-check.\n", missing = "not_delivered=YES\n", truncated = "truncated=YES\n";
        if (open.length() + warning.length() + missing.length() + close.length() > maxChars)
            return missing.trim();
        StringBuilder out = new StringBuilder(open).append(warning);
        int shown = 0;
        boolean omitted = false;
        for (var entry : assets) {
            Map<String, Object> value = entry.observation().value();
            String compact = "- rdd_assets detail asset_id=" + text(entry.assetId()) + " label="
                    + text(value.getOrDefault("label", entry.assetId())) + "\n";
            String full = "- rdd_assets detail asset_id=" + text(entry.assetId()) + " kind=" + text(value.get("kind"))
                    + " label=" + text(value.getOrDefault("label", entry.assetId()))
                    + " world=" + text(value.get("world_id"))
                    + " observed_at=" + (entry.observation().observedAt() > 0 ? entry.observation().observedAt() : "UNKNOWN")
                    + " source=" + text(entry.observation().source()) + " scope=UNKNOWN coverage=UNKNOWN"
                    + " dimension=" + text(value.get("dimension"))
                    + " pos=" + text(value.get("x")) + "," + text(value.get("y")) + "," + text(value.get("z"))
                    + " refresh=" + text(value.getOrDefault("refresh_policy", "LAZY"))
                    + " status=" + entry.status() + details(value) + "\n";
            int reserve = missing.length() + close.length();
            String line = out.length() + full.length() + reserve <= maxChars ? full : compact;
            if (out.length() + line.length() + reserve > maxChars) { omitted = true; continue; }
            out.append(line);
            shown++;
        }
        if (omitted) out.append(shown == 0 ? missing : truncated);
        return out.append(close).toString();
    }

    /** Planner has no rdd_assets tool: provide a bounded historical fact, not an unusable ref. */
    static String renderForPlanner(AssetRegistry registry, int maxChars) {
        List<AssetRegistry.AssetEntry> assets = worldAssets(registry);
        if (assets.isEmpty()) return "";
        String head = "历史线索非当前；需重查：", omitted = "未投递";
        for (var entry : assets) {
            var v = entry.observation().value();
            String line = head + text(v.get("kind")) + " " + text(v.getOrDefault("label", entry.assetId()))
                    + " " + text(v.get("dimension")) + " (" + text(v.get("x")) + "," + text(v.get("y"))
                    + "," + text(v.get("z")) + ") 时间="
                    + (entry.observation().observedAt() > 0 ? entry.observation().observedAt() : "UNKNOWN")
                    + " 状态=" + entry.status() + " 世界=" + knownText(v.get("world_id"))
                    + " 来源=" + knownText(entry.observation().source()) + " 范围=UNKNOWN";
            String suffix = assets.size() > 1 ? "；其余未展示" : "";
            if (line.length() + suffix.length() <= maxChars) return line + suffix;
        }
        return omitted.length() <= maxChars ? omitted : "";
    }

    private static String details(Map<String, Object> value) {
        Object summary = value.get("summary");
        Object contents = value.get("important_items");
        if (summary == null && contents == null) return "";
        return " details=" + text(summary != null ? summary : contents);
    }

    private static String knownText(Object value) {
        if (!(value instanceof String s) || s.isBlank() || "unknown".equalsIgnoreCase(s)) return "UNKNOWN";
        return text(s);
    }

    private static String text(Object value) {
        if (value == null) return "unknown";
        return String.valueOf(value).replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\n", " ").replace("\r", " ");
    }
}
