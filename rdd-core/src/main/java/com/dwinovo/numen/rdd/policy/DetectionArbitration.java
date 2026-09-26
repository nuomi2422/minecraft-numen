package com.dwinovo.numen.rdd.policy;

import com.dwinovo.numen.rdd.api.SupervisorDecisionType;

import java.util.List;

/**
 * \u68c0\u6d4b\u4e8b\u5b9e\u7684\u88c1\u51b3\u7b56\u7565\uff08Detection Arbitration\uff09\u2014\u2014\u300c\u68c0\u6d4b != \u91cd\u89c4\u5212\u300d\u7684<b>\u53e6\u4e00\u534a</b>\u3002
 *
 * <p>\u3010W \u5ba1\u6838\u8865\u7f3a\u3011{@link RequirementManifest} \u53ea\u4ea7\u68c0\u6d4b<b>\u4e8b\u5b9e</b>\uff1b\u672c\u7c7b\u628a\u4e8b\u5b9e\u8f6c\u6210
 * {@link SupervisorDecisionType} \u88c1\u51b3\uff08\u8c01\u51b3\u5b9a"\u6807\u8bb0\u5df2\u6709 vs \u91cd\u89c4\u5212"\uff09\u3002\u7eaf\u51fd\u6570\uff1a\u65e0 LLM\u3001\u65e0 MC\u3001\u53ef\u5355\u6d4b\u3002
 *
 * <p>\u4e5d\u5206\u652f\u7cbe\u7b80\u5230\u73b0\u6709 5 \u578b\uff08rdd-core api \u65e2\u6709\u7684 SupervisorDecisionType\uff09\uff1a
 * CONFIRM\uff08\u6807\u8bb0\u5df2\u6709\uff09/ REJECT\uff08\u4fdd\u6301\u73b0\u72b6\uff09/ NEED_MORE_EVIDENCE\uff08\u4fe1\u606f\u4e0d\u8db3\uff0c\u5148\u4fa6\u5bdf\uff09
 * / REPLAN\uff08\u503c\u5f97\u6539\u89c4\u5212\uff09/ DEFER\uff08\u672a\u7a33\u5b9a\uff0c\u89c2\u671b\uff09\u3002
 */
public final class DetectionArbitration {

    private DetectionArbitration() {}

    /** \u88c1\u51b3\u8f93\u5165\u4e8b\u5b9e\uff1a\u9700\u6c42\u662f\u5426\u5168\u6ee1\u8db3 + \u7f3a\u53e3\u6570 + \u662f\u5426\u53ef\u4fa6\u5bdf + \u662f\u5426\u7a33\u5b9a(eval-ready)\u3002 */
    public record Facts(boolean allSatisfied, int gapCount, boolean reconAvailable, boolean settled) {
        public static Facts of(RequirementManifest.Detection d, boolean reconAvailable, boolean settled) {
            boolean all = d != null && d.satisfied();
            int gaps = (d == null || d.gaps() == null) ? 0 : d.gaps().size();
            return new Facts(all, gaps, reconAvailable, settled);
        }
    }

    /**
     * \u88c1\u51b3\uff1a\u628a\u68c0\u6d4b\u4e8b\u5b9e\u8f6c\u6210 Supervisor \u51b3\u7b56\u7c7b\u578b\u3002
     * <ul>
     *   <li>\u672a\u7a33\u5b9a -> DEFER\uff08\u7b49\u4e0b\u4e00\u8f6e\uff0c\u4e0d\u57fa\u4e8e\u534a\u622a\u4e8b\u5b9e\u51b3\u7b56\uff09</li>
     *   <li>\u5168\u6ee1\u8db3 -> CONFIRM\uff08\u6807\u8bb0\u5df2\u6709\uff0c\u4e0d\u52a8\u5927\u89c4\u5212\uff09</li>
     *   <li>\u6709\u7f3a\u53e3\u4e14\u53ef\u4fa6\u5bdf -> NEED_MORE_EVIDENCE\uff08\u5148\u4fa6\u5bdf\u8865\u9f50\u4fe1\u606f\uff0c\u9632\u6296\u52a8\u5347\u7ea7\uff09</li>
     *   <li>\u6709\u7f3a\u53e3\u3001\u4e0d\u53ef\u4fa6\u5bdf\u3001\u7f3a\u53e3\u660e\u786e -> REPLAN\uff08\u503c\u5f97\u6539\u89c4\u5212\uff09</li>
     *   <li>\u6709\u7f3a\u53e3\u4f46\u65e0\u4e8b\u53ef\u505a -> REJECT\uff08\u4fdd\u6301\u73b0\u72b6\uff09</li>
     * </ul>
     */
    public static SupervisorDecisionType arbitrate(Facts f) {
        if (f == null) return SupervisorDecisionType.DEFER;
        if (!f.settled()) return SupervisorDecisionType.DEFER;
        if (f.allSatisfied()) return SupervisorDecisionType.CONFIRM;
        if (f.gapCount() > 0) {
            if (f.reconAvailable()) return SupervisorDecisionType.NEED_MORE_EVIDENCE;
            return SupervisorDecisionType.REPLAN;
        }
        return SupervisorDecisionType.REJECT;
    }

    /** \u662f\u5426\u9700\u8981\u52a8\u5927\u89c4\u5212\uff08REPLAN \u624d\u52a8\uff09\u3002\u7528\u4e8e"\u68c0\u6d4b\u53ea\u4ea7\u4e8b\u5b9e\u3001\u51b3\u7b56\u624d\u6539\u89c4\u5212"\u7684\u8fb9\u754c\u6838\u5bf9\u3002 */
    public static boolean changesPlan(SupervisorDecisionType t) {
        return t == SupervisorDecisionType.REPLAN;
    }

    /** \u51b3\u7b56\u7406\u7531\uff08\u4eba\u7c7b\u53ef\u8bfb\uff0c\u4fbf\u4e8e\u57cb\u70b9/\u76d1\u6d4b\u53f0\uff09\u3002 */
    public static String reasonOf(Facts f, SupervisorDecisionType t) {
        if (t == null) return "no decision";
        return switch (t) {
            case CONFIRM -> "requirements satisfied; mark as held, do not replan";
            case NEED_MORE_EVIDENCE -> "gaps present but recon possible; insert recon first";
            case REPLAN -> "gaps present and not recon-able; replan justified";
            case REJECT -> "no actionable gap; keep current";
            case DEFER -> "facts not settled; wait";
        };
    }
}
