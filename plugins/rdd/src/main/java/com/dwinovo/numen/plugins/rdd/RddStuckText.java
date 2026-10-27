package com.dwinovo.numen.plugins.rdd;

/**
 * 「你在空转」提示（2026-10-28，新目标第 2 轮实机观察）。
 *
 * <h2>实机证据（不是猜的）</h2>
 * 链停在「深入地下 Y≤30 找岩浆」这一步时，她的连续工具调用是：
 * <pre>
 *   rdd_status → scan_blocks → look_around → scan_blocks → get_self_status → rdd_status …
 * </pre>
 * 全是**读**，没有一次改变世界（没有 mine / goto / build）。背包资产 159 十几分钟没变。
 *
 * <p>这与死亡分析里那条「1783 次调用里 54% 是 rdd_get_inventory + task_status」是同一个病：
 * **上下文里没有"你已经原地打转"这条事实**，于是查询比动手更"安全"。
 *
 * <h2>这段话做什么</h2>
 * 当同一个二级持续超过阈值、且资产数一点没变时，直接把"你在空转"告诉她，并给出**具体可做的动作**
 * （这步是往地下走，就给 goto/mine；有明确条件就照着条件做），而不是泛泛地说"加油"。
 *
 * <p>纯文本、有界，不读世界。
 */
final class RddStuckText {

    /** 同一二级超过这么多秒且资产没变 → 认定空转（300 秒 = 5 分钟）。 */
    static final int STUCK_SECONDS = 300;

    private RddStuckText() {}

    /**
     * @param minutes   同一二级已持续多少分钟
     * @param assets    当前资产数
     * @param sameAssets 这段时间资产数有没有变化
     * @param step      当前二级描述（用来给出对症的动作）
     */
    static String block(int minutes, int assets, boolean sameAssets, String step) {
        if (minutes < STUCK_SECONDS / 60 || !sameAssets) return "";
        String s = step == null ? "" : step;
        StringBuilder advice = new StringBuilder();
        // 按这一步在干什么给动作，不给空话
        if (s.contains("地下") || s.contains("Y≤") || s.contains("y≤") || s.contains("矿洞") || s.contains("岩浆")) {
            advice.append("往下走/往下挖：goto 到附近的洞口，或在脚下安全开挖（先挖两格看脚下，别垂直挖穿）");
        } else if (s.contains("找") || s.contains("前往") || s.contains("到达")) {
            advice.append("直接 goto 过去，到了再用 scan 看；不要站在原地点名");
        } else if (s.contains("凑") || s.contains("采集") || s.contains("挖")) {
            advice.append("拿起镐直接 mine，挖到就进背包；缺工具就先合成");
        } else {
            advice.append("挑一个能改变世界的动作执行（goto / mine / build / attack），别再读状态");
        }
        return "<stuck_hint>这一步已经过了 " + minutes + " 分钟，背包资产一直是 " + assets
                + " 件没有变化 —— 你大概率在**空转**（反复查状态/扫描但没动手）。"
                + "下一条不要再查了，直接做：" + advice + "。"
                + "查状态不会让进度前进，只有挖/走/放/打会。</stuck_hint>";
    }
}
