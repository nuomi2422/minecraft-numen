package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.NodeType;
import com.dwinovo.numen.rdd.api.RecurrenceSpec;
import com.dwinovo.numen.rdd.api.SubtaskSpec;
import com.dwinovo.numen.rdd.side.DailyMaintenanceSideTaskType;
import com.dwinovo.numen.rdd.side.FoodSupplyPolicy;
import com.dwinovo.numen.rdd.side.FoodSupplySideTaskTrigger;
import com.dwinovo.numen.rdd.side.FoodSupplySideTaskType;
import com.dwinovo.numen.rdd.side.MainlineResumeToken;
import com.dwinovo.numen.rdd.side.SideTask;
import com.dwinovo.numen.rdd.side.SideTaskEngine;
import com.dwinovo.numen.rdd.side.SideTaskType;
import com.dwinovo.numen.rdd.side.SideTaskVerdict;
import com.dwinovo.numen.rdd.side.SideTaskWorldPort;
import com.dwinovo.numen.rdd.side.death.DeathRecoverySideTaskTrigger;
import com.dwinovo.numen.rdd.side.death.DeathRecoverySideTaskType;
import com.dwinovo.numen.rdd.side.sleep.SleepSideTaskTrigger;
import com.dwinovo.numen.rdd.side.sleep.SleepSideTaskType;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.PrimaryGoalStatus;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.api.SubtaskStatus;
import com.dwinovo.numen.rdd.core.TaskChain;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 044 B2 · 生存抢占 → 重规划 → 恢复主线：真实消费者（{@link RddReplanTransition}）契约组。
 * 2026-11-13 第二版：从 RddPlugin 反射改为直接调生产提取类，纯 JVM，不加载 MC 宿主。
 *
 * <p><b>V03 原件</b>：{@code attack-core-V03/latest.log:15906-15927}、{@code events.jsonl:17834}。
 * t9(goto) 02:24:49 内部取消（CANCELLED，不是外部 close），同刻 SEEK_FOOD_FIRST hunger=9 hp=20，
 * 随后「无法进入重规划 … current subtask is not stuck: RUNNING」，之后没有新任务。
 *
 * <p><b>同窗补证（rdd.jsonl:1304-1316）</b>：该窗只有一条 {@code survival_preempt}；
 * 02:25:10 sleep 支线 created+activated，02:25:50/02:26:50 stalled。此时链仍是 ACTIVE
 * （requestReplan 已失败），所以“sleep 在 REPLANNING 期间接管”只是代码路径可能，不是该窗实机证明。
 *
 * <p><b>本组契约</b>（f82 红基线：5 红 2 绿，见
 * mutation-20261113-111822-5f3451/attempt-e1a6105…；修复批后应 7+1 全绿，且不改任何原断言）：
 * <ol>
 *   <li>原因文本只解释：FAILURE 触发在 RUNNING 上被拒，预算不增；</li>
 *   <li>普通原因同样被拒且不花预算；</li>
 *   <li>已 REPLANNING 的重复请求被拒且不花；</li>
 *   <li>旧 STUCK 正控：FAILED 接受、恰好花 1 次；</li>
 *   <li>接受即作废旧执行身份；迟到重复被拒且不再花；</li>
 *   <li>接受回调只在 ACCEPTED 触发一次、拒绝零次（宿主红基线传空回调，不证明 MC 派发被清）；</li>
 *   <li>SURVIVAL 触发从 RUNNING 必须能进重规划（修复批要求绿）；</li>
 *   <li>规划器 attempt：FAILURE=1，SURVIVAL=0（不得伪造“上一版不可执行”）。</li>
 * </ol>
 */
class RddSurvivalPreemptReplanHostTest {

    private static final int LIMIT = 3;
    private static final String SURVIVAL_REASON = "survival SEEK_FOOD_FIRST hunger=9 hp=20 free=16";

