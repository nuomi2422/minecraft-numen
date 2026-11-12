package com.dwinovo.numen.rdd.side.death;

import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.dwinovo.numen.rdd.side.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-12: real strategy -> plan callback -> condition -> engine terminal -> original mainline. */
class DeathPreparationFlowTest {
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000dc");
    private static final Map<String, Object> CHEST = Map.of("type", "equipment", "item", "minecraft:iron_chestplate", "slot", "chest");
    private static final class Port implements SideTaskWorldPort {
        long now = 1000;
        PreparationState preparation = PreparationState.MISSING;
        Map<String, Integer> backup = Map.of();
        public long gameTime() { return now; }
        public long dayTime() { return 1000; }
        public long gameDay() { return 0; }
        public boolean hasDayNightCycle() { return true; }
        public String worldId() { return "minecraft:overworld"; }
        public boolean isSleeping(UUID id) { return false; }
        public boolean isDay() { return true; }
        public Wake pollWake(UUID id) { return Wake.NONE; }
        public DeathRecoveryFacts deathRecoveryFacts(UUID id, SideTask task) {
            return new DeathRecoveryFacts(DeathDropState.DROPS_ABSENT, false, !backup.isEmpty(), true, "loaded absent");
        }
        public PreparationFacts deathPreparationFacts(UUID id, SideTask task) {
            return new PreparationFacts(preparation, preparation.name(), Map.of("minecraft:carrot", 23), backup);
        }
        public Boolean deathPreparationCondition(UUID id, Map<String, Object> condition) {
            return preparation == PreparationState.UNKNOWN ? null : preparation == PreparationState.READY;
        }
    }
    private static TaskChain chain() {
        TaskChain chain = new TaskChain(new Goal("g", "finish", List.of(
                new PrimaryGoal("gear", "equipment", List.of(Subtask.hardCoded("wear", "wear chest", CHEST))),
                new PrimaryGoal("trade", "trade", List.of(
                        Subtask.hardCoded("iron", "iron eight", Map.of("asset_key", "minecraft:iron_ingot", "minimum", 8)),
                        Subtask.hardCoded("emerald", "emerald twenty", Map.of("asset_key", "minecraft:emerald", "minimum", 20)))))));
        chain.startCurrent();
        chain.applyHardCodedResult("wear", true);
        chain.settlePrimaryByFact("gear", "current equipment verified", true);
        chain.startCurrent();
        return chain;
    }
    private static MainlineResumeToken token(TaskChain chain) {
        return new MainlineResumeToken(C, chain.chainInstanceId(), chain.goal().id(), chain.currentPrimary().id(),
                chain.currentSubtask().id(), chain.planRevision(), 1);
    }
    private static SideTaskEngine engine(TaskChain chain, Port port, List<Map<String, Object>> settled) {
        var engine = new SideTaskEngine((event, data) -> {
            if (event.equals("side_task_completed") || event.equals("side_task_skipped")) settled.add(data);
        }, value -> value);
        engine.registerType(new DeathRecoverySideTaskType());
        assertTrue(engine.startNow(new DeathRecoverySideTaskType(), C, port, token(chain), Map.of("death_seq", "7")));
        assertTrue(DeathPreparation.attach(engine, C, chain, Map.of(), Map.of()));
        return engine;
    }
    @Test void priorEquipmentSurvivesTradingManifestAndPlansOnlyRecovery() {
        var chain = chain(); var port = new Port(); var events = new ArrayList<Map<String, Object>>();
        var engine = engine(chain, port, events);
        String original = chain.toJson();
        assertTrue(DeathPreparation.choose(engine, C, DeathRecoveryDecision.PREPARE_AND_REPLAN));
        AtomicInteger calls = new AtomicInteger();
        DeathPreparation.tick(engine, C, chain, port, (objective, hint, done) -> {
            calls.incrementAndGet();
            assertTrue(objective.contains("恢复当前装备能力"));
            assertFalse(objective.contains("trade"));
            assertTrue(hint.contains("iron_chestplate"));
            assertTrue(hint.contains("DROPS_ABSENT"));
            done.accept(List.of(new SubtaskSpec("wear the replacement chest", CHEST, null)));
        });
        DeathPreparation.tick(engine, C, chain, port, (o, h, d) -> fail("duplicate plan"));
        assertEquals(1, calls.get());
        assertTrue(engine.current(C).orElseThrow().data().containsKey(DeathPreparation.PLAN));
        assertEquals(original, chain.toJson(), "no current trading replacement, cursor movement or history rollback");
        engine.tick(C, port, token(chain), false);
        assertTrue(engine.isActive(C));
        port.preparation = SideTaskWorldPort.PreparationState.READY;
        DeathPreparation.tick(engine, C, chain, port, null);
        engine.tick(C, port, token(chain), false);
        assertFalse(engine.isActive(C));
        assertEquals("true", events.get(0).get("prepareVerified"));
        assertTrue(DeathPreparation.settle(chain, String.valueOf(events.get(0).get("prepareResume")), true, "ready"));
        assertEquals(original, chain.toJson());
        assertEquals("iron", chain.currentSubtask().id());
    }
    @Test void automaticAbsenceAlsoPlansAndUnknownNeverBecomesEmpty() {
        var chain = chain(); var port = new Port(); var engine = engine(chain, port, new ArrayList<>());
        port.preparation = SideTaskWorldPort.PreparationState.UNKNOWN;
        DeathPreparation.tick(engine, C, chain, port, (o,h,d) -> fail("unknown cannot request manufacturing"));
        engine.tick(C, port, token(chain), false);
        assertTrue(engine.isActive(C));
        port.preparation = SideTaskWorldPort.PreparationState.MISSING;
        DeathPreparation.tick(engine, C, chain, port, (o,h,d) -> d.accept(List.of(new SubtaskSpec("equip", CHEST, null))));
        assertEquals("READY", engine.current(C).orElseThrow().data().get("prepare_plan_state"));
    }
    @Test void readyEquipmentDoesNotRebuildAndBackupIsGivenToPlanner() {
        var chain = chain(); var port = new Port(); var engine = engine(chain, port, new ArrayList<>());
        DeathPreparation.choose(engine, C, DeathRecoveryDecision.FETCH_BACKUP_THEN_CONTINUE);
        port.preparation = SideTaskWorldPort.PreparationState.READY;
        DeathPreparation.tick(engine, C, chain, port, (o,h,d) -> fail("already equipped; do not rebuild"));
        port.preparation = SideTaskWorldPort.PreparationState.MISSING;
        port.backup = Map.of("minecraft:iron_chestplate", 1);
        DeathPreparation.tick(engine, C, chain, port, (o,h,d) -> {
            assertTrue(h.contains("iron_chestplate=1"));
            assertTrue(h.contains("不重复制造"));
            d.accept(List.of(new SubtaskSpec("fetch then wear backup", CHEST, null)));
        });
    }
    @Test void staleChainRevisionAndDeathBatchCannotInstallCallback() {
        var chain = chain(); var port = new Port(); var engine = engine(chain, port, new ArrayList<>());
        DeathPreparation.choose(engine, C, DeathRecoveryDecision.PREPARE_AND_REPLAN);
        AtomicReference<Consumer<List<SubtaskSpec>>> callback = new AtomicReference<>();
        DeathPreparation.tick(engine, C, chain, port, (o,h,d) -> callback.set(d));
        SideTask requested = engine.current(C).orElseThrow();
        String request = requested.data().get("prepare_request");
        assertFalse(DeathPreparation.install(engine, C, chain(), requested, request, List.of(new SubtaskSpec("equip", CHEST, null))));
        var changed = new LinkedHashMap<>(requested.data()); changed.put("death_seq", "8");
        engine.runtime().replace(requested.withData(changed));
        callback.get().accept(List.of(new SubtaskSpec("old callback", CHEST, null)));
        assertFalse(engine.current(C).orElseThrow().data().containsKey(DeathPreparation.PLAN));
        engine.runtime().replace(requested);
        chain.nextPlanRevision();
        assertFalse(DeathPreparation.install(engine, C, chain, requested, request, List.of(new SubtaskSpec("equip", CHEST, null))));
    }
    @Test void rejectedPlanAndDeadlinePauseWithoutDroppingHistoryAndResumeOnlyWhenReady() {
        var chain = chain(); var port = new Port(); var events = new ArrayList<Map<String, Object>>();
        var engine = engine(chain, port, events);
        var originalSubtasks = chain.currentPrimary().subtasks(); var history = chain.satisfiedStages();
        DeathPreparation.choose(engine, C, DeathRecoveryDecision.PREPARE_AND_REPLAN);
        DeathPreparation.tick(engine, C, chain, port, (o,h,d) -> d.accept(List.of()));
        assertEquals("REJECTED", engine.current(C).orElseThrow().data().get("prepare_plan_state"));
        port.now += DeathRecoverySideTaskType.DEADLINE_TICKS;
        engine.tick(C, port, token(chain), false);
        assertFalse(engine.isActive(C));
        String payload = String.valueOf(events.get(0).get("prepareResume"));
        assertEquals("false", events.get(0).get("prepareVerified"));
        assertTrue(DeathPreparation.settle(chain, payload, false, "plan unavailable"));
        long version = chain.stateVersion();
        assertTrue(DeathPreparation.settle(chain, payload, false, "duplicate"));
        assertEquals(version, chain.stateVersion());
        assertTrue(chain.isPaused("iron"));
        assertTrue(DeathPreparation.holdPaused(chain, C, port));
        assertEquals(originalSubtasks, chain.currentPrimary().subtasks()); assertEquals(history, chain.satisfiedStages());
        port.preparation = SideTaskWorldPort.PreparationState.READY;
        assertFalse(DeathPreparation.holdPaused(chain, C, port));
        assertFalse(chain.isPaused("iron"));
    }
    @Test void terminalResumePayloadRetainsActualRecoveryInputAfterSideTaskContextIsRemoved() {
        var c=chain(); var p=new Port(); var events=new ArrayList<Map<String,Object>>(); var engine=engine(c,p,events);
        DeathPreparation.choose(engine,C,DeathRecoveryDecision.PREPARE_AND_REPLAN);
        DeathPreparation.tick(engine,C,c,p,(o,h,d)->d.accept(List.of(new SubtaskSpec("fetch and wear chest",CHEST,null))));
        var active=engine.current(C).orElseThrow(); var data=new LinkedHashMap<>(active.data());
        data.put("prepare_index","0"); data.put("prepare_reason","path temporarily unavailable");
        data.put("prepare_retry_at","25000"); data.put("prepare_retries","0"); engine.runtime().replace(active.withData(data));
        p.now+=DeathRecoverySideTaskType.DEADLINE_TICKS;
        engine.tick(C,p,token(c),false); assertFalse(engine.isActive(C));
        var restored=DeathPreparation.resumeTask(String.valueOf(events.get(0).get("prepareResume")));
        assertNotNull(restored);
        assertTrue(restored.data().containsKey(DeathPreparation.PLAN),"terminal side context removal must not erase the bounded actual preparation plan");
        assertTrue(restored.data().containsKey("prepare_descriptions"));
        assertEquals("0",restored.data().get("prepare_index"));
        assertTrue(restored.data().get("prepare_reason").contains("MISSING"));
    }
    @Test void materialsAndUnreachedFutureLoadoutDoNotBecomeDeathEquipmentObligations() {
        var c = new TaskChain(new Goal("g", "gear words alone", List.of(
            new PrimaryGoal("now", "trade iron armor words", List.of(Subtask.hardCoded("wheat", "wheat", Map.of("asset_key","minecraft:wheat","minimum",9)))),
            new PrimaryGoal("future", "future gear", List.of(Subtask.hardCoded("future-wear","future wear",CHEST))))));
        assertTrue(DeathPreparation.targets(c, Map.of(), Map.of()).isEmpty());
        c.recordSatisfiedNeed("now|r0|a0|minecraft:wheat");
        var before = c.satisfiedNeedKeys();
        assertTrue(DeathPreparation.targets(c, Map.of(), Map.of()).isEmpty());
        assertEquals(before, c.satisfiedNeedKeys());
    }
}
