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

    private static String policy(int version, int callsPerWindow, long maxChars) {
        return """
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
