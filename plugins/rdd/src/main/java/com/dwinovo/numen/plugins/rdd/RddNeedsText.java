package com.dwinovo.numen.plugins.rdd;

/**
 * 「装备没穿完 / 该吃东西了」——两条轻提示（2026-10-28，主人点名要的支线提醒）。
 *
 * <h2>为什么是提示而不是新工具</h2>
 * 能力其实已经有了：{@code equip_item} 能把背包里的护甲自动路由到对应槽位，
 * {@code eat} 能吃东西。缺的是**"该做这件事"的提醒**——
 * 主人原话：「增加支线任务提醒如食物不足的补充，装备没穿完的提示」。
 *
 * <h2>两类提示的边界（都只在"确有可行动作"时出现）</h2>
 * <ul>
 *   <li><b>装备</b>：某个护甲槽空着、或身上是低阶、而背包里有更好的 → 提示换；
 *       空槽与背包里没有对应护甲时**不提示**（否则变成噪音）。</li>
 *   <li><b>食物</b>：饱食度低且有吃的 → 提示先吃；饱食度低且背包没吃的 → 提示去弄吃的。
 *       饱食度正常 → 不提示。</li>
 * </ul>
 *
 * <p>纯文本、可单测（不读世界）；读世界在 {@link RddNeedsFacts}。
 */
final class RddNeedsText {

    /** 饱食度低于这个值才提"吃"；20 满、17 开始掉，14 以下会掉血。 */
    static final int HUNGRY_AT = 14;

    /** 明显饿（会掉血）。 */
    static final int STARVING_AT = 7;

    private RddNeedsText() {}

    /**
     * 装备提示。
     *
     * @param slotZh    槽位中文名（头/胸/腿/脚）
     * @param wornZh    身上那件的中文名；空槽传空串
     * @param betterZh  背包里更好的那件中文名
     * @param count     背包里这件有几件
     */
    static String gear(String slotZh, String wornZh, String betterZh, int count) {
        if (betterZh == null || betterZh.isBlank()) return "";
        StringBuilder sb = new StringBuilder("<gear_hint>");
        if (wornZh == null || wornZh.isBlank()) {
            sb.append("你的").append(slotZh).append("是空的，但背包里有 ").append(betterZh);
        } else {
            sb.append("你身上").append(slotZh).append("穿的是").append(wornZh)
              .append("，背包里有更好的 ").append(betterZh);
        }
        if (count > 1) sb.append("（×").append(count).append("）");
        sb.append("。用 equip_item 换上去再干活 —— 护甲没穿上等于没做。")
          .append("</gear_hint>");
        return sb.toString();
    }

    /**
     * 食物提示。
     *
     * @param hunger    当前饱食度（0~20）
     * @param foodCount 背包里可直接吃的件数
     */
    static String food(int hunger, int foodCount) {
        if (hunger >= HUNGRY_AT) return "";
        String level = hunger <= STARVING_AT ? "很饿（" : "有点饿（";
        if (foodCount > 0) {
            return "<food_hint>你现在" + level + hunger + "/20），背包里有 " + foodCount
                    + " 份能直接吃的：先用 eat 吃点再继续，别饿着干活。"
                    + "（饱食度掉到 0 会持续掉血）</food_hint>";
        }
        return "<food_hint>你现在" + level + hunger + "/20），但背包里**没有能直接吃的东西**："
                + "先解决吃的（打猎/摘果子/收作物都行），或者回基地取存粮再继续。"
                + "饿着做危险动作是最亏的死法。</food_hint>";
    }
}
