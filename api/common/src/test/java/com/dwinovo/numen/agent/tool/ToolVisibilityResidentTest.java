package com.dwinovo.numen.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-05：白名单把 RESIDENT 工具一起滤掉了。
 *
 * <p>现场：2026-10-30 tool-visibility 上线当天起，learner_note 从 54 次/10 天掉到 6 天 0 次；
 * learner_announce / learner_carrier / learner_usage 同样消失。而源码里它们都标着 RESIDENT，
 * 注释写着「执行 AI 要一直都知道这件事」——配置静默推翻了代码里的决定。
 *
 * <p>这里钉住：白名单只裁非 RESIDENT；常驻工具永远保留；名单为空 = 不裁。
 */
class ToolVisibilityResidentTest {

    private static NumenTool tool(String name, NumenTool.Residency residency) {
        return new NumenTool() {
            @Override public String name() { return name; }
            @Override public Residency residency() { return residency; }
            @Override public String description() { return name; }
            @Override public Map<String, Object> parameterSchema() { return Map.of(); }
            @Override public void invoke(ToolCall call) { }
        };
    }

    @Test
    void residentToolSurvivesWhitelist() {
        var all = List.of(tool("learner_note", NumenTool.Residency.RESIDENT),
                          tool("debug_kill", NumenTool.Residency.DEFERRED));
        var kept = ToolVisibility.applyWhitelist(all, Set.of("debug_kill"));
        assertEquals(2, kept.size(), "常驻工具不能被白名单裁掉");
        assertTrue(kept.stream().anyMatch(t -> t.name().equals("learner_note")));
    }

    @Test
    void nonResidentIsStillFiltered() {
        var all = List.of(tool("a", NumenTool.Residency.DEFERRED), tool("b", NumenTool.Residency.DEFERRED));
        var kept = ToolVisibility.applyWhitelist(all, Set.of("a"));
        assertEquals(1, kept.size());
        assertEquals("a", kept.get(0).name());
    }

    @Test
    void emptyWhitelistMeansNoFiltering() {
        var all = List.of(tool("a", NumenTool.Residency.DEFERRED));
        assertEquals(1, ToolVisibility.applyWhitelist(all, Set.of()).size());
        assertEquals(1, ToolVisibility.applyWhitelist(all, null).size());
    }
}
