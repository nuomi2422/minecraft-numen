package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TaskChainPendingTest {
    @Test
    void listsOnlyPendingSubtasksInOrder() {
        Subtask a = Subtask.hardCoded("a", "a", Map.of("type", "item", "item", "minecraft:diamond", "minimum", 1));
        Subtask b = Subtask.hardCoded("b", "b", Map.of("type", "item", "item", "minecraft:diamond", "minimum", 2));
        TaskChain chain = new TaskChain(new Goal("g", "g", List.of(new PrimaryGoal("p", "p", List.of(a, b)))));
        assertEquals(List.of(a, b), chain.pendingSubtasks());
    }
}
