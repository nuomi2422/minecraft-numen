package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
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
 * <p>现状（9314 + 机械接缝）：读意图的守卫未接，重复请求仍 ACCEPTED —— 第 1、3 条为红；
 * 第 2、4、5、6 条为绿对照，修复后必须保持绿。
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

        RddReplanTransition.Result second = f.request(new Object());
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
        RddReplanTransition.observeSafe(f.id);
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

        Fixture newPrimary = new Fixture(companion, "goal-intent-primary", "primary-9");
        assertTrue(newPrimary.request(new Object()).accepted(), "换 primary 后旧意图过期，允许新抢占");
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
                    List.of(PrimaryGoal.unexpanded(primaryId, "先弄到吃的", List.of()))));
            chain.expandCurrentPrimary(List.of(
                    Subtask.hardCoded("s0", "走到熔炉边", Map.of("asset_key", "minecraft:furnace"))));
            chain.startCurrent();
        }

        String key() {
            return id + "|" + primaryId;
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
            chain.replaceCurrentSubtasks(List.of(
                    Subtask.hardCoded("s2", "先弄到吃的", Map.of("group", "food"))));
            assertTrue(chain.activateCurrent(Map.of()), "新计划应能过依赖门进入 ACTIVE");
            appliedRevision = chain.planRevision();
            RddReplanTransition.markApplied(id, token, appliedRevision);
        }

        /** 计划由别的来源换掉（不绑定生存意图）。 */
        void replaceByOtherSource() {
            chain.enterReplanningFromNegotiation("external plan change");
            chain.replaceCurrentSubtasks(List.of(
                    Subtask.hardCoded("s3", "外部换的计划", Map.of("group", "food"))));
            assertTrue(chain.activateCurrent(Map.of()));
        }
    }
}
