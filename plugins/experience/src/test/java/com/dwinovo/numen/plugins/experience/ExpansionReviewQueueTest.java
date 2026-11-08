package com.dwinovo.numen.plugins.experience;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-08 C1: expansion review queue idempotence. */
class ExpansionReviewQueueTest {
    private static final java.util.UUID C = java.util.UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    @Test void sameRefVersionUseQueuedOnce(@TempDir Path dir) throws Exception {
        assertTrue(ExpansionReviewQueue.enqueue(dir, C, "exp:e1", "v1", "call-1"));
        assertFalse(ExpansionReviewQueue.enqueue(dir, C, "exp:e1", "v1", "call-1"), "重复展开不得重复入队");
        assertEquals(1, ExpansionReviewQueue.size(dir));
        assertTrue(Files.readString(dir.resolve(ExpansionReviewQueue.FILE)).contains("call-1"));
    }

    @Test void differentUseIdQueuesAgain(@TempDir Path dir) {
        assertTrue(ExpansionReviewQueue.enqueue(dir, C, "exp:e1", "v1", "call-3"));
        assertTrue(ExpansionReviewQueue.enqueue(dir, C, "exp:e1", "v1", "call-4"));
        assertEquals(2, ExpansionReviewQueue.size(dir));
    }
}
