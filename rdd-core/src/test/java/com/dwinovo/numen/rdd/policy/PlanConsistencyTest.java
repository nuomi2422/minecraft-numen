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
}