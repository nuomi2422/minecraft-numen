package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.PrimaryGoalStatus;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.api.SubtaskStatus;
import com.dwinovo.numen.rdd.core.TaskChain;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RddDeathResourceTransitionTest {
    @Test void failedPlanningCallbackCannotUnfreezeResourceInvalidatedDeathPlan() {
        TaskChain chain = new TaskChain(new Goal("g", "goal", List.of(
                new PrimaryGoal("p", "current", List.of(Subtask.hardCoded("s0", "use furnace",
                        Map.of("asset_key", "minecraft:furnace", "minimum", 1)))))));
        chain.startCurrent();
        chain.invalidateResourcePremises(Set.of("minecraft:furnace"), "death snapshot lost tool");
        assertEquals(PrimaryGoalStatus.REPLANNING, chain.primaryStatus());

        RddReplanTransition.abortPlan(UUID.randomUUID(), new Object(), chain);

        assertEquals(PrimaryGoalStatus.REPLANNING, chain.primaryStatus());
        assertEquals(SubtaskStatus.INVALIDATED, chain.currentSubtaskStatus());
        assertTrue(chain.resourcePremiseReevaluationRequired());
    }
}
