package com.dwinovo.numen.api;

import com.dwinovo.numen.agent.llm.LlmEndpoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Selected-endpoint facade regression source: same queued resolver as production, no Minecraft/config/HTTP.
 * Why not load ProviderLibrary here: that would read a player's credentials instead of fake sentinels.
 * Run through the existing api:common test entry when Java testing is authorized; source alone is not a pass.
 */
class CompanionEndpointTest {
    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final LlmEndpoint EMPTY = new LlmEndpoint("", "", "", "", "", "");

    @Test void queuesBeforeReadingAndReturnsOnlyTheSelectedProfile() {
        Fixture f = new Fixture();
        LlmEndpoint selected = profile("deepseek", "FAKE_PROFILE_A");
        f.profiles.put("a", selected);
        var result = f.lookup(A);
        assertFalse(result.isDone());
        assertEquals(0, f.bindingReads);
        assertEquals(0, f.resolverReads);
        f.queue.runAll();
        assertSame(selected, result.join());
        assertEquals(List.of(A), f.bindingIds);
        assertEquals(List.of("a"), f.profileIds);
        // No global endpoint/config is an input: a blank global key cannot veto this selection.
    }

    @Test void twoCompanionsAndLaterProfileChangesNeverBorrowCredentials() {
        Fixture f = new Fixture();
        f.known.add(B); f.bindings.put(B, "b");
        f.profiles.put("a", profile("openai", "FAKE_PROFILE_A"));
        f.profiles.put("b", profile("deepseek", "FAKE_PROFILE_B"));
        var a = f.lookup(A); var b = f.lookup(B);
        f.queue.runAll();
        assertEquals("FAKE_PROFILE_A", a.join().apiKey());
        assertEquals("FAKE_PROFILE_B", b.join().apiKey());
        f.bindings.put(A, "b");
        var switched = f.lookup(A);
        f.queue.runAll();
        assertSame(b.join(), switched.join());
        assertEquals(List.of("a", "b", "b"), f.profileIds);
    }

    @Test void unboundDeletedAndKeylessProfilesNeverAcquireAnotherKey() {
        Fixture f = new Fixture();
        f.bindings.remove(A);
        var unbound = f.lookup(A); f.queue.runAll();
        assertNull(unbound.join());
        assertEquals(0, f.resolverReads);
        f.bindings.put(A, "deleted");
        var deleted = f.lookup(A); f.queue.runAll();
        assertSame(EMPTY, deleted.join());
        f.profiles.put("deleted", profile("openai", " "));
        var keyless = f.lookup(A); f.queue.runAll();
        assertFalse(keyless.join().hasApiKey());
        assertEquals(List.of("deleted", "deleted"), f.profileIds);
    }

    @Test void nullUnknownAndWorldNotReadyRejectBeforeBindingReads() {
        Fixture f = new Fixture();
        var absent = f.lookup(null); var unknown = f.lookup(B);
        f.worldReady = false;
        var offline = f.lookup(A);
        f.queue.runAll();
        assertNull(absent.join()); assertNull(unknown.join()); assertNull(offline.join());
        assertEquals(0, f.bindingReads); assertEquals(0, f.resolverReads);
    }

    @Test void changedWorldGenerationBeforeClientTaskRejectsWithoutLookup() {
        Fixture f = new Fixture();
        var result = f.lookup(A);
        f.generation.incrementAndGet();
        f.queue.runAll();
        assertNull(result.join());
        assertEquals(0, f.bindingReads); assertEquals(0, f.resolverReads);
    }

    @Test void generationChangeDuringResolutionDoesNotPublishAnEndpoint() {
        Fixture f = new Fixture();
        var result = f.lookup(A, id -> {
            f.generation.incrementAndGet();
            return profile("openai", "FAKE_OLD_WORLD");
        });
        f.queue.runAll();
        assertNull(result.join());
    }

