package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.observation.*;
import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.*;
import com.dwinovo.numen.rdd.policy.AssetSemantics;
import com.dwinovo.numen.rdd.side.*;
import com.dwinovo.numen.rdd.side.death.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-12: offline host admission -> equipment facts -> controlled planner callback.
 * WHY: core-only flow tests miss both latest-death host gates after an empty successor.
 * Run the exact class through the parent's managed plugins:rdd test slot.
 * The candidate expects one fresh request; stale callbacks must still be rejected.
 * Uses the production seam rather than source assertions or a copied eligibility rule.
 * MC dispatch, server tickets, live world identity and the eight live deaths are not proven here.
 */
class RddDeathPreparationHostSuccessorTest {
    private static final String DIM = "minecraft:overworld", CHEST = "minecraft:iron_chestplate";
    private static final Map<String, Object> EQUIPMENT =
            Map.of("type", "equipment", "item", CHEST, "slot", "chest");
    private static final List<SubtaskSpec> PLAN = List.of(new SubtaskSpec("wear replacement chest", EQUIPMENT, null));
    private static final Map<String, String> STAMP = Map.of("world_id", "world", "server_session", "session");

    private static TaskChain chain() {
        TaskChain chain = new TaskChain(new Goal("g", "finish", List.of(
                new PrimaryGoal("gear", "equipment", List.of(Subtask.hardCoded("wear", "wear chest", EQUIPMENT))),
                new PrimaryGoal("trade", "trade", List.of(
                        Subtask.hardCoded("iron", "iron eight", Map.of("asset_key", "minecraft:iron_ingot", "minimum", 8)),
                        Subtask.hardCoded("emerald", "emerald twenty", Map.of("asset_key", "minecraft:emerald", "minimum", 20)))))));
        chain.startCurrent();
        chain.applyHardCodedResult("wear", true);
        chain.settlePrimaryByFact("gear", "current equipment verified", true);
        chain.startCurrent();
        return chain;
    }

    private static Map<String, Object> slot(int index, String item) {
        return Map.of("slot", index, "id", item, "count", item.isEmpty() ? 0 : 1);
    }

    private static RddDeathEquipment.Sample self(UUID id, boolean worn, boolean carried, boolean unknown) {
        List<Map<String, Object>> main = new ArrayList<>(), armor = new ArrayList<>();
        for (int i = 0; i < 36; i++) main.add(slot(i, carried && i == 1 ? CHEST : ""));
        for (int i = 0; i < 4; i++) armor.add(slot(36 + i, worn && i == 2 ? CHEST : ""));
        var values = Map.<String, Object>of("dimension", DIM, "companionUUID", id.toString(),
                "main", main, "armor", armor, "offhand", slot(40, ""), "selected", 0);
        var response = new WorldObservationService.Response(WorldObservationService.Status.OK, "self", "snapshot", 1,
                WorldObservationService.Coverage.FULL, "",
                List.of(new WorldObservationService.Item(values, unknown ? Set.of("armor[2].id") : Set.of(), Set.of())), 0,
                new ObservationMetadata("world", "session", DIM, id.toString(), "self", "self-key", 1, 1000,
                        ObservationMetadata.Freshness.CURRENT, "INTERNAL", 4000));
        return RddDeathEquipment.sample(response, id, DIM, STAMP, 1001);
    }

    /** Only shell boundaries are controlled; ledger, engine, host gates and equipment projection are real. */
    private static final class Fixture implements DeathPreparationHost.Access, SideTaskWorldPort {
        final UUID id = UUID.randomUUID();
        final TaskChain chain = chain();
        RddRuntime live = new RddRuntime(chain, new AssetRegistry());
        final List<Map<String, Object>> terminal = new ArrayList<>();
        final SideTaskEngine engine = new SideTaskEngine((event, data) -> {
            if (event.equals("side_task_completed") || event.equals("side_task_skipped")) terminal.add(data);
        }, value -> value);
        final List<Consumer<List<SubtaskSpec>>> callbacks = new ArrayList<>();
        RddDeathEquipment.Sample sample = self(id, false, false, false);
        int semanticsReads, equipmentReads, stopped, persisted;
        long now = 1000;

