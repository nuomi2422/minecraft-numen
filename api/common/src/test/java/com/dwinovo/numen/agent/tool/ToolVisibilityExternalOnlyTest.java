package com.dwinovo.numen.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolVisibilityExternalOnlyTest {

    private static NumenTool tool(String name, boolean externalOnly) {
        return new NumenTool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public Map<String, Object> parameterSchema() { return Schema.none(); }
            @Override public boolean externalOnly() { return externalOnly; }
        };
    }

    @Test void externalOnlyNeverReachesExecutorEvenWhenWhitelisted() {
        NumenTool admin = tool("learner_admin", true);
        NumenTool normal = tool("mine", false);
        List<NumenTool> kept = ToolVisibility.applyWhitelist(List.of(admin, normal), Set.of("learner_admin", "mine"));
        assertTrue(kept.contains(normal));
        assertFalse(kept.contains(admin), "external-only tools must be invisible to the executor");
        assertEquals(1, kept.size());
    }
}
