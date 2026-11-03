package com.dwinovo.numen.rdd.side;

import com.dwinovo.numen.rdd.side.sleep.SleepSideTaskTrigger;
import com.dwinovo.numen.rdd.side.sleep.SleepSideTaskType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 睡觉支线按本次行动一次性完成：服务端观察到正在睡觉或本次新绑上床，
 * 不必等待天亮；已有绑床状态、上次历史和 sleeping=false 不足以完成本次。
 * 测试使用真实引擎和 FakeWorldPort，不推断实际游戏是否自然醒。
 */
class SleepSideTaskSpawnBindTest {

    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    /** 夜前约一分钟：dayTime 11800，游戏日 0。 */
    private static final long WINDOW = 11_800L;

    private SideTaskEngine engine(RecordingEvents events) {
        SideTaskEngine engine = new SideTaskEngine(events, token -> token);
        engine.registerType(new SleepSideTaskType());
        engine.registerTrigger(new SleepSideTaskTrigger());
        return engine;
    }

    @Test
    void bindingSpawnPointCompletesWithoutSleepingThrough() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        FakeWorldPort port = new FakeWorldPort().at(WINDOW, 0L).spawnBound(false);

        engine.tick(C, port, null, false);   // 创建（引擎在创建当拍不做评估）
        assertTrue(engine.isActive(C));
        engine.tick(C, port, null, false);   // 第一次评估：把"起始未绑"留底
        assertTrue(engine.isActive(C));

        // 只模拟出生点由未绑定变为已绑定；不假定游戏里一定也睡着。
        port.spawnBound(true);
        engine.tick(C, port, null, false);

