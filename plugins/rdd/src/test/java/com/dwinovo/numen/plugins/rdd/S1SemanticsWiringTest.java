package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.AssetRequirement;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.policy.AssetSemantics;
import com.dwinovo.numen.rdd.policy.LossImpactAnalyzer;
import com.dwinovo.numen.rdd.policy.RequirementManifest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S1 契约测试（2026-10-24）：语义资产生产接线——写路径派生 + 死亡损失分析消费。
 *
 * <p>用户原话：「资产没标重要度、死了不按重要度、丢了东西没重新规划的显示」。
 * 本批把「未发生」变成「发生且可见」。钉三条链：
 * <ol>
 *   <li><b>写路径</b>：manifest → 派生 AssetSemantics（CRITICAL→IMPORTANT，同键取高，旧键降 IRRELEVANT）；</li>
 *   <li><b>读路径</b>：死亡掉落 + 清单 + 语义 → LossImpactAnalyzer → recommendation/affectedGoals；</li>
 *   <li><b>fail-soft</b>：无清单/无语义时分析器走 SECONDARY 兜底，不炸、不编。</li>
 * </ol>
 *
 * <p>判据 S1–S5 冻结于 实机验证\2026-10.md 的 S 卡；实机只验「可见性到达」。
 */
class S1SemanticsWiringTest {

    private static RequirementManifest.Manifest manifest(String goalId, String key, int minimum,
                                                          RequirementManifest.Importance importance) {
        return new RequirementManifest.Manifest(goalId,
                List.of(new RequirementManifest.Requirement(key, minimum, List.of(), importance, 0)));
    }

    // ── 写路径 ──────────────────────────────────────────────────

    @Test
    void s1_criticalRequirementBecomesImportantSemantics() {
        var out = RddSemanticsStore.derive(
                manifest("goal-1", "minecraft:flint", 1, RequirementManifest.Importance.CRITICAL),
                "goal-1", null);
        AssetSemantics s = out.get("minecraft:flint");
        assertNotNull(s, "CRITICAL 需求必须产语义条目");
        assertEquals(AssetSemantics.Importance.IMPORTANT, s.importance(),
                "CRITICAL 需求 → IMPORTANT（进本一级的门）");
        assertEquals("goal-1", s.servesGoal(), "servesGoal = 当前 goalId");
        assertEquals(AssetSemantics.Nature.MATERIAL, s.nature(), "flint 无装备后缀 → 保守分类 MATERIAL");
        assertTrue(s.source().startsWith("derived:"), "来源标明派生，不冒充人工声明");
    }

    @Test
    void s1_normalRequirementStaysSecondary() {
        var out = RddSemanticsStore.derive(
                manifest("goal-1", "minecraft:stone", 32, RequirementManifest.Importance.NORMAL),
                "goal-1", null);
        assertEquals(AssetSemantics.Importance.SECONDARY, out.get("minecraft:stone").importance(),
                "NORMAL 需求 → SECONDARY（不是万物皆 IMPORTANT）");
    }

    @Test
    void s1_staleKeyDegradesToIrrelevantNotDeleted() {
        var prev = new LinkedHashMap<String, AssetSemantics>();
        prev.put("minecraft:flint", AssetSemantics.declared("minecraft:flint",
                AssetSemantics.Importance.IMPORTANT, AssetSemantics.Nature.MATERIAL, "goal-1"));
        // 换目标：新清单不再有 flint
        var out = RddSemanticsStore.derive(
                manifest("goal-2", "minecraft:bread", 1, RequirementManifest.Importance.CRITICAL),
                "goal-2", prev);
        AssetSemantics flint = out.get("minecraft:flint");
        assertNotNull(flint, "消失的键不删（历史可查，假删除才是丢数据）");
        assertEquals(AssetSemantics.Importance.IRRELEVANT, flint.importance(),
                "不再服务旧目标的键降 IRRELEVANT（LossImpactAnalyzer 不对它触发动作）");
        assertTrue(flint.source().endsWith(";stale"), "降级来源要能和真派生区分");
    }

    @Test
    void s1_sameKeyKeepsHigherImportance() {
        var m = new RequirementManifest.Manifest("goal-1", List.of(
                new RequirementManifest.Requirement("minecraft:iron_ingot", 5, List.of(),
                        RequirementManifest.Importance.CRITICAL, 0),
                new RequirementManifest.Requirement("minecraft:iron_ingot", 2, List.of(),
                        RequirementManifest.Importance.NORMAL, 0)));
        var out = RddSemanticsStore.derive(m, "goal-1", null);
        assertEquals(AssetSemantics.Importance.IMPORTANT, out.get("minecraft:iron_ingot").importance(),
                "同键取高——与 RddRequirementDetector「同键取高」同口径，两处不一致清单与语义会互相矛盾");
    }

    // ── 读路径：死亡损失分析（判据 S1/S4 的核心）─────────────────

