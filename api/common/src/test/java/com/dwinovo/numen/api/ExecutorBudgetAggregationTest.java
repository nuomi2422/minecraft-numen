package com.dwinovo.numen.api;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ExecutorBudgetAggregationTest {
    private static final NumenPlugins.DeliveredRef R1 = new NumenPlugins.DeliveredRef("r1", "v1", false);

    @Test void noBudgetBothDelivered() {
        var out = agg(List.of(c("a", 10), c("b", 10)));
        assertTrue(out.text().contains("aaaaaaaaaa"));
        assertTrue(out.text().contains("bbbbbbbbbb"));
        assertEquals(2, out.refs().size());
    }

    @Test void exactAndPartialRefsMarkTruncated() {
        var out = agg(List.of(c("x", 3990), c("y", 100)));
        assertTrue(out.text().length() <= NumenPlugins.MAX_EXECUTOR_KNOWLEDGE_CHARS);
        assertEquals(2, out.refs().size());
        assertFalse(out.refs().get(0).truncated());
        assertTrue(out.refs().get(1).truncated());
    }

    @Test void laterContributorStarvedButFirstStays() {
        var out = agg(List.of(c("first", 4200), c("second", 100)));
        assertTrue(out.text().contains("first"));
        assertFalse(out.text().contains("second"));
        assertEquals(1, out.refs().size());
    }

    @Test void oldTextFragmentHasNoRefs() {
        NumenPlugins.KnowledgeContributor old = (id, obj) -> NumenPlugins.KnowledgeFragment.ofText("old");
        var out = agg(List.of(old));
        assertEquals("old", out.text());
        assertTrue(out.refs().isEmpty());
    }

    private static NumenPlugins.KnowledgeFragment agg(List<NumenPlugins.KnowledgeContributor> cs) {
        return NumenPlugins.aggregateExecutorKnowledgeFragment(cs, UUID.randomUUID(), "obj");
    }

    private static NumenPlugins.KnowledgeContributor c(String s, int n) {
        return (id, obj) -> new NumenPlugins.KnowledgeFragment(s.repeat(n), List.of(
                new NumenPlugins.DeliveredRef("ref-" + s, "v1", false)));
    }
}
