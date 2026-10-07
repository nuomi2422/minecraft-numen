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

    @Test
    void triggersDuringNightToo() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        engine.tick(C, new FakeWorldPort().at(18_000L, 0L), null, false);
        assertTrue(engine.isActive(C), "夜里（18000）也应触发，不能只认夜前那 60 秒");
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
}
