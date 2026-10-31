package com.dwinovo.numen.plugins.learner.funnel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 漏斗回放：从两本现有日志重建七阶（164 号方案）。
 *
 * <p>钉死：否定结论必须能记（applicability_fail）、被过滤也算召回、rendered 才等于呈现、
 * 未知原因码不许静默当适用、CANCELED 不是成功。
 */
class FunnelReplayTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final String TS = "2026-10-31T04:00:00Z";
    private static final long TS_MS = java.time.Instant.parse(TS).toEpochMilli();

    private static String snapshot(String role, JsonObject... refs) {
        JsonObject env = new JsonObject();
        env.addProperty("event_id", "ctx-1");
        env.addProperty("timestamp", TS);
        env.addProperty("type", "retrieval_snapshot");
        JsonObject data = new JsonObject();
        data.addProperty("role", role);
        data.addProperty("task_fingerprint", "tf-1");
        JsonArray arr = new JsonArray();
        for (JsonObject r : refs) {
            arr.add(r);
        }
        data.add("refs", arr);
        env.add("data", data);
        return env.toString();
    }

    private static JsonObject ref(String id, boolean rendered, String reason) {
        JsonObject o = new JsonObject();
        o.addProperty("ref", id);
        o.addProperty("rendered", rendered);
        o.addProperty("reason", reason);
        return o;
    }

    private static String expmem(String type, JsonObject data, String eventId) {
        JsonObject env = new JsonObject();
        env.addProperty("event_id", eventId);
        env.addProperty("timestamp", TS);
        env.addProperty("type", type);
        env.add("data", data);
        return env.toString();
    }

    @Test
    void 否定结论必须能记() {
        var r = FunnelReplay.replay(List.of(snapshot("EXECUTOR", ref("exp-1", false, "applicability_fail"))),
                List.of(), NOW);

        assertTrue(r.entries().stream().anyMatch(e -> e.stage() == FunnelStage.APPLICABLE
                        && "applicability_fail".equals(e.reason())),
                "第 0 阶的否定结论是唯一能回答「为什么没被用」的证据");
        assertFalse(r.entries().stream().anyMatch(e -> e.stage() == FunnelStage.PRESENTED));
    }

    @Test
    void 被过滤也算召回() {
        var r = FunnelReplay.replay(List.of(snapshot("EXECUTOR", ref("exp-1", false, "role_not_granted"))),
                List.of(), NOW);
        assertTrue(r.entries().stream().anyMatch(e -> e.stage() == FunnelStage.RECALLED),
                "进了候选集就是召回，被角色挡掉是呈现阶段的事");
        assertFalse(r.entries().stream().anyMatch(e -> e.stage() == FunnelStage.PRESENTED));
    }

    @Test
    void 只有rendered才是呈现() {
        var r = FunnelReplay.replay(List.of(
                        snapshot("EXECUTOR", ref("a", true, ""), ref("b", false, ""))),
                List.of(), NOW);
        List<String> presented = r.entries().stream()
                .filter(e -> e.stage() == FunnelStage.PRESENTED).map(e -> e.entryRef()).toList();
        assertEquals(List.of("a"), presented);
    }

    @Test
    void 角色决定调用方() {
        var learner = FunnelReplay.replay(List.of(snapshot("LEARNER", ref("a", true, ""))), List.of(), NOW);
        assertFalse(learner.entries().isEmpty());
        assertTrue(learner.entries().stream()
                        .allMatch(e -> e.caller() == FunnelLedger.Caller.LEARNER),
                "一条 rendered 的候选会产生 适用/召回/呈现 三条，但必须全归 LEARNER");
        var exec = FunnelReplay.replay(List.of(snapshot("PLANNER", ref("a", true, ""))), List.of(), NOW);
        assertEquals(0, exec.entries().stream()
                .filter(e -> e.caller() == FunnelLedger.Caller.LEARNER).count(),
                "只有 LEARNER 角色才算学习者视图");
    }

    @Test
    void 未知原因码不静默当适用() {
        var r = FunnelReplay.replay(List.of(snapshot("EXECUTOR", ref("a", false, "brand_new_code"))),
                List.of(), NOW);
        assertTrue(r.unknownReasons().contains("brand_new_code"), "原因码漂移必须可见");
    }

    @Test
    void 展开与采用与执行都来自expmem() {
        JsonObject expanded = new JsonObject();
        expanded.addProperty("ref", "exp-1");
        expanded.addProperty("version", "v2");
        JsonObject trial = new JsonObject();
        trial.addProperty("ref", "exp-1");
        trial.addProperty("outcome", "SUCCESS");
        JsonObject usage = new JsonObject();
        usage.addProperty("ref", "exp-1");
        usage.addProperty("state", "SUCCESS");

        var r = FunnelReplay.replay(List.of(),
                List.of(expmem("expanded", expanded, "e1"),
                        expmem("trial_used", trial, "e2"),
                        expmem("usage_recorded", usage, "e3")), NOW);

        assertTrue(r.entries().stream().anyMatch(e -> e.stage() == FunnelStage.EXPANDED));
        assertTrue(r.entries().stream().anyMatch(e -> e.stage() == FunnelStage.ADOPTED
                && e.reason().startsWith("approx_adopted")), "「采用」只能近似，必须标注");
        assertTrue(r.entries().stream().anyMatch(e -> e.stage() == FunnelStage.EXECUTED));
        assertTrue(r.entries().stream().anyMatch(e -> e.stage() == FunnelStage.EFFECT
                && "SUCCESS".equals(e.reason())));
    }

    @Test
    void 取消是效果但不是成功() {
        JsonObject usage = new JsonObject();
        usage.addProperty("ref", "exp-1");
        usage.addProperty("state", "CANCELED");
        var r = FunnelReplay.replay(List.of(), List.of(expmem("usage_recorded", usage, "e1")), NOW);

        var effect = r.entries().stream().filter(e -> e.stage() == FunnelStage.EFFECT).findFirst().orElseThrow();
        assertEquals("CANCELED", effect.reason(), "档位必须留在 reason 里，不许折成成功");
    }

    @Test
    void 没有终态的usage不产生效果阶() {
        JsonObject usage = new JsonObject();
        usage.addProperty("ref", "exp-1");
        usage.addProperty("state", "PRESENTED");
        var r = FunnelReplay.replay(List.of(), List.of(expmem("usage_recorded", usage, "e1")), NOW);
        assertTrue(r.entries().stream().noneMatch(e -> e.stage() == FunnelStage.EFFECT));
    }

    @Test
    void 时间窗外的行被排除() {
        String early = snapshotAt("2026-10-31T03:00:00Z", "EXECUTOR", ref("old", true, ""));
        String late = snapshot("EXECUTOR", ref("new", true, ""));
        long from = java.time.Instant.parse("2026-10-31T03:30:00Z").toEpochMilli();
        long to = java.time.Instant.parse("2026-10-31T04:30:00Z").toEpochMilli();

        var r = FunnelReplay.replay(List.of(early, late), List.of(), from, to, NOW);

        assertTrue(r.entries().stream().noneMatch(e -> e.entryRef().equals("old")), "窗口外的不许混进来");
        assertTrue(r.entries().stream().anyMatch(e -> e.entryRef().equals("new")));
        assertEquals(1, r.outOfWindow(), "窗口外的行单独计数");
        assertEquals(0, r.skipped(), "窗口外不是「日志坏了」，两者不能合并成一个数");
    }

    @Test
    void 两端不限时保留全部() {
        String early = snapshotAt("2026-10-31T03:00:00Z", "EXECUTOR", ref("old", true, ""));
        var r = FunnelReplay.replay(List.of(early), List.of(), 0L, 0L, NOW);
        assertTrue(r.entries().stream().anyMatch(e -> e.entryRef().equals("old")));
    }

    @Test
    void 没有时间戳的记录照样保留但要留痕() {
        com.google.gson.JsonObject env = new com.google.gson.JsonObject();
        env.addProperty("event_id", "ctx-nots");
        env.addProperty("type", "retrieval_snapshot");
        com.google.gson.JsonObject data = new com.google.gson.JsonObject();
        data.addProperty("role", "EXECUTOR");
        JsonArray arr = new JsonArray();
        arr.add(ref("x", true, ""));
        data.add("refs", arr);
        env.add("data", data);

        var r = FunnelReplay.replay(List.of(env.toString()), List.of(),
                NOW - 60_000L, NOW, NOW);

        assertTrue(r.entries().stream().anyMatch(e -> e.entryRef().equals("x")),
                "没有时间戳不等于不在窗口内：宁可保留并标注");
        assertTrue(r.problems().contains("no_timestamp"));
    }

    @Test
    void 两个真实出现过的原因码不再算未知() {
        // 2026-10-31 真实日志回放发现的两个码（164 §8），来自 ai 模块的 Provider。
        var r = FunnelReplay.replay(List.of(snapshot("EXECUTOR",
                        ref("a", false, "not_retrievable"),
                        ref("b", false, "draft_or_suspended"))),
                List.of(), NOW);
        assertTrue(r.unknownReasons().isEmpty(), "词表漏码会让面板一直报「未知」：" + r.unknownReasons());
    }

    private static String snapshotAt(String ts, String role, JsonObject... refs) {
        JsonObject env = new JsonObject();
        env.addProperty("event_id", "ctx-" + ts);
        env.addProperty("timestamp", ts);
        env.addProperty("type", "retrieval_snapshot");
        JsonObject data = new JsonObject();
        data.addProperty("role", role);
        JsonArray arr = new JsonArray();
        for (JsonObject r : refs) {
            arr.add(r);
        }
        data.add("refs", arr);
        env.add("data", data);
        return env.toString();
    }

    @Test
    void 坏行跳过并计数() {
        var r = FunnelReplay.replay(List.of("{oops"), List.of("not json"), NOW);
        assertEquals(2, r.skipped());
        assertTrue(r.entries().isEmpty());
    }

    @Test
    void 没有ref的候选被跳过并留下原因() {
        JsonObject bad = new JsonObject();
        bad.addProperty("rendered", true);
        var r = FunnelReplay.replay(List.of(snapshot("EXECUTOR", bad)), List.of(), NOW);
        assertEquals(1, r.skipped());
        assertTrue(r.problems().contains("ref_without_id"));
    }

    @Test
    void 回放记录能直接进账本且幂等() {
        var r = FunnelReplay.replay(List.of(snapshot("EXECUTOR", ref("a", true, ""))), List.of(), NOW);
        FunnelLedger l = new FunnelLedger();
        int added = 0;
        for (var e : r.entries()) {
            if (l.record(e)) {
                added++;
            }
        }
        assertEquals(r.entries().size(), added);

        // 同一段日志再回放一次：账本幂等键挡住，计数不变
        for (var e : r.entries()) {
            l.record(e);
        }
        assertEquals(added, l.entries().size());
        assertEquals(r.entries().size(), l.duplicates());
    }

    @Test
    void 不适用时停点说的是不适用而不是适用未召回() {
        var r = FunnelReplay.replay(List.of(snapshot("EXECUTOR", ref("a", false, "applicability_fail"))),
                List.of(), NOW);
        FunnelLedger l = new FunnelLedger();
        for (var e : r.entries()) {
            l.record(e);
        }
        var stop = l.whereStopped("a", FunnelLedger.Caller.EXECUTOR);
        assertTrue(stop.verdict().startsWith("不适用"),
                "说成「适用未召回」会让人去查检索，而真正该改的是触发条件");
    }
}