        assertFalse(engine.isActive(C), "绑上出生点就该收，不等天亮");
        assertTrue(events.saw("side_task_completed"));
        assertTrue(events.saw("side_task_resumed_mainline"));
        assertFalse(events.saw("side_task_skipped"));
    }

    @Test
    void alreadyBoundBedFromPreviousNightCannotSelfComplete() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        // 出生点在前一晚就绑好了 —— 这一晚同伴还没做任何动作
        FakeWorldPort port = new FakeWorldPort().at(WINDOW, 0L).spawnBound(true);

        engine.tick(C, port, null, false);
        engine.tick(C, port, null, false);

        assertTrue(engine.isActive(C), "「昨晚已绑」不能冒充「今晚绑了」");
        assertFalse(events.saw("side_task_completed"));
    }

    @Test
    void noBedWithinSeekWindowReleasesMainline() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        FakeWorldPort port = new FakeWorldPort().at(WINDOW, 0L).spawnBound(false);

        engine.tick(C, port, null, false);
        assertTrue(engine.isActive(C));

        // 窗口内的最后一拍：还活着（不是 1 tick 就放弃）
        port.at(WINDOW + SleepSideTaskType.SEEK_WINDOW_TICKS - 1L, 0L);
        engine.tick(C, port, null, false);
        assertTrue(engine.isActive(C), "2 分钟内还在找床");

        // 超过窗口还没绑上 → 把主线还回去（SKIPPED，不是失败）
        port.at(WINDOW + SleepSideTaskType.SEEK_WINDOW_TICKS + 1L, 0L);
        engine.tick(C, port, null, false);
        assertFalse(engine.isActive(C), "找不到床就把主线还回去，别守一夜");
        assertTrue(events.saw("side_task_skipped"));
        assertTrue(events.saw("side_task_resumed_mainline"));
    }

    @Test
    void alreadyBoundBedCompletesImmediatelyWhenSleepingIsObserved() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        FakeWorldPort port = new FakeWorldPort().at(13_000L, 0L).spawnBound(true).asleep(C, false);
        MainlineResumeToken token = new MainlineResumeToken(C, "chain", "goal", "primary", "sub", 1, 1);

        engine.tick(C, port, token, false); // 创建
        String instance = engine.current(C).orElseThrow().instanceId();
        engine.tick(C, port, token, false); // 静态已绑定，仍无睡眠
        assertTrue(engine.isActive(C));
        assertEquals(0, events.count("side_task_completed"));

        port.asleep(C, true); // 同一张已绑床：本次真上床
        engine.tick(C, port, token, false);

        assertFalse(engine.isActive(C), "一观察到睡着就完成，不等天亮");
        assertEquals(1, events.count("side_task_completed"));
        assertEquals(instance, lastPayload(events, "side_task_completed").get("instanceId"));
        assertEquals("observed companion sleeping during this side task",
                lastPayload(events, "side_task_completed").get("reason"));
        assertEquals(true, lastPayload(events, "side_task_resumed_mainline").get("mainlineResumed"));
        assertFalse(events.saw("side_task_skipped"));
    }

    @Test
    void firstEvaluationCompletesIfAlreadySleeping() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        FakeWorldPort port = new FakeWorldPort().at(13_000L, 0L).spawnBound(true).asleep(C, true);

        engine.tick(C, port, null, false); // 创建当拍不评估
        assertTrue(engine.isActive(C));
        engine.tick(C, port, null, false);
        assertFalse(engine.isActive(C), "不要求在同一支线内先采到 sleeping=false");
        assertEquals(1, events.count("side_task_completed"));
        assertEquals("observed companion sleeping during this side task",
                lastPayload(events, "side_task_completed").get("reason"));
    }

    @Test
    void observedSleepWinsOverExpiredSeekWindow() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        FakeWorldPort port = new FakeWorldPort().at(13_000L, 0L).spawnBound(false);

        engine.tick(C, port, null, false);
        engine.tick(C, port, null, false);
        port.at(13_000L + SleepSideTaskType.SEEK_WINDOW_TICKS + 1L, 0L).asleep(C, true);
        engine.tick(C, port, null, false);

        assertFalse(engine.isActive(C));
        assertEquals(1, events.count("side_task_completed"));
        assertFalse(events.saw("side_task_skipped"), "睡眠证据先于找床窗口跳过");
    }

    @Test
    void sleepingFalseAndInterruptedWakeDoNotCompleteAndDeclineStaysDeclined() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        FakeWorldPort port = new FakeWorldPort().at(13_000L, 0L).spawnBound(true).asleep(C, false);

        engine.tick(C, port, null, false);
        engine.tick(C, port, null, false);
        // sleep 被拒、sleeping=false 不构成完成证据；假端口不模拟工具返回值。
        port.asleep(C, false).wake(C, SideTaskWorldPort.Wake.INTERRUPTED);
        engine.tick(C, port, null, false);
        assertTrue(engine.isActive(C));
        assertEquals(0, events.count("side_task_completed"));

        assertTrue(engine.decline(C, "unsafe bed", port.worldId(), port.gameDay()));
        assertFalse(engine.isActive(C));
        assertEquals(1, events.count("side_task_declined"));
        assertEquals(0, events.count("side_task_completed"));
        engine.tick(C, port, null, false);
        assertEquals(1, events.count("side_task_created"), "拒绝后本游戏日不重开");
    }

    @Test
    void nextGameDayDoesNotCompleteFromPreviousSleepOrStaticBinding() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        FakeWorldPort port = new FakeWorldPort().at(13_000L, 0L).spawnBound(true).asleep(C, false);

        engine.tick(C, port, null, false);
        String first = engine.current(C).orElseThrow().instanceId();
        engine.tick(C, port, null, false);
        port.asleep(C, true);
        engine.tick(C, port, null, false);
        assertEquals(1, events.count("side_task_completed"));
        engine.tick(C, port, null, false);
        assertEquals(1, events.count("side_task_created"), "已完成后同一游戏日不重开");

        // 只推进假世界时间并清除当前睡眠，不把它说成真实的两夜或自然醒。
        port.at(24_000L + 13_000L, 1L).asleep(C, false);
        engine.tick(C, port, null, false);
        String second = engine.current(C).orElseThrow().instanceId();
        assertNotEquals(first, second);
        engine.tick(C, port, null, false);
        assertTrue(engine.isActive(C), "昨晚睡过且床仍已绑定，不等于本次已睡");
        assertEquals(1, events.count("side_task_completed"));

        port.asleep(C, true);
        engine.tick(C, port, null, false);
        assertFalse(engine.isActive(C));
        assertEquals(2, events.count("side_task_completed"));
        assertEquals(second, lastPayload(events, "side_task_completed").get("instanceId"));
    }

    @Test
    void restoredLegacySleepingTaskCompletesIfStillSleeping() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        FakeWorldPort port = new FakeWorldPort().at(13_000L, 0L).spawnBound(true);
        engine.tick(C, port, null, false);
        SideTask legacy = engine.current(C).orElseThrow().withPhase(SleepSideTaskType.PHASE_SLEEPING)
                .withData(Map.of(SleepSideTaskType.KEY_SPAWN_BOUND_AT_START, "true",
                        SleepSideTaskType.KEY_EVER_SLEPT, "true", "sleeping", "true"));
        engine.runtime().replace(legacy);

        RecordingEvents restoredEvents = new RecordingEvents();
        SideTaskEngine restored = engine(restoredEvents);
        restored.restore(engine.toJson());
        port.asleep(C, true);
        restored.tick(C, port, null, false);

        assertFalse(restored.isActive(C), "恢复后仍在睡的旧支线同样立即完成");
        assertEquals(1, restoredEvents.count("side_task_completed"));
        assertEquals("observed companion sleeping during this side task",
                lastPayload(restoredEvents, "side_task_completed").get("reason"));
    }

    @Test
    void legacyInterruptedSleepReturnsToSeekingWithoutCompleting() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        FakeWorldPort port = new FakeWorldPort().at(13_000L, 0L).spawnBound(true);
        engine.tick(C, port, null, false);
        SideTask legacy = engine.current(C).orElseThrow().withPhase(SleepSideTaskType.PHASE_SLEEPING)
                .withData(Map.of(SleepSideTaskType.KEY_SPAWN_BOUND_AT_START, "true",
                        SleepSideTaskType.KEY_EVER_SLEPT, "true", "sleeping", "true"));
        engine.runtime().replace(legacy);
        port.asleep(C, false).wake(C, SideTaskWorldPort.Wake.INTERRUPTED);

        engine.tick(C, port, null, false);
        assertEquals(SleepSideTaskType.PHASE_SEEKING, engine.current(C).orElseThrow().phase());
        assertEquals("interrupted_wake", engine.current(C).orElseThrow().data().get("last"));
        assertEquals(0, events.count("side_task_completed"));
    }

    @Test
    void deadlineStillSkipsBeforeObservingSleep() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        FakeWorldPort port = new FakeWorldPort().at(13_000L, 0L);
        engine.tick(C, port, null, false);
        port.at(13_000L + SleepSideTaskType.DEADLINE_TICKS, 0L).dayTime(13_000L).asleep(C, true);

        engine.tick(C, port, null, false);
        assertFalse(engine.isActive(C));
        assertEquals(1, events.count("side_task_skipped"));
        assertEquals(0, events.count("side_task_completed"));
    }

    @Test
    void daybreakStillCompletesWhenSleepingWasNotSampled() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        FakeWorldPort port = new FakeWorldPort().at(13_000L, 0L).spawnBound(false);

        engine.tick(C, port, null, false);
        // 模拟引擎两次采样间快速跳夜，不能据此声称已验证真实游戏的自然醒。
        port.at(24_000L, 1L).asleep(C, false).wake(C, SideTaskWorldPort.Wake.DAYBREAK);
        engine.tick(C, port, null, false);

        assertFalse(engine.isActive(C));
        assertEquals(1, events.count("side_task_completed"));
        assertEquals("slept through the night and woke at daybreak",
                lastPayload(events, "side_task_completed").get("reason"));
    }

    private static Map<String, Object> lastPayload(RecordingEvents events, String event) {
        int index = events.events.lastIndexOf(event);
        assertTrue(index >= 0, "missing event: " + event);
        return events.payloads.get(index);
    }
}
