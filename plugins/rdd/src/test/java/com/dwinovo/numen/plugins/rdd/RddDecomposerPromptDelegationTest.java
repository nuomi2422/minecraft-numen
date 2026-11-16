package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.core.PlanningAssetSnapshot;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 2026-11-17 prompt/schema 职责拆分后的最小完整输出对照：
 * 原入口 {@link RddDecomposer} 与渲染类 {@link RddDecomposerPrompt} 对同一输入必须逐字节/深相等；
 * 既有动态断言（AssetBounds 等）不改，只补入口与渲染的完整输出等价。
 */
class RddDecomposerPromptDelegationTest {

    @Test
    void systemPromptEntryIsByteIdentical() {
        assertEquals(RddDecomposer.systemPrompt(), RddDecomposerPrompt.systemPrompt());
    }

    @Test
    void schemaEntryIsDeepEqual() {
        assertEquals(RddDecomposer.decomposeSchema(), RddDecomposerPrompt.decomposeSchema());
    }

    @Test
    void decompositionPromptFullOutputMatches_forHeldMap() {
        Map<String, Integer> held = new LinkedHashMap<>();
        held.put("minecraft:oak_log", 3);
        held.put("minecraft:crafting_table", 1);
        String viaEntry = RddDecomposer.decompositionPrompt("建个木屋", held, List.of("阶段一", "阶段二"));
        String viaRenderer = RddDecomposerPrompt.decompositionPrompt("建个木屋", held, List.of("阶段一", "阶段二"));
        assertEquals(viaEntry, viaRenderer);
    }

    @Test
    void decompositionPromptFullOutputMatches_forSnapshot() {
        PlanningAssetSnapshot snapshot = PlanningAssetSnapshot.from(Map.of("minecraft:oak_log", 3), new AssetRegistry());
        String viaEntry = RddDecomposer.decompositionPrompt("建个木屋", snapshot, List.of());
        String viaRenderer = RddDecomposerPrompt.decompositionPrompt("建个木屋", snapshot, List.of());
        assertEquals(viaEntry, viaRenderer);
    }

    @Test
    void boundedIdListAndMarkerEntriesDelegate() {
        List<String> ids = List.of("a", "b", "c");
        assertEquals(RddDecomposer.boundedIdList(ids), RddDecomposerPrompt.boundedIdList(ids));
        assertEquals(RddDecomposer.marker(3), RddDecomposerPrompt.marker(3));
    }
}