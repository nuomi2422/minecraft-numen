package com.dwinovo.numen.plugins.learner.skills;

import com.dwinovo.numen.plugins.learner.contract.ArtifactDraft;
import com.dwinovo.numen.plugins.learner.contract.ArtifactKind;
import com.dwinovo.numen.plugins.learner.contract.EvidenceRef;
import com.dwinovo.numen.plugins.learner.contract.ReviewDraft;
import com.dwinovo.numen.plugins.learner.contract.Submission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Skill 登记入口：只有过门的产物进目录；id 由指纹算，重复提交走复用（159 G）。
 */
class SkillRegistrarTest {

    @AfterEach
    void clean() {
        SkillRegistrar.resetForTest();
    }

    private static ArtifactDraft artifact(String purpose, String body, String sample) {
        return new ArtifactDraft(ArtifactKind.EXPERIENCE, "夜间别出门", purpose, body,
                List.of("夜间"), List.of("白天"), List.of(), "",
                List.of(EvidenceRef.parse("block:dc-1")), List.of(sample), "EXECUTOR");
    }

    private static Submission.Draft draft(ArtifactDraft... arts) {
        ReviewDraft.Draft review = new ReviewDraft.Draft("s-1", List.of(
                new ReviewDraft.Claim(ReviewDraft.Section.FACT, "x",
                        List.of(EvidenceRef.parse("block:dc-1")))));
        return new Submission.Draft(Submission.Mode.INTERNAL, "s-1", review, List.of(arts));
    }

    @Test
    void 没通过校验的提交一条都不登记(@TempDir Path dir) {
        var r = SkillRegistrar.register(dir, draft(artifact("p", "b1", "s-1")), false);
        assertEquals(0, r.total());
        assertFalse(Files.exists(dir.resolve("skills.jsonl")), "没过门就落盘等于自己开后门");
    }

    @Test
    void 通过校验后登记并落盘(@TempDir Path dir) throws IOException {
        var r = SkillRegistrar.register(dir, draft(artifact("p", "b1", "s-1")), true);
        assertEquals(1, r.registered());
        assertTrue(Files.exists(dir.resolve("skills.jsonl")));

        SkillRegistry reopened = SkillRegistry.open(dir.resolve("skills.jsonl"));
        assertEquals(1, reopened.size());
        assertEquals(List.of(r.total() > 0 ? reopened.skillsOfSample("s-1").get(0) : ""),
                reopened.skillsOfSample("s-1"));
    }

    @Test
    void 同一产物再登记是复用而不是新建(@TempDir Path dir) {
        SkillRegistrar.register(dir, draft(artifact("p", "b1", "s-1")), true);
        var r2 = SkillRegistrar.register(dir, draft(artifact("p", "b1", "s-2")), true);

        assertEquals(1, r2.reused(), "id 由指纹算，同一件事必须落到同一条");
        assertEquals(0, r2.registered());
    }

    @Test
    void 正文变了是修订而不是新建(@TempDir Path dir) {
        SkillRegistrar.register(dir, draft(artifact("p", "b1", "s-1")), true);
        var r2 = SkillRegistrar.register(dir, draft(artifact("p", "b2", "s-1")), true);
        assertEquals(1, r2.revised());
        assertEquals(0, r2.registered());
    }

    @Test
    void skillId只由身份字段决定() {
        ArtifactDraft a = artifact("p", "b1", "s-1");
        // 正文不同、样本不同 → 同一个 skillId（指纹不含正文与样本）
        assertEquals(SkillRegistrar.skillIdOf(a), SkillRegistrar.skillIdOf(artifact("p", "b2", "s-9")));
        // 用途不同 → 另一个 skillId
        org.junit.jupiter.api.Assertions.assertNotEquals(
                SkillRegistrar.skillIdOf(a), SkillRegistrar.skillIdOf(artifact("another purpose", "b1", "s-1")));
    }

    @Test
    void 被判试用态的产物进目录后状态是TRIAL(@TempDir Path dir) throws IOException {
        // 内部学习者的 AUTO_ACTION 会被 PermissionMatrix 判成 TRIAL_ONLY；
        // 若这里仍写 DRAFT，"只准试用"就只活在日志里，检索侧照样一视同仁。
        ArtifactDraft trialArt = new ArtifactDraft(ArtifactKind.AUTO_ACTION, "撤退", "血量低于 6 就撤",
                "body", List.of("hp<6"), List.of("安全"), List.of("有退路"), "LOW",
                List.of(EvidenceRef.parse("block:dc-1")), List.of("s-1"), "EXECUTOR");
        var r = SkillRegistrar.register(dir, draft(trialArt), true, java.util.Set.of(0));
        assertEquals(1, r.registered());

        var reg = SkillRegistry.open(dir.resolve("skills.jsonl"));
        var entry = reg.get(SkillRegistrar.skillIdOf(trialArt)).orElseThrow();
        assertEquals(SkillStatus.TRIAL, entry.status());
        assertTrue(entry.status().retrievable(), "TRIAL 是可见的，只是范围受限——不能因此藏起来");
    }

    @Test
    void 没被判试用态的仍是草稿(@TempDir Path dir) throws IOException {
        var r = SkillRegistrar.register(dir, draft(artifact("p", "b1", "s-1")), true);
        assertEquals(1, r.registered());
        var reg = SkillRegistry.open(dir.resolve("skills.jsonl"));
        var e = reg.get(SkillRegistrar.skillIdOf(artifact("p", "b1", "s-1"))).orElseThrow();
        assertEquals(SkillStatus.DRAFT, e.status());
        assertFalse(e.status().retrievable(), "草稿不进检索：登记不等于验证完成");
    }

    @Test
    void 空提交安全(@TempDir Path dir) {
        assertEquals(0, SkillRegistrar.register(dir, null, true).total());
        assertEquals(0, SkillRegistrar.register(dir, draft(), true).total());
        assertTrue(SkillRegistrar.register(null, draft(artifact("p", "b", "s")), true).total() == 0);
    }
}
