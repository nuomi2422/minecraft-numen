package com.dwinovo.numen.plugins.learner.sample;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 158 §6 幂等 + 159 §3 剧本 1 的存储面：<b>重启后同一事故不能变成第二条样本</b>。
 *
 * <p>这是整个样本层最容易被写错、也最贵的一条：幂等一旦破了，长跑里每条事故都会在重启后
 * 翻倍，复盘队列被同一件事灌满，而「复发次数」这个指标从此全是假的。
 * 所以这里不只测内存，必须真的落盘 → 关掉 → 重开 → 再上报。
 */
class SampleStoreTest {

    private static final long BUCKET = 5 * 60 * 1000L;
    private static final long T0 = 1_700_000_000_000L;

    private static SampleAdmission.Candidate death(long at, String evidence) {
        return new SampleAdmission.Candidate("c1", "death", "被僵尸打死", "zombie",
                "run-1", "g1", "1", "w1", "overworld", at, evidence, "instrumentation");
    }

    private static Path path(Path dir) {
        return dir.resolve("samples.jsonl");
    }

    @Test
    void 同一事故上报三次只有一个样本三条出现_重启后仍然如此(@TempDir Path dir) throws IOException {
        Path f = path(dir);
        SampleStore store = SampleStore.open(f, BUCKET);

        SampleAdmission.Decision d1 = store.admit(death(T0, "ev-1"), T0);
        SampleAdmission.Decision d2 = store.admit(death(T0 + 1_000, "ev-2"), T0 + 1_000);
        SampleAdmission.Decision d3 = store.admit(death(T0 + 2_000, "ev-3"), T0 + 2_000);

        assertEquals(SampleAdmission.Outcome.NEW, d1.outcome());
        assertEquals(SampleAdmission.Outcome.MERGED, d2.outcome());
        assertEquals(SampleAdmission.Outcome.MERGED, d3.outcome());
        assertEquals(1, store.sampleCount(), "同一次事故只能有一个样本");
        assertEquals(3, store.occurrenceCount());

        String sampleId = d1.sample().sampleId();

        // —— 重启：新进程、新对象，只留文件 ——
        SampleStore reopened = SampleStore.open(f, BUCKET);
        assertEquals(1, reopened.sampleCount(), "重启后样本数不能翻倍");
        assertEquals(3, reopened.occurrenceCount(), "重启后 appearance 记录要原样读回");
        assertTrue(reopened.get(sampleId).isPresent(), "id 必须可复算，重启后还能按 id 查到");

        SampleAdmission.Decision d4 = reopened.admit(death(T0 + 3_000, "ev-4"), T0 + 3_000);
        assertEquals(SampleAdmission.Outcome.MERGED, d4.outcome(), "重启后的同一次事故仍要合并");
        assertEquals(sampleId, d4.sample().sampleId(), "合并后的 id 必须还是原来那条");
        assertEquals(1, reopened.sampleCount());
        assertEquals(4, reopened.occurrenceCount());
    }

    @Test
    void 重启后同一条证据不重复计次(@TempDir Path dir) throws IOException {
        Path f = path(dir);
        SampleStore store = SampleStore.open(f, BUCKET);
        store.admit(death(T0, "ev-1"), T0);

        SampleStore reopened = SampleStore.open(f, BUCKET);
        reopened.admit(death(T0, "ev-1"), T0);
        assertEquals(1, reopened.occurrenceCount(), "重启重放同一证据不是复发");
    }

    @Test
    void 同桶不同死因不合并但标记疑似重复(@TempDir Path dir) throws IOException {
        // ⚠ 这条是变异测试 M1 逼出来的：只测 SampleAdmission 时会直接把 null 传进
        //   「强指纹命中」参数，指纹根本没被算过 —— 于是「强指纹忽略 cause」这种致命变异能活下来。
        //   必须走真实存储，让组合出来的指纹参与判定。
        Path f = path(dir);
        SampleStore store = SampleStore.open(f, BUCKET);
        SampleAdmission.Decision a = store.admit(death(T0, "ev-1"), T0);
        SampleAdmission.Candidate lava = new SampleAdmission.Candidate("c1", "death", "被岩浆烧死", "lava",
                "run-1", "g1", "1", "w1", "overworld", T0 + 1_000, "ev-2", "instrumentation");
        SampleAdmission.Decision b = store.admit(lava, T0 + 1_000);

        assertEquals(SampleAdmission.Outcome.POSSIBLE_DUPLICATE, b.outcome(),
                "同桶同目标同世界但死因不同：不许合并");
        org.junit.jupiter.api.Assertions.assertNotEquals(a.sample().sampleId(), b.sample().sampleId());
        assertEquals(2, store.sampleCount());
        assertTrue(b.sample().possibleDuplicate());
        assertEquals(a.sample().sampleId(), b.sample().possibleDuplicateOf());
    }

