package com.dwinovo.numen.plugins.learner.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-08 046-A: body must be readable after write/restart; no path traversal. */
class SkillBodyStoreTest {
    @Test void writeThenReadRoundTrip(@TempDir Path dir) {
        String ref = SkillBodyStore.write(dir, "sk-test", 1, "abc123", "# body\nstep");
        assertNotNull(ref);
        assertTrue(ref.startsWith("skill-body:"));
        assertEquals("# body\nstep", SkillBodyStore.read(dir, ref));
        assertTrue(Files.exists(dir.resolve("skill-bodies")));
    }

    @Test void emptyBodyOrNullDirReturnsNull(@TempDir Path dir) {
        assertNull(SkillBodyStore.write(dir, "sk-test", 1, "h", ""));
        assertNull(SkillBodyStore.write(null, "sk-test", 1, "h", "x"));
    }

    @Test void pathTraversalIsNeutralized(@TempDir Path dir) throws Exception {
        String ref = SkillBodyStore.write(dir, "../../evil", 1, "../../x", "safe");
        assertNotNull(ref);
        String rel = ref.substring("skill-body:".length());
        assertFalse(rel.contains(".."), rel);
        assertFalse(Files.exists(dir.getParent().resolve("evil@v1-x.md")));
        assertEquals("safe", SkillBodyStore.read(dir, ref));
    }

    @Test void rewriteSameTargetIsIdempotent(@TempDir Path dir) {
        String a = SkillBodyStore.write(dir, "sk-x", 1, "h", "one");
        String b = SkillBodyStore.write(dir, "sk-x", 1, "h", "two");
        assertEquals(a, b);
        assertEquals("two", SkillBodyStore.read(dir, b));
    }
}
