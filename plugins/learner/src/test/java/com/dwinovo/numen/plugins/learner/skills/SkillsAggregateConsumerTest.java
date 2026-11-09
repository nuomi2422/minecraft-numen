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
