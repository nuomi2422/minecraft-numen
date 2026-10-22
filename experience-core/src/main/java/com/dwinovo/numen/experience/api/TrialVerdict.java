package com.dwinovo.numen.experience.api;

/**
 * G6c：试用期结束后「这条经验该往哪走」的判定。
 *
 * <h2>为什么是纯函数</h2>
 * 判据（C3/C4）必须能离线钉死。放进插件里就变成「跑一次看结果」，
 * 而这一条的要点恰恰是**样本不足时不许猜**。
 *
 * <h2>规则（写死，不许按结果放宽）</h2>
 * <pre>
 * 用过 0 次            → KEEP_TRIAL（没证据）
 * 用过 &lt; MIN_SAMPLES → KEEP_TRIAL（样本不足 —— 1 成 1 败落这里，即 C4）
 * 一次没成过（0 成功）  → SUSPEND     （失败不该被平均分掩盖）
 * 成功多于失败         → SUGGEST_PUBLISH（**只是建议**，发布必须人批）
 * 其余                → KEEP_TRIAL
 * </pre>
 *
 * <p><b>机器永远不自己把它改成 PUBLISHED</b>：本类只产出 {@link Decision#SUGGEST_PUBLISH}，
 * 改状态仍由 `experience_publish` 那一步（人/AI 显式调用）完成。
 */
public final class TrialVerdict {

    /** 判定结果。 */
    public enum Decision {
        /** 继续试用（样本不足或还看不出好坏）。 */
        KEEP_TRIAL,
        /** 建议暂停（用了多次一次没成）。 */
        SUSPEND,
        /** 建议转正（成功多于失败）—— 只是建议，发布要人批。 */
        SUGGEST_PUBLISH
    }

    /** 至少攒这么多次使用才允许下结论。1 成 1 败不算证据。 */
    public static final int MIN_SAMPLES = 3;

    private TrialVerdict() {}

    public static Decision decide(TrialStats s) {
        if (s == null || s.uses() <= 0) {
            return Decision.KEEP_TRIAL;
        }
        // ★ 只看**结论性**样本：取消/中断既不算成功也不算失败，不能拿它凑数。
        //   （这是 G6c 写测试时抓到的：3 次里 1 成 2 取消，按 uses 算够 3 次，实际只有 1 条证据。）
        if (s.successes() + s.failures() < MIN_SAMPLES) {
            return Decision.KEEP_TRIAL;   // 结论性样本不足 → 不猜（C4）
        }
        if (s.successes() == 0) {
            return Decision.SUSPEND;      // 一次没成过 → 停（失败不被平均掩盖）
        }
        if (s.successes() > s.failures()) {
            return Decision.SUGGEST_PUBLISH;
        }
        return Decision.KEEP_TRIAL;
    }

    /** 一句话解释，写进事件与回执（人看得懂，不用去翻规则）。 */
    public static String explain(TrialStats s, Decision d) {
        if (s == null) return "没有使用记录 → 继续试用";
        return switch (d) {
            case KEEP_TRIAL -> s.successes() + s.failures() < MIN_SAMPLES
                    ? "结论性样本不足（" + (s.successes() + s.failures()) + "/" + MIN_SAMPLES + "）→ 继续试用，不猜"
                    : "成功 " + s.successes() + " / 失败 " + s.failures() + "，看不出优势 → 继续试用";
            case SUSPEND -> "用了 " + s.uses() + " 次一次没成（失败 " + s.failures() + "）→ 建议暂停";
            case SUGGEST_PUBLISH -> "成功 " + s.successes() + " > 失败 " + s.failures()
                    + "（共 " + s.uses() + " 次）→ 建议转正（需人批）";
        };
    }
}
