package com.dwinovo.numen.plugins.learner.sample;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TTL 归档：只归档、不删证据（158 §8）。
 */
class SampleStoreTtlTest {

    private static final long MIN = 60_000L;
    private static final long T0 = 1_700_000_000_000L;
    private static final long BUCKET = 5 * MIN;

    private static SampleAdmission.Candidate death(long at, String ev) {
        return new SampleAdmission.Candidate("c1", "death", "被僵尸打死", "zombie",
                "run-1", "g1", "1", "w1", "overworld", at, ev, "instrumentation");
    }

    @Test
    void 超期样本被归档但证据一条不少(@TempDir Path dir) throws IOException {
        SampleStore store = SampleStore.open(dir.resolve("samples.jsonl"), BUCKET);
        store.admit(death(T0, "ev-1"), T0);
        store.admit(death(T0 + 1_000, "ev-2"), T0 + 1_000);
        String id = store.samples().get(0).sampleId();

        int n = store.archiveExpired(T0 + 60 * MIN, 30 * MIN);

        assertEquals(1, n);
        LearningSample s = store.get(id).orElseThrow();
        assertEquals(SampleState.ARCHIVED, s.state());
        assertEquals(2, s.occurrenceCount(), "归档的是待办，不是事实：occurrence 一条都不许删");
        assertEquals(T0, s.earliestEvidenceAt());
    }

    @Test
    void 没超期的不动(@TempDir Path dir) throws IOException {
        SampleStore store = SampleStore.open(dir.resolve("samples.jsonl"), BUCKET);
        store.admit(death(T0, "ev-1"), T0);

        assertEquals(0, store.archiveExpired(T0 + 10 * MIN, 30 * MIN));
        assertEquals(SampleState.OPEN, store.samples().get(0).state());
    }

    @Test
    void 已归档的不再重复计数(@TempDir Path dir) throws IOException {
        SampleStore store = SampleStore.open(dir.resolve("samples.jsonl"), BUCKET);
        store.admit(death(T0, "ev-1"), T0);
        assertEquals(1, store.archiveExpired(T0 + 60 * MIN, 30 * MIN));
        assertEquals(0, store.archiveExpired(T0 + 120 * MIN, 30 * MIN),
                "已归档的样本不是待办态，第二次扫不能重复计数");
        assertEquals(1, store.occurrenceCount(), "两次扫之后证据仍在");
    }

    @Test
    void 过期按最近证据算而不是按登记时刻(@TempDir Path dir) throws IOException {
        // ⚠ 变异测试 M78 逼出来的：原用例里 createdAt 与 latestEvidenceAt 恰好相同，
        //   把判据从「最近证据」换成「创建时间」测试照样全绿。
        //   实际影响：一条 3 小时前登记、刚刚又发生过的样本，会被误判为过期归档。
        SampleStore store = SampleStore.open(dir.resolve("samples.jsonl"), BUCKET);
        long evidenceAt = T0;
        long createdAt = T0 - 3 * 60 * MIN;
        store.admit(death(evidenceAt, "ev-1"), createdAt);

        assertEquals(0, store.archiveExpired(evidenceAt + 10 * MIN, 30 * MIN),
                "刚发生过的事不该因为「登记得早」被归档");
        assertEquals(1, store.archiveExpired(evidenceAt + 40 * MIN, 30 * MIN),
                "真的静置超期才归档");
    }

    @Test
    void ttl非法直接拒绝(@TempDir Path dir) throws IOException {
        SampleStore store = SampleStore.open(dir.resolve("samples.jsonl"), BUCKET);
        assertThrows(IllegalArgumentException.class, () -> store.archiveExpired(T0, 0L));
        assertThrows(IllegalArgumentException.class, () -> store.archiveExpired(T0, -1L));
    }

    @Test
    void 归档状态重启后仍在(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("samples.jsonl");
        SampleStore store = SampleStore.open(f, BUCKET);
        store.admit(death(T0, "ev-1"), T0);
        store.archiveExpired(T0 + 60 * MIN, 30 * MIN);

        SampleStore reopened = SampleStore.open(f, BUCKET);
        assertEquals(SampleState.ARCHIVED, reopened.samples().get(0).state());
        assertEquals(1, reopened.occurrenceCount());
    }
}
