package com.dwinovo.numen.rdd.policy;

/**
 * 资产键的<b>构造规则</b>（把游戏里的真实物品翻译成预算/缺口用的键）。
 *
 * <h2>为什么单独抽出来</h2>
 * 2026-10-31 实机（166 B3）发现一处<b>永久拦停</b>：
 * {@link ResourceBudget} 对下界要求 `minecraft:fire_resistance_potion` 3，
 * 而检测侧只按 {@code BuiltInRegistries.ITEM} 的物品 id 计数 ——
 * 真实药水的 id 是 `minecraft:potion`，<b>不是</b> `fire_resistance_potion`，
 * 于是这个要求永远满足不了，高风险阶段被无限期挡住。
 *
 * <p>把映射规则放在这里（纯函数、无 Minecraft 依赖）有两个好处：
 * <ol>
 *   <li>能被离线单测钉死，不需要起游戏；</li>
 *   <li>检测侧与预算侧共用同一套构造规则，不会再出现"要求一种键、统计另一种键"。</li>
 * </ol>
 */
public final class AssetKeys {

    private AssetKeys() {
    }

    /**
     * 药水栈 → 预算键。
     *
     * <p>例：效果 id 为 {@code fire_resistance} → {@code minecraft:fire_resistance_potion}；
     * 喷溅/滞留同键（都算"带着这瓶药"）。
     *
     * @param effectPath 效果注册名（如 `fire_resistance`），可带命名空间
     * @return 预算键；效果为空时返回空串（调用方应跳过，不要生成 `minecraft:_potion` 这种假键）
     */
    public static String potionKey(String effectPath) {
        if (effectPath == null) {
            return "";
        }
        String p = effectPath.trim().toLowerCase(java.util.Locale.ROOT);
        if (p.isEmpty()) {
            return "";
        }
        int colon = p.indexOf(':');
        if (colon >= 0) {
            p = p.substring(colon + 1);
        }
        if (p.isEmpty()) {
            return "";
        }
        return "minecraft:" + p + "_potion";
    }
}
