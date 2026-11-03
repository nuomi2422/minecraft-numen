package com.dwinovo.numen.rdd.policy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 167 §17：实机原文——正文「各两套」，子任务只有 chestplate>=1 / helmet>=1。 */
class PlanConsistencyTest {

    private static final String STAGE =
            "配齐下界以外的铁装与补给：做出铁甲头盔/胸甲/护腿/靴各两套(含身上一套)、铁镐两把、铁剑两把";

    private static PlanConsistency.Subtask cond(String item, int min) {
        return new PlanConsistency.Subtask("", Map.of("asset_key", "minecraft:" + item, "minimum", min));
    }

    @Test
    void catchesTheExactContradictionFromTheRun() {
        var warnings = PlanConsistency.check(STAGE, List.of(
                cond("iron_chestplate", 1), cond("iron_helmet", 1),
                cond("iron_sword", 1), cond("iron_pickaxe", 1)));
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("iron_chestplate prose=2 condition=1")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("iron_sword prose=2 condition=1")), warnings.toString());
    }

    @Test
    void consistentPlanIsSilent() {
        var warnings = PlanConsistency.check(STAGE, List.of(
                cond("iron_chestplate", 2), cond("iron_helmet", 2),
                cond("iron_sword", 2), cond("iron_pickaxe", 2)));
        assertEquals(List.of(), warnings, "两套就该配 >=2，不该报");
    }

    @Test
    void missingItemIsNotAccused() {
        // 正文说两套，但子任务里压根没有这件装备 → 不冤枉它（另有「漏步骤」类检查负责）
        var warnings = PlanConsistency.check(STAGE, List.of(cond("iron_chestplate", 2)));
        assertTrue(warnings.stream().noneMatch(w -> w.contains("iron_leggings")), warnings.toString());
    }

    @Test
    void singleSetProseIsNotAWarning() {
        var warnings = PlanConsistency.check("做一套铁甲", List.of(cond("iron_chestplate", 1)));
        assertEquals(List.of(), warnings);
    }

    @Test
    void digitsAreParsedToo() {
        var warnings = PlanConsistency.check("做 3 套铁甲", List.of(cond("iron_chestplate", 1)));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("prose=3")), warnings.toString());
    }

    @Test
    void emptyInputsAreSafe() {
        assertEquals(List.of(), PlanConsistency.check(null, List.of()));
        assertEquals(List.of(), PlanConsistency.check("", null));
    }
    // ── 2026-11-03：语义 vs 条件类型的最小一致性 ──────────────────

    private static PlanConsistency.Subtask typed(String type, Map<String, Object> extra) {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("type", type);
        if (extra != null) m.putAll(extra);
        return new PlanConsistency.Subtask("", m);
    }

    @Test
    void wearStatementNeedsEquipmentCondition() {
        // 实机阶段 1 原文：只给了 container_at + position_at，穿戴从未被证明
        var bad = PlanConsistency.checkSemantics(
                "主用穿戴成型：把已持有的铁头盔/铁胸甲/铁护腿/铁靴子四件按装备槽真实穿上",
                List.of(typed("container_at", Map.of("item", "minecraft:iron_ingot", "minimum", 1)),
                        typed("position_at", Map.of("radius", 8))));
        assertEquals(1, bad.size());
        assertTrue(bad.get(0).startsWith("wear_without_equipment"));

        var good = PlanConsistency.checkSemantics(
                "主用穿戴成型：把四件铁甲按装备槽真实穿上",
                List.of(typed("equipment", Map.of("item", "minecraft:iron_chestplate", "slot", "chest"))));
        assertEquals(List.of(), good);
    }

    @Test
    void storeStatementNeedsContainerCondition() {
        var bad = PlanConsistency.checkSemantics(
                "备用套入库对账：把第二套四部位铁甲搬进指定箱子",
                List.of(typed("equipment", Map.of("item", "minecraft:iron_helmet", "slot", "head"))));
        assertEquals(1, bad.size());
        assertTrue(bad.get(0).startsWith("store_without_container"));

        // 实机阶段 4 的写法：只有 stocked≥40，没有 container_at → 必须报 container_at 缺失
        var stockedOnly = PlanConsistency.checkSemantics(
                "备用套入库对账：把第二套四部位铁甲搬进指定箱子",
                List.of(typed("stocked", Map.of("radius", 8, "minimum", 40))));
        assertEquals(1, stockedOnly.size());
        assertTrue(stockedOnly.get(0).startsWith("store_without_container_at"));

        var good = PlanConsistency.checkSemantics(
                "备用套入库对账：把第二套四部位搬进指定箱子",
                List.of(typed("container_at", Map.of("item", "minecraft:iron_helmet", "minimum", 1))));
        assertEquals(List.of(), good);
    }

    @Test
    void sleepStatementNeedsSleepCondition() {
        // 实机重写后：阶段 3 正文「连续两夜睡床」，条件只有 position_at/base/lit/stocked
        var bad = PlanConsistency.checkSemantics("夜间自保：连续两夜在基地睡床或安全掩蔽",
                List.of(typed("position_at", Map.of("radius", 8)), typed("lit", Map.of("minimum", 4))));
        assertEquals(1, bad.size());
        assertTrue(bad.get(0).startsWith("sleep_not_expressible"));
    }

    @Test
    void singleSleepAdvancementCannotProveTwoNights() {
        var once = PlanConsistency.checkSemantics("夜间自保：连续两夜睡床",
                List.of(new PlanConsistency.Subtask("",
                        Map.of("type", "advancement", "advancement", "minecraft:adventure/sleep_in_bed"))));
        assertEquals(1, once.size());
        assertTrue(once.get(0).startsWith("sleep_once_cannot_prove_two_nights"));
    }

    @Test
    void unrelatedProseProducesNoSemanticWarning() {
        assertEquals(List.of(), PlanConsistency.checkSemantics("去村庄拿干草块并做成面包",
                List.of(cond("wheat", 8))));
    }
}