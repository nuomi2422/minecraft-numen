package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.plugins.learner.core.Memo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-08 C1 review-result commit gate. */
class ReviewTargetGateTest {
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static Memo memo(String ref, String version) {
        return new Memo("m", "p", "EXPERIENCE_EXPAND", "t", "ref=" + ref + " version=" + version, 1L, "learning", List.of());
    }

    @Test void retractedFailsClosed(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("experience-" + C + ".jsonl"),
                "{\"id\":\"e1\",\"publication\":\"TRIAL\",\"retracted\":true}\n");
        assertFalse(ReviewTargetGate.check(dir, C, memo("exp:e1", "v1")).ok());
    }

    @Test void missingFailsClosed(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("experience-" + C + ".jsonl"),
                "{\"id\":\"other\",\"publication\":\"TRIAL\",\"retracted\":false}\n");
        assertEquals("MISSING", ReviewTargetGate.check(dir, C, memo("exp:e1", "v1")).reason());
    }

    @Test void versionUnverifiableFailsClosed(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("experience-" + C + ".jsonl"),
                "{\"id\":\"e1\",\"publication\":\"TRIAL\",\"retracted\":false}\n");
        var c = ReviewTargetGate.check(dir, C, memo("exp:e1", "v1"));
        assertFalse(c.ok(), "版本不可核不得当有效 verdict");
        assertEquals("VERSION_UNVERIFIED", c.reason());
    }

    @Test void nonExpansionMemoNotGated() {
        assertTrue(ReviewTargetGate.check(null, C, new Memo("x", "p", "learning", "t", "s", 1L, "learning", List.of())).ok());
    }

    @Test void exactVersionMatches(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("experience-" + C + ".jsonl"),
                "{\"id\":\"e1\",\"publication\":\"TRIAL\",\"retracted\":false,\"content_fingerprint\":\"v1\"}\n");
        var c = ReviewTargetGate.check(dir, C, memo("exp:e1", "v1"));
        assertTrue(c.ok(), c.reason());
        assertEquals("OK", c.reason());
    }

    @Test void versionMismatchFailsClosed(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("experience-" + C + ".jsonl"),
                "{\"id\":\"e1\",\"publication\":\"TRIAL\",\"retracted\":false,\"content_fingerprint\":\"v2\"}\n");
        var c = ReviewTargetGate.check(dir, C, memo("exp:e1", "v1"));
        assertFalse(c.ok());
        assertEquals("VERSION_MISMATCH", c.reason());
    }
}
