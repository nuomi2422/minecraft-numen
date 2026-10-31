package com.dwinovo.numen.agent.tool;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具表必须能扛住"拷贝时带出 null"。
 *
 * <h2>这条用例是怎么来的</h2>
 * 2026-10-31 实机事故：{@code tools/list} 与内脑每轮 {@code callableTools()} 同时抛
 * {@code Cannot invoke NumenTool.name() because "t" is null}，整条 AI 链路停摆。
 * 根因是旧实现的 {@code new ArrayList<>(TOOLS.values())} 走
 * {@code AbstractCollection.toArray()}：先按 size 开数组再迭代填充，
 * 期间若别的线程 register/remove，数组尾部会留 null —— 不抛异常，静默破坏。
 *
 * <p>所以这里用反射直接把 null 塞进表里，验证 {@code all()/resident()/deferred()}
 * 都不会把它递出去。
 */
class ToolRegistryNullSafetyTest {

    private static final class Fake implements NumenTool {
        private final String name;
        private final Residency residency;

        Fake(String name, Residency residency) {
            this.name = name;
            this.residency = residency;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "fake";
        }

        @Override
        public Map<String, Object> parameterSchema() {
            return Map.of();
        }

        @Override
        public Residency residency() {
            return residency;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, NumenTool> rawMap() throws Exception {
        Field f = ToolRegistry.class.getDeclaredField("TOOLS");
        f.setAccessible(true);
        return (Map<String, NumenTool>) f.get(null);
    }

    @Test
    void 表里混进null时不许递出去() throws Exception {
        Map<String, NumenTool> map = rawMap();
        map.put("__null_probe__", null);
        try {
            List<NumenTool> all = ToolRegistry.all();
            assertTrue(all.stream().noneMatch(java.util.Objects::isNull),
                    "all() 里出现 null，调用方 t.name() 就会 NPE 并把整轮 AI 打挂");
            assertFalse(all.stream().anyMatch(t -> "__null_probe__".equals(t == null ? "" : t.name())));
            assertDoesNotThrow(() -> ToolRegistry.resident().forEach(NumenTool::name));
            assertDoesNotThrow(() -> ToolRegistry.deferred().forEach(NumenTool::name));
        } finally {
            map.remove("__null_probe__");
        }
    }
}
