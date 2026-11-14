package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.SubtaskSpec;
import com.dwinovo.numen.rdd.core.PlanningAssetSnapshot;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 044 B1/B2 (2026-11-14): real request -> parse/guard -> production injection -> chain.
 * WHY: V19 new-rdd L45/L56 request 5f58ee3a generated the exact step below; L60
 * installed r42, then new-context L346 request 1c761bf4 dispatched it as current_task.
 * new-tools L148/L151/L154 bind call_723c4e9e -> wire-4eb6cb6f -> t15 -> correct refusal.
 * Do not blame nearby daily1602, change mine, or replay these inputs into a live world.
 * Use the parent's managed plugins:rdd TestOnly selector; no MC lifecycle is claimed.
 * Not a mirrored feasibility helper: prompt, parser, guard, buildInjection, acceptPlan
 * and TaskChain are the existing production consumers. Synthetic controls are explicit.
 */
class RddObsidianPlanConsumerTest {
    private static final String THEME = "下界取烈焰棒与末影珍珠：进下界找到堡垒，打烈焰人取得烈焰棒，同时击杀末影人收集末影珍珠，之后回主世界";
    private static final String ORIGINAL = "用背包里的 flint_and_steel 点火造下界传送门：需要 10 个黑曜石（用 iron_pickaxe 在 Y<16 的熔岩湖旁用水桶浇熔岩，或挖已有黑曜石）。水桶已有 minecraft:water_bucket×1。目标：背包里有 10 个黑曜石或门已点亮；本步验收黑曜石到手 10 个。";
    private static final Map<String, Integer> IRON = Map.of(
            "minecraft:iron_pickaxe", 1, "minecraft:stone_pickaxe", 1,
            "minecraft:wooden_pickaxe", 2, "minecraft:water_bucket", 1,
            "minecraft:flint_and_steel", 1, "minecraft:iron_chestplate", 1);

    @Test void finalStageBRequestExplainsScopedHarvestPrerequisiteRatherThanInventingWorldFacts() {
        TaskChain chain = chain();
        List<String> sent = new ArrayList<>();
        RddDecomposer.dispatchStageB(UUID.randomUUID(), THEME, chain, chain.planRevision(), 0,
                List.of(), "fixture-only failure context retained", PlanningAssetSnapshot.from(IRON, null),
                List.of("fixture-only known failure"), (id, objective, stage, failures) -> "fixture-only knowledge",
                () -> chain, sent::add);
        assertEquals(1, sent.size());
        String request = sent.getFirst();
        assertTrue(request.contains("vanilla-java-1.21.1"), "static method knowledge needs source/version/scope");
        assertTrue(request.contains("iron_pickaxe") && request.contains("不能取得"));
        assertTrue(request.contains("世界方块") && request.contains("背包"));
        assertTrue(request.contains("UNKNOWN"), "modded/unobserved ability must not be fabricated");
        assertTrue(request.contains("fixture-only failure context retained"));
        assertTrue(request.contains("fixture-only knowledge"), "reuse existing knowledge delivery");
    }

    @Test void originalV19IronAndWaterStepCannotReachAnExecutableCurrentTask() {
        SubtaskSpec original = parse(ORIGINAL, Map.of("asset_key", "minecraft:obsidian", "minimum", 10));
        var guarded = guard(List.of(original), IRON);
        TaskChain chain = install(guarded.allowed(), IRON);
        assertTrue(chain.currentPrimary().unexpanded(), "known impossible obsidian acquisition must not install");
        assertTrue(guarded.allowed().isEmpty());
        assertTrue(guarded.dropped().stream().anyMatch(s -> s.contains("iron_pickaxe") && s.contains("obsidian")),
                "the existing policy rejection must explain the conflicting tool and target");
        assertEquals(10, ((Number) original.condition().get("minimum")).intValue());
        assertEquals("minecraft:obsidian", original.condition().get("asset_key"));
        assertTrue(chain.satisfiedStages().isEmpty(), "rejection is not completion");
        assertEquals("通关MC", chain.goal().description(), "the original objective survives");
    }

    @Test void knownIronOnlyImplicitMiningAlsoNeedsPreparationOrAnotherSource() {
        var guarded = guard(List.of(obsidian("开采黑曜石，背包取得10个")), IRON);
        assertTrue(guarded.allowed().isEmpty(), "omitting the tool name does not make iron harvest obsidian");
        assertFalse(guarded.dropped().isEmpty());
    }

    @Test void rejectingRequiredAcquisitionCannotInstallItsDependentTailAsACompletePlan() {
        SubtaskSpec tail = parse("进入下界", Map.of("type", "dimension", "dimension", "minecraft:the_nether"));
        var guarded = guard(List.of(obsidian(ORIGINAL), tail), IRON);
        TaskChain chain = install(guarded.allowed(), IRON);
        assertTrue(chain.currentPrimary().unexpanded(), "dropping only the hard target would swallow its dependency");
        assertTrue(guarded.allowed().isEmpty());
        assertTrue(chain.satisfiedStages().isEmpty());
    }

