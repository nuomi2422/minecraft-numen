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
    private static TaskChain chain() { return chain(true); }
    private static TaskChain chain(boolean startTrade) {
        TaskChain chain = new TaskChain(new Goal("g", "finish", List.of(
                new PrimaryGoal("gear", "equipment", List.of(Subtask.hardCoded("wear", "wear chest", CHEST))),
                new PrimaryGoal("trade", "trade", List.of(
                        Subtask.hardCoded("iron", "iron eight", Map.of("asset_key", "minecraft:iron_ingot", "minimum", 8)),
                        Subtask.hardCoded("emerald", "emerald twenty", Map.of("asset_key", "minecraft:emerald", "minimum", 20)))))));
        chain.startCurrent();
        chain.applyHardCodedResult("wear", true);
        chain.settlePrimaryByFact("gear", "current equipment verified", true);
        if (startTrade) chain.startCurrent();
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
    @Test void pausedPayloadProjectionAndBoundedRecheckActuallyRequestsRecoveryAgain() {
        var c=chain(); var p=new Port(); var events=new ArrayList<Map<String,Object>>(); var engine=engine(c,p,events);
        DeathPreparation.choose(engine,C,DeathRecoveryDecision.PREPARE_AND_REPLAN);
        DeathPreparation.tick(engine,C,c,p,(o,h,d)->d.accept(List.of()));
        engine.tick(C,p,token(c),false);
        assertFalse(engine.isActive(C));
        assertTrue(DeathPreparation.settle(c,String.valueOf(events.get(0).get("prepareResume")),false,"temporary planner failure"));
        assertTrue(DeathPreparation.pausedContext(c).contains("iron_chestplate"));
        assertTrue(DeathPreparation.pausedContext(c).contains("recovery_not_verified"));
        assertFalse(DeathPreparation.reopenPaused(engine,C,c,p),"no every-tick retries");
        p.now+=1200;
        assertTrue(DeathPreparation.reopenPaused(engine,C,c,p));
        AtomicInteger called=new AtomicInteger();
        DeathPreparation.tick(engine,C,c,p,(o,h,d)->{called.incrementAndGet();d.accept(List.of(new SubtaskSpec("wear chest again",CHEST,null)));});
        assertEquals(1,called.get());
        engine.tick(C,p,token(c),false); assertTrue(c.isPaused("iron"));
        p.preparation=SideTaskWorldPort.PreparationState.READY;
        engine.tick(C,p,token(c),false); assertFalse(engine.isActive(C));
        assertTrue(DeathPreparation.settle(c,String.valueOf(events.get(1).get("prepareResume")),true,"ready"));
        assertFalse(c.isPaused("iron")); assertEquals("iron",c.currentSubtask().id());
    }
    /** Real finish ordering, with only the MC host boundary replaced by synchronous callbacks.
     * SIDE_TASK is a context projection, not a TaskChain state. PENDING is reached by the
     * original prior-primary settlement; no invented state or reflection is used here.
     */
    private static final class TerminalHost {
        final TaskChain chain;
        final Port port = new Port();
        final SideTaskEngine engine;
        final List<String> order = new ArrayList<>();
        final List<RuntimeException> settlementErrors = new ArrayList<>();
        Map<String, Object> terminal;
        Boolean resumed;
        int oldGoalNudges;
        TerminalHost(TaskChain chain) {
            this.chain = chain;
            engine = new SideTaskEngine((event, data) -> {
                if (event.equals("side_task_completed") || event.equals("side_task_skipped")) {
                    order.add("terminal");
                    terminal = data;
                    assertFalse(engineActive(), "finish removes the side before synchronous settlement");
                    try {
                        DeathPreparation.settle(chain, String.valueOf(data.get("prepareResume")),
                                "true".equals(data.get("prepareVerified")), "terminal preparation evidence");
                    } catch (RuntimeException ex) {
                        // Match the existing RddPlugin/SideTaskHost catch boundary, not an ideal host.
                        settlementErrors.add(ex);
                    }
                } else if (event.equals("side_task_resumed_mainline")) {
                    order.add("resume-event");
                    resumed = Boolean.TRUE.equals(data.get("mainlineResumed"));
                    if (resumed) oldGoalNudges++;
                }
            }, captured -> {
                order.add("validator");
                // SideTaskHost.validateMainline: recovery pause + identity, no state normalization.
                String id = chain.currentSubtask().id();
                String reason = chain.pauseReason(id);
                if (chain.isPaused(id) && reason != null && reason.startsWith("recovery_not_verified:")) return null;
                return captured.matches(chain.chainInstanceId(), chain.goal().id(),
                        chain.currentPrimary().id(), id, chain.planRevision()) ? captured : null;
            });
            var type = new DeathRecoverySideTaskType();
            engine.registerType(type);
            assertTrue(engine.startNow(type, C, port, token(chain), Map.of("death_seq", "47")));
            assertTrue(DeathPreparation.attach(engine, C, chain, Map.of(), Map.of()));
            assertTrue(DeathPreparation.choose(engine, C, DeathRecoveryDecision.PREPARE_AND_REPLAN));
        }
        boolean engineActive() { return engine.isActive(C); }
        void finish(boolean verified) {
            if (verified) port.preparation = SideTaskWorldPort.PreparationState.READY;
            else port.now += DeathRecoverySideTaskType.DEADLINE_TICKS + 1;
            engine.tick(C, port, token(chain), false);
            assertFalse(engine.isActive(C));
            assertNotNull(terminal, "registered real type must enter real engine finish");
            assertEquals(List.of("terminal", "validator", "resume-event"), order);
        }
    }
    @Test void pendingPrimaryUnverifiedRealFinishPausesBeforeResumeAndCannotNudgeOldGoal() {
        var chain = chain(false);
        assertEquals(PrimaryGoalStatus.PENDING, chain.primaryStatus());
        var plan = chain.currentPrimary().subtasks(); var history = chain.satisfiedStages();
        String identity = chain.chainInstanceId(); long revision = chain.planRevision();
        var host = new TerminalHost(chain);
        host.finish(false);
        assertAll(
                () -> assertTrue(host.settlementErrors.isEmpty(), "unverified settlement must not throw primary not active"),
                () -> assertTrue(chain.isPaused("iron"), "PENDING old step must be paused before finish can release it"),
                () -> assertEquals(Boolean.FALSE, host.resumed, "swallowed settlement failure must not license resume"),
                () -> assertEquals(0, host.oldGoalNudges));
        assertEquals(identity, chain.chainInstanceId()); assertEquals(revision, chain.planRevision());
        assertEquals(plan, chain.currentPrimary().subtasks()); assertEquals(history, chain.satisfiedStages());
        assertEquals("iron", chain.currentSubtask().id());
    }
    @Test void activeUnverifiedRealFinishPausesAndDuplicateSettlementDoesNotMoveHistory() {
        var chain = chain(); var host = new TerminalHost(chain);
        host.finish(false);
        assertTrue(host.settlementErrors.isEmpty()); assertTrue(chain.isPaused("iron"));
        assertEquals(Boolean.FALSE, host.resumed); assertEquals(0, host.oldGoalNudges);
        String paused = chain.toJson();
        assertTrue(DeathPreparation.settle(chain, String.valueOf(host.terminal.get("prepareResume")), false, "duplicate"));
        assertEquals(paused, chain.toJson());
    }
    @Test void verifiedRealFinishPreservesOriginalContinuationForPendingAndActive() {
        for (boolean active : List.of(false, true)) {
            var chain = chain(active); var host = new TerminalHost(chain); String original = chain.toJson();
            host.finish(true);
            assertTrue(host.settlementErrors.isEmpty()); assertEquals(Boolean.TRUE, host.resumed);
            assertEquals(original, chain.toJson(), "verified side completion must not rewrite original continuation");
        }
    }
    @Test void terminalSettlementWrongChainAndOutdatedRevisionDoNotMutateOriginal() {
        var chain = chain(); var host = new TerminalHost(chain); host.finish(false);
        String payload = String.valueOf(host.terminal.get("prepareResume"));
        var other = chain(); String otherBefore = other.toJson();
        assertFalse(DeathPreparation.settle(other, payload, false, "other chain"));
        assertEquals(otherBefore, other.toJson());
        chain.nextPlanRevision(); String changed = chain.toJson();
        assertFalse(DeathPreparation.settle(chain, payload, true, "outdated callback"));
        assertEquals(changed, chain.toJson());
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
