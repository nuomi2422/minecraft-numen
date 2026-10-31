package com.dwinovo.numen.plugins.learner.blocks;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `context.jsonl` → 环境块（159 D 的数据源适配）。
 *
 * <p>钉死：只取本同伴；时间戳缺失写 UNKNOWN 而不是用当前时刻顶替；坏行跳过并计数；
 * 老记录标 STALE；同一 turn 重试不产生重复 id；不臆造 RETRIEVAL_TRACE。
 */
class ContextBlockSourceTest {

    private static final long T0 = Instant.parse("2026-10-31T04:00:00Z").toEpochMilli();
    private static final String TS0 = "2026-10-31T04:00:00Z";
    private static final long STALE = 10 * 60 * 1000L;

    private static String runtimeState(String companion, int turn, String ts, String xml) {
        JsonObject env = new JsonObject();
        env.addProperty("schema_version", 1);
        env.addProperty("event_id", "e-" + ts + "-" + turn);
        env.addProperty("timestamp", ts);
        env.addProperty("category", "context");
        env.addProperty("type", "runtime_state");
        JsonObject data = new JsonObject();
        data.addProperty("companion_id", companion);
        data.addProperty("turn", turn);
        data.addProperty("origin", "tick");
        data.addProperty("attempt", 1);
        data.addProperty("context", xml);
        env.add("data", data);
        return env.toString();
    }

    private static String llmRequest(String companion, String ts, String requestId) {
        JsonObject env = new JsonObject();
        env.addProperty("schema_version", 1);
        env.addProperty("event_id", "e-" + ts);
        env.addProperty("timestamp", ts);
        env.addProperty("category", "context");
        env.addProperty("type", "llm_request");
        JsonObject data = new JsonObject();
        data.addProperty("companionId", companion);
        data.addProperty("requestId", requestId);
        data.addProperty("phase", "execution");
        data.addProperty("model", "m-1");
        data.add("request", JsonParser.parseString("{\"messages\":[]}"));
        env.add("data", data);
        return env.toString();
    }

    @Test
    void runtime_state生成决策上下文块() {
        List<String> lines = List.of(runtimeState("c1", 3, TS0, "<runtime_state>hp=3</runtime_state>"));
        ContextBlockSource.Result r = ContextBlockSource.extract(lines, ContextBlockSource.Selection.all(), T0, STALE);

        assertEquals(1, r.blocks().size());
        assertEquals(0, r.skipped());
        EnvBlock b = r.blocks().get(0);
        assertEquals(BlockKind.DECISION_CONTEXT, b.kind());
        assertEquals("dc-turn-3", b.blockId());
        assertEquals("<runtime_state>hp=3</runtime_state>", b.body());
        assertEquals(T0, b.capturedAt());
        assertEquals(EnvBlock.Coverage.COMPLETE, b.coverage());
        assertEquals(EnvBlock.Freshness.CURRENT, b.freshness());
        assertEquals(EnvBlock.Confidence.HIGH, b.confidence(), "逐字落盘的原始内容应是最高档");
    }

    @Test
    void llm_request生成请求块且可信度低一档() {
        ContextBlockSource.Result r = ContextBlockSource.extract(
                List.of(llmRequest("c1", TS0, "req-9")), ContextBlockSource.Selection.all(), T0, STALE);
        EnvBlock b = r.blocks().get(0);
        assertEquals("req-req-9", b.blockId());
        assertEquals(EnvBlock.Confidence.MEDIUM, b.confidence(), "wire body 含传输包装，不能与逐字 XML 同档");
        assertTrue(b.body().contains("messages"));
    }

    @Test
    void 别的同伴不许混进来() {
        List<String> lines = List.of(
                runtimeState("c1", 1, TS0, "<a/>"),
                runtimeState("c2", 1, TS0, "<b/>"));
        ContextBlockSource.Result r = ContextBlockSource.extract(lines,
                new ContextBlockSource.Selection("c1", 0, 0), T0, STALE);

        assertEquals(1, r.blocks().size());
        assertTrue(r.blocks().get(0).body().contains("<a/>"), "跨同伴串味是最难复现的一类错");
        assertEquals(1, r.skipped());
    }

