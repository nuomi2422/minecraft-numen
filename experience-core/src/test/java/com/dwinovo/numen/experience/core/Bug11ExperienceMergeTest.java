package com.dwinovo.numen.experience.core;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.PublicationState;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** One-shot admin: merge retry-budget duplicates in the REAL experience store via public API. */
class Bug11ExperienceMergeTest {
    private static final Path FILE = Path.of("D:/rdd/mc/The Best of Twilight Forest/config/numen/experience-a43d753e-790d-472b-8d5b-dc18ac0afbc0.jsonl");

    @Test void mergeRetryFamilyThroughApi() throws Exception {
        Assumptions.assumeTrue(System.getenv("BUG11_MERGE") != null, "set BUG11_MERGE=1 to run");
        Assumptions.assumeTrue(Files.exists(FILE), "experience file missing");
        ExperienceStore store = ExperienceStore.at(FILE);
        List<ExperienceEntry> family = store.all().stream()
                .filter(e -> {
                    String t = e.title() == null ? "" : e.title();
                    return (t.contains("同一 subtask") || t.contains("同一子任务"))
                            && (t.contains("预算") || t.contains("attempts"))
                            && !t.contains("协商回执") && !t.contains("协商阶段");
                }).toList();
        assertTrue(family.size() >= 2, "family too small: " + family.size());
        ExperienceEntry canonical = family.stream()
                .max(Comparator.comparingInt(e -> e.title().length())).orElseThrow();
        int retracted = 0;
        for (ExperienceEntry e : family) {
            if (!e.id().equals(canonical.id()) && !e.retracted()) {
                store.retract(e.id(), "bug-11 merge into " + canonical.id());
                retracted++;
            }
        }
        java.util.List<String> tags = new java.util.ArrayList<>(canonical.tags());
        if (tags.stream().noneMatch(t -> "audience:executor".equalsIgnoreCase(t))) {
            tags.add("audience:executor");
            store.learn(ExperienceEntry.builder().from(canonical).tags(tags).build());
        }
        String code = canonical.publication() == PublicationState.TRIAL ? "OK"
                : store.publish(canonical.id(), PublicationState.TRIAL, "bug-11 canonical").code();
        System.out.println("[bug11] family=" + family.size() + " retracted=" + retracted
                + " canonical=" + canonical.id() + " publish=" + code);
        assertEquals("OK", code);
        ExperienceEntry after = store.findById(canonical.id());
        assertEquals(PublicationState.TRIAL, after.publication());
    }
}
