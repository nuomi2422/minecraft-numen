package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-07: needs stickiness - satisfied needs do not reappear when spent. */
class RddFactContextStickyNeedsTest {
    private static RddFactContext.Need wheat() {
        return new RddFactContext.Need("minecraft:wheat", 9, false);
    }
    private static RddFactContext.Need bread() {
        return new RddFactContext.Need("minecraft:bread", 2, false);
    }

    @Test void satisfiedThenSpentDoesNotReportShortage() {
        var needs = List.of(wheat());
        var held = Map.of("minecraft:wheat", 3);
        assertTrue(RddFactContext.renderNeedsBlock(needs, held, Map.of(), Map.of()).contains("wheat"));
        String sticky = RddFactContext.renderNeedsBlock(needs, held, Map.of(), Map.of(), Set.of("minecraft:wheat"));
        assertEquals("", sticky, "已满足并粘住的需求不应再报缺");
        assertEquals(List.of("minecraft:wheat"), RddFactContext.satisfiedButSpent(needs, held, Set.of("minecraft:wheat")));
    }

    @Test void neverSatisfiedStillReportsShortage() {
        String out = RddFactContext.renderNeedsBlock(List.of(wheat()), Map.of("minecraft:wheat", 3),
                Map.of(), Map.of(), Set.of());
        assertTrue(out.contains("wheat"), out);
        assertFalse(out.contains("bread"), out);
    }

    @Test void unrelatedNeedStillShows() {
        String out = RddFactContext.renderNeedsBlock(List.of(wheat(), bread()),
                Map.of("minecraft:wheat", 9, "minecraft:bread", 0), Map.of(), Map.of(), Set.of("minecraft:wheat"));
        assertFalse(out.contains("wheat"), out);
        assertTrue(out.contains("bread"), out);
    }
}
