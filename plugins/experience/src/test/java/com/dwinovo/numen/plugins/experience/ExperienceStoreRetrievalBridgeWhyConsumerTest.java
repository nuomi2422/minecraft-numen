package com.dwinovo.numen.plugins.experience;

import com.dwinovo.numen.agent.retrieval.Applicability;
import com.dwinovo.numen.agent.retrieval.ExperienceRetrievalProvider;
import com.dwinovo.numen.agent.retrieval.RetrievalBudget;
import com.dwinovo.numen.agent.retrieval.RetrievalQuery;
import com.dwinovo.numen.agent.retrieval.RetrievalRole;
import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceMaturity;
import com.dwinovo.numen.experience.api.ExperienceType;
import com.dwinovo.numen.experience.api.PublicationState;
import com.dwinovo.numen.experience.core.ExperienceMemory;
import com.dwinovo.numen.experience.core.LexicalExperienceRetriever;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Frozen consumer regression: eligible, task-matched source why/conditions must survive the bridge.
 * WHY (2026-11-14): body(entry) keeps only mechanism/response, losing existing rationale/preconditions.
 * Use the managed plugins:experience TestOnly selector after the parent accepts this red fixture.
 * Synthetic entries live only under TempDir; no live memory, game, model or goal is touched.
 * Do not fix by pinning every owner route for EXECUTOR or by adding a second teaching template.
 */
class ExperienceStoreRetrievalBridgeWhyConsumerTest {
    private static final String TASK = "recover provisions after fixture death";
    private static final String WHY = "Fixture repeated losses make a replenishable supply necessary.";
    private static final String CONDITIONS = "Fixture recovery only: supply is accessible and usable.";
    private static final String OLD_TAIL = "unrelated long route tail ";
    @TempDir Path dir;

    private ExperienceMemory memory(ExperienceEntry entry) {
        ExperienceMemory memory = ExperienceMemory.at(dir.resolve("fixture.jsonl"), new LexicalExperienceRetriever());
        memory.learn(entry);
        return memory;
    }

    private static ExperienceEntry.Builder owner() {
        return ExperienceEntry.builder()
                .type(ExperienceType.POLICY)
                .title("fixture recovery provisions")
                .description("Fixture-only owner teaching about recover provisions, not a world observation.")
                .rationale(WHY)
                .preconditions(CONDITIONS)
                .mechanism(OLD_TAIL.repeat(30))
                .recommendedResponse("Fixture action: replenish supplies before resuming this task.")
                .triggerStrings(List.of("recover provisions"))
                .failureConditions("nether transit")
                .tags(List.of(PlanningKnowledge.ALWAYS_TAG))
                .maturity(ExperienceMaturity.VERIFIED)
                .verifiedCount(2)
                .publication(PublicationState.PUBLISHED);
    }

    private static ExperienceStoreRetrievalBridge.Rendered render(ExperienceMemory memory, String task,
                                                                  int chars, RetrievalRole role) {
        return ExperienceStoreRetrievalBridge.render(memory, task, 4, chars, role);
    }

    private static String ref(ExperienceMemory memory) {
        return "exp:" + memory.all().getFirst().id();
    }

    @Test
    void matchedEligibleExecutorCarriesSourceWhyAndConditionsWithinTheExistingBodyLimit() {
        ExperienceMemory memory = memory(owner().build());
        String ref = ref(memory);
        var planner = render(memory, TASK, 2400, RetrievalRole.PLANNER);
        var executor = render(memory, TASK, 2400, RetrievalRole.EXECUTOR);
        var source = memory.all().getFirst();
        assertTrue(planner.text().contains("ref=\"" + ref + "\""), "same source must still reach PLANNER");
        assertTrue(executor.refs().stream().anyMatch(r -> r.ref().equals(ref) && r.rendered()),
                "the real executor consumer already selected this eligible task match");
        // Extract the existing entry body without depending on a new XML/why template.
        int bodyStart = executor.text().indexOf('>', executor.text().indexOf("<entry ")) + 1;
        String renderedBody = executor.text().substring(bodyStart, executor.text().indexOf("</entry>", bodyStart));
        assertAll(
                () -> assertTrue(renderedBody.contains(source.rationale()), "existing source why was dropped"),
                () -> assertTrue(renderedBody.contains(source.preconditions()), "existing source conditions were dropped"),
                () -> assertTrue(renderedBody.length() <= source.title().length() + ExperienceStoreRetrievalBridge.MAX_BODY_CHARS + 2,
                        "why/conditions must share the original bounded body, not append the full route"));
    }

    @Test
    void excludedOldNetherTaskDoesNotTurnOwnerTeachingIntoAnExecutorPin() {
        ExperienceMemory memory = memory(owner().build());
        assertTrue(render(memory, "nether transit", 2400, RetrievalRole.PLANNER).text().contains(ref(memory)));
        var executor = render(memory, "nether transit", 2400, RetrievalRole.EXECUTOR);
        assertFalse(executor.text().contains(ref(memory)), "preserve the existing explicit exclusion contract");
        assertTrue(executor.refs().stream().anyMatch(r -> r.ref().equals(ref(memory))
                && !r.rendered() && r.reason().equals("applicability_fail")));
    }

