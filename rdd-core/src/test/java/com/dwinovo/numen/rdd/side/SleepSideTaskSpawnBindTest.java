package com.dwinovo.numen.rdd.side;

import com.dwinovo.numen.rdd.side.sleep.SleepSideTaskTrigger;
import com.dwinovo.numen.rdd.side.sleep.SleepSideTaskType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-30（主人裁定）：睡觉支线的完成判据 = <b>绑出生点</b>，不是"睡满一夜"。
 *
 * <p>三件事必须成立：
 * ① 从"没绑"到"绑上了" → 立刻 COMPLETE，不等天亮；
 * ② 昨晚已经绑过的那张床不能让新支线一秒自我完成（起始状态要留底）；
 * ③ 找不到床时 2 分钟就把主线还回去，不再干等一夜（实测冻结过 8 分钟）。
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

        // 同伴走到床边右键 → 出生点绑上（原版：这里就开始睡觉了，但支线不必等天亮）
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
    void seekWindowDoesNotCutAnActualSleep() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        FakeWorldPort port = new FakeWorldPort().at(WINDOW, 0L).spawnBound(false);

        engine.tick(C, port, null, false);
        port.asleep(C, true);
        engine.tick(C, port, null, false);
        assertEquals(SleepSideTaskType.PHASE_SLEEPING, engine.current(C).orElseThrow().phase());

        // 真的躺下了 → 睡过窗口也不该被剪断（天亮自然收）
        port.at(WINDOW + SleepSideTaskType.SEEK_WINDOW_TICKS + 1L, 0L);
        engine.tick(C, port, null, false);
        assertTrue(engine.isActive(C), "已经睡着了就别用找床窗口剪它");
    }

    @Test
    void sleepingThroughNightStillCompletes() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        FakeWorldPort port = new FakeWorldPort().at(WINDOW, 0L).spawnBound(false);

        engine.tick(C, port, null, false);
        port.asleep(C, true);
        engine.tick(C, port, null, false);
        port.asleep(C, false).wake(C, SideTaskWorldPort.Wake.DAYBREAK);
        engine.tick(C, port, null, false);

        assertFalse(engine.isActive(C));
        assertTrue(events.saw("side_task_completed"), "睡到天亮仍然算完成（更强的证据）");
    }
}
