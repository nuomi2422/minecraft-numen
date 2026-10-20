package com.dwinovo.numen.agent.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G4c S107：<b>主人回合豁免预算闸</b>（方案 A）。
 *
 * <p>为什么这条值得单独钉：开闸（enforce）之后，「主人正说话、系统因为预算把这一轮压住」
 * 是用户可见的最难看的一种失败——消息没丢，但她不吭声，主人只能再问一遍。
 * 预算管的是花多少钱，不是谁说了算；所以主人回合放行，并留一条 {@code budget_owner_override} 事件。
 *
 * <p>唯一不许的是第三种：静默压着。方案 B（照拦但立刻告知排队）也合规，
 * 那时把 {@code checkBlockForTurn} 改成直接调 {@code checkBlock} 即可，本测试的豁免断言同步改。
 */
class S107OwnerOverrideTest {

    @TempDir
    Path configDir;

    private void installEnforceExhausted() throws Exception {
        Files.writeString(configDir.resolve("budget-policy.json"), """
                {
                  "version": 60,
                  "mode": "enforce",
                  "windowMinutes": 60,
                  "roles": { "execution": { "tokensPerWindow": 1000 } }
                }
                """, StandardCharsets.UTF_8);
        BudgetPolicy.install(configDir);
        BudgetPolicy.record("execution", 5000, 1000);   // 已经超了
    }

    @Test
    void ownerTurnIsNotBlockedEvenWhenTheBudgetIsExhausted() throws Exception {
        BudgetPolicy.resetForTest();
        try {
            installEnforceExhausted();
            assertNotNull(BudgetPolicy.checkBlock("execution", 100), "前提：enforce 下确实该拦");

            assertNull(BudgetPolicy.checkBlockForTurn("execution", 100, true),
                    "主人回合必须放行（S107 方案 A）");
            assertNotNull(BudgetPolicy.checkBlockForTurn("execution", 100, false),
                    "她自发的轮次照拦：豁免只管主人");
        } finally {
            BudgetPolicy.resetForTest();
        }
    }

    @Test
    void overrideDoesNotChangeTheMetering() throws Exception {
        BudgetPolicy.resetForTest();
        try {
            installEnforceExhausted();
            long before = BudgetPolicy.windowUsage("execution").tokens();
            assertNull(BudgetPolicy.checkBlockForTurn("execution", 100, true));
            assertEquals(before, BudgetPolicy.windowUsage("execution").tokens(),
                    "豁免只决定「这一轮发不发」，不许顺手改计量——花掉的 token 照样记");
        } finally {
            BudgetPolicy.resetForTest();
        }
    }

    @Test
    void shadowModeNeverBlocksEitherWay() throws Exception {
        BudgetPolicy.resetForTest();
        try {
            Files.writeString(configDir.resolve("budget-policy.json"), """
                    { "version": 61, "mode": "shadow", "roles": { "execution": { "tokensPerWindow": 1000 } } }
                    """, StandardCharsets.UTF_8);
            BudgetPolicy.install(configDir);
            BudgetPolicy.record("execution", 5000, 1000);
            assertNull(BudgetPolicy.checkBlockForTurn("execution", 100, false));
            assertNull(BudgetPolicy.checkBlockForTurn("execution", 100, true));
        } finally {
            BudgetPolicy.resetForTest();
        }
    }
}