    @Test
    void 时间窗外的记录被跳过() {
        List<String> lines = List.of(
                runtimeState("c1", 1, "2026-10-31T03:00:00Z", "<old/>"),
                runtimeState("c1", 2, TS0, "<new/>"));
        ContextBlockSource.Result r = ContextBlockSource.extract(lines,
                new ContextBlockSource.Selection("", T0 - 60_000L, 0), T0, STALE);

        assertEquals(1, r.blocks().size());
        assertTrue(r.blocks().get(0).body().contains("<new/>"));
    }

    @Test
    void 坏行跳过并计数_好行照常解析() {
        List<String> lines = List.of("{oops", "\"not an object\"", runtimeState("c1", 1, TS0, "<a/>"));
        ContextBlockSource.Result r = ContextBlockSource.extract(lines, ContextBlockSource.Selection.all(), T0, STALE);

        assertEquals(1, r.blocks().size(), "一行坏不许拖垮整条链路");
        assertEquals(2, r.skipped());
        assertFalse(r.problems().isEmpty());
    }

    @Test
    void 缺时间戳写UNKNOWN而不是用当前时刻顶替() {
        List<String> lines = List.of(runtimeState("c1", 1, "", "<a/>"));
        EnvBlock b = ContextBlockSource.extract(lines, ContextBlockSource.Selection.all(), T0, STALE).blocks().get(0);

        assertEquals(0L, b.capturedAt());
        assertEquals(EnvBlock.Freshness.UNKNOWN, b.freshness(), "没时间戳就没有新鲜度，不许写成 CURRENT");
        assertTrue(b.renderLine().contains("capturedAt=UNKNOWN"));
    }

    @Test
    void 老记录标STALE() {
        long muchLater = T0 + STALE * 5;
        EnvBlock b = ContextBlockSource.extract(List.of(runtimeState("c1", 1, TS0, "<a/>")),
                ContextBlockSource.Selection.all(), muchLater, STALE).blocks().get(0);
        assertEquals(EnvBlock.Freshness.STALE, b.freshness());
    }

    @Test
    void 同一turn重试不产生重复id() {
        List<String> lines = List.of(
                runtimeState("c1", 3, TS0, "<first/>"),
                runtimeState("c1", 3, "2026-10-31T04:00:05Z", "<retry/>"));
        ContextBlockSource.Result r = ContextBlockSource.extract(lines, ContextBlockSource.Selection.all(), T0, STALE);

        assertEquals(2, r.blocks().size(), "重试是两次真实的请求，不能丢");
        assertEquals("dc-turn-3", r.blocks().get(0).blockId());
        assertEquals("dc-turn-3#2", r.blocks().get(1).blockId(), "BlockReader 拒绝重复 id");
    }

    @Test
    void 未知类型跳过并留原因() {
        JsonObject env = new JsonObject();
        env.addProperty("type", "something_else");
        env.add("data", new JsonObject());
        ContextBlockSource.Result r = ContextBlockSource.extract(List.of(env.toString()),
                ContextBlockSource.Selection.all(), T0, STALE);

        assertTrue(r.blocks().isEmpty());
        assertEquals(1, r.skipped());
        assertTrue(r.problems().get(0).startsWith("unknown_type"));
    }

    @Test
    void 不臆造检索轨迹块() {
        // 161：能可靠拿到 kept/dropped 的地方是 expmem 的 budget_decision，不是这里。
        ContextBlockSource.Result r = ContextBlockSource.extract(
                List.of(runtimeState("c1", 1, TS0, "<a/>"), llmRequest("c1", TS0, "r1")),
                ContextBlockSource.Selection.all(), T0, STALE);

        assertTrue(r.blocks().stream().noneMatch(b -> b.kind() == BlockKind.RETRIEVAL_TRACE),
                "猜一类块比缺一类块更查不出来");
    }

    @Test
    void 空输入安全() {
        assertTrue(ContextBlockSource.extract(null, null, T0, STALE).blocks().isEmpty());
        assertTrue(ContextBlockSource.extract(List.of(), ContextBlockSource.Selection.all(), T0, STALE).blocks().isEmpty());
    }
}
