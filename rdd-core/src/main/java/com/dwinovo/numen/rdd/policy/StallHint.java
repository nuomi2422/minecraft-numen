package com.dwinovo.numen.rdd.policy;

import java.util.Map;

/**
 * 卡死拍醒时补的那句「这一步要什么、现在有几个」（166 §77）。
 *
 * <p><b>为什么单独成一个类</b>：这句话原来内联在 {@code RddStallWatcher} 里，
 * 只能靠"实机卡死"才验得到；而实机现在被额度 429 阻断（§77），等不起。
 * 抽成纯函数之后，条件形状（有/没有 minimum、group、null、未知键）都能离线钉死，
 * 实机回来时只需观察一句话是否出现。
 *
 * <p>措辞刻意只陈述**判据与现状**，不给工具名、不教唆（RL-19 的约束同 §21）。
 */
public final class StallHint {

    private StallHint() {}

    /**
     * @param condition 子任务条件（可空）
     * @param held      该 asset_key 当前持有量（由调用方从背包数出来）
     * @return 一句可以直接拼进提醒的话；条件里没有可说的东西时返回空串
     */
    public static String forCondition(Map<String, Object> condition, int held) {
        if (condition == null || condition.isEmpty()) {
            return "";
        }
        Object key = condition.get("asset_key");
        if (key == null || String.valueOf(key).isBlank()) {
            Object group = condition.get("group");
            if (group == null || String.valueOf(group).isBlank()) {
                return "";
            }
            return "这一步要的是「" + group + "」这一组东西，先把组里任意一种凑够。";
        }
        Object min = condition.get("minimum");
        String need = min == null ? "至少 1" : ("至少 " + min);
        return "这一步的判据是「" + key + "」，你现在有 " + held + " 个（要 " + need + "）。";
    }
}
