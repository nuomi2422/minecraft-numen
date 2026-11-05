package com.dwinovo.numen.core.tools.agent;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-05：find_tools 空结果时补「工具族清单」。纯函数版可直接测。 */
class FindToolsFamiliesHintTest {

    private static NumenTool tool(String name) {
        return new NumenTool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public Map<String, Object> parameterSchema() { return Map.of(); }
            @Override public void invoke(ToolCall call) { }
        };
    }

    @Test
    void groupsByPrefixWithCounts() {
        String hint = FindToolsTool.familiesHint(List.of(
                tool("rdd_status"), tool("rdd_assets"), tool("settlement_list"), tool("goto")));
        assertTrue(hint.contains("rdd_(2)"), "应按前缀计数，实际：" + hint);
        assertTrue(hint.contains("settlement_(1)"), "实际：" + hint);
        assertTrue(hint.contains("goto"), "没有下划线的工具按整名成族，实际：" + hint);
    }

    @Test
    void emptyRegistryGivesEmptyHint() {
        assertEquals("", FindToolsTool.familiesHint(List.of()));
    }
}
