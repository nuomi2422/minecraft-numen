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
    }
}
