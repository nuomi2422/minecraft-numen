package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.PrimaryGoalStatus;
import com.dwinovo.numen.plugins.rdd.RddReplanTransition.SurvivalApplyOutcome;
import java.util.ArrayList;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.TaskChain;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 044 B2 · 生存供食意图：重复抢占抑制与生命周期（机械接缝红基线，2026-11-13）。
 *
 * <p>现场根据（V04 原窗）：三次 accepted 生存抢占分别应用 rev29/30/31，首步都是食物；
 * 约 60s 后同一低饥饿又抢占，停掉上一次的生存身体、换计划并花预算，food 条件从未完成。
 *
 * <p>本组先冻红（机械接缝：类型/参数/生命周期 API 就位，读意图的守卫未接）：
 * <ul>
 *   <li>同一 chainInstanceId+primary 的生存意图 APPLIED 且 planRevision 未变时，同型请求必须被拒
 *       （DUPLICATE_SURVIVAL_PENDING），不转换、不记账、不回调；</li>
 *   <li>旧回调 token 不得清掉新意图；</li>
 *   <li>空回/替换异常/server 缺失（markAborted）后允许重试；外部换计划(planRevision 变)、安全观测、
 *       换链实例后允许新抢占；UNKNOWN 不自动释放（本组不伪造）。</li>
 * </ul>
 *
 * <p>c53 原 XML 为9项/3断言红/6过；R2补守卫并保原断言。新增用真实TaskChain、
 * 原CallbackGuard及生产共用服务端/应用判定消费者验证边界；MC壳事件接线与供食效果未覆盖。
 */
class RddSurvivalIntentTest {

    private static final int LIMIT = 3;
    private static final String REASON = "survival SEEK_FOOD_FIRST hunger=9 hp=20 free=16";

    @Test
    void duplicateSurvivalWhileAppliedIntentPendingIsSuppressed() {
        Fixture f = new Fixture(UUID.randomUUID(), "goal-intent-dup");
        Object first = new Object();
        assertTrue(f.request(first).accepted());
        f.applyPlan(first);
        assertEquals(1, f.used());
        assertEquals(1, f.callbacks());

        int revision = f.chain.planRevision();
        long version = f.chain.stateVersion();
        RddReplanTransition.Result second = f.request(new Object());
        assertEquals(revision, f.chain.planRevision(), "重复请求不换计划代次");
        assertEquals(version, f.chain.stateVersion(), "重复请求不转换链状态");
        assertFalse(second.accepted(),
                "同链同 primary 的生存供食意图仍未完成时，同型不得再换计划/停体");
        assertEquals(RddReplanTransition.Outcome.DUPLICATE_SURVIVAL_PENDING, second.outcome());
        assertEquals(1, f.used(), "重复请求不得再花 failure 预算");
        assertEquals(1, f.callbacks(), "重复请求不得再触发接受回调（不停体）");
    }

    @Test
    void emptyOrFailedApplyAllowsRetry() {
        Fixture f = new Fixture(UUID.randomUUID(), "goal-intent-abort");
        Object first = new Object();
        assertTrue(f.request(first).accepted());
        // 原九项中的token释放单元控制；宿主失败判定由后面的共用消费者测试覆盖。
        RddReplanTransition.markAborted(f.id, first);
        f.applyPlan(new Object());
        assertTrue(f.request(new Object()).accepted(), "空回退后同一危险应允许重新抢占");
    }

    @Test
    void staleCallbackMustNotClearNewIntent() {
        Fixture f = new Fixture(UUID.randomUUID(), "goal-intent-stale");
        Object oldToken = new Object();
        assertTrue(f.request(oldToken).accepted());
        RddReplanTransition.markAborted(f.id, oldToken);
        f.applyPlan(new Object());

        Object newToken = new Object();
        assertTrue(f.request(newToken).accepted(), "释放后新意图可以开始");
        f.applyPlan(newToken);
        RddReplanTransition.markAborted(f.id, oldToken); // 迟到的旧回调

        RddReplanTransition.Result third = f.request(new Object());
        assertFalse(third.accepted(), "旧回调不得清掉新意图，因此第三个同型请求仍应被拒");
        assertEquals(RddReplanTransition.Outcome.DUPLICATE_SURVIVAL_PENDING, third.outcome());
    }

    @Test
    void externalPlanChangeReleasesIntent() {
        Fixture f = new Fixture(UUID.randomUUID(), "goal-intent-rev");
        Object first = new Object();
        assertTrue(f.request(first).accepted());
        f.applyPlan(first);
        f.replaceByOtherSource();
        assertTrue(f.request(new Object()).accepted(), "计划被别的来源换掉后，新的生存危险允许重新抢占");
    }