    @Test void schedulingAndLookupErrorsCompleteInsteadOfHangingOrFallingBack() {
        Fixture schedule = new Fixture(); schedule.queue.reject = true;
        var rejected = schedule.lookup(A);
        assertThrows(CompletionException.class, rejected::join);
        assertEquals(0, schedule.bindingReads);
        Fixture lookup = new Fixture();
        var failed = lookup.lookup(A, id -> { throw new IllegalStateException("fixture lookup failed"); });
        lookup.queue.runAll();
        assertThrows(CompletionException.class, failed::join);
        assertEquals(1, lookup.bindingReads);
    }

    @Test void clientThreadCallUsesSameResolverWithoutAnotherQueueOrLoopCreation() {
        Fixture f = new Fixture(); f.queue.client = true;
        var endpoint = profile("openai", "FAKE_PROFILE_A"); f.profiles.put("a", endpoint);
        assertSame(endpoint, f.lookup(A).join());
        assertTrue(f.queue.tasks.isEmpty());
        assertEquals(1, f.bindingReads);
    }

    // Same real planner port as RDD; an explicit choice must never borrow execution credentials.
    @Test void independentPlannerWinsWithoutReadingOrRebindingExecution() {
        Fixture f = new Fixture(); f.plannerId = "planner";
        var planner = profile("deepseek", "FAKE_PLANNER_A");
        f.profiles.put("planner", planner);
        f.profiles.put("a", profile("openai", "FAKE_EXECUTOR_B"));
        var selected = f.plannerLookup();
        assertFalse(selected.isDone()); assertEquals(0, f.plannerReads);
        f.queue.runAll();
        assertSame(planner, selected.join());
        assertEquals(1, f.plannerReads); assertEquals(0, f.bindingReads);
        assertEquals(List.of("planner"), f.profileIds);
        assertEquals("a", f.bindings.get(A));
    }

    @Test void onlyAnAbsentPlannerReferenceInheritsExecution() {
        for (String absent : new String[] {null, "", " "}) {
            Fixture f = new Fixture(); f.plannerId = absent;
            var execution = profile("openai", "FAKE_EXECUTOR_B"); f.profiles.put("a", execution);
            var selected = f.plannerLookup(); f.queue.runAll();
            assertSame(execution, selected.join());
            assertEquals(List.of("a"), f.profileIds); assertEquals("a", f.bindings.get(A));
        }
    }

    @Test void deletedAndKeylessIndependentPlannerNeverBorrowExecutionCredentials() {
        for (boolean keyless : List.of(false, true)) {
            Fixture f = new Fixture(); f.plannerId = "planner";
            f.profiles.put("a", profile("openai", "FAKE_EXECUTOR_B"));
            if (keyless) f.profiles.put("planner", profile("deepseek", " "));
            var selected = f.plannerLookup(); f.queue.runAll();
            assertNotNull(selected.join());
            assertFalse(selected.join().hasApiKey(), "configured but unusable is not unconfigured");
            assertEquals(List.of("planner"), f.profileIds); assertEquals(0, f.bindingReads);
        }
    }

    @Test void independentPlannerLookupFailureCannotTriggerExecutionFallback() {
        Fixture f = new Fixture(); f.plannerId = "planner";
        f.profiles.put("a", profile("openai", "FAKE_EXECUTOR_B"));
        var selected = f.plannerLookup(id -> {
            if ("planner".equals(id)) throw new IllegalStateException("fixture invalid planner");
            return f.profiles.get(id);
        });
        f.queue.runAll();
        assertThrows(CompletionException.class, selected::join); assertEquals(0, f.bindingReads);
    }

