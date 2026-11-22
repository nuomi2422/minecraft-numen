package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.observation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real environment renderer; observation source is a detached fixture, not Minecraft. */
class SelfInventoryDerivedConsumerTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000006");
    private static final long NOW = 100_000;
    private static WorldObservationService.Response sample(int bread, long revision, Set<String> unknown) {
        var slots = new ArrayList<Map<String,Object>>();
        for (int i=0; i<36; i++) slots.add(Map.of("slot",i,"id",i==0?"minecraft:bread":i==1?"minecraft:wheat":"", "count",i==0?bread:i==1?99:0,"edible",i==0));
        var values = Map.<String,Object>of("main",slots,"mainHand",slots.get(0),"offhand",Map.of("slot",40,"id","minecraft:bread","count",64),
                "companionUUID",ID.toString(),"dimension","minecraft:overworld","hp",20,"food",6);
        var meta = new ObservationMetadata("world","session","minecraft:overworld",ID.toString(),"self-provider","self-key",revision,NOW-100,
                ObservationMetadata.Freshness.CURRENT,"SERVER_LOADED",NOW+2900);
        return new WorldObservationService.Response(WorldObservationService.Status.OK,"self","fixture",1,WorldObservationService.Coverage.FULL,"",
                List.of(new WorldObservationService.Item(values,unknown,Set.of())),revision,meta);
    }
    @Test void onlyConfirmedDirectlyEdibleMainSlotsAreFoodReserve() {
        var output = RddEnvBlock.render(ID,sample(3,1,Set.of()),NOW);
        assertTrue(output.contains("可食储备 3件"),output);
        assertFalse(output.contains("可食储备 102件"),"wheat needs crafting, main hand is alias, offhand is separate");
    }
    @Test void inputRevisionRecomputesRealEnvironmentConsumer() {
        assertTrue(RddEnvBlock.render(ID,sample(3,1,Set.of()),NOW).contains("可食储备 3件"));
        assertTrue(RddEnvBlock.render(ID,sample(0,2,Set.of()),NOW).contains("可食储备 0件"));
    }
    @Test void maskedMainCountIsUnknownRatherThanEmptyReserve() {
        String output = RddEnvBlock.render(ID,sample(3,1,Set.of("main[0].count")),NOW);
        assertTrue(output.contains("可食储备 未知"),output);
        assertFalse(output.contains("可食储备 0件"));
    }
    @Test void expiryCannotKeepDerivedFoodCurrent() {
        var response=sample(3,1,Set.of());
        assertTrue(RddEnvBlock.render(ID,response,NOW).contains("可食储备 3件"));
        String expired=RddEnvBlock.render(ID,response,NOW+2900);
        assertTrue(expired.contains("不可用"),expired);
        assertFalse(expired.contains("可食储备 3件"));
    }
}
