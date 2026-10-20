package com.dwinovo.numen.agent.llm;

import com.dwinovo.numen.agent.context.ContextBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G4b 契约：{@code budget-policy.json} 是给人手调的旋钮，<b>改完必须当轮生效</b>。
 *
 * <p>为什么值得单独一类测试：原来只在 {@code EntityAgentLoop} 类加载时装一次，
 * 改个数值要重启整个游戏 —— 那等于这个旋钮没人会调（实机验证也被迫一次次数重启）。
 * 而「热重载」最容易写错的两处正是这里断言的：①mtime 没变不许重读（否则每轮都在读盘）；
 * ②文件被删要退回默认，而不是抱着旧值不放。
 */
class BudgetPolicyReloadTest {

    @TempDir
    Path configDir;

    private Path file() {
        return configDir.resolve("budget-policy.json");
    }

    private void write(String json) throws Exception {
        Files.writeString(file(), json, StandardCharsets.UTF_8);
    }

    private static String policy(int version, int callsPerWindow, long maxChars) {        return """
                {
                  "version": %d,
                  "mode": "shadow",
                  "roles": { "execution": { "callsPerWindow": %d } },
                  "context": { "maxChars": %d }
                }
                """.formatted(version, callsPerWindow, maxChars);
    }

    @Test
    void changingTheFileTakesEffectWithoutARestart() throws Exception {
        BudgetPolicy.resetForTest();
        try {
            write(policy(1, 2, 2000));
            BudgetPolicy.install(configDir);
            assertEquals(1, BudgetPolicy.policyForTest().version());
            assertEquals(2, BudgetPolicy.policyForTest().roles().get("execution").callsPerWindow());
            assertEquals(2000L, BudgetPolicy.contextBudget().maxChars());

            // 用户改数值（模拟保存文件）：mtime 一定变，下一轮读到的必须已经是新值。
            Thread.sleep(20);
            write(policy(2, 9, 500));
            assertEquals(1, BudgetPolicy.policyForTest().version(), "写文件本身不该改策略：重读由 refresh 驱动");
            BudgetPolicy.refreshIfChanged();
            assertEquals(2, BudgetPolicy.policyForTest().version());
            assertEquals(9, BudgetPolicy.policyForTest().roles().get("execution").callsPerWindow());
            assertEquals(500L, BudgetPolicy.contextBudget().maxChars());
            assertTrue(BudgetPolicy.roleHasLimit("execution"));
        } finally {
            BudgetPolicy.resetForTest();
        }
    }

    @Test
    void unchangedFileIsNotReloaded() throws Exception {
        BudgetPolicy.resetForTest();
        try {
            write(policy(3, 1, 100));
            BudgetPolicy.install(configDir);
            BudgetPolicy.refreshIfChanged();
            BudgetPolicy.refreshIfChanged();
            assertEquals(3, BudgetPolicy.policyForTest().version(), "mtime 没变就不该重读（每轮读盘是白烧 IO）");
        } finally {
            BudgetPolicy.resetForTest();
        }
    }

    @Test
    void reservationIsPinnedToThePolicyVersionItWasMadeUnder() throws Exception {
        // 2026-10-20 复审要求：热重载让「占额在 v10、落地在 v11」成为真事。
        // 不钉版本就会出现「按旧限额占的额度、按新限额记账」——开闸后这是对账灾难。
        BudgetPolicy.resetForTest();
        try {
            write(policy(10, 5, 0));
            BudgetPolicy.install(configDir);
            BudgetPolicy.Reservation r = BudgetPolicy.reserve("execution", 100, 0);
            assertNotNull(r);
            assertEquals(10, r.policyVersion(), "预留必须记住占额时的策略版本");

            Thread.sleep(20);
            write(policy(11, 5, 0));
            BudgetPolicy.refreshIfChanged();
            assertEquals(11, BudgetPolicy.policyForTest().version());

            BudgetPolicy.ChargeOutcome outcome = BudgetPolicy.chargeDetailed(r.id(), 42, 7);
            assertFalse(outcome.charged(), "跨版本不许按旧预留记账");
            assertEquals("POLICY_CHANGED", outcome.reason());
            assertEquals(1, BudgetPolicy.policyVersionMismatches(), "错位要能被数出来");
            assertEquals(0, BudgetPolicy.windowUsage("execution").calls(),
                    "被拒的扣账不写计量：账由调用点用 record 补记（不重复记）");
        } finally {
            BudgetPolicy.resetForTest();
        }
    }