    @Test
    void reasonTextDoesNotAuthorizePreemptionAndRefusalIsFree() {
        Fixture f = new Fixture();
        assertEquals(SubtaskStatus.RUNNING, f.chain.currentSubtaskStatus(), "前提：V03 t9 正在 goto");
        RddReplanTransition.Result r = f.request(RddReplanTransition.Trigger.FAILURE, SURVIVAL_REASON);
        assertFalse(r.accepted(), "原因文本只解释：失败驱动不得因 reason 写着 survival 就放行 RUNNING");
        assertEquals(RddReplanTransition.Outcome.NOT_STUCK, r.outcome());
        assertEquals(SubtaskStatus.RUNNING, f.chain.currentSubtaskStatus(), "拒绝后不得伪标 FAILED/STALLED");
        assertEquals(0, f.used(), "被拒绝的重规划不得先花 failure 预算");
    }

    @Test
    void nonSurvivalReasonOnRunningIsStillRefusedWithoutSpending() {
        Fixture f = new Fixture();
        RddReplanTransition.Result r = f.request(RddReplanTransition.Trigger.FAILURE, "挖不到");
        assertFalse(r.accepted(), "普通原因在 RUNNING 上同样被拒，不得顺带放宽旧路由");
        assertEquals(0, f.used());
    }

    @Test
    void duplicateRequestWhileReplanningIsRefusedWithoutSpending() {
        Fixture f = new Fixture();
        f.chain.enterReplanningFromNegotiation("soldier COUNTER: 计划前提不成立");
        RddReplanTransition.Result r = f.request(RddReplanTransition.Trigger.FAILURE, SURVIVAL_REASON);
        assertFalse(r.accepted());
        assertEquals(RddReplanTransition.Outcome.NOT_ACTIVE, r.outcome());
        assertEquals(0, f.used(), "重复/非 ACTIVE 请求被拒时不得花预算");
    }

    @Test
    void stuckFailureRouteStillAcceptedWithExactlyOneBudgetAndOneCallback() {
        Fixture f = new Fixture();
        f.chain.markFailed("s0", "挖不到");
        RddReplanTransition.Result r = f.request(RddReplanTransition.Trigger.FAILURE, "挖不到");
        assertTrue(r.accepted(), "旧正常 STUCK 重规划路由不得回归");
        assertEquals(1, f.used(), "被接受的一次重规划恰好花 1 次 failure 预算");
        assertEquals(1, f.acceptedCallbacks(), "接受后宿主回调恰好一次");
        assertEquals(PrimaryGoalStatus.REPLANNING, f.chain.primaryStatus(),
                "接受即进入 REPLANNING（真实宿主在有服务端时才继续换计划/回落）");
        assertEquals(SubtaskStatus.FAILED, f.chain.currentSubtaskStatus(),
                "旧二级保持原状态：修复批也不伪标 RUNNING/STALLED");
    }

    @Test
    void acceptedReplanVoidsOldExecutionIdentityAndLateDuplicateStaysFree() {
        Fixture f = new Fixture();
        f.chain.bindExecution("s0", "exec-v03-t9");
        f.chain.markFailed("s0", "挖不到");
        assertTrue(f.request(RddReplanTransition.Trigger.FAILURE, "挖不到").accepted());
        assertNull(f.chain.activeExecutionId(), "进入重规划即作废旧执行身份");
        int used = f.used();
        RddReplanTransition.Result late = f.request(RddReplanTransition.Trigger.FAILURE, "挖不到");
        assertFalse(late.accepted(), "旧身份/重复事件不得再开一次重规划");
        assertEquals(used, f.used(), "被拒的迟到请求不得再花预算");
        assertEquals(1, f.acceptedCallbacks(), "被拒的请求不得触发接受回调");
    }

    /**
     * 回调契约：ACCEPTED 恰好一次，拒绝零次。
     *
     * <p>红基线里真实宿主传空回调（机械提取不夹带净新增）；产品修复批才把它接成
     * {@code clearBody(companionId)} 并单独注明。这里只证明“接受才回调、只回调一次”，
     * **不证明** MC 侧身体派发真的被清掉——那需要带 MC 的运行环境，本模块单测没有。
     */
    @Test
    void acceptedCallbackRunsOnceAndNeverOnRefusal() {
        Fixture refused = new Fixture();
        assertFalse(refused.request(RddReplanTransition.Trigger.FAILURE, "挖不到").accepted());
        assertEquals(0, refused.acceptedCallbacks(), "拒绝时不得触发接受回调");

        Fixture accepted = new Fixture();
        accepted.chain.markFailed("s0", "挖不到");
        assertTrue(accepted.request(RddReplanTransition.Trigger.FAILURE, "挖不到").accepted());
        assertEquals(1, accepted.acceptedCallbacks());
    }

