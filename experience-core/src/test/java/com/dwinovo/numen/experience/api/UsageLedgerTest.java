package com.dwinovo.numen.experience.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G6a-ledger 判据 L1–L5（离线）：五态入账、重复不双计、先取消后成功不串结论、版本不猜、父子样本。
 */
class UsageLedgerTest {

    private static UsageSample s(int attempt, UsageState state, String artifact) {
        return new UsageSample("exp:e1", attempt, "executor", artifact, "h1", state, 1000L + attempt);
    }

    @Test
    void L1_五态各记各的() {
        UsageStats st = UsageLedger.derive("exp:e1", "h1", List.of(
                s(1, UsageState.SUCCESS, "a1"),
                s(2, UsageState.FAILURE, "a2"),
                s(3, UsageState.CANCELED, "a3"),
                s(4, UsageState.INTERRUPTED, "a4"),
                s(5, UsageState.UNKNOWN, "a5")));
        assertEquals(1, st.success());
        assertEquals(1, st.failure());
        assertEquals(1, st.canceled());
        assertEquals(1, st.interrupted());
        assertEquals(1, st.unknown());
        assertEquals(5, st.total());
        assertEquals(UsageState.UNKNOWN, st.conclusion(), "结论取最后一次尝试（attempt=5）");
        assertEquals(5, st.lastAttempt());
        assertTrue(st.hadCancel());
    }

    @Test
    void L2_重复投递不双计() {
        UsageSample one = s(1, UsageState.SUCCESS, "a1");
        UsageStats st = UsageLedger.derive("exp:e1", "h1", List.of(one, one));
        assertEquals(1, st.total());
        assertEquals(1, st.success());
        assertEquals(1, st.duplicates());
        assertEquals(UsageState.SUCCESS, st.conclusion());
    }

    @Test
    void L3_先取消后成功不串结论_但取消证据还在() {
        UsageStats st = UsageLedger.derive("exp:e1", "h1", List.of(
                s(1, UsageState.CANCELED, "same-artifact"),
                s(2, UsageState.SUCCESS, "same-artifact")));
        assertEquals(UsageState.SUCCESS, st.conclusion(), "结论只看最大 attempt");
        assertEquals(1, st.canceled(), "取消仍要计数，不许被成功覆盖");
        assertTrue(st.hadCancel());
        assertEquals(1, st.success());
    }

    @Test
    void L4_版本不同不合并() {
        UsageSample h1 = new UsageSample("exp:e1", 1, "executor", "a1", "h1", UsageState.SUCCESS, 1L);
        UsageSample h2 = new UsageSample("exp:e1", 1, "executor", "a1", "h2", UsageState.FAILURE, 2L);

        UsageStats only1 = UsageLedger.derive("exp:e1", "h1", List.of(h1, h2));
        assertEquals(1, only1.success());
        assertEquals(0, only1.failure());
        assertEquals(1, only1.excludedByVersion(), "版本对不上的要显式计数，不许静默并进来");

        assertEquals(2, UsageLedger.versions("exp:e1", List.of(h1, h2)).size());
    }

    @Test
    void L5_父子样本结论取最后一次() {
        UsageStats st = UsageLedger.derive("exp:e1", "h1", List.of(
                s(1, UsageState.FAILURE, "parent"),
                s(2, UsageState.SUCCESS, "parent")));
        assertEquals(UsageState.SUCCESS, st.conclusion());
        assertEquals(2, st.total());
        assertEquals(1, st.failure());
        assertFalse(st.hadCancel());
        assertEquals(0.5, st.successRate(), 1e-9);
    }

    @Test
    void 空样本与空ref不炸() {
        assertEquals(UsageStats.EMPTY, UsageLedger.derive("exp:e1", null, null));
        assertEquals(0, UsageLedger.derive("", "h1", List.of()).total());
    }

    @Test
    void L6_真数据36条_五态可解释() throws Exception {
        // 夹具由 events.jsonl 的 task_finished 派生（2026-10-22 当天 36 条），见批次卡 §3.1
        java.nio.file.Path p = java.nio.file.Path.of("src/test/resources/usage-samples-20261022.jsonl");
        java.util.List<UsageSample> samples = new java.util.ArrayList<>();
        for (String line : java.nio.file.Files.readAllLines(p)) {
            if (line.isBlank()) continue;
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
            samples.add(new UsageSample(
                    o.get("entryRef").getAsString(),
                    o.get("attempt").getAsInt(),
                    o.get("phase").getAsString(),
                    o.get("artifactId").getAsString(),
                    o.get("version").getAsString(),
                    UsageState.parse(o.get("state").getAsString()),
                    o.get("atMillis").getAsLong()));
        }
        UsageStats st = UsageLedger.derive("", (String) null, samples);
        assertEquals(36, st.total(), "真数据 36 条一条都不许丢");
        assertEquals(33, st.success());
        assertEquals(2, st.failure());
        assertEquals(1, st.interrupted());
        assertEquals(0, st.canceled());
        assertEquals(0, st.unknown());
        assertEquals(0, st.duplicates(), "跨会话同名 t1/t2 不许被当成重复投递吞掉");
    }
}