    @Test
    void explicitPlannerAudienceRemainsClosedToExecutor() {
        ExperienceMemory memory = memory(owner().tags(List.of(PlanningKnowledge.ALWAYS_TAG, "audience:planner")).build());
        assertTrue(render(memory, TASK, 2400, RetrievalRole.PLANNER).text().contains(ref(memory)));
        var executor = render(memory, TASK, 2400, RetrievalRole.EXECUTOR);
        assertFalse(executor.text().contains(ref(memory)));
        assertTrue(executor.refs().stream().anyMatch(r -> r.ref().equals(ref(memory))
                && !r.rendered() && r.reason().equals("audience_not_executor")));
    }

    @Test
    void withdrawnOwnerTeachingIsNotResurrectedByWhyDelivery() {
        ExperienceMemory memory = memory(owner().retracted(true).retractedReason("fixture withdrawn").build());
        assertEquals(1, memory.all().size(), "the source exists but is not usable");
        var executor = render(memory, TASK, 2400, RetrievalRole.EXECUTOR);
        assertEquals(0, executor.entries());
        assertFalse(executor.text().contains(WHY));
    }

    @Test
    void whyCannotBypassTheRealCharacterBudget() {
        ExperienceMemory memory = memory(owner().build());
        int budget = TASK.length() + 1;
        var executor = render(memory, TASK, budget, RetrievalRole.EXECUTOR);
        assertEquals(0, executor.entries());
        assertTrue(executor.truncationReasons().contains("max_chars=" + budget));
        assertFalse(executor.text().contains(WHY));
    }

    @Test
    void absentWhyAndConditionsStayAbsentAndApplicabilityStaysUnknown() {
        ExperienceMemory memory = memory(owner().rationale("").preconditions("")
                .triggerStrings(List.of()).failureConditions("")
                .mechanism("Fixture action only.").recommendedResponse("Continue this fixture action.").build());
        var executor = render(memory, TASK, 2400, RetrievalRole.EXECUTOR);
        assertTrue(executor.text().contains(ref(memory)), "UNKNOWN remains the original reference-only behavior");
        assertFalse(executor.text().contains(WHY) || executor.text().contains(CONDITIONS), "do not invent missing source fields");
        var query = new RetrievalQuery("fixture-unknown", "", "", RetrievalRole.EXECUTOR, TASK,
                List.of(), List.of(), List.of(), 1, "", new RetrievalBudget(4, 0, 0, 0, 2400, 0, "none"), "ACTIVE");
        var page = new ExperienceRetrievalProvider("experience", ExperienceStoreRetrievalBridge.sourceFor(memory)).search(query);
        assertEquals(Applicability.UNKNOWN, page.entries().getFirst().applicability(), "rendering must not manufacture PASS");
    }

    @Test
    void longNewFieldsKeepMechanismAndActionInsideTheSameBoundedEntry() {
        String why = "WHY_PREFIX " + "why tail ".repeat(100);
        String conditions = "CONDITION_PREFIX " + "condition tail ".repeat(100);
        ExperienceMemory memory = memory(owner().rationale(why).preconditions(conditions)
                .mechanism("MECHANISM_PREFIX keep usable supplies.")
                .recommendedResponse("ACTION_PREFIX replenish before travel.").build());
        var executor = render(memory, TASK, 2400, RetrievalRole.EXECUTOR);
        int start = executor.text().indexOf('>', executor.text().indexOf("<entry ")) + 1;
        String body = executor.text().substring(start, executor.text().indexOf("</entry>", start));
        assertAll(
                () -> assertTrue(body.contains("WHY_PREFIX") && body.contains("CONDITION_PREFIX"), "both new fields get a visible excerpt"),
                () -> assertTrue(body.contains("MECHANISM_PREFIX") && body.contains("ACTION_PREFIX"), "new prose cannot consume every action slot"),
                () -> assertTrue(body.contains("…") && !body.contains(why) && !body.contains(conditions), "long field excerpts are visibly incomplete"),
                () -> assertTrue(body.length() <= memory.all().getFirst().title().length() + ExperienceStoreRetrievalBridge.MAX_BODY_CHARS + 2));
    }

    @Test
    void entriesWithoutNewFieldsRetainTheExactLegacyBodyBytes() {
        ExperienceMemory memory = memory(owner().rationale("").preconditions("")
                .mechanism("  legacy mechanism  ").recommendedResponse("  legacy action  ").build());
        String original = "legacy mechanism\n推荐处理: legacy action";
        assertEquals(original, ExperienceStoreRetrievalBridge.snapshot(memory).getFirst().bodyContent());
        String longMechanism = "m".repeat(ExperienceStoreRetrievalBridge.MAX_BODY_CHARS + 20);
        ExperienceMemory longMemory = ExperienceMemory.at(dir.resolve("legacy-long.jsonl"), new LexicalExperienceRetriever());
        longMemory.learn(owner().rationale("").preconditions("")
                .mechanism(longMechanism).recommendedResponse("old action").build());
        assertEquals("m".repeat(ExperienceStoreRetrievalBridge.MAX_BODY_CHARS) + "…",
                ExperienceStoreRetrievalBridge.snapshot(longMemory).getFirst().bodyContent());
    }
}