    @Test
    void safetyObservationReleasesIntent() {
        Fixture f = new Fixture(UUID.randomUUID(), "goal-intent-safe");
        Object first = new Object();
        assertTrue(f.request(first).accepted());
        f.applyPlan(first);
        RddReplanTransition.SURVIVAL_PREEMPT_AT.put(f.id, 1_000L);
        RddReplanTransition.observeSafe(f.id);
        assertFalse(RddReplanTransition.SURVIVAL_PREEMPT_AT.containsKey(f.id), "安全后立即再危险不等旧60s");
        assertTrue(f.request(new Object()).accepted(), "安全观测释放后，再危险允许新抢占");
    }

    @Test
    void chainInstanceChangeReleasesIntent() {
        UUID companion = UUID.randomUUID();
        Fixture oldChain = new Fixture(companion, "goal-intent-old");
        Object first = new Object();
        assertTrue(oldChain.request(first).accepted());
        oldChain.applyPlan(first);

        Fixture newChain = new Fixture(companion, "goal-intent-new");
        assertTrue(newChain.request(new Object()).accepted(), "换链实例后旧意图过期，允许新抢占");
    }

    /** PENDING 未回（无 markApplied/markAborted）：UNKNOWN 不自动释放，同型仍应被抑制。 */
    @Test
    void unknownPendingIntentKeepsSuppression() {
        Fixture f = new Fixture(UUID.randomUUID(), "goal-intent-unknown");
        Object first = new Object();
        assertTrue(f.request(first).accepted());
        // 没有应用回调、也没有明确失败回调：宿主此后回落到原计划并重新 ACTIVE（UNKNOWN）。
        f.chain.resumeFromReplanning();
        assertTrue(f.chain.activateCurrent(Map.of()));
        RddReplanTransition.Result retry = f.request(new Object());
        assertFalse(retry.accepted(), "UNKNOWN 不自动释放：PENDING 未回时同型仍应被抑制");
        assertEquals(RddReplanTransition.Outcome.DUPLICATE_SURVIVAL_PENDING, retry.outcome());
        assertEquals(1, f.used(), "被抑制的请求不得再花预算");
    }

    /** 同一 low hunger 下 SEEK→EAT 是新的生存动作，不应被旧 SEEK 意图压住。 */
    @Test
    void decisionEscalationAllowsNewPreemption() {
        Fixture f = new Fixture(UUID.randomUUID(), "goal-intent-eat");
        Object first = new Object();
        assertTrue(f.request(RddSurvivalGate.Decision.SEEK_FOOD_FIRST, first).accepted());
        f.applyPlan(first);
        assertTrue(f.request(RddSurvivalGate.Decision.EAT_FIRST, new Object()).accepted(),
                "SEEK 意图不压 EAT：背包已有食物时允许新的进食抢占");
    }

    /** primary 换了（不是同一次生存意图的作用域）→ 允许新抢占。 */
    @Test
    void primaryChangeReleasesIntent() {
        UUID companion = UUID.randomUUID();
        Fixture oldPrimary = new Fixture(companion, "goal-intent-primary", "primary-0");
        Object first = new Object();
        assertTrue(oldPrimary.request(first).accepted());
        oldPrimary.applyPlan(first);

        String chainId = oldPrimary.chain.chainInstanceId();
        int rev = oldPrimary.chain.planRevision();
        oldPrimary.chain.settlePrimaryByFact("primary-0", "controlled fixture verified prior stage", true);
        oldPrimary.chain.expandCurrentPrimary(List.of(
                Subtask.hardCoded("next-s0", "后续阶段", Map.of("group", "food"))));
        oldPrimary.chain.startCurrent();
        assertEquals(chainId, oldPrimary.chain.chainInstanceId(), "必须同链，不与换链混测");
        assertEquals(rev, oldPrimary.chain.planRevision(), "必须同rev，只改变primary");
        assertEquals("primary-next", oldPrimary.chain.currentPrimary().id());
        assertTrue(oldPrimary.request(new Object()).accepted(), "换 primary 后旧意图过期，允许新抢占");
    }


