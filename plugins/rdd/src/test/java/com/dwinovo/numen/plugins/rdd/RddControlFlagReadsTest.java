package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Real temporary-file IO; no Minecraft world or flag-state copy. */
class RddControlFlagReadsTest {
    @TempDir Path temp;

    @Test void missingAndEmptyFlagsRetainTheirDifferentDefaults() throws Exception {
        Path flag = temp.resolve("flag");
        assertFalse(RddControlFlagReads.bodyDispatchEnabled(flag));
        assertFalse(RddControlFlagReads.supervisionPaused(flag));
        assertFalse(RddControlFlagReads.pauseDisabled(flag));
        Files.writeString(flag, "  ");
        assertFalse(RddControlFlagReads.bodyDispatchEnabled(flag));
        assertFalse(RddControlFlagReads.supervisionPaused(flag));
        assertTrue(RddControlFlagReads.pauseDisabled(flag));
    }

    @Test void bodyAndSupervisionDoNotShareTheSameVocabulary() throws Exception {
        Path flag = temp.resolve("flag");
        for (String text : List.of("on", "ON", "1")) {
            Files.writeString(flag, " " + text + " ");
            assertTrue(RddControlFlagReads.bodyDispatchEnabled(flag));
            assertTrue(RddControlFlagReads.supervisionPaused(flag));
        }
        Files.writeString(flag, " RuN ");
        assertTrue(RddControlFlagReads.bodyDispatchEnabled(flag));
        assertFalse(RddControlFlagReads.supervisionPaused(flag));
        for (String text : List.of("pause", "0", "未知")) {
            Files.writeString(flag, text);
            assertFalse(RddControlFlagReads.bodyDispatchEnabled(flag));
            assertTrue(RddControlFlagReads.supervisionPaused(flag));
        }
    }

    @Test void readsRemainFreshAndIoFailureIsNotConvertedToAState() throws Exception {
        Path flag = temp.resolve("flag");
        Files.writeString(flag, "run");
        assertTrue(RddControlFlagReads.bodyDispatchEnabled(flag));
        Files.writeString(flag, "pause");
        assertFalse(RddControlFlagReads.bodyDispatchEnabled(flag));
        assertTrue(RddControlFlagReads.supervisionPaused(flag));
        Files.delete(flag);
        assertFalse(RddControlFlagReads.pauseDisabled(flag));
        assertThrows(IOException.class, () -> RddControlFlagReads.bodyDispatchEnabled(temp));
        assertThrows(IOException.class, () -> RddControlFlagReads.supervisionPaused(temp));
        assertTrue(RddControlFlagReads.pauseDisabled(temp), "existence-only pause semantics remain unchanged");
    }

    @Test void realPluginKeepsExceptionFallbackAndPublicationOwnership() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("plugins/rdd/src/main/java"))) root = root.getParent();
        assertNotNull(root);
        String source = Files.readString(root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddPlugin.java"));
        assertTrue(source.contains("on = RddControlFlagReads.bodyDispatchEnabled(bodyDispatchFlag);"));
        assertTrue(source.contains("on = bodySubmissionEnabled; // 读失败保持当前"));
        assertTrue(source.contains("paused = RddControlFlagReads.supervisionPaused(supervisionFlag);"));
        assertTrue(source.contains("paused = !supervisionEnabled; // flag 读取失败保持当前状态"));
        assertTrue(source.contains("disabled = RddControlFlagReads.pauseDisabled(pauseDisabledFlag);"));
        assertTrue(source.contains("disabled = !pauseEnabled;"));
        assertTrue(source.contains("RddMonitor.publish(\"body_dispatch_state\""));
        assertTrue(source.contains("RddMonitor.publish(\"supervision_state\""));
    }
}
