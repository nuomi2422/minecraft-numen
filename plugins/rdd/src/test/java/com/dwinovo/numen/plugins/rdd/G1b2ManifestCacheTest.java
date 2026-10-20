package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.AssetRequirement;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.policy.RequirementManifest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G1b2 契约测试（V-20261020-13 债①）：需求清单缓存必须对「一级懒展开」失效。
 *
 * <p>缺陷现场：Stage-A 绑定一级时 unexpanded、二级为空 → 清单只有 waitFor；
 * GoalDriver 注入二级后 primary.id/goalId 都不变 → 旧缓存被永远命中，
 * replan 块与 requirement_manifest 事件少报（执行侧 needs 块现算，不受影响）。
 *
 * <p>钉两条：S45 展开后必须重算（数值变大）；S46 指纹不变必须仍走缓存（同实例）。
 */
class G1b2ManifestCacheTest {

    private static PrimaryGoal unexpanded() {
        // 绑定时刻：只有 waitFor，二级空
        return PrimaryGoal.unexpanded("primary-g1b2", "备料阶段",
                List.of(new AssetRequirement("minecraft:oak_log", 8)));
    }

    private static PrimaryGoal expanded() {
        // 展开后：同 id/goalId，waitFor + 3 条二级 asset_key
        return new PrimaryGoal("primary-g1b2", "备料阶段", List.of(
                Subtask.hardCoded("s0", "木棍", Map.of("asset_key", "minecraft:stick", "minimum", 24)),
                Subtask.hardCoded("s1", "火把", Map.of("asset_key", "minecraft:torch", "minimum", 16)),
                Subtask.hardCoded("s2", "圆石", Map.of("asset_key", "minecraft:cobblestone", "minimum", 64))),
                List.of(new AssetRequirement("minecraft:oak_log", 8)));
    }

    @Test
    void s45_expansionInvalidatesCachedManifest() {
        UUID companion = UUID.randomUUID();
        String goalId = "goal-g1b2";

        RequirementManifest.Manifest atBind = RddFactContext.ensureManifest(companion, unexpanded(), goalId);
        assertEquals(1, atBind.requirements().size(),
                "绑定时刻只有 waitFor 一条（Stage-A 一级未展开）");

        RequirementManifest.Manifest afterExpand = RddFactContext.ensureManifest(companion, expanded(), goalId);
        assertEquals(4, afterExpand.requirements().size(),
                "展开后必须重算：waitFor(oak_log) + stick/torch/cobblestone 三条 = 4");
        assertTrue(afterExpand.requirements().stream()
                        .anyMatch(r -> "minecraft:torch".equals(r.key())),
                "展开后的二级需求必须进清单（旧缓存会漏掉）");
        // waitFor 的 CRITICAL 不因重算丢档
        var oak = afterExpand.requirements().stream()
                .filter(r -> "minecraft:oak_log".equals(r.key())).findFirst().orElseThrow();
        assertEquals(RequirementManifest.Importance.CRITICAL, oak.importance(),
                "重算后 waitFor 仍是 CRITICAL（G1b 语义不许被缓存修复带偏）");
    }

    @Test
    void s46_unchangedFingerprintStillHitsCache() {
        UUID companion = UUID.randomUUID();
        String goalId = "goal-g1b2";

        RequirementManifest.Manifest first = RddFactContext.ensureManifest(companion, expanded(), goalId);
        RequirementManifest.Manifest second = RddFactContext.ensureManifest(companion, expanded(), goalId);
        assertSame(first, second,
                "展开指纹不变必须仍走缓存（否则每 30s tick 重建清单一遍，白烧 CPU/事件）");
    }

    @Test
    void s46b_primaryChangeStillInvalidates() {
        UUID companion = UUID.randomUUID();
        RddFactContext.ensureManifest(companion, unexpanded(), "goal-a");

        PrimaryGoal other = PrimaryGoal.unexpanded("primary-other", "换阶段",
                List.of(new AssetRequirement("minecraft:iron_ore", 3)));
        RequirementManifest.Manifest changed = RddFactContext.ensureManifest(companion, other, "goal-b");
        assertEquals(1, changed.requirements().size());
        assertEquals("minecraft:iron_ore", changed.requirements().get(0).key(),
                "换 primary/goal 必须重建（既有语义回归）");
    }
}
