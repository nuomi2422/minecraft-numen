package com.dwinovo.numen.monitor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-05：轮转器只归档不清理，实机 context-* 累积 362 份 ≈ 5.8 GB。
 * 这个测试钉住"只删该类别的旧归档、绝不碰正在写的活动文件"。
 */
class MonitoringJournalRetentionTest {

    private static void touch(Path dir, String name, long mtime) throws Exception {
        Path p = dir.resolve(name);
        Files.writeString(p, "x");
        Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.fromMillis(mtime));
    }

    @Test
    void prunesOldRotatedFilesButKeepsActiveAndOtherCategories(@TempDir Path dir) throws Exception {
        long base = 1_700_000_000_000L;
        for (int i = 0; i < 25; i++) touch(dir, "context-" + (base + i) + ".jsonl", base + i);
        touch(dir, "context.jsonl", base + 999);              // 正在写的，绝不能删
        touch(dir, "rdd-1.jsonl", base + 1);                  // 别的类别，绝不能删

        int removed = MonitoringJournal.pruneRotated(dir, "context", 20);

        assertEquals(5, removed, "25 份归档保留 20 份 → 删 5");
        assertEquals(20, Files.list(dir).filter(p -> p.getFileName().toString().matches("context-\\d+\\.jsonl")).count());
        assertTrue(Files.exists(dir.resolve("context.jsonl")), "活动文件不能被删");
        assertTrue(Files.exists(dir.resolve("rdd-1.jsonl")), "别的类别不能被误删");
    }

    @Test
    void keepsEverythingWhenUnderLimit(@TempDir Path dir) throws Exception {
        for (int i = 0; i < 3; i++) touch(dir, "context-" + (100 + i) + ".jsonl", 100 + i);
        assertEquals(0, MonitoringJournal.pruneRotated(dir, "context", 20));
    }
}
