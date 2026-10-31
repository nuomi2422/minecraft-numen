package com.dwinovo.numen.plugins.learner.skills;

import com.dwinovo.numen.plugins.learner.contract.ArtifactDraft;
import com.dwinovo.numen.plugins.learner.contract.ArtifactKind;
import com.dwinovo.numen.plugins.learner.contract.EvidenceRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Skill 目录：复用 / 修订 / 冲突三分支 + 双向回链（158 §6，159 G）。
 */
class SkillRegistryTest {

    private static ArtifactDraft artifact(String name, String purpose, String body, String sample) {
        return new ArtifactDraft(ArtifactKind.EXPERIENCE, name, purpose, body,
                List.of("夜间"), List.of("白天"), List.of(), "",
                List.of(EvidenceRef.parse("block:dc-1")), List.of(sample), "EXECUTOR");
    }

    @Test
    void 首次登记是新条目() {
        SkillRegistry r = new SkillRegistry();
        var d = r.register(artifact("夜间别出门", "夜里在洞口停留会被围", "v1正文", "s-1"), "sk-1", "body:1");

        assertEquals(SkillRegistry.Outcome.REGISTERED, d.outcome());
        assertEquals(1, d.entry().version());
        assertEquals(1, r.size());
        assertTrue(d.stored());
    }

    @Test
    void 同指纹同正文是复用且不涨版本() {
        SkillRegistry r = new SkillRegistry();
        r.register(artifact("夜间别出门", "夜里在洞口停留会被围", "同正文", "s-1"), "sk-1", "body:1");
        var d = r.register(artifact("夜间别出门", "夜里在洞口停留会被围", "同正文", "s-2"), "sk-2", "body:1");

        assertEquals(SkillRegistry.Outcome.REUSE, d.outcome());
        assertEquals(1, d.entry().version(), "每次复盘都涨版本号会让版本失去意义");
        assertEquals(1, r.size());
        assertFalse(d.stored(), "复用不是写入");
    }

    @Test
    void 同指纹不同正文是修订_旧版进supersedes() {
        SkillRegistry r = new SkillRegistry();
        r.register(artifact("夜间别出门", "夜里在洞口停留会被围", "v1", "s-1"), "sk-1", "body:1");
        var d = r.register(artifact("夜间别出门", "夜里在洞口停留会被围", "v2", "s-2"), "sk-9", "body:2");

        assertEquals(SkillRegistry.Outcome.REVISE, d.outcome());
        assertEquals(2, d.entry().version());
        assertTrue(d.entry().supersedes().contains("sk-1@v1"), "旧版本必须可追");
        assertTrue(d.entry().foundingSamples().containsAll(List.of("s-1", "s-2")),
                "修订要把新样本并进 founding samples，否则抽验会漏");
        assertEquals(1, r.size(), "修订不是新建条目");
    }

    @Test
    void 同名不同指纹判冲突而不是静默新建() {
        SkillRegistry r = new SkillRegistry();
        r.register(artifact("夜间别出门", "夜里在洞口停留会被围", "v1", "s-1"), "sk-1", "body:1");
        // 同名、但用途（指纹）不同
        var d = r.register(artifact("夜间别出门", "夜里不要下矿", "v1", "s-2"), "sk-2", "body:2");

        assertEquals(SkillRegistry.Outcome.CONFLICT, d.outcome(),
                "两条同名条目会互相矛盾，而检索时只看得到名字");
        assertEquals("sk-1", d.entry().skillId(), "冲突时给的是既有那条");
        assertEquals(1, r.size(), "冲突不落库");
        assertFalse(d.stored());
    }

    @Test
    void 双向回链成立() {
        SkillRegistry r = new SkillRegistry();
        r.register(artifact("夜间别出门", "夜里在洞口停留会被围", "v1", "s-1"), "sk-1", "body:1");

        assertEquals(List.of("sk-1"), r.skillsOfSample("s-1"), "样本侧要能查到自己派生了哪些 Skill");
        assertEquals(List.of("s-1"), r.get("sk-1").orElseThrow().foundingSamples());
        assertTrue(r.skillsOfSample("s-none").isEmpty());
    }

    @Test
    void 修订后样本侧仍能查到() {
        SkillRegistry r = new SkillRegistry();
        r.register(artifact("夜间别出门", "夜里在洞口停留会被围", "v1", "s-1"), "sk-1", "body:1");
        r.register(artifact("夜间别出门", "夜里在洞口停留会被围", "v2", "s-2"), "sk-9", "body:2");

        assertEquals(List.of("sk-1"), r.skillsOfSample("s-2"), "修订后新样本也要挂上");
    }

    @Test
    void 没有来源样本的条目直接拒绝() {
        assertThrows(IllegalArgumentException.class, () -> new SkillEntry(
                "sk-1", 1, ArtifactKind.EXPERIENCE, "n", "p", "EXECUTOR",
                List.of(), List.of(), List.of(), "", "", SkillStatus.DRAFT, "body:1",
                List.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void 版本号必须大于等于一() {
        assertThrows(IllegalArgumentException.class, () -> new SkillEntry(
                "sk-1", 0, ArtifactKind.EXPERIENCE, "n", "p", "EXECUTOR",
                List.of(), List.of(), List.of(), "", "", SkillStatus.DRAFT, "body:1",
                List.of(), List.of("s-1"), List.of(), List.of()));
    }

    @Test
    void 只有TRIAL与PUBLISHED可被检索() {
        assertTrue(SkillStatus.TRIAL.retrievable());
        assertTrue(SkillStatus.PUBLISHED.retrievable());
        assertFalse(SkillStatus.DRAFT.retrievable());
        assertFalse(SkillStatus.SUSPENDED.retrievable());
        assertFalse(SkillStatus.REVOKED.retrievable());
    }

    @Test
    void 草稿不进检索结果() {
        SkillRegistry r = new SkillRegistry();
        r.register(artifact("夜间别出门", "夜里在洞口停留会被围", "v1", "s-1"), "sk-1", "body:1");
        assertEquals(0, r.retrievable().size(), "刚登记的是 DRAFT：登记不等于验证完成");
    }

    @Test
    void 指纹忽略大小写与全角() {
        SkillRegistry r = new SkillRegistry();
        var a = artifact("夜间别出门", "ＰＵＲＰＯＳＥ", "v1", "s-1");
        var b = artifact("夜间别出门", "purpose", "v1", "s-2");
        r.register(a, "sk-1", "body:1");
        assertEquals(SkillRegistry.Outcome.REUSE, r.register(b, "sk-2", "body:1").outcome());
    }
}
