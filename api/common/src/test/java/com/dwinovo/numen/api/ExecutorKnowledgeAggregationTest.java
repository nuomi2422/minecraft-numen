package com.dwinovo.numen.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G5e：执行角色知识聚合门（{@link NumenPlugins#aggregateExecutorKnowledge}）的离线证据。
 *
 * <p>与规划知识同口径的三重兜底：稳定分隔符、总字符上限、故障隔离。
 * 用纯函数而不是全局注册表 —— 静态表没法在测试间隔离。
 */
class ExecutorKnowledgeAggregationTest {

    private static final UUID C = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static Function<UUID, String> fixed(String text) {
        return uuid -> text;
    }

    @Test
    void 多条贡献按稳定分隔符拼接() {
        String out = NumenPlugins.aggregateExecutorKnowledge(
                List.of(fixed("<a>1</a>"), fixed("<b>2</b>")), C);
        assertEquals("<a>1</a>" + NumenPlugins.PLANNING_KNOWLEDGE_SEPARATOR + "<b>2</b>", out);
    }

    @Test
    void 某条算炸只丢它自己() {
        String out = NumenPlugins.aggregateExecutorKnowledge(
                List.of(fixed("<ok>1</ok>"), uuid -> {
                    throw new IllegalStateException("boom");
                }, fixed("<ok>3</ok>")), C);
        assertEquals("<ok>1</ok>" + NumenPlugins.PLANNING_KNOWLEDGE_SEPARATOR + "<ok>3</ok>", out);
    }

    @Test
    void 超总上限截断且后面的不再追加() {
        String big = "x".repeat(NumenPlugins.MAX_EXECUTOR_KNOWLEDGE_CHARS + 50);
        String out = NumenPlugins.aggregateExecutorKnowledge(
                List.of(fixed(big), fixed("<tail>不该出现</tail>")), C);
        assertEquals(NumenPlugins.MAX_EXECUTOR_KNOWLEDGE_CHARS, out.length());
        assertTrue(out.startsWith("xxx"));
    }

    @Test
    void 空表空uuid返回空串() {
        assertEquals("", NumenPlugins.aggregateExecutorKnowledge(null, C));
        assertEquals("", NumenPlugins.aggregateExecutorKnowledge(List.of(), C));
        assertEquals("", NumenPlugins.aggregateExecutorKnowledge(List.of(fixed("x")), null));
        assertEquals("", NumenPlugins.aggregateExecutorKnowledge(List.of(fixed("   ")), C));
    }
}