        Fixture() {
            assertNotNull(sample);
            for (int seq = 1; seq <= 29; seq++) record(seq == 29 ? Map.of(CHEST, 1) : Map.of(), seq);
            assertEquals(29, RddDeathLedger.latest(id).seq());
            engine.registerType(new DeathRecoverySideTaskType());
            assertTrue(engine.startNow(new DeathRecoverySideTaskType(), id, this, token(), Map.of("death_seq", "29")));
            assertTrue(DeathPreparation.choose(engine, id, DeathRecoveryDecision.RECOVER_DROPS_FIRST));
            tick(); // production admission attaches reached obligations; live drops do not auto-prepare
            assertTrue(active().data().get(DeathPreparation.TARGETS).contains(CHEST));
            assertEquals(0, callbacks.size());
        }

        MainlineResumeToken token() {
            return new MainlineResumeToken(id, chain.chainInstanceId(), chain.goal().id(),
                    chain.currentPrimary().id(), chain.currentSubtask().id(), chain.planRevision(), 1);
        }
        RddDeathLedger.Death record(Map<String, Integer> lost, int seq) {
            return RddDeathLedger.record(id, seq * 10L, seq * 1000L, DIM + " 0 64 0",
                    lost.size(), lost, "offline_fixture");
        }
        SideTask active() { return engine.current(id).orElseThrow(); }
        void tick() { DeathPreparationHost.tick(id, engine, this, this); }
        void prepare() {
            assertTrue(DeathPreparation.choose(engine, id, DeathRecoveryDecision.PREPARE_AND_REPLAN));
            tick();
        }
        Consumer<List<SubtaskSpec>> pending() {
            prepare();
            assertEquals(1, callbacks.size());
            assertEquals("PENDING", active().data().get("prepare_plan_state"));
            assertNotNull(active().data().get("prepare_request"));
            return callbacks.get(0);
        }
        PreparationFacts rawFacts() { return RddDeathEquipment.facts(sample, active(), Map.of(), null); }
        void noInstalledPlan() {
            assertFalse(active().data().containsKey(DeathPreparation.PLAN));
            assertEquals("PENDING", active().data().get("prepare_plan_state"));
        }
        public RddRuntime runtime(UUID subject) { return id.equals(subject) ? live : null; }
        public Map<String, AssetSemantics> semantics(UUID subject) {
            assertEquals(id, subject); semanticsReads++; return Map.of();
        }
        public void declareWearSlot(String key, Map<String, String> slots) { }
        public DeathPreparation.Planner capturePlanner(UUID subject) {
            assertEquals(id, subject);
            return (objective, hint, done) -> {
                assertTrue(objective.contains("恢复当前装备能力"));
                assertTrue(hint.contains(CHEST));
                callbacks.add(done);
            };
        }
        public void stopBody() { stopped++; }
        public void persist() { persisted++; }
        public long gameTime() { return now; }
        public long dayTime() { return now; }
        public long gameDay() { return 0; }
        public boolean hasDayNightCycle() { return true; }
        public String worldId() { return DIM; }
        public boolean isSleeping(UUID subject) { return false; }
        public boolean isDay() { return true; }
        public Wake pollWake(UUID subject) { return Wake.NONE; }
        public DeathRecoveryFacts deathRecoveryFacts(UUID subject, SideTask task) {
            assertEquals("29", task.data().get("death_seq"));
            assertNotNull(RddDeathLedger.openDeath(id, 29));
            return new DeathRecoveryFacts(DeathDropState.DROPS_PRESENT, false, false, true,
                    "bounded fixture: original batch29 drops remain observed");
        }
        public PreparationFacts deathPreparationFacts(UUID subject, SideTask task) {
            return DeathPreparationHost.facts(subject, id, task, () -> {
                equipmentReads++;
                return RddDeathEquipment.facts(sample, task, Map.of(), null);
            });
        }
        public Boolean deathPreparationCondition(UUID subject, Map<String, Object> condition) {
            assertEquals(id, subject);
            return RddDeathEquipment.condition(sample, condition, null);
        }
    }

