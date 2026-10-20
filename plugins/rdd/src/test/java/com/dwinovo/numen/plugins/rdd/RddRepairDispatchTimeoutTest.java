package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.RddDeathLedger;
import com.dwinovo.numen.rdd.policy.LocalRepairTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D20-C1：死亡支线超时结账的契约测试。
 *
 * <p><b>修的缺口</b>（FV 终验 V-20261020-03 债①实测）：
 * {@code onRepairFailure} 生产零调用者 → {@code LAST_REPAIR} 永不清除 →
 * 台账永远 UNVERIFIED。{@code tickTimeout} 补上「支线过期 → confirmLost → 清支线」路径。
 *
 * <p>因为 {@code RddRepairDispatch} 是包私有静态 Map 状态机（不接 MC），用反射注入状态做纯 JVM 测试；
 * {@code RddInstrumentation.currentGameTimeTicks()} 是静态方法读 MC 全局——超时判定里它不可注入，
 * 所以这里直接测 {@code tickTimeout} 的分支语义（未过期不动 / 过期结账清态），
 * 时间推进用 Map 里的 created tick 反向构造（把 created 设得足够老即可，不用动时钟）。
 */
class RddRepairDispatchTimeoutTest {

    private static final UUID COMPANION = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private static <T> Map<UUID, T> map(String name) throws Exception {
        Field f = RddRepairDispatch.class.getDeclaredField(name);
        f.setAccessible(true);
        return (Map<UUID, T>) f.get(null);
    }

    /** 造一条已在台账里的死亡 + 已派发的支线（created tick 由参数控制）。 */
    private static void seed(long createdTick) throws Exception {
        RddDeathLedger.clearAll();
        RddDeathLedger.restore(COMPANION, new RddDeathLedger.LedgerSnapshot(1, java.util.List.of()));
        RddDeathLedger.record(COMPANION, 1000L, System.currentTimeMillis(), "0, 0, 0",
                1, Map.of("minecraft:dirt", 10));
        LocalRepairTask.Task task = LocalRepairTask.Task.of("repair-test", "goal-test", "death-event",
                LocalRepairTask.Trigger.DEATH, LocalRepairTask.Priority.CRITICAL,
                5 * 60 * 20, LocalRepairTask.ResumePolicy.RESEARCH_TARGET,
                "test", "ESCALATE", "test");
        map("LAST_REPAIR").put(COMPANION, task);
        map("REPAIR_CREATED_TICK").put(COMPANION, createdTick);
        // OPEN_DEATH_SEQ 用公开入口不进包私有（onDeath 走 Guard），直接反射。
        Map<UUID, Integer> open = map("OPEN_DEATH_SEQ");
        open.put(COMPANION, 1);
    }

    @Test
    void freshBranchIsNotClosed(@TempDir Path dir) throws Exception {
        seed(Long.MAX_VALUE - 100);   // 刚创建，绝不可能过期
        boolean closed = RddRepairDispatch.tickTimeout(COMPANION);
        assertFalse(closed, "刚创建的支线不该被结");
        assertNotNull(RddRepairDispatch.lastRepair(COMPANION), "lastRepair 仍在");
    }

    @Test
    void expiredBranchIsClosedAndLedgerConfirmedLost(@TempDir Path dir) throws Exception {
        // currentGameTimeTicks() 无 MC 环境返回 -1（RddInstrumentation 明确 fallback）。
        // created = Long.MIN_VALUE/2 → now(-1) - created = 巨大正数 → 必然过阈。
        seed(Long.MIN_VALUE / 2);
        boolean closed = RddRepairDispatch.tickTimeout(COMPANION);
        assertTrue(closed, "过期支线应被结掉");
        assertNull(RddRepairDispatch.lastRepair(COMPANION), "lastRepair 应被清");
        var death = RddDeathLedger.all(COMPANION).stream()
                .filter(d -> d.seq() == 1).findFirst().orElseThrow();
        assertEquals(RddDeathLedger.State.CONFIRMED_LOST, death.state(),
                "台账应结成 CONFIRMED_LOST（追踪级，非物理证据）");
        assertTrue(death.evidence().contains("timed out"), "evidence 说明是超时放弃，不是物理销毁");
    }

    @Test
    void noBranchIsNoop(@TempDir Path dir) throws Exception {
        RddDeathLedger.clearAll();
        assertFalse(RddRepairDispatch.tickTimeout(COMPANION), "没有支线时不动");
        assertFalse(RddRepairDispatch.tickTimeout(null), "null 伴不炸");
    }

    @Test
    void removeClearsAllFourMaps(@TempDir Path dir) throws Exception {
        seed(0L);
        RddRepairDispatch.remove(COMPANION);
        assertNull(map("REPAIR_CREATED_TICK").get(COMPANION), "remove 清起点 tick");
        assertNull(RddRepairDispatch.lastRepair(COMPANION), "remove 清任务");
        assertNull(map("OPEN_DEATH_SEQ").get(COMPANION), "remove 清批次 seq");
    }
}
