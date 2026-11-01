package com.dwinovo.numen.rdd.side;

import com.dwinovo.numen.rdd.side.sleep.SleepSideTaskTrigger;
import com.dwinovo.numen.rdd.side.sleep.SleepSideTaskType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 通用支线引擎的纯 JVM 行为：触发去重 / 完成 / 中断 / 超时 / 占用 / 持久化。 */
class SideTaskEngineTest {

    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    /** 窗口内的一拍：gameTime=11800（夜前约一分钟），游戏日 0。 */
    private static final long WINDOW = 11_800L;

    private SideTaskEngine engine(RecordingEvents events) {
        SideTaskEngine engine = new SideTaskEngine(events, token -> token);
        engine.registerType(new SleepSideTaskType());
        engine.registerTrigger(new SleepSideTaskTrigger());
        return engine;
    }

    @Test
    void triggersOnceThenNotAgainSameGameDay() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        FakeWorldPort port = new FakeWorldPort().at(WINDOW, 0L);

        engine.tick(C, port, null, false);
        assertTrue(engine.isActive(C), "窗口内应开出一条睡觉支线");
        assertEquals(1, events.count("side_task_created"));

        // 天亮自然醒 → 完成
        port.wake(C, SideTaskWorldPort.Wake.DAYBREAK);
        engine.tick(C, port, null, false);
        assertFalse(engine.isActive(C));
        assertTrue(events.saw("side_task_completed"));
        assertTrue(events.saw("side_task_resumed_mainline"));