    @Test void emptySuccessorMustAllowFreshPreparationWithoutReusingOldCallback() {
        var f = new Fixture();
        String mainline = f.chain.toJson();
        var old = f.active();
        var successor = f.record(Map.of(), 30); // empty production deaths do not start a new recovery
        assertEquals(30, successor.seq());
        assertTrue(successor.lostItems().isEmpty());
        assertEquals(RddDeathLedger.State.UNVERIFIED, successor.state());
        assertEquals(SideTaskWorldPort.PreparationState.MISSING, f.rawFacts().state(),
                "current trustworthy SELF still lacks the historical chest obligation");
        assertTrue(DeathPreparation.choose(f.engine, f.id, DeathRecoveryDecision.PREPARE_AND_REPLAN));
        int reads = f.equipmentReads, declarations = f.semanticsReads;
        f.tick();
        f.tick();
        assertEquals(old.instanceId(), f.active().instanceId());
        assertEquals("29", f.active().data().get("death_seq"));
        assertEquals(declarations, f.semanticsReads, "latest-seq admission currently returns before attach/tick");
        assertEquals(reads, f.equipmentReads);
        var gated = f.deathPreparationFacts(f.id, f.active());
        assertEquals(SideTaskWorldPort.PreparationState.UNKNOWN, gated.state());
        assertEquals("stale_death_batch", gated.reason());
        assertEquals(reads, f.equipmentReads, "the second gate also suppresses SELF reads");
        assertNotNull(RddDeathLedger.openDeath(f.id, 29));
        assertTrue(RddDeathLedger.all(f.id).stream().noneMatch(d -> d.state() == RddDeathLedger.State.CONFIRMED_LOST));
        assertEquals(mainline, f.chain.toJson(), "keep current trade and historical completion");
        // Expected candidate red on cb53: 0 requests. This asserts liveness, not a chosen business repair.
        assertEquals(1, f.callbacks.size(),
                "empty successor30 must not strand active29 PREPARE with missing equipment and no fresh request");
    }

    @Test void matchingBatchRequestsOnceAndOnlyWornEquipmentReleasesMainline() {
        var f = new Fixture();
        String mainline = f.chain.toJson();
        f.sample = self(f.id, false, true, false);
        assertEquals(SideTaskWorldPort.PreparationState.MISSING, f.rawFacts().state());
        var done = f.pending();
        f.tick();
        assertEquals(1, f.callbacks.size());
        assertEquals(1, f.stopped);
        done.accept(PLAN);
        assertEquals(1, f.persisted);
        assertEquals("READY", f.active().data().get("prepare_plan_state"));
        assertTrue(f.active().data().containsKey(DeathPreparation.PLAN));
        f.engine.tick(f.id, f, f.token(), false);
        assertTrue(f.engine.isActive(f.id), "a valid plan and carried chestplate do not certify worn equipment");
        f.sample = self(f.id, true, false, false);
        f.tick();
        f.engine.tick(f.id, f, f.token(), false);
        assertFalse(f.engine.isActive(f.id));
        assertEquals("true", f.terminal.get(0).get("prepareVerified"));
        assertTrue(DeathPreparation.settle(f.chain, String.valueOf(f.terminal.get(0).get("prepareResume")), true, "worn"));
        assertEquals(mainline, f.chain.toJson());
        assertEquals("iron", f.chain.currentSubtask().id());
    }

    @Test void emptySuccessorRejectsAlreadyPendingOldCallback() {
        assertSuccessorRejectsCallback(Map.of());
    }

    @Test void nonemptySuccessorAlsoRejectsAlreadyPendingOldCallback() {
        assertSuccessorRejectsCallback(Map.of(CHEST, 1));
    }

    private static void assertSuccessorRejectsCallback(Map<String, Integer> lost) {
        var f = new Fixture();
        String mainline = f.chain.toJson();
        var done = f.pending();
        String request = f.active().data().get("prepare_request");
        f.record(lost, 30);
        done.accept(PLAN);
        f.noInstalledPlan();
        assertEquals(request, f.active().data().get("prepare_request"));
        assertEquals(0, f.persisted, "host rejects stale batch before the core installer/persist");
        assertNotNull(RddDeathLedger.openDeath(f.id, 29));
        assertEquals(mainline, f.chain.toJson());
    }

