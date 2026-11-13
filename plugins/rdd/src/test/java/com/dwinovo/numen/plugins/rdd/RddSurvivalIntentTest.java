package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.PrimaryGoalStatus;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * 原CallbackGuard及生产共用失效/回落消费者验证边界；源码接线pin不冒MC加载或供食效果。
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
        // 宿主空回/替换异常/server=null 统一走 markAborted：明确失败才释放，允许重试。
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
    void noServerFallbackConsumerReleasesAndResumes() {
        Fixture f = new Fixture(UUID.randomUUID(), "server-missing");
        Object token = new Object();
        assertTrue(f.request(token).accepted());
        RddReplanTransition.SURVIVAL_PREEMPT_AT.put(f.id, 1_000L);
        RddReplanTransition.abortPlan(f.id, token, f.chain); // Plugin server==null共用消费者
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
        RddReplanTransition.abortPlan(f.id, token, f.chain);
        assertEquals(PrimaryGoalStatus.PENDING, f.chain.primaryStatus());
        assertTrue(f.chain.activateCurrent(Map.of()));
        assertTrue(f.request(new Object()).accepted());
    }

    @Test
    void realReplacementRejectionReleasesForRetry() {
        Fixture f = new Fixture(UUID.randomUUID(), "replacement-rejected");
        Object token = new Object();
        assertTrue(f.request(token).accepted());
        f.chain.nextPlanRevision();
        assertThrows(IllegalArgumentException.class, () -> f.chain.replaceCurrentSubtasks(List.of()));
        RddReplanTransition.abortPlan(f.id, token, f.chain);
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

    /** 只验证MC壳调用点，不冒称已执行MC事件或得到世界效果。 */
    @Test
    void hostFailureAndLifecycleEdgesAreWiredToTestedConsumers() throws Exception {
        Path src = Path.of("src/main/java/com/dwinovo/numen/plugins/rdd");
        String plugin = Files.readString(src.resolve("RddPlugin.java"));
        String detector = Files.readString(src.resolve("RddDetector.java"));
        assertTrue(plugin.contains("CompanionEvent.DEATH, body -> onCompanionDeath(body)"));
        assertTrue(plugin.contains("RddReplanTransition.onDeath(companionId,"));
        assertTrue(plugin.contains("ServerStoppedEvent event) -> clearWorldState()"));
        assertTrue(plugin.contains("RddReplanTransition.clearAll();"));
        assertTrue(plugin.contains("RUNTIMES.remove(companionId);\n        RddReplanTransition.invalidate(companionId);"));
        assertTrue(plugin.contains("RUNTIMES.remove(companionId);\n                RddReplanTransition.invalidate(companionId);"));
        assertTrue(plugin.contains("removeCurrent(id);"), "external_world_reset沿原撤销入口");
        assertTrue(plugin.indexOf("if (server == null)", plugin.indexOf("boolean fromNegotiation = trigger"))
                < plugin.indexOf("RddReplanTransition.abortPlan(companionId, survivalToken, chain)"));
        int empty = plugin.indexOf("if (specs == null || specs.isEmpty())");
        assertTrue(plugin.substring(empty, plugin.indexOf("replan_fallback_resume", empty)).contains("RddReplanTransition.abortPlan"));
        int failed = plugin.indexOf("重规划替换失败，回落重跑现有");
        assertTrue(plugin.substring(failed, plugin.indexOf("return true;", failed)).contains("RddReplanTransition.abortPlan"));
        assertTrue(plugin.contains("组装/发起规划同步失败也是已知失败"));
        assertTrue(plugin.contains("if (!applied) RddReplanTransition.abortPlan"), "应用成功后观测异常不抹marker");
        assertTrue(plugin.contains("RddReplanTransition.markApplied(companionId, survivalToken, chain.planRevision())"));
        assertTrue(detector.contains("SURVIVAL_PREEMPT_AT = RddReplanTransition.SURVIVAL_PREEMPT_AT"));
        int start = detector.indexOf("private static void survivalPreempt");
        int safe = detector.indexOf("RddReplanTransition.observeSafe", start);
        assertTrue(safe > start && safe < detector.indexOf("RddGoalDriver.needExpansion", start));
        assertTrue(safe < detector.indexOf("SideTaskHost.isActive", start));
        assertTrue(safe < detector.indexOf("SURVIVAL_PREEMPT_AT.get", start));
        assertTrue(detector.contains("now - last < 60_000L"), "原冷却阈值不扩大");
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

        /** 模拟宿主 replace 成功：新 rev 落链后才绑定 applied。 */
        void applyPlan(Object token) {
            chain.nextPlanRevision(); // 与生产nextPlanRevision→replace相同，不把换计划当自动增rev
            chain.replaceCurrentSubtasks(List.of(
                    Subtask.hardCoded("s2", "先弄到吃的", Map.of("group", "food"))));
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
