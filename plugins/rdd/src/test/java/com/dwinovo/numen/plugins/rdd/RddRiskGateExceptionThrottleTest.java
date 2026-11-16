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
    void enabledRiskGateSkipsNormalObservationButFailsClosedForHighRisk() throws Exception {
        // The plugin unit-test runtime has no Minecraft ServerPlayer; exercise the real
        // pure guard and separately pin the real adapter delegation below, not a fake body.
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        com.dwinovo.numen.rdd.policy.RiskGateGuard.Facts failing = level -> {
            calls.incrementAndGet();
            throw new IllegalStateException("observation unavailable");
        };
        assertTrue(com.dwinovo.numen.rdd.policy.RiskGateGuard.decide(
                com.dwinovo.numen.rdd.policy.RiskGate.levelForText("原地待命"), failing).allowed());
        org.junit.jupiter.api.Assertions.assertEquals(0, calls.get(), "NORMAL must not sample");
        var denied = com.dwinovo.numen.rdd.policy.RiskGateGuard.decide(
                com.dwinovo.numen.rdd.policy.RiskGate.levelForText("进入下界挖矿"), failing);
        assertFalse(denied.allowed(), "risky observation failure must deny");
        org.junit.jupiter.api.Assertions.assertEquals(1, calls.get());
        org.junit.jupiter.api.Assertions.assertEquals("gate_exception", denied.reason());
        var root = java.nio.file.Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.isDirectory(root.resolve("plugins/rdd/src/main/java"))) root = root.getParent();
        assertTrue(root != null, "candidate source tree required");
        String admission = java.nio.file.Files.readString(root.resolve(
                "plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddRiskAdmission.java"));
        String detector = java.nio.file.Files.readString(root.resolve(
                "plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddDetector.java"));
        assertTrue(detector.contains("return RddRiskAdmission.riskGateAllows(ap, primaryDescription);"));
        assertTrue(admission.contains("RiskGateGuard.decide(level, lv ->"));
        assertTrue(admission.contains("return applyGateDecision(ap, primaryDescription, level, decision, inv);"));
        assertTrue(admission.contains("return decision.allowed();"));
    }
}
