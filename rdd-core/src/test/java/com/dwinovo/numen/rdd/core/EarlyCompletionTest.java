package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EarlyCompletionTest {
    private static Subtask item(String id, String name, int min) {
        return Subtask.hardCoded(id, id, Map.of("type", "item", "item", name, "minimum", min));
    }

    @Test
    void onlyInventorySatisfiedPendingSubtasks() {
        Subtask oneDiamond = item("a", "minecraft:diamond", 1);
        Subtask twoDiamond = item("b", "minecraft:diamond", 2);
        Subtask iron = item("c", "minecraft:iron_ingot", 1);
        Subtask chestDiamond = Subtask.hardCoded("d", "chest",
                Map.of("type", "item", "item", "minecraft:diamond", "minimum", 1, "source", "chest"));
        TaskChain chain = new TaskChain(new Goal("g", "g", List.of(
                new PrimaryGoal("p1", "p1", List.of(oneDiamond, twoDiamond)),
                new PrimaryGoal("p2", "p2", List.of(iron, chestDiamond)))));
        var got = EarlyCompletion.satisfied(chain, Map.of("minecraft:diamond", 1, "minecraft:iron_ingot", 1));
        assertEquals(List.of(oneDiamond, iron), got);
        assertEquals(SubtaskStatus.PENDING, chain.currentSubtaskStatus());
        assertTrue(EarlyCompletion.satisfied(chain, Map.of("minecraft:diamond", 1), 999L).isEmpty());
    }
}
