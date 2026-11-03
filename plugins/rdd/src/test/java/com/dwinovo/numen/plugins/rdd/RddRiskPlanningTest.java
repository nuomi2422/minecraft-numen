package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.AssetRequirement;
import com.dwinovo.numen.rdd.api.PrimarySpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** P2.2 规划期风险前置：提示词块 + 高风险阶段自动 wait_for 硬门。 */
class RddRiskPlanningTest {

    @Test void prepHintEmptyForNormal() {
        assertEquals("", RddRiskPlanning.prepHint("做石镐和食物", Map.of()));
    }

    @Test void prepHintListsRequirementsForNether() {
        String hint = RddRiskPlanning.prepHint("进入下界", Map.of());
        assertTrue(hint.contains("风险前置"));
        assertTrue(hint.contains("下界"));
        assertTrue(hint.contains("minecraft:diamond_chestplate"));
        assertTrue(hint.contains("minecraft:fire_resistance_potion"));
        assertTrue(hint.contains("提前"));
    }

    @Test void injectWaitForOnlyIntoRiskyStage() {
        List<PrimarySpec> stages = List.of(
                new PrimarySpec("做石镐和食物", List.of()),
                new PrimarySpec("进入下界", List.of()));
        List<PrimarySpec> out = RddRiskPlanning.injectWaitFor(stages, Map.of());
        assertEquals(2, out.size());
        assertTrue(out.get(0).waitFor().isEmpty(), "普通阶段不加门");
        var keys = out.get(1).waitFor().stream().map(AssetRequirement::assetKey).toList();
        assertTrue(keys.contains("minecraft:diamond_chestplate"));
        assertTrue(keys.contains("minecraft:fire_resistance_potion"));
        int chest = out.get(1).waitFor().stream()
                .filter(r -> r.assetKey().equals("minecraft:diamond_chestplate"))
                .findFirst().orElseThrow().minimum();
        assertEquals(1, chest, "166 §72：下界只要求一套钻石甲（旧值 2 = 48 钻，等于把终局装备当前置）");
        int ironChest = out.get(1).waitFor().stream()
                .filter(r -> r.assetKey().equals("minecraft:iron_chestplate"))
                .findFirst().orElseThrow().minimum();
        assertEquals(2, ironChest, "166 §73：仍要求两套铁甲作为可成建制补回的备用");
        // §6③：下界阶段追加"床"作为恢复点硬门
        assertTrue(keys.contains("minecraft:white_bed"), "下界阶段应有床（恢复点）硬门");
    }

    @Test void prepHintMentionsRecoveryPointForHighRisk() {
        assertTrue(RddRiskPlanning.prepHint("进入下界", Map.of()).contains("恢复点"));
        assertTrue(RddRiskPlanning.prepHint("击杀末影龙", Map.of()).contains("恢复点"));
        assertFalse(RddRiskPlanning.prepHint("下矿采钻石", Map.of()).contains("恢复点"),
                "下矿不要求恢复点");
    }

    @Test void injectWaitForMergesWithExistingTakingMax() {
        // 高风险阶段需为非首个阶段（第一个一级目标不加跨级硬门，防自锁）。
        List<PrimarySpec> stages = List.of(
                new PrimarySpec("石器起步", List.of()),
                new PrimarySpec("进入下界", List.of(new AssetRequirement("minecraft:torch", 4))));
        List<PrimarySpec> out = RddRiskPlanning.injectWaitFor(stages, Map.of());
        int torch = out.get(1).waitFor().stream()
                .filter(r -> r.assetKey().equals("minecraft:torch"))
                .findFirst().orElseThrow().minimum();
        assertEquals(16, torch, "同 key 取较大 minimum");
    }

    @Test void firstStageNeverGetsInjectedWaitFor() {
        // 真机 bug 回归：第一个一级目标（如"就地挖石头/采集木头"被判 MINING）不得加 wait_for，
        // 否则要求 torch/bread 而该阶段本身就产出它们 → 永久 WAITING 自锁。
        List<PrimarySpec> stages = List.of(
                new PrimarySpec("石器起步：就地挖石头、采集木头做石斧石镐", List.of()));
        List<PrimarySpec> out = RddRiskPlanning.injectWaitFor(stages, Map.of());
        assertTrue(out.get(0).waitFor().isEmpty(), "first stage must not get injected wait_for");
    }

    // ---- A2（94 号）：备用套进硬门 ----

    @Test void highRiskStageCarriesBackupSet() {
        List<PrimarySpec> stages = List.of(
                new PrimarySpec("石器起步", List.of()),
                new PrimarySpec("击杀末影龙", List.of()));
        List<PrimarySpec> out = RddRiskPlanning.injectWaitFor(stages, Map.of());
        var wf = out.get(1).waitFor();
        int beef = wf.stream().filter(r -> r.assetKey().equals("minecraft:cooked_beef"))
                .findFirst().orElseThrow().minimum();
        assertEquals(32, beef, "末地阶段应带备用食物 32（基线 16）");
        int arrows = wf.stream().filter(r -> r.assetKey().equals("minecraft:arrow"))
                .findFirst().orElseThrow().minimum();
        assertEquals(64, arrows, "末地阶段应带备用箭 64（基线 32）");
    }

