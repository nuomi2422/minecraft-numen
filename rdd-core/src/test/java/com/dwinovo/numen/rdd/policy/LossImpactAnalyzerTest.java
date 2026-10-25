package com.dwinovo.numen.rdd.policy;

import com.dwinovo.numen.rdd.policy.AssetSemantics.Importance;
import com.dwinovo.numen.rdd.policy.AssetSemantics.Nature;
import com.dwinovo.numen.rdd.policy.LossImpactAnalyzer.LossImpact;
import com.dwinovo.numen.rdd.policy.LossImpactAnalyzer.Recommendation;
import com.dwinovo.numen.rdd.policy.RequirementManifest.Manifest;
import com.dwinovo.numen.rdd.policy.RequirementManifest.Requirement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 损失影响分析：重要性决定值不值得关注，剩余能力决定需不需要行动。 */
class LossImpactAnalyzerTest {

    private static final String GOAL = "goal-nethers";

    private static Manifest manifest(Requirement... reqs) {
        return new Manifest(GOAL, List.of(reqs));
    }

    private static Map<String, AssetSemantics> importantEquipment(String key, String goal) {
        return Map.of(key, AssetSemantics.declared(key, Importance.IMPORTANT, Nature.EQUIPMENT, goal));
    }

    @Test
    void recoveredLossIsNotPermanent() {
        // 死亡瞬间丢过，但已捡回：净损失为空 → 不报永久损失
        LossImpact impact = LossImpactAnalyzer.analyze(
                Map.of(),
                Map.of("minecraft:diamond_sword", 1),
                List.of(manifest(new Requirement("minecraft:diamond_sword", 1, List.of()))),
                importantEquipment("minecraft:diamond_sword", GOAL),
                true);
        assertEquals(Recommendation.NONE, impact.recommendation());
        assertFalse(impact.importantLoss());
        assertTrue(impact.affectedGoals().isEmpty());
    }

    @Test
    void importantLossWithBackupContinues() {
        LossImpact impact = LossImpactAnalyzer.analyze(
                Map.of("minecraft:diamond_sword", 1),
                Map.of("minecraft:iron_sword", 1),
                List.of(manifest(new Requirement("minecraft:diamond_sword", 1,
                        List.of("minecraft:iron_sword")))),
                importantEquipment("minecraft:diamond_sword", GOAL),
                true);
        assertEquals(Recommendation.SWAP_BACKUP, impact.recommendation());
        assertTrue(impact.importantLoss());
        assertTrue(impact.backupSufficient(), "备用武器覆盖了需求 → 换装继续");
        assertTrue(impact.affectedGoals().isEmpty());
    }

    @Test
    void capabilityGapNeedsSupplement() {
        // 丢了钻石镐，只有石镐（不是合格替代）→ 能力缺口，需补充
        LossImpact impact = LossImpactAnalyzer.analyze(
                Map.of("minecraft:diamond_pickaxe", 1),
                Map.of("minecraft:stone_pickaxe", 1),
                List.of(manifest(new Requirement("minecraft:diamond_pickaxe", 1, List.of()))),
                importantEquipment("minecraft:diamond_pickaxe", "采钻石"),
                true);
        assertEquals(Recommendation.SUPPLEMENT, impact.recommendation());
        assertTrue(impact.needsAction());
        assertFalse(impact.backupSufficient());
        assertEquals(1, impact.affectedGoals().size());
        assertEquals("minecraft:diamond_pickaxe", impact.affectedGoals().get(0).requirementKey());
    }

    @Test
    void unconfirmedLossAsksToConfirmFirst() {
        LossImpact impact = LossImpactAnalyzer.analyze(
                Map.of("minecraft:diamond_pickaxe", 1),
                Map.of(),
                List.of(manifest(new Requirement("minecraft:diamond_pickaxe", 1, List.of()))),
                importantEquipment("minecraft:diamond_pickaxe", GOAL),
                false);
        assertEquals(Recommendation.CONFIRM_STOCK, impact.recommendation());
        assertFalse(impact.needsAction(), "尚未确认丢失时不下'永久丢失'结论");
        assertTrue(impact.summary().contains("尚未确认"));
    }

    @Test
    void secondaryLossJustReconfirms() {
        LossImpact impact = LossImpactAnalyzer.analyze(
                Map.of("minecraft:stone", 64),
                Map.of(),
                List.of(manifest(new Requirement("minecraft:stone", 32, List.of()))),
                Map.of("minecraft:stone", AssetSemantics.declared("minecraft:stone",
                        Importance.SECONDARY, Nature.MATERIAL, GOAL)),
                true);
        assertEquals(Recommendation.CONFIRM_STOCK, impact.recommendation());
        assertFalse(impact.importantLoss());
    }

    @Test
    void goalCancelledMeansIrrelevantNeedsNothing() {
        // 目标取消后语义标 IRRELEVANT → 丢了也不触发动作
        LossImpact impact = LossImpactAnalyzer.analyze(
                Map.of("minecraft:diamond_sword", 1),
                Map.of(),
                List.of(),
                Map.of("minecraft:diamond_sword", AssetSemantics.declared("minecraft:diamond_sword",
                        Importance.IRRELEVANT, Nature.EQUIPMENT, "")),
                true);
        assertEquals(Recommendation.NONE, impact.recommendation());
        assertFalse(impact.importantLoss());
    }

