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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-09（046-A）：真实统一检索聚合消费者接收 skills 条目并逐条记账。 */
class SkillsAggregateConsumerTest {

    @TempDir Path dir;

    private void register(String id) throws IOException {
        String bodyRef = SkillBodyStore.write(dir, id, 1, "h-" + id, "正文-" + id);
        ArtifactDraft d = new ArtifactDraft(ArtifactKind.EXPERIENCE, "name-" + id, "purpose-" + id, "正文-" + id,
                List.of("夜"), List.of(), List.of(), "", List.of(), List.of("memo-" + id), "");
        SkillRegistry.open(dir.resolve("skills.jsonl")).register(d, id, bodyRef, true);
    }

    /** 2026-11-12: adopt_skill requires registry vN, not a version embedded in the artifact body.
     * Real final requests carried only ref + body; metadata refs were invisible to the model. */
    @Test
    void aggregateExposesRegistryVersionRatherThanBodyVersion() throws IOException {
        String id = "sk-version";
        String body = "";
        var registry = SkillRegistry.open(dir.resolve("skills.jsonl"));
        for (int revision = 1; revision <= 7; revision++) {
            body = "{\"version\":\"1\",\"revision\":" + revision + "}";
            String bodyRef = SkillBodyStore.write(dir, id, 1, "h-version-" + revision, body);
            ArtifactDraft draft = new ArtifactDraft(ArtifactKind.EXPERIENCE, "versioned-skill", "same-purpose", body,
                    List.of("night"), List.of(), List.of(), "", List.of(), List.of("memo-" + revision), "EXECUTOR");
            var decision = registry.register(draft, id, bodyRef, true);
            org.junit.jupiter.api.Assertions.assertEquals(revision, decision.entry().version(),
                    "fixture must use actual registry revisions, not a guessed body version");
        }
        SkillsKnowledgeContributor skills = new SkillsKnowledgeContributor(dir, "EXECUTOR", 0);
        var frag = NumenPlugins.aggregateExecutorKnowledgeFragment(
                List.of((NumenPlugins.KnowledgeContributor) skills::contribute), UUID.randomUUID(), "objective");

        org.junit.jupiter.api.Assertions.assertEquals("v7", frag.refs().get(0).version());
        assertTrue(frag.text().contains(body), "preserve the original body, including its unrelated version=1");
        assertFalse(frag.refs().get(0).truncated());
        String content = finalProviderContent(frag.text(), frag.text().length());
        assertTrue(content.contains("<entry ref=\"skill:sk-version\">@v7 "),
                "the final provider JSON must expose the exact version required by adopt_skill/report_skill");
        assertTrue(content.contains(body), "ref, registry version and original body must survive together");
        assertFalse(content.contains("@v1 "), "never infer registry version from body JSON");
    }

    /** Pure production seams only: character budget and provider serialization; never invokes a model. */
    private String finalProviderContent(String text, int maxChars) {
        var budget = new com.dwinovo.numen.agent.context.ContextBudget(0, 0, 0, 0L, maxChars, 0L, "chars-only");
        var allocation = com.dwinovo.numen.agent.context.ContextBudget.allocate(budget, List.of(
                new com.dwinovo.numen.agent.context.ContextBudget.Section("executor_knowledge", text, false, 0)));
        String kept = allocation.kept().stream().map(com.dwinovo.numen.agent.context.ContextBudget.Section::text)
                .collect(java.util.stream.Collectors.joining());
        var provider = new com.dwinovo.numen.agent.provider.DeepSeekProvider();
        var request = provider.buildRequestBody("offline-model", null,
                List.of(provider.buildUserMessage(kept)), new com.google.gson.JsonArray());
        var serialized = com.google.gson.JsonParser.parseString(request.toString()).getAsJsonObject();
        return serialized.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
    }

    @Test
    void versionHeaderDoesNotMakeBudgetCutBodyACompleteDelivery() throws IOException {
        String id = "sk-long";
        String body = "long-body-".repeat(600);
        String bodyRef = SkillBodyStore.write(dir, id, 1, "h-long", body);
        ArtifactDraft draft = new ArtifactDraft(ArtifactKind.EXPERIENCE, "long-skill", "purpose", body,
                List.of("night"), List.of(), List.of(), "", List.of(), List.of("memo-long"), "EXECUTOR");
        SkillRegistry.open(dir.resolve("skills.jsonl")).register(draft, id, bodyRef, true);
        SkillsKnowledgeContributor skills = new SkillsKnowledgeContributor(dir, "EXECUTOR", 0);
        var frag = NumenPlugins.aggregateExecutorKnowledgeFragment(
                List.of((NumenPlugins.KnowledgeContributor) skills::contribute), UUID.randomUUID(), "objective");

        assertTrue(frag.refs().get(0).truncated(), "a visible version header is not complete body delivery");
        assertFalse(frag.text().contains("</entry>"), "fixture must actually cross the shared budget cut");
        String content = finalProviderContent(frag.text(), frag.text().length());
        assertTrue(content.contains("<entry ref=\"skill:sk-long\">@v1 "));
        assertFalse(content.contains(body));
        assertFalse(content.contains("</entry>"));
    }

    @Test
    void droppedKnowledgeHasNoVersionOrBodyInFinalProviderJson() throws IOException {
        register("sk-drop");
        var frag = new SkillsKnowledgeContributor(dir, "EXECUTOR", 0).contribute(UUID.randomUUID(), "objective");
        String content = finalProviderContent(frag.text(), frag.text().length() - 1);
        assertTrue(content.isEmpty(), "optional knowledge is dropped whole, not a version-only delivery");
    }

    @Test
    void aggregateKeepsEntriesAndRefs() throws IOException {
        register("sk-a");
        register("sk-b");
        SkillsKnowledgeContributor skills = new SkillsKnowledgeContributor(dir, "EXECUTOR", 0);
        var frag = NumenPlugins.aggregateExecutorKnowledgeFragment(
                List.of((com.dwinovo.numen.api.NumenPlugins.KnowledgeContributor) (c, o) -> skills.contribute(c, o)),
                UUID.randomUUID(), "objective");

        assertTrue(frag.text().contains("<entry ref=\"skill:sk-a\">"), "条目边界必须保留");
        assertTrue(frag.text().contains("正文-sk-b"), "正文要经真实聚合进片段");
        assertFalse(frag.refs().isEmpty(), "逐条 refs 要记账");
        assertTrue(frag.refs().stream().noneMatch(NumenPlugins.DeliveredRef::truncated),
                "未截断时不得标记 truncated");
    }
}
