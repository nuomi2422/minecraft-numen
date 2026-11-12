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
 * Synthetic SELF stamps exercise projection shape; they do not authenticate a real world/session.
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
        int equipmentReads, stopped, persisted;
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
            assertEquals(id, subject); return Map.of();
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
            if (!"29".equals(task.data().get("death_seq")))
                return new DeathRecoveryFacts(DeathDropState.UNKNOWN, false, false, true,
                        "bounded fixture: no drop observation for this batch");
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
        assertEquals("29", old.data().get("death_seq"), "initial recovery batch");
        var successor = f.record(Map.of(), 30); // empty production deaths do not start a new recovery
        assertEquals(30, successor.seq());
        assertTrue(successor.lostItems().isEmpty());
        assertEquals(RddDeathLedger.State.UNVERIFIED, successor.state());
        assertEquals(SideTaskWorldPort.PreparationState.MISSING, f.rawFacts().state(),
                "current trustworthy SELF still lacks the historical chest obligation");
        assertTrue(DeathPreparation.choose(f.engine, f.id, DeathRecoveryDecision.PREPARE_AND_REPLAN));
        f.tick();
        f.tick();
        // cb53 returns before attach/tick; its facts gate also reports stale_death_batch.
        // Those diagnostics are not success criteria: a repair may establish a fresh side identity/batch.
        assertNotNull(RddDeathLedger.openDeath(f.id, 29));
        assertEquals(SideTaskWorldPort.DeathDropState.DROPS_PRESENT,
                f.deathRecoveryFacts(f.id, old).dropState(), "original drop evidence stays bound to batch29");
        assertEquals(RddDeathLedger.State.UNVERIFIED, RddDeathLedger.latest(f.id).state(),
                "an empty successor is not evidence of confirmed loss");
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

    @Test void freshSuccessorRequestInstallsWhileOldCallbackIsRejectedBeforeAndAfter() {
        var f = new Fixture();
        String mainline = f.chain.toJson();
        var old = f.pending();
        String oldRequest = f.active().data().get("prepare_request");
        f.record(Map.of(), 30);
        old.accept(PLAN);
        f.noInstalledPlan();
        f.tick();
        f.tick();
        assertEquals(2, f.callbacks.size(), "one fresh request after the original in-flight request");
        assertNotEquals(oldRequest, f.active().data().get("prepare_request"));
        assertEquals("29", f.active().data().get("death_seq"), "drop source remains batch29");
        old.accept(PLAN);
        f.noInstalledPlan();
        assertEquals(0, f.persisted);
        f.callbacks.get(1).accept(PLAN);
        assertEquals("READY", f.active().data().get("prepare_plan_state"));
        String installed = f.active().data().get(DeathPreparation.PLAN);
        old.accept(List.of());
        assertEquals(installed, f.active().data().get(DeathPreparation.PLAN));
        assertEquals(1, f.persisted, "stale callback never reaches the installer");
        f.engine.tick(f.id, f, f.token(), false);
        assertTrue(f.engine.isActive(f.id), "accepted plan is not equipment READY");
        f.sample = self(f.id, true, false, false);
        f.tick();
        f.engine.tick(f.id, f, f.token(), false);
        assertFalse(f.engine.isActive(f.id));
        var restored = DeathPreparation.resumeTask(String.valueOf(f.terminal.get(0).get("prepareResume")));
        assertNotNull(restored);
        assertEquals(SideTaskWorldPort.PreparationState.READY,
                f.deathPreparationFacts(f.id, restored).state(), "terminal host consumer rechecks current SELF");
        assertTrue(DeathPreparation.settle(f.chain, String.valueOf(f.terminal.get(0).get("prepareResume")), true, "worn"));
        assertEquals(mainline, f.chain.toJson());
        assertNotNull(RddDeathLedger.openDeath(f.id, 29));
    }

    @Test void eachEmptySuccessorInvalidatesPreviousPlanAndRequestsOnlyOnce() {
        var f = new Fixture();
        f.record(Map.of(), 30);
        assertTrue(DeathPreparation.choose(f.engine, f.id, DeathRecoveryDecision.FETCH_BACKUP_THEN_CONTINUE));
        f.tick();
        assertEquals(1, f.callbacks.size());
        var callback30 = f.callbacks.get(0);
        callback30.accept(PLAN);
        assertTrue(f.active().data().containsKey(DeathPreparation.PLAN));
        String request30 = f.active().data().get("prepare_request");
        f.record(Map.of(), 31);
        f.tick();
        f.tick();
        assertEquals(2, f.callbacks.size());
        assertNotEquals(request30, f.active().data().get("prepare_request"));
        f.noInstalledPlan();
        callback30.accept(PLAN);
        f.noInstalledPlan();
        f.callbacks.get(1).accept(PLAN);
        assertTrue(f.active().data().containsKey(DeathPreparation.PLAN));
        assertEquals("29", f.active().data().get("death_seq"));
        assertNotNull(RddDeathLedger.openDeath(f.id, 29));
        assertEquals(2, f.stopped, "each new body binding stops the obsolete preparation action once");
    }

    @Test void nonemptyUnknownOrEvictedSuccessorEvidenceNeverAuthorizesRebinding() {
        for (String gap : List.of("nonempty", "unknown_items", "evicted_source", "malformed_source")) {
            var f = new Fixture();
            if (gap.equals("nonempty")) {
                f.record(Map.of(CHEST, 1), 30);
                f.record(Map.of(), 31);
            } else if (gap.equals("unknown_items")) {
                RddDeathLedger.record(f.id, 300, 30000, DIM + " 0 64 0", 1, Map.of(), "unknown_items");
            } else if (gap.equals("evicted_source")) {
                for (int seq = 30; seq <= 37; seq++) f.record(Map.of(), seq);
                assertNull(RddDeathLedger.openDeath(f.id, 29));
            } else {
                f.record(Map.of(), 30);
                f.engine.setData(f.id, "death_seq", "unknown");
            }
            // A persisted marker alone must not bypass the actual ledger proof.
            f.engine.setData(f.id, "prepare_death_seq", String.valueOf(RddDeathLedger.latest(f.id).seq()));
            assertTrue(DeathPreparation.choose(f.engine, f.id, DeathRecoveryDecision.PREPARE_AND_REPLAN));
            int reads = f.equipmentReads;
            f.tick();
            assertEquals(0, f.callbacks.size(), gap);
            assertEquals(SideTaskWorldPort.PreparationState.UNKNOWN,
                    f.deathPreparationFacts(f.id, f.active()).state(), gap);
            assertEquals(reads, f.equipmentReads, "invalid lineage stays lazy: " + gap);
        }
    }

    @Test void successorUnknownSelfWaitsForTrustedMissingWithoutCompleting() {
        var f = new Fixture();
        f.record(Map.of(), 30);
        f.sample = self(f.id, false, false, true);
        f.prepare();
        assertEquals(0, f.callbacks.size());
        assertEquals(SideTaskWorldPort.PreparationState.UNKNOWN, f.deathPreparationFacts(f.id, f.active()).state());
        f.engine.tick(f.id, f, f.token(), false);
        assertTrue(f.engine.isActive(f.id));
        f.sample = self(f.id, false, true, false);
        f.tick();
        f.tick();
        assertEquals(1, f.callbacks.size(), "current trustworthy MISSING may now request preparation");
        f.callbacks.get(0).accept(PLAN);
        f.engine.tick(f.id, f, f.token(), false);
        assertTrue(f.engine.isActive(f.id), "carried replacement still does not satisfy worn slot");
        assertTrue(f.terminal.isEmpty());
    }

    @Test void freshSuccessorCallbackStillChecksChainSideIdentityChoiceRequestAndRevision() {
        for (String drift : List.of("chain", "instance", "generation", "choice", "request", "revision")) {
            var f = new Fixture();
            f.record(Map.of(), 30);
            f.prepare();
            assertEquals(1, f.callbacks.size(), drift);
            var t = f.active();
            if (drift.equals("chain")) f.live = new RddRuntime(TaskChain.fromJson(f.chain.toJson()), new AssetRegistry());
            else if (drift.equals("instance") || drift.equals("generation"))
                f.engine.runtime().replace(new SideTask(t.companionId(),
                        drift.equals("instance") ? t.instanceId() + "-new" : t.instanceId(), t.typeId(),
                        drift.equals("generation") ? t.generation() + 1 : t.generation(), t.scheduledGameDay(),
                        t.createdAtGameTime(), t.deadlineGameTime(), t.state(), t.phase(), t.data(), t.mainlineToken()));
            else if (drift.equals("choice")) DeathPreparation.choose(f.engine, f.id, DeathRecoveryDecision.FETCH_BACKUP_THEN_CONTINUE);
            else if (drift.equals("request")) f.engine.setData(f.id, "prepare_request", "replacement");
            else f.chain.nextPlanRevision();
            f.callbacks.get(0).accept(PLAN);
            assertFalse(f.active().data().containsKey(DeathPreparation.PLAN), drift);
        }
    }

    @Test void successorPausePayloadRechecksCurrentSelfWithoutTrustingTransientMarker() {
        var f = new Fixture();
        var history = f.chain.satisfiedStages();
        var trade = f.chain.currentPrimary().subtasks();
        f.record(Map.of(), 30);
        f.prepare();
        assertEquals(1, f.callbacks.size());
        f.callbacks.get(0).accept(List.of()); // existing bounded failed-plan pause path
        f.engine.tick(f.id, f, f.token(), false);
        assertFalse(f.engine.isActive(f.id));
        String payload = String.valueOf(f.terminal.get(0).get("prepareResume"));
        assertTrue(DeathPreparation.settle(f.chain, payload, false, "planner unavailable"));
        assertTrue(f.chain.isPaused("iron"));
        f.sample = self(f.id, false, false, true);
        assertTrue(DeathPreparation.holdPaused(f.chain, f.id, f));
        f.sample = self(f.id, true, false, false);
        assertFalse(DeathPreparation.holdPaused(f.chain, f.id, f));
        assertFalse(f.chain.isPaused("iron"));
        assertEquals(history, f.chain.satisfiedStages());
        assertEquals(trade, f.chain.currentPrimary().subtasks());
        assertEquals("iron", f.chain.currentSubtask().id());
    }

    @Test void emptySuccessorNeverRebindsWithoutPreparingChoiceOrMatchingChain() {
        for (String blocked : List.of("recover", "abandon", "reset", "invalid", "chain")) {
            var f = new Fixture();
            f.record(Map.of(), 30);
            if (blocked.equals("abandon")) DeathPreparation.choose(f.engine, f.id, DeathRecoveryDecision.ABANDON_RECOVERY);
            else if (blocked.equals("reset")) DeathPreparation.choose(f.engine, f.id, DeathRecoveryDecision.RESET_AND_RESTART);
            else if (blocked.equals("invalid")) f.engine.setData(f.id, "decision", "unrecognized");
            else if (blocked.equals("chain")) {
                DeathPreparation.choose(f.engine, f.id, DeathRecoveryDecision.PREPARE_AND_REPLAN);
                f.chain.nextPlanRevision();
            }
            var before = f.active();
            f.tick();
            assertEquals(0, f.callbacks.size(), blocked);
            assertEquals(before, f.active(), "rejected admission must not mutate side data: " + blocked);
        }
    }

    @Test void terminalPayloadRetainsFreshBodyAndRejectsOlderReadyEvent() {
        var f = new Fixture();
        f.prepare();
        String oldPayload = DeathPreparation.resumePayload(f.active());
        f.record(Map.of(), 30);
        f.sample = self(f.id, true, false, false);
        var old = DeathPreparation.resumeTask(oldPayload);
        assertFalse(DeathPreparationHost.settlementIdentity(f.id, 29, old, f.live),
                "current wearing never authenticates an old body's terminal event");
        f.tick();
        f.engine.tick(f.id, f, f.token(), false);
        var fresh = DeathPreparation.resumeTask(String.valueOf(f.terminal.get(0).get("prepareResume")));
        assertNotNull(fresh);
        assertEquals("30", fresh.data().get("prepare_death_seq"), "bounded terminal serialization retains body identity");
        assertTrue(DeathPreparationHost.settlementIdentity(f.id, 29, fresh, f.live));
        assertFalse(DeathPreparationHost.settlementIdentity(f.id, 30, fresh, f.live), "event seq remains drop source");
        assertFalse(DeathPreparationHost.settlementIdentity(UUID.randomUUID(), 29, fresh, f.live));
        f.chain.nextPlanRevision();
        assertFalse(DeathPreparationHost.settlementIdentity(f.id, 29, fresh, f.live));
    }

    @Test void settlementDedupSeparatesBodyAndSideGenerationsButRejectsExactReplay() {
        var f = new Fixture();
        var t = f.active();
        var data = new LinkedHashMap<>(t.data());
        data.put("prepare_death_seq", "30");
        var body30 = t.withData(data);
        data.put("prepare_death_seq", "31");
        var body31 = t.withData(data);
        String key30 = DeathPreparationHost.settlementKey(f.id, 29, true, body30);
        String key31 = DeathPreparationHost.settlementKey(f.id, 29, true, body31);
        var settled = new HashSet<String>();
        assertTrue(settled.add(key30));
        assertTrue(settled.add(key31), "old body settlement must not consume the fresh body's key");
        assertFalse(settled.add(DeathPreparationHost.settlementKey(f.id, 29, true, body31)));
        var newer = new SideTask(t.companionId(), t.instanceId() + "-new", t.typeId(), t.generation() + 1,
                t.scheduledGameDay(), t.createdAtGameTime(), t.deadlineGameTime(), t.state(), t.phase(),
                body31.data(), t.mainlineToken());
        assertNotEquals(key31, DeathPreparationHost.settlementKey(f.id, 29, true, newer));
        assertEquals(f.id + "|29", DeathPreparationHost.settlementKey(f.id, 29, false, null),
                "physical drop-only settlement key stays unchanged");
    }

    @Test void legitimateDropClosureDoesNotInvalidateFreshBodyTerminalFacts() {
        for (boolean recovered : List.of(false, true)) {
            var f = new Fixture();
            f.record(Map.of(), 30);
            f.prepare();
            var data = new LinkedHashMap<>(f.active().data());
            data.put("prepare_death_seq", "30");
            var fresh = f.active().withData(data);
            assertTrue(DeathPreparationHost.settlementIdentity(f.id, 29, fresh, f.live));
            if (recovered) RddDeathLedger.confirmRecovered(f.id, 29, "actual source pickup evidence");
            else RddDeathLedger.confirmLost(f.id, 29, "actual source loaded-absent evidence");
            assertNull(RddDeathLedger.openDeath(f.id, 29));
            f.sample = self(f.id, true, false, false);
            assertTrue(DeathPreparationHost.settlementIdentity(f.id, 29, fresh, f.live));
            assertEquals(SideTaskWorldPort.PreparationState.READY, f.deathPreparationFacts(f.id, fresh).state(),
                    "commit-boundary fact read follows physical closure in the actual plugin");
            assertEquals(RddDeathLedger.State.UNVERIFIED, RddDeathLedger.latest(f.id).state());
        }
    }

    @Test void unknownSuccessorSelfPreservesOldPlanRequestAndStopStateUntilTrusted() {
        var f = new Fixture();
        var old = f.pending();
        old.accept(PLAN);
        var before = f.active();
        int stopped = f.stopped;
        f.record(Map.of(), 30);
        f.sample = self(f.id, false, false, true);
        f.tick();
        assertEquals(before, f.active(), "UNKNOWN must not clear a plan/request or bind a speculative body");
        assertEquals(stopped, f.stopped);
        assertEquals(1, f.callbacks.size());
        f.sample = self(f.id, false, false, false);
        f.tick();
        assertEquals(2, f.callbacks.size());
        f.noInstalledPlan();
        assertEquals(stopped + 1, f.stopped);
    }

    @Test void futureBodyMarkerCannotAuthorizeFactsOrReplaceCurrentRequest() {
        var f = new Fixture();
        f.pending();
        f.record(Map.of(), 30);
        f.engine.setData(f.id, "prepare_death_seq", "999");
        var before = f.active();
        int reads = f.equipmentReads;
        f.tick();
        assertEquals(before, f.active(), "a future marker is invalid, not permission to rebind");
        assertEquals(1, f.callbacks.size());
        assertEquals(SideTaskWorldPort.PreparationState.UNKNOWN, f.deathPreparationFacts(f.id, before).state());
        assertEquals(reads, f.equipmentReads, "future identity must fail before current SELF supplier");
        assertFalse(DeathPreparationHost.settlementIdentity(f.id, 29, before, f.live));
    }

    /** Actual empty planner result -> engine terminal -> unverified settlement, with no active side left. */
    private static Fixture pausedAtBody30() {
        var f = new Fixture();
        f.record(Map.of(), 30);
        f.prepare();
        assertEquals(1, f.callbacks.size());
        f.callbacks.get(0).accept(List.of());
        f.engine.tick(f.id, f, f.token(), false);
        assertFalse(f.engine.isActive(f.id));
        assertEquals(1, f.terminal.size());
        String payload = String.valueOf(f.terminal.get(0).get("prepareResume"));
        var terminal = DeathPreparation.resumeTask(payload);
        assertTrue(DeathPreparationHost.settlementIdentity(f.id, 29, terminal, f.live));
        assertTrue(DeathPreparation.settle(f.chain, payload, false, "planner unavailable"));
        var paused = DeathPreparation.pausedTask(f.chain);
        assertNotNull(paused);
        assertEquals("29", paused.data().get("death_seq"));
        assertEquals("30", paused.data().get("prepare_death_seq"));
        assertEquals("0", paused.data().get("prepare_retries"));
        assertTrue(f.chain.isPaused("iron"));
        assertFalse(f.engine.isActive(f.id));
        return f;
    }

    /** Same production entry as SideTaskHost; only MC nudge/persistence shell effects are controlled. */
    private static boolean recheckPaused(Fixture f) {
        return DeathPreparationHost.holdRecoveryPause(f.id, f.engine, f.chain, f, () -> {
            f.tick();
            f.persist();
        });
    }

    @Test void pausedBody30ThenEmpty31RequestsFreshPreparationOnlyWhenRetryIsEligible() {
        // Admission controls run before the candidate-red liveness assertion below.
        for (String blocked : List.of("nonempty", "chain", "subject", "owner", "budget")) {
            var c = pausedAtBody30();
            var old = DeathPreparation.pausedTask(c.chain);
            if (blocked.equals("budget")) {
                var data = new LinkedHashMap<>(old.data());
                data.put("prepare_retries", "2");
                c.chain.resumeFromPaused("iron");
                assertTrue(DeathPreparation.settle(c.chain, DeathPreparation.resumePayload(old.withData(data)),
                        false, "retry budget exhausted"));
            }
            c.record(blocked.equals("nonempty") ? Map.of(CHEST, 1) : Map.of(), 31);
            c.now = Long.parseLong(old.data().get("prepare_retry_at"));
            if (blocked.equals("chain")) c.chain.nextPlanRevision();
            if (blocked.equals("owner")) {
                c.chain.resumeFromPaused("iron");
                c.chain.pauseSubtask("iron", TaskChain.OWNER_PAUSE_REASON);
                assertTrue(c.chain.isOwnerPause("iron"));
            }
            String pause = c.chain.pauseReason("iron");
            if (blocked.equals("subject")) {
                DeathPreparationHost.holdRecoveryPause(UUID.randomUUID(), c.engine, c.chain, c,
                        () -> fail("wrong subject must not reopen"));
            } else recheckPaused(c);
            assertTrue(c.chain.isPaused("iron"), blocked);
            assertEquals(pause, c.chain.pauseReason("iron"), blocked);
            assertFalse(c.engine.isActive(c.id), blocked);
            assertEquals(1, c.callbacks.size(), blocked);
        }
        var f = pausedAtBody30();
        var old = DeathPreparation.pausedTask(f.chain);
        var history = f.chain.satisfiedStages();
        var trade = f.chain.currentPrimary().subtasks();
        long retryAt = Long.parseLong(old.data().get("prepare_retry_at"));
        f.record(Map.of(), 31); // production empty deaths do not enqueue another recovery side
        f.now = retryAt - 1;
        assertTrue(recheckPaused(f));
        assertFalse(f.engine.isActive(f.id), "MISSING cannot bypass the existing 60s retry time");
        assertEquals(1, f.callbacks.size());
        f.now = retryAt;
        f.sample = self(f.id, false, false, true);
        String pause = f.chain.pauseReason("iron");
        int stopped = f.stopped;
        assertTrue(recheckPaused(f));
        assertEquals(pause, f.chain.pauseReason("iron"), "UNKNOWN keeps the original paused payload");
        assertEquals(stopped, f.stopped);
        assertFalse(f.engine.isActive(f.id));
        assertEquals(1, f.callbacks.size());
        f.sample = self(f.id, false, false, false);
        assertEquals(SideTaskWorldPort.PreparationState.MISSING,
                RddDeathEquipment.facts(f.sample, old, Map.of(), null).state(), "independent current SELF is missing");
        assertFalse(DeathPreparationHost.settlementIdentity(f.id, 29, old, f.live), "old30 terminal stays stale");
        assertTrue(recheckPaused(f), "fresh preparation keeps the original main step paused");
        // Expected red on the unchanged entry: old30 facts are UNKNOWN, so no fresh31 side is reopened.
        assertTrue(f.engine.isActive(f.id), "eligible paused recovery must rebind to proven empty body31");
        assertEquals("31", f.active().data().get("prepare_death_seq"));
        assertEquals("29", f.active().data().get("death_seq"));
        assertEquals("1", f.active().data().get("prepare_retries"));
        assertEquals(2, f.callbacks.size(), "one fresh31 request through the real host callback");
        String request = f.active().data().get("prepare_request");
        assertNotNull(request);
        assertTrue(recheckPaused(f));
        assertEquals(request, f.active().data().get("prepare_request"));
        assertEquals(2, f.callbacks.size());
        assertFalse(DeathPreparationHost.settlementIdentity(f.id, 29, old, f.live));
        assertTrue(f.chain.isPaused("iron"));
        assertEquals(history, f.chain.satisfiedStages());
        assertEquals(trade, f.chain.currentPrimary().subtasks());
        assertEquals("iron", f.chain.currentSubtask().id());
        assertNotNull(RddDeathLedger.openDeath(f.id, 29));
    }

    @Test void pausedBody30ThenEmpty31RechecksCurrentReadyWithoutReusingOldTerminal() {
        var f = pausedAtBody30();
        var old = DeathPreparation.pausedTask(f.chain);
        var history = f.chain.satisfiedStages();
        var trade = f.chain.currentPrimary().subtasks();
        f.record(Map.of(), 31);
        f.now = Long.parseLong(old.data().get("prepare_retry_at"));
        f.sample = self(f.id, false, false, true);
        String pause = f.chain.pauseReason("iron");
        assertTrue(recheckPaused(f));
        assertEquals(pause, f.chain.pauseReason("iron"));
        assertFalse(f.engine.isActive(f.id));
        f.sample = self(f.id, true, false, false);
        assertEquals(SideTaskWorldPort.PreparationState.READY,
                RddDeathEquipment.facts(f.sample, old, Map.of(), null).state(), "current SELF really wears the obligation");
        assertFalse(DeathPreparationHost.settlementIdentity(f.id, 29, old, f.live), "current SELF cannot authenticate old30");
        // Expected red on the unchanged entry: the paused marker30 blocks even a current READY sample.
        assertFalse(recheckPaused(f), "fresh31 READY must release recovery pause through current evidence");
        assertFalse(f.chain.isPaused("iron"));
        assertFalse(f.engine.isActive(f.id));
        assertEquals(1, f.callbacks.size(), "READY does not request another preparation plan");
        assertFalse(DeathPreparationHost.settlementIdentity(f.id, 29, old, f.live));
        assertEquals(history, f.chain.satisfiedStages());
        assertEquals(trade, f.chain.currentPrimary().subtasks());
        assertEquals("iron", f.chain.currentSubtask().id());
        assertNotNull(RddDeathLedger.openDeath(f.id, 29));
        assertEquals(RddDeathLedger.State.UNVERIFIED, RddDeathLedger.latest(f.id).state());
    }
}
