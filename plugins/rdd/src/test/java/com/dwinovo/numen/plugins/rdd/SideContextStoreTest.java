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
}