    @Test void smeltIronWithoutOreGetsMiningPrep() {
        List<PrimarySpec> stages = List.of(new PrimarySpec("烧铁锭做铁甲四部位", List.of()));
        List<PrimarySpec> out = RddRiskPlanning.ensureSmeltPrepStage(stages, Map.of());
        assertEquals(2, out.size());
        assertTrue(out.get(0).description().contains("先下矿挖铁"), out.toString());
        // 必须命中 RiskGate MINING 的行动词（下矿/挖矿），否则自动补级不会获得矿业火把/食物门。
        assertTrue(out.get(0).description().contains("下矿"), out.get(0).description());
    }

    @Test void smeltIronWithPriorMiningDoesNotDuplicatePrep() {
        List<PrimarySpec> stages = List.of(
                new PrimarySpec("浅层挖铁矿石与煤", List.of()),
                new PrimarySpec("烧铁锭做铁甲", List.of()));
        List<PrimarySpec> out = RddRiskPlanning.ensureSmeltPrepStage(stages, Map.of());
        assertEquals(2, out.size(), out.toString());
    }

    @Test void smeltPrepNeverExceedsMaxStages() {
        java.util.List<PrimarySpec> stages = new java.util.ArrayList<>();
        for (int i = 0; i < com.dwinovo.numen.rdd.core.RddChainFactory.MAX_STAGES; i++) {
            stages.add(new PrimarySpec("前置阶段" + i, List.of()));
        }
        stages.set(stages.size() - 1, new PrimarySpec("烧铁锭做铁甲", List.of()));
        List<PrimarySpec> out = RddRiskPlanning.ensureSmeltPrepStage(stages, Map.of());
        assertTrue(out.size() <= com.dwinovo.numen.rdd.core.RddChainFactory.MAX_STAGES,
                "插入后阶段数 " + out.size() + " 超过上限");
    }

    @Test void smeltPrepAtFirstStageAlsoRespectsMax() {
        java.util.List<PrimarySpec> stages = new java.util.ArrayList<>();
        stages.add(new PrimarySpec("烧铁锭做铁甲", List.of()));
        for (int i = 1; i < com.dwinovo.numen.rdd.core.RddChainFactory.MAX_STAGES; i++) {
            stages.add(new PrimarySpec("后续阶段" + i, List.of()));
        }
        List<PrimarySpec> out = RddRiskPlanning.ensureSmeltPrepStage(stages, Map.of());
        assertTrue(out.size() <= com.dwinovo.numen.rdd.core.RddChainFactory.MAX_STAGES,
                "首段插入后阶段数 " + out.size() + " 超过上限");
    }

    @Test void smeltIronWithOreInHandDoesNotAddPrep() {
        List<PrimarySpec> stages = List.of(new PrimarySpec("烧铁锭做铁镐", List.of()));
        List<PrimarySpec> out = RddRiskPlanning.ensureSmeltPrepStage(stages,
                Map.of("minecraft:raw_iron", 11));
        assertEquals(1, out.size(), out.toString());
    }

    @Test void stageThatProducesBreadDoesNotWaitForBread() {
        // 2026-11-02 实机：Stage-A 把「把干草拆成面包」阶段写成 wait_for bread>=1 →
        // 进入该阶段前就要求面包，而面包正是该阶段产物，永远等不到。
        List<PrimarySpec> stages = List.of(
                new PrimarySpec("石器开局", List.of()),
                new PrimarySpec("村庄食物与农田：把干草块拆成小麦→合成面包作为主食",
                        List.of(new AssetRequirement("minecraft:bread", 1))));
        List<PrimarySpec> out = RddRiskPlanning.injectWaitFor(stages, Map.of());
        assertTrue(out.get(1).waitFor().stream()
                        .noneMatch(r -> r.assetKey().equals("minecraft:bread")),
                "本阶段会产出面包，就不能把面包写成进入本阶段的 wait_for");
    }

    @Test void foodProducingStageDoesNotWaitForCookedBeef() {
        List<PrimarySpec> stages = List.of(
                new PrimarySpec("石器开局", List.of()),
                new PrimarySpec("在村庄安家：拆干草块做面包，收农田并补种，食物存箱",
                        List.of(new AssetRequirement("minecraft:cooked_beef", 4))));
        List<PrimarySpec> out = RddRiskPlanning.injectWaitFor(stages, Map.of());
        assertTrue(out.get(1).waitFor().stream()
                        .noneMatch(r -> r.assetKey().equals("minecraft:cooked_beef")),
                "本阶段会产食物，不能把熟牛肉写成进入本阶段的前置（实机靠作弊 16 牛肉才过）");
    }

