package com.dwinovo.numen.rdd.policy;

import java.util.ArrayList;
import java.util.List;

/**
 * \u8d44\u4ea7\u53ef\u7528\u8c13\u8bcd\uff08Available Predicate\uff09\u2014\u2014\u300c\u6ee1\u8db3 != \u53ef\u7528\u300d\u7684\u5355\u4e00\u5224\u5b9a\u5b9e\u73b0\u3002
 *
 * <p>\u3010W \u5ba1\u6838\u8865\u7f3a\u3011\u68c0\u6d4b\u5230"\u6709 3 \u4e2a\u6728\u5934"\u4e0d\u7b49\u4e8e"\u80fd\u62ff\u6765\u7528"\uff1a\u8fd8\u9700\u591f\u6570\u3001\u53ef\u8fbe\u3001\u5de5\u5177\u9002\u914d\u3001
 * \u73af\u5883\u5b89\u5168\u3001\u80cc\u5305\u6709\u7a7a\u4f4d\u3001\u6709\u6743\u9650\u3002\u672c\u8c13\u8bcd<b>\u5fc5\u987b\u4e0e\u6267\u884c\u524d\u7f6e\u6821\u9a8c\u5171\u7528\u4e00\u4efd\u5b9e\u73b0</b>\uff0c
 * \u5426\u5219"\u68c0\u6d4b\u8bf4 SATISFIED\uff0c\u6267\u884c\u5374\u5931\u8d25"\uff08\u4e24\u5957\u5224\u5b9a\u6f02\u79fb\uff09\u3002
 *
 * <p>\u7eaf JVM\u3001\u53ef\u5355\u6d4b\uff1b\u5bbf\u4e3b\u628a\u771f\u5b9e\u4e16\u754c\u67e5\u8be2\u7ed3\u679c\u5305\u88c5\u6210 {@link Facts} \u4f20\u5165\u3002
 */
public final class AvailablePredicate {

    private AvailablePredicate() {}

    /**
     * \u53ef\u7528\u6027\u4e8b\u5b9e\uff08\u7531\u5bbf\u4e3b\u4ece\u771f\u5b9e\u4e16\u754c\u586b\u5145\uff1b\u7f3a\u7701\u4fdd\u5b88 = \u4e0d\u53ef\u7528\uff09\u3002
     *
     * @param exists         \u8d44\u4ea7\u662f\u5426\u5b58\u5728\u4e8e\u4e16\u754c/\u80cc\u5305
     * @param count          \u73b0\u6709\u6570\u91cf
     * @param required       \u9700\u8981\u6570\u91cf
     * @param reachable      \u662f\u5426\u53ef\u8fbe\uff08\u80fd\u8d70\u5230/\u591f\u5230\uff09
     * @param toolSatisfied  \u5f53\u524d\u5de5\u5177\u662f\u5426\u80fd\u91c7\u96c6/\u4f7f\u7528\u5b83
     * @param safeEnough     \u73af\u5883\u662f\u5426\u8db3\u591f\u5b89\u5168\uff08\u65e0\u5ca9\u6d46/\u654c\u5bf9/\u7a92\u606f\u7b49\uff09
     * @param inventorySpace \u80cc\u5305\u662f\u5426\u6709\u4f4d\uff08\u62fe\u53d6/\u643a\u5e26\uff09
     * @param permitted      \u662f\u5426\u6709\u6743\u9650\uff08\u9886\u5730/\u89c4\u5219\u5141\u8bb8\uff09
     */
    public record Facts(boolean exists, int count, int required, boolean reachable,
                        boolean toolSatisfied, boolean safeEnough, boolean inventorySpace,
                        boolean permitted) {}

    /** \u5224\u5b9a\u7ed3\u679c\uff1a\u662f\u5426\u53ef\u7528 + \u672a\u901a\u8fc7\u7684\u539f\u56e0\u5217\u8868\u3002 */
    public record Verdict(boolean available, List<String> reasons) {}

    /** \u5168\u6761\u4ef6\u4e0e\u5224\u5b9a\uff1aavailable = exists && enough && reachable && tool && safe && space && permitted\u3002 */
    public static Verdict check(Facts f) {
        List<String> reasons = new ArrayList<>();
        if (f == null) {
            reasons.add("no facts");
            return new Verdict(false, List.copyOf(reasons));
        }
        if (!f.exists()) reasons.add("not_exists");
        if (f.count() < f.required()) reasons.add("not_enough(" + f.count() + "/" + f.required() + ")");
        if (!f.reachable()) reasons.add("unreachable");
        if (!f.toolSatisfied()) reasons.add("tool_unsatisfied");
        if (!f.safeEnough()) reasons.add("unsafe");
        if (!f.inventorySpace()) reasons.add("no_inventory_space");
        if (!f.permitted()) reasons.add("not_permitted");
        return new Verdict(reasons.isEmpty(), List.copyOf(reasons));
    }

    /** \u4fbf\u6377\uff1a\u662f\u5426\u53ef\u7528\u3002 */
    public static boolean available(Facts f) {
        return check(f).available();
    }
}