    @Test
    void pendingRequestBeforeCallbackIsDuplicateAndFree() {
        Fixture f = new Fixture(UUID.randomUUID(), "pending-in-flight");
        assertTrue(f.request(new Object()).accepted());
        long state = f.chain.stateVersion();
        assertEquals(RddReplanTransition.Outcome.DUPLICATE_SURVIVAL_PENDING, f.request(new Object()).outcome());
        assertEquals(state, f.chain.stateVersion());
        assertEquals(1, f.used());
        assertEquals(1, f.callbacks());
    }

    @Test
    void staleApplyAndAbortCannotMutateNewPendingIntent() {
        Fixture f = new Fixture(UUID.randomUUID(), "stale-apply");
        Object old = new Object();
        assertTrue(f.request(old).accepted());
        RddReplanTransition.abortPlan(f.id, old, f.chain);
        assertTrue(f.chain.activateCurrent(Map.of()));
        Object current = new Object();
        assertTrue(f.request(current).accepted());
        RddReplanTransition.markApplied(f.id, old, f.chain.planRevision() + 99);
        RddReplanTransition.abortPlan(f.id, old, f.chain);
        assertEquals(PrimaryGoalStatus.REPLANNING, f.chain.primaryStatus(), "旧失败不能回落新在飞计划");
        assertEquals(RddReplanTransition.Outcome.DUPLICATE_SURVIVAL_PENDING, f.request(new Object()).outcome());
        assertEquals(2, f.used());
        assertEquals(2, f.callbacks());
    }

    @Test
    void missingPlanningServerUsesSharedPredicateAndResumes() {
        Fixture f = new Fixture(UUID.randomUUID(), "server-missing");
        Object token = new Object();
        assertTrue(f.request(token).accepted());
        RddReplanTransition.SURVIVAL_PREEMPT_AT.put(f.id, 1_000L);
        assertTrue(RddReplanTransition.requirePlanningServer(f.id, token, f.chain, new Object()));
        assertEquals(PrimaryGoalStatus.REPLANNING, f.chain.primaryStatus());
        assertEquals(RddReplanTransition.Outcome.DUPLICATE_SURVIVAL_PENDING, f.request(new Object()).outcome(),
                "服务端存在且尚无回调仍是UNKNOWN，不释放");
        assertFalse(RddReplanTransition.requirePlanningServer(f.id, token, f.chain, null));
        assertFalse(RddReplanTransition.hasSurvivalIntent(f.id));
        assertFalse(RddReplanTransition.SURVIVAL_PREEMPT_AT.containsKey(f.id));
        assertEquals(PrimaryGoalStatus.PENDING, f.chain.primaryStatus());
        assertTrue(f.chain.activateCurrent(Map.of()));
        assertTrue(f.request(new Object()).accepted());
    }

    @Test
    void emptyCallbackAfterSafetyStillResumesInsteadOfParkingReplanning() {
        Fixture f = new Fixture(UUID.randomUUID(), "safe-before-empty");
        Object token = new Object();
        assertTrue(f.request(token).accepted());
        RddReplanTransition.observeSafe(f.id);
        assertEquals(SurvivalApplyOutcome.EMPTY_FALLBACK,
                RddReplanTransition.applySurvivalPlan(f.id, token, f.chain, null,
                () -> { throw new AssertionError("null回调不得执行替换"); },
                ignored -> fail("null回调不得保存/发布")));
        assertEquals(PrimaryGoalStatus.PENDING, f.chain.primaryStatus());
        assertTrue(f.chain.activateCurrent(Map.of()));
        assertTrue(f.request(new Object()).accepted());
    }

    @Test
    void realReplacementRejectionReleasesForRetry() {
        Fixture f = new Fixture(UUID.randomUUID(), "replacement-rejected");
        Object token = new Object();
        assertTrue(f.request(token).accepted());
        assertThrows(IllegalArgumentException.class, () -> RddReplanTransition.applySurvivalPlan(
                f.id, token, f.chain, List.of("nonempty-spec"),
                () -> {
                    f.chain.nextPlanRevision();
                    f.chain.replaceCurrentSubtasks(List.of()); // 真TaskChain拒绝，异常由生产消费者释放
                    return null;
                }, ignored -> fail("替换拒绝不得保存/发布")));
        assertFalse(RddReplanTransition.hasSurvivalIntent(f.id));
        assertTrue(f.chain.activateCurrent(Map.of()));
        assertTrue(f.request(new Object()).accepted());
    }

