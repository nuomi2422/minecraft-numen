package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.observation.*;
import com.dwinovo.numen.rdd.side.*;
import com.dwinovo.numen.rdd.side.death.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-12: actual SELF projection -> current slots -> death strategy completion. */
class RddDeathEquipmentTest {
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000dc");
    private static final String DIM = "minecraft:overworld", CHEST = "minecraft:iron_chestplate";
    private static final Map<String,String> STAMP = Map.of("world_id", "world", "server_session", "session");
    private static final Map<String,Object> CONDITION = Map.of("type","equipment","item",CHEST,"slot","chest");
    private static Map<String,Object> slot(int index, String item) {
        return Map.of("slot",index,"id",item,"count",item.isEmpty()?0:1);
    }
    private static WorldObservationService.Response response(boolean worn, boolean carried, Set<String> unknown, String subject) {
        List<Map<String,Object>> main = new ArrayList<>(), armor = new ArrayList<>();
        for(int i=0;i<36;i++) main.add(slot(i,carried&&i==1?CHEST:""));
        for(int i=0;i<4;i++) armor.add(slot(36+i,worn&&i==2?CHEST:""));
        var values = Map.<String,Object>of("dimension",DIM,"companionUUID",subject,"main",main,"armor",armor,
                "offhand",slot(40,""),"selected",0);
        return new WorldObservationService.Response(WorldObservationService.Status.OK,"self","snapshot",1,
                WorldObservationService.Coverage.FULL,"",List.of(new WorldObservationService.Item(values,unknown,Set.of())),0,
                new ObservationMetadata("world","session",DIM,subject,"self","self-key",1,1000,
                        ObservationMetadata.Freshness.CURRENT,"INTERNAL",4000));
    }
    private static SideTask task() {
        return new SideTask(C,"death-7",DeathRecoverySideTaskType.TYPE_ID,1,0,0,24000,
                SideTaskState.ACTIVE,DeathRecoverySideTaskType.PHASE_REEQUIP,
                Map.of("death_seq","7","decision","PREPARE_AND_REPLAN",DeathPreparation.TARGETS,
                        "[{\"type\":\"equipment\",\"item\":\"minecraft:iron_chestplate\",\"slot\":\"chest\"}]"),null);
    }
    @Test void carriedChestplateIsNotWornAndRecoveredDoesNotBypassStrategy() {
        var sample = RddDeathEquipment.sample(response(false,true,Set.of(),C.toString()),C,DIM,STAMP,1001);
        assertNotNull(sample);
        assertFalse(RddDeathEquipment.condition(sample,CONDITION,null));
        var facts = RddDeathEquipment.facts(sample,task(),Map.of(CHEST,1),null);
        assertEquals(SideTaskWorldPort.PreparationState.MISSING,facts.state());
        var world = port(sample);
        assertEquals(SideTaskVerdict.Kind.CONTINUE,new DeathRecoverySideTaskType().evaluate(task(),world).kind());
        var worn = RddDeathEquipment.sample(response(true,false,Set.of(),C.toString()),C,DIM,STAMP,1001);
        assertEquals(SideTaskVerdict.Kind.COMPLETE,new DeathRecoverySideTaskType().evaluate(task(),port(worn)).kind());
    }
    private static SideTaskWorldPort port(RddDeathEquipment.Sample sample) {
        return new SideTaskWorldPort() {
            public long gameTime(){return 1000;} public long dayTime(){return 1000;} public long gameDay(){return 0;}
            public boolean hasDayNightCycle(){return true;} public String worldId(){return DIM;}
            public boolean isSleeping(UUID id){return false;} public boolean isDay(){return true;}
            public Wake pollWake(UUID id){return Wake.NONE;}
            public DeathRecoveryFacts deathRecoveryFacts(UUID id,SideTask t){return new DeathRecoveryFacts(DeathDropState.RECOVERED,false,false,true,"");}
            public PreparationFacts deathPreparationFacts(UUID id,SideTask t){return RddDeathEquipment.facts(sample,t,Map.of(),null);}
        };
    }
    @Test void unknownSlotWrongSubjectAndExpiredSamplesAreNotZero() {
        assertNull(RddDeathEquipment.sample(response(false,false,Set.of("armor[2].id"),C.toString()),C,DIM,STAMP,1001));
        assertNull(RddDeathEquipment.sample(response(true,false,Set.of(),UUID.randomUUID().toString()),C,DIM,STAMP,1001));
        assertNull(RddDeathEquipment.sample(response(true,false,Set.of(),C.toString()),C,DIM,STAMP,4000));
        assertEquals(SideTaskWorldPort.PreparationState.UNKNOWN,RddDeathEquipment.facts(null,task(),Map.of(),null).state());
    }
    @Test void absentEquipmentSampleRequestsExistingSelfProviderRefreshNotFallbackScan() {
        AtomicInteger refreshes = new AtomicInteger();
        WorldObservationService service = new WorldObservationService() {
            public Response read(Request request){return Response.error("self","not sampled");}
            public Response refresh(Request request){refreshes.incrementAndGet();assertEquals("self",request.topic());return Response.error("self","unavailable");}
        };
        assertNull(RddDeathEquipment.read(service,C,DIM,STAMP,1001));
        assertEquals(1,refreshes.get());
    }
    @Test void explicitBackupQuantityMustRemainSatisfiedAfterWearing() {
        var sample = RddDeathEquipment.sample(response(true,false,Set.of(),C.toString()),C,DIM,STAMP,1001);
        assertTrue(RddDeathEquipment.condition(sample,CONDITION,null));
        assertFalse(RddDeathEquipment.condition(sample,Map.of("asset_key",CHEST,"minimum",2),null));
    }
}