    @Test
    void survivalTriggerFromRunningMustBeAccepted() {
        Fixture f = new Fixture();
        RddReplanTransition.Result r = f.request(RddReplanTransition.Trigger.SURVIVAL, SURVIVAL_REASON);
        assertTrue(r.accepted(),
                "程序来源的生存抢占从 RUNNING 必须能进重规划（f82 红基线时为 false，修复批必须为 true）");
        assertNotEquals(SubtaskStatus.FAILED, f.chain.currentSubtaskStatus(), "抢占不是失败，不得伪标");
        assertNotEquals(SubtaskStatus.STALLED, f.chain.currentSubtaskStatus(), "抢占不是卡死，不得伪标");
    }

    /** 捕获的 owner 身份判定：同一原对象才停；被替换或没捕获到一律不停（无第二本账）。 */
    @Test
    void capturedOwnerIdentityDecidesStop() {
        Object captured = new Object();
        assertTrue(RddReplanTransition.ownsCapturedBody(captured, captured), "同一原对象才允许停");
        assertFalse(RddReplanTransition.ownsCapturedBody(captured, new Object()), "被替换的任务不得停");
        assertFalse(RddReplanTransition.ownsCapturedBody(null, captured), "没捕获到原动作时只重规划、不停体");
    }

    /** 恢复路由只属于普通失败驱动；生存/协商不得被恢复支线提前 return。 */
    @Test
    void recoveryRouteIsFailureOnly() {
        assertTrue(RddReplanTransition.usesRecoveryRoute(RddReplanTransition.Trigger.FAILURE, false),
                "普通失败可走缺失型恢复");
        assertFalse(RddReplanTransition.usesRecoveryRoute(RddReplanTransition.Trigger.FAILURE, true),
                "显式跳过恢复时不走");
        assertFalse(RddReplanTransition.usesRecoveryRoute(RddReplanTransition.Trigger.SURVIVAL, false),
                "生存抢占不得被恢复支线提前 return（否则 Detector 会把“已点名恢复”误签成“已抢占”）");
        assertFalse(RddReplanTransition.usesRecoveryRoute(RddReplanTransition.Trigger.NEGOTIATION, false),
                "协商不走恢复路由");
    }

    /** 规划器 attempt：只有普通失败是 1；生存/协商是事实变化，必须 0，不能伪造"上一版不可执行"。 */
    @Test
    void planningAttemptIsZeroForSurvivalAndOneForFailure() {
        assertEquals(1, RddReplanTransition.planningAttempt(RddReplanTransition.Trigger.FAILURE),
                "普通失败：上一版确实被判不可执行");
        assertEquals(0, RddReplanTransition.planningAttempt(RddReplanTransition.Trigger.SURVIVAL),
                "生存抢占是事实变化：不得给规划器伪造上一版不可执行");
    }

