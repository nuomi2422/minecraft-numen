package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.api.WorldContextStamp;
import com.dwinovo.numen.plugins.learner.contract.ArtifactDraft;
import com.dwinovo.numen.plugins.learner.contract.ArtifactKind;
import com.dwinovo.numen.plugins.learner.core.UsageLedger;
import com.dwinovo.numen.plugins.learner.skills.SkillBodyStore;
import com.dwinovo.numen.plugins.learner.skills.SkillRegistrar;
import com.dwinovo.numen.plugins.learner.skills.SkillRegistry;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.task.TaskState;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-12 046-F: invoke the real usage tool, body store, shared registry and disk ledger.
 * Only live task lookup/identity are a package-only port; no fabricated fact resolver or ledger.
 * Choice plus task outcome is a review candidate, not proof of skill effectiveness.
 * Frozen before action implementation: missing actions must fail assertions, not compilation.
 */
class LearnerSkillTaskFeedbackTest {
    @TempDir Path dir;
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static WorldContextStamp.Token token(String world, String session, long epoch) {
        return new WorldContextStamp.Token(Map.of("world_id", world, "server_session", session), epoch);
    }
    private static final class Work extends TaskRecord {
        Work(String call) { super("goto", call, 1000); }
    }
    private static final class Tasks implements LearnerUsageTool.TaskAccess {
        UUID companion = C;
        WorldContextStamp.Token stamp = token("world", "session", 7);
        TaskRecord active;
        final Map<String, TaskRecord> done = new HashMap<>();
        public UUID companionId() { return companion; }
        public WorldContextStamp.Token identity() { return stamp; }
        public TaskRecord current() { return active; }
        public TaskRecord finished(String id) { return done.get(id); }
        void finish(TaskState state) {
            active.setState(state);
            active.setResult(state == TaskState.SUCCESS ? TaskResult.ok("arrived") : TaskResult.fail("ended"));
            done.put(active.publicId(), active);
            active = null;
        }
    }
    private Tasks tasks;
    private Work task;
    private UsageLedger ledger;
    private SkillRegistry registry;
    private LearnerUsageTool tool;
    private String bodyRef;

    private static ArtifactDraft artifact(String body) {
        return new ArtifactDraft(ArtifactKind.EXPERIENCE, "safe steps", "stay safe", body,
                List.of("night"), List.of(), List.of(), "LOW", List.of(), List.of("memo-1"), "EXECUTOR");
    }
    private String writeBody(String body) {
        String ref = SkillBodyStore.write(dir, "sk-1", 1, Integer.toHexString(body.hashCode()), body);
        assertNotNull(ref);
        assertEquals(body, SkillBodyStore.read(dir, ref));
        return ref;
    }
    @BeforeEach void setup() {
        tasks = new Tasks();
        task = new Work("action-call");
        task.bindIdentity(tasks.stamp);
        task.setState(TaskState.RUNNING);
        tasks.active = task;
        ledger = new UsageLedger(dir);
        registry = SkillRegistrar.registryFor(dir);
        bodyRef = writeBody("exact original steps");
        registry.register(artifact("exact original steps"), "sk-1", bodyRef, true);
        tool = new LearnerUsageTool(dir, ledger, tasks);
    }
    @AfterEach void reset() { SkillRegistrar.resetForTest(); }
    private JsonObject args(String action) {
        JsonObject a = new JsonObject();
        a.addProperty("action", action);
        a.addProperty("task_id", task.publicId());
        a.addProperty("skill_ref", "skill:sk-1");
        a.addProperty("version", "v1");
        return a;
    }
    private JsonObject call(JsonObject args) { return call(tool, args); }
    private static JsonObject call(LearnerUsageTool tool, JsonObject args) {
        AtomicReference<String> reply = new AtomicReference<>();
        tool.onServerCall("selection-call", args, null, reply::set);
        assertNotNull(reply.get(), "production branch must reply");
        return JsonParser.parseString(reply.get()).getAsJsonObject();
    }
    private static void ok(JsonObject r) { assertTrue(r.get("success").getAsBoolean(), r.toString()); }
    private static void rejected(JsonObject r) { assertFalse(r.get("success").getAsBoolean(), r.toString()); }
    private void adopt() { ok(call(args("adopt_skill"))); }
    private List<UsageLedger.Entry> rows() { return new UsageLedger(dir).all(); }
    private void noRows() { assertTrue(rows().isEmpty(), "rejection must not create result facts"); }