    @Test void genericBedIdIsNotAWaitForGate() {
        List<PrimarySpec> stages = List.of(
                new PrimarySpec("石器开局", List.of()),
                new PrimarySpec("浅层挖煤与铁，做铁甲",
                        List.of(new AssetRequirement("minecraft:bed", 1))));
        List<PrimarySpec> out = RddRiskPlanning.injectWaitFor(stages, Map.of());
        assertTrue(out.get(1).waitFor().stream()
                        .noneMatch(r -> r.assetKey().equals("minecraft:bed")),
                "minecraft:bed 不是真实物品 ID；床可能已放置，不能当硬门");
    }

    @Test void tradeStageDoesNotWaitForEmeraldItProduces() {
        List<PrimarySpec> stages = List.of(
                new PrimarySpec("石器开局", List.of()),
                new PrimarySpec("村民交易闭环：完成一次真实买卖交易（如纸/书/作物→绿宝石）",
                        List.of(new AssetRequirement("minecraft:emerald", 1))));
        List<PrimarySpec> out = RddRiskPlanning.injectWaitFor(stages, Map.of());
        assertTrue(out.get(1).waitFor().stream()
                        .noneMatch(r -> r.assetKey().equals("minecraft:emerald")),
                "交易阶段会获得绿宝石，不能把绿宝石写成进入本阶段的前置");
    }

    @Test void acceptanceStageDoesNotGetNetherGates() {
        // 2026-11-02 实机：「最终验收…以上完成前不下界」被风险门判成 NETHER，
        // 注入了两套钻石甲/抗火药等硬门 → 验收阶段永远等不到。
        List<PrimarySpec> stages = List.of(
                new PrimarySpec("石器开局", List.of()),
                new PrimarySpec("最终验收：确认铁甲四部位穿戴与交易完成；以上完成前不下界", List.of()));
        List<PrimarySpec> out = RddRiskPlanning.injectWaitFor(stages, Map.of());
        assertTrue(out.get(1).waitFor().isEmpty(),
                "验收阶段不是新的高风险行动，不得注入下界/末地硬门；实际：" + out.get(1).waitFor());
    }

    @Test void negationPhraseKeepsFinalAcceptanceOutOfNether() {
        assertEquals(com.dwinovo.numen.rdd.policy.RiskLevel.NORMAL,
                com.dwinovo.numen.rdd.policy.RiskGate.levelForText("最终验收：以上完成前不下界"));
    }

    @Test void lowRiskStageHasNoBackupRequirement() {
        List<PrimarySpec> stages = List.of(
                new PrimarySpec("石器起步", List.of()),
                new PrimarySpec("采集4个橡木原木", List.of()));
        List<PrimarySpec> out = RddRiskPlanning.injectWaitFor(stages, Map.of());
        assertTrue(out.get(1).waitFor().isEmpty(), "低风险阶段不因 A2 被加门");
    }

    @Test void prepHintMentionsBackupForEnd() {
        String hint = RddRiskPlanning.prepHint("击杀末影龙", Map.of());
        assertTrue(hint.contains("备用套"), "风险前置文案要说明含备用套");
    }

    // ---- A2（94 号）：准备节点注入 ----

    @Test void ensurePrepStageInjectedBeforeFirstRiskyStage() {
        List<PrimarySpec> stages = List.of(
                new PrimarySpec("石器起步", List.of()),
                new PrimarySpec("进入下界", List.of()));
        List<PrimarySpec> out = RddRiskPlanning.ensurePrepStage(stages, 12);
        assertEquals(3, out.size(), "高风险前应插入准备节点");
        assertTrue(out.get(1).description().contains("准备与补给"));
        assertEquals("进入下界", out.get(2).description(), "原高风险阶段顺延但内容不变");
    }

    @Test void ensurePrepStageSkipsLowRiskAndExistingPrep() {
        List<PrimarySpec> low = List.of(
                new PrimarySpec("采石头", List.of()), new PrimarySpec("采木头", List.of()));
        assertEquals(2, RddRiskPlanning.ensurePrepStage(low, 12).size(), "低风险目标不插");

        List<PrimarySpec> hasPrep = List.of(
                new PrimarySpec("准备与补给：备食物", List.of()),
                new PrimarySpec("进入下界", List.of()));
        assertEquals(2, RddRiskPlanning.ensurePrepStage(hasPrep, 12).size(), "已有准备阶段不重复插");
    }

    @Test void ensurePrepStageRespectsMaxStages() {
        List<PrimarySpec> stages = List.of(
                new PrimarySpec("石器起步", List.of()),
                new PrimarySpec("进入下界", List.of()));
        assertEquals(2, RddRiskPlanning.ensurePrepStage(stages, 2).size(), "到上限不硬塞");
    }
}
