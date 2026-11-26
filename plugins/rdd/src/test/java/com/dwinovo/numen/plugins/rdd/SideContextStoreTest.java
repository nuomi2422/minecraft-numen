package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SideContextStoreTest {
    private static final String BLOCK = "<rdd><side_task>old</side_task></rdd>";

    @Test void removeAndClearDropOldFacts() {
        UUID a = UUID.randomUUID(); UUID b = UUID.randomUUID();
        SideContextStore s = new SideContextStore();
        s.put(a, BLOCK, "w", "s", "d", 1); s.put(b, BLOCK, "w", "s", "d", 1);
        s.remove(a);
        assertNull(s.render(a, "w", "s"));
        assertNotNull(s.render(b, "w", "s"));
        s.clear();
        assertNull(s.render(b, "w", "s"));
    }

    @Test void crossSessionAndMissingIdentityAreUnknown() {
        UUID a = UUID.randomUUID(); SideContextStore s = new SideContextStore();
        s.put(a, BLOCK, "w1", "s1", "d", 1);
        assertTrue(s.render(a, "w2", "s1").contains("<side_context_status>UNKNOWN</side_context_status>"));
        assertTrue(s.render(a, "w1", "s2").contains("<side_context_status>UNKNOWN</side_context_status>"));
        assertTrue(s.render(null, "w1", "s1") == null);
    }

    @Test void crossWorldAndSessionTogetherLeaksNoOldFacts() {
        UUID a = UUID.randomUUID(); SideContextStore s = new SideContextStore();
        s.put(a, BLOCK, "world-A", "session-A", "dim-A", 1);
        String out = s.render(a, "world-B", "session-B");
        assertTrue(out.contains("<side_context_status>UNKNOWN</side_context_status>"));
        assertFalse(out.contains("old</side_task>"));
    }

    // 2026-11-24: B advisory cache contract on the real SideContextStore.
    // Reflection keeps this test compiling before the host API lands; it is RED until 3号's putAdvisory/advisoryFor exists.
    @Test void advisoryContrastsTwoWorldSessionPairs() throws Exception {
        UUID id = UUID.randomUUID(); Object s = newStore();
        putAdvisory(s, id, advisory("world-A", "session-A", "dim-A", 111L));
        assertNull(advisoryFor(s, id, "world-B", "session-B"), "different world/session must not leak");
        Object got = advisoryFor(s, id, "world-A", "session-A");
        assertNotNull(got, "same world/session must return the cached snapshot");
        assertEquals("world-A", field(got, "worldId"));
        assertEquals("session-A", field(got, "serverSession"));
        assertEquals("dim-A", field(got, "dimension"));
        assertEquals(111L, field(got, "sampledGameTime"));
    }

    @Test void advisoryEmptyIdentityNeverBorrowsOld() throws Exception {
        UUID id = UUID.randomUUID(); Object s = newStore();
        putAdvisory(s, id, advisory("world-A", "session-A", "dim-A", 7L));
        assertNull(advisoryFor(s, id, null, null), "null request identity must not borrow a cached stamp");
        assertNull(advisoryFor(s, id, "", ""), "blank request identity must not borrow a cached stamp");
        assertNull(advisoryFor(s, null, "world-A", "session-A"), "null companion must not read another cache entry");
    }

    @Test void advisoryRemoveAndClearInvalidate() throws Exception {
        UUID id = UUID.randomUUID(); Object s = newStore();
        putAdvisory(s, id, advisory("world-A", "session-A", "dim-A", 9L));
        removeAdvisory(s, id);
        assertNull(advisoryFor(s, id, "world-A", "session-A"));
        putAdvisory(s, id, advisory("world-A", "session-A", "dim-A", 10L));
        s.getClass().getDeclaredMethod("clear").invoke(s);
        assertNull(advisoryFor(s, id, "world-A", "session-A"));
    }

    @Test void advisoryPutRejectsUnknownIdentity() throws Exception {
        UUID id = UUID.randomUUID(); Object s = newStore();
        putAdvisory(s, id, advisory("", "session-A", "dim-A", 1L));
        assertNull(advisoryFor(s, id, "world-A", "session-A"));
    }

    private static Object newStore() throws Exception {
        var c = SideContextStore.class.getDeclaredConstructor(); c.setAccessible(true); return c.newInstance();
    }

    private static Class<?> advisoryClass() throws Exception {
        return Class.forName("com.dwinovo.numen.plugins.rdd.SideContextStore$Advisory");
    }

    private static Object advisory(String world, String session, String dim, long sampled) throws Exception {
        var ctor = advisoryClass().getDeclaredConstructor(String.class, String.class, String.class, long.class,
                boolean.class, int.class, int.class, int.class, boolean.class, boolean.class, boolean.class, long.class);
        ctor.setAccessible(true);
        return ctor.newInstance(world, session, dim, 12000L, true, 20, 20, 8, false, true, false, sampled);
    }

    private static void putAdvisory(Object store, UUID id, Object adv) throws Exception {
        var m = store.getClass().getDeclaredMethod("putAdvisory", UUID.class, advisoryClass()); m.setAccessible(true); m.invoke(store, id, adv);
    }

    private static Object advisoryFor(Object store, UUID id, String world, String session) throws Exception {
        var m = store.getClass().getDeclaredMethod("advisoryFor", UUID.class, String.class, String.class); m.setAccessible(true); return m.invoke(store, id, world, session);
    }

    private static void removeAdvisory(Object store, UUID id) throws Exception {
        var m = store.getClass().getDeclaredMethod("removeAdvisory", UUID.class); m.setAccessible(true); m.invoke(store, id);
    }

    private static Object field(Object adv, String name) throws Exception {
        var m = adv.getClass().getDeclaredMethod(name); m.setAccessible(true); return m.invoke(adv);
    }
}
