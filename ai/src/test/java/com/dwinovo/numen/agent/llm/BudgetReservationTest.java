package com.dwinovo.numen.agent.llm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G4a 契约（IC09）：并发预留不超限、扣账按实际用量、失败释放不记账、漏释放按 TTL 失效。
 */
class BudgetReservationTest {

    @TempDir
    Path dir;

    private long now = 1_700_000_000_000L;

    @AfterEach
    void tearDown() {
        BudgetPolicy.resetForTest();
    }

    private void install(String json) throws Exception {
        Files.writeString(dir.resolve("budget-policy.json"), json, StandardCharsets.UTF_8);
        BudgetPolicy.resetForTest();
        BudgetPolicy.setClockForTest(() -> now);
        BudgetPolicy.install(dir);
    }

    private static String policy(String mode, long tokensPerWindow, long callsPerWindow) {
        return """
                {"version": 7, "mode": "%s", "windowMinutes": 60, "adviceCooldownMinutes": 5,
                 "roles": {"execution": {"tokensPerWindow": %d, "callsPerWindow": %d}}}
                """.formatted(mode, tokensPerWindow, callsPerWindow);
    }

    // ── S77：并发预留不超限、release 真释放 ──

    @Test
    void s77_concurrentReservationsCannotOverCommit() throws Exception {
        install(policy("enforce", 100, 0));

        var first = BudgetPolicy.reserve("execution", 60, 0);
        assertNotNull(first, "60 ≤ 100 应成功");
        assertEquals("execution", first.role());

        // 第二个并发调用：窗口已用 0，但活跃预留 60 → 60+60 > 100，必须拒绝
        assertNull(BudgetPolicy.reserve("execution", 60, 0),
                "并发两路都看到「窗口已用 0」时会双双放行 —— 预留必须挡住（IC09）");

        assertTrue(BudgetPolicy.release(first.id()), "release 真释放");
        assertEquals(0, BudgetPolicy.activeReservations("execution"));
        assertNotNull(BudgetPolicy.reserve("execution", 60, 0), "释放后额度回来");
    }

    @Test
    void s77b_callLimitIsReservedToo() throws Exception {
        install(policy("enforce", 0, 1));   // 窗口内只允许 1 次调用
        var r = BudgetPolicy.reserve("execution", 10, 0);
        assertNotNull(r);
        assertNull(BudgetPolicy.reserve("execution", 10, 0), "调用次数也按预留占位");
        assertTrue(BudgetPolicy.release(r.id()));
        assertNotNull(BudgetPolicy.reserve("execution", 10, 0));
    }

    @Test
    void unlimitedRoleNeverReserves() throws Exception {
        install(policy("enforce", 100, 0));
        assertNull(BudgetPolicy.reserve("stage_a", 10, 0), "planning 角色未设限额 → 不占额");
    }

    // ── S78：扣账用实际值 ──

    @Test
    void s78_chargeUsesActualTokensNotEstimate() throws Exception {
        install(policy("enforce", 1000, 0));
        var r = BudgetPolicy.reserve("execution", 800, 0);
        assertNotNull(r);
        assertEquals(800, BudgetPolicy.windowUsageWithReservations("execution").tokens());

        assertTrue(BudgetPolicy.charge(r.id(), 30), "扣账成功");
        assertEquals(30, BudgetPolicy.windowUsage("execution").tokens(),
                "记的是实际 30，不是预估 800");
        assertEquals(30, BudgetPolicy.windowUsageWithReservations("execution").tokens());
        assertEquals(0, BudgetPolicy.activeReservations("execution"));
    }

    @Test
    void chargeOnExpiredReservationDoesNotInventACall() throws Exception {
        install(policy("enforce", 1000, 0));
        var r = BudgetPolicy.reserve("execution", 100, 0);
        assertNotNull(r);
        now += BudgetPolicy.DEFAULT_RESERVATION_TTL_MS + 1;   // 过期
        assertFalse(BudgetPolicy.charge(r.id(), 50), "过期预留不可扣账");
        assertEquals(0, BudgetPolicy.windowUsage("execution").calls(), "不许凭空多一笔调用");
    }

    // ── S79：漏释放按 TTL 失效 ──

    @Test
    void s79_reservationExpiresSoALeakDoesNotHoldQuotaForever() throws Exception {
        install(policy("enforce", 100, 0));
        assertNotNull(BudgetPolicy.reserve("execution", 90, 0));
        assertNull(BudgetPolicy.reserve("execution", 90, 0), "同一时刻额度被占");

        now += BudgetPolicy.DEFAULT_RESERVATION_TTL_MS + 1;
        assertEquals(0, BudgetPolicy.windowUsageWithReservations("execution").tokens(),
                "过期预留不再计入（漏 release 不会永久占额）");
        assertNotNull(BudgetPolicy.reserve("execution", 90, 0), "额度回来了");
    }

    // ── S80：失败释放不记账 ──

    @Test
    void s80_releaseRecordsNothing() throws Exception {
        install(policy("enforce", 100, 0));
        var r = BudgetPolicy.reserve("execution", 40, 0);
        assertNotNull(r);
        assertTrue(BudgetPolicy.release(r.id()));
        assertEquals(0, BudgetPolicy.windowUsage("execution").tokens());
        assertEquals(0, BudgetPolicy.windowUsage("execution").calls(), "失败释放不产生调用记录");
    }

    // ── 与既有 checkBlock 的关系：预留不改变 enforce 求值语义 ──

    @Test
    void reservationsDoNotLeakIntoRecordedUsage() throws Exception {
        install(policy("enforce", 1000, 0));
        var r = BudgetPolicy.reserve("execution", 500, 0);
        assertNotNull(r);
        assertEquals(0, BudgetPolicy.windowUsage("execution").tokens(), "windowUsage 只算真实调用");
        assertEquals(500, BudgetPolicy.windowUsageWithReservations("execution").tokens());
    }
}