    @Test
    void deathOfAppliedIntentKeepsChainAndBudgetButAllowsNewBody() {
        Fixture f = new Fixture(UUID.randomUUID(), "death-applied");
        Object token = new Object();
        assertTrue(f.request(token).accepted());
        f.applyPlan(token);
        String identity = f.chain.chainInstanceId();
        int revision = f.chain.planRevision();
        RddReplanTransition.SURVIVAL_PREEMPT_AT.put(f.id, 1_000L);
        AtomicInteger invalidated = new AtomicInteger();
        RddReplanTransition.onDeath(f.id, f.chain, invalidated::incrementAndGet);
        assertEquals(1, invalidated.get(), "死亡无论是否仍有marker都作废原票");
        assertEquals(identity, f.chain.chainInstanceId());
        assertEquals(revision, f.chain.planRevision());
        assertEquals(1, f.used(), "死亡释放不赠送预算");
        assertFalse(RddReplanTransition.SURVIVAL_PREEMPT_AT.containsKey(f.id));
        assertTrue(f.request(new Object()).accepted(), "同链同rev的新身体不得继承旧抑制");
    }

    @Test
    void deathCancelsQueuedPendingCallbackAndAllowsSameChainRetry() {
        Fixture f = new Fixture(UUID.randomUUID(), "death-pending");
        RddCallbackGuard guard = new RddCallbackGuard();
        Object old = new Object();
        assertTrue(f.request(old).accepted());
        var ticket = guard.replace(f.id);
        List<Runnable> queued = new ArrayList<>();
        guard.dispatch(ticket, queued::add, () -> fail("死亡前的旧规划不得应用"));
        RddReplanTransition.onDeath(f.id, f.chain, () -> guard.replace(f.id));
        assertEquals(PrimaryGoalStatus.PENDING, f.chain.primaryStatus());
        assertTrue(f.chain.activateCurrent(Map.of()));
        Object current = new Object();
        assertTrue(f.request(current).accepted());
        f.applyPlan(current);
        queued.forEach(Runnable::run);
        RddReplanTransition.markApplied(f.id, old, 999);
        assertEquals(RddReplanTransition.Outcome.DUPLICATE_SURVIVAL_PENDING, f.request(new Object()).outcome());
    }

    @Test
    void serverStopClearsStaticIntentAcrossReloadOfSameChainIdentity() {
        Fixture f = new Fixture(UUID.randomUUID(), "server-stop");
        Object old = new Object();
        assertTrue(f.request(old).accepted());
        f.applyPlan(old);
        String identity = f.chain.chainInstanceId();
        int revision = f.chain.planRevision();
        RddCallbackGuard guard = new RddCallbackGuard();
        var ticket = guard.replace(f.id);
        List<Runnable> queued = new ArrayList<>();
        guard.dispatch(ticket, queued::add, () -> fail("停服前的回调不得运行"));
        RddReplanTransition.SURVIVAL_PREEMPT_AT.put(f.id, 1_000L);
        guard.clear(RddReplanTransition::clearAll); // 与Plugin.clearWorldState相同原消费者
        TaskChain restored = TaskChain.fromJson(f.chain.toJson());
        assertEquals(identity, restored.chainInstanceId());
        assertEquals(revision, restored.planRevision());
        assertFalse(RddReplanTransition.SURVIVAL_PREEMPT_AT.containsKey(f.id));
        // 2026-11-13真实79项唯一红：fromJson按原协议恢复为RECOVERING，须先由宿主确认续跑。
        assertEquals(PrimaryGoalStatus.RECOVERING, restored.primaryStatus());
        int used = f.used();
        int callbacks = f.callbacks();
        long version = restored.stateVersion();
        var recovering = RddReplanTransition.requestSurvival(f.id, restored, RddSurvivalGate.Decision.SEEK_FOOD_FIRST,
                REASON, f.counts, f.key(), LIMIT, f.callbacks::incrementAndGet, new Object());
        assertEquals(RddReplanTransition.Outcome.NOT_ACTIVE, recovering.outcome());
        assertFalse(recovering.accepted(), "恢复裁决前不放松ACTIVE准入");
        assertEquals(used, f.used(), "恢复前拒绝不耗预算");
        assertEquals(callbacks, f.callbacks(), "恢复前拒绝不触发停体回调");
        assertEquals(version, restored.stateVersion());
        assertFalse(RddReplanTransition.hasSurvivalIntent(f.id));
        restored.resumeFromRecovering(); // 与Detector原恢复路径相同，不伪造ACTIVE或改生产准入。
        assertEquals(PrimaryGoalStatus.ACTIVE, restored.primaryStatus());
        assertEquals(identity, restored.chainInstanceId());
        assertEquals(revision, restored.planRevision());
        assertTrue(RddReplanTransition.requestSurvival(f.id, restored, RddSurvivalGate.Decision.SEEK_FOOD_FIRST,
                REASON, f.counts, f.key(), LIMIT, f.callbacks::incrementAndGet, new Object()).accepted());
        queued.forEach(Runnable::run);
    }

