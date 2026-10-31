package com.dwinovo.numen.plugins.learner.verification;

/**
 * 产物分型验证的三档结论（158 §8，159 I）。
 *
 * <h2>为什么必须是三档</h2>
 * <ul>
 *   <li>{@link #SUPPORTED} —— 有匹配基线、差异可归因；</li>
 *   <li>{@link #INCONCLUSIVE} —— 样本不足 / 环境不可比 / 存在替代解释；</li>
 *   <li>{@link #CONTRADICTED} —— 有反例且无法用条件差异解释。</li>
 * </ul>
 * 只有两档时，"证据不够"会被塞进任一档：塞进 SUPPORTED 就是虚报，
 * 塞进 CONTRADICTED 就是冤枉 —— 两者都会让后续修订建立在错误前提上。
 *
 * <h2>INCONCLUSIVE 必须带原因码</h2>
 * 不分原因，"不清楚"会变成垃圾桶，永远没人去查到底是样本不够还是环境不可比。
 *
 * <p>纯 JVM，可离线单测。
 */
public enum OutcomeVerdict {

    SUPPORTED,
    INCONCLUSIVE,
    CONTRADICTED;

    /** 原因码（稳定字符串，给统计用）。 */
    public static final String NEVER_TRIGGERED = "never_triggered";
    public static final String TOO_FEW_SAMPLES = "too_few_samples";
    public static final String INCOMPARABLE_ENVIRONMENT = "incomparable_environment";
    public static final String ALTERNATIVE_EXPLANATION = "alternative_explanation";
    public static final String SNAPSHOT_MISSING = "snapshot_missing";
    public static final String WORLD_CHANGED = "world_changed";
    public static final String NO_EFFECT = "no_effect";

    /**
     * 一条判定。
     *
     * @param code 稳定原因码；SUPPORTED 时也要有（说明"凭什么算支持"）
     */
    public record Result(OutcomeVerdict verdict, String code, String detail) {
        public Result {
            code = code == null ? "" : code;
            detail = detail == null ? "" : detail;
        }

        public boolean conclusive() {
            return verdict != OutcomeVerdict.INCONCLUSIVE;
        }
    }
}
