package com.dwinovo.numen.plugins.learner.sample;

import com.dwinovo.numen.plugins.learner.core.FeedbackEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行时门面：关着不写、开着能写、坏了不抛。
 *
 * <p>这三条是它存在的理由。少任何一条，接进三个工具时都会出事：
 * 关着还写 = 悄悄污染存档；坏了就抛 = 学习层拖垮任务链；错了不记 = 静默失明。
 */
class SampleIntakeRuntimeTest {

    private static final long BUCKET = 5 * 60 * 1000L;
    private static final long T0 = 1_700_000_000_000L;

    @AfterEach
    void clean() {
        SampleIntakeRuntime.resetForTest();
    }

    private static FeedbackEvent event(String evId, long ts) {
        Map<String, Object> obs = new LinkedHashMap<>();
        obs.put("companionId", "c1");
        obs.put("deathCauseId", "zombie");
        return new FeedbackEvent(evId, "gen", String.valueOf(ts), "death", "DEATH",
                Map.of("companion", "c1"), obs, null);
    }

    private static void enable(Path dir) throws IOException {
        Files.writeString(dir.resolve(SampleIntakeGate.FILE_NAME), "{\"enabled\":true}");
        SampleIntakeRuntime.resetForTest();
    }

    @Test
    void 默认关着时一行都不写(@TempDir Path dir) throws IOException {
        SampleIntakeRuntime rt = SampleIntakeRuntime.shared(dir, BUCKET);
        Optional<SampleIntakeService.Result> r = rt.recordEvent(event("ev-1", T0), SampleIntakeAdapter.SRC_MANUAL, T0);

        assertTrue(r.isEmpty(), "关着就是关着，返回 empty 表示什么都没发生");
        assertFalse(Files.exists(dir.resolve("samples.jsonl")), "关着不许创建存储");
        assertEquals("no_file", rt.gate().reason());
    }

    @Test
    void 开了之后能入库(@TempDir Path dir) throws IOException {
        enable(dir);
        SampleIntakeRuntime rt = SampleIntakeRuntime.shared(dir, BUCKET);
        Optional<SampleIntakeService.Result> r = rt.recordEvent(event("ev-1", T0), SampleIntakeAdapter.SRC_MANUAL, T0);

        assertTrue(r.isPresent());
        assertTrue(r.get().ok());
        assertTrue(Files.exists(dir.resolve("samples.jsonl")));
        assertEquals(0, rt.errors());
    }

    @Test
    void 存储打开失败时降级不抛(@TempDir Path dir) throws IOException {
        enable(dir);
        // 占位成目录 → SampleStore.open 读不动
        Files.createDirectories(dir.resolve("samples.jsonl"));

        SampleIntakeRuntime rt = SampleIntakeRuntime.shared(dir, BUCKET);
        Optional<SampleIntakeService.Result> r = rt.recordEvent(event("ev-1", T0), SampleIntakeAdapter.SRC_MANUAL, T0);

        assertTrue(r.isEmpty(), "降级路径同样返回 empty，但错误计数会变");
        assertEquals(1, rt.errors(), "吞异常不等于假装没发生");
        assertFalse(rt.lastError().isBlank());
        assertEquals(1, rt.stats().get("errors"));
    }

    @Test
    void 工具失败口也能入库(@TempDir Path dir) throws IOException {
        enable(dir);
        SampleIntakeRuntime rt = SampleIntakeRuntime.shared(dir, BUCKET);
        rt.recordEvent(event("ev-1", T0), SampleIntakeAdapter.SRC_AUTO, T0);
        rt.recordToolFailure("c1", "craft", "missing_ingredient", 3, T0 + 1_000, "ev-2", T0 + 1_000);

        Map<String, Object> stats = rt.stats();
        assertTrue((Integer) stats.get("samples") >= 1);
        assertEquals(0, stats.get("errors"));
    }

    @Test
    void 开关读数带原因(@TempDir Path dir) throws IOException {
        SampleIntakeRuntime rt = SampleIntakeRuntime.shared(dir, BUCKET);
        assertEquals("no_file", rt.stats().get("gateReason"));
        enable(dir);
        SampleIntakeRuntime rt2 = SampleIntakeRuntime.shared(dir, BUCKET);
        assertEquals("enabled_file", rt2.stats().get("gateReason"));
        assertTrue((Boolean) rt2.stats().get("enabled"));
    }
}
