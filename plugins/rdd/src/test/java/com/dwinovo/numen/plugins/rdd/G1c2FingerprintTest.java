package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.AssetRequirement;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.policy.RequirementManifest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G1c-2：展开指纹从「二级数」升级为「二级 id 连接串」。
 *
 * <p>钉住：整组二级被替换（数量不变、id 不同）时缓存必须失效重算——
 * 只比数量会漏，replan 块/事件就会拿旧清单。
 */
@Disabled("2026-10-25 结构性跑不了：类初始化时加载 Minecraft 类型（net.minecraft.world.level.block.state.properties.Property），而 :plugins:rdd 的测试类路径没有 MC 运行时（numen-plugin 约定把宿主 API 与 MC 都设为 compileOnly）。与业务逻辑无关；补齐 MC 测试运行时后应重新启用本类。")
class G1c2FingerprintTest {

    private static PrimaryGoal expandedWith(String... subtaskIds) {
        List<Subtask> subs = new java.util.ArrayList<>();
        for (String id : subtaskIds) {
            subs.add(Subtask.hardCoded(id, "步骤 " + id,
                    Map.of("asset_key", "minecraft:" + id.replace('-', '_'), "minimum", 1)));
        }
        return new PrimaryGoal("primary-fp", "阶段", subs,
                List.of(new AssetRequirement("minecraft:oak_log", 4)));
    }

    @Test
    void sameCountButDifferentIdsInvalidates() {
        UUID companion = UUID.randomUUID();
        RequirementManifest.Manifest first = RddFactContext.ensureManifest(companion,
                expandedWith("a-1", "b-2", "c-3"), "goal-fp");
        assertEquals(4, first.requirements().size(), "waitFor 1 + 3 个二级 = 4");

        // 数量相同（3 个）但整组换掉：raw_iron/bread/stone
        RequirementManifest.Manifest replaced = RddFactContext.ensureManifest(companion,
                expandedWith("raw-iron", "bread", "stone"), "goal-fp");
        assertTrue(replaced.requirements().stream()
                        .anyMatch(r -> "minecraft:raw_iron".equals(r.key())),
                "整组替换必须重算（只比数量会漏）：" + replaced.requirements());
        assertEquals(4, replaced.requirements().size());
    }

    @Test
    void sameIdsStillHitCache() {
        UUID companion = UUID.randomUUID();
        RequirementManifest.Manifest first = RddFactContext.ensureManifest(companion,
                expandedWith("x-1", "y-2"), "goal-fp");
        RequirementManifest.Manifest again = RddFactContext.ensureManifest(companion,
                expandedWith("x-1", "y-2"), "goal-fp");
        assertSame(first, again, "id 集合未变必须仍走缓存（不每 tick 重建）");
    }

    @Test
    void fingerprintIsEmptyForUnexpanded() {
        assertEquals("", RddFactContext.subtaskFingerprint(
                PrimaryGoal.unexpanded("p", "阶段", List.of())));
        assertEquals("", RddFactContext.subtaskFingerprint(null));
    }
}