    @Test
    void runtimeRemovalOrExternalResetClearsOnlyCapturedCompanion() {
        Fixture f = new Fixture(UUID.randomUUID(), "runtime-removed");
        Fixture other = new Fixture(UUID.randomUUID(), "other-companion");
        Object first = new Object();
        Object second = new Object();
        assertTrue(f.request(first).accepted());
        assertTrue(other.request(second).accepted());
        f.applyPlan(first);
        other.applyPlan(second);
        RddReplanTransition.invalidate(f.id); // removeCurrent/reset/discard共用消费者
        RddReplanTransition.markApplied(f.id, first, 999); // 不得复活已撤销的意图
        assertFalse(RddReplanTransition.hasSurvivalIntent(f.id));
        assertTrue(f.request(new Object()).accepted());
        assertEquals(RddReplanTransition.Outcome.DUPLICATE_SURVIVAL_PENDING, other.request(new Object()).outcome());
        assertEquals(1, other.used());
    }

    @Test
    void acceptedSideEffectFailureDoesNotLeavePermanentPendingMarker() {
        Fixture f = new Fixture(UUID.randomUUID(), "stop-failed");
        assertThrows(IllegalStateException.class, () -> RddReplanTransition.requestSurvival(f.id, f.chain,
                RddSurvivalGate.Decision.SEEK_FOOD_FIRST, REASON, f.counts, f.key(), LIMIT,
                () -> { throw new IllegalStateException("captured owner stop failed"); }, new Object()));
        assertFalse(RddReplanTransition.hasSurvivalIntent(f.id));
        assertEquals(PrimaryGoalStatus.PENDING, f.chain.primaryStatus());
        assertEquals(1, f.used(), "转换确已接受，原有有界预算不退还");
        assertTrue(f.chain.activateCurrent(Map.of()));
        assertTrue(f.request(new Object()).accepted());
    }

    @Test
    void refusedTransitionAndUntypedRequestHaveNoMarkerOrCallback() {
        Fixture f = new Fixture(UUID.randomUUID(), "refused");
        f.chain.enterReplanningFromNegotiation("other source");
        assertEquals(RddReplanTransition.Outcome.NOT_ACTIVE, f.request(new Object()).outcome());
        assertFalse(RddReplanTransition.hasSurvivalIntent(f.id));
        assertEquals(0, f.used());
        assertEquals(0, f.callbacks());
        assertEquals(RddReplanTransition.Outcome.ILLEGAL,
                f.request(RddSurvivalGate.Decision.NONE, new Object()).outcome());
        assertEquals(0, f.used());
    }

    @Test
    void effectiveIntentWinsBeforeBudgetCheckButNewRiskStillRespectsCap() {
        Fixture f = new Fixture(UUID.randomUUID(), "budget-guard");
        Object first = new Object();
        assertTrue(f.request(first).accepted());
        f.applyPlan(first);
        f.counts.put(f.key(), LIMIT);
        assertEquals(RddReplanTransition.Outcome.DUPLICATE_SURVIVAL_PENDING, f.request(new Object()).outcome());
        RddReplanTransition.observeSafe(f.id);
        assertEquals(RddReplanTransition.Outcome.BUDGET_EXHAUSTED, f.request(new Object()).outcome());
        assertEquals(LIMIT, f.used());
        assertEquals(1, f.callbacks());
    }

    @Test
    void externallyAppliedRevisionReleasesEvenAnUnknownPendingIntent() {
        Fixture f = new Fixture(UUID.randomUUID(), "pending-external-plan");
        assertTrue(f.request(new Object()).accepted());
        f.chain.nextPlanRevision();
        f.chain.replaceCurrentSubtasks(List.of(Subtask.hardCoded("external", "外源计划", Map.of("group", "food"))));
        assertTrue(f.chain.activateCurrent(Map.of()));
        assertTrue(f.request(new Object()).accepted(), "明确换rev是新事实，不等于凭UNKNOWN猜失败");
    }

    @Test
    void refusedDifferentDecisionCannotEraseOriginalPendingIntent() {
        Fixture f = new Fixture(UUID.randomUUID(), "pending-decision-refused");
        assertTrue(f.request(new Object()).accepted());
        assertEquals(RddReplanTransition.Outcome.NOT_ACTIVE,
                f.request(RddSurvivalGate.Decision.EAT_FIRST, new Object()).outcome());
        assertEquals(RddReplanTransition.Outcome.DUPLICATE_SURVIVAL_PENDING, f.request(new Object()).outcome());
        assertEquals(1, f.used());
        assertEquals(1, f.callbacks());
    }