    @Test
    void normalConsumptionIsNotLoss() {
        LossImpact impact = LossImpactAnalyzer.analyze(
                Map.of(),
                Map.of("minecraft:bread", 0),
                List.of(manifest(new Requirement("minecraft:bread", 3, List.of()))),
                Map.of(),
                true);
        assertEquals(Recommendation.NONE, impact.recommendation());
    }

    @Test
    void natureGuesserIsConservative() {
        assertEquals(Nature.EQUIPMENT, AssetSemantics.guessNature("minecraft:diamond_pickaxe"));
        assertEquals(Nature.CONSUMABLE, AssetSemantics.guessNature("minecraft:cooked_beef"));
        assertEquals(Nature.MATERIAL, AssetSemantics.guessNature("minecraft:stone"));
    }

    // ── 81 号 P0-A（2026-10-25）：死亡后"随身 / 备用 / 库存可读"三分 ──────────────
    //
    // 这六条钉的是「不许把观测失败当结论」：
    // A1 基地有备用 → 换装继续（用户要的"有备用就不用再做一遍"）
    // A2 真·空库存 + 未确认 → 只敢说"先确认"
    // A3 确认净损失 + 缺口 → 补
    // A4 库存读不到 → 先确认，且不许算"被打穿"（空表≠零）
    // A5 清单对不上这次损失 → 不许说"备用足够"
    // A6 备用只有 1 件、需求 2 件 → 仍然缺（不被"有一点备用"掩盖）

    private static final String CHEST = "minecraft:iron_chestplate";

    @Test
    void a1_backupInBaseLetsHerKeepGoing() {
        LossImpact impact = LossImpactAnalyzer.analyze(
                Map.of(CHEST, 1),
                Map.of(),                                  // 死亡后随身：真空
                Map.of(CHEST, 1),                          // 基地容器观测到 1 件备用
                List.of(manifest(new Requirement(CHEST, 1, List.of()))),
                importantEquipment(CHEST, GOAL),
                false,                                     // 掉落/回收还没确认
                true);                                     // 背包读得到（真空也是已知）
        assertEquals(Recommendation.SWAP_BACKUP, impact.recommendation());
        assertTrue(impact.backupSufficient(), "基地备用覆盖需求 → 不必回炉重做");
        assertEquals(1, impact.affectedGoals().size());
        assertEquals(0, impact.affectedGoals().get(0).available(), "随身确实没有");
        assertEquals(1, impact.affectedGoals().get(0).backup(), "备用 1 件是证据，不是猜测");
    }

    @Test
    void a2_realEmptyInventoryStillAsksToConfirm() {
        LossImpact impact = LossImpactAnalyzer.analyze(
                Map.of(CHEST, 1), Map.of(), Map.of(),
                List.of(manifest(new Requirement(CHEST, 1, List.of()))),
                importantEquipment(CHEST, GOAL), false, true);
        assertEquals(Recommendation.CONFIRM_STOCK, impact.recommendation());
        assertFalse(impact.backupSufficient());
        assertEquals(0, impact.affectedGoals().get(0).available());
    }

    @Test
    void a3_confirmedNetLossWithGapNeedsSupplement() {
        LossImpact impact = LossImpactAnalyzer.analyze(
                Map.of(CHEST, 1), Map.of(), Map.of(),
                List.of(manifest(new Requirement(CHEST, 1, List.of()))),
                importantEquipment(CHEST, GOAL), true, true);
        assertEquals(Recommendation.SUPPLEMENT, impact.recommendation());
        assertTrue(impact.needsAction());
        assertEquals("important_requirement_unsatisfied_after_confirmed_loss", impact.reason());
    }

    @Test
    void a4_unreadableInventoryNeverClaimsEnough() {
        LossImpact impact = LossImpactAnalyzer.analyze(
                Map.of(CHEST, 1), Map.of(), Map.of(),
                List.of(manifest(new Requirement(CHEST, 1, List.of()))),
                importantEquipment(CHEST, GOAL), false, false);
        assertEquals(Recommendation.CONFIRM_STOCK, impact.recommendation());
        assertEquals("inventory_unreadable", impact.reason());
        assertFalse(impact.inventoryKnown());
        assertTrue(impact.affectedGoals().isEmpty(), "读不到时不算'被打穿'——空表不是零");
        assertFalse(impact.backupSufficient());
    }

    @Test
    void a5_lossWithoutRequirementEvidenceCannotClaimBackup() {
        LossImpact impact = LossImpactAnalyzer.analyze(
                Map.of("minecraft:diamond_sword", 1), Map.of(), Map.of(),
                List.of(),                                 // 切过目标：当前没有清单
                importantEquipment("minecraft:diamond_sword", GOAL), true, true);
        assertEquals(Recommendation.CONFIRM_STOCK, impact.recommendation());
        assertEquals("no_requirement_evidence_for_this_loss", impact.reason());
        assertFalse(impact.backupSufficient(), "没证据时不许说'备用足够'");
    }

    @Test
    void a6_partialBackupDoesNotHideTheGap() {
        LossImpact impact = LossImpactAnalyzer.analyze(
                Map.of("minecraft:torch", 2), Map.of(), Map.of("minecraft:torch", 1),
                List.of(manifest(new Requirement("minecraft:torch", 2, List.of()))),
                importantEquipment("minecraft:torch", GOAL), true, true);
        assertEquals(Recommendation.SUPPLEMENT, impact.recommendation(), "备用 1 < 需求 2 → 仍然缺");
        assertEquals(1, impact.affectedGoals().get(0).backup());
        assertFalse(impact.backupSufficient());
    }
}
