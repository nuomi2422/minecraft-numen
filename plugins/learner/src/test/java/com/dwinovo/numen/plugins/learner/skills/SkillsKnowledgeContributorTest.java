package com.dwinovo.numen.plugins.learner.skills;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.plugins.learner.contract.ArtifactDraft;
import com.dwinovo.numen.plugins.learner.contract.ArtifactKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-09（046-A）：真实 registry + body store → KnowledgeFragment（规划/执行两视图）。 */
class SkillsKnowledgeContributorTest {

    @TempDir Path dir;

    private void register(String id, String body, String audience) throws IOException {
        String bodyRef = SkillBodyStore.write(dir, id, 1, "hash-" + id, body);
        ArtifactDraft d = new ArtifactDraft(ArtifactKind.EXPERIENCE, "name-" + id, "purpose-" + id, body,
                List.of("夜"), List.of(), List.of(), "", List.of(), List.of("memo-1"), audience);
        SkillRegistry.open(dir.resolve("skills.jsonl")).register(d, id, bodyRef, true);
    }

    @Test
    void plannerSeesPlannerAudienceWithBodyAndVersionRef() throws IOException {
        register("sk-a", "夜间不要出门", "PLANNER");
        var frag = new SkillsKnowledgeContributor(dir, "PLANNER", 800)
                .contribute(UUID.randomUUID(), "睡觉");

        assertTrue(frag.text().contains("夜间不要出门"), "正文要真的带上");
        assertTrue(frag.text().contains("<entry ref=\"skill:sk-a\">"), "条目边界要约给统一检索记账");
        assertEquals(1, frag.refs().size());
        assertEquals("skill:sk-a", frag.refs().get(0).entryRef());
        assertEquals("v1", frag.refs().get(0).version());
        assertFalse(frag.refs().get(0).truncated());
    }

    @Test
    void executorDoesNotSeePlannerOnlyEntry() throws IOException {
        register("sk-a", "夜间不要出门", "PLANNER");
        var frag = new SkillsKnowledgeContributor(dir, "EXECUTOR", 800)
                .contribute(UUID.randomUUID(), "煮饭");
        assertNull(frag, "受众不匹配 = 无结果");
    }

    @Test
    void revokedEntryIsNotDelivered() throws IOException {
        register("sk-a", "夜间不要出门", "PLANNER,EXECUTOR");
        SkillRegistry.open(dir.resolve("skills.jsonl")).retire("sk-a", "test");
        var frag = new SkillsKnowledgeContributor(dir, "PLANNER", 800)
                .contribute(UUID.randomUUID(), "睡觉");
        assertNull(frag, "撤回后不再检索 = 无结果");
    }

    @Test
    void missingBodyIsNotDelivered() throws IOException {
        ArtifactDraft d = new ArtifactDraft(ArtifactKind.EXPERIENCE, "name-x", "purpose-x", "body",
                List.of("夜"), List.of(), List.of(), "", List.of(), List.of("memo-1"), "PLANNER");
        // 2026-11-12: simulate the historical bad row, not a new publication bypass.
        SkillEntry old = new SkillEntry("sk-x", 1, d.kind(), d.name(), d.purpose(), d.audience(),
                d.triggerConditions(), d.excludeConditions(), d.preconditions(), d.risk(), "UNKNOWN",
                SkillStatus.TRIAL, "/nonexistent/body", List.of(), d.sampleRefs(), List.of(), List.of());
        java.nio.file.Files.writeString(dir.resolve("skills.jsonl"), SkillRegistry.toJson(old) + "\n");
        var f = new SkillsKnowledgeContributor(dir, "PLANNER", 800).contribute(UUID.randomUUID(), "x");
        assertEquals(1, f.gapCounts().get("body-missing"));
        assertTrue(f.text().isEmpty());
    }

    @Test
    void reviseKeepsOnlyLatestVersion() throws IOException {
        register("sk-r", "旧正文", "PLANNER");
        String bodyRef = SkillBodyStore.write(dir, "sk-r", 1, "hash-r2", "新正文");
        ArtifactDraft again = new ArtifactDraft(ArtifactKind.EXPERIENCE, "name-sk-r", "purpose-sk-r", "新正文",
                List.of("夜"), List.of(), List.of(), "", List.of(), List.of("memo-2"), "PLANNER");
        SkillRegistry.open(dir.resolve("skills.jsonl")).register(again, "sk-r", bodyRef, true);
        var frag = new SkillsKnowledgeContributor(dir, "PLANNER", 800).contribute(UUID.randomUUID(), "x");
        assertEquals(1, frag.refs().size(), "同 id 只投最新");
        assertEquals("v2", frag.refs().get(0).version());
        assertTrue(frag.text().contains("新正文"));
        assertFalse(frag.text().contains("旧正文"), "旧版不投递");
    }

    @Test
    void zeroMaxCharsDelegatesTruncationToContextBudget() throws IOException {
        register("sk-z", "一段比较长的正文" , "PLANNER");
        var frag = new SkillsKnowledgeContributor(dir, "PLANNER", 0).contribute(UUID.randomUUID(), "x");
        assertTrue(frag.text().contains("一段比较长的正文"), "不自截断时正文全量交给消费方预算");
        assertEquals(1, frag.refs().size());
        assertFalse(frag.refs().get(0).truncated(), "provider 不切就不谎报 truncated");
    }

    @Test
    void unknownAudienceIsNotGuessed() throws IOException {
        register("sk-a", "正文", "BOTH");
        var frag = new SkillsKnowledgeContributor(dir, "PLANNER", 800)
                .contribute(UUID.randomUUID(), "x");
        assertNull(frag, "未知 audience 取值不当 BOTH");
    }
}
