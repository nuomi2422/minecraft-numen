package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.policy.RequirementManifest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * \u5143\u4ef6\u68c0\u6d4b \u00b7 \u4e00\u7ea7\u76ee\u6807\u8d44\u4ea7\u8981\u6c42\u68c0\u6d4b\uff08\u63d2\u4ef6\u4fa7\u63a5\u7ebf\uff09\u3002
 *
 * <p>\u3010\u7528\u6237\u67b6\u6784\u6982\u5ff5 2/4 \u7684\u63a5\u7ebf\u3011\u4e00\u7ea7\u5927\u76ee\u6807\u751f\u6210\u65f6\u540c\u6b65\u4ea7\u51fa"\u9700\u6c42\u6e05\u5355"\uff08RequirementManifest\uff09\uff0c
 * \u6301\u7eed\u7c97\u7c92\u5ea6\u68c0\u6d4b\u662f\u5426\u6ee1\u8db3\uff1b\u6ee1\u8db3\u5219\u4ea7<b>\u4e8b\u5b9e</b>\uff08\u4e0d\u51b3\u5b9a\u662f\u5426\u91cd\u89c4\u5212\u2014\u2014\u90a3\u662f Supervisor\uff09\u3002
 *
 * <p>\u4ece\u4e00\u7ea7\u76ee\u6807\u7684 subtasks \u7684 condition\uff08asset_key/minimum \u6216 group\uff09\u63d0\u53d6\u9700\u6c42\u3002
 * \u7eaf\u903b\u8f91\u5728 rdd-core \u7684 {@link RequirementManifest}\uff1b\u672c\u7c7b\u53ea\u505a\u5bbf\u4e3b\u4fa7\u63d0\u53d6\u4e0e\u68c0\u6d4b\u3002
 * \u4e0d\u6539 TaskChain \u6838\u5fc3\u3002
 */
final class RddRequirementDetector {

    private RddRequirementDetector() {}

    /** \u4ece\u4e00\u7ea7\u76ee\u6807\u63d0\u53d6\u9700\u6c42\u6e05\u5355\uff08\u9700\u6c42\u4fa7\uff1bgroup \u6761\u4ef6\u6682\u4e0d\u8ba1\uff0c\u7b2c\u4e8c\u6279\u6269\u5c55\uff09\u3002 */
    static RequirementManifest.Manifest forPrimary(PrimaryGoal primary) {
        List<RequirementManifest.Requirement> reqs = new ArrayList<>();
        if (primary != null) {
            for (Subtask s : primary.subtasks()) {
                Map<String, Object> c = s.condition();
                Object key = c == null ? null : c.get("asset_key");
                if (key instanceof String ks && !ks.isBlank()) {
                    int min = 1;
                    Object m = c.get("minimum");
                    if (m instanceof Number n) min = Math.max(0, n.intValue());
                    reqs.add(new RequirementManifest.Requirement(ks, min, List.of()));
                }
            }
        }
        String goalId = primary == null ? "unknown" : primary.id();
        return new RequirementManifest.Manifest(goalId, reqs);
    }

    /**
     * \u68c0\u6d4b\u4e00\u7ea7\u9700\u6c42\u662f\u5426\u6ee1\u8db3\uff0c\u4ea7\u4e8b\u5b9e\u5e76\u4e0a\u62a5\uff08\u4e0d\u91cd\u89c4\u5212\uff09\u3002
     * @return true = \u5168\u90e8\u6ee1\u8db3\uff08\u5df2\u4e0a\u62a5 requirements_met\uff09
     */
    static boolean detectAndPublish(java.util.UUID companionId, PrimaryGoal primary, Map<String, Integer> held) {
        RequirementManifest.Manifest manifest = forPrimary(primary);
        RequirementManifest.Detection d = manifest.detect(held);
        if (d.satisfied()) {
            RddMonitor.publish("requirements_met", Map.of(
                    "companionId", String.valueOf(companionId),
                    "primary", manifest.goalId(),
                    "requirements", String.valueOf(manifest.requirements().size())));
            return true;
        }
        return false;
    }
}
