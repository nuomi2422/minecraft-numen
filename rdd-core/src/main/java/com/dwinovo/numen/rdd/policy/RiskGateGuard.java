package com.dwinovo.numen.rdd.policy;

/**
 * 风险门的「事实判定 × 观测异常」策略（037/T7 · SAFE-01，2026-11-04）。
 *
 * <p>为什么把它单独拿出来：{@code RddDetector.riskGateAllows} 原来是"先把事实全采完，
 * 外面包一层 catch，异常就 return true"。这等于说：<b>观测越糟，危险任务越容易放行</b>——
 * 背包读不出来、基地注册表坏了、床坐标查询抛了，都会变成"放行下界/末地"。
 *
 * <p>这里只做三件事，且都是纯 JVM、可注入异常：
 * <ol>
 *   <li>非高风险级（NORMAL/MINING）原样放行，**不去碰任何观测**（已有行为不被无差别封死）；</li>
 *   <li>高风险级拿到裁决就按裁决走，不额外收紧也不放松；</li>
 *   <li>高风险级观测失败 → 拒绝，并给出可诊断的 error 文本；**不落永久锁**，
 *       下一次判定会重新采观测，成功即恢复。</li>
 * </ol>
 */
public final class RiskGateGuard {

    private RiskGateGuard() {}

    /** 采集风险门所需事实并给出裁决；允许抛 {@link RuntimeException}（由本类兜）。 */
    public interface Facts {
        RiskGate.Verdict evaluate(RiskLevel level);
    }

    /**
     * @param allowed 最终是否放行
     * @param level   风险级（分类失败时为 null）
     * @param reason  {@code not_risky} / {@code allowed} / {@code blocked} / {@code gate_exception} / {@code classify_exception}
     * @param verdict 正常裁决；异常路径为 null
     * @param error   异常摘要（非异常路径为 null）
     */
    public record Decision(boolean allowed, RiskLevel level, String reason,
                           RiskGate.Verdict verdict, String error) {
    }

    /** 这一级是否属于"高风险"（本批只管下界/末地）。 */
    public static boolean isRisky(RiskLevel level) {
        return level == RiskLevel.NETHER || level == RiskLevel.END;
    }

    /** 高风险级的判定；观测抛异常时按"不知道就不许去"处理，但下一轮会自动重试。 */
    public static Decision decide(RiskLevel level, Facts facts) {
        if (!isRisky(level)) {
            return new Decision(true, level, "not_risky", null, null);
        }
        try {
            RiskGate.Verdict verdict = facts == null ? null : facts.evaluate(level);
            if (verdict == null) {
                return new Decision(false, level, "gate_exception", null, "risk rules returned null verdict");
            }
            return new Decision(verdict.allowed(), level, verdict.allowed() ? "allowed" : "blocked", verdict, null);
        } catch (RuntimeException ex) {
            return new Decision(false, level, "gate_exception", null, describe(ex));
        }
    }

    /** 风险分类本身失败：不知道是哪一级，同样不许当"不危险"放行。 */
    public static Decision classifyFailed(RuntimeException ex) {
        return new Decision(false, null, "classify_exception", null, describe(ex));
    }

    private static String describe(RuntimeException ex) {
        String s = String.valueOf(ex);
        return s.length() > 160 ? s.substring(0, 160) : s;
    }
}
