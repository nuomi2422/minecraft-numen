package com.dwinovo.numen.api;

import com.dwinovo.numen.agent.tool.NumenTool;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline contracts for enumerable state (2026-11-06); tests written, not run here.
 * Isolated registries avoid global plugin state and Minecraft setup.
 * Why not only test strings: identity, requiredness and invocation count are safety contracts.
 */
class StateFragmentRegistryTest {
    private static final UUID C = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void explicitIdentityDoesNotDependOnRegistrationOrderOrOtherOwners() {
        StateFragmentRegistry a = new StateFragmentRegistry();
        StateFragmentRegistry b = new StateFragmentRegistry();
        a.addLegacy(uuid -> "legacy");
        a.add("rdd", "facts", true, 9, uuid -> "facts");
        b.add("other", "facts", false, 1, uuid -> "other");
        b.add("rdd", "facts", true, 9, uuid -> "facts");
        assertEquals(a.entries(C, fail -> fail(fail)).get(1), b.entries(C, fail -> fail(fail)).get(1));
    }

    @Test
    void eachProducerCalledOncePerSnapshotWithoutCachingAndResultIsImmutable() {
        StateFragmentRegistry r = new StateFragmentRegistry();
        AtomicInteger calls = new AtomicInteger();
        r.addLegacy(uuid -> { assertEquals(C, uuid); return "v" + calls.incrementAndGet(); });
        List<StateFragment> first = r.entries(C, failure -> fail(failure));
        assertEquals(1, calls.get());
        assertTrue(first.getFirst().mandatory());
        assertThrows(UnsupportedOperationException.class, () -> first.clear());
        List<StateFragment> second = r.entries(C, failure -> fail(failure));
        assertEquals("v2", second.getFirst().text());
        assertEquals(first.getFirst().id(), second.getFirst().id());
    }

    @Test
    void nullBlankAndFailedProducersDoNotHideLaterRequiredContent() {
        StateFragmentRegistry r = new StateFragmentRegistry();
        List<Throwable> failures = new ArrayList<>();
        r.addLegacy(null);
        r.addLegacy(uuid -> null);
        r.addLegacy(uuid -> " \n");
        r.addLegacy(uuid -> { throw new IllegalStateException("bad"); });
        r.addLegacy(uuid -> { throw new NoClassDefFoundError("missing"); });
        r.addLegacy(uuid -> "safety");
        assertEquals(List.of("safety"), r.entries(C, failures::add).stream().map(StateFragment::text).toList());
        assertEquals(2, failures.size());
    }

    @Test
    void duplicateTypedIdentityIsRejectedWithoutReplacingRequiredContent() {
        StateFragmentRegistry r = new StateFragmentRegistry();
        r.add("rdd", "safety", true, 10, uuid -> "first");
        assertThrows(IllegalArgumentException.class,
                () -> r.add("rdd", "safety", false, 0, uuid -> "second"));
        List<StateFragment> entries = r.entries(C, failure -> fail(failure));
        assertEquals(List.of("first"), entries.stream().map(StateFragment::text).toList());
        assertTrue(entries.stream().allMatch(StateFragment::mandatory));
        assertEquals(1, entries.stream().filter(f -> f.owner().equals("rdd") && f.id().equals("safety")).count());
        assertEquals("first", NumenPlugins.renderStateFragments(entries));
    }

    @Test
    void legacyViewKeepsExactOrderWhitespaceAndSafetyPrefix() {
        StateFragmentRegistry r = new StateFragmentRegistry();
        r.addLegacy(uuid -> " <env>血量5</env>\n");
        r.add("rdd", "playbook", false, 7, uuid -> "<guide>night=1</guide>");
        List<StateFragment> entries = r.entries(C, failure -> fail(failure));
        assertEquals("<state_facts hp=5/>\n <env>血量5</env>\n<guide>night=1</guide>",
                NumenPlugins.renderStateFragments(entries));
        assertEquals("<state_facts hp=5/>\n", NumenPlugins.stateFactsOf(entries));
    }

    @Test
    void optionalFactsCannotOverrideRequiredFactsOrCreateUnknownFacts() {
        List<StateFragment> entries = List.of(
                new StateFragment("rdd", "guide", "血量1 night=1 hostile=true bed=0", false, 0),
                new StateFragment("rdd", "facts", "血量18 night=0 hostile=false", true, 10));
        assertEquals("<state_facts hp=18 night=0 hostile=0/>\n", NumenPlugins.stateFactsOf(entries));
        assertEquals("", NumenPlugins.stateFactsOf(entries.subList(0, 1)));
        assertEquals("", NumenPlugins.renderStateFragments(List.of()));
    }

    @Test
    void malformedNamesAndNullTextAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new StateFragment(" ", "id", "x", true, 0));
        assertThrows(IllegalArgumentException.class, () -> new StateFragment("owner", "", "x", true, 0));
        assertThrows(NullPointerException.class, () -> new StateFragment("owner", "id", null, true, 0));
        assertThrows(IllegalArgumentException.class, () -> new StateFragment("owner", "bad:id", "x", true, 0));
        assertThrows(IllegalArgumentException.class, () -> new StateFragment("bad<owner", "id", "x", true, 0));
        assertThrows(IllegalArgumentException.class, () -> new StateFragment("owner", "bad\"id", "x", true, 0));
    }

    @Test
    void oldImplementorDefaultKeepsOptionalTextInsteadOfDroppingIt() {
        LegacyApi old = new LegacyApi();
        AtomicInteger calls = new AtomicInteger();
        old.contributeState("rdd", "guide", false, 4, uuid -> { calls.incrementAndGet(); return "guide"; });
        assertEquals(0, calls.get());
        assertEquals("guide", old.callbacks.getFirst().apply(C));
        assertEquals(1, calls.get());
    }

    private static final class LegacyApi implements NumenApi {
        final List<Function<UUID, String>> callbacks = new ArrayList<>();
        public <T> T capability(Class<T> type, T impl) { return null; }
        public <T> T capability(Class<T> type) { return null; }
        public <T> void on(CompanionEvent<T> event, Consumer<T> handler) {}
        public void registerTool(NumenTool tool) {}
        public void bundleSkills(Path root) {}
        public void onClient(Runnable block) {}
        public Path configDir() { return null; }
        public void contributeState(Function<UUID, String> fragment) { callbacks.add(fragment); }
        public Delivery enqueue(UUID companion, String message) { return null; }
        public void contributePlanningKnowledge(PlanningKnowledgeContributor contributor) {}
    }
}

