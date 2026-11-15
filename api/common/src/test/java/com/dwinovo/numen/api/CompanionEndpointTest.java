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
        int bindingReads, resolverReads;

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
