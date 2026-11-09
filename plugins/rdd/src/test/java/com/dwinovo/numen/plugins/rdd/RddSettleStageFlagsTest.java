package com.dwinovo.numen.plugins.rdd;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RddSettleStageFlagsTest {
    private static JsonObject args(Boolean verified, Boolean finalGoal) {
        JsonObject o = new JsonObject();
        if (verified != null) o.addProperty("verified", verified);
        if (finalGoal != null) o.addProperty("verify_final_goal", finalGoal);
        return o;
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
}
