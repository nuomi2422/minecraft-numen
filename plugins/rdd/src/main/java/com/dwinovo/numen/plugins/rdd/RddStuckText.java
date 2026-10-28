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
     * @param condType  当前二级的条件类型（block_nearby / container_nearby / position_at …）
     */
    static String block(int minutes, int assets, boolean sameAssets, String step, String condType) {
        if (minutes < STUCK_SECONDS / 60 || !sameAssets) return "";
        String s = step == null ? "" : step;
        String ct = condType == null ? "" : condType;
        StringBuilder advice = new StringBuilder();
        // 2026-10-28 实机：这一步的判据是「**附近**有黑曜石(半径16)」，而她人在 200 格外的村庄，
        // 于是连续 failed/stalled —— 这类条件失败不是"没干活"，是"站错地方"，建议必须是"走过去"。
        if (ct.endsWith("_nearby") || "position_at".equals(ct)) {
            advice.append("这一条的判据是「**你在目标附近**」（").append(ct)
                  .append("），所以光在原地挖/查没用：先用 locate_block / locate_structure 查最近的")
                  .append("目标在哪，再 goto 过去，到了再判定。前面如果已经找到过位置，就直接回去。");
        } else if (s.contains("地下") || s.contains("Y≤") || s.contains("y≤") || s.contains("矿洞") || s.contains("岩浆")) {
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
                // 2026-10-28 实机：她收到提醒后确实 mine 了，但紧接着 task_stop 把自己叫停（两次），
                // 资产一直没变 —— 把"异步受理 ≠ 做完"这条事实加进来，免得她以为没进展就换路。
                + "**动手之后要等它跑完**：mine/goto 这类是异步的，回执写「已受理」只代表开始，"
                + "不是说没进展；不要立刻 task_stop 换个动作——频繁叫停等于一直没干活。"
                + "查状态不会让进度前进，只有挖/走/放/打会。</stuck_hint>";
    }

    /** 兼容旧调用：不知道条件类型时按步骤文字给建议。 */
    static String block(int minutes, int assets, boolean sameAssets, String step) {
        return block(minutes, assets, sameAssets, step, "");
    }

    /**
     * 「整条链在原地」——跨步骤看门狗。
     *
     * <p>实机（2026-10-28）：链在 30 分钟里从 r5 重规划到 r11，**每一步都换了**，
     * 于是步骤级计时永远攒不满；而她的背包从头到尾 157~159 件，一次都没有真正推进。
     * 这种情况要说的是"你换了很多步但产出为零"，而不是"这一步做久了"。
     */
    static String noProgress(int minutes, int assets, String step) {
        String s = step == null ? "" : step;
        StringBuilder sb = new StringBuilder("<progress_hint>");
        sb.append("过去 ").append(minutes).append(" 分钟，你的背包一直是 ").append(assets)
          .append(" 件，**一件新东西都没有** —— 不管计划换了几步，你其实没有推进。");
        sb.append("现在的卡点是什么？说清楚再动：缺什么（工具/材料/食物）、")
          .append("还是到不了（路被挡/太远/找不到）。");
        if (s.contains("黑曜石") || s.contains("岩浆")) {
            sb.append("眼前这类目标还有个更省事的路子：**废墟传送门的黑曜石框可以直接挖走**，")
              .append("比自己浇岩浆再挖省事得多。");
        }
        sb.append("如果反复走同一条路都失败，换一条完全不同的路（换来源/换维度/先去补装备），")
          .append("不要在同一个地方加码。</progress_hint>");
        return sb.toString();
    }
}
