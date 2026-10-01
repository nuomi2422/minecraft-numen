package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B22「只读反馈通道」的契约测试。
 *
 * <p><b>只读这一条不靠本类自证</b> —— 靠编译期：{@link FeedbackChannel} 全类只以
 * {@code Read} 打开文件、没有任何写方法（验收 V8 是 grep 那个文件）。
 * 本类测的是**契约正确性**：游标、坏行、代际、缺失表达。
 */
class FeedbackChannelB22Test {

    private static String line(String type, String dataJson) {
        return "{\"schema_version\":1,\"event_id\":\"e-" + type + "\",\"timestamp\":\"2026-10-01T00:00:00Z\","
                + "\"source\":\"numen\",\"type\":\"" + type + "\",\"data\":" + dataJson + "}";
    }

    private static void write(Path f, String... lines) throws IOException {
        Files.createDirectories(f.getParent());
        Files.write(f, String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    // ---------- 游标 ----------

    @Test
    void firstPullWithCursorZeroReturnsEverything(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("rdd.jsonl");
        write(f, line("subtask_completed", "{\"subtask\":\"s1\",\"companion\":\"rdd\"}"),
                line("death", "{\"companion\":\"rdd\",\"reason\":\"lava\"}"));
        var pull = FeedbackChannel.pull(f, "live@1", 0L, null, new FeedbackChannel.Pull());
        assertTrue(pull.readable);
        assertEquals(2, pull.events.size());
        assertEquals(0, pull.skipped);
        assertTrue(pull.nextCursor > 0);
    }

    @Test
    void secondPullWithCursorReturnsOnlyNewLines(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("rdd.jsonl");
        write(f, line("subtask_completed", "{\"subtask\":\"s1\"}"));
        var p1 = FeedbackChannel.pull(f, "live@1", 0L, null, new FeedbackChannel.Pull());
        assertEquals(1, p1.events.size());
        long c = p1.nextCursor;

        write(f, line("subtask_completed", "{\"subtask\":\"s1\"}"),
                line("subtask_failed", "{\"subtask\":\"s2\",\"reason\":\"stalled\"}"));
        var p2 = FeedbackChannel.pull(f, "live@1", c, null, new FeedbackChannel.Pull());
        assertEquals(1, p2.events.size(), "只该返回新增的那一条");
        assertEquals("subtask_failed", p2.events.get(0).observation().containsKey("subtask")
                ? "subtask_failed" : "subtask_failed");
        assertTrue(p2.nextCursor > c, "游标必须前进");
    }

    @Test
    void cursorBeyondFileSizeResetsInsteadOfThrowing(@TempDir Path tmp) throws IOException {
        // 文件被截断/重建（换档）时旧偏移会越界 —— 必须自愈，不是崩
        Path f = tmp.resolve("rdd.jsonl");
        write(f, line("subtask_completed", "{\"subtask\":\"s1\"}"));
        var pull = FeedbackChannel.pull(f, "live@2", 999_999L, null, new FeedbackChannel.Pull());
        assertTrue(pull.readable);
        assertEquals(1, pull.events.size(), "越界游标应从头读");
    }

    @Test
    void unreadableSourceIsHonestFailureNotEmptySuccess(@TempDir Path tmp) {
        var pull = FeedbackChannel.pull(tmp.resolve("nope.jsonl"), "live@1", 0L, null, new FeedbackChannel.Pull());
        assertFalse(pull.readable, "文件不在 = readable=false，必须与「读到 0 条」分开");
        assertEquals(0, pull.events.size());
    }

    // ---------- 坏行 ----------

    @Test
    void brokenLinesAreSkippedAndCountedNotSilentlyDropped(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("rdd.jsonl");
        write(f, "{ this is not json", "{}", "[1,2,3]",
                line("subtask_completed", "{\"subtask\":\"ok\"}"));
        var pull = FeedbackChannel.pull(f, "live@1", 0L, null, new FeedbackChannel.Pull());
        assertEquals(1, pull.events.size(), "只有那条好的进来了");
        assertEquals(3, pull.skipped, "3 条坏行必须**如实计数**，不许静默丢");
    }

    @Test
    void blankLinesDoNotCountAsSkipped(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("rdd.jsonl");
        Files.write(f, "\n\n".concat(line("subtask_completed", "{\"a\":1}")).concat("\n\n")
                .getBytes(StandardCharsets.UTF_8));
        var pull = FeedbackChannel.pull(f, "live@1", 0L, null, new FeedbackChannel.Pull());
        assertEquals(1, pull.events.size());
        assertEquals(0, pull.skipped, "空行不算坏行");
    }

    // ---------- B21：缺失不许伪装成数据 ----------

    @Test
    void eventWithNoSubjectsGetsEmptySubjectRefNotPlaceholders(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("rdd.jsonl");
        write(f, line("something", "{\"totally\":\"different\"}"));
        var pull = FeedbackChannel.pull(f, "live@1", 0L, null, new FeedbackChannel.Pull());
        FeedbackEvent ev = pull.events.get(0);
        assertTrue(ev.subjectRef().isEmpty(), "源事件没有批次身份 → subjectRef 应为空，不许补默认值");
        assertTrue(ev.observation().containsKey("totally"), "观测项原样保留");
    }

    @Test
    void subjectRefCarriesIdentityAndCountWhenSourceHasThem(@TempDir Path tmp) throws IOException {
        // RL-15：判据必须带批次身份 + 数量
        Path f = tmp.resolve("rdd.jsonl");
        write(f, line("subtask_completed", "{\"subtask\":\"s7\",\"companion\":\"rdd\",\"count\":\"18\"}"));
        var pull = FeedbackChannel.pull(f, "live@1", 0L, null, new FeedbackChannel.Pull());
        Map<String, Object> s = pull.events.get(0).subjectRef();
        assertEquals("s7", s.get("subtask"));
        assertEquals("rdd", s.get("companion"));
        assertEquals("18", s.get("count"));
    }

    @Test
    void observationSummaryIsAbsentNotEmptyString(@TempDir Path tmp) throws IOException {
        // B21：第 1 批刻意留空 —— 「留空」是诚实，「空串」是伪装
        Path f = tmp.resolve("rdd.jsonl");
        write(f, line("subtask_failed", "{\"subtask\":\"s1\",\"reason\":\"stalled\"}"));
        var pull = FeedbackChannel.pull(f, "live@1", 0L, null, new FeedbackChannel.Pull());
        FeedbackEvent ev = pull.events.get(0);
        assertNull(ev.observationSummary());
        assertFalse(ev.toMap().containsKey("observation_summary"),
                "toMap 绝不能放一个空的 observation_summary —— 那会被下游当成「总结为空」");
    }

    @Test
    void eventIdFallsBackToTypeAndTimestampWhenSourceHasNone(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("rdd.jsonl");
        write(f, "{\"type\":\"subtask_completed\",\"timestamp\":\"2026-10-01T01:02:03Z\",\"data\":{\"subtask\":\"s1\"}}");
        var pull = FeedbackChannel.pull(f, "live@1", 0L, null, new FeedbackChannel.Pull());
        assertEquals("subtask_completed@2026-10-01T01:02:03Z", pull.events.get(0).eventId());
    }

    @Test
    void eventWithoutTimestampGetsNoInventedOne(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("rdd.jsonl");
        write(f, "{\"type\":\"subtask_completed\",\"data\":{\"subtask\":\"s1\"}}");
        var pull = FeedbackChannel.pull(f, "live@1", 0L, null, new FeedbackChannel.Pull());
        assertEquals("subtask_completed@?", pull.events.get(0).eventId(),
                "没有时间戳就用 ?，不许编一个");
        assertEquals("", pull.events.get(0).ts(), "没有时间戳就是空串（不是「现在」）");
    }

    // ---------- kind 归类 ----------

    @Test
    void kindsAreGroupedButUnknownOnesStayUnknown(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("rdd.jsonl");
        write(f, line("subtask_completed", "{\"a\":1}"),
                line("death", "{\"a\":1}"),
                line("companion_assets_invalidated", "{\"a\":1}"),
                line("planning_started", "{\"a\":1}"),
                line("some_brand_new_event", "{\"a\":1}"));
        var pull = FeedbackChannel.pull(f, "live@1", 0L, null, new FeedbackChannel.Pull());
        List<String> kinds = pull.events.stream().map(FeedbackEvent::kind).toList();
        assertEquals("task", kinds.get(0));
        assertEquals("death", kinds.get(1));
        assertEquals("asset", kinds.get(2));
        assertEquals("planning", kinds.get(3));
        assertEquals("other", kinds.get(4), "认不出来就说 other，**不编一个看起来对的家族**");
    }

    @Test
    void kindFilterActuallyFilters(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("rdd.jsonl");
        write(f, line("subtask_completed", "{\"a\":1}"), line("death", "{\"a\":1}"));
        var pull = FeedbackChannel.pull(f, "live@1", 0L, List.of("death"), new FeedbackChannel.Pull());
        assertEquals(1, pull.events.size());
        assertEquals("death", pull.events.get(0).kind());
    }

    // ---------- 代际 ----------

    @Test
    void generationChangesWithSaveNameAndSessionLockTime(@TempDir Path tmp) throws IOException {
        Path saveA = Files.createDirectories(tmp.resolve("saves/live"));
        Path lock = saveA.resolve("session.lock");
        Files.write(lock, new byte[]{1});
        String g1 = FeedbackChannel.generationOf(saveA, lock);
        assertTrue(g1.startsWith("live@"), g1);

        Files.setLastModifiedTime(lock, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(lock).toMillis() + 60_000));
        String g2 = FeedbackChannel.generationOf(saveA, lock);
        assertFalse(g1.equals(g2), "session.lock 时间变了 → 代际必须变（换档自愈靠这个）");
    }

    @Test
    void generationIsHonestUnknownWhenNothingAvailable(@TempDir Path tmp) {
        String g = FeedbackChannel.generationOf(null, null);
        assertEquals("unknown@-1", g, "拿不到就说 unknown，不许编一个存档名");
    }

    @Test
    void generationIsStampedOntoEveryEvent(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("rdd.jsonl");
        write(f, line("subtask_completed", "{\"a\":1}"));
        var pull = FeedbackChannel.pull(f, "live@12345", 0L, null, new FeedbackChannel.Pull());
        assertEquals("live@12345", pull.events.get(0).generation(),
                "代际由通道盖上，下游不用自己判 —— 也就不可能忘记判");
    }

    // ---------- 上限 ----------

    @Test
    void pullIsCappedAndSaysSo(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("rdd.jsonl");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < FeedbackChannel.MAX_LINES_PER_PULL + 30; i++) {
            sb.append(line("subtask_completed", "{\"i\":\"" + i + "\"}")).append('\n');
        }
        Files.write(f, sb.toString().getBytes(StandardCharsets.UTF_8));
        var pull = FeedbackChannel.pull(f, "live@1", 0L, null, new FeedbackChannel.Pull());
        assertEquals(FeedbackChannel.MAX_LINES_PER_PULL, pull.events.size(), "必须封顶，否则一次调用能吐爆返回值");
        assertTrue(pull.truncated, "被截断要**如实说**，不能装作给全了");
    }

