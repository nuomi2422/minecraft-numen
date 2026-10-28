package com.dwinovo.numen.plugins.rdd;

/**
 * 「目标与阶段」软注入（2026-10-28，主人定的口径）。
 *
 * <h2>为什么要有它</h2>
 * 主人原话：「任务链就是直接给他一个提示，然后干活肯定是模型自己做」——
 * 链条的职责是**让人知道现在在哪、要往哪去**，不是替她把数量钉死。
 * 之前的毛病是「提前写好要多少个」，到了地方才发现根本凑不出（例如黑曜石要 10、手上 7），
 * 于是条件永远 false、步骤 FAILED、人就停在那儿。
 *
 * <h2>这段话给什么</h2>
 * <ul>
 *   <li><b>总目标</b>（她为什么在干这件事，跨阶段不丢）；</li>
 *   <li><b>当前阶段</b>（七级里的哪一级），并明说**阶段划分可以按现场调整**；</li>
 *   <li><b>当前步骤</b>；</li>
 *   <li>一句判据口径：<b>数量到现场再定</b>，够推进就推进；判据只是"这一步算不算完"的参考，
 *       不是"必须凑够 N 个"的硬指标。</li>
 * </ul>
 *
 * 纯文本、有界，不读世界（保证可单测、不引 Entity 类）。
 */
final class RddGoalHint {

    /** 单块上限，防止长目标把上下文吃掉。 */
    private static final int MAX_FIELD = 120;

    private RddGoalHint() {}

    /**
     * @param totalGoal 总目标（可为空）
     * @param stage     当前一级（阶段）描述（可为空）
     * @param step      当前二级（步骤）描述（可为空）
     * @return 注入块；三者全空时返回空串（零注入）
     */
    static String block(String totalGoal, String stage, String step) {
        String g = clip(totalGoal);
        String s = clip(stage);
        String t = clip(step);
        if (g.isEmpty() && s.isEmpty() && t.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("<goals>");
        if (!g.isEmpty()) sb.append("总目标：").append(g).append('；');
        if (!s.isEmpty()) sb.append("当前阶段：").append(s).append('；');
        if (!t.isEmpty()) sb.append("当前步骤：").append(t).append('；');
        sb.append("判据口径：数量分两类 —— **配方/规则能算死的按实数办**（钻石镐 3 钻、下界门 10 黑曜石、"
          + "末影之眼 12）；**只有到现场才知道多少的**（这条矿脉几块矿、这片林子多少木头、附近几只动物）"
          + "先走过去看清，再决定挖/采到什么程度，够用就走，不必为凑一个预先写死的大数字反复折返。")
          .append("阶段与步骤是路线图，不是硬指标：现场条件变了（怪物、岩浆、缺工具）就按实际调整顺序或换来源。")
          // 2026-10-28 实机（#256）：她返程 450 格，却每回合只走十几格、中间被"查状态"占满，
          // 9 分钟位移 12 格。长距离移动应当一次派到底，中途不必反复确认。
          .append("长距离移动要一次派到底：goto 到目标坐标后等它跑完（异步受理只代表开始），")
          .append("中途不需要反复查状态/背包——走一段看一眼就够，别把一次长途拆成十几小段。")
          .append("</goals>");
        return sb.toString();
    }

    private static String clip(String s) {
        if (s == null) return "";
        String v = s.replace('\n', ' ').replace('\r', ' ').trim();
        if (v.isEmpty()) return "";
        return v.length() <= MAX_FIELD ? v : v.substring(0, MAX_FIELD) + "…";
    }
}
