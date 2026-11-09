package com.dwinovo.numen.plugins.learner.skills;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.PlanningQuery;
import com.dwinovo.numen.plugins.learner.contract.ArtifactDraft;
import com.dwinovo.numen.plugins.learner.contract.ArtifactKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-09（046-A）：规划侧真实聚合接收 skills 文本并受 2000 字符总上限约束。 */
class SkillsPlanningAggregateTest {

    @TempDir Path dir;

    private void register(String id, String body) throws IOException {
        String bodyRef = SkillBodyStore.write(dir, id, 1, "h-" + id, body);
        ArtifactDraft d = new ArtifactDraft(ArtifactKind.EXPERIENCE, "name-" + id, "purpose-" + id, body,
                List.of("夜"), List.of(), List.of(), "", List.of(), List.of("memo-" + id), "PLANNER");
        SkillRegistry.open(dir.resolve("skills.jsonl")).register(d, id, bodyRef, true);
    }

    @Test
    void planningDoesNotSeeExecutorOnlyEntry() throws IOException {
        String bodyRef = SkillBodyStore.write(dir, "sk-e", 1, "h-e", "只给执行看的正文");
        ArtifactDraft d = new ArtifactDraft(ArtifactKind.EXPERIENCE, "name-e", "purpose-e", "只给执行看的正文",
                List.of("夜"), List.of(), List.of(), "", List.of(), List.of("memo-e"), "EXECUTOR");
        SkillRegistry.open(dir.resolve("skills.jsonl")).register(d, "sk-e", bodyRef, true);
        SkillsKnowledgeContributor skills = new SkillsKnowledgeContributor(dir, "PLANNER", 0);
        String out = NumenPlugins.aggregatePlanningKnowledge(
                List.of((PlanningQuery q) -> { var fr = skills.contribute(q.companion(), q.objective()); return fr == null ? "" : fr.text(); }),
                PlanningQuery.of(UUID.randomUUID(), "睡觉", "stage_b", List.of()));
        assertTrue(!out.contains("只给执行看的正文"), "规划视图不得混入 EXECUTOR-only 条目");
    }

    @Test
    void planningAggregateIncludesSkillTextWithinBudget() throws IOException {
        register("sk-p", "夜间先回家再睡觉");
        SkillsKnowledgeContributor skills = new SkillsKnowledgeContributor(dir, "PLANNER", 0);
        String out = NumenPlugins.aggregatePlanningKnowledge(
                List.of((PlanningQuery q) -> { var fr = skills.contribute(q.companion(), q.objective()); return fr == null ? "" : fr.text(); }),
                PlanningQuery.of(UUID.randomUUID(), "睡觉", "stage_b", List.of()));

        assertTrue(out.contains("<entry ref=\"skill:sk-p\">"), "规划正文带条目边界");
        assertTrue(out.contains("夜间先回家再睡觉"), "正文进入规划请求");
        assertTrue(out.length() <= NumenPlugins.MAX_PLANNING_KNOWLEDGE_CHARS + 2,
                "规划总长不得超过既有上限");
    }
}