    @Test
    void deathAfterSafeObservationStillRejectsQueuedOldBodyPlan() {
        Fixture f = new Fixture(UUID.randomUUID(), "safe-then-death");
        assertTrue(f.request(new Object()).accepted());
        RddCallbackGuard guard = new RddCallbackGuard();
        var ticket = guard.replace(f.id);
        List<Runnable> queued = new ArrayList<>();
        guard.dispatch(ticket, queued::add, () -> fail("已清marker不等于旧身体规划仍有效"));
        RddReplanTransition.observeSafe(f.id);
        RddReplanTransition.onDeath(f.id, f.chain, () -> guard.replace(f.id));
        queued.forEach(Runnable::run);
        assertEquals(PrimaryGoalStatus.PENDING, f.chain.primaryStatus());
    }

    @Test
    void emptySpecsUsesSharedApplicationConsumerWithoutReplacing() {
        Fixture f = new Fixture(UUID.randomUUID(), "empty-specs");
        Object token = new Object();
        assertTrue(f.request(token).accepted());
        int rev = f.chain.planRevision();
        RddReplanTransition.SURVIVAL_PREEMPT_AT.put(f.id, 1_000L);
        assertEquals(SurvivalApplyOutcome.EMPTY_FALLBACK,
                RddReplanTransition.applySurvivalPlan(f.id, token, f.chain, List.of(),
                () -> { throw new AssertionError("空specs不得替换"); },
                ignored -> fail("空specs不得保存/发布")));
        assertEquals(rev, f.chain.planRevision());
        assertFalse(RddReplanTransition.hasSurvivalIntent(f.id));
        assertFalse(RddReplanTransition.SURVIVAL_PREEMPT_AT.containsKey(f.id));
        assertEquals(PrimaryGoalStatus.PENDING, f.chain.primaryStatus());
        assertTrue(f.chain.activateCurrent(Map.of()));
        assertTrue(f.request(new Object()).accepted());
    }

    @Test
    void preReplacementFailureReleasesWithoutClaimingApplication() {
        Fixture f = new Fixture(UUID.randomUUID(), "before-replace");
        Object token = new Object();
        assertTrue(f.request(token).accepted());
        int rev = f.chain.planRevision();
        assertThrows(IllegalStateException.class, () -> RddReplanTransition.applySurvivalPlan(
                f.id, token, f.chain, List.of("spec"),
                () -> { throw new IllegalStateException("conversion failed before replace"); },
                ignored -> fail("替换前失败不得保存/发布")));
        assertEquals(rev, f.chain.planRevision());
        assertFalse(RddReplanTransition.hasSurvivalIntent(f.id));
        assertEquals(PrimaryGoalStatus.PENDING, f.chain.primaryStatus());
        assertTrue(f.chain.activateCurrent(Map.of()));
        assertTrue(f.request(new Object()).accepted());
    }

    @Test
    void successfulApplicationBindsRealRevisionBeforeObserver() {
        Fixture f = new Fixture(UUID.randomUUID(), "applied-before-observer");
        Object token = new Object();
        assertTrue(f.request(token).accepted());
        int rev = f.chain.planRevision();
        AtomicInteger observed = new AtomicInteger();
        assertEquals(SurvivalApplyOutcome.APPLIED,
                RddReplanTransition.applySurvivalPlan(f.id, token, f.chain, List.of("spec"),
                f::replacePlan, subs -> {
                    assertEquals(1, subs.size());
                    assertEquals(rev + 1, f.chain.planRevision());
                    assertEquals(RddReplanTransition.Outcome.DUPLICATE_SURVIVAL_PENDING,
                            f.request(new Object()).outcome(), "观察时已绑定实际rev，而非仍记请求rev");
                    observed.incrementAndGet();
                }));
        assertEquals(1, observed.get());
        assertTrue(f.chain.activateCurrent(Map.of()));
        assertEquals(RddReplanTransition.Outcome.DUPLICATE_SURVIVAL_PENDING, f.request(new Object()).outcome());
        assertEquals(1, f.used());
    }