    @Test
    void s1_losingCriticalItemYieldsSupplementAndAffectedGoal() {
        // 清单：flint CRITICAL ×1；语义：flint IMPORTANT
        var manifest = manifest("goal-1", "minecraft:flint", 1, RequirementManifest.Importance.CRITICAL);
        var semantics = Map.of("minecraft:flint",
                AssetSemantics.declared("minecraft:flint",
                        AssetSemantics.Importance.IMPORTANT, AssetSemantics.Nature.MATERIAL, "goal-1"));
        var impact = LossImpactAnalyzer.analyze(
                Map.of("minecraft:flint", 1),      // 丢了 1 个 flint（净损失）
                Map.of(),                          // 死后背包 = 空（最保守输入）
                List.of(manifest),
                semantics,
                false);                            // 掉落还在地上：未确认丢失
        assertTrue(impact.importantLoss(), "丢的是 IMPORTANT 资产（判据 S1 前半）");
        assertFalse(impact.lossConfirmed(), "未确认丢失——分析器边界 2");
        assertEquals(LossImpactAnalyzer.Recommendation.CONFIRM_STOCK, impact.recommendation(),
                "未确认时建议先确认库存（不是立刻 SUPPLEMENT）");
        assertEquals(1, impact.affectedGoals().size(), "flint 需求被打穿（判据 S1 后半：affectedGoals 非空）");
        var g = impact.affectedGoals().get(0);
        assertEquals("minecraft:flint", g.requirementKey());
        assertEquals(0, g.available(), "死后空背包 → available=0");
    }

    @Test
    void s1_confirmedCriticalLossYieldsSupplement() {
        var manifest = manifest("goal-1", "minecraft:flint", 1, RequirementManifest.Importance.CRITICAL);
        var semantics = Map.of("minecraft:flint",
                AssetSemantics.declared("minecraft:flint",
                        AssetSemantics.Importance.IMPORTANT, AssetSemantics.Nature.MATERIAL, "goal-1"));
        var impact = LossImpactAnalyzer.analyze(
                Map.of("minecraft:flint", 1), Map.of(), List.of(manifest), semantics,
                true);   // 这次确认丢了（掉落超时没了）
        assertEquals(LossImpactAnalyzer.Recommendation.SUPPLEMENT, impact.recommendation(),
                "确认丢失 + 重要能力缺口 → SUPPLEMENT（判据 S4 的行为通道）");
        assertTrue(impact.needsAction());
    }

    @Test
    void s1_unrelatedLossStaysQuiet() {
        // 清单只要 flint；丢的全是 stone（语义表里没有）
        var manifest = manifest("goal-1", "minecraft:flint", 1, RequirementManifest.Importance.CRITICAL);
        var impact = LossImpactAnalyzer.analyze(
                Map.of("minecraft:cobblestone", 64), Map.of(),
                List.of(manifest), Map.of(), false);
        assertFalse(impact.importantLoss(), "无关资产 ≠ 重要损失");
        assertTrue(impact.affectedGoals().isEmpty(), "没有需求被打穿");
        assertEquals(LossImpactAnalyzer.Recommendation.NONE, impact.recommendation());
        assertEquals("", RddRepairDispatch.renderSemanticNote(impact),
                "没丢重要的 ⇒ 文案一段都不加（判据 S2 的否定面）");
    }

    // ── fail-soft（判据 S5）────────────────────────────────────

    @Test
    void s1_noManifestNoSemanticsStillAnalyzesWithFallback() {
        // 没绑定目标（清单 null）+ 没有语义表：分析器走 SECONDARY 兜底，不炸不编
        var impact = LossImpactAnalyzer.analyze(
                Map.of("minecraft:diamond_sword", 1), Map.of(), List.of(), Map.of(), false);
        assertFalse(impact.importantLoss(), "无语义表 → 全部按 SECONDARY（保守），不冒充 IMPORTANT");
        assertTrue(impact.affectedGoals().isEmpty(), "无清单 → 无目标被打穿");
        assertEquals("", RddRepairDispatch.renderSemanticNote(impact), "无事可报 ⇒ 不加文案段");
    }

    @Test
    void s1_renderSemanticNoteNamesTheAffectedGoal() {
        var manifest = manifest("goal-1", "minecraft:flint", 1, RequirementManifest.Importance.CRITICAL);
        var semantics = Map.of("minecraft:flint",
                AssetSemantics.declared("minecraft:flint",
                        AssetSemantics.Importance.IMPORTANT, AssetSemantics.Nature.MATERIAL, "goal-1"));
        var impact = LossImpactAnalyzer.analyze(
                Map.of("minecraft:flint", 1), Map.of(), List.of(manifest), semantics, false);
        String note = RddRepairDispatch.renderSemanticNote(impact);
        assertTrue(note.contains("[损失影响]"), "语义段有显著抬头");
        assertTrue(note.contains("minecraft:flint"), "点名丢了什么（判据 S2）");
        assertTrue(note.contains("goal-1"), "点名影响哪个目标");
        assertTrue(note.contains("确认"), "未确认时建议是「先确认」不是「立刻补」");
    }

    @Test
    void s1_nullImpactRendersEmpty() {
        assertEquals("", RddRepairDispatch.renderSemanticNote(null), "analyzeDeathLoss 失败 ⇒ 无语义段，支线照旧");
    }
}
