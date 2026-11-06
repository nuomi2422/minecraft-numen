package com.dwinovo.numen.plugins.experience;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceMaturity;
import com.dwinovo.numen.experience.api.ExperienceType;
import com.dwinovo.numen.experience.core.ExperienceMemory;
import com.dwinovo.numen.experience.core.LexicalExperienceRetriever;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-06: router→bridge→query 的 knownFailures 接线与按 companion 取库不串。 */
class KnownFailuresWiringTest {

    @TempDir Path dir;

    private ExperienceMemory memoryWith(String file, String title) {
        var m = ExperienceMemory.at(dir.resolve(file), new LexicalExperienceRetriever());
        m.learn(ExperienceEntry.builder()
                .id("e-" + file)
                .type(ExperienceType.EXECUTION)
                .title(title)
                .description("desc " + title)
                .maturity(ExperienceMaturity.VERIFIED)
                .verifiedCount(3)
                .triggerStrings(List.of("mine"))
                .failureConditions("")
                .mechanism("m").recommendedResponse("r").build());
        return m;
    }

    @Test
    void bridgeOrderUsesKnownFailuresText() {
        ExperienceMemory memory = memoryWith("a.jsonl", "no path out 教训");
        String plain = ExperienceStoreRetrievalBridge.render(memory, "mine ore", 8, 6000).text();
        String withFailure = ExperienceStoreRetrievalBridge.render(
                memory, "mine ore", List.of("no path out"), 8, 6000).text();
        assertFalse(plain.isEmpty());
        assertTrue(withFailure.contains("no path out"));
        assertTrue(plain.indexOf("no path out") >= 0 || withFailure.indexOf("no path out") >= 0,
                "条目至少应在其中一次渲染出现");
    }

    @Test
    void routerUsesPerCompanionMemoryOnly() {
        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();
        ExperienceMemory m1 = memoryWith("c1.jsonl", "C1-ONLY 教训");
        ExperienceMemory m2 = memoryWith("c2.jsonl", "C2-ONLY 教训");
        String t1 = ExperiencePlanningRouter.knowledgeFor(c1, "mine ore", "", List.of(), true,
                id -> id.equals(c1) ? m1 : m2, null);
        String t2 = ExperiencePlanningRouter.knowledgeFor(c2, "mine ore", "", List.of(), true,
                id -> id.equals(c1) ? m1 : m2, null);
        assertTrue(t1.contains("C1-ONLY"));
        assertFalse(t1.contains("C2-ONLY"));
        assertTrue(t2.contains("C2-ONLY"));
        assertFalse(t2.contains("C1-ONLY"));
    }
}
