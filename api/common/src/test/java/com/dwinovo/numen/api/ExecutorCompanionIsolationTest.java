package com.dwinovo.numen.api;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ExecutorCompanionIsolationTest {
    @Test void aggregateOnlySeesRequestedCompanion() {
        NumenPlugins.KnowledgeContributor c = (id, obj) ->
                NumenPlugins.KnowledgeFragment.ofText("companion=" + id);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var fa = NumenPlugins.aggregateExecutorKnowledgeFragment(List.of(c), a, "x");
        var fb = NumenPlugins.aggregateExecutorKnowledgeFragment(List.of(c), b, "x");
        assertTrue(fa.text().contains("companion=" + a));
        assertFalse(fa.text().contains("companion=" + b));
        assertTrue(fb.text().contains("companion=" + b));
        assertFalse(fb.text().contains("companion=" + a));
    }
}
