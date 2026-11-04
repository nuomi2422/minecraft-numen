package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.core.AdvancementVerification.Result;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 037/T3 第一轮 · EV-01：冻结成就四态的状态转换表，并用反例说明旧布尔为什么会混淆。
 *
 * <p>这些用例不需要 Minecraft：状态表是纯常量，注入的是 {@link AdvancementVerification} 的四态。
 */
class AdvancementPolicyTest {

    @Test
    void everyStateHasExactlyOneRow() {
        Set<Result> covered = EnumSet.noneOf(Result.class);
        for (Result r : Result.values()) {
            AdvancementPolicy.Rule rule = AdvancementPolicy.of(r);
            assertEquals(r, rule.result());
            covered.add(rule.result());
            assertTrue(rule.next() != null, r + " 没有下一步");
        }
        assertEquals(EnumSet.allOf(Result.class), covered, "四态必须全覆盖");
    }

    @Test
    void matchedIsTheOnlyCompletionEvidence() {
        for (Result r : Result.values()) {
            boolean expected = r == Result.MATCHED;
            assertEquals(expected, AdvancementPolicy.of(r).completionEvidence(), r + " 的完成证据口径不对");
        }
        assertEquals(AdvancementPolicy.Next.CONTINUE, AdvancementPolicy.of(Result.MATCHED).next());
    }

    @Test
    void notMatchedKeepsTheExistingRetryBehaviour() {
        AdvancementPolicy.Rule rule = AdvancementPolicy.of(Result.NOT_MATCHED);
        assertFalse(rule.completionEvidence());
        assertTrue(rule.countsAsActionFailure(), "真的没做完，仍走现有失败/重试策略");
        assertEquals(AdvancementPolicy.Next.RETRY, rule.next());
    }

    @Test
    void invalidConditionIsRepairedNotBlamedOnTheExecutor() {
        AdvancementPolicy.Rule rule = AdvancementPolicy.of(Result.INVALID);
        assertFalse(rule.completionEvidence(), "条件错绝不能算完成");
        assertFalse(rule.countsAsActionFailure(), "条件写错不是执行者的失败");
        assertEquals(AdvancementPolicy.Next.REPAIR_CONDITION, rule.next());
    }

    @Test
    void notVerifiableIsDiagnosticNotFailure() {
        AdvancementPolicy.Rule rule = AdvancementPolicy.of(Result.NOT_VERIFIABLE);
        assertFalse(rule.completionEvidence());
        assertFalse(rule.countsAsActionFailure(), "观测不可用不能记成动作失败");
        assertEquals(AdvancementPolicy.Next.DIAGNOSE_WAIT, rule.next(), "也不许永久沉默");
    }

    @Test
    void legacyBooleanCollapsesThreeDistinctStates() {
        // 反例：旧布尔只能回答 true/false，于是 INVALID、NOT_VERIFIABLE、NOT_MATCHED 全变成 false。
        assertTrue(AdvancementPolicy.legacyBoolean(Result.MATCHED));
        assertFalse(AdvancementPolicy.legacyBoolean(Result.NOT_MATCHED));
        assertFalse(AdvancementPolicy.legacyBoolean(Result.INVALID));
        assertFalse(AdvancementPolicy.legacyBoolean(Result.NOT_VERIFIABLE));

        // 三种"false"的处置必须不同——这正是布尔给不出的信息。
        assertEquals(AdvancementPolicy.Next.RETRY, AdvancementPolicy.of(Result.NOT_MATCHED).next());
        assertEquals(AdvancementPolicy.Next.REPAIR_CONDITION, AdvancementPolicy.of(Result.INVALID).next());
        assertEquals(AdvancementPolicy.Next.DIAGNOSE_WAIT, AdvancementPolicy.of(Result.NOT_VERIFIABLE).next());

        long failuresCounted = java.util.Arrays.stream(Result.values())
                .filter(r -> AdvancementPolicy.of(r).countsAsActionFailure()).count();
        assertEquals(1L, failuresCounted, "只有 NOT_MATCHED 该记动作失败");
    }

    @Test
    void nullIsRejectedInsteadOfGuessed() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> AdvancementPolicy.of(null));
    }

    @Test
    void predicatesMatchTheFrozenTable() {
        // 执行侧只用这三个谓词，避免各处自己 switch 四态又长出第二套规则。
        assertTrue(AdvancementPolicy.completesStep(Result.MATCHED));
        assertFalse(AdvancementPolicy.completesStep(Result.NOT_MATCHED));
        assertFalse(AdvancementPolicy.completesStep(Result.INVALID));
        assertFalse(AdvancementPolicy.completesStep(Result.NOT_VERIFIABLE));

        assertTrue(AdvancementPolicy.shouldRequestRepair(Result.INVALID));
        assertFalse(AdvancementPolicy.shouldRequestRepair(Result.NOT_VERIFIABLE));
        assertFalse(AdvancementPolicy.shouldRequestRepair(Result.NOT_MATCHED));
        assertFalse(AdvancementPolicy.shouldRequestRepair(Result.MATCHED));

        assertTrue(AdvancementPolicy.shouldDiagnoseOnly(Result.NOT_VERIFIABLE));
        assertFalse(AdvancementPolicy.shouldDiagnoseOnly(Result.INVALID));
        assertFalse(AdvancementPolicy.shouldDiagnoseOnly(Result.MATCHED));
    }

    @Test
    void onlyOneStateCanEverCompleteOrRepairOrDiagnose() {
        long completes = java.util.Arrays.stream(Result.values())
                .filter(AdvancementPolicy::completesStep).count();
        long repairs = java.util.Arrays.stream(Result.values())
                .filter(AdvancementPolicy::shouldRequestRepair).count();
        long diagnoses = java.util.Arrays.stream(Result.values())
                .filter(AdvancementPolicy::shouldDiagnoseOnly).count();
        assertEquals(1L, completes);
        assertEquals(1L, repairs);
        assertEquals(1L, diagnoses);
    }
}
