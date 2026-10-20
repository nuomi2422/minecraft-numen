package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.AssetRequirement;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G1c-3：转化归因进入缺口渲染（75号 §3.2：数量减少 ≠ 东西没了）。
 *
 * <p>钉住：只给命中的未满足行加「近期转化」，不改变任何数量判定；
 * 过期归因（>10 分钟）不再出现。
 */
class G1c3TransformAttributionTest {

    private static PrimaryGoal goal() {
        return new PrimaryGoal("primary-g1c3", "备料", List.of(
                Subtask.hardCoded("s1", "原木", Map.of("asset_key", "minecraft:oak_log", "minimum", 32)),
                Subtask.hardCoded("s2", "木棍", Map.of("asset_key", "minecraft:stick", "minimum", 64))),
                List.of());
    }

    @Test
    void needsBlockExplainsTransformInsteadOfTreatingItAsLoss() {
        List<RddFactContext.Need> needs = RddFactContext.needsOf(goal());
        Map<String, Integer> held = Map.of("minecraft:oak_log", 0, "minecraft:stick", 18);

        String plain = RddFactContext.renderNeedsBlock(needs, held);
        assertFalse(plain.contains("近期转化"), "没有归因时不许多写：" + plain);

        String explained = RddFactContext.renderNeedsBlock(needs, held, Map.of(),
                Map.of("minecraft:oak_log", "log→planks×8"));
        assertTrue(explained.contains("minecraft:oak_log ×32（现有 0）（近期转化 log→planks×8）"), explained);
        assertFalse(explained.contains("minecraft:stick ×64（现有 18）（近期转化"),
                "没被转化过的资源不许带归因：" + explained);
    }

    @Test
    void unmetTextCarriesTheSameAttribution() {
        List<RddFactContext.Need> needs = RddFactContext.needsOf(goal());
        Map<String, Integer> held = Map.of("minecraft:oak_log", 0, "minecraft:stick", 18);

        String text = RddFactContext.unmetText(needs, held, Map.of("minecraft:oak_log", "log→planks×8"));
        assertTrue(text.contains("minecraft:oak_log 需32 有0（近期转化 log→planks×8）"), text);
        assertFalse(text.contains("minecraft:stick 需64 有18（近期转化"), text);
    }

    @Test
    void quantitiesAndOrderingAreUntouched() {
        List<RddFactContext.Need> needs = RddFactContext.needsOf(goal());
        Map<String, Integer> held = Map.of("minecraft:oak_log", 0, "minecraft:stick", 18);

        String withNotes = RddFactContext.renderNeedsBlock(needs, held, Map.of(),
                Map.of("minecraft:oak_log", "log→planks×8"));
        String without = RddFactContext.renderNeedsBlock(needs, held);
        // 去掉归因文本后应与不带归因的渲染逐字一致 —— 证明本批只加解释、不改判定
        String stripped = withNotes.replace("（近期转化 log→planks×8）", "");
        assertEquals(without, stripped, "归因不许顺带改数量/顺序/前缀");
    }
}
