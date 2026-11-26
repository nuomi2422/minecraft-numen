package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.api.WorldContextStamp;
import com.dwinovo.numen.plugins.learner.contract.ArtifactDraft;
import com.dwinovo.numen.plugins.learner.contract.ArtifactKind;
import com.dwinovo.numen.plugins.learner.core.*;
import com.dwinovo.numen.plugins.learner.skills.SkillBodyStore;
import com.dwinovo.numen.plugins.learner.skills.SkillRegistrar;
import com.dwinovo.numen.plugins.learner.skills.SkillRegistry;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.task.TaskState;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-13 C1 host red controls: real scanOnce, original disk ledger/queue/ENQUEUED journal.
 * Producer fixture minimally copied from LearnerTaskResultIntakeTest (Work/Tasks/adopt/report).
 * Why not hand-build legal RESULT: that skips real full-body selection and original task binding.
 * Only existing queueResolver/deliveryLedgerReader inputs are installed; no fake Tool/Ledger/registry.
 * Run via the parent's exact managed test-only registration; no review, game or production edits.
 * All replaced providers and isolated static state are restored, even after assertion failures.
 * Exhaustive terminal/identity/snapshot variants remain core coverage, not claimed host coverage.
 */
class LearnerAutoTaskResultIntakeTest {
    @TempDir Path dir;
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000031");
    private static final LearnerAutoIntake.DeliveryScope CURRENT =
            new LearnerAutoIntake.DeliveryScope("world", "session", 7);
    private final List<Executable> restorations = new ArrayList<>();
    private final Set<String> fingerprints = new LinkedHashSet<>();
    private final AtomicInteger reads = new AtomicInteger();
    private Set<String> keys;
    private Map<Object, Object> cursors;
    private UsageLedger ledger;
    private MemoQueue queue;
    private SkillRegistry registry;
    private LearnerUsageTool tool;
    private Tasks tasks;

    @BeforeEach void setup() throws Exception {
        for (String name : List.of("configDirCache", "enabled", "lastToggleCheck", "queueResolver", "deliveryLedgerReader")) {
            Field field = field(LearnerAutoIntake.class, name);
            Object original = field.get(null);
            restorations.add(() -> field.set(null, original));
        }
        keys = isolateKeys();
        cursors = isolateMap(LearnerAutoIntake.class, "CURSORS");
        // Preserve old objects rather than reset unrelated tests' cooldowns, counters or registries.
        isolateMap(StormGuard.class, "STATES");
        isolateMap(T3Seen.class, "SEEN");
        isolateMap(SkillRegistrar.class, "REGISTRIES");
        set("configDirCache", dir); set("enabled", true); set("lastToggleCheck", -1L);
        ledger = new UsageLedger(dir);
        queue = new MemoQueue(dir.resolve("learner-memos-" + OWNER + ".json"));
        registry = SkillRegistrar.registryFor(dir); // Only this @TempDir registry, never live data.
        tasks = new Tasks();
        tool = new LearnerUsageTool(dir, ledger, tasks);
        LearnerAutoIntake.installQueueResolverForTest(owner -> { assertEquals(OWNER, owner); return queue; });
        LearnerAutoIntake.installDeliveryLedgerReaderForTest(() -> { reads.incrementAndGet(); return ledger.readSnapshot(); });
        Files.createDirectories(dir.resolve("monitor"));
    }