        // 同一游戏日再 tick（窗口仍开着）不得再开一条
        engine.tick(C, port, null, false);
        assertFalse(engine.isActive(C));
        assertEquals(1, events.count("side_task_created"), "同一游戏日只触发一次");
    }

    /**
     * 167 §B4-6（2026-11-01）：睡觉支线正在跑时发生死亡，**不许把死亡恢复点名吞掉**。
     *
     * <p>原来的行为：active 存在 → advance 并 return，于是本轮不会创建 death_recovery，
     * 要等睡觉结束；而掉落物 5 分钟就没了。现在死亡占用会先取消不允许在死亡期运行的支线。
     */
    @Test
    void deathRecoveryPreemptsSleepingSideTask() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        FakeWorldPort port = new FakeWorldPort().at(WINDOW, 0L);

        engine.tick(C, port, null, false);
        assertTrue(engine.isActive(C), "先在窗口内开出一条睡觉支线");
        assertEquals("sleep", engine.current(C).orElseThrow().typeId());

        int createdBefore = events.count("side_task_created");
        // 死亡占用：这一拍必须把睡觉支线取消（否则死亡恢复点名会被拖到天亮）
        engine.tick(C, port, null, true);
        assertEquals(createdBefore, events.count("side_task_created"), "本测试没有注册死亡触发器，不应凭空创建");
        assertFalse(engine.isActive(C), "睡觉支线应已被取消，把位置让给死亡恢复");
        assertTrue(events.saw("side_task_cancelled"), "取消要有事件，便于事后归因");

        // 再一拍：仍处死亡占用，且没有死亡触发器 → 不许把 sleep 重新创建回来（否则 cancel→create 循环）
        engine.tick(C, port, null, true);
        assertFalse(engine.isActive(C), "死亡占用期间常驻支线不得被重新创建");
        assertEquals(createdBefore, events.count("side_task_created"));
    }

    @Test
    void triggersDuringNightToo() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        engine.tick(C, new FakeWorldPort().at(18_000L, 0L), null, false);
        assertTrue(engine.isActive(C), "夜里（18000）也应触发，不能只认夜前那 60 秒");
    }

    /**
     * 2026-11-01 实机回归（杂项记录 #423）：{@code 11_800} 那一刻 MC 的 {@code isDay()} 仍为真
     * （夕阳 {@code 12_000} 才开始）。旧实现用 isDay() 判白天 → 每天第一拍就 SKIP；引擎又把
     * SKIP 记进 handledGameDay，13_000–23_000 整个夜晚不再触发（实测连续两晚没有睡眠支线）。
     */
    @Test
    void sleepStartsAtPreDuskEvenWhileIsDayStillTrue() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        FakeWorldPort port = new FakeWorldPort().at(WINDOW, 0L).day(true);

        engine.tick(C, port, null, false);
        assertTrue(engine.isActive(C), "夜前一分钟（isDay 仍真）也必须开睡觉支线");
        assertEquals(1, events.count("side_task_created"));
        assertFalse(events.saw("side_task_skipped_occurrence"), "不得把这次当成白天跳过并锁当日");

        // 创建后的下一拍仍在白天窗口内：不能被 evaluate 的白天判定立刻收掉
        port.at(WINDOW + 100L, 0L).day(true);
        engine.tick(C, port, null, false);
        assertTrue(engine.isActive(C), "窗口内保持 active，等它走到床边绑出生点");
        assertFalse(events.saw("side_task_skipped"));

        // 入夜（13_000，仍在一个 2 分钟找床窗口内）：继续跑
        port.at(13_000L, 0L).day(false);
        engine.tick(C, port, null, false);
        assertTrue(engine.isActive(C), "入夜后支线应继续（窗口没到就还在找床）");
    }

    /** 反向保护：真正把时间跳到正午时，睡眠支线仍然要收，别白占主线。 */
    @Test
    void sleepSideTaskStillSkipsWhenTimeJumpsToRealDay() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        FakeWorldPort port = new FakeWorldPort().at(18_000L, 0L).day(false);

        engine.tick(C, port, null, false);
        assertTrue(engine.isActive(C));

        port.at(6_000L, 0L).day(true);
        engine.tick(C, port, null, false);
        assertFalse(engine.isActive(C), "正午是真实白天，睡觉支线必须收");
        assertTrue(events.saw("side_task_skipped"));
    }

    @Test
    void noTriggerWithoutDayNightCycle() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        FakeWorldPort port = new FakeWorldPort().at(WINDOW, 0L).cycle(false).world("minecraft:the_end");
        engine.tick(C, port, null, false);
        assertFalse(engine.isActive(C));
        assertFalse(events.saw("side_task_created"));
    }

    @Test
    void noTriggerWhileDeathSideTaskOccupies() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        FakeWorldPort port = new FakeWorldPort().at(WINDOW, 0L);
        engine.tick(C, port, null, true);
        assertFalse(engine.isActive(C), "死亡支线占用时不抢");
    }

    @Test
    void deadlineSkipsInsteadOfFailing() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        FakeWorldPort port = new FakeWorldPort().at(WINDOW, 0L);
        engine.tick(C, port, null, false);
        assertTrue(engine.isActive(C));

        port.at(WINDOW + SleepSideTaskType.DEADLINE_TICKS + 1L, 0L);
        engine.tick(C, port, null, false);
        assertFalse(engine.isActive(C));
        assertTrue(events.saw("side_task_skipped"), "超时应是 SKIPPED 不是 FAILED");
        assertFalse(events.saw("side_task_completed"));
    }

    @Test
    void interruptedWakeReturnsToSeeking() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        FakeWorldPort port = new FakeWorldPort().at(WINDOW, 0L);
        engine.tick(C, port, null, false);

        port.asleep(C, true);
        engine.tick(C, port, null, false);
        assertEquals(SleepSideTaskType.PHASE_SLEEPING, engine.current(C).orElseThrow().phase());

        port.asleep(C, false).wake(C, SideTaskWorldPort.Wake.INTERRUPTED);
        engine.tick(C, port, null, false);
        assertTrue(engine.isActive(C), "中断不应结束支线");
        assertEquals(SleepSideTaskType.PHASE_SEEKING, engine.current(C).orElseThrow().phase());
        assertFalse(events.saw("side_task_completed"));
    }

    @Test
    void capturesMainlineToken() {
        MainlineResumeToken token = new MainlineResumeToken(C, "goal-1", "primary-1", "sub-1", 3, 3);
        SideTaskEngine engine = new SideTaskEngine(new RecordingEvents(), t -> t);
        engine.registerType(new SleepSideTaskType());
        engine.registerTrigger(new SleepSideTaskTrigger());
        engine.tick(C, new FakeWorldPort().at(WINDOW, 0L), token, false);
        MainlineResumeToken saved = engine.current(C).orElseThrow().mainlineToken();
        assertNotNull(saved);
        assertEquals("goal-1", saved.goalId());
        assertEquals(3, saved.planRevision());
    }

    @Test
    void invalidMainlineTokenIsNotSaved() {
        SideTaskEngine engine = new SideTaskEngine(new RecordingEvents(), t -> null);
        engine.registerType(new SleepSideTaskType());
        engine.registerTrigger(new SleepSideTaskTrigger());
        engine.tick(C, new FakeWorldPort().at(WINDOW, 0L),
                new MainlineResumeToken(C, "g", "p", "s", 1, 1), false);
        assertTrue(engine.current(C).isPresent());
        assertNull(engine.current(C).orElseThrow().mainlineToken(), "主线已失效 → 不保存令牌");
    }

    @Test
    void persistsAndRestores() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        engine.tick(C, new FakeWorldPort().at(WINDOW, 0L), null, false);
        String instance = engine.current(C).orElseThrow().instanceId();

        RecordingEvents restoredEvents = new RecordingEvents();
        SideTaskEngine restored = new SideTaskEngine(restoredEvents, token -> token);
        restored.registerType(new SleepSideTaskType());
        restored.registerTrigger(new SleepSideTaskTrigger());
        restored.restore(engine.toJson());
        assertTrue(restored.isActive(C));
        assertEquals(instance, restored.current(C).orElseThrow().instanceId());

        // 重启后同一游戏日不重复触发
        restored.tick(C, new FakeWorldPort().at(WINDOW, 0L), null, false);
        assertFalse(restoredEvents.saw("side_task_created"));
    }

    @Test
    void generationAndInstanceIncreaseAcrossDays() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        FakeWorldPort day0 = new FakeWorldPort().at(WINDOW, 0L);
        engine.tick(C, day0, null, false);
        String first = engine.current(C).orElseThrow().instanceId();

        day0.wake(C, SideTaskWorldPort.Wake.DAYBREAK);
        engine.tick(C, day0, null, false);

        long day1Time = 24_000L + WINDOW;
        engine.tick(C, new FakeWorldPort().at(day1Time, 1L), null, false);
        assertTrue(engine.isActive(C));
        assertNotEquals(first, engine.current(C).orElseThrow().instanceId());
        assertEquals(2, events.count("side_task_created"));
    }

    @Test
    void cancelReleasesSlotAndEmitsEvent() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        engine.tick(C, new FakeWorldPort().at(WINDOW, 0L), null, false);
        engine.cancel(C, "owner cleared the goal");
        assertFalse(engine.isActive(C));
        assertTrue(events.saw("side_task_cancelled"));
    }

    // ══════════════════════════════════════════════════════════════════
    // 决策：执行 / 推迟 / 跳过本次（开之前那一问）
    // ══════════════════════════════════════════════════════════════════

    @Test
    void skipOccurrenceLocksDayAndEmits() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = decidingEngine(events, () -> SideTaskDecision.skip("keep_night"));
        FakeWorldPort port = new FakeWorldPort().at(WINDOW, 0L);

        engine.tick(C, port, null, false);
        assertFalse(engine.isActive(C));
        assertEquals(1, events.count("side_task_skipped_occurrence"));
        assertEquals(0, events.count("side_task_created"));

        engine.tick(C, port, null, false);
        assertEquals(1, events.count("side_task_skipped_occurrence"), "跳过本次后本晚不再重复");
    }

    @Test
    void deferThenExecuteAfterCooldown() {
        RecordingEvents events = new RecordingEvents();
        java.util.concurrent.atomic.AtomicReference<SideTaskDecision> decision =
                new java.util.concurrent.atomic.AtomicReference<>(SideTaskDecision.defer(200, "mid_operation"));
        SideTaskEngine engine = decidingEngine(events, decision::get);

        engine.tick(C, new FakeWorldPort().at(WINDOW, 0L), null, false);
        assertFalse(engine.isActive(C));
        assertEquals(1, events.count("side_task_deferred"));

        // 冷却内：不重新决策、不新增事件
        engine.tick(C, new FakeWorldPort().at(WINDOW + 10, 0L), null, false);
        assertEquals(1, events.count("side_task_deferred"));
        assertFalse(engine.isActive(C));

        // 冷却后 + 条件满足：执行
        decision.set(SideTaskDecision.EXECUTE);
        engine.tick(C, new FakeWorldPort().at(WINDOW + 200, 0L), null, false);
        assertTrue(engine.isActive(C));
    }

    @Test
    void deferredStateSurvivesRestart() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = decidingEngine(events, () -> SideTaskDecision.defer(200, "mid_operation"));
        engine.tick(C, new FakeWorldPort().at(WINDOW, 0L), null, false);
        assertEquals(1, events.count("side_task_deferred"));

        RecordingEvents restoredEvents = new RecordingEvents();
        SideTaskEngine restored = decidingEngine(restoredEvents, () -> SideTaskDecision.EXECUTE);
        restored.restore(engine.toJson());

        restored.tick(C, new FakeWorldPort().at(WINDOW + 10, 0L), null, false);
        assertFalse(restored.isActive(C), "重启后仍在冷却内，不决策");
        assertFalse(restoredEvents.saw("side_task_created"));

        restored.tick(C, new FakeWorldPort().at(WINDOW + 200, 0L), null, false);
        assertTrue(restored.isActive(C));
    }

    @Test
    void deferReevaluatesNextNight() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = decidingEngine(events, () -> SideTaskDecision.defer(200, "mid_operation"));
        engine.tick(C, new FakeWorldPort().at(WINDOW, 0L), null, false);
        assertEquals(1, events.count("side_task_deferred"));

        engine.tick(C, new FakeWorldPort().at(WINDOW + 24_000L, 1L), null, false);
        assertEquals(2, events.count("side_task_deferred"), "下一夜重新决策，不被上一夜残留挡住");
    }

    @Test
    void sleepDefersOnCombat() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        FakeWorldPort port = new FakeWorldPort().at(WINDOW, 0L).inCombat(true);

        engine.tick(C, port, null, false);
        assertFalse(engine.isActive(C));
        assertTrue(events.saw("side_task_deferred"));

        port.inCombat(false).at(WINDOW + SleepSideTaskType.COMBAT_RETRY_TICKS, 0L);
        engine.tick(C, port, null, false);
        assertTrue(engine.isActive(C), "战斗结束后过冷却就开睡");
    }

    @Test
    void sleepDefersWhileBodyBusy() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        FakeWorldPort port = new FakeWorldPort().at(WINDOW, 0L).bodyBusy(true);

        engine.tick(C, port, null, false);
        assertFalse(engine.isActive(C));
        assertTrue(events.saw("side_task_deferred"));

        port.bodyBusy(false).at(WINDOW + SleepSideTaskType.BUSY_RETRY_TICKS, 0L);
        engine.tick(C, port, null, false);
        assertTrue(engine.isActive(C), "身体空出来后开睡");
    }

    @Test
    void sleepSkipsWhenMainlineKeepsNight() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        FakeWorldPort port = new FakeWorldPort().at(WINDOW, 0L).objective("主目标：保持夜晚，引诱僵尸");

        engine.tick(C, port, null, false);
        assertFalse(engine.isActive(C));
        assertTrue(events.saw("side_task_skipped_occurrence"));
        assertEquals(0, events.count("side_task_created"));
    }

    private SideTaskEngine decidingEngine(RecordingEvents events,
                                          java.util.function.Supplier<SideTaskDecision> decision) {
        SideTaskEngine engine = new SideTaskEngine(events, token -> token);
        engine.registerType(new DecidingType(decision));
        engine.registerTrigger(new SideTaskTrigger() {
            @Override public String typeId() { return DecidingType.TYPE_ID; }
            @Override public boolean fires(Context ctx) { return true; }
        });
        return engine;
    }

    private static final class DecidingType implements SideTaskType {
        static final String TYPE_ID = "deciding";
        private final java.util.function.Supplier<SideTaskDecision> decision;

        DecidingType(java.util.function.Supplier<SideTaskDecision> decision) {
            this.decision = decision;
        }

        @Override public String typeId() { return TYPE_ID; }
        @Override public long deadlineTicks() { return 1000L; }

        @Override public SideTask create(CreationContext ctx) {
            return new SideTask(ctx.companionId(), ctx.instanceId(), TYPE_ID, ctx.generation(),
                    ctx.scheduledGameDay(), ctx.gameTime(), ctx.deadlineGameTime(),
                    SideTaskState.ACTIVE, "P", java.util.Map.of(), ctx.mainlineToken());
        }

        @Override public SideTaskVerdict evaluate(SideTask task, SideTaskWorldPort world) {
            return SideTaskVerdict.keep("P", java.util.Map.of());
        }

        @Override public SideTaskDecision decide(UUID companionId, SideTaskWorldPort world) {
            return decision.get();
        }

        @Override public String renderContext(SideTask task, SideTaskWorldPort world) { return ""; }
    }
}