    @Test
    void samePolicyVersionChargesNormally() throws Exception {
        BudgetPolicy.resetForTest();
        try {
            write(policy(20, 5, 0));
            BudgetPolicy.install(configDir);
            BudgetPolicy.Reservation r = BudgetPolicy.reserve("execution", 100, 0);
            assertNotNull(r);
            assertTrue(BudgetPolicy.chargeDetailed(r.id(), 42, 7).charged(), "同版本按实际用量扣账");
            assertEquals(1, BudgetPolicy.windowUsage("execution").calls());
            assertEquals(42L, BudgetPolicy.windowUsage("execution").tokens());
            assertEquals(0, BudgetPolicy.policyVersionMismatches());
        } finally {
            BudgetPolicy.resetForTest();
        }
    }

    @Test
    void estimateMultiplierCalibratesReservationWithoutTouchingTheRuler() throws Exception {
        // G4c：影子期实测 est/actual 中位 3.16、P90 3.69 → 开闸前要能「用一个系数校准占额」，
        // 而且校准不能动 CompactSplit（那把尺同时管 replay 窗口与压缩闸）。
        BudgetPolicy.resetForTest();
        try {
            Files.writeString(file(), """
                    { "version": 30, "mode": "shadow", "estimateMultiplier": 0.35,
                      "roles": { "execution": { "tokensPerWindow": 1000 } } }
                    """, StandardCharsets.UTF_8);
            BudgetPolicy.install(configDir);
            assertEquals(0.35, BudgetPolicy.policyForTest().estimateMultiplier(), 1e-9);
            assertEquals(350L, BudgetPolicy.scaleEstimate(1000), "占额按系数缩放");
            assertEquals(0L, BudgetPolicy.scaleEstimate(0));
            assertEquals(0L, BudgetPolicy.scaleEstimate(-5), "负数不产生负占额");

            // 不写这个字段 = 1.0 = 行为与今天完全一致
            Thread.sleep(20);
            Files.writeString(file(), """
                    { "version": 31, "mode": "shadow", "roles": { "execution": { "tokensPerWindow": 1000 } } }
                    """, StandardCharsets.UTF_8);
            BudgetPolicy.refreshIfChanged();
            assertEquals(1.0, BudgetPolicy.policyForTest().estimateMultiplier(), 1e-9);
            assertEquals(1000L, BudgetPolicy.scaleEstimate(1000));
        } finally {
            BudgetPolicy.resetForTest();
        }
    }

    @Test
    void illegalEstimateMultiplierFallsBackToNeutral() throws Exception {
        BudgetPolicy.resetForTest();
        try {
            for (String bad : new String[]{"0", "-2", "1e9"}) {
                Files.writeString(file(),
                        "{ \"version\": 40, \"estimateMultiplier\": " + bad + " }", StandardCharsets.UTF_8);
                BudgetPolicy.install(configDir);
                double m = BudgetPolicy.policyForTest().estimateMultiplier();
                assertTrue(m > 0 && m <= 10.0, "非法/越界系数必须夹回安全区间，实际=" + m + "（输入 " + bad + "）");
            }
        } finally {
            BudgetPolicy.resetForTest();
        }
    }

    @Test
    void deletingTheFileFallsBackToUnlimitedInsteadOfHoldingTheOldPolicy() throws Exception {
        BudgetPolicy.resetForTest();
        try {
            write(policy(4, 5, 800));
            BudgetPolicy.install(configDir);
            assertEquals(4, BudgetPolicy.policyForTest().version());
            assertEquals(800L, BudgetPolicy.contextBudget().maxChars());

            Files.delete(file());
            BudgetPolicy.refreshIfChanged();
            assertEquals(0, BudgetPolicy.policyForTest().version(), "配置删掉 = 默认策略（shadow、全部不限）");
            assertTrue(BudgetPolicy.contextBudget().isUnlimited());
            assertFalse(BudgetPolicy.roleHasLimit("execution"));
        } finally {
            BudgetPolicy.resetForTest();
        }
    }
}
