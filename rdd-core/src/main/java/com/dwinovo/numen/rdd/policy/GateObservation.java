package com.dwinovo.numen.rdd.policy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 风险门裁决时的<b>观测快照</b>：把「要求什么」与「实际有多少」摆在同一行。
 *
 * <h2>为什么需要它</h2>
 * 2026-10-31（166 B3）踩到一次：门一直拦停，但日志里只有 `missing`，
 * 没有「背包里到底有什么键」。于是无法判断某个要求是"配额太高"还是
 * **"键根本不可满足"**（例如药水真实 id 是 `minecraft:potion`，预算却要求
 * `minecraft:fire_resistance_potion`）—— 那次就是这个原因导致永久拦停，
 * 而验证它只能靠读源码猜。
 *
 * <p>把观测写进裁决事件后，下次拦停就能直接看到：
 * 有没有 `minecraft:fire_resistance_potion`、有没有 `minecraft:potion`。
 *
 * <p>输出<b>有界</b>：只收「本次要求里的键」+「背包里全部 `*_potion` 键」，
 * 并限制条数，避免把整个背包倒进日志。
 */
public final class GateObservation {

    /** 最多带出多少条，防止日志膨胀。 */
    public static final int MAX_ENTRIES = 16;

    private GateObservation() {
    }

    /**
     * @param required 本次风险级别要求的键→数量
     * @param inventory 实际盘点（键→数量）
     * @param maxEntries 上限（≤0 时用 {@link #MAX_ENTRIES}）
     * @return 有序 map：先要求项（含 have），再补背包里的药水键；超出上限截断
     */
    public static Map<String, Integer> observe(Map<String, Integer> required,
                                               Map<String, Integer> inventory,
                                               int maxEntries) {
        int cap = maxEntries > 0 ? maxEntries : MAX_ENTRIES;
        Map<String, Integer> out = new LinkedHashMap<>();
        if (required != null) {
            for (String key : required.keySet()) {
                if (out.size() >= cap) {
                    return Map.copyOf(out);
                }
                out.put(key, have(inventory, key));
            }
        }
        if (inventory != null) {
            for (Map.Entry<String, Integer> e : inventory.entrySet()) {
                if (out.size() >= cap) {
                    break;
                }
                String k = e.getKey();
                if (k != null && k.endsWith("_potion") && !out.containsKey(k)) {
                    out.put(k, e.getValue() == null ? 0 : e.getValue());
                }
            }
        }
        return Map.copyOf(out);
    }

    private static int have(Map<String, Integer> inventory, String key) {
        if (inventory == null || key == null) {
            return 0;
        }
        Integer v = inventory.get(key);
        return v == null ? 0 : v;
    }
}
