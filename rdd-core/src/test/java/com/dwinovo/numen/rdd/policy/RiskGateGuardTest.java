package com.dwinovo.numen.rdd.policy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 037/T7 · SAFE-01：高风险任务的观测异常不得变成放行。
 *
 * <p>这组用例只用纯函数注入异常，不需要 Minecraft；每个失败点都能单独对上一个真实观测步骤
 * （背包 → 恢复点 → 基地注册表 → 备用投影）。
 */
class RiskGateGuardTest {

    private static final RiskGate.Verdict ALLOW_NETHER = new RiskGate.Verdict(true, RiskLevel.NETHER, List.of(), List.of());
    private static final RiskGate.Verdict BLOCK_NETHER = new RiskGate.Verdict(false, RiskLevel.NETHER,
            List.of("recovery_point need 1 have 0"), List.of("先找床"));

    @Test
    void normalAndMiningNeverTouchObservations() {
        AtomicInteger calls = new AtomicInteger();
        RiskGateGuard.Facts exploding = lv -> {
            calls.incrementAndGet();
            throw new IllegalStateException("背包读不出来");
        };
        for (RiskLevel lv : List.of(RiskLevel.NORMAL, RiskLevel.MINING)) {
            RiskGateGuard.Decision d = RiskGateGuard.decide(lv, exploding);
            assertTrue(d.allowed(), lv + " 不该被这道门拦");
            assertEquals("not_risky", d.reason());
        }
        assertEquals(0, calls.get(), "非高风险级不该去采观测");
    }

    @Test
    void riskyObservationFailureBlocksInsteadOfAllowing() {
        RiskGateGuard.Decision d = RiskGateGuard.decide(RiskLevel.NETHER,
                lv -> { throw new IllegalStateException("基地注册表坏了"); });
        assertFalse(d.allowed(), "观测失败时不许放行下界");
        assertEquals("gate_exception", d.reason());
        assertNotNull(d.error());
        assertTrue(d.error().contains("基地注册表"), d.error());
        assertNull(d.verdict());
    }

    @Test
    void riskyEndLevelFailureBlocksToo() {
        RiskGateGuard.Decision d = RiskGateGuard.decide(RiskLevel.END,
                lv -> { throw new RuntimeException("床坐标查询异常"); });
        assertFalse(d.allowed());
        assertEquals(RiskLevel.END, d.level());
    }

    @Test
    void classifyFailureIsNotTreatedAsHarmless() {
        RiskGateGuard.Decision d = RiskGateGuard.classifyFailed(new IllegalStateException("分类器炸了"));
        assertFalse(d.allowed(), "不知道风险级时不能默认不危险");
        assertEquals("classify_exception", d.reason());
        assertNotNull(d.error());
    }

    @Test
    void healthyVerdictPassesThroughWithoutTightening() {
        RiskGateGuard.Decision allow = RiskGateGuard.decide(RiskLevel.NETHER, lv -> ALLOW_NETHER);
        assertTrue(allow.allowed());
        assertEquals("allowed", allow.reason());
        assertEquals(ALLOW_NETHER, allow.verdict());

        RiskGateGuard.Decision block = RiskGateGuard.decide(RiskLevel.NETHER, lv -> BLOCK_NETHER);
        assertFalse(block.allowed());
        assertEquals("blocked", block.reason());
        assertEquals(BLOCK_NETHER.missing(), block.verdict().missing());
    }

    @Test
    void nullVerdictIsNeverAnAllow() {
        RiskGateGuard.Decision d = RiskGateGuard.decide(RiskLevel.NETHER, lv -> null);
        assertFalse(d.allowed(), "拿不到裁决不等于没问题");
    }

    @Test
    void failureDoesNotLockTheGateForever() {
        // 第一次观测坏 → 拒绝；观测恢复后同一进程再判 → 按正常裁决放行（没有永久暂停锁）。
        AtomicInteger phase = new AtomicInteger(0);
        RiskGateGuard.Facts flaky = lv -> {
            if (phase.get() == 0) {
                throw new IllegalStateException("第一次观测失败");
            }
            return ALLOW_NETHER;
        };
        assertFalse(RiskGateGuard.decide(RiskLevel.NETHER, flaky).allowed());
        phase.set(1);
        RiskGateGuard.Decision recovered = RiskGateGuard.decide(RiskLevel.NETHER, flaky);
        assertTrue(recovered.allowed(), "恢复后必须能重新判，不许永久卡死");
        assertEquals("allowed", recovered.reason());
    }

    @Test
    void riskLevelsStayTheSame() {
        assertTrue(RiskGateGuard.isRisky(RiskLevel.NETHER));
        assertTrue(RiskGateGuard.isRisky(RiskLevel.END));
        assertFalse(RiskGateGuard.isRisky(RiskLevel.MINING));
        assertFalse(RiskGateGuard.isRisky(RiskLevel.NORMAL));
        // 与 RiskGate 的分类口径一致，别自己再造一套
        assertEquals(RiskLevel.NETHER, RiskGate.levelForText("进入下界挖矿"));
        assertTrue(Map.of().isEmpty());
    }
}
