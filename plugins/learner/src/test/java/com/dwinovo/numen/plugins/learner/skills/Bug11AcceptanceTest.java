package com.dwinovo.numen.plugins.learner.skills;

import com.dwinovo.numen.plugins.learner.contract.ArtifactDraft;
import com.dwinovo.numen.plugins.learner.contract.ArtifactKind;
import com.dwinovo.numen.plugins.learner.contract.EvidenceRef;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Acceptance against the real merged skills.jsonl (T2/T3/T4, bug-11). */
class Bug11AcceptanceTest {
    private static final Path FILE = Path.of("D:/rdd/mc/The Best of Twilight Forest/config/numen/skills.jsonl");

    private static ArtifactDraft draft(SkillEntry e) {
        return new ArtifactDraft(e.kind(), e.name(), e.purpose(), "body",
                e.triggerConditions(), e.excludeConditions(), e.preconditions(), "",
                List.of(EvidenceRef.parse("block:acceptance")), List.of("s"), e.audience());
    }

    @Test void t2AndT4AgainstRealMergedData() throws Exception {
        Assumptions.assumeTrue(Files.exists(FILE), "game skills file not present");
        SkillRegistry r = SkillRegistry.open(FILE);
        SkillEntry keeper = r.all().stream().filter(e -> e.skillId().equals("sk-30e682eb837e")).findFirst().orElseThrow();
        for (String id : List.of("sk-2f52e3bd6c43", "sk-3a34618ae3d9", "sk-0936b836a8cd", "sk-cfcfb663f3f1",
                "sk-5304390b1bcd", "sk-c3c7191c2eb1", "sk-30e682eb837e", "sk-ab99648c6d9a")) {
            SkillEntry e = r.all().stream().filter(x -> x.skillId().equals(id)).findFirst().orElseThrow();
            ArtifactDraft d = draft(e);
            assertEquals("retry-budget/subtask-restart", SkillRegistry.family(d), id);
            assertTrue(r.isDuplicate(d, "sk-30e682eb837e"), "T2 should flag " + id);
        }
        for (String id : List.of("sk-3585e116da71", "sk-45dbf7326351", "sk-9f1f9c34960b")) {
            SkillEntry e = r.all().stream().filter(x -> x.skillId().equals(id)).findFirst().orElseThrow();
            assertFalse(r.isDuplicate(draft(e), "sk-30e682eb837e"), "T4 false merge " + id);
        }
        assertTrue(keeper.triggerConditions().size() >= 3, "T3 keeper must carry three signals");
        Path tmp = Files.createTempFile("skills-acceptance", ".jsonl");
        try {
            Files.copy(FILE, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            SkillRegistry writable = SkillRegistry.open(tmp);
            var fourth = draft(keeper);
            var created = new ArtifactDraft(fourth.kind(), fourth.name() + " 第4条", fourth.purpose() + " 第4条",
                    fourth.body(), fourth.triggerConditions(), fourth.excludeConditions(),
                    fourth.preconditions(), "", fourth.evidence(), fourth.sampleRefs(), fourth.audience());
            assertEquals(SkillRegistry.Outcome.LIMIT, writable.register(created, "sk-fourth", "body:4").outcome(),
                    "归并后第 4 条同族必须被拒");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test void readSideSeesOnlyThreeActiveFamilyEntries() throws Exception {
        Assumptions.assumeTrue(Files.exists(FILE), "game skills file not present");
        SkillRegistry r = SkillRegistry.open(FILE);
        java.util.Set<String> group = java.util.Set.of(
                "sk-30e682eb837e", "sk-68a55bb557f5", "sk-2f52e3bd6c43", "sk-6d8100e9e200", "sk-c3c7191c2eb1", "sk-d69ba1d16c7c",
                "sk-1cd075c06b72", "sk-3bb436f292f3", "sk-67882339516c",
                "sk-e256b9e7da65", "sk-1088551a829c", "sk-011e35d0972f", "sk-d9735ff0bbfb", "sk-cfcfb663f3f1", "sk-d605765d9714",
                "sk-3a34618ae3d9", "sk-ab99648c6d9a", "sk-a2a9934d59c4", "sk-e6f367efc01e", "sk-5304390b1bcd", "sk-e0b289a328e4",
                "sk-2472927682d2", "sk-0936b836a8cd");
        var active = r.all().stream()
                .filter(e -> group.contains(e.skillId()))
                .filter(e -> e.status() == SkillStatus.DRAFT || e.status() == SkillStatus.TRIAL)
                .map(SkillEntry::skillId).sorted().toList();
        assertEquals(java.util.List.of("sk-30e682eb837e", "sk-3a34618ae3d9", "sk-e256b9e7da65"), active,
                "合并组读侧只应看到 3 条活跃条目");
        long revokedMerged = r.all().stream()
                .filter(e -> group.contains(e.skillId()))
                .filter(e -> e.status() == SkillStatus.REVOKED)
                .count();
        assertEquals(20, revokedMerged, "被并入的 20 条必须是 REVOKED 且 supersedes 指向 canonical");
    }

    @Test void retrievalReturnsThreeCanonicalTrials() throws Exception {
        Assumptions.assumeTrue(Files.exists(FILE), "game skills file not present");
        SkillRegistry r = SkillRegistry.open(FILE);
        var ids = r.retrievable().stream().map(SkillEntry::skillId).toList();
        assertTrue(ids.containsAll(java.util.List.of("sk-30e682eb837e", "sk-3a34618ae3d9", "sk-e256b9e7da65")),
                "canonical 必须进入可检索集合: " + ids);
        assertFalse(ids.contains("sk-2f52e3bd6c43"), "REVOKED 被并入条不得可检索");
        assertFalse(ids.contains("sk-cfcfb663f3f1"), "REVOKED 被并入条不得可检索");
    }
}
