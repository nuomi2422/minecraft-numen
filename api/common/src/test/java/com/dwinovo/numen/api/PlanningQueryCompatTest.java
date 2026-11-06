package com.dwinovo.numen.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-06: PlanningQuery 兼容扩展——旧 4 参回退 objective，检索串独立。 */
class PlanningQueryCompatTest {

    @Test void oldConstructorFallsBackToObjective() {
        UUID c = UUID.randomUUID();
        PlanningQuery q = new PlanningQuery(c, "goal", "stage_a", List.of("f1"));
        assertEquals("goal", q.objective());
        assertEquals("goal", q.retrievalObjective());
    }

    @Test void blankRetrievalFallsBackButExplicitStaysSeparate() {
        UUID c = UUID.randomUUID();
        assertEquals("goal", PlanningQuery.of(c, "goal", "", List.of(), " ").retrievalObjective());
        PlanningQuery q = PlanningQuery.of(c, "goal", "", List.of(), "goal <state_facts hp=4/>");
        assertEquals("goal", q.objective());
        assertEquals("goal <state_facts hp=4/>", q.retrievalObjective());
    }
}
