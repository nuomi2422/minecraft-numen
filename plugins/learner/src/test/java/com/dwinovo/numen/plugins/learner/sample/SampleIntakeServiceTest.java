package com.dwinovo.numen.plugins.learner.sample;

import com.dwinovo.numen.plugins.learner.core.FeedbackEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三条 intake 口收敛到同一个入口之后的行为（161 §6.5 的收敛验收）。
 *
 * <p>钉死三件事：
 * ① 同一条事实从两个口进来只算一条样本（否则 161 说的「同一事实三种结论」原样保留）；
 * ② 三条口的不同证据能并到同一条样本的三条 occurrence；
 * ③ 写盘失败降级但不抛（学习是旁路，不许拖垮任务链），且计数可见。
 */
class SampleIntakeServiceTest {

    private static final long BUCKET = 5 * 60 * 1000L;
    private static final long T0 = 1_700_000_000_000L;

    private static Map<String, Object> obs(String companion, String cause) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("companionId", companion);
        m.put("deathCauseId", cause);
        return m;
    }

    private static FeedbackEvent event(String evId, long ts, String companion, String cause) {
        return new FeedbackEvent(evId, "gen", String.valueOf(ts), "death", "DEATH",
                Map.of("companion", companion), obs(companion, cause), null);
    }

    private static SampleIntakeAdapter.Envelope env(String source, long now) {
        return new SampleIntakeAdapter.Envelope("run-1", "g1", "1", "w1", "overworld", source, now);
    }

    @Test
    void 同一条事实从手动口和自动口进来只算一条样本(@TempDir Path dir) throws IOException {
        SampleIntakeService svc = SampleIntakeService.open(dir.resolve("samples.jsonl"), BUCKET);
        FeedbackEvent ev = event("ev-1", T0, "c1", "zombie");

        SampleIntakeService.Result a = svc.admitEvent(ev, env(SampleIntakeAdapter.SRC_MANUAL, T0));
        SampleIntakeService.Result b = svc.admitEvent(ev, env(SampleIntakeAdapter.SRC_AUTO, T0));

        assertTrue(a.ok());
        assertTrue(b.ok());
        assertEquals(1, svc.store().sampleCount(), "两个入口不许各自造一条样本");
        assertEquals(1, svc.store().occurrenceCount(), "同证据同时间不是复发");
        assertEquals(1, svc.admitted());
        assertEquals(1, svc.merged());
        assertEquals(a.decision().sample().sampleId(), b.decision().sample().sampleId());
    }

    @Test
    void 三条口的不同证据并到同一条样本的三条出现(@TempDir Path dir) throws IOException {
        SampleIntakeService svc = SampleIntakeService.open(dir.resolve("samples.jsonl"), BUCKET);

        svc.admitEvent(event("ev-1", T0, "c1", "zombie"), env(SampleIntakeAdapter.SRC_MANUAL, T0));
        svc.admitEvent(event("ev-2", T0 + 1_000, "c1", "zombie"), env(SampleIntakeAdapter.SRC_AUTO, T0 + 1_000));
        svc.admitToolFailure("c1", "craft", "missing_ingredient", 3, T0 + 2_000, "ev-3",
                env(SampleIntakeAdapter.SRC_TOOL_FAILURE, T0 + 2_000));

        assertEquals(2, svc.store().sampleCount(), "死亡与工具失败是两类样本，不是一条");
        assertEquals(3, svc.store().occurrenceCount(), "两条死亡 + 一条工具失败");
    }

    @Test
    void 跳过的候选不算故障也不算入库(@TempDir Path dir) throws IOException {
        SampleIntakeService svc = SampleIntakeService.open(dir.resolve("samples.jsonl"), BUCKET);
        FeedbackEvent noCompanion = new FeedbackEvent("ev-1", "g", String.valueOf(T0), "death", "DEATH",
                Map.of(), Map.of(), null);

        SampleIntakeService.Result r = svc.admitEvent(noCompanion, env(SampleIntakeAdapter.SRC_MANUAL, T0));
        assertTrue(r.skipped(), "跳过既不是成功也不是故障");
        assertFalse(r.failed());
        assertEquals(1, svc.skipped());
        assertEquals(0, svc.store().sampleCount());
    }

    @Test
    void 写盘失败降级不抛且计数可见(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("samples.jsonl");
        SampleIntakeService svc = SampleIntakeService.open(f, BUCKET);
        // 把临时落盘路径占成目录 → persist 的 writeString 必失败
        Files.createDirectories(dir.resolve("samples.jsonl.tmp"));

        SampleIntakeService.Result r = svc.admitEvent(event("ev-1", T0, "c1", "zombie"),
                env(SampleIntakeAdapter.SRC_MANUAL, T0));

        assertTrue(r.failed(), "故障必须显式，不许静默没学到");
        assertFalse(r.error().isBlank());
        assertEquals(1, svc.failed());
        assertEquals(0, svc.admitted());
    }

    @Test
    void 统计读数齐全(@TempDir Path dir) throws IOException {
        SampleIntakeService svc = SampleIntakeService.open(dir.resolve("samples.jsonl"), BUCKET);
        svc.admitEvent(event("ev-1", T0, "c1", "zombie"), env(SampleIntakeAdapter.SRC_MANUAL, T0));
        svc.admitEvent(event("ev-2", T0 + 1_000, "c1", "zombie"), env(SampleIntakeAdapter.SRC_AUTO, T0 + 1_000));

        Map<String, Object> stats = svc.stats();
        assertEquals(1, stats.get("admitted"));
        assertEquals(1, stats.get("merged"));
        assertEquals(0, stats.get("failed"));
        assertEquals(1, stats.get("samples"));
        assertEquals(2, stats.get("occurrences"));
    }
}
