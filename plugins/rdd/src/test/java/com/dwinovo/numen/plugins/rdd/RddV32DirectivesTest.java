package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** RddV32Directives \u5168\u91cf\u6ce8\u5165\u6587\u6848\u5355\u6d4b\uff08\u9875\u4e00 annexTaskSpec + \u9875\u4e8c harnessHints\uff0c\u7eaf\u6587\u672c\u4e0d\u89e6 MC\uff09\u3002 */
class RddV32DirectivesTest {

    @Test void annexTaskSpecHasTwelveStepsAndDependencyRules() {
        String spec = RddV32Directives.annexTaskSpec();
        assertNotNull(spec);
        for (int i = 1; i <= 12; i++) {
            assertTrue(spec.contains(i + ") "), "missing step " + i);
        }
        // \u4f9d\u8d56\u94c1\u5f8b\u5fc5\u987b\u70b9\u51fa\u5173\u952e\u5e8f\uff1a\u94bb\u77f3\u5b58\u4e00\u5957 \u5148\u4e8e \u6316\u9ed1\u66dc\u77f3\uff1b\u5e8a\u7ed1\u5b9a \u5148\u4e8e\u9ad8\u98ce\u9669\u5916\u51fa
        assertTrue(spec.contains("obsidian") || spec.contains("\u9ed1\u66dc\u77f3"), "obsidian mentioned");
        assertTrue(spec.contains("\u4f9d\u8d56") || spec.contains("\u5148\u4e8e"), "dependency rule present");
    }

    /**
     * 2026-10-01 语义变了：无参 {@code harnessHints()} 不再是「11 条全量」，
     * 而是「无条件该知道的那几条」（省约 500 token/请求，见 RddV32Directives 的类注释）。
     * 所以这里断言的是「无条件条数」而不是 11。
     */
    @Test void harnessHintsWithoutTaskOnlyKeepsUnconditionalOnes() {
        String hints = RddV32Directives.harnessHints();
        assertNotNull(hints);
        // 无条件只有 1 条（保护床/刷怪笼），编号从 1 起连续
        assertTrue(hints.contains("1) "), "unconditional hint kept");
        assertFalse(hints.contains("2) "), "keyword-gated hints must NOT appear without a task");
        assertTrue(hints.contains("刷怪笼"), "the unconditional one is the bed/spawner guard");
    }

    /** 给了任务描述就只发命中的那几条 + 无条件那条，且编号重新连续。 */
    @Test void harnessHintsSelectsByTaskKeywords() {
        String mining = RddV32Directives.harnessHints("挖黑曜石并做鱼骨通道");
        assertTrue(mining.contains("黑曜石") || mining.contains("鱼骨"), "mining hint matched");
        String fighting = RddV32Directives.harnessHints("击杀僵尸验证刷怪机");
        assertTrue(fighting.contains("盾") || fighting.contains("硬冲"), "combat hint matched");
        // 无论命中什么，无条件那条永远在
        assertTrue(mining.contains("刷怪笼"), "unconditional always present");
        assertTrue(fighting.contains("刷怪笼"), "unconditional always present");
    }

    /** 任务描述为空 -> 退化成无参版，不 NPE、不返回 null。 */
    @Test void harnessHintsBlankTaskDegradesToUnconditional() {
        assertEquals(RddV32Directives.harnessHints(), RddV32Directives.harnessHints(null));
        assertEquals(RddV32Directives.harnessHints(), RddV32Directives.harnessHints(""));
    }

    /** 全 11 条仍在表里（只是不再无条件发）——防止「筛着筛着把提示删丢了」。 */
    @Test void allHintsStillPresentInTheTable() {
        String all = String.join("\n", RddV32Directives.harnessHints("挖 矿 水 火把 战斗 刷怪 重生 床 搭塔 捡掉落 背包 丢"));
        int numbered = all.split("\\d+\\) ", -1).length - 1;
        assertEquals(11, numbered, "all 11 hints must be reachable via a matching task");
    }

    @Test void annexObjectiveMentionsNoNetherEntry() {
        String obj = RddV32Directives.ANNEX_OBJECTIVE;
        assertNotNull(obj);
        assertTrue(obj.length() > 20);
    }

    @Test void attachHarnessAppendsWithoutLosingBase() {
        String base = "BASE_CONTEXT";
        String merged = RddV32Directives.attachHarness(base);
        assertTrue(merged.startsWith(base), "base preserved");
        assertTrue(merged.contains(RddV32Directives.harnessHints()), "hints appended");
        // \u7a7a base \u8fd4\u56de hints\uff1bnull base \u4e5f\u4e0d NPE
        assertEquals(RddV32Directives.harnessHints(), RddV32Directives.attachHarness(""));
        assertEquals(RddV32Directives.harnessHints(), RddV32Directives.attachHarness(null));
    }
}
