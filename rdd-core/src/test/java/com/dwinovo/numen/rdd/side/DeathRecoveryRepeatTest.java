package com.dwinovo.numen.rdd.side;

import com.dwinovo.numen.rdd.side.death.DeathRecoverySideTaskTrigger;
import com.dwinovo.numen.rdd.side.death.DeathRecoverySideTaskType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 死亡恢复的两条去重纪律（2026-10-30，V-20261030-13 实机债②）。
 *
 * <p>实机：同一天连死 5 次，只有 seq1/2/4 开出了支线；seq3 的事实被 seq4 覆盖、seq5 被
 * 「同一游戏日只开一次」直接跳过。这两条都属于"静默吞掉"，比崩溃更难发现，所以钉成测试：
 * <ol>
 *   <li>触发器按到达顺序排队，一次 {@code take()} 只消费一批（不同 seq 不互相覆盖）；</li>
 *   <li>引擎对 {@code oncePerGameDay=false} 的支线不做按日去重 —— 同一天第二批死亡照样开。</li>
 * </ol>
 */
class DeathRecoveryRepeatTest {

    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    @AfterEach
    void clearQueue() {
        DeathRecoverySideTaskTrigger.resetForTest();
    }

    @Test
    void triggerQueuesDifferentDeathBatchesInsteadOfOverwriting() {
        DeathRecoverySideTaskTrigger.expect(C, 3, "minecraft:overworld", "1, 64, 1",
                Map.of("minecraft:diamond", 3));
        DeathRecoverySideTaskTrigger.expect(C, 4, "minecraft:overworld", "2, 64, 2",
                Map.of("minecraft:iron_ingot", 5));

        assertEquals(2, DeathRecoverySideTaskTrigger.pendingCount(C), "两批死亡事实都必须排队");
        assertEquals("3", DeathRecoverySideTaskTrigger.take(C).get("death_seq"), "先到先取");
        assertEquals("4", DeathRecoverySideTaskTrigger.take(C).get("death_seq"), "第二批不能被覆盖");
        assertTrue(DeathRecoverySideTaskTrigger.take(C).isEmpty(), "取空之后没有残留");
        assertEquals(0, DeathRecoverySideTaskTrigger.pendingCount(C));
    }

    @Test
    void repeatedExpectForSameSeqDoesNotQueueTwice() {
        DeathRecoverySideTaskTrigger.expect(C, 7, "minecraft:overworld", "1, 64, 1", Map.of());
        DeathRecoverySideTaskTrigger.expect(C, 7, "minecraft:overworld", "1, 64, 1", Map.of());
        assertEquals(1, DeathRecoverySideTaskTrigger.pendingCount(C), "同一批死亡只排一条");
    }

    @Test
    void engineOpensDeathRecoveryAgainInTheSameGameDay() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = new SideTaskEngine(events, token -> token);
        engine.registerType(new DeathRecoverySideTaskType());
        engine.registerTrigger(new DeathRecoverySideTaskTrigger());
        FakeWorldPort port = new FakeWorldPort().at(1_000L, 5L);

        DeathRecoverySideTaskTrigger.expect(C, 1, "minecraft:overworld", "1, 64, 1",
                Map.of("minecraft:diamond", 1));
        engine.tick(C, port, null, true);
        assertTrue(engine.isActive(C), "第一次死亡应开出恢复支线");
        assertEquals(1, events.count("side_task_created"));

        // 执行 AI 放弃：支线结束，但**不能**把"今天问过了"记下来
        engine.decline(C, "太危险", "minecraft:overworld", 5L);
        assertFalse(engine.isActive(C));

        // 同一天第二批死亡：必须还能开
        DeathRecoverySideTaskTrigger.expect(C, 2, "minecraft:overworld", "2, 64, 2",
                Map.of("minecraft:iron_ingot", 2));
        engine.tick(C, port, null, true);
        assertTrue(engine.isActive(C), "同一天第二次死亡也必须有自己的恢复支线");
        assertEquals(2, events.count("side_task_created"), "两次死亡 = 两条支线，不许按日吞掉");
        assertEquals("2", engine.current(C).orElseThrow().data().get("death_seq"));
    }
}