    /**
     * V24: rdd108 stage-B dispatched at 11:19:48.033Z, EAT_FIRST at .034,
     * then daily_maintenance at .201; the planner returned only at 11:20:04.538Z.
     * Exercise the original SleepSafety -> Engine consumer while its real typed intent is pending,
     * not a test-side "if pending" arbiter. The port supplies only body/cooldown facts.
     */
    @Test
    void pendingSurvivalPlannerMustNotSpendOrActivateDueDailyBeforeCallback() {
        Fixture f = new Fixture();
        Object ticket = new Object();
        var world = new PendingDailyWorld();
        var events = new java.util.ArrayList<String>();
        var engine = new SideTaskEngine((event, data) -> events.add(event), token -> token);
        engine.registerType(new HardDailyType());
        engine.registerType(new FoodSupplySideTaskType());
        engine.registerTrigger(new FoodSupplySideTaskTrigger(ctx -> !FoodSupplyPolicy.cooldownBlocks(
                true, ctx.hunger(), ctx.health(), ctx.edibleCount())));
        var daily = RddGoalDriver.buildInjection(f.chain.currentPrimary().id(), f.chain.planRevision(),
                List.of(new SubtaskSpec("收掉成熟小麦并补种", Map.of("asset_key", "minecraft:wheat", "minimum", 1),
                        null, NodeType.DAILY, RecurrenceSpec.daily(false))));
        SideTaskHost.DailyRegistration.register(f.chain, daily, engine);
        String specId = "daily:" + f.chain.goal().id() + ":"
                + Integer.toHexString(daily.get(0).description().hashCode());
        try {
            assertEquals(RddSurvivalGate.Decision.EAT_FIRST,
                    RddSurvivalGate.evaluate(world.hunger(f.id), world.health(f.id), 31, false,
                            world.edibleCount(f.id) > 0));
            assertTrue(RddReplanTransition.requestSurvival(f.id, f.chain, RddSurvivalGate.Decision.EAT_FIRST,
                    "survival EAT_FIRST hunger=6 hp=16 edible=1", f.counts, f.key(), LIMIT,
                    f.callbacks::incrementAndGet, ticket).accepted());
            assertEquals(PrimaryGoalStatus.REPLANNING, f.chain.primaryStatus());
            assertTrue(RddReplanTransition.hasSurvivalIntent(f.id));
            assertFalse(world.bodyBusy(f.id), "pending model work is not an actual body action");
            var token = new MainlineResumeToken(f.id, f.chain.chainInstanceId(), f.chain.goal().id(),
                    f.chain.currentPrimary().id(), f.chain.currentSubtask().id(),
                    f.chain.planRevision(), f.chain.planRevision());
            long version = f.chain.stateVersion();
            SideTaskHost.SleepSafety.tick(engine, f.id, world, token, false,
                    () -> { fail("daytime idle body must not be interrupted for sleep"); return false; });
            assertAll("fresh pending EAT_FIRST owns the decision window, not the body slot",
                    () -> assertTrue(engine.current(f.id).isEmpty(), "DAILY must not take side context before survival callback"),
                    () -> assertNull(engine.handledToday(f.id, world.worldId(), specId, world.gameDay()),
                            "blocked DAILY must retain its original due occurrence"),
                    () -> assertTrue(events.isEmpty(), "no premature due/created/activated receipt"),
                    () -> assertEquals(version, f.chain.stateVersion(), "side admission does not rewrite the mainline"));

            assertEquals(RddReplanTransition.SurvivalApplyOutcome.EMPTY_FALLBACK,
                    RddReplanTransition.applySurvivalPlan(f.id, ticket, f.chain, List.of(),
                            () -> { fail("EMPTY callback must not replace the plan"); return List.of(); }, ignored -> {}));
            assertFalse(RddReplanTransition.hasSurvivalIntent(f.id));
            world.now += 20;
            SideTaskHost.SleepSafety.tick(engine, f.id, world, token, false, () -> false);
            assertEquals(DailyMaintenanceSideTaskType.TYPE_ID, engine.current(f.id).orElseThrow().typeId(),
                    "after explicit callback release, the same due DAILY is admitted by original rules");
            assertEquals(Boolean.TRUE, engine.handledToday(f.id, world.worldId(), specId, world.gameDay()));
            assertEquals(1, java.util.Collections.frequency(events, "side_task_activated"));
        } finally {
            RddReplanTransition.invalidate(f.id);
        }
    }

