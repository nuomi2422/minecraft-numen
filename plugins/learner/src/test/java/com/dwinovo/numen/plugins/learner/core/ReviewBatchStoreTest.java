package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-08 bug-17 durable batch claim. */
class ReviewBatchStoreTest {
    private static Memo memo(String id) {
        return new Memo(id, "p", "stage", "tried", "snap", 123L, "learning", List.of());
    }

    @Test void saveLoadDeleteRoundTrip(@TempDir Path dir) {
        var batch = List.of(memo("m1"), memo("m2"));
        assertTrue(ReviewBatchStore.save(dir, "rev-1", batch));
        var got = ReviewBatchStore.load(dir, "rev-1");
        assertEquals(2, got.size());
        assertEquals("m1", got.get(0).id());
        assertTrue(ReviewBatchStore.delete(dir, "rev-1"));
        assertTrue(ReviewBatchStore.load(dir, "rev-1").isEmpty());
    }

    @Test void corruptFileReturnsEmptyNotThrow(@TempDir Path dir) throws Exception {
        Files.writeString(ReviewBatchStore.file(dir, "rev-x"), "not-json");
        assertTrue(ReviewBatchStore.load(dir, "rev-x").isEmpty());
    }
}
