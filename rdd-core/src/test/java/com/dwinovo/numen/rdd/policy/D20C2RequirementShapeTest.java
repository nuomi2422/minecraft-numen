package com.dwinovo.numen.rdd.policy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D20-C2：需求 record 扩字段（importance / minimumReserve）与
 * acquire 基线可见性的契约测试。
 *
 * <p>75号 15.4「数据侧先行」：这两字段当前没有消费者，钉住的是
 * <b>形状兼容</b>——旧三参构造仍可用、默认值不破坏既有判定、
 * 新字段可解析可持久化。后续批次接消费者时不用再动 shape。
 */
class D20C2RequirementShapeTest {

    @Test
    void legacyConstructorKeepsDefaults() {
        RequirementManifest.Requirement r =
                new RequirementManifest.Requirement("minecraft:flint", 1, List.of());
        assertEquals(RequirementManifest.Importance.NORMAL, r.importance());
        assertEquals(0, r.minimumReserve());
        // 判定逻辑完全不受新字段影响
        assertTrue(r.satisfiedBy(Map.of("minecraft:flint", 1)));
        assertFalse(r.satisfiedBy(Map.of()));
        assertEquals("minecraft:flint 需要 1 现有 0", r.gapText(Map.of()));
    }

    @Test
    void newFieldsAreSettableAndReadable() {
        RequirementManifest.Requirement r = new RequirementManifest.Requirement(
                "minecraft:flint", 3, List.of("minecraft:iron"),
                RequirementManifest.Importance.CRITICAL, 1);
        assertEquals(RequirementManifest.Importance.CRITICAL, r.importance());
        assertEquals(1, r.minimumReserve());
        // 判定仍只看 key/minimum/alternatives——消费者没接，加了字段不改变行为
        assertTrue(r.satisfiedBy(Map.of("minecraft:flint", 3)));
        assertTrue(r.satisfiedBy(Map.of("minecraft:iron", 5)));
        assertFalse(r.satisfiedBy(Map.of("minecraft:flint", 2)));
    }

    @Test
    void nullImportanceFallsBackToNormal() {
        RequirementManifest.Requirement r = new RequirementManifest.Requirement(
                "x", 1, null, null, -5);
        assertEquals(RequirementManifest.Importance.NORMAL, r.importance(), "null 归 NORMAL");
        assertEquals(0, r.minimumReserve(), "负预留归 0（防呆）");
    }

    @Test
    void importanceEnumThreeLevels() {
        assertEquals(3, RequirementManifest.Importance.values().length);
        assertArrayEquals(new RequirementManifest.Importance[]{
                RequirementManifest.Importance.CRITICAL,
                RequirementManifest.Importance.NORMAL,
                RequirementManifest.Importance.OPPORTUNISTIC,
        }, RequirementManifest.Importance.values());
    }

    @Test
    void manifestOperationsUnchangedWithNewFields() {
        RequirementManifest.Manifest m = new RequirementManifest.Manifest("goal-1", List.of(
                new RequirementManifest.Requirement("minecraft:dirt", 5, List.of()),
                new RequirementManifest.Requirement("minecraft:flint", 1, List.of(),
                        RequirementManifest.Importance.CRITICAL, 1)));
        assertTrue(m.allSatisfied(Map.of("minecraft:dirt", 10, "minecraft:flint", 1)));
        assertTrue(m.gaps(Map.of("minecraft:dirt", 10, "minecraft:flint", 0)).size() == 1);
        var d = m.detect(Map.of("minecraft:dirt", 10, "minecraft:flint", 0));
        assertFalse(d.satisfied());
        assertEquals(1, d.gaps().size());
    }
}
