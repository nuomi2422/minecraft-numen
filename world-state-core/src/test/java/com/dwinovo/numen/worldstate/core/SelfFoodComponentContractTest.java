package com.dwinovo.numen.worldstate.core;

import com.dwinovo.numen.worldstate.core.observation.payload.SelfPayload.Slot;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SelfFoodComponentContractTest {
    @Test void legacyConstructorAndJsonDoNotGuessFoodFromName() {
        assertNull(new Slot(0,"minecraft:bread",3,0,0,false).edible());
        var slot = new Gson().fromJson("{\"slot\":0,\"id\":\"minecraft:bread\",\"count\":3}",Slot.class);
        assertNull(slot.edible()); assertEquals(3,slot.count());
    }
    @Test void componentEvidenceAndKnownEmptySurviveRoundTrip() {
        var gson=new Gson();
        var meal=new Slot(0,"mod:component_meal",3,0,0,false,true);
        assertTrue(gson.fromJson(gson.toJson(meal),Slot.class).edible());
        assertFalse(Slot.empty(0).edible());
    }
}
