package com.dwinovo.numen.rdd.side.death;

import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.dwinovo.numen.rdd.policy.AssetSemantics;
import com.dwinovo.numen.rdd.side.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-12: real obligation selection/engine settlement, frozen against 9dbd scope defects. */
class DeathPreparationScopeRegressionTest {
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000dc");
    private static final String IRON = "minecraft:iron_chestplate", DIAMOND = "minecraft:diamond_chestplate";
    private static Map<String,Object> wear(String key) { return Map.of("type","equipment","item",key,"slot","chest"); }
    private static final class Port implements SideTaskWorldPort {
        long now = 1000;
        DeathDropState drop = DeathDropState.DROPS_ABSENT;
        public long gameTime(){return now;} public long dayTime(){return 1000;} public long gameDay(){return 0;}
        public boolean hasDayNightCycle(){return true;} public String worldId(){return "minecraft:overworld";}
        public boolean isSleeping(UUID id){return false;} public boolean isDay(){return true;}
        public Wake pollWake(UUID id){return Wake.NONE;}
        public DeathRecoveryFacts deathRecoveryFacts(UUID id,SideTask task){return new DeathRecoveryFacts(drop,false,false,true,"loaded");}
        public PreparationFacts deathPreparationFacts(UUID id,SideTask task){return PreparationFacts.unknown("self missing");}
    }
    private static MainlineResumeToken token(TaskChain c) {
        return new MainlineResumeToken(C,c.chainInstanceId(),c.goal().id(),c.currentPrimary().id(),c.currentSubtask().id(),c.planRevision(),1);
    }
    private static TaskChain trading() {
        var c=new TaskChain(new Goal("g","trade",List.of(new PrimaryGoal("p","trade",List.of(
                Subtask.hardCoded("iron","iron",Map.of("asset_key","minecraft:iron_ingot","minimum",8)),
                Subtask.hardCoded("emerald","emerald",Map.of("asset_key","minecraft:emerald","minimum",20)))))));
        c.startCurrent(); return c;
    }
    private static SideTaskEngine start(TaskChain c,Port p,List<Map<String,Object>> terminal,Map<String,AssetSemantics> semantics) {
        var engine=new SideTaskEngine((event,data)->{if(event.equals("side_task_completed")||event.equals("side_task_skipped"))terminal.add(data);},t->t);
        engine.registerType(new DeathRecoverySideTaskType());
        assertTrue(engine.startNow(new DeathRecoverySideTaskType(),C,p,token(c),Map.of("death_seq","7")));
        assertTrue(DeathPreparation.attach(engine,C,c,semantics,Map.of(IRON,"chest",DIAMOND,"chest")));
        return engine;
    }
    @Test void noEquipmentObligationKeepsOriginalAbsentDropSettlementAndDoesNotPauseTrading() {
        var c=trading(); var p=new Port(); var terminal=new ArrayList<Map<String,Object>>(); var engine=start(c,p,terminal,Map.of());
        String before=c.toJson();
        DeathPreparation.tick(engine,C,c,p,(o,h,d)->fail("no declared equipment obligation to plan"));
        engine.tick(C,p,token(c),false);
        assertFalse(engine.isActive(C),"reliably no equipment obligation must use the original drop settlement");
        assertEquals(RecoveryTerminal.CONFIRMED_ABSENT,terminal.get(0).get("deathRecoveryTerminalReason"));
        assertFalse(terminal.get(0).containsKey("prepareResume"),"no equipment takeover must not suppress original loss analysis/replan or create a recovery pause");
        assertEquals(before,c.toJson());
    }
    @Test void explicitPrepareWithoutEquipmentObligationDoesNotInventAnUnreleasablePause() {
        var c=trading(); var p=new Port(); p.drop=SideTaskWorldPort.DeathDropState.RECOVERED;
        var terminal=new ArrayList<Map<String,Object>>(); var engine=start(c,p,terminal,Map.of());
        assertTrue(DeathPreparation.choose(engine,C,DeathRecoveryDecision.PREPARE_AND_REPLAN));
        DeathPreparation.tick(engine,C,c,p,(o,h,d)->fail("no declaration is not zero inventory"));
        engine.tick(C,p,token(c),false);
        assertFalse(engine.isActive(C));
        assertFalse(terminal.get(0).containsKey("prepareResume"));
        assertEquals(RecoveryTerminal.RECOVERED,terminal.get(0).get("deathRecoveryTerminalReason"));
        assertFalse(c.isPaused("iron"));
    }
    @Test void currentMiningDoesNotRequireLaterUnreachedEquipmentOrItsSemanticEntry() {
        var c=new TaskChain(new Goal("g","iron equipment",List.of(new PrimaryGoal("p","iron stage",List.of(
                Subtask.hardCoded("mine","get iron",Map.of("asset_key","minecraft:iron_ingot","minimum",8)),
                Subtask.hardCoded("future-wear","future wear",wear(IRON)))))));
        c.startCurrent(); var p=new Port(); var terminal=new ArrayList<Map<String,Object>>();
        var semantic=new AssetSemantics(IRON,AssetSemantics.Importance.IMPORTANT,AssetSemantics.Nature.EQUIPMENT,
                "g","1","declaration",List.of());
        var engine=start(c,p,terminal,Map.of(IRON,semantic));
        DeathPreparation.tick(engine,C,c,p,(o,h,d)->fail("future loadout must not become a recovery plan"));
        engine.tick(C,p,token(c),false);
        assertFalse(engine.isActive(C));
        assertFalse(terminal.get(0).containsKey("prepareResume"));
        assertEquals("mine",c.currentSubtask().id());
    }
    @Test void futureDiamondTierCannotReplaceHistoricalIronObligationAndUnknownStillHolds() {
        var c=new TaskChain(new Goal("g","finish",List.of(
                new PrimaryGoal("gear","iron equipment",List.of(Subtask.hardCoded("old-wear","wear iron",wear(IRON)))),
                new PrimaryGoal("p","trade then upgrade",List.of(
                    Subtask.hardCoded("trade","trade",Map.of("asset_key","minecraft:emerald","minimum",20)),
                    Subtask.hardCoded("future-diamond","later upgrade",wear(DIAMOND)))))));
        c.startCurrent(); c.applyHardCodedResult("old-wear",true); c.settlePrimaryByFact("gear","verified",true); c.startCurrent();
        var p=new Port(); var terminal=new ArrayList<Map<String,Object>>(); var engine=start(c,p,terminal,Map.of());
        var targets=DeathPreparation.conditions(engine.current(C).orElseThrow(),DeathPreparation.TARGETS);
        assertEquals(1,targets.size()); assertEquals(IRON,targets.get(0).get("item"));
        DeathPreparation.choose(engine,C,DeathRecoveryDecision.PREPARE_AND_REPLAN);
        DeathPreparation.tick(engine,C,c,p,(o,h,d)->fail("unknown observation must not be treated as absent equipment"));
        engine.tick(C,p,token(c),false); assertTrue(engine.isActive(C));
        p.now+=DeathRecoverySideTaskType.DEADLINE_TICKS;
        engine.tick(C,p,token(c),false); assertFalse(engine.isActive(C));
        String payload=String.valueOf(terminal.get(0).get("prepareResume"));
        assertTrue(DeathPreparation.settle(c,payload,false,"self missing"));
        assertTrue(c.isPaused("trade")); assertTrue(DeathPreparation.holdPaused(c,C,p));
    }
}
