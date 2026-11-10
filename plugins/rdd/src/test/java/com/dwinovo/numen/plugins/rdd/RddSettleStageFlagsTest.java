package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RddSettleStageFlagsTest {
    private static JsonObject args(Boolean verified, Boolean finalGoal) {
        JsonObject o = new JsonObject();
        if (verified != null) o.addProperty("verified", verified);
        if (finalGoal != null) o.addProperty("verify_final_goal", finalGoal);
        return o;
    }

    private static TaskChain chain() {
        Subtask a = Subtask.hardCoded("a", "a", Map.of("type", "item", "item", "minecraft:diamond", "minimum", 1));
        return new TaskChain(new Goal("g", "g", List.of(new PrimaryGoal("p", "p", List.of(a)))));
    }

    @Test void missingFieldsKeepOldDefaultFalse() {
        assertEquals("", RddSettleStageTool.verifyFlagError(args(null, null)));
        assertEquals("", RddSettleStageTool.verifyFlagError((JsonObject) null));
    }

    @Test void finalGoalWithoutVerifiedIsRejected() {
        String err = RddSettleStageTool.verifyFlagError(args(false, true));
        assertTrue(err.contains("requires verified=true"));
        assertTrue(err.contains("no facts were checked"));
        assertFalse(RddSettleStageTool.verifyFlagError(args(false, false)).length() > 0);
        assertFalse(RddSettleStageTool.verifyFlagError(args(true, true)).length() > 0);
    }

    @Test void statusGenerationRoundTripsWithoutStringCoercion() {
        TaskChain c = chain();
        Map<String, Object> fields = RddStatusTool.generationFields(c);
        assertEquals(c.chainInstanceId(), fields.get("chain_instance_id"));
        assertInstanceOf(Number.class, fields.get("state_version"));
        assertInstanceOf(Number.class, fields.get("plan_revision"));
        JsonObject serialized = com.google.gson.JsonParser.parseString(
                new com.google.gson.Gson().toJson(fields)).getAsJsonObject();
        assertTrue(serialized.get("state_version").getAsJsonPrimitive().isNumber());
        assertTrue(serialized.get("plan_revision").getAsJsonPrimitive().isNumber());
        assertEquals("", RddSettleStageTool.generationError(c, serialized));
        assertTrue(RddStatusTool.generationFields(null).isEmpty());
    }

    @Test void statusProjectionDistinguishesActiveAwaitingAndCompleted() {
        TaskChain active = chain();
        var live = RddStatusTool.projectChain(active);
        assertEquals(true, live.data().get("active"));
        assertEquals("a", live.data().get("subtask"));
        assertNotNull(live.data().get("state_version"));

        TaskChain waiting = new TaskChain(new Goal("gw", "gw", List.of(
                PrimaryGoal.unexpanded("lazy", "lazy stage", null))));
        var lazy = RddStatusTool.projectChain(waiting);
        assertNull(lazy.current());
        assertFalse(lazy.data().containsKey("subtask"));
        assertEquals(waiting.chainInstanceId(), lazy.data().get("chain_instance_id"));

        active.startCurrent();
        active.applyHardCodedResult("a", true);
        active.applySupervisorDecision(new SupervisorDecision(
                SupervisorDecisionType.CONFIRM, "p", "verified"));
        var done = RddStatusTool.projectChain(active);
        assertEquals(true, done.completed());
        assertEquals(false, done.data().get("active"));
        assertEquals(true, done.data().get("completed"));
        assertEquals(active.planRevision(), done.data().get("plan_revision"));
    }

    @Test void verifiedRequiresExactGeneration() {
        TaskChain c = chain();
        JsonObject missing = args(true, false);
        assertTrue(RddSettleStageTool.generationError(c, missing).contains("requires chain_instance_id"));
        missing.addProperty("chain_instance_id", c.chainInstanceId());
        assertTrue(RddSettleStageTool.generationError(c, missing).contains("requires state_version"));

        JsonObject exact = args(true, false);
        exact.addProperty("chain_instance_id", c.chainInstanceId());
        exact.addProperty("state_version", c.stateVersion());
        exact.addProperty("plan_revision", c.planRevision());
        assertEquals("", RddSettleStageTool.generationError(c, exact));

        TaskChain replacement = chain();
        assertEquals(c.stateVersion(), replacement.stateVersion());
        assertEquals(c.planRevision(), replacement.planRevision());
        assertTrue(RddSettleStageTool.generationError(replacement, exact).contains("stale chain_instance_id"));

        JsonObject stale = args(true, false);
        stale.addProperty("chain_instance_id", c.chainInstanceId());
        stale.addProperty("state_version", c.stateVersion() + 1);
        stale.addProperty("plan_revision", c.planRevision());
        assertTrue(RddSettleStageTool.generationError(c, stale).contains("stale generation"));
    }
}
