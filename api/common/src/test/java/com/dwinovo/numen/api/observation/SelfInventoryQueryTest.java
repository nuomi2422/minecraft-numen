package com.dwinovo.numen.api.observation;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SelfInventoryQueryTest {
    private static ObservationMetadata meta(long rev, String session, long until) {
        return new ObservationMetadata("world",session,"dim","subject","self","key",rev,1000,
                ObservationMetadata.Freshness.CURRENT,"SERVER_LOADED",until);
    }
    private static WorldObservationService.Item item(int bread, Boolean food, Set<String> unknown) {
        var slots=new ArrayList<Map<String,Object>>();
        for(int i=0;i<36;i++) {
            var slot=new HashMap<String,Object>();
            slot.put("slot",i); slot.put("id",i==0?"mod:meal":i==1?"minecraft:wheat":"");
            slot.put("count",i==0?bread:i==1?99:0);
            if(food!=null) slot.put("edible",i==0 && food);
            slots.add(slot);
        }
        return new WorldObservationService.Item(Map.of("companionUUID","subject","dimension","dim","main",slots),unknown,Set.of());
    }
    @Test void componentFoodAndTotalsHaveDifferentMeanings() {
        var result=SelfInventoryQuery.query(meta(1,"s",4000),item(3,true,Set.of()),1100,Set.of());
        assertEquals(102L,result.total()); assertEquals(3L,result.foodCount());
        assertEquals("main36",result.data().get("scope")); assertEquals("items",result.data().get("unit"));
        assertThrows(UnsupportedOperationException.class,()->result.counts().put("fake",9L));
    }
    @Test void legacyFoodUnknownDoesNotEraseKnownTotal() {
        var result=SelfInventoryQuery.query(meta(1,"s",4000),item(3,null,Set.of()),1100,Set.of());
        assertTrue(result.known()); assertEquals(102L,result.total()); assertFalse(result.foodKnown());
        assertNull(result.foodCount()); assertFalse(result.data().containsKey("foodCount"));
    }
    @Test void revisionParametersAndSameRevisionComponentChangeNeverReuseOtherAnswer() {
        var m=meta(1,"s",4000); var first=item(3,true,Set.of());
        var result=SelfInventoryQuery.query(m,first,1100,Set.of());
        assertSame(result,SelfInventoryQuery.query(m,first,1101,Set.of()));
        assertEquals(3L,SelfInventoryQuery.query(m,first,1100,Set.of("mod:meal")).total());
        assertEquals(99L,SelfInventoryQuery.query(m,first,1100,Set.of("minecraft:wheat")).total());
        assertEquals(0L,SelfInventoryQuery.query(meta(2,"s",4000),item(0,true,Set.of()),1100,Set.of()).foodCount());
        assertEquals(0L,SelfInventoryQuery.query(m,item(3,false,Set.of()),1100,Set.of()).foodCount());
    }
    @Test void expiryMaskAndRecoveryAreCheckedBeforeCacheHit() {
        var m=meta(1,"s",4000); var first=item(3,true,Set.of());
        assertTrue(SelfInventoryQuery.query(m,first,3999,Set.of()).foodKnown());
        assertFalse(SelfInventoryQuery.query(m,first,4000,Set.of()).known());
        var masked=SelfInventoryQuery.query(m,item(3,true,Set.of("main[0].count")),1100,Set.of());
        assertFalse(masked.known()); assertNull(masked.total());
        var foodMasked=SelfInventoryQuery.query(m,item(3,true,Set.of("main[0].edible")),1100,Set.of());
        assertTrue(foodMasked.known()); assertFalse(foodMasked.foodKnown());
        assertEquals(3L,SelfInventoryQuery.query(meta(3,"s",5000),first,4100,Set.of()).foodCount());
    }
    @Test void inputSessionAndPayloadIdentityAreNotInterchangeable() {
        var a=SelfInventoryQuery.query(meta(1,"a",4000),item(3,true,Set.of()),1100,Set.of());
        var b=SelfInventoryQuery.query(meta(1,"b",4000),item(4,true,Set.of()),1100,Set.of());
        assertNotSame(a,b); assertEquals(4L,b.foodCount()); assertEquals("b",b.input().serverSession());
        var wrong=new WorldObservationService.Item(Map.of("companionUUID","someone","dimension","dim"),Set.of(),Set.of());
        assertFalse(SelfInventoryQuery.query(meta(1,"a",4000),wrong,1100,Set.of()).known());
    }
    @Test void serializedParametersDistinguishFilteredAndWholeInventory() {
        var m=meta(1,"s",4000); var source=item(3,true,Set.of());
        var all=SelfInventoryQuery.query(m,source,1100,Set.of()).data();
        var onlyMeal=SelfInventoryQuery.query(m,source,1100,Set.of("mod:meal")).data();
        assertEquals(Map.of("selection","ALL","ids",List.of()),all.get("parameters"));
        assertEquals(Map.of("selection","EXACT_IDS","ids",List.of("mod:meal")),onlyMeal.get("parameters"));
        var both=SelfInventoryQuery.query(m,source,1100,new LinkedHashSet<>(List.of("mod:meal","minecraft:wheat"))).data();
        assertEquals(Map.of("selection","EXACT_IDS","ids",List.of("minecraft:wheat","mod:meal")),both.get("parameters"));
        assertNotEquals(all.get("parameters"),onlyMeal.get("parameters"));
        var wire=new com.google.gson.Gson().toJsonTree(onlyMeal).getAsJsonObject();
        assertEquals("EXACT_IDS",wire.getAsJsonObject("parameters").get("selection").getAsString());
        assertEquals("mod:meal",wire.getAsJsonObject("parameters").getAsJsonArray("ids").get(0).getAsString());
        assertEquals(all.get("input"),onlyMeal.get("input"));
        var expired=SelfInventoryQuery.query(m,source,4000,Set.of("mod:meal")).data();
        assertEquals(onlyMeal.get("parameters"),expired.get("parameters"));
        assertEquals("UNKNOWN",expired.get("state"));
        assertFalse(expired.containsKey("total"));
    }
    @Test void missingSourceCannotReusePreviouslyKnownAnswer() {
        assertFalse(SelfInventoryQuery.read(null,1100).known());
        assertFalse(SelfInventoryQuery.read(WorldObservationService.Response.error("self","unavailable"),1100).known());
    }
}
