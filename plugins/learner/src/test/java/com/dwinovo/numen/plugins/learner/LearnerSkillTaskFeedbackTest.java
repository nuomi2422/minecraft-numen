package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.api.WorldContextStamp;
import com.dwinovo.numen.plugins.learner.contract.ArtifactDraft;
import com.dwinovo.numen.plugins.learner.contract.ArtifactKind;
import com.dwinovo.numen.plugins.learner.core.UsageLedger;
import com.dwinovo.numen.plugins.learner.core.MemoQueue;
import com.dwinovo.numen.plugins.learner.core.SkillDeliveryEvidence;
import com.dwinovo.numen.plugins.learner.core.SkillDeliveryIntake;
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

    /**
     * 2026-11-13 characterization, not a requested product change: an RDD logical
     * step is not a body TaskRecord. Synchronous craft does not create a RUNNING
     * body record; do not keep setup's injected Work and call that craft support.
     */
    @Test void emptyBodyCannotAdoptUsingAnRddLogicalStepId() {
        tasks.active = null;
        JsonObject logical = args("adopt_skill");
        logical.addProperty("task_id", "chain-8e3b:p8:s1");
        JsonObject response = call(logical);
        rejected(response);
        assertTrue(response.get("message").getAsString().contains("精确 task_id 无原任务记录"));
        assertNull(tasks.current());
        assertTrue(tasks.done.isEmpty());
        assertNull(task.skillSelection());
        noRows();
    }

    @Test void emptyBodyCannotReuseARealOldTerminalIdAsACraftSelection() {
        tasks.finish(TaskState.SUCCESS);
        assertSame(task, tasks.finished(task.publicId()), "the old id is real, not an absent-record fixture");
        assertNull(tasks.current());
        rejected(call(args("adopt_skill")));
        assertNull(task.skillSelection());
        rejected(call(args("report_skill")));
        assertSame(task, tasks.finished(task.publicId()));
        noRows();
    }

    @Test void runningBodySelectionStillWorksButIsNotSynchronousCraftSupport() {
        // This is the existing live-task port, not a claim that CraftTool creates Work.
        assertSame(task, tasks.current());
        assertEquals(TaskState.RUNNING, task.getState());
        JsonObject response = call(args("adopt_skill"));
        ok(response);
        assertEquals(task.publicId(), response.getAsJsonObject("data").get("task_id").getAsString());
        assertEquals("exact original steps", response.getAsJsonObject("data").get("body").getAsString());
        assertEquals("skill:sk-1", task.skillSelection().skillRef());
        assertEquals("v1", task.skillSelection().version());
        noRows();
        tasks.finish(TaskState.SUCCESS);
        noRows(); // Explicit report remains required by the current contract.
        ok(call(args("report_skill")));
        assertEquals(1, rows().size());
        assertEquals(UsageLedger.Phase.RESULT, rows().get(0).phase());
        assertEquals(UsageLedger.EvidenceLevel.NONE, rows().get(0).evidence());
    }

    @Test void realChildrenAndDescriptorFallbackCannotBindToCurrentTask() {
        for (String body : List.of(
                "{\"steps\":[{\"id\":\"gate\",\"block\":\"if\",\"params\":{\"field\":\"$input.ready\",\"op\":\"==\",\"value\":true},\"children\":[{\"id\":\"go\",\"block\":\"goto\",\"params\":{\"x\":2,\"y\":88,\"z\":-3}}]}]}",
                "{\"steps\":[{\"id\":\"go\",\"block\":\"goto\",\"params\":{\"x\":{\"$from\":\"$input.table.x\",\"$default\":2},\"y\":\"$input.table.y\",\"z\":\"$input.table.z\"}}]}")) {
            registry.register(artifact(body), "sk-1", writeBody(body), true);
            JsonObject a = args("adopt_skill");
            a.addProperty("version", "v" + registry.get("sk-1").orElseThrow().version());
            rejected(call(a)); assertNull(task.skillSelection()); noRows();
        }
    }
    @Test void legacyStaticGotoCannotBindEvenToACurrentTask() {
        String body = "{\"steps\":[{\"id\":\"go\",\"block\":\"goto\",\"params\":{\"x\":2,\"y\":88,\"z\":-3}}]}";
        registry.register(artifact(body), "sk-1", writeBody(body), true);
        JsonObject a = args("adopt_skill"); a.addProperty("version", "v2");
        rejected(call(a));
        assertNull(task.skillSelection());
        tasks.finish(TaskState.SUCCESS);
        a.addProperty("action", "report_skill"); rejected(call(a)); noRows();
    }
    @Test void parameterizedGotoAdoptionKeepsTheExactBodyAndNormalReporting() {
        String body = "{\"steps\":[{\"id\":\"go\",\"block\":\"goto\",\"params\":{\"x\":\"$input.table.x\",\"y\":\"$input.table.y\",\"z\":\"$input.table.z\"}}]}";
        registry.register(artifact(body), "sk-1", writeBody(body), true);
        JsonObject a = args("adopt_skill"); a.addProperty("version", "v2");
        var result = call(a); ok(result);
        assertEquals(body, result.getAsJsonObject("data").get("body").getAsString());
        tasks.finish(TaskState.SUCCESS);
        a.addProperty("action", "report_skill"); ok(call(a));
        assertEquals(1, rows().size());
    }

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

    // 2026-11-13 C1: join real delivery and original task-result producers.
    // These helpers reuse the normal usage tool and persisted ledger; only task lookup is injected.
    private UsageLedger.Entry deliver(String request, String taskId, String taskCall, String ref,
                                      String version, SkillDeliveryIntake.Scope scope) {
        var result = SkillDeliveryEvidence.record(new SkillDeliveryEvidence.Context(request,
                scope.worldId(), scope.serverSession(), scope.epoch(), scope.companionId(),
                "EXECUTOR", "execution", taskId, taskCall,
                List.of(new SkillDeliveryEvidence.Ref(ref, version))), ledger);
        assertEquals(1, result.written());
        assertFalse(result.ioFailed());
        return rows().stream().filter(e -> result.artifacts().contains(e.artifactId())).findFirst().orElseThrow();
    }

    private SkillDeliveryIntake.Scope deliveryScope() {
        return new SkillDeliveryIntake.Scope("world", "session", 7L, C.toString());
    }

    private static SkillDeliveryIntake.EnqueuedJournal deliveryJournal() {
        return new SkillDeliveryIntake.EnqueuedJournal() {
            private final java.util.Set<String> ids = new java.util.HashSet<>();
            public boolean contains(String id) { return ids.contains(id); }
            public void remember(String id) { ids.add(id); }
        };
    }

    private void assertDeliveryRemains(List<UsageLedger.Entry> facts, UsageLedger.Entry delivery,
                                       SkillDeliveryIntake.Scope scope, String queueName) {
        assertEquals(List.of(delivery), SkillDeliveryIntake.selectDue(facts, scope,
                java.util.Set.of(), java.util.Set.of(), 16));
        MemoQueue queue = new MemoQueue(dir.resolve(queueName));
        int before = rows().size();
        var scan = SkillDeliveryIntake.run(facts, scope, queue, deliveryJournal(),
                (c, fp, now, once) -> once.getAsBoolean(), 1000L, 16);
        assertEquals(1, scan.enqueued());
        assertEquals(0, scan.resultChannel());
        assertEquals(1, queue.all().size());
        assertEquals("skill_delivery", queue.all().get(0).stage());
        assertEquals(before, rows().size(), "intake must not write adoption, result or effectiveness facts");
        assertNull(LearnerPlugin.runningReview(C), "intake must not start review");
    }

    @Test void realDeliveryAndOriginalResultJoinAcrossDifferentArtifactFormats() {
        var delivery = deliver("req-join", task.publicId(), task.getToolCallId(),
                "skill:sk-1", "v1", deliveryScope());
        adopt(); tasks.finish(TaskState.SUCCESS); ok(call(args("report_skill")));
        var result = rows().get(1);
        assertNotEquals(delivery.artifactId(), result.artifactId());
        assertEquals("EXPERIENCE", result.kind(), "real result kind is the skill's artifact kind");
        assertEquals(UsageLedger.EvidenceLevel.NONE, result.evidence());
        assertFalse(result.validityClaimed());
        for (boolean reverse : List.of(false, true)) {
            var facts = new java.util.ArrayList<>(rows());
            if (reverse) java.util.Collections.reverse(facts);
            assertTrue(SkillDeliveryIntake.selectDue(facts, deliveryScope(),
                    java.util.Set.of(), java.util.Set.of(), 16).isEmpty());
            MemoQueue queue = new MemoQueue(dir.resolve("joined-" + reverse + ".json"));
            var scan = SkillDeliveryIntake.run(facts, deliveryScope(), queue, deliveryJournal(),
                    (c, fp, now, once) -> once.getAsBoolean(), 1000L, 16);
            assertEquals(0, scan.enqueued());
            assertEquals(1, scan.resultChannel(), "classified delivery, not a completed learning review");
            assertTrue(queue.all().isEmpty());
            assertNull(LearnerPlugin.runningReview(C));
        }
        assertEquals(2, rows().size());
    }

    /**
     * C1-B: actual delivery + actual adopt/report facts enter the actual host scan.
     * Replay each real producer variant as a complete disk ledger, not singleton run(row) calls.
     */
    @Test void realHostScanKeepsExactResultJoinAndOriginalIdentityBoundaries() throws Exception {
        var joined = deliver("host-joined", task.publicId(), task.getToolCallId(),
                "skill:sk-1", "v1", deliveryScope());
        adopt(); tasks.finish(TaskState.SUCCESS); ok(call(args("report_skill")));
        String joinedLedger = Files.readString(ledger.file());
        List<UsageLedger.Entry> variants = List.of(
                deliver("host-wrong-task", "other-task", task.getToolCallId(),
                        "skill:sk-1", "v1", deliveryScope()),
                deliver("host-wrong-call", task.publicId(), "other-call",
                        "skill:sk-1", "v1", deliveryScope()),
                deliver("host-selection", task.publicId(), "selection-call",
                        "skill:sk-1", "v1", deliveryScope()),
                deliver("host-version", task.publicId(), task.getToolCallId(),
                        "skill:sk-1", "v2", deliveryScope()),
                deliver("host-episode", "", "", "skill:sk-1", "v1", deliveryScope()));
        List<String> allLines = Files.readAllLines(ledger.file());
        Map<String, Object> old = new HashMap<>();
        for (String name : List.of("configDirCache", "enabled", "queueResolver", "deliveryLedgerReader")) {
            var field = LearnerAutoIntake.class.getDeclaredField(name);
            field.setAccessible(true);
            old.put(name, field.get(null));
        }
        var keyField = LearnerAutoIntake.class.getDeclaredField("ENQUEUED");
        keyField.setAccessible(true);
        @SuppressWarnings("unchecked") var keys = (java.util.Set<String>) keyField.get(null);
        var oldKeys = new java.util.HashSet<>(keys);
        var cursorField = LearnerAutoIntake.class.getDeclaredField("CURSORS");
        cursorField.setAccessible(true);
        @SuppressWarnings("unchecked") var cursors = (Map<String, Long>) cursorField.get(null);
        var oldCursors = new HashMap<>(cursors);
        var hostQueue = new MemoQueue(dir.resolve("host-memos.json"));
        try {
            for (var entry : Map.of("configDirCache", (Object) dir, "enabled", true).entrySet()) {
                var field = LearnerAutoIntake.class.getDeclaredField(entry.getKey());
                field.setAccessible(true);
                field.set(null, entry.getValue());
            }
            keys.clear();
            cursors.clear();
            LearnerAutoIntake.installQueueResolverForTest(owner -> {
                assertEquals(C, owner);
                return hostQueue;
            });
            var reads = new java.util.concurrent.atomic.AtomicInteger();
            LearnerAutoIntake.installDeliveryLedgerReaderForTest(() -> {
                reads.incrementAndGet();
                return ledger.readSnapshot();
            });
            for (int i = 0; i < variants.size(); i++) {
                // Both joined rows plus one real producer row; the entire file is passed to the host.
                Files.writeString(ledger.file(), joinedLedger + allLines.get(i + 2) + "\n");
                com.dwinovo.numen.plugins.learner.core.StormGuard.resetForTest();
                assertEquals(1, LearnerAutoIntake.scanOnce(() ->
                        new LearnerAutoIntake.DeliveryScope("world", "session", 7)));
                assertEquals(i + 1, reads.get(), "one full ledger snapshot per host scan");
                assertTrue(hostQueue.contains(SkillDeliveryIntake.memoIdOf(variants.get(i))));
                assertFalse(hostQueue.contains(SkillDeliveryIntake.memoIdOf(joined)),
                        "EXPERIENCE result with exact original task/call must classify joined delivery");
                assertEquals(i + 1, hostQueue.size());
                assertEquals(3, ledger.all().size(), "consumer must not create adoption/result/validity facts");
                assertNull(LearnerPlugin.runningReview(C));
            }
        } finally {
            keys.clear(); keys.addAll(oldKeys);
            cursors.clear(); cursors.putAll(oldCursors);
            for (var entry : old.entrySet()) {
                var field = LearnerAutoIntake.class.getDeclaredField(entry.getKey());
                field.setAccessible(true);
                field.set(null, entry.getValue());
            }
            com.dwinovo.numen.plugins.learner.core.StormGuard.resetForTest();
        }
    }

    @Test void realTaskDeliveryWithoutAReportedResultStillEnqueues() {
        var delivery = deliver("req-pending", task.publicId(), task.getToolCallId(),
                "skill:sk-1", "v1", deliveryScope());
        assertDeliveryRemains(rows(), delivery, deliveryScope(), "unreported.json");
    }

    @Test void originalTaskAndCallMatchExactlyAndSelectionCallCannotSubstitute() {
        var otherTask = new Work(task.getToolCallId());
        var wrongTask = deliver("req-other-task", otherTask.publicId(), otherTask.getToolCallId(),
                "skill:sk-1", "v1", deliveryScope());
        var wrongCall = deliver("req-other-call", task.publicId(), "another-action-call",
                "skill:sk-1", "v1", deliveryScope());
        var selectionCall = deliver("req-selection-call", task.publicId(), "selection-call",
                "skill:sk-1", "v1", deliveryScope());
        adopt(); tasks.finish(TaskState.SUCCESS); ok(call(args("report_skill")));
        var result = rows().get(3);
        for (var delivery : List.of(wrongTask, wrongCall, selectionCall)) {
            assertDeliveryRemains(List.of(result, delivery), delivery, deliveryScope(),
                    "different-" + SkillDeliveryIntake.memoIdOf(delivery) + ".json");
        }
    }

    @Test void worldSessionEpochAndCompanionCannotBorrowAnotherResult() {
        adopt(); tasks.finish(TaskState.SUCCESS); ok(call(args("report_skill")));
        var result = rows().get(0);
        var scopes = List.of(new SkillDeliveryIntake.Scope("other-world", "session", 7, C.toString()),
                new SkillDeliveryIntake.Scope("world", "other-session", 7, C.toString()),
                new SkillDeliveryIntake.Scope("world", "session", 8, C.toString()),
                new SkillDeliveryIntake.Scope("world", "session", 7, UUID.randomUUID().toString()));
        int i = 0;
        for (var scope : scopes) {
            var delivery = deliver("req-scope-" + i, task.publicId(), task.getToolCallId(),
                    "skill:sk-1", "v1", scope);
            assertDeliveryRemains(List.of(delivery, result), delivery, scope, "scope-" + i++ + ".json");
        }
    }

    @Test void realOtherSkillResultDoesNotSuppressTheDeliveredSkill() {
        var delivery = deliver("req-ref", task.publicId(), task.getToolCallId(),
                "skill:sk-1", "v1", deliveryScope());
        String otherBody = SkillBodyStore.write(dir, "sk-2", 1, Integer.toHexString("other original steps".hashCode()), "other original steps");
        ArtifactDraft other = new ArtifactDraft(ArtifactKind.EXPERIENCE, "other safe steps", "cross a bridge",
                "other original steps", List.of("night"), List.of(), List.of(), "LOW", List.of(),
                List.of("memo-2"), "EXECUTOR");
        var registered = registry.register(other, "sk-2", otherBody, true);
        assertEquals(SkillRegistry.Outcome.REGISTERED, registered.outcome());
        assertEquals("sk-2", registered.entry().skillId());
        JsonObject choose = args("adopt_skill"); choose.addProperty("skill_ref", "skill:sk-2");
        ok(call(choose)); tasks.finish(TaskState.SUCCESS);
        choose.addProperty("action", "report_skill"); ok(call(choose));
        assertDeliveryRemains(rows(), delivery, deliveryScope(), "other-ref.json");
    }

    @Test void realNextVersionResultDoesNotSuppressTheDeliveredOldVersion() {
        var delivery = deliver("req-version", task.publicId(), task.getToolCallId(),
                "skill:sk-1", "v1", deliveryScope());
        registry.register(artifact("revised steps"), "sk-1", writeBody("revised steps"), true);
        JsonObject choose = args("adopt_skill"); choose.addProperty("version", "v2");
        ok(call(choose)); tasks.finish(TaskState.SUCCESS);
        choose.addProperty("action", "report_skill"); ok(call(choose));
        assertDeliveryRemains(rows(), delivery, deliveryScope(), "other-version.json");
    }

    @Test void episodeDeliveryCannotBorrowASameReferenceTaskResult() {
        var delivery = deliver("req-episode", "", "", "skill:sk-1", "v1", deliveryScope());
        adopt(); tasks.finish(TaskState.SUCCESS); ok(call(args("report_skill")));
        assertDeliveryRemains(rows(), delivery, deliveryScope(), "episode.json");
    }

    @Test void incompleteOrForeignNamespaceResultCannotSuppressDelivery() {
        var delivery = deliver("req-incomplete", task.publicId(), task.getToolCallId(),
                "skill:sk-1", "v1", deliveryScope());
        adopt(); tasks.finish(TaskState.SUCCESS); ok(call(args("report_skill")));
        var result = rows().get(1);
        var fields = List.of("skill_ref", "version", "world_id", "server_session", "epoch",
                "companion_id", "task_id", "task_tool_call_id", "task_identity");
        int i = 0;
        for (String field : fields) {
            JsonObject detail = JsonParser.parseString(result.detail()).getAsJsonObject();
            detail.remove(field);
            var incomplete = ledger.append(result.artifactId(), result.kind(), result.name(), result.phase(),
                    result.outcome(), detail.toString(), result.source(), result.companionId());
            assertDeliveryRemains(List.of(delivery, incomplete), delivery, deliveryScope(),
                    "incomplete-" + i++ + ".json");
        }
        var foreignSource = ledger.append(result.artifactId(), result.kind(), result.name(), result.phase(),
                result.outcome(), result.detail(), "manual:test", result.companionId());
        assertDeliveryRemains(List.of(delivery, foreignSource), delivery, deliveryScope(), "foreign-source.json");
        var badDetail = ledger.append(result.artifactId(), result.kind(), result.name(), result.phase(),
                result.outcome(), "{broken", result.source(), result.companionId());
        assertDeliveryRemains(List.of(delivery, badDetail), delivery, deliveryScope(), "broken-detail.json");
    }

    @Test void oneRealResultOnlyClassifiesItsExactDeliveryInEitherRowOrder() {
        var matching = deliver("req-matching", task.publicId(), task.getToolCallId(),
                "skill:sk-1", "v1", deliveryScope());
        Work another = new Work("another-original-call");
        var remaining = deliver("req-remaining", another.publicId(), another.getToolCallId(),
                "skill:sk-1", "v1", deliveryScope());
        adopt(); tasks.finish(TaskState.SUCCESS); ok(call(args("report_skill")));
        for (boolean reverse : List.of(false, true)) {
            var facts = new java.util.ArrayList<>(rows());
            if (reverse) java.util.Collections.reverse(facts);
            assertEquals(List.of(remaining), SkillDeliveryIntake.selectDue(facts, deliveryScope(),
                    java.util.Set.of(), java.util.Set.of(), 16));
            MemoQueue queue = new MemoQueue(dir.resolve("one-to-many-" + reverse + ".json"));
            var scan = SkillDeliveryIntake.run(facts, deliveryScope(), queue, deliveryJournal(),
                    (c, fp, now, once) -> once.getAsBoolean(), 1000L, 16);
            assertEquals(1, scan.enqueued());
            assertEquals(1, scan.resultChannel());
            assertTrue(queue.contains(SkillDeliveryIntake.memoIdOf(remaining)));
            assertFalse(queue.contains(SkillDeliveryIntake.memoIdOf(matching)));
        }
    }

}
