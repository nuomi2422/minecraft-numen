package com.dwinovo.numen.rdd.policy;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G1c 软预留账本契约（75号 §15.4 / IC05 / D11）。
 *
 * <p>钉四件事：并发预留不超库存（全有或全无）、release 真释放、过期只释放承诺不记丢失、
 * 可用量口径（自己不扣自己的）。
 */
class ReservationLedgerTest {

    // ── S48 · 同一资源两个预留：不超 held；release 后可再申请 ──

    @Test
    void s48_twoReservationsNeverExceedHeld() {
        ReservationLedger ledger = new ReservationLedger();
        Map<String, Integer> held = Map.of("minecraft:torch", 50);

        var first = ledger.reserve("goal-a", "minecraft:torch", 30, 0, 0, held);
        assertTrue(first.ok(), "30 ≤ 50 应成功：" + first.reason());
        assertEquals(30, first.granted());

        var second = ledger.reserve("goal-b", "minecraft:torch", 25, 0, 0, held);
        assertFalse(second.ok(), "30+25 > 50 必须 DENIED（全有或全无，不给部分授予）");
        assertEquals(20, second.available(), "拒绝时要如实报出可用余量");

        assertEquals(Map.of("minecraft:torch", 30), ledger.reservedAmounts(),
                "被拒绝的申请不得留下条目");

        assertTrue(ledger.release(first.id()), "release 真释放");
        var retry = ledger.reserve("goal-b", "minecraft:torch", 25, 0, 0, held);
        assertTrue(retry.ok(), "释放后 25 ≤ 50 应成功：" + retry.reason());
        assertEquals(25, ledger.reservedAmounts().get("minecraft:torch"));
    }

    @Test
    void s48b_sameDemandIsIdempotentAndCanGrow() {
        ReservationLedger ledger = new ReservationLedger();
        Map<String, Integer> held = Map.of("minecraft:bread", 10);

        var a = ledger.reserve("goal-a", "minecraft:bread", 4, 0, 0, held);
        var b = ledger.reserve("goal-a", "minecraft:bread", 4, 0, 0, held);
        assertTrue(b.ok());
        assertEquals(a.id(), b.id(), "同股同量重复申请必须返回原身份（每 30s tick 不会越算越穷）");
        assertEquals(1, ledger.reservations().size());

        var grown = ledger.reserve("goal-a", "minecraft:bread", 7, 0, 0, held);
        assertTrue(grown.ok(), "自己占的 4 不算他人占用，涨到 7 应允许");
        assertEquals(7, ledger.reservedBy("goal-a", "minecraft:bread"));
    }

    // ── S49 · 过期只释放承诺，不记物理丢失 ──

    @Test
    void s49_expiryReleasesThePromiseOnly() {
        ReservationLedger ledger = new ReservationLedger();
        Map<String, Integer> held = Map.of("minecraft:iron_ingot", 12);

        var grant = ledger.reserve("repair", "minecraft:iron_ingot", 8, 1, 1_000L, held);
        assertTrue(grant.ok());

        assertEquals(0, ledger.releaseExpired(999L), "未到期不得释放");
        assertEquals(1, ledger.releaseExpired(1_000L), "到点释放");
        assertTrue(ledger.reservedAmounts().isEmpty(), "过期后不再计入有效预留");

        // ★ 关键：账本里没有任何「丢失」语义 —— 过期只是承诺作废，
        //   资产事实（held=12）由资产层持有，账本无权改。
        var after = ledger.reserve("goal-b", "minecraft:iron_ingot", 12, 0, 0, held);
        assertTrue(after.ok(), "过期释放后资源重新可用（不是丢了 8 个）");
    }

    // ── S50 · 可用量口径：自己不扣自己的 ──

    @Test
    void s50_availableExcludesOthersOnly() {
        ReservationLedger ledger = new ReservationLedger();
        Map<String, Integer> held = Map.of("minecraft:torch", 40, "minecraft:bread", 6);

        ledger.reserve("goal-a", "minecraft:torch", 25, 0, 0, held);

        Map<String, Integer> forA = ledger.available(held, "goal-a");
        assertEquals(40, forA.get("minecraft:torch"), "自己的预留不扣自己（否则当前目标把自己算穷）");
        assertEquals(6, forA.get("minecraft:bread"), "未被预留的 key 原样返回");

        Map<String, Integer> forB = ledger.available(held, "goal-b");
        assertEquals(15, forB.get("minecraft:torch"), "他人视角 = 40 − 25");
        Map<String, Integer> anon = ledger.available(held, null);
        assertEquals(15, anon.get("minecraft:torch"), "无身份视角按「他人」算（更保守）");
    }

    @Test
    void s50b_releaseAllOfAndRetainOnly() {
        ReservationLedger ledger = new ReservationLedger();
        Map<String, Integer> held = Map.of("minecraft:stick", 20, "minecraft:torch", 20);
        ledger.reserve("goal-a", "minecraft:stick", 5, 0, 0, held);
        ledger.reserve("goal-a", "minecraft:torch", 5, 0, 0, held);
        ledger.reserve("goal-b", "minecraft:stick", 5, 0, 0, held);

        assertEquals(2, ledger.releaseAllOf("goal-a"));
        assertEquals(Map.of("minecraft:stick", 5), ledger.reservedAmounts(),
                "只释放 goal-a 的两条，goal-b 的保留");

        assertEquals(1, ledger.retainOnly("goal-c"), "换阶段：非 keep 的全部释放");
        assertTrue(ledger.reservedAmounts().isEmpty());
    }

    @Test
    void zeroAndNegativeRequestsAreSafe() {
        ReservationLedger ledger = new ReservationLedger();
        Map<String, Integer> held = Map.of("minecraft:dirt", 3);
        assertTrue(ledger.reserve("g", "minecraft:dirt", 0, 0, 0, held).ok(),
                "0 件申请合法（幂等清空）");
        assertTrue(ledger.reservations().isEmpty());
        assertFalse(ledger.reserve("g", "minecraft:iron_ingot", 1, 0, 0, held).ok(),
                "held 缺 key = 0 件，申请 1 件必须拒绝（不猜）");
    }
}
