package com.dwinovo.numen.plugins.learner.skills;

import com.dwinovo.numen.plugins.learner.contract.ArtifactDraft;
import com.dwinovo.numen.plugins.learner.contract.ArtifactKind;
import com.dwinovo.numen.plugins.learner.contract.EvidenceRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Skill 目录落盘：重启后索引靠条目自身重建（不靠外部索引表）。
 */
class SkillRegistryPersistenceTest {

    private static ArtifactDraft artifact(String purpose, String body, String sample) {
        return new ArtifactDraft(ArtifactKind.EXPERIENCE, "夜间别出门", purpose, body,
                List.of("夜间"), List.of("白天"), List.of(), "",
                List.of(EvidenceRef.parse("block:dc-1")), List.of(sample), "EXECUTOR");
    }

    @Test
    void 重启后条目与回链都在(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("skills.jsonl");
        SkillRegistry reg = SkillRegistry.open(f);
        reg.register(artifact("夜里在洞口停留会被围", "v1", "s-1"), "sk-1", "body:1");

        SkillRegistry reopened = SkillRegistry.open(f);
        assertEquals(1, reopened.size());
        assertEquals("body:1", reopened.get("sk-1").orElseThrow().bodyRef(), "正文引用要原样读回");
        assertEquals(List.of("sk-1"), reopened.skillsOfSample("s-1"));
        assertEquals(0, reopened.skippedLines());
    }

    @Test
    void 重启后指纹索引仍能判复用(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("skills.jsonl");
        SkillRegistry reg = SkillRegistry.open(f);
        reg.register(artifact("夜里在洞口停留会被围", "v1", "s-1"), "sk-1", "body:1");

        SkillRegistry reopened = SkillRegistry.open(f);
        var d = reopened.register(artifact("夜里在洞口停留会被围", "v1", "s-9"), "sk-2", "body:1");
        assertEquals(SkillRegistry.Outcome.REUSE, d.outcome(), "重启后指纹索引必须能重建，否则每次重启都新建条目");
    }

    @Test
    void 修订后的版本号重启后保留(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("skills.jsonl");
        SkillRegistry reg = SkillRegistry.open(f);
        reg.register(artifact("夜里在洞口停留会被围", "v1", "s-1"), "sk-1", "body:1");
        reg.register(artifact("夜里在洞口停留会被围", "v2", "s-2"), "sk-9", "body:2");

        SkillRegistry reopened = SkillRegistry.open(f);
        assertEquals(2, reopened.get("sk-1").orElseThrow().version());
        assertTrue(reopened.get("sk-1").orElseThrow().supersedes().contains("sk-1@v1"));
    }

    @Test
    void 坏行跳过并计数(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("skills.jsonl");
        SkillRegistry reg = SkillRegistry.open(f);
        reg.register(artifact("p", "v1", "s-1"), "sk-1", "body:1");
        Files.writeString(f, "{oops\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        SkillRegistry reopened = SkillRegistry.open(f);
        assertEquals(1, reopened.size(), "好行照常读回");
        assertEquals(1, reopened.skippedLines());
    }

    @Test
    void 纯内存目录不落盘(@TempDir Path dir) throws IOException {
        SkillRegistry mem = new SkillRegistry();
        mem.register(artifact("p", "v1", "s-1"), "sk-1", "body:1");
        assertEquals(1, mem.size());
        try (var s = Files.list(dir)) {
            assertEquals(0, s.count(), "没给文件就不该写任何东西");
        }
    }
}
