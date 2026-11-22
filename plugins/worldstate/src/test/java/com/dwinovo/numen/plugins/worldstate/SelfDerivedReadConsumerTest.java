package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.api.observation.*;
import com.dwinovo.numen.worldstate.core.freshness.TopicFreshnessPolicy;
import com.dwinovo.numen.worldstate.core.model.*;
import com.dwinovo.numen.worldstate.core.observation.*;
import com.dwinovo.numen.worldstate.core.observation.payload.SelfPayload;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;
import com.dwinovo.numen.worldstate.core.store.InMemoryObservationStore;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual store/SELF reader/projection/tool JSON; no Minecraft or network. */
class SelfDerivedReadConsumerTest {
    private static final UUID ID=UUID.fromString("00000000-0000-0000-0000-000000000006");
    private static final String DIM="minecraft:overworld";
    private static Observation sample(int count, Boolean food, long rev, long at) {
        var slots=new ArrayList<SelfPayload.Slot>(); for(int i=0;i<36;i++) slots.add(SelfPayload.Slot.empty(i));
        slots.set(0,new SelfPayload.Slot(0,"mod:meal",count,0,0,false,food));
        slots.set(1,new SelfPayload.Slot(1,"minecraft:wheat",99,0,0,false,false));
        var payload=new SelfPayload(DIM,ID,slots,null,null,0,20f,20f,6,0f,List.of(),null,null);
        return new Observation(ObservationRequest.self(DIM,ID).keyFor("world"),payload,"self",Origin.DIRECT,
                1,at,Coverage.COMPLETE,Freshness.CURRENT,rev,1,"session");
    }
    private static WorldObservationService.Response read(InMemoryObservationStore store,long now) {
        return SelfSampleReader.read(store.all(),TopicFreshnessPolicy.defaults(),"world","session","test",ID,3000,0,now);
    }
    private static JsonObject derived(WorldObservationService.Response response) {
        return new Gson().toJsonTree(SelfObservationProjection.toolData(response)).getAsJsonObject().getAsJsonObject("derived");
    }
    @Test void realStoreRevisionInvalidationAndReadRecoveryReachTypedToolOutput() {
        long now=System.currentTimeMillis(); var store=new InMemoryObservationStore(16);
        assertTrue(store.put(sample(3,true,1,now)));
        var first=derived(read(store,now));
        assertEquals(3,first.get("foodCount").getAsInt()); assertEquals(102,first.get("total").getAsInt());
        assertTrue(store.put(sample(0,true,2,now)));
        assertFalse(store.put(sample(9,true,1,now)));
        assertEquals(0,derived(read(store,now)).get("foodCount").getAsInt());
        store.invalidate(sample(0,true,2,now).key());
        assertEquals("UNKNOWN",derived(read(store,now)).get("foodState").getAsString());
        assertTrue(store.put(sample(4,true,3,now)));
        assertEquals(4,derived(read(store,now)).get("foodCount").getAsInt());
    }
    @Test void oldFoodUnknownKeepsTotalAndUnchangedOriginalEnvelope() {
        long now=System.currentTimeMillis(); var store=new InMemoryObservationStore(16);
        store.put(sample(3,null,1,now)); var response=read(store,now);
        var data=SelfObservationProjection.toolData(response); var projected=derived(response);
        assertEquals("KNOWN",projected.get("state").getAsString()); assertEquals(102,projected.get("total").getAsInt());
        assertEquals("UNKNOWN",projected.get("foodState").getAsString()); assertFalse(projected.has("foodCount"));
        assertEquals(response.items(),data.get("items")); assertEquals(response.watermark(),data.get("watermark"));
        assertTrue(response.items().getFirst().unknownFields().contains("main[0].edible"));
    }
    @Test void actualToolDeliveryDoesNotResurrectExpiredSample() {
        long now=System.currentTimeMillis(); var store=new InMemoryObservationStore(16);
        store.put(sample(3,true,1,now-4000)); var response=read(store,now);
        var value=derived(response); assertEquals("UNKNOWN",value.get("foodState").getAsString());
        assertFalse(value.has("foodCount")); assertFalse(value.has("total"));
    }
}