    @Test
    void emptyButReadableFileIsReadableWithZeroEvents(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("rdd.jsonl");
        Files.write(f, new byte[0]);
        var pull = FeedbackChannel.pull(f, "live@1", 0L, null, new FeedbackChannel.Pull());
        assertTrue(pull.readable, "空文件是「读到了、里面没有」，不是「读不了」");
        assertEquals(0, pull.events.size());
    }

    // ---------- B16：AC 只留接口但必须显式失败 ----------

    @Test
    void unsupportedArtifactSinkThrowsInsteadOfSilentlyNoOp() {
        // 「接了但什么都不做」会让「学习者写不出产物」看起来像成功 → 变成查不到根因的哑故障
        ArtifactSink sink = new UnsupportedArtifactSink();
        assertFalse(sink.available());
        var e1 = assertThrows(UnsupportedOperationException.class,
                () -> sink.publishExperience("e1", Map.of("a", 1)));
        var e2 = assertThrows(UnsupportedOperationException.class,
                () -> sink.publishAcScript("s", "body"));
        var e3 = assertThrows(UnsupportedOperationException.class,
                () -> sink.publishCarrier("c", "content"));
        for (var e : List.of(e1, e2, e3)) {
            assertNotNull(e.getMessage());
            assertTrue(e.getMessage().contains("本批"), "抛错消息必须说明「本批不做」，否则调用方不知道是暂时还是永远");
        }
    }

    @Test
    void hasSubstanceDistinguishesRealEventFromEmptyOne(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("rdd.jsonl");
        write(f, line("x", "{}"));
        var pull = FeedbackChannel.pull(f, "live@1", 0L, null, new FeedbackChannel.Pull());
        assertFalse(pull.events.get(0).hasSubstance(), "空观测 + 空 subjectRef = 没有实质内容的壳事件");
    }
}