    /**
     * 060-B：普通 DAILY 是 B 型建议——生存 planner pending 期间 due 仍可见，但永不 activate、
     * 不占身体、不改主线版本。硬源的正控留在上一条（{@link HardDailyType}）。
     */
    @Test
    void ordinaryDailyDueIsAdvisoryWithoutActivationWhilePlannerPending() {
        Fixture f = new Fixture();
        Object ticket = new Object();
        var world = new PendingDailyWorld();
        var events = new java.util.ArrayList<String>();
        var engine = new SideTaskEngine((event, data) -> events.add(event), token -> token);
        engine.registerType(new DailyMaintenanceSideTaskType());
        var daily = RddGoalDriver.buildInjection(f.chain.currentPrimary().id(), f.chain.planRevision(),
                List.of(new SubtaskSpec("收掉成熟小麦并补种", Map.of("asset_key", "minecraft:wheat", "minimum", 1),
                        null, NodeType.DAILY, RecurrenceSpec.daily(false))));
        SideTaskHost.DailyRegistration.register(f.chain, daily, engine);
        try {
            assertTrue(RddReplanTransition.requestSurvival(f.id, f.chain, RddSurvivalGate.Decision.EAT_FIRST,
                    "survival EAT_FIRST hunger=6 hp=16 edible=1", f.counts, f.key(), LIMIT,
                    f.callbacks::incrementAndGet, ticket).accepted());
            long version = f.chain.stateVersion();
            SideTaskHost.SleepSafety.tick(engine, f.id, world, sideOwner(f), false, () -> false);
            assertAll("ordinary DAILY is advisory: visible but never owns the body",
                    () -> assertTrue(engine.current(f.id).isEmpty(), "advisory must not activate"),
                    () -> assertTrue(events.contains("side_task_due"), "due must stay visible"),
                    () -> assertTrue(events.contains("side_task_advisory"), "ordinary due is an advisory"),
                    () -> assertFalse(events.contains("side_task_created"), "no hard instance is created"),
                    () -> assertFalse(events.contains("side_task_activated"), "no activation"),
                    () -> assertEquals(version, f.chain.stateVersion(), "advisory does not rewrite the mainline"));
        } finally {
            RddReplanTransition.invalidate(f.id);
        }
    }

    /** The original red case covers EMPTY; real successful/throwing callbacks also end only the wait. */
    @Test
    void dailyWaitIsScopedAndEndsAfterAppliedOrFailedCallback() {
        for (boolean apply : List.of(true, false)) {
            Fixture f = new Fixture();
            Object ticket = new Object();
            var world = new PendingDailyWorld();
            var engine = dueDailyEngine(f);
            var owner = sideOwner(f);
            try {
                assertFalse(RddReplanTransition.deferPlannerDaily(f.id, owner), "no intent means no wait");
                assertTrue(RddReplanTransition.requestSurvival(f.id, f.chain, RddSurvivalGate.Decision.EAT_FIRST,
                        "typed survival", f.counts, f.key(), LIMIT, null, ticket).accepted());
                assertTrue(RddReplanTransition.deferPlannerDaily(f.id, owner));
                assertFalse(RddReplanTransition.deferPlannerDaily(f.id, null));
                assertFalse(RddReplanTransition.deferPlannerDaily(UUID.randomUUID(), owner));
                for (var other : List.of(
                        new MainlineResumeToken(UUID.randomUUID(), owner.chainInstanceId(), owner.goalId(), owner.primaryId(), owner.subtaskId(), owner.planRevision(), owner.generation()),
                        new MainlineResumeToken(f.id, "other-chain", owner.goalId(), owner.primaryId(), owner.subtaskId(), owner.planRevision(), owner.generation()),
                        new MainlineResumeToken(f.id, owner.chainInstanceId(), owner.goalId(), "other-primary", owner.subtaskId(), owner.planRevision(), owner.generation()),
                        new MainlineResumeToken(f.id, owner.chainInstanceId(), owner.goalId(), owner.primaryId(), owner.subtaskId(), owner.planRevision() + 1, owner.generation()))) {
                    assertFalse(RddReplanTransition.deferPlannerDaily(f.id, other), "old scope must not block the current owner");
                    SideTaskHost.SleepSafety.tick(engine, other.companionId(), world, other, false, () -> false);
                    assertEquals(DailyMaintenanceSideTaskType.TYPE_ID, engine.current(other.companionId()).orElseThrow().typeId());
                    engine = dueDailyEngine(f); // independent current owner; never erase the pending intent
                }
                assertTrue(RddReplanTransition.deferPlannerDaily(f.id, owner), "mismatched reads cannot consume the intent");
                if (apply) {
                    var plan = List.of(Subtask.hardCoded("survival-eat", "eat current food", Map.of("group", "food")));
                    assertEquals(RddReplanTransition.SurvivalApplyOutcome.APPLIED,
                            RddReplanTransition.applySurvivalPlan(f.id, ticket, f.chain, plan, () -> {
                                f.chain.nextPlanRevision();
                                f.chain.replaceCurrentSubtasks(plan);
                                return plan;
                            }, ignored -> {}));
                    assertTrue(RddReplanTransition.hasSurvivalIntent(f.id), "APPLIED remains an intent, not food completion");
                } else {
                    assertThrows(IllegalArgumentException.class, () -> RddReplanTransition.applySurvivalPlan(
                            f.id, ticket, f.chain, List.of("nonempty callback"), () -> {
                                f.chain.replaceCurrentSubtasks(List.of()); // real chain rejects the replacement
                                return List.of();
                            }, ignored -> {}));
                    assertFalse(RddReplanTransition.hasSurvivalIntent(f.id));
                }
                assertFalse(RddReplanTransition.deferPlannerDaily(f.id, sideOwner(f)));
                SideTaskHost.SleepSafety.tick(engine, f.id, world, sideOwner(f), false, () -> false);
                assertEquals(DailyMaintenanceSideTaskType.TYPE_ID, engine.current(f.id).orElseThrow().typeId(),
                        "explicit callback outcome restores ordinary due DAILY admission");
            } finally {
                RddReplanTransition.invalidate(f.id);
            }
        }
    }

