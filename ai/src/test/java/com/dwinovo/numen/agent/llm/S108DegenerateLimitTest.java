package com.dwinovo.numen.agent.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G4c S108：<b>退化额度</b>——限额比单次请求还小。
 *
 * <p>实机暴露（V-20261021-03）：`tokensPerWindow=15000` 而单次实测 prompt 16.6k。
 * 这种额度拦了也解不开：窗口滚过之后用量仍是 0，下一次还是被同一份请求撑爆，
 * 于是同伴被<b>永久关死</b>，只能等人改配置。
 *
 * <p>取舍写死在这里：<b>宁可超预算，也不许卡死</b> —— 放行 + 大声告警，
 * 而不是安静地把她锁住。
 */
class S108DegenerateLimitTest {

    @TempDir
    Path configDir;

    private void installEnforce(long tokensPerWindow) throws Exception {
        Files.writeString(configDir.resolve("budget-policy.json"), """
                {
                  "version": 70,
                  "mode": "enforce",
                  "windowMinutes": 60,
                  "roles": { "execution": { "tokensPerWindow": %d } }
                }
                """.formatted(tokensPerWindow), StandardCharsets.UTF_8);
        BudgetPolicy.install(configDir);
    }

    @Test
    void singleRequestBiggerThanTheWholeBudgetIsNotBlocked() throws Exception {
        BudgetPolicy.resetForTest();
        try {
            installEnforce(15000);
            // 窗口是空的，但这一个请求就要 16600 > 15000
            assertNull(BudgetPolicy.checkBlock("execution", 16600),
                    "退化额度必须放行：拦了永远解不开，等于把她关死");
            assertTrue(BudgetPolicy.degenerateConfigs() >= 1, "放行必须可被数出来，不能静默");
        } finally {
            BudgetPolicy.resetForTest();
        }
    }

    @Test
    void normalOverrunStillBlocks() throws Exception {
        BudgetPolicy.resetForTest();
        try {
            installEnforce(40000);
            BudgetPolicy.record("execution", 30000, 1000);     // 窗口里已经有 30k
            assertNotNull(BudgetPolicy.checkBlock("execution", 15000),
                    "有真实用量时照拦：30k + 15k > 40k");
            assertEquals(0, BudgetPolicy.degenerateConfigs(), "这不是退化额度，不该计入");
        } finally {
            BudgetPolicy.resetForTest();
        }
    }

    @Test
    void requestThatMerelyFitsIsNotDegenerate() throws Exception {
        BudgetPolicy.resetForTest();
        try {
            installEnforce(20000);
            assertNull(BudgetPolicy.checkBlock("execution", 19000), "单个请求装得下 → 不拦");
            assertEquals(0, BudgetPolicy.degenerateConfigs(), "装得下就不是退化配置");
        } finally {
            BudgetPolicy.resetForTest();
        }
    }
}
