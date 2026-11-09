package com.dwinovo.numen.plugins.rdd;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RddRationaleTest {
    @Test void parsesAllFieldsWithoutInventing() {
        JsonObject o = new JsonObject();
        o.addProperty("reason", "diamond is already held");
        o.addProperty("risks", "lava near the chest");
        o.addProperty("conditions", "overworld only");
        o.addProperty("rationale_source", "experience:exp-7");
        var e = RddRationale.from(o, "planner_tool:test");
        assertEquals("diamond is already held", e.why());
        assertEquals("lava near the chest", e.risks());
        assertEquals("overworld only", e.conditions());
        assertEquals("experience:exp-7", e.source());
    }

    @Test void missingOptionalFieldsStayBlankAndDefaultSourceWins() {
        JsonObject o = new JsonObject();
        o.addProperty("reason", "why");
        var e = RddRationale.from(o, "planner_tool:rdd_patch:p1");
        assertEquals("why", e.why());
        assertEquals("", e.risks());
        assertEquals("", e.conditions());
        assertEquals("planner_tool:rdd_patch:p1", e.source());
    }
}