    /** Same pending intent must not turn the admission filter into an all-tick sleep/death stop. */
    @Test
    void pendingDailyWaitKeepsActualSleepDeathAndLegacyTickPathsLive() {
        Fixture f = new Fixture();
        Object ticket = new Object();
        var world = new PendingDailyWorld();
        var owner = sideOwner(f);
        var engine = dueDailyEngine(f);
        engine.registerType(new SleepSideTaskType(SleepSideTaskType.Goal.LEGACY_BIND_OR_ENTER, false));
        engine.registerTrigger(new SleepSideTaskTrigger());
        engine.registerType(new DeathRecoverySideTaskType());
        engine.registerTrigger(new DeathRecoverySideTaskTrigger());
        try {
            assertTrue(RddReplanTransition.requestSurvival(f.id, f.chain, RddSurvivalGate.Decision.EAT_FIRST,
                    "typed survival", f.counts, f.key(), LIMIT, null, ticket).accepted());
            SideTaskWorldPort sleeping = new SideTaskWorldPort() {
                public long gameTime() { return world.now; }
                public long dayTime() { return 14_000L; }
                public long gameDay() { return world.gameDay(); }
                public boolean hasDayNightCycle() { return true; }
                public String worldId() { return world.worldId(); }
                public boolean isSleeping(UUID id) { return true; }
                public boolean isDay() { return false; }
                public Wake pollWake(UUID id) { return Wake.NONE; }
            };
            SideTaskHost.SleepSafety.tick(engine, f.id, sleeping, owner, false,
                    () -> { fail("actual sleeping body must never be interrupted"); return false; });
            assertEquals(SleepSideTaskType.TYPE_ID, engine.current(f.id).orElseThrow().typeId());
            SideTaskHost.SleepSafety.tick(engine, f.id, sleeping, owner, false, () -> false);
            assertTrue(engine.current(f.id).isEmpty(), "original sleep evaluation still completes from actual sleeping");
            assertTrue(RddReplanTransition.deferPlannerDaily(f.id, owner), "sleep does not forge a planning callback");

            DeathRecoverySideTaskTrigger.expect(f.id, 40, world.worldId(), "241,68,170", Map.of("minecraft:salmon", 1));
            SideTaskHost.SleepSafety.tick(engine, f.id, world, owner, true,
                    () -> { fail("death recovery must not interrupt for sleep"); return false; });
            assertEquals(DeathRecoverySideTaskType.TYPE_ID, engine.current(f.id).orElseThrow().typeId(),
                    "original death trigger must still consume its own batch despite a pending planner");
            assertEquals("40", engine.current(f.id).orElseThrow().data().get("death_seq"));

            var legacy = dueDailyEngine(f);
            legacy.tick(f.id, world, owner, false);
            assertEquals(DailyMaintenanceSideTaskType.TYPE_ID, legacy.current(f.id).orElseThrow().typeId(),
                    "the old four-argument engine entry retains its original admission contract");
        } finally {
            DeathRecoverySideTaskTrigger.take(f.id);
            RddReplanTransition.invalidate(f.id);
        }
    }

