package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.side.MainlineResumeToken;
import com.dwinovo.numen.rdd.side.RecurringSideTaskSpec;
import com.dwinovo.numen.rdd.side.SideTaskEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SideRegistrationStoreTest {
    private static final UUID SELF = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String BLOCK = "<rdd><side_task>legacy</side_task></rdd>";

    private static MainlineResumeToken owner(UUID id, String chain, String goal, int rev) {
        return new MainlineResumeToken(id, chain, goal, "primary", "subtask", rev, rev);
    }
    private static SideContextStore.Ticket ticket(String world, String session, String dim,
                                                MainlineResumeToken owner, long day) {
        return new SideContextStore.Ticket(world, session, dim, owner, day);
    }
    private static SideContextStore.Ticket current() {
        return ticket("world", "session", "dim", owner(SELF, "chain", "goal", 4), 10);
    }
    private static RecurringSideTaskSpec spec(Map<String, String> data) {
        return new RecurringSideTaskSpec("daily:goal:abc", "daily_maintenance", 2, 3,
                RecurringSideTaskSpec.MissedPolicy.SKIP, true, false, data);
    }

    @Test void everyIdentityBoundaryRejectsStaleFacts() {
        SideContextStore store = new SideContextStore();
        store.putRegistrations(SELF, current(), List.of(spec(Map.of())));
        assertEquals(1, store.registrationsFor(SELF, current()).size());
        var owner = current().owner();
        var mismatches = List.of(
                ticket("other", "session", "dim", owner, 10),
                ticket("world", "other", "dim", owner, 10),
                ticket("world", "session", "other", owner, 10),
                ticket("world", "session", "dim", owner(OTHER, "chain", "goal", 4), 10),
                ticket("world", "session", "dim", owner(SELF, "replacement", "goal", 4), 10),
                ticket("world", "session", "dim", owner(SELF, "chain", "other", 4), 10),
                ticket("world", "session", "dim", owner(SELF, "chain", "goal", 5), 10),
                ticket("world", "session", "dim", owner, 11));
        for (var mismatch : mismatches) assertNull(store.registrationsFor(SELF, mismatch));
        assertNull(store.registrationsFor(OTHER, current()));
        assertNull(store.registrationsFor(null, current()));
        assertNull(store.registrationsFor(SELF, null));
        assertEquals(1, store.registrationsFor(SELF, current()).size(), "read mismatch must not evict");
    }

    @Test void missingIdentityIsUnknownEvenWhenBothTicketsWouldMatch() {
        var owner = current().owner();
        var missing = new ArrayList<SideContextStore.Ticket>();
        missing.add(null);
        missing.add(ticket(null, "session", "dim", owner, 10));
        missing.add(ticket("world", " ", "dim", owner, 10));
        missing.add(ticket("world", "session", "", owner, 10));
        missing.add(ticket("world", "session", "dim", null, 10));
        missing.add(ticket("world", "session", "dim", owner(SELF, null, "goal", 4), 10));
        missing.add(ticket("world", "session", "dim", owner(SELF, "chain", "", 4), 10));
        missing.add(ticket("world", "session", "dim", owner(SELF, "chain", "goal", -1), 10));
        missing.add(ticket("world", "session", "dim", owner, -1));
        for (var absent : missing) {
            SideContextStore store = new SideContextStore();
            store.putRegistrations(SELF, current(), List.of(spec(Map.of())));
            store.putRegistrations(SELF, absent, List.of());
            assertNull(store.registrationsFor(SELF, absent));
            assertNull(store.registrationsFor(SELF, current()), "invalid refresh drops stale registrations");
        }
    }

    @Test void emptySnapshotIsKnownButCursorChangesAreNotOwnerChanges() {
        SideContextStore store = new SideContextStore();
        assertNull(store.registrationsFor(SELF, current()));
        store.putRegistrations(SELF, current(), List.of());
        var advanced = new MainlineResumeToken(SELF, "chain", "goal", "next-primary", "next-subtask", 4, 99);
        var result = store.registrationsFor(SELF, ticket("world", "session", "dim", advanced, 10));
        assertNotNull(result);
        assertTrue(result.isEmpty());
        store.putRegistrations(SELF, current(), null);
        assertNull(store.registrationsFor(SELF, current()));
    }

    @Test void engineAndCacheSnapshotsAreImmutableAndDetachedFromLaterWrites() {
        SideTaskEngine engine = new SideTaskEngine(null, null);
        Map<String, String> payload = new HashMap<>(Map.of("description", "registered"));
        var registered = spec(payload);
        engine.registerRecurring(registered, "daily_maintenance");
        var snapshot = engine.recurringSpecsSnapshot();
        assertEquals(List.of(registered), snapshot);
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        payload.put("description", "mutated");
        assertEquals("registered", snapshot.get(0).data().get("description"));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.get(0).data().put("description", "mutated"));
        engine.clearRecurring();
        assertTrue(engine.recurringSpecsSnapshot().isEmpty());
        assertEquals(1, snapshot.size());
        assertNull(engine.handledToday(SELF, "world", registered.scheduleId(), 10));
        assertFalse(engine.isActive(SELF));

        SideContextStore store = new SideContextStore();
        var input = new ArrayList<>(snapshot);
        store.putRegistrations(SELF, current(), input);
        input.clear();
        var cached = store.registrationsFor(SELF, current());
        assertEquals(snapshot, cached);
        assertThrows(UnsupportedOperationException.class, cached::clear);
        store.putRegistrations(SELF, current(), List.of());
        assertEquals(1, cached.size(), "published snapshots stay unchanged after replacement");
        assertTrue(store.registrationsFor(SELF, current()).isEmpty());
    }

    @Test void registrationLifecycleDoesNotChangeLegacyRender() {
        SideContextStore store = new SideContextStore();
        store.put(SELF, BLOCK, "world", "session", "dim", 7);
        String legacy = store.render(SELF, "world", "session");
        store.putRegistrations(SELF, current(), List.of(spec(Map.of())));
        assertEquals(legacy, store.render(SELF, "world", "session"));
        store.removeRegistrations(SELF);
        assertNull(store.registrationsFor(SELF, current()));
        assertEquals(legacy, store.render(SELF, "world", "session"));
        store.putRegistrations(SELF, current(), List.of());
        store.remove(SELF);
        assertNull(store.render(SELF, "world", "session"));
        assertNull(store.registrationsFor(SELF, current()));
        store.put(SELF, BLOCK, "world", "session", "dim", 7);
        store.putRegistrations(SELF, current(), List.of());
        store.clear();
        assertNull(store.render(SELF, "world", "session"));
        assertNull(store.registrationsFor(SELF, current()));
    }
}