    @Test
    void 不同目标与环境分别成样(@TempDir Path dir) throws IOException {
        Path f = path(dir);
        SampleStore store = SampleStore.open(f, BUCKET);
        store.admit(death(T0, "ev-1"), T0);
        store.admit(new SampleAdmission.Candidate("c1", "death", "被僵尸打死", "zombie",
                "run-1", "g2", "1", "w1", "overworld", T0, "ev-2", "instrumentation"), T0);
        store.admit(new SampleAdmission.Candidate("c2", "death", "被僵尸打死", "zombie",
                "run-1", "g1", "1", "w1", "overworld", T0, "ev-3", "instrumentation"), T0);
        assertEquals(3, store.sampleCount(), "换目标/换同伴都不许并成一条");
    }

    @Test
    void 落盘一行一个样本且可读回(@TempDir Path dir) throws IOException {
        Path f = path(dir);
        SampleStore store = SampleStore.open(f, BUCKET);
        store.admit(death(T0, "ev-1"), T0);
        store.admit(new SampleAdmission.Candidate("c2", "death", "d", "zombie",
                "run-1", "g1", "1", "w1", "overworld", T0, "ev-2", "instrumentation"), T0);

        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        assertEquals(2, lines.size(), "accumulate 后仍是一行一个样本");
        assertTrue(lines.get(0).startsWith("{"), "必须是 JSONL");
        assertFalse(Files.exists(f.resolveSibling("samples.jsonl.tmp")), "临时文件必须被移走");
    }

    @Test
    void 事件落盘且重启后仍可对账(@TempDir Path dir) throws IOException {
        Path f = path(dir);
        SampleStore store = SampleStore.open(f, BUCKET);
        store.admit(death(T0, "ev-1"), T0);
        store.admit(death(T0 + 1_000, "ev-2"), T0 + 1_000);
        // ⚠ 去重键是 (证据, 时间)：只改证据时间不算重投，必须两者都相同才是同一条证据被重复投递
        store.admit(death(T0 + 1_000, "ev-2"), T0 + 2_000); // 同证据同时间重投 → 去重事件

        List<String> lines = Files.readAllLines(store.eventsFile(), StandardCharsets.UTF_8);
        assertEquals(3, lines.size(), "三次上报 = 三条事件（创建/出现/去重）");
        assertTrue(lines.get(0).contains("SAMPLE_CREATED"));
        assertTrue(lines.get(1).contains("SAMPLE_OCCURRENCE_ADDED"));
        assertTrue(lines.get(2).contains("SAMPLE_DEDUPED"));
        assertEquals(3, store.recentEvents(10).size());

        // 重启后事件文件是 append-only 的历史，不会被样本重写冲掉
        SampleStore reopened = SampleStore.open(f, BUCKET);
        assertEquals(3, Files.readAllLines(reopened.eventsFile(), StandardCharsets.UTF_8).size());
        reopened.admit(death(T0 + 3_000, "ev-4"), T0 + 3_000);
        assertEquals(4, Files.readAllLines(reopened.eventsFile(), StandardCharsets.UTF_8).size());
    }

    @Test
    void 坏行被计数而不是静默吞掉(@TempDir Path dir) throws IOException {
        Path f = path(dir);
        SampleStore store = SampleStore.open(f, BUCKET);
        store.admit(death(T0, "ev-1"), T0);

        Files.writeString(f, "{\"this is\": \"not a sample\"}\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        SampleStore reopened = SampleStore.open(f, BUCKET);
        assertEquals(1, reopened.sampleCount(), "好行照常加载");
        assertEquals(1, reopened.skippedLines(), "坏行必须计数，不许假装文件干净");
        assertFalse(reopened.problems().isEmpty(), "坏行要留下可查的原因");
    }

    @Test
    void 新库没有文件也能开(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("nested").resolve("samples.jsonl");
        SampleStore store = SampleStore.open(f, BUCKET);
        assertEquals(0, store.sampleCount());
        store.admit(death(T0, "ev-1"), T0);
        assertTrue(Files.exists(f), "首次写入要建父目录");
    }

    @Test
    void 时间桶非法直接拒绝(@TempDir Path dir) {
        Path f = path(dir);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> SampleStore.open(f, 0L));
    }
}