    /** Real five-minute admission recovery must release the same-revision wait before DAILY can run. */
    @Test
    void originalMainlineTimeoutReleasesPendingWaitAndPreservesSameDayDaily() {
        Fixture f = new Fixture();
        Object ticket = new Object();
        var world = new PendingDailyWorld();
        var engine = dueDailyEngine(f);
        var enteredAt = new HashMap<UUID, Long>();
        var clock = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        var resumed = new AtomicInteger();
        try {
            assertTrue(RddReplanTransition.requestSurvival(f.id, f.chain, RddSurvivalGate.Decision.EAT_FIRST,
                    "typed survival awaiting callback", f.counts, f.key(), LIMIT, null, ticket).accepted());
            var owner = sideOwner(f);
            var host = new RddMainlineAdmission.Host() {
                public boolean sideActive() { return engine.isActive(f.id); }
                public void activeProposal() { fail("REPLANNING does not publish an ACTIVE proposal"); }
                public boolean negotiate() { return false; }
                public void watchParked() {}
                public void recovering() { fail("this is not a RECOVERING chain"); }
                public void timeoutResumed(long waitedMs) {
                    assertEquals(300_001L, waitedMs);
                    assertEquals(PrimaryGoalStatus.PENDING, f.chain.primaryStatus());
                    assertFalse(RddReplanTransition.deferPlannerDaily(f.id, owner), "release precedes host observation");
                    resumed.incrementAndGet();
                }
                public void timeoutFailed(long waitedMs, RuntimeException failure) { fail("real timeout resume must succeed", failure); }
            };
            assertTrue(RddMainlineAdmission.stopAfterObservation(f.id, f.chain, f.chain.primaryStatus(),
                    enteredAt, clock::get, host));
            SideTaskHost.SleepSafety.tick(engine, f.id, world, owner, false, () -> false);
            assertTrue(engine.current(f.id).isEmpty(), "the due DAILY is unspent while the planner is pending");
            assertTrue(RddReplanTransition.deferPlannerDaily(f.id, owner));
            clock.addAndGet(300_001L);
            assertTrue(RddMainlineAdmission.stopAfterObservation(f.id, f.chain, f.chain.primaryStatus(),
                    enteredAt, clock::get, host));
            assertEquals(1, resumed.get());
            assertTrue(enteredAt.isEmpty());
            assertEquals(owner.planRevision(), f.chain.planRevision(), "timeout does not hide the stale intent by changing revision");
            assertEquals(owner.chainInstanceId(), f.chain.chainInstanceId());
            assertFalse(RddReplanTransition.hasSurvivalIntent(f.id), "successful original fallback ends this callback wait");
            assertEquals(1, f.used(), "timeout does not buy or spend another replan attempt");
            SideTaskHost.SleepSafety.tick(engine, f.id, world, owner, false, () -> false);
            assertEquals(DailyMaintenanceSideTaskType.TYPE_ID, engine.current(f.id).orElseThrow().typeId());
            assertEquals(world.gameDay(), engine.current(f.id).orElseThrow().scheduledGameDay(),
                    "the same-day pending occurrence survives without moving its schedule");
        } finally {
            RddReplanTransition.invalidate(f.id);
        }
    }

    private static MainlineResumeToken sideOwner(Fixture f) {
        return new MainlineResumeToken(f.id, f.chain.chainInstanceId(), f.chain.goal().id(),
                f.chain.currentPrimary().id(), f.chain.currentSubtask().id(), f.chain.planRevision(), f.chain.planRevision());
    }

