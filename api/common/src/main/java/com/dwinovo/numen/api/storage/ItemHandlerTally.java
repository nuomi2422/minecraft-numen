package com.dwinovo.numen.api.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 把多个"物品栏视图"合成一份数量（037/T8b，2026-11-04）。
 *
 * <p><b>为什么需要它</b>：NeoForge 的能力系统对同一个箱子按 {@code null} + 六个面各返回
 * 一个<b>新的包装对象</b>，按对象身份去重会失效 —— 实机踩过：一份格子在文本 dump 里出现 7 次，
 * 1 把锄头被读成 ×7、9 个铁锭被读成 ×63，规划据此排链全错。所以合并前必须按
 * <b>内容签名</b>去重：内容完全相同的视图只算一次。
 *
 * <p>纯函数，不依赖 Minecraft：宿主把每个 handler 读成 {@link Slot} 列表即可。
 */
public final class ItemHandlerTally {

    private ItemHandlerTally() {}

    /** 一个格子：物品 id + 数量。 */
    public record Slot(String itemId, int count) {
        public Slot {
            if (itemId == null || itemId.isBlank()) {
                throw new IllegalArgumentException("slot itemId required");
            }
            if (count <= 0) {
                throw new IllegalArgumentException("slot count must be positive: " + count);
            }
        }
    }

    /**
     * 合并多个 handler 视图。
     *
     * @param handlers 每个元素是一个 handler 的全部非空格子（顺序无关）
     * @return 物品 id → 总数量（按 id 排序，便于稳定 diff）
     */
    public static Map<String, Integer> merge(List<List<Slot>> handlers) {
        if (handlers == null || handlers.isEmpty()) {
            return Map.of();
        }
        Map<String, List<Slot>> unique = new LinkedHashMap<>();
        for (List<Slot> handler : handlers) {
            if (handler == null || handler.isEmpty()) {
                continue;   // 空视图不参与去重，也不贡献数量
            }
            unique.putIfAbsent(signature(handler), handler);
        }
        Map<String, Integer> out = new TreeMap<>();
        for (List<Slot> handler : unique.values()) {
            for (Slot slot : handler) {
                out.merge(slot.itemId(), slot.count(), Integer::sum);
            }
        }
        // 用 TreeMap 的不可变视图：Map.copyOf 不保证迭代顺序，会让"按 id 排序"的承诺落空。
        return java.util.Collections.unmodifiableMap(new TreeMap<>(out));
    }

    /**
     * 内容签名：同一份格子无论从哪个面读、顺序如何，签名都一样。
     * （按 id 聚合再排序，所以"两格 5 个"与"一格 10 个"也会得到同一签名。）
     */
    static String signature(List<Slot> handler) {
        Map<String, Integer> byId = new TreeMap<>();
        for (Slot slot : handler) {
            byId.merge(slot.itemId(), slot.count(), Integer::sum);
        }
        List<String> parts = new ArrayList<>(byId.size());
        for (Map.Entry<String, Integer> e : byId.entrySet()) {
            parts.add(e.getKey() + "=" + e.getValue());
        }
        return String.join(";", parts);
    }
}
