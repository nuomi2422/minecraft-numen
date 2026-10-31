package com.dwinovo.numen.plugins.learner.verification;

/**
 * 产物分型验证（159 I）：<strong>每种产物用自己的信号</strong>，不共用一套"有效/无效"。
 *
 * <h2>为什么不能共用一套</h2>
 * 自动动作看的是<b>世界前后变化</b>；经验没有"执行"这一步，只能靠<b>匹配基线</b>
 * （同一执行者 + 相近目标 + 相近条件）；支线任务看的是<b>状态机推进到哪</b>，
 * 而且"被正确拒绝"不等于失败（不安全的任务本就该拒）。
 *
 * <p>用一套判，就会出现 158 §8 明令禁止的三种混淆：
 * 「一条经验因为没有回执被当成无效」、「一个被拒绝的支线被算成失败」、
 * 「世界变了就当成规则有效」。
 *
 * <h2>一条贯穿所有类型的原则</h2>
 * <b>不清楚就说不清楚</b>：所有拿不到证据的路径都返回
 * {@link OutcomeVerdict#INCONCLUSIVE} + 具体原因码，绝不默认成任何一档。
 *
 * <p>纯 JVM、无 IO、无时钟，可离线单测。
 */
public final class OutcomeVerifier {

    /**
     * 自动动作的验证事实。
     *
     * @param triggers              触发次数
     * @param diff                  执行前后世界对比
     * @param comparableBaselineRuns 可比基线运行数（同执行者/同条件）
     */
    public record AutoActionFacts(int triggers, BeforeAfter.Diff diff, int comparableBaselineRuns) {
    }

    /**
     * 经验的验证事实（没有"执行"，只能比基线）。
     *
     * @param matchedBaselineRuns 匹配到的基线运行数
     */
    public record ExperienceFacts(int matchedBaselineRuns, boolean sameCompanion,
                                  boolean sameGoal, boolean environmentComparable) {
    }

    private OutcomeVerifier() {
    }

    public static OutcomeVerdict.Result forAutoAction(AutoActionFacts f) {
        if (f == null) {
            return new OutcomeVerdict.Result(OutcomeVerdict.INCONCLUSIVE,
                    OutcomeVerdict.TOO_FEW_SAMPLES, "没有事实可判");
        }
        if (f.triggers() <= 0) {
            // 没触发过就谈不上效果：这是"没发生"，不是"没效果"。
            return new OutcomeVerdict.Result(OutcomeVerdict.INCONCLUSIVE,
                    OutcomeVerdict.NEVER_TRIGGERED, "规则一次都没触发");
        }
        if (f.diff() == null || !f.diff().comparable()) {
            return new OutcomeVerdict.Result(OutcomeVerdict.INCONCLUSIVE,
                    OutcomeVerdict.SNAPSHOT_MISSING, "前后快照缺一侧，比不了");
        }
        if (!f.diff().missing().isEmpty()) {
            // 缺字段不能当"没变化"：那会把"没采到"读成"没发生"。
            return new OutcomeVerdict.Result(OutcomeVerdict.INCONCLUSIVE,
                    OutcomeVerdict.SNAPSHOT_MISSING, "有字段缺失：" + f.diff().missing());
        }
        if (f.diff().anyChange()) {
            // ⚠ 只证明"世界变了"，不证明"变好"。措辞里必须带这一句，
            //   否则下一个人会把它当成功证据。
            return new OutcomeVerdict.Result(OutcomeVerdict.SUPPORTED,
                    OutcomeVerdict.WORLD_CHANGED,
                    "触发后世界确有变化（变了 ≠ 变好，改善与否需另判）：" + f.diff().changed());
        }
        if (f.comparableBaselineRuns() >= 3) {
            return new OutcomeVerdict.Result(OutcomeVerdict.CONTRADICTED,
                    OutcomeVerdict.NO_EFFECT, "触发过、世界无变化，且有 "
                    + f.comparableBaselineRuns() + " 条可比基线");
        }
        return new OutcomeVerdict.Result(OutcomeVerdict.INCONCLUSIVE,
                OutcomeVerdict.TOO_FEW_SAMPLES, "世界无变化，但可比基线只有 "
                + Math.max(0, f.comparableBaselineRuns()) + " 条（需 ≥3）");
    }

    public static OutcomeVerdict.Result forExperience(ExperienceFacts f) {
        if (f == null) {
            return new OutcomeVerdict.Result(OutcomeVerdict.INCONCLUSIVE,
                    OutcomeVerdict.TOO_FEW_SAMPLES, "没有事实可判");
        }
        if (f.matchedBaselineRuns() <= 0) {
            return new OutcomeVerdict.Result(OutcomeVerdict.INCONCLUSIVE,
                    OutcomeVerdict.TOO_FEW_SAMPLES, "找不到匹配基线");
        }
        if (!f.sameCompanion()) {
            // 换个同伴做基线，等于拿别人的身体当对照。
            return new OutcomeVerdict.Result(OutcomeVerdict.INCONCLUSIVE,
                    OutcomeVerdict.INCOMPARABLE_ENVIRONMENT, "基线来自另一个同伴");
        }
        if (!f.environmentComparable()) {
            return new OutcomeVerdict.Result(OutcomeVerdict.INCONCLUSIVE,
                    OutcomeVerdict.INCOMPARABLE_ENVIRONMENT, "环境不可比");
        }
        if (!f.sameGoal()) {
            // 目标不同时，"做得更好"会被目标差异解释掉。
            return new OutcomeVerdict.Result(OutcomeVerdict.INCONCLUSIVE,
                    OutcomeVerdict.ALTERNATIVE_EXPLANATION, "目标不同，差异可被目标本身解释");
        }
        return new OutcomeVerdict.Result(OutcomeVerdict.SUPPORTED,
                "matched_baseline", "同执行者 / 同目标 / 环境可比，且有 "
                + f.matchedBaselineRuns() + " 条基线");
    }

    /**
     * 支线任务：看状态机推进，不把"被拒绝"当失败。
     *
     * @param accepted   是否被接受
     * @param reachedStep 推进到第几步（0 = 没推进）
     * @param finalStep   完成条件所在的步
     */
    public static OutcomeVerdict.Result forSideTask(boolean accepted, int reachedStep, int finalStep) {
        if (!accepted) {
            // 不安全的任务本就该被拒；把拒绝算失败会惩罚正确的判断。
            return new OutcomeVerdict.Result(OutcomeVerdict.INCONCLUSIVE,
                    "not_accepted", "支线未被接受（可能是正确的拒绝）");
        }
        if (reachedStep <= 0) {
            return new OutcomeVerdict.Result(OutcomeVerdict.INCONCLUSIVE,
                    "no_progress", "被接受但没有推进");
        }
        if (reachedStep >= finalStep) {
            return new OutcomeVerdict.Result(OutcomeVerdict.SUPPORTED,
                    "completed", "走到完成条件（第 " + reachedStep + " 步）");
        }
        return new OutcomeVerdict.Result(OutcomeVerdict.INCONCLUSIVE,
                "stalled", "推进到第 " + reachedStep + " / " + finalStep + " 步后停了：中途≠失败");
    }
}
