package com.dwinovo.numen.rdd.side;

import com.dwinovo.numen.rdd.side.sleep.SleepSideTaskTrigger;
import com.dwinovo.numen.rdd.side.sleep.SleepSideTaskType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行 AI 拒绝支线（{@link SideTaskEngine#decline}）：2026-10-28 主人要的
 * 「支线任务发给你了，但要不要去是你的决定 —— 万一那里很危险，我过去了也没用」。
 *
 * <p>钉死四件事：拒绝 ≠ 取消（事件分开）、拒绝之后主线立刻解开、
 * 同一天不重开同型支线、拿不到世界信息时只拒一次（不许凭猜锁掉整晚）。
 */
class SideTaskDeclineTest {

    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final long WINDOW = 11_800L;

    private SideTaskEngine engine(RecordingEvents events) {
        SideTaskEngine engine = new SideTaskEngine(events, token -> token);
        engine.registerType(new SleepSideTaskType());
        engine.registerTrigger(new SleepSideTaskTrigger());
        return engine;
    }

    @Test
    void declineReleasesMainlineAndLocksTheRestOfTheDay() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        FakeWorldPort port = new FakeWorldPort().at(WINDOW, 0L);
        engine.tick(C, port, null, false);
        assertTrue(engine.isActive(C), "夜窗内先开出一条睡觉支线");

        boolean declined = engine.decline(C, "三只僵尸守在床边，我手里没剑", "minecraft:overworld", 0L);

        assertTrue(declined);
        assertFalse(engine.isActive(C), "拒绝之后主线必须立刻解开（否则冻结还在）");
        assertTrue(events.saw("side_task_declined"));
        assertTrue(events.saw("side_task_resumed_mainline"), "拒绝也要走恢复令牌那条路，监测台才对得上账");
        assertEquals(0, events.count("side_task_cancelled"),
                "拒绝不是取消：CANCELLED=别人叫停，DECLINED=她自己判断，复盘含义相反");

        engine.tick(C, port, null, false);
        assertFalse(engine.isActive(C), "同一游戏日不许重开同型支线，否则'拒绝'下一拍就打回原形");
        assertEquals(1, events.count("side_task_created"));
    }

    @Test
    void decliningNothingIsReportedAsFailureNotSilentSuccess() {
        SideTaskEngine engine = engine(new RecordingEvents());
        assertFalse(engine.decline(C, "nothing to decline", "minecraft:overworld", 0L),
                "没有活跃支线时返回 false，调用方据此回执（不许回 success 让她以为跳过了）");
    }

    @Test
    void withoutWorldInfoTheDeclineStillWorksButOnlyForThisInstance() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engine(events);
        FakeWorldPort port = new FakeWorldPort().at(WINDOW, 0L);
        engine.tick(C, port, null, false);

        assertTrue(engine.decline(C, "拿不到世界信息，只拒这一次", null, -1L),
                "拿不到世界信息也要能拒绝（不许因为少一个参数就把人锁在支线里）");
        assertFalse(engine.isActive(C));

        // 当天本就不重开（创建那一刻已经锁了 handledGameDay），下一夜应当照常再来问。
        engine.tick(C, new FakeWorldPort().at(24_000L + WINDOW, 1L), null, false);
        assertTrue(engine.isActive(C), "拒绝只影响当次/当天，不许永久关掉这条支线");
    }

    @Test
    void declinedSideTaskStaysDeclinedAcrossRestart() {
        SideTaskEngine engine = engine(new RecordingEvents());
        engine.tick(C, new FakeWorldPort().at(WINDOW, 0L), null, false);
        engine.decline(C, "declined", "minecraft:overworld", 0L);

        RecordingEvents restoredEvents = new RecordingEvents();
        SideTaskEngine restored = engine(restoredEvents);
        restored.restore(engine.toJson());

        assertFalse(restored.isActive(C), "重启不能把'她拒绝过'的记忆变成'没发生过'");
        restored.tick(C, new FakeWorldPort().at(WINDOW, 0L), null, false);
        assertFalse(restored.isActive(C), "当天去重也随盘存活");
    }
}