    @Test void equalSerializedChainInAnotherObjectRejectsOldCallback() {
        var f = new Fixture();
        var done = f.pending();
        var clone = TaskChain.fromJson(f.chain.toJson());
        assertNotSame(f.chain, clone);
        assertEquals(f.chain.chainInstanceId(), clone.chainInstanceId());
        assertTrue(DeathPreparation.sameChain(f.active(), clone), "token alone accepts the restored identity");
        f.live = new RddRuntime(clone, new AssetRegistry());
        done.accept(PLAN);
        f.noInstalledPlan();
        assertEquals(0, f.persisted, "host additionally requires the captured chain reference");
    }

    @Test void refreshedRuntimeWrapperWithSameChainAcceptsCurrentCallback() {
        var f = new Fixture();
        var done = f.pending();
        f.live = new RddRuntime(f.chain, new AssetRegistry());
        done.accept(PLAN);
        assertTrue(f.active().data().containsKey(DeathPreparation.PLAN));
        assertEquals(1, f.persisted);
    }

    @Test void changedSideInstanceOrGenerationRejectsCallbackAtCoreConsumer() {
        for (boolean generation : List.of(false, true)) {
            var f = new Fixture();
            var done = f.pending();
            var t = f.active();
            f.engine.runtime().replace(new SideTask(t.companionId(),
                    generation ? t.instanceId() : t.instanceId() + "-replacement", t.typeId(),
                    generation ? t.generation() + 1 : t.generation(), t.scheduledGameDay(),
                    t.createdAtGameTime(), t.deadlineGameTime(), t.state(), t.phase(), t.data(), t.mainlineToken()));
            done.accept(PLAN);
            f.noInstalledPlan();
            assertEquals(1, f.persisted, "host admitted callback; actual core identity consumer rejected installation");
        }
    }

    @Test void changedChoiceRequestOrPlanRevisionRejectsCallbackAtCoreConsumer() {
        for (String mutation : List.of("choice", "request", "revision")) {
            var f = new Fixture();
            var done = f.pending();
            if (mutation.equals("choice"))
                assertTrue(DeathPreparation.choose(f.engine, f.id, DeathRecoveryDecision.FETCH_BACKUP_THEN_CONTINUE));
            else if (mutation.equals("request")) f.engine.setData(f.id, "prepare_request", "different-request");
            else f.chain.nextPlanRevision();
            done.accept(PLAN);
            assertFalse(f.active().data().containsKey(DeathPreparation.PLAN), mutation);
            assertEquals(1, f.persisted, "host admitted callback; core rechecks persisted request/choice/revision");
        }
    }

    @Test void factsSupplierIsLazyForWrongSubjectAndStaleBatch() {
        var f = new Fixture();
        int reads = f.equipmentReads;
        var wrong = f.deathPreparationFacts(UUID.randomUUID(), f.active());
        assertEquals(SideTaskWorldPort.PreparationState.UNKNOWN, wrong.state());
        assertEquals("subject_mismatch", wrong.reason());
        assertEquals(reads, f.equipmentReads);
        assertEquals(SideTaskWorldPort.PreparationState.MISSING, f.deathPreparationFacts(f.id, f.active()).state());
        assertEquals(reads + 1, f.equipmentReads);
        f.record(Map.of(), 30);
        assertEquals("subject_mismatch", f.deathPreparationFacts(UUID.randomUUID(), f.active()).reason(),
                "subject guard precedes the stale-batch guard");
        assertEquals("stale_death_batch", f.deathPreparationFacts(f.id, f.active()).reason());
        assertEquals(reads + 1, f.equipmentReads);
    }

    @Test void unknownSelfNeverBecomesMissingOrCompletesRecovery() {
        var f = new Fixture();
        String mainline = f.chain.toJson();
        f.sample = self(f.id, false, false, true);
        assertNull(f.sample);
        f.prepare();
        assertEquals(SideTaskWorldPort.PreparationState.UNKNOWN, f.deathPreparationFacts(f.id, f.active()).state());
        assertEquals(0, f.callbacks.size());
        f.engine.tick(f.id, f, f.token(), false);
        assertTrue(f.engine.isActive(f.id));
        assertTrue(f.terminal.isEmpty());
        assertEquals(mainline, f.chain.toJson());
    }
}
