package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.observation.ObservationMetadata;
import com.dwinovo.numen.api.observation.WorldObservationService;
import com.dwinovo.numen.rdd.side.*;
import com.dwinovo.numen.rdd.side.sleep.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-12 sleep-only production host seam regression: real registered Engine ->
 * production SleepSafety -> existing interruption callback -> active context/token.
 * No simulated Minecraft body or alternate engine. The adapter itself still requires live validation.
 * Unlike a nudge assertion, the cancellation callback must actually be consumed exactly once.
 */
class SleepRiskHostTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000ac");
    private static final MainlineResumeToken TOKEN = new MainlineResumeToken(ID,"chain","goal","primary","step",1,1);
    @Test void riskChangeInterruptsOriginalActionOnceAndPreservesResume() {
        Host h = new Host(); h.tick(); assertFalse(h.engine.isActive(ID));
        h.risk = SideTaskWorldPort.SleepRisk.HOSTILES; h.now += 20; h.tick();
        assertTrue(h.engine.isActive(ID)); assertEquals(1, h.stops); assertFalse(h.busy);
        SideTask task = h.engine.current(ID).orElseThrow(); assertEquals(TOKEN, task.mainlineToken());
        String block = new SleepSideTaskType().renderContext(task, h);
        assertTrue(block.contains("庇护")); assertTrue(block.contains("先脱离"));
        for(int i=0;i<5;i++) {h.now+=20; h.tick();}
        assertEquals(1,h.stops,"active sleep never repeatedly cancels the body");
        h.engine.decline(ID,"already sheltered",h.worldId(),0);
        assertFalse(h.engine.isActive(ID));
        assertEquals(Boolean.TRUE,h.resumed,"original token remains valid after side settlement");
    }
    @Test void externalUnknownAndSurvivalOwnersRetainBody() {
        assertFalse(SideTaskHost.SleepSafety.mayInterrupt(true,"mine"));
        for(String tool:List.of("attack","flee","goto","eat","sleep","unknown",""))
            assertFalse(SideTaskHost.SleepSafety.mayInterrupt(false,tool),tool);
        assertTrue(SideTaskHost.SleepSafety.mayInterrupt(false,"mine"));
        Host h=new Host(); h.eligible=false; h.risk=SideTaskWorldPort.SleepRisk.HOSTILES;
        h.tick(); assertFalse(h.engine.isActive(ID)); assertEquals(0,h.stops);
    }
    @Test void combatYieldsToImmediateThreatThenReevaluatesBeforeCombatDeferExpires() {
        Host h=new Host(); h.risk=SideTaskWorldPort.SleepRisk.HOSTILES; h.combat=true;
        h.tick(); assertFalse(h.engine.isActive(ID)); assertEquals(0,h.stops);
        h.combat=false; h.now+=20; h.tick(); assertTrue(h.engine.isActive(ID)); assertEquals(1,h.stops);
    }
    @Test void safeBusyNightIntentDeathAndExistingSleepDoNotCancel() {
        Host safe=new Host(); safe.risk=SideTaskWorldPort.SleepRisk.CLEAR;
        for(int i=0;i<4;i++){safe.tick();safe.now+=20;} assertEquals(0,safe.stops);
        Host night=new Host(); night.risk=SideTaskWorldPort.SleepRisk.HOSTILES; night.objective="夜间活动";
        night.tick(); assertEquals(0,night.stops); assertFalse(night.engine.isActive(ID));
        Host death=new Host(); death.risk=SideTaskWorldPort.SleepRisk.HOSTILES; death.death=true;
        death.tick(); assertEquals(0,death.stops); assertFalse(death.engine.isActive(ID));
        Host sleeping=new Host(); sleeping.busy=false; sleeping.tick(); sleeping.busy=true;
        sleeping.risk=SideTaskWorldPort.SleepRisk.HOSTILES; sleeping.tick(); assertEquals(0,sleeping.stops);
    }
    @Test void changedOwnerAtHandoffCancelsSideWithoutRepeatedStop() {
        Host h = new Host(); h.risk = SideTaskWorldPort.SleepRisk.HOSTILES;
        int[] attempts = {0};
        SideTaskHost.SleepSafety.tick(h.engine,ID,h,TOKEN,false,()->{attempts[0]++;return false;});
        assertFalse(h.engine.isActive(ID)); assertEquals(1,attempts[0]);
        h.tick(); assertEquals(0,h.stops,"same occurrence cannot repeatedly cancel a replacement");
    }
    @Test void staleOrUnknownOrDifferentWorldFactsNeverBecomeAutomaticPreemption() {
        for(var freshness:List.of(ObservationMetadata.Freshness.UNKNOWN,ObservationMetadata.Freshness.STALE))
            assertEquals(SideTaskWorldPort.SleepRisk.UNKNOWN,risk(response(1,freshness,2000,Set.of(),WorldObservationService.Coverage.FULL),1500));
        assertEquals(SideTaskWorldPort.SleepRisk.UNKNOWN,risk(response(1,ObservationMetadata.Freshness.CURRENT,1400,Set.of(),WorldObservationService.Coverage.FULL),1500));
        assertEquals(SideTaskWorldPort.SleepRisk.UNKNOWN,SideTaskHost.SleepSafety.risk(response(1,ObservationMetadata.Freshness.CURRENT,2000,Set.of(),WorldObservationService.Coverage.FULL),"other","s","dim",1500));
        assertEquals(SideTaskWorldPort.SleepRisk.UNKNOWN,risk(response(1,ObservationMetadata.Freshness.CURRENT,2000,Set.of("count"),WorldObservationService.Coverage.FULL),1500));
        assertEquals(SideTaskWorldPort.SleepRisk.UNKNOWN,risk(response(0,ObservationMetadata.Freshness.CURRENT,2000,Set.of(),WorldObservationService.Coverage.PARTIAL),1500));
        assertEquals(SideTaskWorldPort.SleepRisk.HOSTILES,risk(response(1,ObservationMetadata.Freshness.CURRENT,2000,Set.of(),WorldObservationService.Coverage.PARTIAL),1500));
        assertEquals(SideTaskWorldPort.SleepRisk.CLEAR,risk(response(0,ObservationMetadata.Freshness.CURRENT,2000,Set.of(),WorldObservationService.Coverage.FULL),1500));
    }
    private static SideTaskWorldPort.SleepRisk risk(WorldObservationService.Response r,long now){return SideTaskHost.SleepSafety.risk(r,"w","s","dim",now);}
    private static WorldObservationService.Response response(int count,ObservationMetadata.Freshness freshness,long expiry,Set<String> unknown,WorldObservationService.Coverage coverage){
        return new WorldObservationService.Response(WorldObservationService.Status.OK,"entities","snap",100,coverage,null,
                List.of(new WorldObservationService.Item(Map.of("count",count),unknown,Set.of())),0,
                new ObservationMetadata("w","s","dim","","provider","entities/box",1,1000,freshness,"SERVER_LOADED",expiry));
    }
    private static final class Host implements SideTaskWorldPort {
        long now=14000; boolean busy=true,eligible=true,combat,death; int stops; Boolean resumed;
        String objective="收集木材"; SleepRisk risk=SleepRisk.UNKNOWN;
        SideTaskEngine engine=new SideTaskEngine((event,data)->{if(event.equals("side_task_resumed_mainline"))resumed=(Boolean)data.get("mainlineResumed");}, t->t);
        Host(){engine.registerType(new SleepSideTaskType());engine.registerTrigger(new SleepSideTaskTrigger());}
        void tick(){SideTaskHost.SleepSafety.tick(engine,ID,this,TOKEN,death,()->{stops++;busy=false;return true;});}
        public long gameTime(){return now;} public long dayTime(){return now;} public long gameDay(){return 0;}
        public boolean hasDayNightCycle(){return true;} public String worldId(){return "minecraft:overworld";}
        public boolean isSleeping(UUID id){return false;} public boolean isDay(){return false;}
        public Wake pollWake(UUID id){return Wake.NONE;} public boolean bodyBusy(UUID id){return busy;}
        public boolean inCombat(UUID id){return combat;} public SleepRisk sleepRisk(UUID id){return risk;}
        public boolean sleepMayInterrupt(UUID id){return eligible;} public String mainlineObjective(UUID id){return objective;}
    }
}
