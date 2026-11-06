package com.dwinovo.numen.plugins.learner.skills;

import com.dwinovo.numen.plugins.learner.contract.ArtifactDraft;
import com.dwinovo.numen.plugins.learner.contract.ArtifactKind;
import com.dwinovo.numen.plugins.learner.contract.EvidenceRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 升格通道（2026-11-06，监督者按实测补）。
 *
 * <p>守三件事：
 * <ol>
 *   <li>DRAFT 能被升成 TRIAL，且真的变成可检索（retrievable 只放 TRIAL/PUBLISHED）；</li>
 *   <li>升格是追加版本 + supersedes，不删历史；</li>
 *   <li>未知 id 报冲突；已升格的幂等；已撤回的不许升。</li>
 * </ol>
 */
class SkillRegistryPromoteTest {

    private static ArtifactDraft artifact(String purpose) {
        return new ArtifactDraft(ArtifactKind.EXPERIENCE, "夜间别出门", purpose, "v1",
                List.of("夜间"), List.of("白天"), List.of(), "",
                List.of(EvidenceRef.parse("block:dc-1")), List.of("s-1"), "EXECUTOR");
    }

    @Test
    void 草案升成试用后真的可检索(@TempDir Path dir) throws IOException {
        SkillRegistry reg = SkillRegistry.open(dir.resolve("skills.jsonl"));
        reg.register(artifact("夜里在洞口停留会被围"), "sk-1", "body:1");
        assertFalse(reg.get("sk-1").orElseThrow().status().retrievable(), "DRAFT 不该可检索");

        SkillRegistry.Decision d = reg.promote("sk-1", "质量门 PASS");

        assertEquals(SkillRegistry.Outcome.REVISE, d.outcome());
        SkillEntry after = reg.get("sk-1").orElseThrow();
        assertEquals(SkillStatus.TRIAL, after.status());
        assertEquals(2, after.version(), "升格要追加版本");
        assertTrue(after.supersedes().contains("sk-1@v1"), "旧版要进 supersedes");
        assertTrue(after.status().retrievable(), "TRIAL 必须可检索");
    }

    @Test
    void 未知id报冲突(@TempDir Path dir) throws IOException {
        SkillRegistry reg = SkillRegistry.open(dir.resolve("skills.jsonl"));
        assertEquals(SkillRegistry.Outcome.CONFLICT, reg.promote("sk-nope", "x").outcome());
    }

    @Test
    void 已升格的重复调用幂等(@TempDir Path dir) throws IOException {
        SkillRegistry reg = SkillRegistry.open(dir.resolve("skills.jsonl"));
        reg.register(artifact("p"), "sk-1", "body:1");
        reg.promote("sk-1", "first");
        SkillRegistry.Decision again = reg.promote("sk-1", "second");
        assertEquals(SkillRegistry.Outcome.REUSE, again.outcome());
        assertEquals(2, reg.get("sk-1").orElseThrow().version(), "幂等不应再涨版本");
    }

    @Test
    void 已撤回的不能升格(@TempDir Path dir) throws IOException {
        SkillRegistry reg = SkillRegistry.open(dir.resolve("skills.jsonl"));
        reg.register(artifact("p"), "sk-1", "body:1");
        reg.retire("sk-1", "wrong");
        assertEquals(SkillRegistry.Outcome.CONFLICT, reg.promote("sk-1", "x").outcome());
    }
}