    @Test void plannerSwitchAndClearUseNextSelectionWithoutMutatingExecutionBinding() {
        Fixture f = new Fixture();
        var a = profile("deepseek", "FAKE_PLANNER_A");
        var c = profile("openai", "FAKE_PLANNER_C");
        var b = profile("openai", "FAKE_EXECUTOR_B");
        f.profiles.put("planner-a", a); f.profiles.put("planner-c", c); f.profiles.put("a", b);
        f.plannerId = "planner-a";
        var first = f.plannerLookup(); f.queue.runAll(); assertSame(a, first.join());
        f.plannerId = "planner-c";
        var switched = f.plannerLookup(); f.queue.runAll(); assertSame(c, switched.join());
        f.plannerId = null;
        var inherited = f.plannerLookup(); f.queue.runAll(); assertSame(b, inherited.join());
        assertEquals("a", f.bindings.get(A));
        assertEquals(List.of("planner-a", "planner-c", "a"), f.profileIds);
    }

    @Test void plannerWorldChangeBeforeQueueOrDuringResolutionPublishesNoOldEndpoint() {
        Fixture queued = new Fixture(); queued.plannerId = "planner";
        var old = queued.plannerLookup(); queued.generation.incrementAndGet(); queued.queue.runAll();
        assertNull(old.join()); assertEquals(0, queued.plannerReads); assertEquals(0, queued.bindingReads);
        Fixture resolving = new Fixture(); resolving.plannerId = "planner";
        var changed = resolving.plannerLookup(id -> {
            if ("planner".equals(id)) resolving.generation.incrementAndGet();
            return profile("deepseek", "FAKE_OLD_PLANNER");
        });
        resolving.queue.runAll();
        assertNull(changed.join()); assertEquals(0, resolving.bindingReads);
    }

    @Test void plannerReferenceRoundTripsAndDeletedSelectionNeverTurnsIntoInheritance(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var file = dir.resolve("providers.json");
        var lib = profileLibrary(file);
        var entry = lib.create("planner A", "deepseek", "fixture-model", "FAKE_ONLY", "https://fixture.invalid/v1", "high", "", 0);
        lib.setPlannerEntryId(entry.id());
        lib.remove(entry.id());
        var loaded = profileLibrary(file); loadLibrary(loaded);
        assertEquals(entry.id(), loaded.plannerEntryId(), "deleted explicit reference must not inherit");
        assertNull(loaded.get(entry.id()));
        loaded.setPlannerEntryId(null);
        var inherited = profileLibrary(file); loadLibrary(inherited);
        assertNull(inherited.plannerEntryId());
        importLegacy(inherited, "FAKE_LEGACY", "https://old.invalid/v1");
        assertNull(inherited.plannerEntryId(), "explicit inheritance must survive old global settings");
        assertTrue(inherited.list().isEmpty());
    }

    @Test void legacyExplicitApiImportsOnceWithVisibleSourceButDefaultModelDoesNot(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var lib = profileLibrary(dir.resolve("providers.json"));
        importLegacy(lib, "", "");
        assertNull(lib.plannerEntryId()); assertTrue(lib.list().isEmpty());
        importLegacy(lib, "", "https://legacy.invalid/v1");
        assertEquals("legacy-global-planner", lib.plannerEntryId());
        var original = lib.get(lib.plannerEntryId());
        assertEquals("旧全局规划配置（兼容导入）", original.name());
        assertEquals("", original.apiKey(), "incomplete legacy selection must stay independently incomplete");
        importLegacy(lib, "FAKE_LATER", "https://changed.invalid/v1");
        assertSame(original, lib.get(lib.plannerEntryId()));
        assertEquals(1, lib.list().size());
    }

    @Test void corruptPlannerReferenceCannotSilentlyInheritOrImportGlobals(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var file = dir.resolve("providers.json");
        java.nio.file.Files.writeString(file, "{\"entries\":[],\"planner_provider\":123}");
        var lib = profileLibrary(file); loadLibrary(lib);
        assertEquals("invalid-planner-configuration", lib.plannerEntryId());
        importLegacy(lib, "FAKE_LEGACY", "https://old.invalid/v1");
        assertEquals("invalid-planner-configuration", lib.plannerEntryId());
        assertTrue(lib.list().isEmpty());
    }