    @Test void completeReplyThenOriginalSuccessIsOneReviewCandidateAfterReload() {
        JsonObject adopted = call(args("adopt_skill"));
        ok(adopted);
        assertEquals("exact original steps", adopted.getAsJsonObject("data").get("body").getAsString());
        noRows();
        tasks.finish(TaskState.SUCCESS);
        ok(call(args("report_skill")));
        assertEquals(1, rows().size());
        UsageLedger.Entry e = rows().get(0);
        assertTrue(e.artifactId().matches("skill:sk-1@v1#task=[A-Za-z0-9_-]+"));
        assertEquals("EXPERIENCE", e.kind());
        assertEquals(UsageLedger.Phase.RESULT, e.phase());
        assertEquals(UsageLedger.Outcome.SUCCESS, e.outcome());
        assertEquals(C.toString(), e.companionId());
        assertEquals(UsageLedger.EvidenceLevel.NONE, e.evidence());
        assertFalse(e.validityClaimed());
        assertFalse(e.source().startsWith("acx-run:"));
        JsonObject detail = JsonParser.parseString(e.detail()).getAsJsonObject();
        assertEquals(task.publicId(), detail.get("task_id").getAsString());
        String encodedTask = e.artifactId().substring("skill:sk-1@v1#task=".length());
        var identity = JsonParser.parseString(new String(java.util.Base64.getUrlDecoder().decode(encodedTask),
                java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray();
        assertEquals(detail.getAsJsonArray("task_identity"), identity);
        assertEquals("world", identity.get(0).getAsString());
        assertEquals("session", identity.get(1).getAsString());
        assertEquals(7L, identity.get(2).getAsLong());
        assertEquals(C.toString(), identity.get(3).getAsString());
        assertEquals(task.publicId(), identity.get(4).getAsString());
        assertEquals("action-call", identity.get(5).getAsString());
        assertEquals("action-call", detail.get("task_tool_call_id").getAsString());
        assertEquals("selection-call", detail.get("selection_call_id").getAsString());
        assertEquals("SUCCESS", detail.get("terminal_state").getAsString());
        LearnerUsageTool reloaded = new LearnerUsageTool(dir, new UsageLedger(dir), tasks);
        ok(call(reloaded, args("report_skill")));
        assertEquals(1, rows().size(), "reloading the existing ledger must preserve idempotency");
    }
    @Test void failureTimeoutAndCancelRemainDistinctTaskFacts() {
        TaskState[] states = {TaskState.FAILED, TaskState.TIMEOUT, TaskState.CANCELLED};
        UsageLedger.Outcome[] outcomes = {UsageLedger.Outcome.FAIL, UsageLedger.Outcome.FAIL, UsageLedger.Outcome.CANCELED};
        for (int i = 0; i < states.length; i++) {
            task = new Work("action-" + i);
            task.bindIdentity(tasks.stamp);
            task.setState(TaskState.RUNNING);
            tasks.active = task;
            adopt();
            tasks.finish(states[i]);
            ok(call(args("report_skill")));
            assertEquals(outcomes[i], rows().get(i).outcome());
            assertTrue(rows().get(i).detail().contains(states[i].name()));
        }
    }
    @Test void terminalWithoutSelectionAndNonterminalReportCannotBecomeSuccess() {
        rejected(call(args("report_skill")));
        tasks.finish(TaskState.SUCCESS);
        rejected(call(args("report_skill")));
        noRows();
    }
    @Test void lateAdoptionAndWrongTaskAreRejected() {
        JsonObject wrong = args("adopt_skill"); wrong.addProperty("task_id", "t-never");
        rejected(call(wrong));
        tasks.finish(TaskState.SUCCESS);
        rejected(call(args("adopt_skill")));
        noRows();
    }
    @Test void explicitTaskLookupCannotFallBackToLatestFinished() {
        adopt(); tasks.finish(TaskState.SUCCESS);
        JsonObject wrong = args("report_skill"); wrong.addProperty("task_id", "t-never");
        rejected(call(wrong)); noRows();
    }
    @Test void wrongVersionCannotSelectOrReportAndSameSelectionIsIdempotent() {
        JsonObject wrong = args("adopt_skill"); wrong.addProperty("version", "v2");
        rejected(call(wrong));
        adopt(); adopt();
        tasks.finish(TaskState.SUCCESS);
        wrong = args("report_skill"); wrong.addProperty("version", "v2");
        rejected(call(wrong)); noRows();
        ok(call(args("report_skill"))); assertEquals(1, rows().size());
    }
    @Test void switchingSkillVersionOnSameTaskIsConflict() {
        adopt();
        registry.register(artifact("revised steps"), "sk-1", writeBody("revised steps"), true);
        JsonObject next = args("adopt_skill"); next.addProperty("version", "v2");
        rejected(call(next));
        tasks.finish(TaskState.SUCCESS);
        rejected(call(args("report_skill"))); noRows();
    }
    @Test void revokedSkillCannotSelectOrReport() {
        adopt(); registry.retire("sk-1", "withdrawn");
        rejected(call(args("adopt_skill")));
        tasks.finish(TaskState.SUCCESS);
        rejected(call(args("report_skill"))); noRows();
    }
    @Test void tamperedOrMissingBodyCannotSelectOrReport() throws Exception {
        adopt();
        Files.writeString(dir.resolve("skill-bodies").resolve(bodyRef.substring("skill-body:".length())), "tampered");
        rejected(call(args("adopt_skill")));
        tasks.finish(TaskState.SUCCESS);
        rejected(call(args("report_skill"))); noRows();
    }
    @Test void wrongWorldSessionEpochOrUnknownIdentityRejectsSelectionAndReporting() {
        WorldContextStamp.Token original = tasks.stamp;
        for (WorldContextStamp.Token bad : List.of(token("other", "session", 7), token("world", "old", 7),
                token("world", "session", 8), new WorldContextStamp.Token(null, 0))) {
            tasks.stamp = bad; rejected(call(args("adopt_skill")));
        }
        tasks.stamp = original; adopt(); tasks.finish(TaskState.SUCCESS);
        tasks.stamp = token("world", "new-server", 9);
        rejected(call(args("report_skill"))); noRows();
    }
    @Test void anotherCompanionCannotReportTheOriginalBinding() {
        adopt(); tasks.finish(TaskState.SUCCESS);
        tasks.companion = UUID.randomUUID();
        rejected(call(args("report_skill"))); noRows();
    }
    @Test void evictedTerminalRecordIsUnknownAndDoesNotCreateFact() {
        adopt(); tasks.finish(TaskState.SUCCESS); tasks.done.clear();
        rejected(call(args("report_skill"))); noRows();
    }
    @Test void replyFailureMustNotCreateSelectionForLaterSuccess() {
        assertDoesNotThrow(() -> tool.onServerCall("failed-reply", args("adopt_skill"), null,
                json -> { throw new IllegalStateException("transport failed"); }));
        tasks.finish(TaskState.SUCCESS);
        rejected(call(args("report_skill"))); noRows();
    }
    @Test void oversizedCompleteResponseCannotBeBoundAsDelivered() {
        registry.register(artifact("x".repeat(17000)), "sk-1", writeBody("x".repeat(17000)), true);
        JsonObject a = args("adopt_skill"); a.addProperty("version", "v2");
        rejected(call(a)); tasks.finish(TaskState.SUCCESS);
        a.addProperty("action", "report_skill"); rejected(call(a)); noRows();
    }
    @Test void callerOutcomeAndLegacySkillRecordOrClaimCannotForgeSuccess() {
        JsonObject forged = new JsonObject();
        forged.addProperty("action", "record"); forged.addProperty("artifact_id", "skill:sk-1@v1");
        forged.addProperty("phase", "RESULT"); forged.addProperty("outcome", "SUCCESS");
        rejected(call(forged));
        forged.addProperty("action", "claim_validity"); forged.addProperty("reason", "model says so");
        rejected(call(forged)); noRows();
        adopt(); tasks.finish(TaskState.SUCCESS);
        JsonObject a = args("report_skill"); a.addProperty("outcome", "SUCCESS");
        rejected(call(a)); noRows();
    }
    @Test void nonSkillRecordAndReportKeepTheirExistingBehavior() {
        JsonObject a = new JsonObject();
        a.addProperty("action", "record"); a.addProperty("artifact_id", "ac-original");
        a.addProperty("kind", "AC_SCRIPT"); a.addProperty("phase", "RESULT"); a.addProperty("outcome", "FAIL");
        ok(call(a)); assertEquals(1, rows().size());
        a = new JsonObject(); a.addProperty("action", "report"); ok(call(a));
    }

    @Test void pendingTaskAndMissingBodyCannotCreateABinding() throws Exception {
        task.setState(TaskState.PENDING);
        rejected(call(args("adopt_skill")));
        task.setState(TaskState.RUNNING);
        Files.delete(dir.resolve("skill-bodies").resolve(bodyRef.substring("skill-body:".length())));
        rejected(call(args("adopt_skill")));
        tasks.finish(TaskState.SUCCESS);
        rejected(call(args("report_skill")));
        noRows();
    }
    @Test void retryWithAnotherReplyCallRetainsOriginalSelectionCall() {
        adopt();
        AtomicReference<String> response = new AtomicReference<>();
        tool.onServerCall("retry-call", args("adopt_skill"), null, response::set);
        ok(JsonParser.parseString(response.get()).getAsJsonObject());
        tasks.finish(TaskState.SUCCESS);
        ok(call(args("report_skill")));
        JsonObject detail = JsonParser.parseString(rows().get(0).detail()).getAsJsonObject();
        assertEquals("selection-call", detail.get("selection_call_id").getAsString());
    }
    @Test void conflictingTerminalReplayCannotOverwriteOriginalFact() {
        adopt(); tasks.finish(TaskState.SUCCESS);
        ok(call(args("report_skill")));
        task.setState(TaskState.FAILED);
        rejected(call(args("report_skill")));
        assertEquals(1, rows().size());
        assertEquals(UsageLedger.Outcome.SUCCESS, rows().get(0).outcome());
    }
    @Test void equalJavaHashDoesNotPermitReplacingTheSelectedBody() throws Exception {
        assertEquals("Aa".hashCode(), "BB".hashCode());
        registry.register(artifact("Aa"), "sk-1", writeBody("Aa"), true);
        JsonObject a = args("adopt_skill"); a.addProperty("version", "v2");
        ok(call(a));
        String selectedRef = registry.get("sk-1").orElseThrow().bodyRef();
        Files.writeString(dir.resolve("skill-bodies").resolve(selectedRef.substring("skill-body:".length())), "BB");
        tasks.finish(TaskState.SUCCESS);
        a.addProperty("action", "report_skill");
        rejected(call(a)); noRows();
    }

    @Test void cancelledTaskCannotPoisonLaterSuccessForTheSameSkillVersion() {
        adopt(); tasks.finish(TaskState.CANCELLED);
        ok(call(args("report_skill")));
        String cancelledArtifact = rows().get(0).artifactId();
        task = new Work("second-action");
        task.bindIdentity(tasks.stamp);
        task.setState(TaskState.RUNNING);
        tasks.active = task;
        adopt(); tasks.finish(TaskState.SUCCESS);
        LearnerUsageTool reloaded = new LearnerUsageTool(dir, new UsageLedger(dir), tasks);
        ok(call(reloaded, args("report_skill")));
        ok(call(reloaded, args("report_skill")));
        List<UsageLedger.Entry> facts = rows();
        assertEquals(2, facts.size());
        assertEquals(UsageLedger.Outcome.CANCELED, facts.get(0).outcome());
        assertEquals(UsageLedger.Outcome.SUCCESS, facts.get(1).outcome());
        assertNotEquals(cancelledArtifact, facts.get(1).artifactId());
        assertTrue(facts.stream().allMatch(e -> e.artifactId().startsWith("skill:sk-1@v1#task=")));
        assertEquals(2, new UsageLedger(dir).conclusions().size());
        assertEquals(1, new UsageLedger(dir).associationCandidates().size());
    }
}
