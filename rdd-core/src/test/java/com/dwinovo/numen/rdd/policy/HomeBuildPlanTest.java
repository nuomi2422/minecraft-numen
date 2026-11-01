package com.dwinovo.numen.rdd.policy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 167 §B1：建家必须是**一条带依赖的可序列化路线**，不是每次现编。
 */
class HomeBuildPlanTest {

    @Test
    void orderIsTopologicallyValid() {
        List<HomeBuildPlan.Node> nodes = HomeBuildPlan.standard();
        assertNull(HomeBuildPlan.firstDependencyViolation(nodes), "依赖顺序必须成立");
    }

    @Test
    void idsAreStableAndUnique() {
        List<HomeBuildPlan.Node> nodes = HomeBuildPlan.standard();
        Map<String, HomeBuildPlan.Node> byId = HomeBuildPlan.byId(nodes);
        assertEquals(nodes.size(), byId.size(), "id 不许重复（幂等与已完成事实都靠它）");
        for (String expected : List.of("home.platform", "home.house", "home.house.register",
                "home.house.verify", "home.bind_bed", "home.reserve_box.register",
                "home.reserve_box.stock", "home.lit", "home.farm", "home.farm.verify")) {
            assertNotNull(byId.get(expected), "缺少稳定节点: " + expected);
        }
    }

    @Test
    void registerAndVerifyAreSeparateNodes() {
        List<HomeBuildPlan.Node> nodes = HomeBuildPlan.standard();
        Map<String, HomeBuildPlan.Node> byId = HomeBuildPlan.byId(nodes);
        assertTrue(byId.get("home.house.verify").dependsOn().contains("home.house.register"),
                "验收必须依赖登记（167：登记与验收分开）");
    }

    @Test
    void buildNodesCarryMaterialsAndNonRetryFailureAdvice() {
        for (HomeBuildPlan.Node n : HomeBuildPlan.standard()) {
            assertFalse(n.acceptance().isBlank(), n.id() + " 必须有验收条件");
            assertFalse(n.onFailure().isBlank(), n.id() + " 必须有失败恢复，不许留空");
            if (!n.templateId().isBlank()) {
                assertFalse(n.materials().isEmpty(), n.id() + " 是模板施工，必须写明物料");
            }
        }
    }

    @Test
    void farmIsNotReadyJustBecauseStructureExists() {
        List<HomeBuildPlan.Node> nodes = HomeBuildPlan.standard();
        Map<String, HomeBuildPlan.Node> byId = HomeBuildPlan.byId(nodes);
        assertTrue(byId.get("home.farm").acceptance().contains("灌水"),
                "农田的验收必须包含灌水，否则 STRUCTURE_ONLY 会被当成生产就绪");
        assertTrue(byId.get("home.farm.verify").acceptance().contains("真实产出"));
    }

    @Test
    void violationIsDetectedWhenOrderIsBroken() {
        List<HomeBuildPlan.Node> bad = List.of(
                new HomeBuildPlan.Node("b", "", List.of("a"), Map.of(), "x", "y"),
                new HomeBuildPlan.Node("a", "", List.of(), Map.of(), "x", "y"));
        assertEquals("b -> a", HomeBuildPlan.firstDependencyViolation(bad));
    }
}
