package com.dwinovo.numen.api;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeGapsTest {
    @Test void nullAndBlankWithoutRefsAreNoResult() {
        assertNull(KnowledgeGaps.reasonFor(null, null));
        assertNull(KnowledgeGaps.reasonFor(NumenPlugins.KnowledgeFragment.ofText(""), null));
    }
    @Test void blankWithRefsIsMissingBody() {
        var ref = new NumenPlugins.DeliveredRef("r1", "v1", false);
        assertEquals("body-missing", KnowledgeGaps.reasonFor(new NumenPlugins.KnowledgeFragment("", List.of(ref)), null));
    }
    @Test void exceptionIsReadFailed() {
        assertEquals("read-failed", KnowledgeGaps.reasonFor(null, new RuntimeException()));
    }
    @Test void validBodyIsNotGap() {
        assertNull(KnowledgeGaps.reasonFor(NumenPlugins.KnowledgeFragment.ofText("ok"), null));
    }
    @Test void sameKeyIsRateLimitedButDifferentReasonIsNot() {
        UUID id = UUID.randomUUID();
        assertTrue(KnowledgeGaps.allow(id, 1, "body-missing", 1000));
        assertFalse(KnowledgeGaps.allow(id, 1, "body-missing", 2000));
        assertTrue(KnowledgeGaps.allow(id, 1, "read-failed", 2000));
    }
}
