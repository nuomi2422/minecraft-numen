package com.dwinovo.numen.plugins.learner.blocks;

import com.dwinovo.numen.plugins.learner.sample.LearningSample;
import com.dwinovo.numen.plugins.learner.sample.SampleOccurrence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 样本 → 块：窗口、顺序、跨同伴隔离（159 D3）。
 */
class SampleBlockAssemblerTest {

    private static final long T0 = 1_700_000_000_000L;

    private static LearningSample sample(long earliest, long latest) {
        SampleOccurrence occ = new SampleOccurrence("ev-1", earliest, "instrumentation");
        return new LearningSample(
                "s-1", "run-1", "c1", "g1", "1", "death", "被僵尸打死", "zombie",
                "w1", "overworld", 0L, "fp", "wk", com.dwinovo.numen.plugins.learner.sample.SampleState.OPEN,
                "instrumentation", T0, earliest, latest, false, "",
                List.of(), List.of(occ));
    }

    private static String ctx(String companion, long ts, String xml) {
        String iso = java.time.Instant.ofEpochMilli(ts).toString();
        com.google.gson.JsonObject env = new com.google.gson.JsonObject();
        env.addProperty("event_id", "e-" + ts);
        env.addProperty("timestamp", iso);
        env.addProperty("type", "runtime_state");
        com.google.gson.JsonObject data = new com.google.gson.JsonObject();
        data.addProperty("companion_id", companion);
        data.addProperty("turn", 1);
        data.addProperty("context", xml);
        env.add("data", data);
        return env.toString();
    }

    @Test
    void 摘要块排第一() {
        SampleBlockAssembler.Assembled a = SampleBlockAssembler.assemble(
                sample(T0, T0), List.of(ctx("c1", T0, "<x/>")), T0 + 1_000);

        assertEquals(BlockKind.SAMPLE_SUMMARY, a.blocks().get(0).kind(), "目录第一块必须是样本本身");
        assertEquals("summary-s-1", a.blocks().get(0).blockId());
        assertTrue(a.blocks().get(0).body().contains("sampleId=s-1"));
    }

    @Test
    void 时间窗是前60秒后30秒() {
        List<String> lines = List.of(
                ctx("c1", T0 - SampleBlockAssembler.BEFORE_MILLIS - 5_000, "<too-old/>"),
                ctx("c1", T0 - 10_000, "<before/>"),
                ctx("c1", T0 + SampleBlockAssembler.AFTER_MILLIS + 5_000, "<too-new/>"),
                ctx("c1", T0 + 5_000, "<after/>"));

        SampleBlockAssembler.Assembled a = SampleBlockAssembler.assemble(sample(T0, T0), lines, T0);

        List<String> bodies = a.blocks().stream().map(EnvBlock::body).toList();
        assertTrue(bodies.contains("<before/>"));
        assertTrue(bodies.contains("<after/>"));
        assertTrue(bodies.stream().noneMatch(b -> b.contains("too-old")), "窗口外的前后都不许混进来");
        assertTrue(bodies.stream().noneMatch(b -> b.contains("too-new")));
        assertEquals(3, a.blockCount(), "摘要 + 两条上下文");
    }

    @Test
    void 老样本的摘要块标STALE新样本标CURRENT() {
        // ⚠ 变异测试 M40 逼出来的：摘要块的新鲜度原本没有任何用例，
        //   把它写死成 CURRENT 测试照样全绿 —— 而那会让三天前的样本看起来是刚发生的。
        long fresh = SampleBlockAssembler.DEFAULT_STALE / 2;
        long old = SampleBlockAssembler.DEFAULT_STALE * 2;

        EnvBlock current = SampleBlockAssembler.assemble(sample(T0, T0), List.of(), T0 + fresh)
                .blocks().get(0);
        EnvBlock stale = SampleBlockAssembler.assemble(sample(T0, T0), List.of(), T0 + old)
                .blocks().get(0);

        assertEquals(EnvBlock.Freshness.CURRENT, current.freshness());
        assertEquals(EnvBlock.Freshness.STALE, stale.freshness(), "样本发生在很久以前，摘要就不是新鲜的");
    }

    @Test
    void 别的同伴的上下文不进来() {
        List<String> lines = List.of(ctx("c1", T0, "<mine/>"), ctx("c2", T0, "<theirs/>"));
        SampleBlockAssembler.Assembled a = SampleBlockAssembler.assemble(sample(T0, T0), lines, T0);

        List<String> bodies = a.blocks().stream().map(EnvBlock::body).toList();
        assertTrue(bodies.contains("<mine/>"));
        assertTrue(bodies.stream().noneMatch(b -> b.contains("theirs")));
        assertEquals(1, a.skipped());
    }

    @Test
    void 没有上下文时仍给出摘要且说明窗口() {
        SampleBlockAssembler.Assembled a = SampleBlockAssembler.assemble(sample(T0, T0 + 1_000), List.of(), T0);

        assertEquals(1, a.blockCount(), "至少要有样本摘要，否则学习者连这是什么都不知道");
        assertTrue(a.windowNote().contains("companion=c1"));
        assertTrue(a.windowNote().contains("context_blocks=0"));
    }

    @Test
    void 打开会话后没展开的块要补记跳过() {
        ReadLedger l = new ReadLedger();
        BlockReader r = SampleBlockAssembler.openSession(
                sample(T0, T0), List.of(ctx("c1", T0, "<x/>")), l, T0 + 1_000);

        assertEquals(2, r.blockCount());
        r.expand("dc-turn-1", T0 + 2_000);
        r.endSession(T0 + 3_000);
        assertEquals(1, l.countOf(ReadLedger.Action.BLOCK_SKIP), "摘要块没展开，必须补记跳过");
        assertEquals(1, l.countOf(ReadLedger.Action.BLOCK_EXPAND));
    }
}
