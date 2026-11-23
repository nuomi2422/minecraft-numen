package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.ServerIdentityProvider;
import com.dwinovo.numen.rdd.api.AssetScope;
import com.dwinovo.numen.rdd.api.Observation;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual registry -> identity-checked home facts; malformed observation is not an empty box. */
class RddHomeReserveObservationConsumerTest {
    private static final long NOW=1_700_000_000_000L;
    private static final ServerIdentityProvider.Snapshot ID=new ServerIdentityProvider.Snapshot("world-A","session-A");
    private static AssetRegistry registry(Object items) {
        Map<String,Object> value=new LinkedHashMap<>();
        value.put("kind","base");value.put("label","home");value.put("world_id","world-A");value.put("server_session","session-A");
        value.put("dimension","minecraft:overworld");value.put("x",372);value.put("y",68);value.put("z",715);
        value.put("facilities",List.of("core_house"));if(items!=null)value.put("important_items",items);
        AssetRegistry registry=new AssetRegistry();
        registry.apply(new Observation("home-observation","world_base","controlled-cache","minecraft:overworld",NOW,value),"home",AssetScope.GLOBAL,null);
        return registry;
    }
    private static String block(AssetRegistry registry) {return RddHomeFacts.blockFor(registry,null,NOW,ID,372,68,715,"minecraft:overworld");}
    @Test void missingObservationAndConfirmedEmptyBoxDriveDifferentNextActions() {
        String absent=block(registry(null)), empty=block(registry(Map.of()));
        assertAll(
            ()->assertTrue(absent.contains("reserve=UNKNOWN"),absent),
            ()->assertTrue(absent.contains("next=scan_reserve_containers"),absent),
            ()->assertFalse(empty.contains("reserve=UNKNOWN"),empty),
            ()->assertTrue(empty.contains("next=stock_reserve_box"),empty));
    }
    @Test void measuredZeroRemainsKnownButUnreadableCountDoesNot() {
        var zero=registry(Map.of("minecraft:bread",0));
        assertTrue(RddHomeFacts.HomeView.of(zero,NOW,ID,372,68,715,"minecraft:overworld").reserve().known());
        assertTrue(block(zero).contains("next=stock_reserve_box"));
        var bad=registry(Map.of("minecraft:bread",Double.NaN));
        assertAll(
            ()->assertFalse(RddHomeFacts.HomeView.of(bad,NOW,ID,372,68,715,"minecraft:overworld").reserve().known(),"NaN must not intValue into a measured zero"),
            ()->assertTrue(block(bad).contains("next=scan_reserve_containers"),block(bad)));
    }

    @Test void invalidCountsCannotHideBehindOtherReadableEntries() {
        for(Object count:List.of(-1,Double.POSITIVE_INFINITY,1.5,Long.MAX_VALUE,"unknown")) {
            var registry=registry(Map.of("minecraft:bread",count,"minecraft:torch",8));
            var reserve=RddHomeFacts.HomeView.of(registry,NOW,ID,372,68,715,"minecraft:overworld").reserve();
            assertFalse(reserve.known(),"mixed invalid count must not become partial current evidence: "+count);
        }
    }

    @Test void unreadableItemKeyCannotBecomeKnownInventory() {
        for(Object key:List.of("", "   ",17)) {
            var registry=registry(Map.of(key,8));
            assertFalse(RddHomeFacts.HomeView.of(registry,NOW,ID,372,68,715,"minecraft:overworld").reserve().known(),
                    "invalid key must not be stringified into inventory: "+key);
        }
    }

    @Test void integralJsonNumberRemainsAValidObservation() {
        var registry=registry(Map.of("minecraft:bread",8.0));
        var reserve=RddHomeFacts.HomeView.of(registry,NOW,ID,372,68,715,"minecraft:overworld").reserve();
        assertTrue(reserve.known(),"integral Double from JSON remains valid");
        assertEquals(8,reserve.items().get("minecraft:bread"));
    }

    @Test void malformedCountCannotBecomeConfirmedEmptyReserve() {
        var registry=registry(Map.of("minecraft:bread","unknown"));String requestFacts=block(registry);
        assertAll(
            ()->assertFalse(RddHomeFacts.HomeView.of(registry,NOW,ID,372,68,715,"minecraft:overworld").reserve().known(),"malformed count is not a measured zero"),
            ()->assertTrue(requestFacts.contains("reserve=UNKNOWN"),requestFacts),
            ()->assertTrue(requestFacts.contains("next=scan_reserve_containers"),requestFacts),
            ()->assertFalse(requestFacts.contains("next=stock_reserve_box"),requestFacts));
    }
}
