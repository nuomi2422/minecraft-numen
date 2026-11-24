package com.dwinovo.numen.plugins.rdd;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RddExternalResetWiringTest {
    /**
     * 2026-11-24 迁移实机：reset 唯一失败是 IOException home store unavailable。
     * 行为面证据：无 facts 目录绑定 → clear 必须失败关闭；经生产初始化绑定后同一调用必须能清。
     */
    @Test void homeAnchorClearWorksAfterTheFactsStoreInitAndFailsClosedBeforeIt(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.util.UUID id = java.util.UUID.randomUUID();
        java.nio.file.Path file = dir.resolve("home-anchor-" + id + ".json");
        Files.writeString(file, "{}");
        RddPlugin.useFactsDirForTest(null);
        assertThrows(java.io.IOException.class, () -> RddHomeAnchor.clear(id),
                "no bound store must fail closed instead of silently succeeding");
        RddPlugin.useFactsDirForTest(dir);
        try {
            assertDoesNotThrow(() -> RddHomeAnchor.clear(id),
                    "the host facts init must bind the home store before any reset side effect");
            assertFalse(Files.exists(file), "clear must delete the same companion's home anchor file");
        } finally {
            RddPlugin.useFactsDirForTest(null);
        }
    }

    @Test void resetFailsClosedAndClearsAllInventoryOnlyAfterChainAndDisk() throws Exception {
        String s = Files.readString(Path.of("src/main/java/com/dwinovo/numen/plugins/rdd/RddPlugin.java"));
        String body = s.substring(s.indexOf("private static Map<String, Object> resetCurrentWorld"), s.indexOf("public static void remove(UUID companionId)"));
        assertTrue(body.indexOf("completionPending(id)") < body.indexOf("removeCurrent(id)"));
        assertTrue(body.indexOf("removeCurrent(id)") < body.indexOf("RddAssetStore.save"));
        assertTrue(body.indexOf("RddAssetStore.save") < body.indexOf("ASSETS.put"));
        assertTrue(body.indexOf("RddHomeAnchor.clear") < body.indexOf("clearContent()"));
        assertTrue(body.indexOf("task_stop") < body.indexOf("removeCurrent(id)"));
        assertTrue(body.contains("getContainerSize()"));
        assertTrue(body.contains("inventory reset incomplete"));
        assertFalse(body.contains("historyDir"));
        assertFalse(body.contains("ledgerDir"));
        // Wiring only: the native Player setters/readback still need an owned-runtime test, not a fake MC host.
        int xpReset = body.indexOf("body.setExperienceLevels(0);");
        assertTrue(xpReset > body.indexOf("inventory reset incomplete"));
        assertTrue(body.contains("body.setExperiencePoints(0);"));
        assertTrue(body.contains("body.totalExperience = 0;"));
        assertTrue(body.contains("body.experienceLevel == 0 && body.totalExperience == 0 && body.experienceProgress == 0.0f"));
        assertTrue(body.indexOf("physical experience reset incomplete") < body.indexOf("RddMonitor.publish(\"external_world_reset\""));
        // Merge wiring only (not native): XP readback, then inheritance retirement, then the success event.
        int xpReadback = body.indexOf("physical experience reset incomplete");
        int retire = body.indexOf("RddFactStore.retireStageInheritance(factsDir, id, completedFacts)");
        assertTrue(xpReadback < retire);
        assertTrue(retire < body.indexOf("RddMonitor.publish(\"external_world_reset\""));
        assertTrue(body.contains("\"level\", body.experienceLevel"));
        assertTrue(body.contains("\"total\", body.totalExperience, \"progress\", body.experienceProgress"));
        assertTrue(body.contains("\"cleared\", physicalExperienceCleared"));
        assertEquals(2, body.split("\"physicalExperience\", physicalExperience", -1).length - 1);
        assertEquals(s.indexOf("body.setExperienceLevels(0);"), s.lastIndexOf("body.setExperienceLevels(0);"));
    }
}