    @Test
    void observerFailureAfterReplacementRetainsAppliedIntent() {
        Fixture f = new Fixture(UUID.randomUUID(), "after-replace-observer");
        Object token = new Object();
        assertTrue(f.request(token).accepted());
        int rev = f.chain.planRevision();
        RddReplanTransition.SURVIVAL_PREEMPT_AT.put(f.id, 1_000L);
        assertThrows(IllegalStateException.class, () -> RddReplanTransition.applySurvivalPlan(
                f.id, token, f.chain, List.of("spec"), f::replacePlan,
                ignored -> { throw new IllegalStateException("post-apply save/publish action failed"); }));
        assertEquals(rev + 1, f.chain.planRevision());
        assertTrue(RddReplanTransition.hasSurvivalIntent(f.id), "已应用不因后续动作失败而释放");
        assertTrue(RddReplanTransition.SURVIVAL_PREEMPT_AT.containsKey(f.id));
        assertTrue(f.chain.activateCurrent(Map.of()));
        assertEquals(RddReplanTransition.Outcome.DUPLICATE_SURVIVAL_PENDING, f.request(new Object()).outcome());
        assertEquals(1, f.used());
    }

    @Test
    void oldFailureConsumersCannotResumeNewPendingPlan() {
        Fixture f = new Fixture(UUID.randomUUID(), "stale-failure-consumers");
        Object old = new Object();
        assertTrue(f.request(old).accepted());
        RddReplanTransition.abortPlan(f.id, old, f.chain);
        assertTrue(f.chain.activateCurrent(Map.of()));
        Object current = new Object();
        assertTrue(f.request(current).accepted());
        RddReplanTransition.SURVIVAL_PREEMPT_AT.put(f.id, 2_000L);
        long state = f.chain.stateVersion();
        assertFalse(RddReplanTransition.requirePlanningServer(f.id, old, f.chain, null));
        assertEquals(SurvivalApplyOutcome.STALE_IGNORED,
                RddReplanTransition.applySurvivalPlan(f.id, old, f.chain, List.of(),
                        () -> { throw new AssertionError("旧空回不得替换"); }, ignored -> fail("旧空回不得发布")));
        assertEquals(SurvivalApplyOutcome.STALE_IGNORED,
                RddReplanTransition.applySurvivalPlan(f.id, old, f.chain, List.of("old-spec"),
                        () -> { throw new AssertionError("旧转换不得运行"); },
                        ignored -> fail("旧失败不得发布")));
        assertEquals(state, f.chain.stateVersion(), "旧失败不得回落新REPLANNING");
        assertEquals(PrimaryGoalStatus.REPLANNING, f.chain.primaryStatus());
        assertEquals(Long.valueOf(2_000L), RddReplanTransition.SURVIVAL_PREEMPT_AT.get(f.id));
        assertEquals(RddReplanTransition.Outcome.DUPLICATE_SURVIVAL_PENDING, f.request(new Object()).outcome());
        assertEquals(2, f.used());
    }

    @Test
    void oldSuccessfulApplyCannotReplaceOrObserveNewPendingPlan() {
        Fixture f = new Fixture(UUID.randomUUID(), "stale-successful-apply");
        Object old = new Object();
        assertTrue(f.request(old).accepted());
        RddReplanTransition.abortPlan(f.id, old, f.chain);
        assertTrue(f.chain.activateCurrent(Map.of()));
        Object current = new Object();
        assertTrue(f.request(current).accepted());
        RddReplanTransition.SURVIVAL_PREEMPT_AT.put(f.id, 2_000L);
        int revision = f.chain.planRevision();
        long state = f.chain.stateVersion();
        AtomicInteger replacements = new AtomicInteger();
        AtomicInteger observations = new AtomicInteger();

        assertEquals(SurvivalApplyOutcome.STALE_IGNORED,
                RddReplanTransition.applySurvivalPlan(f.id, old, f.chain, List.of("valid-old-spec"),
                        () -> {
                            replacements.incrementAndGet();
                            return f.replacePlan(); // 若执行，会真实推进rev并替换TaskChain
                        }, ignored -> observations.incrementAndGet()));

        assertEquals(0, replacements.get(), "旧token必须在真实替换前拒绝");
        assertEquals(0, observations.get(), "旧回调不得保存/发布");
        assertEquals(revision, f.chain.planRevision());
        assertEquals(state, f.chain.stateVersion());
        assertEquals(PrimaryGoalStatus.REPLANNING, f.chain.primaryStatus());
        assertTrue(RddReplanTransition.hasSurvivalIntent(f.id));
        assertEquals(Long.valueOf(2_000L), RddReplanTransition.SURVIVAL_PREEMPT_AT.get(f.id));
        assertEquals(RddReplanTransition.Outcome.DUPLICATE_SURVIVAL_PENDING, f.request(new Object()).outcome());
        assertEquals(2, f.used());
        assertEquals(2, f.callbacks());
    }

