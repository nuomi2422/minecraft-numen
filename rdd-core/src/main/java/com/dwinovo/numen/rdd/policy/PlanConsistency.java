package com.dwinovo.numen.rdd.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 计划自洽检查（167 §17，2026-11-01）。

 * <p>为什么需要：实机抓到规划模型**同一次输出**里自相矛盾——
 * 阶段正文写「铁甲头盔/胸甲/护腿/靴各两套（含身上一套）」，
 * 而它自己给的子任务条件只有 `iron_chestplate >= 1`、`iron_helmet >= 1`，
 * 没有任何一条覆盖「备用那一套」。结果：造一套就被判完成，备用套永远不存在，
 * 死亡后只能取木剑木镐裸装回去（§16）。

 * <p>本类只做**检测**：不拦、不改判据、不改计划。发现矛盾就返回一句话，
 * 由调用方发一条 `plan_inconsistent` 事件——先让矛盾可见，再谈要不要拦。
 */
public final class PlanConsistency {

    private PlanConsistency() {}

    /** 一个子任务的最小视图。 */
    public record Subtask(String description, Map<String, Object> condition) {}

    /** 正文里能识别的数量单位 → 关心的物品族。 */
    private static final Map<String, List<String>> FAMILIES = Map.of(
            "套", List.of("iron_chestplate", "iron_helmet", "iron_leggings", "iron_boots",
                    "diamond_chestplate", "diamond_helmet", "diamond_leggings", "diamond_boots"),
            "把", List.of("iron_sword", "iron_pickaxe", "diamond_sword", "diamond_pickaxe"));

    /**
     * @param stageDescription 阶段正文（分解模型写的自然语言）
     * @param subtasks         它给的子任务
     * @return 矛盾描述；没有矛盾返回空表
     */
    public static List<String> check(String stageDescription, List<Subtask> subtasks) {
        if (stageDescription == null || subtasks == null || subtasks.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (var entry : FAMILIES.entrySet()) {
            String unit = entry.getKey();
            int prose = proseCount(stageDescription, unit);
            if (prose <= 1) continue;   // 只关心「多个」的情形
            for (String item : entry.getValue()) {
                Integer cond = conditionMinimum(subtasks, item);
                if (cond != null && cond < prose) {
                    out.add(item + " prose=" + prose + " condition=" + cond);
                }
            }
        }
        return List.copyOf(out);
    }

    /** 子任务里针对该物品的最小要求；没有这条子任务返回 null（不冤枉）。 */
    private static Integer conditionMinimum(List<Subtask> subtasks, String item) {
        Integer best = null;
        for (Subtask s : subtasks) {
            if (s == null || s.condition() == null) continue;
            Object key = s.condition().get("asset_key");
            if (key == null) continue;
            String k = String.valueOf(key);
            if (!k.endsWith(item)) continue;
            Object min = s.condition().get("minimum");
            int v = min instanceof Number n ? n.intValue() : 1;
            if (best == null || v > best) best = v;
        }
        return best;
    }

    /**
     * 从正文里读「…各两套 / 两把 / 3 套」的数字。
     *
     * <p>故意只认紧挨着单位的数字/中文数字，避免把别的句子算进来。
     */
    static int proseCount(String text, String unit) {
        if (text == null || unit == null) return 0;
        int best = 0;
        for (int i = 0; i < text.length() - unit.length(); i++) {
            if (!text.startsWith(unit, i)) continue;
            int j = i - 1;
            while (j >= 0 && (text.charAt(j) == ' ' || text.charAt(j) == '各')) j--;
            if (j < 0) continue;
            char c = text.charAt(j);
            int v = 0;
            switch (c) {
                case '一' -> v = 1;
                case '两', '二' -> v = 2;
                case '三' -> v = 3;
                case '四' -> v = 4;
                case '五' -> v = 5;
                default -> {
                    if (Character.isDigit(c)) {
                        int start = j;
                        while (start > 0 && Character.isDigit(text.charAt(start - 1))) start--;
                        try { v = Integer.parseInt(text.substring(start, j + 1)); } catch (NumberFormatException e) { v = 0; }
                    }
                }
            }
            if (v > best) best = v;
        }
        return best;
    }
}