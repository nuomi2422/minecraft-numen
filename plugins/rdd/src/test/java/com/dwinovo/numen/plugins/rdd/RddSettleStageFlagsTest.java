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

    @Test void verifiedRequiresExactGeneration() {
        TaskChain c = chain();
        JsonObject missing = args(true, false);
        assertTrue(RddSettleStageTool.generationError(c, missing).contains("requires state_version"));

        JsonObject exact = args(true, false);
        exact.addProperty("state_version", c.stateVersion());
        exact.addProperty("plan_revision", c.planRevision());
        assertEquals("", RddSettleStageTool.generationError(c, exact));

        JsonObject stale = args(true, false);
        stale.addProperty("state_version", c.stateVersion() + 1);
        stale.addProperty("plan_revision", c.planRevision());
        assertTrue(RddSettleStageTool.generationError(c, stale).contains("stale generation"));
    }
}