    @Test void diamondAndNetheritePositiveControlsReachRealRuntimeUnchanged() {
        for (String tool : List.of("diamond_pickaxe", "netherite_pickaxe")) {
            Map<String, Integer> held = Map.of("minecraft:" + tool, 1);
            SubtaskSpec step = obsidian("用 " + tool + " 开采黑曜石到背包10个");
            var guarded = guard(List.of(step), held);
            assertEquals(List.of(step), guarded.allowed());
            TaskChain chain = install(guarded.allowed(), held);
            assertFalse(chain.currentPrimary().unexpanded());
            assertEquals(step.condition(), chain.currentSubtask().condition());
            assertEquals(step.description(), chain.currentSubtask().description());
        }
    }

    @Test void admittedToolPreparationRemainsAheadOfMiningNotAssumedAlreadyOwned() {
        SubtaskSpec prep = parse("合成钻石镐", Map.of("type", "item_crafted", "item", "minecraft:diamond_pickaxe", "minimum", 1));
        SubtaskSpec mine = obsidian("用钻石镐开采黑曜石到手10个");
        var guarded = guard(List.of(prep, mine), IRON);
        assertEquals(List.of(prep, mine), guarded.allowed());
        TaskChain chain = install(guarded.allowed(), IRON);
        assertEquals(prep.description(), chain.currentSubtask().description());
        assertEquals("minecraft:diamond_pickaxe", chain.currentSubtask().condition().get("item"));
        assertTrue(chain.satisfiedStages().isEmpty());
    }

    @Test void unobservedToolInventoryIsUnknownNotAReasonToReject() {
        SubtaskSpec step = obsidian("开采黑曜石到手10个");
        assertEquals(List.of(step), guard(List.of(step), null).allowed());
        assertEquals(List.of(step), guard(List.of(step), Map.of()).allowed());
        assertEquals(List.of(step), guard(List.of(step), Map.of("minecraft:dirt", 1)).allowed());
    }

    @Test void moddedToolAbilityIsUnknownNotGuessedFromItsNameOrTier() {
        SubtaskSpec step = obsidian("用 mod:powered_pickaxe 开采黑曜石到手10个");
        assertEquals(List.of(step), guard(List.of(step), Map.of(
                "mod:powered_pickaxe", 1, "minecraft:iron_pickaxe", 1)).allowed());
    }

    @Test void nonMiningAcquisitionDoesNotForceDiamondMiningOrChangeThePredicate() {
        SubtaskSpec loot = obsidian("从可用箱子取出黑曜石10个到背包");
        var guarded = guard(List.of(loot), IRON);
        assertEquals(List.of(loot), guarded.allowed());
        assertEquals(loot.condition(), install(guarded.allowed(), IRON).currentSubtask().condition());
    }

    @Test void castingPortalWorldBlockAndUnrelatedLegalPlansRemainLegal() {
        SubtaskSpec cast = parse("用水桶原地浇筑传送门框并点火", Map.of("type", "block_nearby", "block", "minecraft:nether_portal", "radius", 8));
        SubtaskSpec other = parse("用铁镐采铁矿", Map.of("asset_key", "minecraft:raw_iron", "minimum", 1));
        assertEquals(List.of(cast, other), guard(List.of(cast, other), IRON).allowed());
    }

    private static SubtaskSpec obsidian(String description) {
        return parse(description, Map.of("asset_key", "minecraft:obsidian", "minimum", 10));
    }

    private static SubtaskSpec parse(String description, Map<String, Object> condition) {
        String json = new Gson().toJson(Map.of("subtasks", List.of(Map.of("description", description, "condition", condition))));
        var specs = RddDecomposer.parse(json);
        assertEquals(1, specs.size(), "fixture must pass the real parser before testing the guard");
        return specs.getFirst();
    }

    private static RddPlanGuard.Filtered guard(List<SubtaskSpec> specs, Map<String, Integer> held) {
        return RddPlanGuard.filterSubtasks(RddPlanGuard.normalizeConditions(specs), THEME, held);
    }

    private static TaskChain chain() {
        return new TaskChain(new Goal("fixture-g", "通关MC", List.of(PrimaryGoal.unexpanded("fixture-p", THEME, List.of()))));
    }

    private static TaskChain install(List<SubtaskSpec> accepted, Map<String, Integer> held) {
        TaskChain chain = chain();
        if (!accepted.isEmpty()) {
            var subs = RddGoalDriver.buildInjection("fixture-p", chain.nextPlanRevision(), accepted);
            RddGoalDriver.acceptPlan(subs, ignored -> {}, () -> chain.expandCurrentPrimary(subs));
            chain.activateCurrent(held);
        }
        return chain;
    }
}
