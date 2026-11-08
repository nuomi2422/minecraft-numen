package com.dwinovo.numen.plugins.experience;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-08 C1: queue version and serialized content fingerprint are the same source. */
class ExpansionVersionBindingTest {
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    @Test void queueVersionEqualsSerializedFingerprint(@TempDir Path dir) throws Exception {
        ExperienceEntry e = ExperienceEntry.builder().type(ExperienceType.TOOL_DEFECT)
                .title("t").description("body").build();
        String version = e.contentFingerprint();
        assertEquals(version, e.toJson().get("content_fingerprint").getAsString());
        assertTrue(ExpansionReviewQueue.enqueue(dir, C, "exp:" + e.id(), version, "call-1"));
        String line = Files.readString(dir.resolve(ExpansionReviewQueue.FILE));
        assertTrue(line.contains(version), line);
    }

    @Test void contentChangeProducesDifferentVersion(@TempDir Path dir) {
        ExperienceEntry a = ExperienceEntry.builder().type(ExperienceType.TOOL_DEFECT)
                .title("t").description("one").build();
        ExperienceEntry b = ExperienceEntry.builder().type(ExperienceType.TOOL_DEFECT)
                .title("t").description("two").build();
        assertNotEquals(a.contentFingerprint(), b.contentFingerprint());
    }
}
