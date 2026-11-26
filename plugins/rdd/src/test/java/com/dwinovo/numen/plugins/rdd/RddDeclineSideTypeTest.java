package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02：expected_type 大小写不敏感（RECOVERY vs recovery）。 */
class RddDeclineSideTypeTest {

    @Test
    void caseInsensitiveMatch() {
        assertTrue(RddSideType.matches("RECOVERY", "recovery"));
        assertTrue(RddSideType.matches("recovery", "recovery"));
        assertTrue(RddSideType.matches(" Recovery ", "recovery"));
    }

    @Test
    void deathDeclineDoesNotClaimUnverifiedMainlineResumed() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(
                "plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddDeclineSideTool.java"))) {
            root = root.getParent();
        }
        assertTrue(root != null, "source root missing");
        String source = Files.readString(root.resolve(
                "plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddDeclineSideTool.java"));
        assertFalse(source.contains("mainline resumed —"));
        assertTrue(source.contains("mainline remains protected until current capability is verified"));
    }

    @Test
    void differentTypeStillMismatch() {
        assertFalse(RddSideType.matches("sleep", "recovery"));
        assertFalse(RddSideType.matches(null, "recovery"));
    }
}