    @Test
    void safetyClearedMarkerStillAllowsOriginalValidTicketToApply() {
        Fixture f = new Fixture(UUID.randomUUID(), "safe-original-apply");
        Object token = new Object();
        assertTrue(f.request(token).accepted());
        RddCallbackGuard guard = new RddCallbackGuard();
        var ticket = guard.replace(f.id);
        RddReplanTransition.observeSafe(f.id);
        assertFalse(RddReplanTransition.hasSurvivalIntent(f.id));
        int revision = f.chain.planRevision();
        AtomicInteger replacements = new AtomicInteger();
        AtomicInteger observations = new AtomicInteger();

        guard.dispatch(ticket, Runnable::run, () -> assertEquals(SurvivalApplyOutcome.APPLIED,
                RddReplanTransition.applySurvivalPlan(f.id, token, f.chain, List.of("valid-spec"),
                        () -> {
                            replacements.incrementAndGet();
                            return f.replacePlan();
                        }, ignored -> observations.incrementAndGet())));

        assertEquals(1, replacements.get());
        assertEquals(1, observations.get());
        assertEquals(revision + 1, f.chain.planRevision());
        assertFalse(RddReplanTransition.hasSurvivalIntent(f.id), "安全释放后不复活marker");
        assertTrue(f.chain.activateCurrent(Map.of()));
        assertTrue(f.request(new Object()).accepted(), "随后新风险可重新请求");
    }

    /** 真 TaskChain + 真预算表；意图 key 用固定 companionId（生产同一 UUID）。 */
    private static final class Fixture {
        final UUID id;
        final Map<String, Integer> counts = new HashMap<>();
        final AtomicInteger callbacks = new AtomicInteger();
        final TaskChain chain;
        private int appliedRevision;

        final String primaryId;

        Fixture(UUID id, String chainGoalId) {
            this(id, chainGoalId, "primary-0");
        }

        Fixture(UUID id, String chainGoalId, String primaryId) {
            this.id = id;
            this.primaryId = primaryId;
            this.chain = new TaskChain(new Goal(chainGoalId, "044 B2 生存意图",
                    List.of(PrimaryGoal.unexpanded(primaryId, "先弄到吃的", List.of()),
                            PrimaryGoal.unexpanded("primary-next", "继续原目标", List.of()))));
            chain.expandCurrentPrimary(List.of(
                    Subtask.hardCoded("s0", "走到熔炉边", Map.of("asset_key", "minecraft:furnace"))));
            chain.startCurrent();
        }

        String key() {
            return id + "|" + chain.currentPrimary().id();
        }

        int used() {
            return counts.getOrDefault(key(), 0);
        }

        int callbacks() {
            return callbacks.get();
        }

        RddReplanTransition.Result request(Object token) {
            return request(RddSurvivalGate.Decision.SEEK_FOOD_FIRST, token);
        }

        RddReplanTransition.Result request(RddSurvivalGate.Decision decision, Object token) {
            return RddReplanTransition.requestSurvival(id, chain, decision,
                    REASON, counts, key(), LIMIT, callbacks::incrementAndGet, token);
        }

        /** 测试只提供真实替换动作，不复制产品的空回/异常/应用判定。 */
        List<Subtask> replacePlan() {
            chain.nextPlanRevision();
            var subs = List.of(Subtask.hardCoded("s2", "先弄到吃的", Map.of("group", "food")));
            chain.replaceCurrentSubtasks(subs);
            return subs;
        }

        /** 原九项的生命周期夹具：新 rev 落链后绑定 applied。 */
        void applyPlan(Object token) {
            replacePlan();
            assertTrue(chain.activateCurrent(Map.of()), "新计划应能过依赖门进入 ACTIVE");
            appliedRevision = chain.planRevision();
            RddReplanTransition.markApplied(id, token, appliedRevision);
        }

        /** 计划由别的来源换掉（不绑定生存意图）。 */
        void replaceByOtherSource() {
            chain.enterReplanningFromNegotiation("external plan change");
            chain.nextPlanRevision();
            chain.replaceCurrentSubtasks(List.of(
                    Subtask.hardCoded("s3", "外部换的计划", Map.of("group", "food"))));
            assertTrue(chain.activateCurrent(Map.of()));
        }
    }
}
