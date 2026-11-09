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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-09（046-A）：provider 自截断时 refs 条数=实际写进 text 的条目数；旧经验与 skills 并存不崩、不隐式去重。 */
class SkillsAccountingTest {

    @TempDir Path dir;

    private void register(String id, String body) throws IOException {
        String ref = SkillBodyStore.write(dir, id, 1, "h-" + id, body);
        ArtifactDraft d = new ArtifactDraft(ArtifactKind.EXPERIENCE, "n-" + id, "p-" + id, body,
                List.of("x"), List.of(), List.of(), "", List.of(), List.of("m-" + id), "");
        SkillRegistry.open(dir.resolve("skills.jsonl")).register(d, id, ref, true);
    }

    @Test
    void refsCountEqualsEntriesActuallyWritten() throws IOException {
        register("sk-a", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        register("sk-b", "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");
        var frag = new SkillsKnowledgeContributor(dir, "EXECUTOR", 90)
                .contribute(UUID.randomUUID(), "x");
        int entries = frag.text().split("<entry ref=").length - 1;
        assertEquals(entries, frag.refs().size(), "refs 必须等于实际投递条数");
        assertTrue(frag.text().length() <= 90, "自截断上限生效");
    }

    @Test
    void legacyExperienceAndSkillsCoexistWithoutCrash() throws IOException {
        register("sk-a", "技能正文");
        SkillsKnowledgeContributor skills = new SkillsKnowledgeContributor(dir, "EXECUTOR", 0);
        NumenPlugins.KnowledgeContributor legacy = (c, o) -> new NumenPlugins.KnowledgeFragment(
                "<entry ref=\"exp:x\">旧经验正文</entry>",
                List.of(new NumenPlugins.DeliveredRef("exp:x", "v1", false)));
        var out = NumenPlugins.aggregateExecutorKnowledgeFragment(
                List.of((NumenPlugins.KnowledgeContributor) (c, o) -> skills.contribute(c, o), legacy),
                UUID.randomUUID(), "o");
        assertTrue(out.text().contains("skill:sk-a"));
        assertTrue(out.text().contains("exp:x"), "旧经验与技能并存，不隐式去重");
    }
}
