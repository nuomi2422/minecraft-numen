package com.dwinovo.numen.api;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeGapCountsPayloadTest {
    @Test void payloadCarriesCountsAndNoBody() {
        UUID id = UUID.randomUUID();
        var data = NumenPlugins.knowledgeGapCountsPayload(id, Map.of("body-missing", 4));
        assertEquals(id.toString(), data.get("companion_id"));
        assertEquals(Map.of("body-missing", 4), data.get("counts"));
        assertFalse(data.containsKey("text"));
        assertFalse(data.containsKey("body"));
    }

    @Test void multipleReasonsAndInvalidValues() {
        var data = NumenPlugins.knowledgeGapCountsPayload(UUID.randomUUID(),
                Map.of("body-missing", 4, "read-failed", 2, "bad", -1));
        @SuppressWarnings("unchecked")
        var counts = (Map<String, Integer>) data.get("counts");
        assertEquals(4, counts.get("body-missing"));
        assertEquals(2, counts.get("read-failed"));
        assertFalse(counts.containsKey("bad"));
    }

    @Test void oldReasonSchemaStillHasReasonAndNoCounts() {
        var data = NumenPlugins.knowledgeGapReasonPayload(UUID.randomUUID(), 3, "read-failed", "RuntimeException");
        assertEquals("read-failed", data.get("reason"));
        assertEquals(3, data.get("contributor_index"));
        assertFalse(data.containsKey("counts"));
        assertFalse(data.containsKey("text"));
    }
}
