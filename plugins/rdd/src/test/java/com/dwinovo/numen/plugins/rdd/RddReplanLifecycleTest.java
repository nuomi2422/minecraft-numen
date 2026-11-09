package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.TaskChain;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RddReplanLifecycleTest {
    @Test void nullChainIsRefused() {
        assertTrue(RddReplanChainTool.lifecycleError(null).contains("no active"));
    }

    @Test void activeChainIsAllowed() {
        Subtask a = Subtask.hardCoded("a", "a", Map.of("type", "item", "item", "minecraft:diamond", "minimum", 1));
        TaskChain chain = new TaskChain(new Goal("g", "g", List.of(new PrimaryGoal("p", "p", List.of(a)))));
        assertEquals("", RddReplanChainTool.lifecycleError(chain));
    }
}
