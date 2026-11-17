package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.RddDeathLedger;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Current loss owner + real ledger and original facade; no Minecraft callbacks are exercised. */
class RddRepairLossAnalysisTest {
    @SuppressWarnings("unchecked")
    private static <T> T field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(null);
    }

    private static RddDeathLedger.Death selected(UUID id, int hint) throws Exception {
        var method = RddRepairLossAnalysis.class.getDeclaredMethod("deathForRecompute", UUID.class, int.class);
        method.setAccessible(true);
        return (RddDeathLedger.Death) method.invoke(null, id, hint);
    }

    @Test void explicitOpenAndFallbackBatchSelectionKeepOriginalLedgerSemantics() throws Exception {
        UUID id = UUID.randomUUID();
        RddDeathLedger.restore(id, new RddDeathLedger.LedgerSnapshot(1, java.util.List.of()));
        try {
            var first = RddDeathLedger.record(id, 1000L, System.currentTimeMillis(), "0,0,0", 1, Map.of("minecraft:dirt", 2));
            var latest = RddDeathLedger.record(id, 2000L, System.currentTimeMillis(), "1,0,0", 2, Map.of("minecraft:stone", 3));
            Map<UUID,Integer> open = field(RddRepairDispatch.class, "OPEN_DEATH_SEQ");
            open.put(id, first.seq());
            assertEquals(first.seq(), selected(id, -1).seq(), "original facade owns open batch");
            assertEquals(latest.seq(), selected(id, latest.seq()).seq(), "explicit hint wins");
            open.remove(id);
            assertEquals(first.seq(), selected(id, first.seq()).seq(), "hint survives timeout clearing OPEN");
            assertEquals(latest.seq(), selected(id, -1).seq(), "no open batch uses latest");
            RddDeathLedger.confirmRecovered(id, first.seq(), "test closed batch");
            assertEquals(latest.seq(), selected(id, first.seq()).seq(),
                    "existing closed-batch fallback retained; not a guarantee of historical exact-batch lookup");
        } finally {
            RddRepairDispatch.remove(id);
            RddDeathLedger.restore(id, new RddDeathLedger.LedgerSnapshot(1, java.util.List.of()));
        }
    }

    @Test void facadeRemoveClearsOnlyOriginalPhaseKeysNotRestartOrArmedGuards() throws Exception {
        UUID id = UUID.randomUUID(), other = UUID.randomUUID();
        Set<String> phases = field(RddRepairLossAnalysis.class, "RECOMPUTED");
        Map<UUID,Long> restart = field(RddRepairLossAnalysis.class, "LAST_RECOMPUTED");
        Set<UUID> armed = field(RddRepairLossAnalysis.class, "ARMED_LOGGED");
        try {
            phases.add(id + "|1|timeout"); phases.add(id + "|1|recovered"); phases.add(other + "|1|timeout");
            restart.put(id, 1L); armed.add(id);
            Map<UUID,Integer> open = field(RddRepairDispatch.class, "OPEN_DEATH_SEQ");
            open.put(id, 1);
            RddRepairDispatch.remove(id);
            assertFalse(open.containsKey(id), "dispatch batch ownership is still cleaned by its facade");
            assertFalse(phases.contains(id + "|1|timeout"));
            assertFalse(phases.contains(id + "|1|recovered"));
            assertTrue(phases.contains(other + "|1|timeout"));
            assertEquals(1L, restart.get(id));
            assertTrue(armed.contains(id), "process-lived armed/restart guards were not reset before extraction");
        } finally {
            phases.removeIf(key -> key.startsWith(id.toString()) || key.startsWith(other.toString()));
            restart.remove(id); armed.remove(id);
        }
    }

    @Test void emptyDeathAndNullFacadeBoundariesStayFailSoftWithoutHostReads() {
        assertNull(RddRepairLossAnalysis.analyzeDeathLoss(null, null));
        assertNull(RddRepairDispatch.recomputeAfterDeath(null, null, false, "restart"));
        assertNull(RddRepairDispatch.recomputeAfterDeath(null, Map.of(), true, "timeout", 5));
        assertEquals(Map.of(), RddRepairDispatch.backupFromBase(null));
        assertEquals("", RddRepairDispatch.renderSemanticNote(null));
    }

    // Compile the unchanged production owner with bounded host IO, so these cases also run
    // against the pre-fix owner and fail on behavior, never on a missing new helper.
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path output;

    private static com.dwinovo.numen.rdd.core.TaskChain reviewChain() {
        var chain = new com.dwinovo.numen.rdd.core.TaskChain(new com.dwinovo.numen.rdd.api.Goal(
                "real-goal", "reach the end", java.util.List.of(
                new com.dwinovo.numen.rdd.api.PrimaryGoal("past", "finished shelter", java.util.List.of(
                        com.dwinovo.numen.rdd.api.Subtask.hardCoded("past-0", "shelter", Map.of("minimum", 1))),
                        java.util.List.of(new com.dwinovo.numen.rdd.api.AssetRequirement("minecraft:diamond", 1))),
                new com.dwinovo.numen.rdd.api.PrimaryGoal("current", "trade now", java.util.List.of(
                        com.dwinovo.numen.rdd.api.Subtask.hardCoded("current-0", "trade", Map.of("asset_key", "minecraft:emerald", "minimum", 1))),
                        java.util.List.of(new com.dwinovo.numen.rdd.api.AssetRequirement("minecraft:flint", 1))),
                com.dwinovo.numen.rdd.api.PrimaryGoal.unexpanded("future", "mine obsidian", java.util.List.of(
                        new com.dwinovo.numen.rdd.api.AssetRequirement("minecraft:diamond_pickaxe", 1))))));
        chain.settlePrimaryByFact("past", "observed shelter", true);
        return chain;
    }

    private final class ConsumerFixture implements AutoCloseable {
        final java.net.URLClassLoader loader;
        final Class<?> host, owner;
        final UUID id = UUID.randomUUID();
        final com.dwinovo.numen.rdd.core.TaskChain chain = reviewChain();
        String before = chain.toJson();
        ConsumerFixture() throws Exception {
            java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
            while (root != null && !java.nio.file.Files.isDirectory(root.resolve("plugins/rdd/src/main/java"))) root = root.getParent();
            assertNotNull(root, "repository root");
            var src = root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd");
            var shell = output.resolve("RddPlugin.java");
            java.nio.file.Files.writeString(shell, HOST_SHELL);
            var args = new java.util.ArrayList<String>(java.util.List.of("-encoding", "UTF-8", "-proc:none", "-classpath",
                    System.getProperty("java.class.path") + java.io.File.pathSeparator
                            + java.nio.file.Path.of(com.dwinovo.numen.rdd.core.TaskChain.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                            + java.io.File.pathSeparator + java.nio.file.Path.of(com.google.gson.Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                            + java.io.File.pathSeparator + java.nio.file.Path.of(org.slf4j.Logger.class.getProtectionDomain().getCodeSource().getLocation().toURI()),
                    "-d", output.toString(), shell.toString()));
            for (String name : java.util.List.of("RddRepairLossAnalysis", "RddRequirementDetector", "RddSemanticsStore", "RddRepairLogPolicy"))
                args.add(src.resolve(name + ".java").toString());
            var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
            assertNotNull(compiler);
            assertEquals(0, compiler.run(null, null, null, args.toArray(String[]::new)), "compile actual owner before business assertions");
            loader = new java.net.URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (name.startsWith("com.dwinovo.numen.plugins.rdd.")) {
                        Class<?> found = findLoadedClass(name);
                        if (found == null) { try { found = findClass(name); } catch (ClassNotFoundException ignored) { } }
                        if (found != null) { if (resolve) resolveClass(found); return found; }
                    }
                    return super.loadClass(name, resolve);
                }
            };
            host = loader.loadClass("com.dwinovo.numen.plugins.rdd.RddPlugin");
            owner = loader.loadClass("com.dwinovo.numen.plugins.rdd.RddRepairLossAnalysis");
            assertEquals(output.toUri().toURL(), owner.getProtectionDomain().getCodeSource().getLocation());
            host.getMethod("reset", UUID.class, com.dwinovo.numen.rdd.core.RddRuntime.class).invoke(null, id,
                    new com.dwinovo.numen.rdd.core.RddRuntime(chain, new com.dwinovo.numen.rdd.core.AssetRegistry()));
        }
        com.dwinovo.numen.rdd.policy.LossImpactAnalyzer.LossImpact run(Map<String,Integer> lost,
                Map<String,Integer> held, Map<String,Integer> backup, boolean confirmed) throws Exception {
            host.getField("backup").set(null, backup);
            RddDeathLedger.restore(id, new RddDeathLedger.LedgerSnapshot(2, java.util.List.of(
                    new RddDeathLedger.Death(1, 1000, System.currentTimeMillis(), "0,64,0", 1,
                            RddDeathLedger.State.UNVERIFIED, "", lost))));
            var method = owner.getDeclaredMethod("recomputeAfterDeath", UUID.class, Map.class, boolean.class, String.class, int.class);
            method.setAccessible(true);
            return (com.dwinovo.numen.rdd.policy.LossImpactAnalyzer.LossImpact) method.invoke(null, id, held, confirmed, "confirmed_lost", 1);
        }
        void nextDetectorLedgerTick() throws Exception {
            var method = owner.getDeclaredMethod("recomputeLatestFromLedger", UUID.class, Map.class);
            method.setAccessible(true);
            method.invoke(null, id, Map.of());
        }
        @SuppressWarnings("unchecked") java.util.List<String> nudges() throws Exception {
            return (java.util.List<String>) host.getField("nudges").get(null);
        }
        void unchanged() { assertEquals(before, chain.toJson(), "review delivery must not rewrite/reset/activate the chain"); }
        @Override public void close() throws Exception {
            unchanged();
            RddDeathLedger.restore(id, new RddDeathLedger.LedgerSnapshot(1, java.util.List.of()));
            loader.close();
        }
    }

    @Test void confirmedFutureLazyCriticalLossDeliversRemainingReviewOnceWithoutChangingChain() throws Exception {
        try (var f = new ConsumerFixture()) {
            var result = f.run(Map.of("minecraft:diamond_pickaxe", 1), Map.of(), Map.of(), true);
            assertAll(
                    () -> assertNotNull(result),
                    () -> assertEquals(com.dwinovo.numen.rdd.policy.LossImpactAnalyzer.Recommendation.SUPPLEMENT, result.recommendation()),
                    () -> assertTrue(result.affectedGoals().stream().anyMatch(g -> g.requirementKey().equals("minecraft:diamond_pickaxe"))),
                    () -> assertEquals(1, f.nudges().size()),
                    () -> assertTrue(String.join("", f.nudges()).contains("rdd_replan_chain")),
                    () -> assertTrue(String.join("", f.nudges()).contains("mine obsidian")),
                    () -> assertTrue(String.join("", f.nudges()).contains("minecraft:diamond_pickaxe")));
            assertTrue(result.affectedGoals().stream().allMatch(g -> g.goalId().equals("real-goal")), "do not substitute primary IDs for goal identity");
            assertNull(f.run(Map.of("minecraft:diamond_pickaxe", 1), Map.of(), Map.of(), true));
            assertEquals(1, f.nudges().size(), "same batch/phase never redelivers review");
        }
    }

    @Test void rejectedConfirmedAdviceRetriesSamePhaseAndPlanOnceWithoutRepeatingObservation() throws Exception {
        try (var f = new ConsumerFixture()) {
            f.host.getField("rejectNudge").setBoolean(null, true);
            f.run(Map.of("minecraft:diamond_pickaxe", 1), Map.of(), Map.of(), true);
            assertTrue(f.nudges().isEmpty(), "false receipt is not delivery");
            assertEquals(1, f.host.getField("recomputeEvents").getInt(null));
            f.nextDetectorLedgerTick();
            assertEquals(1, f.host.getField("adviceAttempts").getInt(null), "read-only throttle peek avoids repeat delivery/log attempts");
            f.host.getField("rejectNudge").setBoolean(null, false);
            f.nextDetectorLedgerTick();
            assertEquals(1, f.nudges().size(), "existing Detector ledger cadence must deliver previously rejected advice");
            assertTrue(String.join("", f.nudges()).contains("rdd_replan_chain"));
            assertEquals(1, f.host.getField("recomputeEvents").getInt(null), "observation receipt is independent");
            f.nextDetectorLedgerTick();
            assertEquals(2, f.host.getField("adviceAttempts").getInt(null), "success is not retried");
            assertEquals(1, f.host.getField("recomputeEvents").getInt(null), "later tick must not open a redundant restart observation");
            assertEquals(1, f.nudges().size());
        }
    }

    @Test void rejectedAdviceCannotCrossRuntimeOwnerEvenAtSamePlanRevision() throws Exception {
        try (var f = new ConsumerFixture()) {
            f.host.getField("rejectNudge").setBoolean(null, true);
            f.run(Map.of("minecraft:diamond_pickaxe", 1), Map.of(), Map.of(), true);
            var replacement = reviewChain();
            String replacementBefore = replacement.toJson();
            f.host.getField("rt").set(null, new com.dwinovo.numen.rdd.core.RddRuntime(replacement,
                    new com.dwinovo.numen.rdd.core.AssetRegistry()));
            f.host.getField("rejectNudge").setBoolean(null, false);
            f.nextDetectorLedgerTick();
            assertTrue(f.nudges().isEmpty());
            assertEquals(1, f.host.getField("adviceAttempts").getInt(null));
            assertEquals(replacementBefore, replacement.toJson());
        }
    }

    @Test void rejectedAdviceCannotRedeliverOldBatchAfterNewDeath() throws Exception {
        try (var f = new ConsumerFixture()) {
            f.host.getField("rejectNudge").setBoolean(null, true);
            f.run(Map.of("minecraft:diamond_pickaxe", 1), Map.of(), Map.of(), true);
            RddDeathLedger.record(f.id, 2000, System.currentTimeMillis(), "1,64,0", 2, Map.of("minecraft:dirt", 1));
            f.host.getField("rejectNudge").setBoolean(null, false);
            f.nextDetectorLedgerTick();
            assertTrue(f.nudges().isEmpty());
            assertEquals(1, f.host.getField("adviceAttempts").getInt(null));
        }
    }

    @Test void currentAndFutureCriticalRequirementsRemainImportantInOneLocalUnion() throws Exception {
        try (var f = new ConsumerFixture()) {
            var result = f.run(Map.of("minecraft:flint", 1, "minecraft:diamond_pickaxe", 1), Map.of(), Map.of(), true);
            assertEquals(Set.of("minecraft:flint", "minecraft:diamond_pickaxe"),
                    result.affectedGoals().stream().map(com.dwinovo.numen.rdd.policy.LossImpactAnalyzer.GoalImpact::requirementKey)
                            .collect(java.util.stream.Collectors.toSet()));
            assertEquals(com.dwinovo.numen.rdd.policy.LossImpactAnalyzer.Recommendation.SUPPLEMENT, result.recommendation());
            assertTrue(String.join("", f.nudges()).contains("rdd_replan_chain"));
        }
    }

    @Test void existingImportantSemanticsAreNotDowngradedByNormalCurrentRequirement() throws Exception {
        try (var f = new ConsumerFixture()) {
            Class<?> store = f.loader.loadClass("com.dwinovo.numen.plugins.rdd.RddSemanticsStore");
            Map<UUID, Map<String, com.dwinovo.numen.rdd.policy.AssetSemantics>> table = field(store, "SEMANTICS");
            table.put(f.id, Map.of("minecraft:emerald", com.dwinovo.numen.rdd.policy.AssetSemantics.declared(
                    "minecraft:emerald", com.dwinovo.numen.rdd.policy.AssetSemantics.Importance.IMPORTANT,
                    com.dwinovo.numen.rdd.policy.AssetSemantics.Nature.MATERIAL, "real-goal")));
            var result = f.run(Map.of("minecraft:emerald", 1), Map.of(), Map.of(), true);
            assertEquals(com.dwinovo.numen.rdd.policy.LossImpactAnalyzer.Recommendation.SUPPLEMENT, result.recommendation());
            assertTrue(String.join("", f.nudges()).contains("rdd_replan_chain"));
            assertEquals(com.dwinovo.numen.rdd.policy.AssetSemantics.Importance.IMPORTANT,
                    table.get(f.id).get("minecraft:emerald").importance(), "analysis-local overlay must not rewrite stored semantics");
        }
    }

    @Test void declaredFutureRequirementsSurviveMissingCurrentManifestAndFailedRebuild() throws Exception {
        try (var f = new ConsumerFixture()) {
            f.host.getField("missingManifest").setBoolean(null, true);
            var result = f.run(Map.of("minecraft:diamond_pickaxe", 1), Map.of(), Map.of(), true);
            assertNotNull(result, "a missing current cache must not suppress declared future dependencies");
            assertEquals(com.dwinovo.numen.rdd.policy.LossImpactAnalyzer.Recommendation.SUPPLEMENT, result.recommendation());
            assertTrue(String.join("", f.nudges()).contains("rdd_replan_chain"));
        }
    }

    @Test void futureBackupSufficesWithoutWholeChainReview() throws Exception {
        try (var f = new ConsumerFixture()) {
            var result = f.run(Map.of("minecraft:diamond_pickaxe", 1), Map.of(), Map.of("minecraft:diamond_pickaxe", 1), true);
            assertEquals(com.dwinovo.numen.rdd.policy.LossImpactAnalyzer.Recommendation.SWAP_BACKUP, result.recommendation());
            assertEquals(1, f.nudges().size());
            assertFalse(String.join("", f.nudges()).contains("rdd_replan_chain"));
        }
    }

    @Test void unknownInventoryAndUnconfirmedFutureLossDoNotDeliverReview() throws Exception {
        try (var f = new ConsumerFixture()) {
            var result = f.run(Map.of("minecraft:diamond_pickaxe", 1), null, Map.of(), true);
            assertEquals(com.dwinovo.numen.rdd.policy.LossImpactAnalyzer.Recommendation.CONFIRM_STOCK, result.recommendation());
            assertTrue(f.nudges().isEmpty());
        }
        // A fresh companion/batch is required; phase dedupe is intentionally not bypassed.
        try (var f = new ConsumerFixture()) {
            var result = f.run(Map.of("minecraft:diamond_pickaxe", 1), Map.of(), Map.of(), false);
            assertEquals(com.dwinovo.numen.rdd.policy.LossImpactAnalyzer.Recommendation.CONFIRM_STOCK, result.recommendation());
            assertTrue(f.nudges().isEmpty());
        }
    }

    @Test void unrelatedLossConsumptionAndPastOnlyRequirementsStayQuiet() throws Exception {
        for (var lost : java.util.List.of(Map.of("minecraft:dirt", 1), Map.of("minecraft:diamond", 1), Map.<String,Integer>of())) {
            try (var f = new ConsumerFixture()) {
                var result = f.run(lost, Map.of(), Map.of(), true);
                if (lost.isEmpty()) assertNull(result, "ordinary consumption supplies no death loss");
                else assertEquals(com.dwinovo.numen.rdd.policy.LossImpactAnalyzer.Recommendation.NONE, result.recommendation());
                assertTrue(f.nudges().isEmpty());
            }
        }
    }

    @Test void rejectedFinalStageAdviceCannotDeliverAfterRealChainCompletion() throws Exception {
        try (var f = new ConsumerFixture()) {
            f.chain.settlePrimaryByFact("current", "verified trade world fact", true);
            assertEquals("future", f.chain.currentPrimary().id());
            assertEquals(com.dwinovo.numen.rdd.api.PrimaryGoalStatus.PENDING, f.chain.primaryStatus());
            int revision = f.chain.planRevision();
            f.host.getField("rejectNudge").setBoolean(null, true);
            f.run(Map.of("minecraft:diamond_pickaxe", 1), Map.of(), Map.of(), true);
            assertEquals(1, f.host.getField("adviceAttempts").getInt(null), "final-stage advice was actually rejected first");
            assertTrue(f.nudges().isEmpty());
            f.chain.settlePrimaryByFact("future", "verified final goal world fact", true);
            assertEquals(com.dwinovo.numen.rdd.api.PrimaryGoalStatus.COMPLETED, f.chain.primaryStatus());
            assertEquals("future", f.chain.currentPrimary().id(), "completion retains last primary, not null");
            assertEquals(revision, f.chain.planRevision(), "completion alone must invalidate advice even without a revision change");
            f.before = f.chain.toJson(); // only the explicit world-fact transitions above may change the fixture
            f.host.getField("rejectNudge").setBoolean(null, false);
            f.nextDetectorLedgerTick();
            f.nextDetectorLedgerTick();
            assertAll(
                    () -> assertTrue(f.nudges().isEmpty(), "completed chain must not receive an old repair/review"),
                    () -> assertEquals(1, f.host.getField("adviceAttempts").getInt(null)),
                    () -> assertEquals(1, f.host.getField("recomputeEvents").getInt(null), "no repeated terminal observation"));
        }
    }

    private static void cacheCurrentThenAdvanceToFuture(ConsumerFixture f) throws Exception {
        Class<?> detector = f.loader.loadClass("com.dwinovo.numen.plugins.rdd.RddRequirementDetector");
        var build = detector.getDeclaredMethod("forPrimary", com.dwinovo.numen.rdd.api.PrimaryGoal.class, String.class);
        build.setAccessible(true);
        Object cached = build.invoke(null, f.chain.currentPrimary(), f.chain.goal().id());
        f.host.getField("cachedManifestOverride").set(null, cached);
        f.chain.settlePrimaryByFact("current", "verified trade before cache refresh", true);
        assertEquals("future", f.chain.currentPrimary().id());
        f.before = f.chain.toJson();
    }

    @Test void stalePreviousPrimaryManifestCannotHideCurrentPrimaryCriticalLoss() throws Exception {
        try (var f = new ConsumerFixture()) {
            cacheCurrentThenAdvanceToFuture(f);
            var result = f.run(Map.of("minecraft:diamond_pickaxe", 1), Map.of(), Map.of(), true);
            assertNotNull(result);
            assertAll(
                    () -> assertEquals(com.dwinovo.numen.rdd.policy.LossImpactAnalyzer.Recommendation.SUPPLEMENT, result.recommendation()),
                    () -> assertTrue(result.affectedGoals().stream().anyMatch(g -> g.requirementKey().equals("minecraft:diamond_pickaxe"))),
                    () -> assertEquals(1, f.nudges().size()),
                    () -> assertTrue(String.join("", f.nudges()).contains("rdd_replan_chain")));
        }
    }

    @Test void stalePreviousPrimaryOnlyRequirementDoesNotProduceRepairOrReview() throws Exception {
        try (var f = new ConsumerFixture()) {
            cacheCurrentThenAdvanceToFuture(f);
            var result = f.run(Map.of("minecraft:flint", 1), Map.of(), Map.of(), true);
            assertNotNull(result);
            assertAll(
                    () -> assertEquals(com.dwinovo.numen.rdd.policy.LossImpactAnalyzer.Recommendation.NONE, result.recommendation()),
                    () -> assertTrue(result.affectedGoals().isEmpty(), "old primary requirement is not a remaining demand"),
                    () -> assertTrue(f.nudges().isEmpty()));
        }
    }

    private static final String HOST_SHELL = """
            package com.dwinovo.numen.plugins.rdd;
            import com.dwinovo.numen.rdd.core.*;
            import com.dwinovo.numen.rdd.policy.*;
            import java.util.*;
            public class RddPlugin {
                public static RddRuntime rt;
                public static Map<String,Integer> backup = Map.of();
                public static List<String> nudges = new ArrayList<>();
                public static boolean missingManifest, rejectNudge;
                public static RequirementManifest.Manifest cachedManifestOverride;
                public static int adviceAttempts, recomputeEvents;
                public static void reset(UUID id, RddRuntime runtime) { rt = runtime; backup = Map.of(); nudges.clear(); missingManifest = false; rejectNudge = false; adviceAttempts = 0; recomputeEvents = 0; cachedManifestOverride = null; }
                static RddRuntime runtime(UUID id) { return rt; }
                static void ensureLedgerLoaded(UUID id) {}
                static boolean nudgeAllowed(UUID id) { return !rejectNudge; }
                static boolean nudge(UUID id, String text) { adviceAttempts++; if (rejectNudge) return false; nudges.add(text); return true; }
            }
            class RddFactContext {
                static RequirementManifest.Manifest currentManifest(UUID id) {
                    if (RddPlugin.missingManifest) return null;
                    if (RddPlugin.cachedManifestOverride != null) return RddPlugin.cachedManifestOverride;
                    return RddRequirementDetector.forPrimary(RddPlugin.rt.chain().currentPrimary(), RddPlugin.rt.chain().goal().id());
                }
                static RequirementManifest.Manifest ensureManifest(UUID id, com.dwinovo.numen.rdd.api.PrimaryGoal p, String gid) {
                    if (RddPlugin.missingManifest) throw new IllegalStateException("bounded cache rebuild failure");
                    return RddRequirementDetector.forPrimary(p, gid);
                }
            }
            class RddRepairDispatch { static int openDeathSeq(UUID id) { return 1; } }
            class RddMonitor { static void publish(String event, Map<String,?> data) { if (event.equals("loss_impact_recomputed")) RddPlugin.recomputeEvents++; } }
            class RddCompanionBody {
                static RddCompanionBody get(UUID id) { return null; }
                RddCompanionBody getFoodData() { return this; }
                int getFoodLevel() { return 20; }
            }
            class RddAssetContext {
                record Entry(Observation observation) {}
                record Observation(String type, long observedAt, Map<String,Object> value) {}
                static List<Entry> worldAssets(AssetRegistry assets) {
                    return List.of(new Entry(new Observation("world_base", System.currentTimeMillis(), Map.of("important_items", RddPlugin.backup))));
                }
            }
            """;
}
