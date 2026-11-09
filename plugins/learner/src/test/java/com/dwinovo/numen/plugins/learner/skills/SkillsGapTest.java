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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-09（046-A，主干活聚合器契约）：无正文可发 = 返回 null，不拼空 entry、不自发事件。 */
class SkillsGapTest {

    @TempDir Path dir;

    @Test
    void missingBodyIsSkippedAndCounted() throws IOException {
        ArtifactDraft d = new ArtifactDraft(ArtifactKind.EXPERIENCE, "n", "p", "b",
                List.of("x"), List.of(), List.of(), "", List.of(), List.of("m"), "PLANNER");
        SkillRegistry.open(dir.resolve("skills.jsonl")).register(d, "sk-m", "/no/such/body", true);
        var f = new SkillsKnowledgeContributor(dir, "PLANNER", 0).contribute(UUID.randomUUID(), "x");
        assertEquals(1, f.gapCounts().get("body-missing"));
        assertTrue(f.text().isEmpty());
    }

    @Test
    void unreadableRegistryReturnsNull() throws IOException {
        Path broken = dir.resolve("broken");
        Files.createDirectories(broken);
        Files.createDirectories(broken.resolve("skills.jsonl"));
        var ex2 = org.junit.jupiter.api.Assertions.assertThrows(com.dwinovo.numen.api.KnowledgeGapException.class,
                () -> new SkillsKnowledgeContributor(broken, "PLANNER", 0).contribute(UUID.randomUUID(), "x"));
        assertEquals(com.dwinovo.numen.api.KnowledgeGapException.Reason.READ_FAILED, ex2.reason());
    }
}
