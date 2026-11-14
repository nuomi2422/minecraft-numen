package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.SubtaskSpec;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Narrow planning prerequisite, not a second mining implementation (044 B1/B2, 2026-11-14).
 * WHY: V19 promised obsidian inventory drops with iron and water. Casting a world block
 * is not acquiring an item. Native ItemStack.isCorrectToolForDrops remains authority.
 * Static vanilla knowledge only informs planning; it NEVER proves current datapack rules.
 * The guard only consumes a fresh, complete native projection from the game-thread shell.
 * No new world observation, tier table, recipe substitute, learned route or completion.
 */
final class RddMiningPrerequisite {
    static final String OBSIDIAN = "minecraft:obsidian";

    /** Scoped native projection of actual stacks, not an item-id/default-stack guess. */
    record Evidence(Map<String, Integer> inventory, Map<String, Boolean> correctForDrops,
                    String source, long capturedAtMs, boolean complete) {
        Evidence {
            inventory = Map.copyOf(inventory);
            correctForDrops = Map.copyOf(correctForDrops);
        }
        boolean currentFor(Map<String, Integer> held, long nowMs) {
            return source != null && !source.isBlank() && capturedAtMs > 0 && capturedAtMs <= nowMs
                    && nowMs - capturedAtMs <= 3_000L && inventory.equals(held) && complete;
        }
    }

    private RddMiningPrerequisite() {}

    static boolean relevant(String objective) {
        String text = objective == null ? "" : objective.toLowerCase(Locale.ROOT);
        return text.contains("obsidian") || text.contains("黑曜石") || text.contains("下界")
                || text.contains("传送门") || text.contains("nether") || text.contains("portal");
    }

    /** Single method contract for the existing planning policy, not a second experience route. */
    static String prompt(String objective) {
        if (!relevant(objective)) return "";
        return "【采集能力前提｜静态方法知识，来源/适用范围：vanilla-java-1.21.1 默认方块标签/工具组件】\n"
                + "原版 iron_pickaxe 不能取得 obsidian 掉落；原版开采到背包需 diamond_pickaxe 或 netherite_pickaxe。"
                + "倒水浇筑只形成世界方块，不等于黑曜石进背包。先补可采工具准备或改变取得方式并说明理由；"
                + "造门与取得物品是不同目标，不得偷改 condition、吞目标或无证完成，也不强制走挖钻石路线。"
                + "模组/数据包适用性或工具未观测时是 UNKNOWN；静态知识不是当前世界事实，采前以实际工具能力核验为准。\n";
    }

    static boolean needsCheck(SubtaskSpec spec) {
        return spec != null && OBSIDIAN.equals(spec.condition().get("asset_key"));
    }

    /** Empty means not proven impossible, never a positive guarantee that mining will succeed. */
    static String contradiction(SubtaskSpec spec, Map<String, Integer> held, Evidence evidence, Set<String> prepared) {
        if (!needsCheck(spec) || evidence == null || !evidence.currentFor(held, System.currentTimeMillis())) return "";
        String description = spec.description() == null ? "" : spec.description().toLowerCase(Locale.ROOT);
        if (!extractsOrCasts(description) || hasAlternative(description) || unknownTool(description, evidence)) return "";
        if (evidence.correctForDrops().containsValue(true)) return "";
        // A preceding admitted tool condition must execute first. It is NOT a current
        // capability fact: keep both steps and require native recheck when mining runs.
        // A new same-ID stack may have different components: current native false only
        // describes the old stack, not the pending replacement verified by its condition.
        if (!prepared.isEmpty()) return "";
        return "采集能力矛盾（" + evidence.source() + "，captured_at=" + evidence.capturedAtMs() + "）："
                + String.join("/", evidence.correctForDrops().keySet()) + " 没有可取得 " + OBSIDIAN + " 掉落的工具；"
                + "浇筑仅形成世界方块，不能满足背包到手条件。先补可采工具准备或换取得方式并说明理由；"
                + "保留原目标与判据，整批退回原规划恢复，不跳过本步。";
    }

    static Set<String> preparedTools(SubtaskSpec spec) {
        if (spec == null) return Set.of();
        Object key = spec.condition().get("asset_key");
        if ("item_crafted".equals(spec.condition().get("type"))) key = spec.condition().get("item");
        if (!(key instanceof String id) || !id.endsWith("_pickaxe")) return Set.of();
        // Reuse the existing preparation recognizer, but only when its real condition
        // verifies this tool. Merely mentioning "make a tool" is not preparation proof.
        return RddPlanGuard.producedToolIds(spec.description()).contains(id) ? Set.of(id) : Set.of();
    }

    private static boolean extractsOrCasts(String description) {
        for (String clause : description.split("[，,。；;（）()\n]")) {
            if (clause.contains("不挖") || clause.contains("不采") || clause.contains("无需挖")
                    || clause.contains("不必挖") || clause.contains("不要挖")) continue;
            if (clause.contains("挖") || clause.contains("采") || clause.contains("mine")
                    || clause.contains("浇") || clause.contains("倒水") || clause.contains("冷却")) return true;
        }
        return false;
    }

    private static boolean hasAlternative(String description) {
        return description.contains("箱") || description.contains("交易") || description.contains("兑换")
                || description.contains("拾取") || description.contains("捡") || description.contains("chest")
                || description.contains("loot") || description.contains("barter") || description.contains("模组")
                || description.contains("数据包") || description.contains("datapack");
    }

    private static boolean unknownTool(String description, Evidence evidence) {
        return java.util.regex.Pattern.compile("(?<![a-z0-9_])[a-z0-9_]+:[a-z0-9_/]+").matcher(description)
                .results().anyMatch(m -> !m.group().startsWith("minecraft:")
                        && !evidence.correctForDrops().containsKey(m.group()));
    }
}
