package com.dwinovo.numen.plugins.learner.skills;

import com.dwinovo.numen.plugins.learner.contract.ArtifactDraft;
import com.dwinovo.numen.plugins.learner.contract.ArtifactKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-11: registration does not freeze a skill body file.
 * Exercise the real persisted registry and planner/executor contributor after a file changes,
 * so a delivered v1 reference cannot silently accompany different text.
 * Uses only temporary files; does not run Minecraft or claim adoption/effectiveness.
 */
class SkillBodyDeliveryIntegrityTest {
    @TempDir Path dir;

    private String register(String id, String body, String hash) throws IOException {
        String ref = SkillBodyStore.write(dir, id, 1, hash, body);
        assertNotNull(ref);
        var draft = new ArtifactDraft(ArtifactKind.EXPERIENCE, "name-" + id, "purpose-" + id, body,
                List.of("night"), List.of(), List.of(), "", List.of(), List.of("memo-" + id),
                "PLANNER,EXECUTOR");
        SkillRegistry.open(dir.resolve("skills.jsonl")).register(draft, id, ref, true);
        assertEquals(SkillStatus.TRIAL, SkillRegistry.open(dir.resolve("skills.jsonl"))
                .get(id).orElseThrow().status());
        return ref;
    }

    private Path bodyFile(String ref) {
        return dir.resolve("skill-bodies").resolve(ref.substring("skill-body:".length()));
    }

    @Test
    void changedBodyIsNotDeliveredUnderOriginalVersion() throws IOException {
        String original = "原来的安全步骤";
        String ref = register("changed", original, Integer.toHexString(original.hashCode()));
        Files.writeString(bodyFile(ref), "改写后的不同步骤");
        for (String role : List.of("PLANNER", "EXECUTOR")) {
            var fragment = new SkillsKnowledgeContributor(dir, role, 0).contribute(UUID.randomUUID(), "night");
            assertNotNull(fragment, "unreadable body must remain an observable gap");
            assertTrue(fragment.text().isEmpty(), role + " must not deliver changed body text");
            assertTrue(fragment.refs().isEmpty(), role + " must not receipt a mismatched v1 body");
            assertEquals(1, fragment.gapCounts().get("body-missing"));
        }
    }

    @Test
    void intactBodyKeepsExactTextAndVersionForBothRoles() throws IOException {
        String original = "中文与 emoji 🌙\r\nsecond line";
        register("intact", original, Integer.toHexString(original.hashCode()));
        for (String role : List.of("PLANNER", "EXECUTOR")) {
            var fragment = new SkillsKnowledgeContributor(dir, role, 0).contribute(UUID.randomUUID(), "night");
            assertNotNull(fragment);
            assertTrue(fragment.text().contains(original));
            assertEquals(List.of("skill:intact"), fragment.refs().stream().map(r -> r.entryRef()).toList());
            assertEquals("v1", fragment.refs().get(0).version());
            assertTrue(fragment.gapCounts().isEmpty());
        }
    }

    @Test
    void changedBodyDoesNotSuppressOtherIntactSkills() throws IOException {
        String original = "first body";
        String ref = register("changed", original, Integer.toHexString(original.hashCode()));
        register("intact", "good body", Integer.toHexString("good body".hashCode()));
        Files.writeString(bodyFile(ref), "different body");
        var fragment = new SkillsKnowledgeContributor(dir, "EXECUTOR", 0).contribute(UUID.randomUUID(), "night");
        assertEquals(List.of("skill:intact"), fragment.refs().stream().map(r -> r.entryRef()).toList());
        assertTrue(fragment.text().contains("good body"));
        assertFalse(fragment.text().contains("different body"));
        assertEquals(1, fragment.gapCounts().get("body-missing"));
    }

    @Test
    void legacyNohashBodyRetainsExistingReadabilityContract() throws IOException {
        register("legacy", "legacy body", "nohash");
        var fragment = new SkillsKnowledgeContributor(dir, "EXECUTOR", 0).contribute(UUID.randomUUID(), "night");
        assertTrue(fragment.text().contains("legacy body"));
        assertEquals("v1", fragment.refs().get(0).version());
        // A non-checksummed legacy reference is readable, not proof of content integrity.
    }
}
