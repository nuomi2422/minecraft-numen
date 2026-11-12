package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.carrier.CarrierAlarms;
import com.dwinovo.numen.api.carrier.CarrierChain;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live 2026-11-12 regression: a fresh night/critical alarm must let execution
 * re-evaluate survival even when the sleep side task has not acquired the body.
 * The tests use the existing fact contract and do not simulate a world or sleep.
 */
class RddAlarmResponseTextTest {
    private static CarrierChain.Facts facts(Map<String, String> values, long at, String coverage) {
        return CarrierChain.factsOf(values, values.toString().toLowerCase(), at, "body", coverage);
    }

    private static CarrierAlarms.Hit alarm(String rule, String priority) {
        return new CarrierAlarms.Hit(rule, 1, priority, "sampled facts", "advice");
    }

    @Test
    void freshNightWithoutBedOffersShelterAndAllowsTaskPause() {
        String note = RddAlarmResponseText.note(List.of(alarm("night", "P1")),
                facts(Map.of("hp", "12", "night", "1", "bed", "0"), 1_000L, "COMPLETE"), 1_500L);
        assertTrue(note.contains("可以暂停或替换当前非生存动作"));
        assertTrue(note.contains("挖三填一"));
        assertTrue(note.contains("附近敌怪正攻击或脚下不安全时先撤"));
        assertFalse(note.contains("不要为了提醒中断当前目标"));
    }

    @Test
    void criticalHpWithBedStillPutsSurvivalBeforeUnsafeSleep() {
        String note = RddAlarmResponseText.note(List.of(alarm("night", "P1"), alarm("low_hp", "P0")),
                facts(Map.of("hp", "2", "night", "1", "bed", "1", "hostile", "1"), 1_000L, "COMPLETE"), 1_500L);
        assertTrue(note.contains("先脱离直接威胁"));
        assertTrue(note.contains("安全且床可用时再睡"));
        assertFalse(note.contains("挖三填一"));
    }

    @Test
    void staleOrUnknownCannotClaimCurrentSurvivalPriority() {
        var hit = List.of(alarm("night", "P1"));
        String stale = RddAlarmResponseText.note(hit,
                facts(Map.of("hp", "12", "night", "1", "bed", "0"), 1_000L, "COMPLETE"), 12_000L);
        String unknown = RddAlarmResponseText.note(hit,
                facts(Map.of("night", "1", "bed", "0"), 1_000L, "UNKNOWN"), 1_500L);
        assertTrue(stale.contains("已过期或覆盖不足"));
        assertTrue(unknown.contains("已过期或覆盖不足"));
        assertFalse(stale.contains("可以暂停或替换"));
        assertFalse(unknown.contains("挖三填一"));
    }

    @Test
    void ordinaryReminderDoesNotDivertCurrentWork() {
        String note = RddAlarmResponseText.note(List.of(alarm("hungry_soft", "P2")),
                facts(Map.of("hp", "18", "food", "16"), 1_000L, "COMPLETE"), 1_500L);
        assertTrue(note.contains("按当前任务和现场事实取舍"));
        assertFalse(note.contains("可以暂停或替换"));
    }
}
