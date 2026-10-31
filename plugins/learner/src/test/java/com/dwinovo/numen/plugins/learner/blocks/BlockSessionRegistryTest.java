package com.dwinovo.numen.plugins.learner.blocks;

import com.dwinovo.numen.plugins.learner.sample.SampleAdmission;
import com.dwinovo.numen.plugins.learner.sample.SampleIntakeAdapter;
import com.dwinovo.numen.plugins.learner.sample.SampleIntakeService;
import com.dwinovo.numen.plugins.learner.sample.SampleState;
import com.dwinovo.numen.plugins.learner.sample.SampleStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话注册表：归属隔离、生命周期、跳过补记（159 D4）。
 */
class BlockSessionRegistryTest {

    private static final long BUCKET = 5 * 60 * 1000L;
    private static final long T0 = 1_700_000_000_000L;

    private static SampleStore storeWithDeath(Path dir, String companion) throws IOException {
        SampleStore store = SampleStore.open(dir.resolve("samples.jsonl"), BUCKET);
        store.admit(new SampleAdmission.Candidate(companion, "death", "被僵尸打死", "zombie",
                "run-1", "g1", "1", "w1", "overworld", T0, "ev-1", "instrumentation"), T0);
        return store;
    }

    private static String ctx(String companion, String xml) {
        com.google.gson.JsonObject env = new com.google.gson.JsonObject();
        env.addProperty("event_id", "e-1");
        env.addProperty("timestamp", java.time.Instant.ofEpochMilli(T0).toString());
        env.addProperty("type", "runtime_state");
        com.google.gson.JsonObject data = new com.google.gson.JsonObject();
        data.addProperty("companion_id", companion);
        data.addProperty("turn", 1);
        data.addProperty("context", xml);
        env.add("data", data);
        return env.toString();
    }

    @Test
    void 不许读别人的样本(@TempDir Path dir) throws IOException {
        SampleStore store = storeWithDeath(dir, "c1");
        BlockSessionRegistry reg = new BlockSessionRegistry();
        String sampleId = store.samples().get(0).sampleId();

        BlockSessionRegistry.Outcome r = reg.open("c2", sampleId, store, List.of(), T0);

        assertFalse(r.ok(), "B 的死亡位置/背包/决策上下文不许流到 A");
        assertTrue(r.error().contains("不属于"), "错误里连摘要都不该带");
    }

    @Test
    void 未知样本与未开会话都明确失败(@TempDir Path dir) throws IOException {
        SampleStore store = storeWithDeath(dir, "c1");
        BlockSessionRegistry reg = new BlockSessionRegistry();

        assertFalse(reg.open("c1", "s-nope", store, List.of(), T0).ok());
        assertFalse(reg.open("c1", store.samples().get(0).sampleId(), null, List.of(), T0).ok());
        assertFalse(reg.list("c1", T0).ok(), "没开会话不许假装有一份目录");
        assertFalse(reg.expand("c1", "b", T0).ok());
        assertFalse(reg.close("c1", T0).ok());
    }

    @Test
    void 打开后能列目录展开正文再关闭(@TempDir Path dir) throws IOException {
        SampleStore store = storeWithDeath(dir, "c1");
        String sampleId = store.samples().get(0).sampleId();
        BlockSessionRegistry reg = new BlockSessionRegistry();

        assertTrue(reg.open("c1", sampleId, store, List.of(ctx("c1", "<runtime_state>hp=3</runtime_state>")), T0).ok());

        BlockSessionRegistry.Outcome listed = reg.list("c1", T0 + 1);
        assertTrue(listed.ok());
        assertEquals(2, listed.data().get("count"), "摘要 + 决策上下文");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) listed.data().get("blocks");
        assertEquals("SAMPLE_SUMMARY", rows.get(0).get("kind"));
        assertFalse(rows.get(0).containsKey("body"), "目录不许带正文");

        BlockSessionRegistry.Outcome expanded = reg.expand("c1", "dc-turn-1", T0 + 2);
        assertTrue(expanded.ok());
        assertTrue(String.valueOf(expanded.data().get("body")).contains("hp=3"));
        assertEquals("HIGH", expanded.data().get("confidence"));

        BlockSessionRegistry.Outcome closed = reg.close("c1", T0 + 3);
        assertTrue(closed.ok());
        assertEquals(1, closed.data().get("skipped"), "摘要块没展开，关闭时要补记跳过");
        assertFalse(reg.active("c1"), "关闭后会话必须消失");
    }

    @Test
    void 关闭后再调用一律失败(@TempDir Path dir) throws IOException {
        SampleStore store = storeWithDeath(dir, "c1");
        String sampleId = store.samples().get(0).sampleId();
        BlockSessionRegistry reg = new BlockSessionRegistry();
        reg.open("c1", sampleId, store, List.of(), T0);
        reg.close("c1", T0 + 1);

        assertFalse(reg.list("c1", T0 + 2).ok());
        assertFalse(reg.expand("c1", "summary-" + sampleId, T0 + 2).ok());
    }

    @Test
    void 翻页返回目标或明确eof(@TempDir Path dir) throws IOException {
        SampleStore store = storeWithDeath(dir, "c1");
        String sampleId = store.samples().get(0).sampleId();
        BlockSessionRegistry reg = new BlockSessionRegistry();
        reg.open("c1", sampleId, store, List.of(ctx("c1", "<x/>")), T0);

        BlockSessionRegistry.Outcome next = reg.step("c1", "summary-" + sampleId, true, T0 + 1);
        assertTrue(next.ok());
        assertEquals("dc-turn-1", next.data().get("target"));
        assertEquals(false, next.data().get("eof"));

        BlockSessionRegistry.Outcome eof = reg.step("c1", "dc-turn-1", true, T0 + 2);
        assertEquals(true, eof.data().get("eof"), "到底了要显式说 eof，不许回绕");
    }

    @Test
    void 展开不存在的块是失败不是空正文(@TempDir Path dir) throws IOException {
        SampleStore store = storeWithDeath(dir, "c1");
        String sampleId = store.samples().get(0).sampleId();
        BlockSessionRegistry reg = new BlockSessionRegistry();
        reg.open("c1", sampleId, store, List.of(), T0);

        BlockSessionRegistry.Outcome r = reg.expand("c1", "nope", T0 + 1);
        assertFalse(r.ok());
        assertTrue(r.error().contains("unknown block_id"));
        assertTrue(r.data().isEmpty(), "失败时不许塞半截数据");
    }

    @Test
    void 缺同伴直接拒绝(@TempDir Path dir) throws IOException {
        SampleStore store = storeWithDeath(dir, "c1");
        BlockSessionRegistry reg = new BlockSessionRegistry();
        assertFalse(reg.open("", store.samples().get(0).sampleId(), store, List.of(), T0).ok());
    }
}
