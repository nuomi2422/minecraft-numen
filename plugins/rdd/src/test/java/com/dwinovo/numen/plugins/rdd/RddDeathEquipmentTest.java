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
    @Test void unresolvedWaivedEquipmentCannotBeHiddenByReadyExistingTarget() {
        var sample = RddDeathEquipment.sample(response(true,false,Set.of(),C.toString()),C,DIM,STAMP,1001);
        var data = new LinkedHashMap<>(task().data());
        data.put("prepare_review_candidates", "{\"legs\":{\"type\":\"equipment\",\"item\":\"minecraft:iron_leggings\",\"slot\":\"legs\"}}");
        var unresolved = task().withData(data);
        assertEquals(SideTaskWorldPort.PreparationState.UNKNOWN,RddDeathEquipment.facts(sample,unresolved,Map.of(),null).state());
        assertEquals(SideTaskVerdict.Kind.CONTINUE,new DeathRecoverySideTaskType().evaluate(unresolved,port(sample)).kind());
        data.put("prepare_review_decisions", "{\"legs\":\"CANCELLED\"}");
        assertEquals(SideTaskWorldPort.PreparationState.READY,RddDeathEquipment.facts(sample,task().withData(data),Map.of(),null).state());
        data.put("prepare_review_decisions", "{\"legs\":\"RECHECK\"}");
        data.put(DeathPreparation.TARGETS,"[{\"type\":\"equipment\",\"item\":\"minecraft:iron_chestplate\",\"slot\":\"chest\"},"
                + "{\"type\":\"equipment\",\"item\":\"minecraft:iron_leggings\",\"slot\":\"legs\"}]");
        assertEquals(SideTaskWorldPort.PreparationState.MISSING,RddDeathEquipment.facts(sample,task().withData(data),Map.of(),null).state());
    }
    @Test void explicitBackupQuantityMustRemainSatisfiedAfterWearing() {
        var sample = RddDeathEquipment.sample(response(true,false,Set.of(),C.toString()),C,DIM,STAMP,1001);
        assertTrue(RddDeathEquipment.condition(sample,CONDITION,null));
        assertFalse(RddDeathEquipment.condition(sample,Map.of("asset_key",CHEST,"minimum",2),null));
    }

    private static final String BOOTS = "minecraft:iron_boots", LEGS = "minecraft:iron_leggings", HELMET = "minecraft:iron_helmet";
    private static final List<String> IRON_ARMOR = List.of(BOOTS,LEGS,CHEST,HELMET);
    private static final Map<String,Object> FOUR_SLOT_CONDITION = Map.of("type","equipment","slots",
            Map.of("feet",BOOTS,"legs",LEGS,"chest",CHEST,"head",HELMET));

    private static SideTask fourSlotTask() {
        var data = new LinkedHashMap<>(task().data());
        data.put(DeathPreparation.TARGETS,"[{\"type\":\"equipment\",\"slots\":{\"feet\":\"minecraft:iron_boots\","
                + "\"legs\":\"minecraft:iron_leggings\",\"chest\":\"minecraft:iron_chestplate\",\"head\":\"minecraft:iron_helmet\"}}]");
        return task().withData(data);
    }

    private static WorldObservationService.Response withEquipment(WorldObservationService.Response base,
            List<String> worn, List<String> carried, String offhand) {
        var item = base.items().get(0);
        var values = new LinkedHashMap<>(item.values());
        List<Map<String,Object>> main = new ArrayList<>(), armor = new ArrayList<>();
        for(int i=0;i<36;i++) main.add(slot(i,i<carried.size()?carried.get(i):""));
        for(int i=0;i<4;i++) armor.add(slot(36+i,worn.get(i)));
        values.put("main",main);
        values.put("armor",armor);
        values.put("offhand",slot(40,offhand));
        return new WorldObservationService.Response(base.status(),base.topic(),base.snapshotId(),base.observedTick(),
                base.coverage(),base.reason(),List.of(new WorldObservationService.Item(values,item.unknownFields(),item.staleFields())),
                base.watermark(),base.metadata());
    }

    private static void assertFourSlotFacts(String reason, WorldObservationService.Response response,
            boolean ready) {
        var sample = RddDeathEquipment.sample(response,C,DIM,STAMP,1001);
        assertNotNull(sample,reason);
        assertAll(reason,
                () -> assertEquals(Boolean.valueOf(ready),RddDeathEquipment.condition(sample,FOUR_SLOT_CONDITION,null)),
                () -> assertEquals(ready ? SideTaskWorldPort.PreparationState.READY : SideTaskWorldPort.PreparationState.MISSING,
                        RddDeathEquipment.facts(sample,fourSlotTask(),Map.of(),null).state()));
    }

    @Test void fourWornSlotsMustMatchTogetherInOneCurrentSelfSample() {
        var base = response(false,false,Set.of(),C.toString());
        var empty = List.of("","","","");
        var missingHead = List.of(BOOTS,LEGS,CHEST,"");
        assertAll(
                () -> assertFourSlotFacts("all four worn",withEquipment(base,IRON_ARMOR,List.of(),""),true),
                () -> assertFourSlotFacts("missing head",withEquipment(base,missingHead,List.of(),""),false),
                () -> assertFourSlotFacts("helmet only",withEquipment(base,List.of("","","",HELMET),List.of(),""),false),
                () -> assertFourSlotFacts("all four carried, none worn",withEquipment(base,empty,IRON_ARMOR,""),false),
                () -> assertFourSlotFacts("boots and leggings in wrong slots",withEquipment(base,List.of(LEGS,BOOTS,CHEST,HELMET),List.of(),""),false),
                () -> assertFourSlotFacts("mainhand cannot fill head",withEquipment(base,missingHead,List.of(HELMET),""),false),
                () -> assertFourSlotFacts("offhand cannot fill head",withEquipment(base,missingHead,List.of(),HELMET),false));
    }

    @Test void maskedStaleOrWrongSubjectFourSlotSelfEvidenceRemainsUnknown() {
        var worn = withEquipment(response(false,false,Set.of(),C.toString()),IRON_ARMOR,List.of(),"");
        var stale = new WorldObservationService.Response(worn.status(),worn.topic(),worn.snapshotId(),worn.observedTick(),
                worn.coverage(),worn.reason(),worn.items(),worn.watermark(),
                worn.metadata().withFreshness(ObservationMetadata.Freshness.STALE));
        var rejected = Map.of(
                "masked armor",withEquipment(response(false,false,Set.of("armor[1].id"),C.toString()),IRON_ARMOR,List.of(),""),
                "stale sample",stale,
                "wrong subject",withEquipment(response(false,false,Set.of(),UUID.randomUUID().toString()),IRON_ARMOR,List.of(),""));
        for(var entry : rejected.entrySet()) {
            var sample = RddDeathEquipment.sample(entry.getValue(),C,DIM,STAMP,1001);
            assertNull(sample,entry.getKey());
            assertNull(RddDeathEquipment.condition(sample,FOUR_SLOT_CONDITION,null),entry.getKey());
            assertEquals(SideTaskWorldPort.PreparationState.UNKNOWN,
                    RddDeathEquipment.facts(sample,fourSlotTask(),Map.of(),null).state(),entry.getKey());
        }
    }
}
