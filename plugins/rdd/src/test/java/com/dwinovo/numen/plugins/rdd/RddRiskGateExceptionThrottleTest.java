package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 037/T7：风险门异常事件的限频口径（纯函数，不需要 Minecraft）。
 *
 * <p>异常拦截本身每 tick 都可能发生，事件必须限频；但「不同原因 / 不同同伴」要各算一次，
 * 否则一个同伴的持续异常会把另一个同伴的首次异常吞掉。
 */
class RddRiskGateExceptionThrottleTest {

    @Test
    void sameTargetAndReasonWithinTenSecondsIsReportedOnce() {
        UUID uuid = UUID.randomUUID();
        String key = "进入下界挖矿".hashCode() + "|gate_exception";
        assertTrue(RddDetector.shouldPublishGatedSnapshot(uuid, key, "riskgate_error"), "首次必须发");
        assertFalse(RddDetector.shouldPublishGatedSnapshot(uuid, key, "riskgate_error"), "10 秒内重复不发");
        assertTrue(RddDetector.shouldPublishGatedSnapshot(uuid, key + "|分类", "riskgate_error"),
                "换原因要单独发一次");
        assertTrue(RddDetector.shouldPublishGatedSnapshot(UUID.randomUUID(), key, "riskgate_error"),
                "换同伴要单独发一次");
    }

    @Test
    void disabledRiskGateAllowsHighRiskWithoutSampling() {
        boolean original = RddDetector.riskGateActive();
        try {
            RddDetector.setRiskGateEnabled(false);
            assertTrue(RddDetector.riskGateAllows(null, "进入下界挖矿"),
                    "关闭风险门时不应采样，空同伴的高风险动作也放行");
        } finally {
            RddDetector.setRiskGateEnabled(original);
        }
    }

    @Test
    void enabledRiskGateSkipsNormalObservationButFailsClosedForHighRisk() {
        boolean original = RddDetector.riskGateActive();
        try {
            RddDetector.setRiskGateEnabled(true);
            assertTrue(RddDetector.riskGateAllows(null, "原地待命"),
                    "NORMAL 不应触碰观测，空同伴也放行");
            assertFalse(RddDetector.riskGateAllows(null, "进入下界挖矿"),
                    "高风险的空同伴背包观测失败必须拒绝，遥测失败也不改变裁决");
        } finally {
            RddDetector.setRiskGateEnabled(original);
        }
    }
}
