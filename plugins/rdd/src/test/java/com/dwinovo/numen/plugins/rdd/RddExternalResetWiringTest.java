package com.dwinovo.numen.plugins.rdd;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RddExternalResetWiringTest {
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
        assertTrue(body.contains("\"level\", body.experienceLevel"));
        assertTrue(body.contains("\"total\", body.totalExperience, \"progress\", body.experienceProgress"));
        assertTrue(body.contains("\"cleared\", physicalExperienceCleared"));
        assertEquals(2, body.split("\"physicalExperience\", physicalExperience", -1).length - 1);
        assertEquals(s.indexOf("body.setExperienceLevels(0);"), s.lastIndexOf("body.setExperienceLevels(0);"));
    }
}