    private static SideTaskEngine dueDailyEngine(Fixture f) {
        var engine = new SideTaskEngine(null, token -> token);
        engine.registerType(new HardDailyType());
        var daily = RddGoalDriver.buildInjection(f.chain.currentPrimary().id(), f.chain.planRevision(),
                List.of(new SubtaskSpec("收掉成熟小麦并补种", Map.of("asset_key", "minecraft:wheat", "minimum", 1),
                        null, NodeType.DAILY, RecurrenceSpec.daily(false))));
        SideTaskHost.DailyRegistration.register(f.chain, daily, engine);
        return engine;
    }

    /** Facts only: V24 post-supply cooldown, one uneaten fish, empty body slot; no admission logic. */
    private static final class PendingDailyWorld implements SideTaskWorldPort {
        long now = 40L * 24_000L + 6_000L;
        public long gameTime() { return now; }
        public long dayTime() { return 6_000L; }
        public long gameDay() { return 40L; }
        public boolean hasDayNightCycle() { return true; }
        public String worldId() { return "minecraft:overworld"; }
        public boolean isSleeping(UUID id) { return false; }
        public boolean isDay() { return true; }
        public Wake pollWake(UUID id) { return Wake.NONE; }
        public int hunger(UUID id) { return 6; }
        public int health(UUID id) { return 16; }
        public int edibleCount(UUID id) { return 1; }
        public boolean bodyBusy(UUID id) { return false; }
    }

    /**
     * 老机械题要的“硬占”来源：同一 DAILY TYPE_ID，但显式 advisory=false。
     *
     * <p>2026-11-24 060-B 之后，普通 DAILY 是 B 型建议（advisoryOnly=true：只报 due、不占体）；
     * 这些从 044/E2 起就在验「生存 planner pending 时硬支线先让路」的案例，需要一条真硬源，
     * 而不是把产品默认 DAILY 改回硬来救测试。所以只在 fixture 里显式返回 false。
     */
    private static final class HardDailyType implements SideTaskType {
        private final DailyMaintenanceSideTaskType delegate = new DailyMaintenanceSideTaskType();
        @Override public String typeId() { return DailyMaintenanceSideTaskType.TYPE_ID; }
        @Override public long deadlineTicks() { return delegate.deadlineTicks(); }
        @Override public boolean advisoryOnly() { return false; }
        @Override public SideTask create(SideTaskType.CreationContext ctx) { return delegate.create(ctx); }
        @Override public SideTaskVerdict evaluate(SideTask task, SideTaskWorldPort world) {
            return delegate.evaluate(task, world);
        }
        @Override public String renderContext(SideTask task, SideTaskWorldPort world) {
            return delegate.renderContext(task, world);
        }
    }

    /** 真 TaskChain + 真预算表；触发源显式传入，不靠 reason 文本。 */
    private static final class Fixture {
        final UUID id = UUID.randomUUID();
        final Map<String, Integer> counts = new HashMap<>();
        final AtomicInteger callbacks = new AtomicInteger();
        final TaskChain chain = new TaskChain(new Goal("goal-b2",
                "044 B2 生存抢占→重规划→恢复主线",
                List.of(PrimaryGoal.unexpanded("primary-0", "先弄到吃的", List.of()))));

        Fixture() {
            chain.expandCurrentPrimary(List.of(
                    Subtask.hardCoded("s0", "走到熔炉边", Map.of("asset_key", "minecraft:furnace"))));
            chain.startCurrent();
        }

        String key() {
            return id + "|" + chain.currentPrimary().id();
        }

        RddReplanTransition.Result request(RddReplanTransition.Trigger trigger, String reason) {
            return RddReplanTransition.request(chain, trigger, reason, counts, key(), LIMIT,
                    callbacks::incrementAndGet);
        }

        int used() {
            return counts.getOrDefault(key(), 0);
        }

        int acceptedCallbacks() {
            return callbacks.get();
        }
    }
}