    @AfterEach void restoreGlobals() {
        assertAll("restore every original provider, toggle, key and cache", restorations);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void set(String name, Object value) throws Exception {
        field(LearnerAutoIntake.class, name).set(null, value);
    }
    @SuppressWarnings("unchecked")
    private static Map<Object, Object> map(Class<?> type, String name) throws Exception {
        return (Map<Object, Object>) field(type, name).get(null);
    }
    private Map<Object, Object> isolateMap(Class<?> type, String name) throws Exception {
        Map<Object, Object> live = map(type, name), original = new LinkedHashMap<>(live);
        restorations.add(() -> { live.clear(); live.putAll(original); });
        live.clear(); return live;
    }
    @SuppressWarnings("unchecked")
    private Set<String> isolateKeys() throws Exception {
        Set<String> live = (Set<String>) field(LearnerAutoIntake.class, "ENQUEUED").get(null);
        Set<String> original = new LinkedHashSet<>(live);
        restorations.add(() -> { live.clear(); live.addAll(original); });
        live.clear(); return live;
    }

    private static WorldContextStamp.Token token(LearnerAutoIntake.DeliveryScope scope) {
        return new WorldContextStamp.Token(Map.of("world_id", scope.worldId(), "server_session", scope.serverSession()), scope.epoch());
    }
    private static final class Work extends TaskRecord {
        Work(String call) { super("goto", call, 1000); }
    }
    private static final class Tasks implements LearnerUsageTool.TaskAccess {
        WorldContextStamp.Token stamp = token(CURRENT);
        UUID companion = OWNER;
        TaskRecord active;
        final Map<String, TaskRecord> done = new HashMap<>();
        public UUID companionId() { return companion; }
        public WorldContextStamp.Token identity() { return stamp; }
        public TaskRecord current() { return active; }
        public TaskRecord finished(String id) { return done.get(id); }
        void finish(TaskState state) {
            active.setState(state);
            active.setResult(state == TaskState.SUCCESS ? TaskResult.ok("arrived") : TaskResult.fail("ended"));
            done.put(active.publicId(), active); active = null;
        }
    }

    private static final String LEARNING_EVENT = "tools-async-learning-1";
    private record Learned(String skill, String version, String body, Memo source) {}

    /** Produce the artifact through the actual completion contract, never by synthesizing RESULT. */
    private Learned learnForBodyTask() throws Exception {
        Files.writeString(dir.resolve("monitor/tools.jsonl"),
                "{\"event_id\":\"" + LEARNING_EVENT + "\",\"event\":\"tool_result\"}\n");
        Memo source = new Memo("async-original-learning", "learn a safe method", "learning", "review", "", 1L,
                "learning", List.of(LEARNING_EVENT));
        MemoQueue learning = new MemoQueue(dir.resolve("source-learning.json"));
        assertTrue(learning.append(source));
        var batch = learning.drain(3);
        Experience experience = new Experience("async learned method", "task is active", "old attempt failed",
                "observed task result", "follow original learned steps", "avoid the failed attempt",
                LEARNING_EVENT, "FAILURE", List.of("EXECUTOR"));
        Verdict verdict = new Verdict(source.id(), List.of(Verdict.Action.WRITE_EXPERIENCE), .8,
                "derive a candidate method from the original event", experience, "", "", "", "", List.of(), List.of(),
                new RetrievalDecision(List.of(), RetrievalDecision.Kind.NEW, "no existing matching skill"));
        var stamp = token(CURRENT);
        var completion = new ReviewCompletion(batch, memo -> ReviewTargetGate.check(dir, OWNER, memo, stamp, stamp));
        var consumed = completion.consume(dir, learning, batch, List.of(verdict), (kind, data) -> {});
        assertEquals(1, consumed.settlement().committed());
        var submission = com.dwinovo.numen.plugins.learner.contract.ReviewContractAdapter.toSubmission(verdict,
                source.id(), com.dwinovo.numen.plugins.learner.contract.Submission.Mode.INTERNAL, "", List.of(LEARNING_EVENT));
        String skill = SkillRegistrar.skillIdOf(submission.artifacts().getFirst());
        var entry = registry.get(skill).orElseThrow();
        assertTrue(entry.foundingSamples().contains(source.id()));
        assertEquals(com.dwinovo.numen.plugins.learner.skills.SkillStatus.DRAFT, entry.status(),
                "new EXPERIENCE uses the existing DRAFT publication policy, not automatic adoption permission");
        Work unpublishedTask = new Work("draft-must-not-adopt");
        unpublishedTask.bindIdentity(tasks.stamp); unpublishedTask.setState(TaskState.RUNNING); tasks.active = unpublishedTask;
        var unpublished = new Learned(skill, "v" + entry.version(), SkillBodyStore.read(dir, entry.bodyRef()), source);
        JsonObject denied = taskUsage("adopt_skill", unpublished, unpublishedTask);
        assertFalse(denied.get("success").getAsBoolean(), "DRAFT must not acquire an async task binding");
        assertNull(unpublishedTask.skillSelection());
        assertTrue(ledger.all().isEmpty());
        tasks.active = null;
        var publication = registry.promote(skill, "controlled test publication after actual learning output");
        assertTrue(publication.stored(), publication.reason());
        entry = registry.get(skill).orElseThrow();
        assertTrue(entry.status().retrievable());
        return new Learned(skill, "v" + entry.version(), SkillBodyStore.read(dir, entry.bodyRef()), source);
    }

    private JsonObject taskUsage(String action, Learned learned, Work task) {
        JsonObject args = new JsonObject();
        args.addProperty("action", action); args.addProperty("task_id", task.publicId());
        args.addProperty("skill_ref", "skill:" + learned.skill()); args.addProperty("version", learned.version());
        AtomicReference<String> reply = new AtomicReference<>();
        tool.onServerCall(action + "-" + task.getToolCallId(), args, null, reply::set);
        return JsonParser.parseString(reply.get()).getAsJsonObject();
    }
    private Work selectLearned(Learned learned, String call) {
        Work work = new Work(call); work.bindIdentity(tasks.stamp); work.setState(TaskState.RUNNING); tasks.active = work;
        JsonObject response = taskUsage("adopt_skill", learned, work);
        assertTrue(response.get("success").getAsBoolean(), response.toString());
        assertEquals(learned.body(), response.getAsJsonObject("data").get("body").getAsString());
        assertNotNull(work.skillSelection());
        assertEquals("skill:" + learned.skill(), work.skillSelection().skillRef());
        return work;
    }
    private Memo finishAndCollect(Learned learned, Work work) {
        tasks.finish(TaskState.SUCCESS);
        JsonObject result = taskUsage("report_skill", learned, work);
        assertTrue(result.get("success").getAsBoolean(), result.toString());
        assertEquals(1, ledger.all().size());
        var row = ledger.all().getFirst();
        assertEquals(UsageLedger.EvidenceLevel.NONE, row.evidence());
        assertFalse(row.validityClaimed());
        assertEquals(1, scan());
        Memo memo = queue.all().getFirst();
        assertEquals("skill_task_result", memo.stage());
        var fact = SkillDeliveryIntake.taskResultOf(row);
        assertNotNull(fact); assertEquals(work.publicId(), fact.taskId());
        assertEquals(work.getToolCallId(), fact.taskCall());
        assertEquals(fact, SkillDeliveryIntake.taskResultFromSnapshot(memo.snapshot()), "host must preserve the exact producer fact");
        assertEquals(UsageLedger.Outcome.SUCCESS, row.outcome());
        assertEquals(OWNER.toString(), row.companionId());
        assertEquals("skill:" + learned.skill(), fact.ref());
        assertEquals(learned.version(), fact.version());
        String input = TrajectorySlices.forMemo(dir, OWNER, memo);
        assertTrue(input.contains(learned.body())); assertTrue(input.contains(work.publicId()));
        return memo;
    }
    private ReviewCompletion.Consumption consumeReturned(Memo memo, boolean revoke, Learned learned) {
        var batch = queue.drain(3); assertEquals(List.of(memo), batch);
        var stable = token(CURRENT);
        var completion = new ReviewCompletion(batch, sample -> ReviewTargetGate.check(dir, OWNER, sample, stable, stable));
        assertTrue(completion.permits(memo.id()));
        if (revoke) assertTrue(registry.retire(learned.skill(), "controlled withdrawal after candidate intake").stored());
        Verdict verdict = new Verdict(memo.id(), List.of(Verdict.Action.NO_ACTION), .8,
                "original task terminal result only, not world effectiveness", null, "", "", "", List.of(), List.of());
        return completion.consume(dir, queue, batch, List.of(verdict), (kind, data) -> {});
    }

    @Test void originalAsyncResultCanReviseItsOwnLearnedArtifactWithoutPromotingEvidence() throws Exception {
        Learned learned = learnForBodyTask();
        Work work = selectLearned(learned, "mcp-async-relearn-original-call");
        work.markAsync();
        tasks.finish(TaskState.SUCCESS);
        try (AsyncJournalFixture journal = new AsyncJournalFixture(dir)) {
            OriginalAsyncJournalProducer.publish(OWNER, work); journal.barrier();
            assertTrue(taskUsage("report_skill", learned, work).get("success").getAsBoolean());
            assertEquals(1, scan());
        }
        Memo returned = queue.all().getFirst();
        assertEquals(1, returned.evidence().size());
        Files.writeString(dir.resolve(com.dwinovo.numen.plugins.learner.contract.ReviewContractGate.FILE_NAME), "{\"mode\":\"enforce\"}");
        var batch = queue.drain(3);
        var stable = token(CURRENT);
        var completion = new ReviewCompletion(batch, sample -> ReviewTargetGate.check(dir, OWNER, sample, stable, stable));
        assertTrue(completion.permits(returned.id()));
        // Keep existing metadata/fingerprint. The new text is a candidate interpretation, not a world-effect assertion.
        Experience interpretation = new Experience("async learned method", "task is active", "old attempt failed",
                "observed task result", "follow original learned steps; task completion alone is not world verification",
                "avoid the failed attempt", "", "FAILURE", List.of("EXECUTOR"));
        Verdict revision = new Verdict(returned.id(), List.of(Verdict.Action.WRITE_EXPERIENCE), .8,
                "revise candidate instructions while retaining uncertainty about world effectiveness", interpretation,
                "", "", "", "", List.of(), List.of(), new RetrievalDecision(
                List.of(new RetrievalDecision.Hit("skill:" + learned.skill(), "observed task result", "same original method", true, "TRIAL")),
                RetrievalDecision.Kind.REVISE, "same learned method; retain original task identity"));
        var consumed = completion.consume(dir, queue, batch, List.of(revision), (kind, data) -> {});
        assertEquals(1, consumed.settlement().committed());
        var updated = registry.get(learned.skill()).orElseThrow();
        assertEquals(Integer.parseInt(learned.version().substring(1)) + 1, updated.version());
        assertTrue(updated.foundingSamples().contains(learned.source().id()));
        assertTrue(updated.foundingSamples().contains(returned.id()));
        assertEquals(UsageLedger.EvidenceLevel.NONE, ledger.all().getFirst().evidence());
        assertEquals(1, ledger.all().size(), "revision cannot manufacture another task result");
        assertTrue(TrajectorySlices.forMemo(dir, OWNER, returned).contains(learned.body()), "history keeps the selected original body, not the replacement revision body");
        assertFalse(ReviewTargetGate.check(dir, OWNER, returned, stable, stable).ok(), "old sample no longer grants current replacement-version permission");
        assertEquals(0, new MemoQueue(queue.file()).size());
    }

    @Test void originalJournalWithoutTaskIdentityRemainsDiagnosticAndCannotAuthorizeEvidence() throws Exception {
        Learned learned = learnForBodyTask();
        Work work = selectLearned(learned, "mcp-original-legacy-task-id");
        work.markAsync(); tasks.finish(TaskState.SUCCESS);
        try (AsyncJournalFixture journal = new AsyncJournalFixture(dir)) {
            OriginalAsyncJournalProducer.publish(OWNER, work); journal.barrier();
            Path log = dir.resolve("monitor/tools.jsonl");
            String original = Files.readString(log);
            var legacy = new StringBuilder();
            for (String line : original.lines().toList()) {
                var envelope = JsonParser.parseString(line).getAsJsonObject();
                if (envelope.has("data")) envelope.getAsJsonObject("data").remove("task_id");
                legacy.append(envelope).append('\n');
            }
            Files.writeString(log, legacy.toString()); // remove only the field from a real original event: exact legacy record shape
            assertTrue(taskUsage("report_skill", learned, work).get("success").getAsBoolean());
            assertEquals(1, scan());
            Memo memo = queue.all().getFirst();
            assertTrue(memo.evidence().isEmpty());
            assertTrue(TrajectorySlices.forMemo(dir, OWNER, memo).contains("TASK_UNKNOWN"));
            Files.writeString(log, original); // restore exact producer bytes; never manufacture a task id
            Memo restored = SkillDeliveryIntake.withTaskResultEvidence(dir, OWNER, memo);
            assertEquals(1, restored.evidence().size());
            assertEquals(memo.id(), restored.id()); assertEquals(memo.snapshot(), restored.snapshot());
            assertEquals(UsageLedger.EvidenceLevel.NONE, ledger.all().getFirst().evidence());
        }
    }

    @Test void sameScopeDifferentCallCannotSupplySelectedTaskEvidence() throws Exception {
        Learned learned = learnForBodyTask();
        Work selected = selectLearned(learned, "mcp-original-distinct-call");
        selected.markAsync(); tasks.finish(TaskState.SUCCESS);
        Work other = new Work("mcp-foreign-distinct-call");
        other.markAsync(); other.bindIdentity(selected.identityToken());
        other.setState(TaskState.SUCCESS); other.setResult(TaskResult.ok("other call finished"));
        try (AsyncJournalFixture journal = new AsyncJournalFixture(dir)) {
            OriginalAsyncJournalProducer.publish(OWNER, other); journal.barrier();
            var events = Files.readAllLines(dir.resolve("monitor/tools.jsonl")).stream()
                    .map(line -> JsonParser.parseString(line).getAsJsonObject())
                    .filter(row -> row.has("type") && "tool_result".equals(row.get("type").getAsString())).toList();
            assertEquals(1, events.size());
            assertEquals(other.publicId(), events.getFirst().getAsJsonObject("data").get("task_id").getAsString());
            assertTrue(taskUsage("report_skill", learned, selected).get("success").getAsBoolean());
            assertEquals(1, scan());
            Memo memo = queue.all().getFirst();
            assertTrue(memo.evidence().isEmpty());
            assertFalse(TrajectorySlices.forMemo(dir, OWNER, memo).contains("event:" + events.getFirst().get("event_id").getAsString()));
            assertEquals(UsageLedger.EvidenceLevel.NONE, ledger.all().getFirst().evidence());
        }
    }

    @Test void sameScopeCallFromDifferentTaskCannotSupplySelectedTaskEvidence() throws Exception {
        Learned learned = learnForBodyTask();
        Work selected = selectLearned(learned, "mcp-reused-call-different-task");
        selected.markAsync(); tasks.finish(TaskState.SUCCESS);
        Work other = new Work(selected.getToolCallId());
        other.markAsync(); other.bindIdentity(selected.identityToken());
        other.setState(TaskState.SUCCESS); other.setResult(TaskResult.ok("another task ended"));
        assertNotEquals(selected.publicId(), other.publicId());
        try (AsyncJournalFixture journal = new AsyncJournalFixture(dir)) {
            OriginalAsyncJournalProducer.publish(OWNER, other); journal.barrier();
            var originalRows = Files.readAllLines(dir.resolve("monitor/tools.jsonl")).stream()
                    .map(line -> JsonParser.parseString(line).getAsJsonObject())
                    .filter(row -> row.has("type") && "tool_result".equals(row.get("type").getAsString()))
                    .filter(row -> selected.getToolCallId().equals(row.getAsJsonObject("data").get("tool_call_id").getAsString())).toList();
            assertEquals(1, originalRows.size(), "different-task producer must really persist before testing isolation");
            var originalEvent = originalRows.getFirst();
            assertEquals(CURRENT.worldId(), originalEvent.get("world_id").getAsString());
            assertEquals(CURRENT.serverSession(), originalEvent.get("server_session").getAsString());
            assertEquals(CURRENT.epoch(), originalEvent.get("world_epoch").getAsLong());
            assertEquals("another task ended", originalEvent.getAsJsonObject("data").get("result").getAsString());
            assertTrue(EvidenceJournal.unresolved(dir.resolve("monitor"),
                    List.of(originalEvent.get("event_id").getAsString())).isEmpty());
            assertTrue(taskUsage("report_skill", learned, selected).get("success").getAsBoolean());
            assertEquals(1, scan());
            Memo memo = queue.all().getFirst();
            assertTrue(memo.evidence().isEmpty(), "same scope and call cannot substitute a different original TaskRecord");
            assertEquals(UsageLedger.EvidenceLevel.NONE, ledger.all().getFirst().evidence());
        }
    }

    @Test void disappearedOriginalJournalCannotAuthorizeSubmissionFromOldMemoEvidence() throws Exception {
        Learned learned = learnForBodyTask();
        Work work = selectLearned(learned, "mcp-disappeared-original-evidence");
        work.markAsync(); tasks.finish(TaskState.SUCCESS);
        try (AsyncJournalFixture journal = new AsyncJournalFixture(dir)) {
            OriginalAsyncJournalProducer.publish(OWNER, work); journal.barrier();
            assertTrue(taskUsage("report_skill", learned, work).get("success").getAsBoolean());
            assertEquals(1, scan());
            Memo memo = queue.all().getFirst();
            assertFalse(memo.evidence().isEmpty());
            var batch = queue.drain(3);
            var stable = token(CURRENT);
            var completion = new ReviewCompletion(batch, sample -> ReviewTargetGate.check(dir, OWNER, sample, stable, stable));
            assertTrue(completion.permits(memo.id()));
            String before = registryBytes();
            Files.writeString(dir.resolve(com.dwinovo.numen.plugins.learner.contract.ReviewContractGate.FILE_NAME), "{\"mode\":\"enforce\"}");
            Files.writeString(dir.resolve("monitor/tools.jsonl"), ""); // controlled original-log loss, not fabricated evidence
            var noAction = new Verdict(memo.id(), List.of(Verdict.Action.NO_ACTION), .8,
                    "old memo evidence is not permanent permission", null, "", "", "", List.of(), List.of());
            var rejected = completion.consume(dir, queue, batch, List.of(noAction), (kind, data) -> {});
            assertEquals(0, rejected.settlement().committed()); assertEquals(1, rejected.settlement().restored());
            assertTrue(rejected.retrievalRejected().stream().anyMatch(reason -> reason.contains("claim_without_evidence")));
            assertEquals(before, registryBytes());
            assertEquals(List.of(memo.id()), queue.all().stream().map(Memo::id).toList());
            assertEquals(UsageLedger.EvidenceLevel.NONE, ledger.all().getFirst().evidence());
        }
    }

    @Test void lateOriginalTaskJournalCanSupportSameMemoWithoutAnotherIntake() throws Exception {
        Learned learned = learnForBodyTask();
        Work work = selectLearned(learned, "mcp-late-original-evidence");
        work.markAsync(); tasks.finish(TaskState.SUCCESS);
        assertTrue(taskUsage("report_skill", learned, work).get("success").getAsBoolean());
        assertEquals(1, scan());
        Memo pending = queue.all().getFirst();
        assertTrue(pending.evidence().isEmpty());
        var journalBefore = journalLines();
        Files.writeString(dir.resolve(com.dwinovo.numen.plugins.learner.contract.ReviewContractGate.FILE_NAME), "{\"mode\":\"enforce\"}");
        var stable = token(CURRENT);
        var noAction = new Verdict(pending.id(), List.of(Verdict.Action.NO_ACTION), .8,
                "only the original task terminal observation, not world effectiveness", null, "", "", "", List.of(), List.of());
        var first = queue.drain(3);
        var withoutEvidence = new ReviewCompletion(first, sample -> ReviewTargetGate.check(dir, OWNER, sample, stable, stable))
                .consume(dir, queue, first, List.of(noAction), (kind, data) -> {});
        assertEquals(0, withoutEvidence.settlement().committed());
        assertEquals(1, withoutEvidence.settlement().restored());
        try (AsyncJournalFixture journal = new AsyncJournalFixture(dir)) {
            OriginalAsyncJournalProducer.publish(OWNER, work);
            journal.barrier();
            assertEquals(0, scan(), "late evidence cannot manufacture another intake or ENQUEUED identity");
            assertEquals(journalBefore, journalLines());
            assertEquals(List.of(pending.id()), queue.all().stream().map(Memo::id).toList());
            var retry = queue.drain(3);
            var completion = new ReviewCompletion(retry, sample -> ReviewTargetGate.check(dir, OWNER, sample, stable, stable));
            String before = registryBytes();
            var withOriginalEvidence = completion.consume(dir, queue, retry, List.of(noAction), (kind, data) -> {});
            assertEquals(1, withOriginalEvidence.settlement().committed(),
                    "late persisted original task evidence must be rechecked at consumption, not frozen absent at intake");
            assertEquals(before, registryBytes());
            assertEquals(1, ledger.all().size());
            assertEquals(UsageLedger.EvidenceLevel.NONE, ledger.all().getFirst().evidence());
        }
    }

    @Test void originalJournalEventFromAnotherSessionCannotSupplyThisTaskEvidence() throws Exception {
        Learned learned = learnForBodyTask();
        Work selected = selectLearned(learned, "mcp-same-call-foreign-session");
        selected.markAsync(); tasks.finish(TaskState.SUCCESS);
        Work foreign = new Work(selected.getToolCallId());
        foreign.markAsync(); foreign.bindIdentity(token(new LearnerAutoIntake.DeliveryScope("world", "foreign-session", 88)));
        foreign.setState(TaskState.SUCCESS); foreign.setResult(TaskResult.ok("foreign task completed"));
        try (AsyncJournalFixture journal = new AsyncJournalFixture(dir)) {
            OriginalAsyncJournalProducer.publish(OWNER, foreign);
            journal.barrier();
            var event = Files.readAllLines(dir.resolve("monitor/tools.jsonl")).stream()
                    .map(line -> JsonParser.parseString(line).getAsJsonObject())
                    .filter(row -> row.has("type") && "tool_result".equals(row.get("type").getAsString()))
                    .findFirst().orElseThrow().get("event_id").getAsString();
            assertTrue(EvidenceJournal.unresolved(dir.resolve("monitor"), List.of(event)).isEmpty(),
                    "event existence alone does not establish the selected task scope");
            assertTrue(taskUsage("report_skill", learned, selected).get("success").getAsBoolean());
            assertEquals(1, scan());
            Memo memo = queue.all().getFirst();
            assertTrue(memo.evidence().isEmpty());
            assertFalse(TrajectorySlices.forMemo(dir, OWNER, memo).contains("event:" + event),
                    "same call and companion cannot borrow another server-session event");
            assertEquals(UsageLedger.EvidenceLevel.NONE, ledger.all().getFirst().evidence());
        }
    }

    @Test void originalTaskJournalReferenceReachesItsOwnMemoEvidence() throws Exception {
        Learned learned = learnForBodyTask();
        Work work = selectLearned(learned, "mcp-async-evidence-source");
        work.markAsync();
        tasks.finish(TaskState.SUCCESS);
        try (AsyncJournalFixture journal = new AsyncJournalFixture(dir)) {
            OriginalAsyncJournalProducer.publish(OWNER, work);
            journal.barrier();
            var rows = Files.readAllLines(dir.resolve("monitor/tools.jsonl")).stream()
                    .filter(line -> !line.isBlank()).map(line -> JsonParser.parseString(line).getAsJsonObject())
                    .filter(row -> row.has("type") && "tool_result".equals(row.get("type").getAsString()))
                    .filter(row -> row.has("data") && row.getAsJsonObject("data").has("tool_call_id")
                            && work.getToolCallId().equals(row.getAsJsonObject("data").get("tool_call_id").getAsString())).toList();
            assertEquals(1, rows.size(), "the original producer must reach the real journal writer");
            var envelope = rows.getFirst();
            String event = envelope.get("event_id").getAsString();
            assertEquals(work.publicId(), envelope.getAsJsonObject("data").get("task_id").getAsString());
            assertFalse(event.isBlank());
            assertEquals(CURRENT.worldId(), envelope.get("world_id").getAsString());
            assertEquals(CURRENT.serverSession(), envelope.get("server_session").getAsString());
            assertEquals(CURRENT.epoch(), envelope.get("world_epoch").getAsLong());
            assertEquals("server-async", envelope.getAsJsonObject("data").get("journaled_by").getAsString());
            assertEquals("external-done", envelope.getAsJsonObject("data").get("async_status").getAsString());
            assertEquals(OWNER.toString(), envelope.getAsJsonObject("data").get("companion_id").getAsString());
            assertTrue(EvidenceJournal.unresolved(dir.resolve("monitor"), List.of(event)).isEmpty());
            assertTrue(taskUsage("report_skill", learned, work).get("success").getAsBoolean());
            assertEquals(1, scan());
            Memo memo = queue.all().getFirst();
            assertTrue(TrajectorySlices.forMemo(dir, OWNER, memo).contains("event:" + event),
                    "original input already sees the exact task event through its scoped reader");
            assertEquals(List.of(event), memo.evidence(), "exact original task journal reference must reach this memo evidence, not only display text");
            assertEquals(UsageLedger.EvidenceLevel.NONE, ledger.all().getFirst().evidence());
        }
    }

    @Test void taskResultWithoutWorldEvidenceCannotReviseSkillUnderEnforcedContract() throws Exception {
        Learned learned = learnForBodyTask();
        Work work = selectLearned(learned, "async-no-evidence");
        Memo memo = finishAndCollect(learned, work);
        assertTrue(memo.evidence().isEmpty());
        Files.writeString(dir.resolve(com.dwinovo.numen.plugins.learner.contract.ReviewContractGate.FILE_NAME), "{\"mode\":\"enforce\"}");
        String before = registryBytes();
        var batch = queue.drain(3);
        var stable = token(CURRENT);
        var completion = new ReviewCompletion(batch, sample -> ReviewTargetGate.check(dir, OWNER, sample, stable, stable));
        Experience candidate = new Experience("async learned method", "task is active", "old attempt failed",
                "observed task result", "candidate changed interpretation", "avoid the failed attempt", "", "FAILURE", List.of("EXECUTOR"));
        Verdict revision = new Verdict(memo.id(), List.of(Verdict.Action.WRITE_EXPERIENCE), .8,
                "a task terminal state is not world evidence", candidate, "", "", "", "", List.of(), List.of(),
                new RetrievalDecision(List.of(new RetrievalDecision.Hit("skill:" + learned.skill(),
                        "observed task result", "same learned artifact", true, "TRIAL")), RetrievalDecision.Kind.REVISE, "same method"));
        var refused = completion.consume(dir, queue, batch, List.of(revision), (kind, data) -> {});
        assertEquals(0, refused.settlement().committed());
        assertEquals(1, refused.settlement().restored());
        assertTrue(refused.verdicts().isEmpty());
        assertTrue(refused.retrievalRejected().stream().anyMatch(reason -> reason.contains("contract:")));
        assertEquals(before, registryBytes());
        assertEquals(UsageLedger.EvidenceLevel.NONE, ledger.all().getFirst().evidence());
        assertFalse(ledger.all().getFirst().validityClaimed());
        var retry = queue.drain(3);
        var noAction = new Verdict(memo.id(), List.of(Verdict.Action.NO_ACTION), .8,
                "retain uncertainty; no artifact write", null, "", "", "", List.of(), List.of());
        var completed = new ReviewCompletion(retry, sample -> ReviewTargetGate.check(dir, OWNER, sample, stable, stable))
                .consume(dir, queue, retry, List.of(noAction), (kind, data) -> {});
        assertEquals(0, completed.settlement().committed(), "ENFORCE requires evidence for the reasoning claim even with NO_ACTION");
        assertEquals(1, completed.settlement().restored());
        assertTrue(completed.retrievalRejected().stream().anyMatch(reason -> reason.contains("claim_without_evidence")));
        assertEquals(before, registryBytes());
        Files.writeString(dir.resolve(com.dwinovo.numen.plugins.learner.contract.ReviewContractGate.FILE_NAME), "{\"mode\":\"shadow\"}");
        var shadowBatch = queue.drain(3);
        var shadow = new ReviewCompletion(shadowBatch, sample -> ReviewTargetGate.check(dir, OWNER, sample, stable, stable))
                .consume(dir, queue, shadowBatch, List.of(noAction), (kind, data) -> {});
        assertEquals(1, shadow.settlement().committed(), "existing SHADOW policy may settle uncertainty without writing an artifact");
        assertEquals(before, registryBytes());
    }

    @Test void learnedArtifactBodyTaskResultReturnsThroughHostInputAndActualCompletion() throws Exception {
        Learned learned = learnForBodyTask();
        Work work = selectLearned(learned, "async-original-call");
        Memo returned = finishAndCollect(learned, work);
        String registryBefore = registryBytes();
        var done = consumeReturned(returned, false, learned);
        assertEquals(1, done.settlement().committed());
        assertEquals(0, new MemoQueue(queue.file()).size());
        assertEquals(Set.of(returned.id()), done.judged());
        assertEquals(registryBefore, registryBytes());
        assertEquals(UsageLedger.EvidenceLevel.NONE, ledger.all().getFirst().evidence());
        assertEquals(0, scan());
    }
    @Test void learnedAsyncHistorySurvivesWithdrawalButOldVersionCannotCommit() throws Exception {
        Learned learned = learnForBodyTask();
        Work work = selectLearned(learned, "async-withdraw-call");
        Memo returned = finishAndCollect(learned, work);
        var done = consumeReturned(returned, true, learned);
        assertEquals(0, done.settlement().committed());
        assertEquals(1, done.settlement().restored());
        assertTrue(done.verdicts().isEmpty());
        assertEquals(List.of(returned), new MemoQueue(queue.file()).all());
        assertTrue(TrajectorySlices.forMemo(dir, OWNER, returned).contains(learned.body()));
        assertEquals(1, ledger.all().size());
        assertEquals(UsageLedger.EvidenceLevel.NONE, ledger.all().getFirst().evidence());
    }
    @Test void restoredTaskResultCannotCrossServerSessionWhileOriginalScopeRemainsReplayable() throws Exception {
        Learned learned = learnForBodyTask();
        Work original = selectLearned(learned, "async-before-session-change");
        tasks.finish(TaskState.SUCCESS);
        WorldContextStamp.Token originalScope = tasks.stamp;
        tasks.stamp = token(new LearnerAutoIntake.DeliveryScope("world", "replacement-session", 8));
        assertFalse(taskUsage("report_skill", learned, original).get("success").getAsBoolean());
        assertTrue(ledger.all().isEmpty(), "same recovered task object is not permission in a new server scope");
        assertEquals(originalScope, original.skillSelection().token());
        tasks.stamp = originalScope; // controlled restoration of the exact original fixture identity, not a real restart
        assertTrue(taskUsage("report_skill", learned, original).get("success").getAsBoolean());
        assertTrue(taskUsage("report_skill", learned, original).get("success").getAsBoolean());
        assertEquals(1, ledger.all().size(), "original-scope report replay must be idempotent");
        var fact = SkillDeliveryIntake.taskResultOf(ledger.all().getFirst());
        assertNotNull(fact); assertEquals(original.publicId(), fact.taskId());
        assertEquals(CURRENT.serverSession(), fact.scope().serverSession());
        assertEquals(CURRENT.epoch(), fact.scope().epoch());
        assertEquals(1, scan());
        assertEquals(0, scan());
        assertEquals(UsageLedger.EvidenceLevel.NONE, ledger.all().getFirst().evidence());
    }

    @Test void replacementTaskAndCompanionCannotRebindOldLearnedTaskResult() throws Exception {
        Learned learned = learnForBodyTask();
        Work original = selectLearned(learned, "async-before-replacement");
        tasks.finish(TaskState.SUCCESS);
        Work replacement = new Work("async-replacement"); replacement.bindIdentity(tasks.stamp);
        replacement.setState(TaskState.RUNNING); tasks.active = replacement;
        tasks.companion = UUID.fromString("00000000-0000-0000-0000-000000000099");
        assertFalse(taskUsage("report_skill", learned, original).get("success").getAsBoolean());
        assertTrue(ledger.all().isEmpty()); assertNull(replacement.skillSelection());
        tasks.companion = OWNER;
        assertTrue(taskUsage("report_skill", learned, original).get("success").getAsBoolean());
        assertEquals(1, ledger.all().size());
        var fact = SkillDeliveryIntake.taskResultOf(ledger.all().getFirst());
        assertNotNull(fact); assertEquals(original.publicId(), fact.taskId());
        assertNotEquals(replacement.publicId(), fact.taskId()); assertNull(replacement.skillSelection());
        assertEquals(1, scan());
        assertTrue(TrajectorySlices.forMemo(dir, OWNER, queue.all().getFirst()).contains(original.publicId()));
    }

    private void register(String skill) {
        if (registry.get(skill).isPresent()) return;
        String body = "exact original steps " + skill;
        String bodyRef = SkillBodyStore.write(dir, skill, 1, Integer.toHexString(body.hashCode()), body);
        assertNotNull(bodyRef);
        // Original fixture fix: name/body do not enter the fingerprint; unique purpose/trigger do.
        // Keep the original family policy unchanged; these ordinary skills use only the temp registry.
        var draft = new ArtifactDraft(ArtifactKind.EXPERIENCE, skill, "stay safe for " + skill, body,
                List.of("night " + skill), List.of(), List.of(), "LOW", List.of(), List.of("memo-1"), "EXECUTOR");
        assertTrue(fingerprints.add(draft.fingerprint()), "each fixture skill must have a unique fingerprint");
        var decision = registry.register(draft, skill, bodyRef, true);
        assertEquals(SkillRegistry.Outcome.REGISTERED, decision.outcome(), decision.reason());
        assertTrue(decision.stored(), decision.reason());
        assertEquals(skill, decision.entry().skillId(), "must not revise the first fixture skill");
        assertEquals(1, decision.entry().version());
    }
    private Work adopt(String skill, String call) {
        register(skill);
        Work work = new Work(call);
        work.bindIdentity(tasks.stamp); work.setState(TaskState.RUNNING); tasks.active = work;
        invoke("adopt_skill", skill, work);
        assertNotNull(work.skillSelection(), "real successful full-body reply must bind the original task");
        return work;
    }
    private void invoke(String action, String skill, Work work) {
        JsonObject args = new JsonObject();
        args.addProperty("action", action); args.addProperty("task_id", work.publicId());
        args.addProperty("skill_ref", "skill:" + skill); args.addProperty("version", "v1");
        AtomicReference<String> reply = new AtomicReference<>();
        tool.onServerCall("selection-" + work.getToolCallId(), args, null, reply::set);
        assertNotNull(reply.get());
        JsonObject result = JsonParser.parseString(reply.get()).getAsJsonObject();
        assertTrue(result.get("success").getAsBoolean(), result.toString());
    }
    private UsageLedger.Entry report(String skill, Work work, TaskState state) {
        assertSame(work, tasks.active);
        int before = ledger.all().size();
        tasks.finish(state); invoke("report_skill", skill, work);
        var snapshot = ledger.readSnapshot();
        assertEquals(UsageLedger.ReadStatus.READY, snapshot.status());
        assertEquals(before + 1, snapshot.rows().size(), "real producer must append exactly one RESULT");
        var row = snapshot.rows().get(before);
        assertEquals(UsageLedger.Phase.RESULT, row.phase());
        assertNotNull(SkillDeliveryIntake.taskResultOf(row), "real producer identity must parse before testing the host");
        return row;
    }
    private UsageLedger.Entry result(String skill, String call) {
        return report(skill, adopt(skill, call), TaskState.SUCCESS);
    }
    private UsageLedger.Entry delivery(String skill, String request, Work work) {
        register(skill);
        var receipt = SkillDeliveryEvidence.record(new SkillDeliveryEvidence.Context(request, "world", "session", 7,
                OWNER.toString(), "EXECUTOR", "execution", work == null ? "" : work.publicId(),
                work == null ? "" : work.getToolCallId(), List.of(new SkillDeliveryEvidence.Ref("skill:" + skill, "v1"))), ledger);
        assertEquals(1, receipt.written()); assertFalse(receipt.ioFailed());
        return ledger.all().stream().filter(e -> receipt.artifacts().contains(e.artifactId())).findFirst().orElseThrow();
    }
    private static String sr(UsageLedger.Entry row) {
        try {
            return "sr-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(row.artifactId().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private Path journal() { return dir.resolve("learner-enqueued.jsonl"); }
    private List<String> journalLines() throws Exception {
        return Files.isRegularFile(journal()) ? Files.readAllLines(journal()) : List.of();
    }
    private List<String> journalIds() throws Exception {
        return journalLines().stream().map(line -> JsonParser.parseString(line).getAsJsonObject().get("id").getAsString()).toList();
    }
    private String registryBytes() throws Exception { return Files.readString(dir.resolve("skills.jsonl")); }
    private int scan() { return LearnerAutoIntake.scanOnce(() -> CURRENT); }
    private void reload() {
        keys.clear(); cursors.clear();
        queue = new MemoQueue(queue.file()); ledger = new UsageLedger(dir);
        LearnerAutoIntake.install(dir); // Original journal reload, not a hand-restored seen set.
    }
    private void assertPending(UsageLedger.Entry row) {
        Memo memo = queue.all().stream().filter(m -> sr(row).equals(m.id())).findFirst().orElse(null);
        assertNotNull(memo, "real host must enqueue the independent sr identity");
        assertEquals("skill_task_result", memo.stage());
        JsonObject snapshot = JsonParser.parseString(memo.snapshot()).getAsJsonObject();
        assertEquals(JsonParser.parseString(row.detail()), snapshot.get("detail"), "complete original detail object");
        var decoded = SkillDeliveryIntake.taskResultFromSnapshot(memo.snapshot());
        assertNotNull(decoded); assertEquals(row, decoded.entry(), "all original Entry metadata must survive");
        assertFalse(snapshot.has("event_id")); assertFalse(snapshot.getAsJsonObject("detail").has("event_id"));
        assertFalse(row.validityClaimed()); assertEquals(UsageLedger.EvidenceLevel.NONE, row.evidence());
    }

    @Test void realSyncReceiptsForTwoCompanionsShareOneHostReadAndReplayWithoutDuplicates() throws Exception {
        Map<UUID, MemoQueue> queues = syncHostBatch();
        assertEquals(4, queues.values().stream().mapToInt(MemoQueue::size).sum());
    }

    private Map<UUID, MemoQueue> syncHostBatch() throws Exception {
        register("sync-host");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000032");
        var diskReads = new AtomicInteger();
        var constructor = UsageLedger.class.getDeclaredConstructor(Path.class, java.util.function.Consumer.class);
        constructor.setAccessible(true);
        ledger = constructor.newInstance(dir, (java.util.function.Consumer<Path>) path -> diskReads.incrementAndGet());
        var service = new SyncSkillFeedback();
        for (UUID companion : List.of(OWNER, second)) {
            LearnerUsageTool.TaskAccess host = new LearnerUsageTool.TaskAccess() {
                public UUID ownerId() { return OWNER; }
                public UUID companionId() { return companion; }
                public WorldContextStamp.Token identity() { return token(CURRENT); }
                public TaskRecord current() { return null; }
                public TaskRecord finished(String id) { return null; }
            };
            for (int index = 0; index < 2; index++) {
                JsonObject args = new JsonObject();
                args.addProperty("skill_ref", "skill:sync-host"); args.addProperty("version", "v1"); args.addProperty("target_tool", "craft");
                AtomicReference<String> response = new AtomicReference<>();
                service.adopt(args, dir, ledger, host, response::set);
                String id = JsonParser.parseString(response.get()).getAsJsonObject().getAsJsonObject("data").get("selection_id").getAsString();
                String wire = companion + "-sync-" + index;
                assertEquals(com.dwinovo.numen.api.SyncSkillCorrelation.Binding.BOUND,
                        service.bind(id, OWNER, companion, host.identity(), wire, "craft"));
                service.receipt(id, OWNER, companion, host.identity(), wire, "craft", com.dwinovo.numen.api.SyncSkillCorrelation.Receipt.SUCCESS);
                ledger.append("ordinary-" + wire, "ordinary", "ordinary", UsageLedger.Phase.RESULT,
                        UsageLedger.Outcome.UNKNOWN, "{}", "learner_usage", companion.toString());
            }
        }
        Map<UUID, MemoQueue> queues = Map.of(OWNER, queue, second, new MemoQueue(dir.resolve("sync-second.json")));
        LearnerAutoIntake.installQueueResolverForTest(queues::get);
        diskReads.set(0); reads.set(0);
        assertEquals(4, scan(), "real scanOnce must admit both companions' historical receipts");
        assertEquals(1, reads.get(), "one host reader invocation");
        assertEquals(1, diskReads.get(), "one actual complete-ledger read across both owner loops");
        for (var item : queues.entrySet()) {
            assertEquals(2, item.getValue().all().size());
            for (Memo memo : item.getValue().all()) {
                assertEquals("skill_sync_receipt", memo.stage());
                var row = SkillDeliveryIntake.syncEntryFromSnapshot(memo.snapshot());
                assertEquals(item.getKey().toString(), row.companionId());
                assertEquals(UsageLedger.EvidenceLevel.NONE, row.evidence());
            }
        }
        diskReads.set(0); reads.set(0);
        assertEquals(0, scan(), "same production journal must suppress replay");
        assertEquals(1, reads.get()); assertEquals(1, diskReads.get());
        assertEquals(4, queues.values().stream().mapToInt(MemoQueue::size).sum());
        return queues;
    }

    @Test void realSyncHostIntakeContinuesThroughActualCompletionForBothCompanions() throws Exception {
        Map<UUID, MemoQueue> queues = syncHostBatch();
        String registryBefore = registryBytes();
        for (var item : queues.entrySet()) {
            MemoQueue durableQueue = new MemoQueue(item.getValue().file());
            List<Memo> batch = durableQueue.drain(3);
            assertEquals(2, batch.size());
            var stable = token(CURRENT);
            var completion = new ReviewCompletion(batch, memo -> ReviewTargetGate.check(dir, item.getKey(), memo, stable, stable));
            List<Verdict> verdicts = batch.stream().map(memo -> new Verdict(memo.id(),
                    List.of(Verdict.Action.NO_ACTION), .8, "receipt only, no world effectiveness claim",
                    null, "", "", "", List.<Map<String, Object>>of(), List.<String>of())).toList();
            var consumed = completion.consume(dir, durableQueue, batch, verdicts, (kind, data) -> {});
            assertEquals(2, consumed.settlement().committed());
            assertEquals(0, consumed.settlement().restored());
            assertEquals(0, consumed.settlement().terminalSkipped());
            assertEquals(batch.stream().map(Memo::id).collect(java.util.stream.Collectors.toSet()), consumed.judged());
            assertEquals(2, consumed.verdicts().size());
            assertEquals(0, new MemoQueue(durableQueue.file()).size());
        }
        assertEquals(registryBefore, registryBytes(), "NO_ACTION must not register or revise a skill");
        for (UsageLedger.Entry row : ledger.all()) assertEquals(UsageLedger.EvidenceLevel.NONE, row.evidence());
        assertEquals(0, scan(), "completed historical receipts must not be re-enqueued by the host journal");
        for (MemoQueue q : queues.values()) assertEquals(0, new MemoQueue(q.file()).size());
    }

    @Test void realResultEntersHostWithFullSnapshotDespiteOldSdAndReplaysAfterReload() throws Exception {
        Work work = adopt("sk-host-original", "original-action");
        var presented = delivery("sk-host-original", "original-delivery", work);
        String sd = SkillDeliveryIntake.memoIdOf(presented);
        assertEquals(1, scan(), "legacy delivery is the real positive control");
        assertEquals(List.of(sd), journalIds()); assertTrue(keys.contains(sd));
        assertEquals(1, queue.commitDrain(queue.drain(1))); // Removal fixture, not a claimed review.
        var row = report("sk-host-original", work, TaskState.CANCELLED);
        invoke("report_skill", "sk-host-original", work);
        assertEquals(2, ledger.readSnapshot().rows().size(), "real producer replay is idempotent");
        String original = Files.readString(ledger.file()), skills = registryBytes();
        int added = scan(), repeated = scan();
        List<String> persisted = journalLines();
        reload(); int replay = scan();
        assertAll(
                () -> assertNotEquals(sd, sr(row)),
                () -> assertEquals(1, added, "old sd journal is not an equivalent RESULT review"),
                () -> assertEquals(0, repeated),
                () -> assertEquals(List.of(sr(row)), queue.all().stream().map(Memo::id).toList()),
                () -> assertPending(row),
                () -> assertEquals(List.of(sd, sr(row)), journalIds()),
                () -> assertTrue(keys.containsAll(List.of(sd, sr(row)))),
                () -> assertEquals(0, replay),
                () -> assertEquals(persisted, journalLines()),
                () -> assertEquals(original, Files.readString(ledger.file())),
                () -> assertEquals(skills, registryBytes()),
                () -> assertEquals(4, reads.get()));
    }

    @Test void mixedRealResultsAndDeliveriesShareHostPhysicalBudgetDuringJournalFailure() throws Exception {
        List<UsageLedger.Entry> candidates = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            candidates.add(result("sk-host-result-" + i, "result-action-" + i));
            candidates.add(delivery("sk-host-delivery-" + i, "delivery-request-" + i, null));
        }
        candidates.add(delivery("sk-host-spare", "spare-request", null));
        var ready = ledger.readSnapshot();
        assertEquals(UsageLedger.ReadStatus.READY, ready.status());
        assertEquals(17, ready.rows().size(), "all seventeen rows must come from the original producers before scan");
        assertEquals(candidates, ready.rows()); assertEquals(17, fingerprints.size());
        List<String> expected = candidates.stream().map(e -> e.phase() == UsageLedger.Phase.RESULT
                ? sr(e) : SkillDeliveryIntake.memoIdOf(e)).toList();
        String original = Files.readString(ledger.file()), skills = registryBytes();
        Path blocker = Files.createDirectory(journal()); // Original host fault mode: real IO failure.
        int failed = scan();
        List<Memo> physical = List.copyOf(new MemoQueue(queue.file()).all());
        Set<String> failedKeys = Set.copyOf(keys);
        assertEquals(journal(), blocker); assertTrue(Files.isDirectory(blocker));
        Files.delete(blocker); // Only this exact, known empty @TempDir directory; never recursive.
        int repaired = scan();
        List<String> repairedJournal = journalLines();
        reload(); int replay = scan();
        assertAll(
                () -> assertEquals(0, failed, "failed journal cannot count as durable success"),
                () -> assertEquals(16, physical.size(), "one physical budget, even when every journal write fails"),
                () -> assertEquals(8, physical.stream().filter(m -> m.stage().equals("skill_task_result")).count()),
                () -> assertEquals(8, physical.stream().filter(m -> m.stage().equals("skill_delivery")).count()),
                () -> assertEquals(expected.subList(0, 16), physical.stream().map(Memo::id).toList()),
                () -> assertTrue(failedKeys.isEmpty(), "neither candidate type may claim a failed journal write"),
                () -> assertEquals(1, repaired, "repair sixteen existing memos, then add only the spare"),
                () -> assertEquals(expected, queue.all().stream().map(Memo::id).toList()),
                () -> assertEquals(expected, journalIds()),
                () -> assertTrue(keys.containsAll(expected)),
                () -> assertEquals(0, replay),
                () -> assertEquals(repairedJournal, journalLines()),
                () -> assertEquals(original, Files.readString(ledger.file())),
                () -> assertEquals(skills, registryBytes()),
                () -> assertEquals(3, reads.get()));
    }

    @Test void readyBadIdentityAndForeignResultStayUnconsumedWhileRealControlEntersHost() throws Exception {
        var damaged = result("sk-host-damaged", "damaged-action");
        var good = result("sk-host-control", "control-action");
        tasks.stamp = token(new LearnerAutoIntake.DeliveryScope("other-world", "session", 7));
        var foreign = result("sk-host-foreign", "foreign-action"); // A real producer fact, not edited into a legal scope.
        tasks.stamp = token(CURRENT);
        List<String> lines = Files.readAllLines(ledger.file());
        JsonObject raw = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        JsonObject detail = JsonParser.parseString(raw.get("detail").getAsString()).getAsJsonObject();
        detail.getAsJsonArray("task_identity").set(5, new JsonPrimitive("wrong-original-call"));
        raw.addProperty("detail", detail.toString()); lines.set(0, raw.toString());
        Files.writeString(ledger.file(), String.join("\n", lines) + "\n"); // Damage only the real first row.
        var ready = ledger.readSnapshot();
        assertEquals(UsageLedger.ReadStatus.READY, ready.status()); assertEquals(3, ready.rows().size());
        assertNull(SkillDeliveryIntake.taskResultOf(ready.rows().get(0)));
        assertNotNull(SkillDeliveryIntake.taskResultOf(good)); assertNotNull(SkillDeliveryIntake.taskResultOf(foreign));
        String original = Files.readString(ledger.file()), skills = registryBytes();
        assertEquals(0, LearnerAutoIntake.scanOnce(() -> new LearnerAutoIntake.DeliveryScope("", "session", 7)));
        assertTrue(queue.all().isEmpty()); assertTrue(keys.isEmpty()); assertTrue(journalLines().isEmpty());
        int added = scan();
        assertAll(
                () -> assertEquals(1, added, "reject bad/foreign identity but retain a real valid positive control"),
                () -> assertEquals(List.of(sr(good)), queue.all().stream().map(Memo::id).toList()),
                () -> assertPending(good),
                () -> assertEquals(List.of(sr(good)), journalIds()),
                () -> assertFalse(keys.contains(sr(damaged))),
                () -> assertFalse(keys.contains(sr(foreign))),
                () -> assertEquals(original, Files.readString(ledger.file())),
                () -> assertEquals(skills, registryBytes()),
                () -> assertEquals(2, reads.get()));
    }

    private boolean admissionStillOpen(UsageLedger.Entry row) {
        AtomicBoolean called = new AtomicBoolean();
        StormGuard.admitIfEnqueued(OWNER.toString(), "skill_task_result:" + row.artifactId(),
                System.currentTimeMillis(), () -> { called.set(true); return false; });
        return called.get(); // Read the actual admission path without committing a test cooldown.
    }

    @Test void resultJournalFailureRepairsThroughHostWithoutNewMemoOrCooldownAndReloads() throws Exception {
        var row = result("sk-host-repair", "repair-action");
        assertEquals(1, ledger.readSnapshot().rows().size(), "standalone real RESULT needs no PRESENTED");
        String original = Files.readString(ledger.file()), skills = registryBytes();
        Path blocker = Files.createDirectory(journal());
        int first = scan(), retry = scan();
        List<Memo> physical = List.copyOf(new MemoQueue(queue.file()).all());
        boolean observedAdmission = map(StormGuard.class, "STATES").containsKey(OWNER + "|skill_task_result:" + row.artifactId());
        boolean openAfterFailure = admissionStillOpen(row);
        Set<String> failedKeys = Set.copyOf(keys);
        assertEquals(journal(), blocker); assertTrue(Files.isDirectory(blocker));
        Files.delete(blocker); // Exact empty fault directory created above, not live data.
        int repaired = scan();
        List<String> repairedIds = journalIds(), repairedJournal = journalLines();
        boolean openAfterRepair = admissionStillOpen(row);
        queue.commitDrain(queue.drain(1)); // Distinguish journal reload from a still-present queue memo.
        reload(); Set<String> loadedKeys = Set.copyOf(keys); int replay = scan();
        assertAll(
                () -> assertEquals(0, first), () -> assertEquals(0, retry),
                () -> assertEquals(List.of(sr(row)), physical.stream().map(Memo::id).toList(), "one actual disk memo"),
                () -> assertTrue(observedAdmission, "the real host must have used real result admission, not a vacuous probe"),
                () -> assertFalse(failedKeys.contains(sr(row))),
                () -> assertTrue(openAfterFailure, "journal failure cannot consume cooldown"),
                () -> assertEquals(0, repaired, "only repair journal; no second physical memo or success count"),
                () -> assertEquals(List.of(sr(row)), repairedIds),
                () -> assertTrue(openAfterRepair, "journal-only repair cannot consume cooldown"),
                () -> assertTrue(loadedKeys.contains(sr(row)), "reload the original host journal"),
                () -> assertEquals(0, replay), () -> assertEquals(0, queue.size()),
                () -> assertEquals(repairedJournal, journalLines()),
                () -> assertEquals(original, Files.readString(ledger.file())),
                () -> assertEquals(skills, registryBytes()),
                () -> assertEquals(4, reads.get()));
    }
}
