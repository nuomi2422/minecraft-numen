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

/** 2026-11-09（046-C0 决策 B+A）：缺正文逐条跳过并计数；全部缺失才无结果。 */
class SkillsGapTest {

    @TempDir Path dir;

    private void register(String id, String bodyRef, String body) throws IOException {
        ArtifactDraft d = new ArtifactDraft(ArtifactKind.EXPERIENCE, "n-" + id, "p-" + id, body,
                List.of("x"), List.of(), List.of(), "", List.of(), List.of("m-" + id), "PLANNER");
        SkillRegistry.open(dir.resolve("skills.jsonl")).register(d, id, bodyRef, true);
    }

    @Test
    void allMissingReturnsNull() throws IOException {
        register("sk-m", "/no/such/body", "b");
        var frag = new SkillsKnowledgeContributor(dir, "PLANNER", 0).contribute(UUID.randomUUID(), "x");
        assertTrue(frag.text().isEmpty() && frag.refs().isEmpty(), "不发空 entry");
        assertEquals(1, frag.gapCounts().get("body-missing"), "全缺也要让聚合器看到 gap");
    }

    @Test
    void partialMissingSkipsOnlyThatEntryAndCounts() throws IOException {
        String ok = SkillBodyStore.write(dir, "sk-ok", 1, "h1", "可读正文");
        register("sk-ok", ok, "可读正文");
        register("sk-bad", "draft:sk-bad:abc", "旧引用正文");
        var frag = new SkillsKnowledgeContributor(dir, "PLANNER", 0).contribute(UUID.randomUUID(), "x");
        assertTrue(frag.text().contains("可读正文"), "可读条目必须照常投递");
        assertTrue(!frag.text().contains("draft:"), "不拼假条目");
        assertEquals(1, frag.gapCounts().get("body-missing"));
    }

    @Test
    void unreadableRegistryStillThrowsTyped() throws IOException {
        Path broken = dir.resolve("broken");
        Files.createDirectories(broken);
        Files.createDirectories(broken.resolve("skills.jsonl"));
        var ex = org.junit.jupiter.api.Assertions.assertThrows(com.dwinovo.numen.api.KnowledgeGapException.class,
                () -> new SkillsKnowledgeContributor(broken, "PLANNER", 0).contribute(UUID.randomUUID(), "x"));
        assertEquals(com.dwinovo.numen.api.KnowledgeGapException.Reason.READ_FAILED, ex.reason());
    }
}
