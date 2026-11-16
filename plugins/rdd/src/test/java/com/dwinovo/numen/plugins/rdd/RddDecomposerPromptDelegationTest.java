package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.core.PlanningAssetSnapshot;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-17 prompt/schema 拆分后的**委托等价**控 + 少量旧契约矩阵。
 * <p>口径：facade 与 renderer 相等只证明 delegation，不证明旧→新逐字节保真；
 * 旧基线需固定 d20e 旧编译输出（literal/hash），待 Java 槽生成后补，
 * 本测试不用「复制旧渲染算法」冒充 expected。
 */
class RddDecomposerPromptDelegationTest {

    @Test
    void systemPromptFacadeDelegatesToRenderer() {
        assertEquals(RddDecomposer.systemPrompt(), RddDecomposerPrompt.systemPrompt());
    }

    @Test
    void schemaFacadeDelegatesToRenderer() {
        assertEquals(RddDecomposer.decomposeSchema(), RddDecomposerPrompt.decomposeSchema());
    }

    @Test
    void decompositionPromptFacadeDelegates_forHeldMap() {
        Map<String, Integer> held = new LinkedHashMap<>();
        held.put("minecraft:oak_log", 3);
        held.put("minecraft:crafting_table", 1);
        assertEquals(
                RddDecomposer.decompositionPrompt("建个木屋", held, List.of("阶段一", "阶段二")),
                RddDecomposerPrompt.decompositionPrompt("建个木屋", held, List.of("阶段一", "阶段二")));
    }

    @Test
    void decompositionPromptFacadeDelegates_forSnapshot() {
        PlanningAssetSnapshot snapshot = PlanningAssetSnapshot.from(Map.of("minecraft:oak_log", 3), new AssetRegistry());
        assertEquals(
                RddDecomposer.decompositionPrompt("建个木屋", snapshot, List.of()),
                RddDecomposerPrompt.decompositionPrompt("建个木屋", snapshot, List.of()));
    }

    @Test
    void boundedIdListAndMarkerFacadeDelegates() {
        List<String> ids = List.of("a", "b", "c");
        assertEquals(RddDecomposer.boundedIdList(ids), RddDecomposerPrompt.boundedIdList(ids));
        assertEquals(RddDecomposer.marker(3), RddDecomposerPrompt.marker(3));
    }

    // ---- 旧契约矩阵（不依赖复制渲染算法）----

    @Test
    void worldAssetsAndVillageOverloadsAreIgnored() {
        Map<String, Integer> held = new LinkedHashMap<>();
        held.put("minecraft:oak_log", 3);
        String plain = RddDecomposer.decompositionPrompt("建个木屋", held, List.of("阶段一"));
        assertEquals(plain, RddDecomposer.decompositionPrompt("建个木屋", held, List.of("阶段一"), "世界资产"));
        assertEquals(plain, RddDecomposer.decompositionPrompt("建个木屋", held, List.of("阶段一"), "世界资产", "村庄块"));
    }

    @Test
    void nullHeldEqualsEmptyHeld_andOmitsHeldBlock() {
        String viaNull = RddDecomposer.decompositionPrompt("目标", (Map<String, Integer>) null, List.of());
        String viaEmpty = RddDecomposer.decompositionPrompt("目标", Map.of(), List.of());
        assertEquals(viaNull, viaEmpty);
        assertFalse(viaNull.contains("【你当前已真实持有（背包扫描）：】"));
    }

    @Test
    void completedStagesKeepOrder_andObjectiveKeptVerbatim() {
        String out = RddDecomposer.decompositionPrompt("建个木屋（含中文）", Map.of(), List.of("阶段一", "阶段二"));
        assertTrue(out.startsWith("主人的目标：建个木屋（含中文）"));
        int first = out.indexOf("阶段一");
        int second = out.indexOf("阶段二");
        assertTrue(first >= 0 && second > first, "completed 顺序必须保持");
    }

    @Test
    void boundedIdListSmallCasesExact() {
        assertEquals("", RddDecomposer.boundedIdList(List.of()));
        assertEquals("a、b、c", RddDecomposer.boundedIdList(List.of("a", "b", "c")));
        assertEquals("（其余 3 条略）", RddDecomposer.marker(3));
        assertEquals(12, RddDecomposer.ID_LIST_MAX_IDS);
        assertEquals(240, RddDecomposer.ID_LIST_MAX_CHARS);
    }
}