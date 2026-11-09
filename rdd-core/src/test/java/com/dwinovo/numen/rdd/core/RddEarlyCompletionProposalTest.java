package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RddEarlyCompletionProposalTest {
    private static Subtask item(String id) {
        return Subtask.hardCoded(id, id, Map.of("type", "item", "item", "minecraft:diamond", "minimum", 1));
    }

    @Test void proposesButDoesNotMutateChain() {
        Subtask a = item("a"); Subtask b = item("b");
        TaskChain chain = new TaskChain(new Goal("g", "g", List.of(
                new PrimaryGoal("p1", "p1", List.of(a)), new PrimaryGoal("p2", "p2", List.of(b)))));
        var p = RddEarlyCompletionProposal.propose(chain, Map.of("minecraft:diamond", 2));
        assertNotNull(p);
        assertEquals(List.of("a", "b"), p.subtaskIds());
        assertEquals(chain.planRevision(), p.planRevision());
        assertEquals(chain.chainInstanceId(), p.chainInstanceId());
        assertEquals(SubtaskStatus.PENDING, chain.currentSubtaskStatus(), "proposal must not mutate the chain");
        assertNull(RddEarlyCompletionProposal.propose(chain, Map.of("minecraft:diamond", 1)));
        assertNull(RddEarlyCompletionProposal.propose(chain, null));
    }

    @Test void renderIsUnverifiedOnlyWithMatchingIdentityAndFreshSample() {
        Subtask a = item("a");
        TaskChain chain = new TaskChain(new Goal("g", "g", List.of(new PrimaryGoal("p", "p", List.of(a)))));
        var p = RddEarlyCompletionProposal.propose(chain, Map.of("minecraft:diamond", 1));
        assertNotNull(p);
        String ok = RddEarlyCompletionProposal.render(p, "w", "s", "w", "s", p.sampledAtMillis());
        assertTrue(ok.contains("status=\"UNVERIFIED\""));
        assertTrue(ok.contains("a"));
        assertTrue(RddEarlyCompletionProposal.render(p, "w", "s", "other", "s", p.sampledAtMillis())
                .contains("UNKNOWN"), "different world must not show old facts");
        assertTrue(RddEarlyCompletionProposal.render(p, "w", "s", "w", "other", p.sampledAtMillis())
                .contains("UNKNOWN"), "different session must not show old facts");
        String expired = RddEarlyCompletionProposal.render(p, "w", "s", "w", "s", p.sampledAtMillis() + 180_000L);
        assertTrue(expired.contains("UNKNOWN"));
        assertFalse(expired.contains(">a<"), "expired notice must not carry old ids");
        assertTrue(RddEarlyCompletionProposal.render(p, "w", "s", null, null, p.sampledAtMillis()).contains("UNKNOWN"));
    }
}
