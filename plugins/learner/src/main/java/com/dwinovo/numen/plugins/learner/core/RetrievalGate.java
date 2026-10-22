package com.dwinovo.numen.plugins.learner.core;

/**
 * G6b 的**闸门**：写经验之前，必须已经看过「库里有什么」，而且那个判断要过机核。
 *
 * <p><b>为什么闸门单独成类</b>：它要在两个地方被问到 ——
 * ①`LearnerReviewTool` 收尾时决定这条 memo 能不能 commit；
 * ②单测里直接问「这种判定该不该放行」。
 * 埋在工具类里就没法单测，而这一批的判据（Z2/Z3）恰恰是「决策合不合法」。
 *
 * <h2>只对「写经验」这条动作设闸</h2>
 * {@code NO_ACTION} / {@code USE_AC} / {@code NEEDS_HUMAN} 这些不产生新经验，
 * 不该被「先检索」卡住 —— 否则闸门会变成对所有复盘的普遍拖累。
 */
public final class RetrievalGate {

    private RetrievalGate() {}

    /**
     * @return {@code null} = 放行；否则是给人看的拒因（写进回执与监测事件）
     */
    public static String problem(Verdict v) {
        if (v == null) {
            return "没有判定";
        }
        if (!v.actions().contains(Verdict.Action.WRITE_EXPERIENCE)) {
            return null;   // 不写经验 → 不需要「先检索」
        }
        RetrievalDecision d = v.retrieval();
        if (d == null) {
            return "缺 retrieval 段：写经验前必须先给出「复用 / 修订 / 新建」的决策与理由（G6b）";
        }
        return d.problem();
    }

    /** 放行与否（{@link #problem(Verdict)} == null）。 */
    public static boolean allows(Verdict v) {
        return problem(v) == null;
    }
}
