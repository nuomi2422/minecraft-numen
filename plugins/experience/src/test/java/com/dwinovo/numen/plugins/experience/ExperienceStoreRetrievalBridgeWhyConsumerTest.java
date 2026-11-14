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
    void plannerBodyMustCarryAllFourStoredTeachingFieldsWithoutPublishingTheDraft() {
        ExperienceMemory memory = storedTeaching("planner-fields.jsonl", PublicationState.DRAFT);
        var source = memory.all().getFirst(); String sourceRef = ref(memory);
        var planner = render(memory, TASK, 2400, RetrievalRole.PLANNER);
        String body = bodyFor(planner, sourceRef);
        assertEquals(PublicationState.DRAFT, source.publication());
        assertEquals(ExperienceMaturity.OBSERVED, source.maturity());
        var executor = render(memory, TASK, 2400, RetrievalRole.EXECUTOR);
        assertFalse(executor.text().contains(sourceRef), "four-field teaching cannot publish a DRAFT to the executor");
        assertTrue(executor.refs().stream().anyMatch(r -> r.ref().equals(sourceRef) && !r.rendered()));
        assertAll(
                () -> assertTrue(body.contains(source.rationale()), "same-ref body lost stored why"),
                () -> assertTrue(body.contains(source.failureConditions()), "same-ref body lost stored risks"),
                () -> assertTrue(body.contains(source.evidence()), "same-ref body lost original evidence/source reference"),
                () -> assertTrue(body.contains(source.preconditions()), "same-ref body lost stored conditions"),
                () -> assertTrue(ExperienceStoreRetrievalBridge.snapshot(memory).getFirst().bodyContent().length()
                        <= ExperienceStoreRetrievalBridge.MAX_BODY_CHARS));
    }

    @Test
    void executorTrialBodyMustCarryAllFourStoredFieldsWithoutInventingVerifiedEvidence() {
        ExperienceMemory memory = storedTeaching("executor-fields.jsonl", PublicationState.TRIAL);
        var source = memory.all().getFirst(); String sourceRef = ref(memory);
        var executor = render(memory, TASK, 2400, RetrievalRole.EXECUTOR);
        String body = bodyFor(executor, sourceRef);
        assertTrue(executor.refs().stream().anyMatch(r -> r.ref().equals(sourceRef) && r.rendered()
                && r.trial() && r.maturity().equals("OBSERVED")));
        assertTrue(body.contains("（试用中）"));
        assertFalse(executor.text().contains("maturity=\"VERIFIED\""));
        assertEquals(ExperienceMaturity.OBSERVED, source.maturity());
        assertEquals(0, source.verifiedCount());
        assertAll(
                () -> assertTrue(body.contains(source.rationale()), "same-ref body lost stored why"),
                () -> assertTrue(body.contains(source.failureConditions()), "same-ref body lost stored risks"),
                () -> assertTrue(body.contains(source.evidence()), "same-ref body lost original evidence/source reference"),
                () -> assertTrue(body.contains(source.preconditions()), "same-ref body lost stored conditions"),
                () -> assertTrue(ExperienceStoreRetrievalBridge.snapshot(memory).getFirst().bodyContent().length()
                        <= ExperienceStoreRetrievalBridge.MAX_BODY_CHARS));
    }

    private ExperienceMemory storedTeaching(String fileName, PublicationState publication) {
        Path file = dir.resolve(fileName);
        var writer = ExperienceMemory.at(file, new LexicalExperienceRetriever());
        var saved = writer.learn(owner().rationale("Supply access prevents another empty-inventory attempt.")
                .preconditions("Recovery only; safe food access confirmed.")
                .failureConditions("Unsafe route or no retreat.").evidence("fixture:teaching-receipt-7")
                .mechanism("Fixture: replenish before travel.").recommendedResponse("Fixture: collect food.")
                .tags(List.of()).maturity(ExperienceMaturity.OBSERVED).verifiedCount(0).publication(publication).build());
        var loaded = ExperienceMemory.at(file, new LexicalExperienceRetriever());
        var source = loaded.findById(saved.id());
        assertNotNull(source, "use the real stored and reloaded entry, not a second source/provider");
        assertEquals(saved.rationale(), source.rationale()); assertEquals(saved.failureConditions(), source.failureConditions());
        assertEquals(saved.evidence(), source.evidence()); assertEquals(saved.preconditions(), source.preconditions());
        return loaded;
    }

    private static String bodyFor(ExperienceStoreRetrievalBridge.Rendered rendered, String sourceRef) {
        int entry = rendered.text().indexOf("<entry ref=\"" + sourceRef + "\"");
        assertTrue(entry >= 0, "the actual role renderer must select the same stored reference");
        int start = rendered.text().indexOf('>', entry) + 1;
        int end = rendered.text().indexOf("</entry>", start);
        assertTrue(end >= start);
        return rendered.text().substring(start, end);
    }


    @Test
    void risksAndSourceAloneReachActualBodiesInsideTheSameCharacterBudget() {
        ExperienceMemory memory = memory(owner().rationale("").preconditions("")
                .failureConditions("RISK_ONLY unsafe exit.").evidence("fixture:source-only-9")
                .mechanism("MECHANISM_ONLY " + "route ".repeat(90)).recommendedResponse("ACTION_ONLY replenish safely.")
                .tags(List.of()).build());
        var source = memory.all().getFirst();
        assertTrue(source.rationale().isEmpty() && source.preconditions().isEmpty());
        for (RetrievalRole role : List.of(RetrievalRole.PLANNER, RetrievalRole.EXECUTOR)) {
            String body = bodyFor(render(memory, TASK, 2400, role), ref(memory));
            assertTrue(body.contains(source.failureConditions()), "risk-only must enter the existing nonempty-field branch");
            assertTrue(body.contains(source.evidence()), "source-only must not become a made-up provider reference");
            assertFalse(body.contains("理由:") || body.contains("适用条件:"), "empty source fields remain absent");
        }
        assertTrue(ExperienceStoreRetrievalBridge.snapshot(memory).getFirst().bodyContent().length()
                <= ExperienceStoreRetrievalBridge.MAX_BODY_CHARS, "risks/source share the same cap with mechanism/action");
    }


    @Test
    void oversizedHigherRankedOptionalEntryCannotHideFittingPlannerTeaching() {
        ExperienceMemory memory = storedTeaching("planner-budget.jsonl", PublicationState.TRIAL);
        var teaching = memory.all().getFirst(); String teachingRef = "exp:" + teaching.id();
        var observed = new java.util.ArrayList<ExperienceStoreRetrievalBridge.Rendered>();
        java.util.UUID companion = java.util.UUID.randomUUID();
        assertEquals(8, PlanningKnowledge.DEFAULT_MAX_ITEMS);
        assertEquals(2400, PlanningKnowledge.DEFAULT_MAX_CHARS);
        String alone = ExperiencePlanningRouter.knowledgeFor(companion, TASK, "stage_b", List.of(), true,
                ignored -> memory, observed::add);
        assertTrue(alone.contains("ref=\"" + teachingRef + "\""), "the original router must first prove the teaching fits alone");
        String aloneBody = bodyFor(observed.getFirst(), teachingRef);
        assertTrue(aloneBody.contains(teaching.rationale()) && aloneBody.contains(teaching.failureConditions())
                && aloneBody.contains(teaching.evidence()) && aloneBody.contains(teaching.preconditions()));
        var blocker = memory.learn(ExperienceEntry.builder().type(ExperienceType.POLICY)
                .title(TASK + " oversized optional background")
                .description(TASK + " " + "optional background ".repeat(200))
                .mechanism("Fixture optional background, not an action.")
                .triggerStrings(List.of(TASK)).maturity(ExperienceMaturity.OBSERVED).verifiedCount(0)
                .publication(PublicationState.TRIAL).build());
        var query = new RetrievalQuery("planner-budget", companion.toString(), "", RetrievalRole.PLANNER, TASK,
                List.of(), List.of(), List.of(), 1, "", new RetrievalBudget(8, 0, 0, 0, 2400, 0, "none"), "ACTIVE");
        var page = new ExperienceRetrievalProvider("experience", ExperienceStoreRetrievalBridge.sourceFor(memory)).search(query);
        assertEquals(2, page.entries().size(), "both entries pass the unchanged provider/role/candidate gates");
        var first = page.entries().getFirst(); var second = page.entries().getLast();
        assertEquals("exp:" + blocker.id(), first.descriptor().entryRef(), "more matching query terms must put the long entry first");
        assertEquals(teachingRef, second.descriptor().entryRef());
        assertTrue(ExperienceRetrievalProvider.relevanceScore(TASK, first.descriptor())
                > ExperienceRetrievalProvider.relevanceScore(TASK, second.descriptor()));
        // Pin the original assembler's title + summary + body cost, not just rendered-body length.
        int firstCost = first.descriptor().title().length() + first.descriptor().summary().length() + first.body().content().length();
        int teachingCost = second.descriptor().title().length() + second.descriptor().summary().length() + second.body().content().length();
        assertTrue(firstCost + TASK.length() > 2400, "the high-ranked background really cannot fit");
        assertTrue(teachingCost + TASK.length() < 2400, "the later teaching really can fit without any larger budget");
        observed.clear();
        String actual = ExperiencePlanningRouter.knowledgeFor(companion, TASK, "stage_b", List.of(), true,
                ignored -> memory, observed::add); // Actual Router -> Bridge -> Provider -> Assembler, no substitute host.
        assertEquals(1, observed.size());
        assertEquals(actual, observed.getFirst().text());
        assertEquals("planner", observed.getFirst().role());
        assertTrue(observed.getFirst().truncationReasons().contains("max_chars=2400"), "oversized background remains an explicit budget exclusion");
        assertFalse(actual.contains("ref=\"exp:" + blocker.id() + "\""), "do not admit the oversized entry or enlarge its budget");
        String deliveredBody = bodyFor(observed.getFirst(), teachingRef);
        assertAll(
                () -> assertTrue(deliveredBody.contains(teaching.rationale())),
                () -> assertTrue(deliveredBody.contains(teaching.failureConditions())),
                () -> assertTrue(deliveredBody.contains(teaching.evidence())),
                () -> assertTrue(deliveredBody.contains(teaching.preconditions())),
                () -> assertTrue(actual.length() <= 2400));
    }

    @Test
    void entriesWithoutNewFieldsRetainTheExactLegacyBodyBytes() {
        ExperienceMemory memory = memory(owner().rationale("").preconditions("").failureConditions("").evidence("")
                .mechanism("  legacy mechanism  ").recommendedResponse("  legacy action  ").build());
        String original = "legacy mechanism\n推荐处理: legacy action";
        assertEquals(original, ExperienceStoreRetrievalBridge.snapshot(memory).getFirst().bodyContent());
        String longMechanism = "m".repeat(ExperienceStoreRetrievalBridge.MAX_BODY_CHARS + 20);
        ExperienceMemory longMemory = ExperienceMemory.at(dir.resolve("legacy-long.jsonl"), new LexicalExperienceRetriever());
        longMemory.learn(owner().rationale("").preconditions("").failureConditions("").evidence("")
                .mechanism(longMechanism).recommendedResponse("old action").build());
        assertEquals("m".repeat(ExperienceStoreRetrievalBridge.MAX_BODY_CHARS) + "…",
                ExperienceStoreRetrievalBridge.snapshot(longMemory).getFirst().bodyContent());
    }
}
