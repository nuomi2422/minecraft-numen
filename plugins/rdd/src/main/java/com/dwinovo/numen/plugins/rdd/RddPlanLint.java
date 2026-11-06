package com.dwinovo.numen.plugins.rdd;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 计划文本自检（2026-11-06）：只告警、不改计划、不阻塞。
 *
 * <p>起因：计划文本与机器条件漂移已出现多次——r12-3 描述写 65 层、条件 y=66；r1-1 描述
 * “从箱里取出”、条件却是 container_at（要求箱内有）。这类错误让执行 AI 按错方向行动，
 * 但机器条件本身是对的，所以只发 plan_lint_warning 供事后修，不拦流程。
 */
final class RddPlanLint {

    private RddPlanLint() {}

    record Warning(String rule, String detail) {}

    private static final Pattern TRIPLE = Pattern.compile("(-?\\d+)\\s*[,，]\\s*(-?\\d+)\\s*[,，]\\s*(-?\\d+)");
    private static final Pattern TAKE_OUT = Pattern.compile("取出|拿出|取回|take out|withdraw", Pattern.CASE_INSENSITIVE);
    private static final Pattern ASSUME_HAVE = Pattern.compile("已有|已经有了|不用再做|无需再做|不必再做");

    /** 三条确定性检查；condition 字段传 null 表示该项不适用。 */
    static List<Warning> check(String description, Integer x, Integer y, Integer z, String type, String item) {
        List<Warning> out = new ArrayList<>();
        String desc = description == null ? "" : description;
        // 规则 1：条件有坐标时，描述里的三元组必须与条件一致。
        if (x != null && y != null && z != null) {
            Matcher m = TRIPLE.matcher(desc);
            boolean found = false;
            while (m.find()) {
                found = true;
                int dx = Integer.parseInt(m.group(1));
                int dy = Integer.parseInt(m.group(2));
                int dz = Integer.parseInt(m.group(3));
                if (dx != x || dy != y || dz != z) {
                    out.add(new Warning("coord_mismatch", "description says " + dx + "," + dy + "," + dz
                            + " but condition is " + x + "," + y + "," + z));
                    break;
                }
            }
            if (!found) {
                // 没有三元组不算错：很多描述用“主屋箱子”指代，不要求写坐标。
            }
        }
        // 规则 2：容器条件不得配“从箱里取出”的动词。
        if ("container_at".equals(type) && TAKE_OUT.matcher(desc).find()) {
            out.add(new Warning("container_direction", "condition is container_at (must contain the item) "
                    + "but description says taking out"));
        }
        // 规则 3：条件要求具体物品时，描述若断言“已有/不用再做”，先告警（库存由执行时判定）。
        if (item != null && !item.isBlank() && ASSUME_HAVE.matcher(desc).find()) {
            out.add(new Warning("assume_have", "description assumes the item is already held; condition still requires it: " + item));
        }
        return out;
    }

    /** 同一 primary/subtask/rule 只告警一次，避免每 tick 刷屏。 */
    static boolean firstWarning(Set<String> seen, String primaryId, String subtaskId, String rule) {
        return seen.add(String.valueOf(primaryId) + "|" + String.valueOf(subtaskId) + "|" + String.valueOf(rule));
    }
}
