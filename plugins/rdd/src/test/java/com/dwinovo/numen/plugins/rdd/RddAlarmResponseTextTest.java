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
    // V05 final request carried the old unconditional carry note alongside the fixed alarm note.
    // Read the actual production literal, not a second fixture copy. This is a text/serialization pin,
    // not a Minecraft body or natural sleep test.
    private static String productionCarryNote() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.isRegularFile(root.resolve(
                "plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddCarryHint.java"))) root = root.getParent();
        assertTrue(root != null, "production source must be present");
        String source = java.nio.file.Files.readString(root.resolve(
                "plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddCarryHint.java"));
        for (String line : source.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("sb.append(\"<note>这些是建议，不是指令。")) {
                String literal = trimmed.substring("sb.append(".length(), trimmed.lastIndexOf(");"));
                return com.google.gson.JsonParser.parseString(literal).getAsString();
            }
        }
        throw new AssertionError("actual carry note literal not found");
    }

    @Test
    void carryAndFreshAlarmDoNotContradictAfterProviderSerialization() throws Exception {
        String carry = productionCarryNote();
        String alarm = RddAlarmResponseText.note(List.of(alarm("night", "P1")),
                facts(Map.of("hp", "12", "night", "1", "bed", "0"), 1_000L, "COMPLETE"), 1_500L);
        var provider = new com.dwinovo.numen.agent.provider.OpenAIProvider();
        String request = provider.buildRequestBody("offline-fixture", "system",
                List.of(provider.buildUserMessage("<carry>" + carry + "</carry><alarms>" + alarm + "</alarms>")),
                new com.google.gson.JsonArray()).toString();
        assertFalse(request.contains("不要为了携带而中断当前目标"), "carry must not veto the survival exception");
        assertTrue(request.contains("当前可信生存风险"));
        assertTrue(request.contains("可以暂停或替换当前非生存动作"));
        assertTrue(request.contains("挖三填一"));
    }

    @Test
    void carryStillForbidsOrdinaryDiversionAndUnknownAutomaticPreemption() throws Exception {
        String carry = productionCarryNote();
        assertTrue(carry.contains("一般不要仅为携带建议偏离当前任务"));
        assertTrue(carry.contains("未知或过期提示不作为自动抢占依据"));
        String stale = RddAlarmResponseText.note(List.of(alarm("night", "P1")),
                facts(Map.of("night", "1", "bed", "0"), 1_000L, "UNKNOWN"), 12_000L);
        assertTrue(stale.contains("已过期或覆盖不足"));
        assertFalse(stale.contains("可以暂停或替换"));
    }

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