    private static com.dwinovo.numen.agent.llm.ProviderLibrary profileLibrary(java.nio.file.Path path) throws Exception {
        var constructor = com.dwinovo.numen.agent.llm.ProviderLibrary.class.getDeclaredConstructor(java.nio.file.Path.class);
        constructor.setAccessible(true);
        return constructor.newInstance(path);
    }

    private static void loadLibrary(com.dwinovo.numen.agent.llm.ProviderLibrary lib) throws Exception {
        var load = com.dwinovo.numen.client.data.JsonLibrary.class.getDeclaredMethod("load");
        load.setAccessible(true); load.invoke(lib);
    }

    private static void importLegacy(com.dwinovo.numen.agent.llm.ProviderLibrary lib, String key, String url) throws Exception {
        var method = com.dwinovo.numen.agent.llm.ProviderLibrary.class.getDeclaredMethod("importLegacyPlanner",
                String.class, String.class, String.class, String.class, String.class, String.class);
        method.setAccessible(true);
        method.invoke(lib, "openai", "gpt-5-2-mini", key, url, "", "auto");
    }

    private static LlmEndpoint profile(String provider, String key) {
        return new LlmEndpoint(provider, "fixture-model", key, "https://fixture.invalid/v1", "fixture-proxy", "high");
    }

    private static final class Fixture {
        final Queue queue = new Queue();
        final AtomicLong generation = new AtomicLong(7);
        final java.util.Set<UUID> known = new java.util.HashSet<>(List.of(A));
        final Map<UUID, String> bindings = new HashMap<>(Map.of(A, "a"));
        final Map<String, LlmEndpoint> profiles = new HashMap<>();
        final List<UUID> bindingIds = new ArrayList<>();
        final List<String> profileIds = new ArrayList<>();
        boolean worldReady = true;
        int bindingReads, resolverReads, plannerReads;
        String plannerId;

        CompletableFuture<LlmEndpoint> plannerLookup() {
            return plannerLookup(id -> profiles.getOrDefault(id, EMPTY));
        }

        CompletableFuture<LlmEndpoint> plannerLookup(Function<String, LlmEndpoint> resolver) {
            return NumenGateway.plannerEndpoint(A, queue,
                    id -> { assertTrue(queue.client); return worldReady && known.contains(id); },
                    () -> { assertTrue(queue.client); plannerReads++; return plannerId; },
                    id -> { assertTrue(queue.client); bindingReads++; bindingIds.add(id); return bindings.get(id); },
                    id -> { assertTrue(queue.client); resolverReads++; profileIds.add(id); return resolver.apply(id); },
                    generation::get);
        }

        CompletableFuture<LlmEndpoint> lookup(UUID companion) {
            return lookup(companion, id -> {
                resolverReads++; profileIds.add(id);
                return profiles.getOrDefault(id, EMPTY);
            });
        }
        CompletableFuture<LlmEndpoint> lookup(UUID companion, Function<String, LlmEndpoint> resolver) {
            return NumenGateway.companionEndpoint(companion, queue,
                    id -> { assertTrue(queue.client); return worldReady && known.contains(id); },
                    id -> { assertTrue(queue.client); bindingReads++; bindingIds.add(id); return bindings.get(id); },
                    id -> { assertTrue(queue.client); return resolver.apply(id); }, generation::get);
        }
    }

    private static final class Queue implements EnqueueScheduling {
        final List<Runnable> tasks = new ArrayList<>();
        boolean client, reject;
        public boolean onClientThread() { return client; }
        public void executeOnClient(Runnable task) {
            if (reject) throw new IllegalStateException("fixture queue rejected");
            tasks.add(task);
        }
        public ClientBinding captureClient(UUID id) { throw new AssertionError("endpoint lookup must not resolve a body"); }
        public CompletableFuture<ServerSample> sampleServerAsync(UUID id) {
            throw new AssertionError("endpoint lookup must not read server facts");
        }
        void runAll() {
            client = true;
            try { while (!tasks.isEmpty()) tasks.removeFirst().run(); }
            finally { client = false; }
        }
    }
}
