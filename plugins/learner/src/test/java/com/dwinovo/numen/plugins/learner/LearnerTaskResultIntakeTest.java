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
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-13 C1 RESULT red batch: real adopt/report, core consumer, disk queue and journal.
 * Why not synthesize RESULT JSON: that would bypass the original body/task binding producer.
 * Only task lookup is injected; core intake consumes complete original ledger snapshots.
 * The mechanical runWithTaskResults stub intentionally retains delivery-only behavior.
 * No production scan/review/registry wiring, evidence promotion, or epoch commit gate is changed.
 */
class LearnerTaskResultIntakeTest {
    @TempDir Path root;
    private static final Gson GSON = new Gson();
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final SkillDeliveryIntake.Scope CURRENT =
            new SkillDeliveryIntake.Scope("world", "session", 7, OWNER.toString());
    private SkillDeliveryIntake.EnqueuedJournal intakeJournal;
    private Path dir;
    private UsageLedger ledger;
    private MemoQueue queue;
    private SkillRegistry registry;
    private LearnerUsageTool tool;
    private Tasks tasks;

    private static final class Work extends TaskRecord {
        Work(String call) { super("goto", call, 1000); }
    }
    private static final class Tasks implements LearnerUsageTool.TaskAccess {
        final WorldContextStamp.Token stamp =
                new WorldContextStamp.Token(Map.of("world_id", "world", "server_session", "session"), 7);
        TaskRecord active;
        final Map<String, TaskRecord> done = new HashMap<>();
        public UUID companionId() { return OWNER; }
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

    @AfterEach void reset() {
        StormGuard.resetForTest();
        SkillRegistrar.resetForTest();
    }
    private void configure(String name) throws Exception {
        dir = Files.createDirectories(root.resolve(name));
        StormGuard.resetForTest(); SkillRegistrar.resetForTest();
        ledger = new UsageLedger(dir);
        queue = new MemoQueue(dir.resolve("memos.json"));
        registry = SkillRegistrar.registryFor(dir);
        tasks = new Tasks();
        tool = new LearnerUsageTool(dir, ledger, tasks);
        intakeJournal = diskJournal(journal());
    }
    private Path journal() { return dir.resolve("core-enqueued.txt"); }
    private SkillDeliveryIntake.Scan run(int budget) {
        var snapshot = ledger.readSnapshot();
        assertEquals(UsageLedger.ReadStatus.READY, snapshot.status());
        return SkillDeliveryIntake.runWithTaskResults(snapshot.rows(), CURRENT, queue, intakeJournal,
                StormGuard::admitIfEnqueued, 1000L, budget);
    }
    private int enqueueCount() { return run(16).enqueued(); }
    private Work adopt(String skill, String call) {
        if (registry.get(skill).isEmpty()) {
            String body = "exact original steps " + skill;
            String bodyRef = SkillBodyStore.write(dir, skill, 1, Integer.toHexString(body.hashCode()), body);
            assertNotNull(bodyRef);
            // 2026-11-13: ArtifactDraft fingerprint is kind|purpose|triggers|excludes|audience (no name/body),
            // so a shared "stay safe"/"night" draft merges into the first skill id and the second id is absent.
            // Keep every fixture skill's purpose/trigger unique, and assert the registry stored this exact id at v1.
            var decision = registry.register(new ArtifactDraft(ArtifactKind.EXPERIENCE, skill,
                    "stay safe for " + skill, body, List.of("night " + skill), List.of(), List.of(), "LOW",
                    List.of(), List.of("memo-1"), "EXECUTOR"), skill, bodyRef, true);
            assertTrue(decision.stored(), "fixture registration must store its own skill: " + decision.reason());
            assertEquals(skill, decision.entry().skillId(), "fixture registration must not revise another id");
            assertEquals(1, decision.entry().version(), "fresh fixture skill must be v1");
        }
        Work work = new Work(call);
        work.bindIdentity(tasks.stamp);
        work.setState(TaskState.RUNNING);
        tasks.active = work;
        invoke("adopt_skill", skill, work);
        assertNotNull(work.skillSelection(), "real successful full-body reply must bind original task");
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
        tasks.finish(state);
        invoke("report_skill", skill, work);
        return ledger.all().get(ledger.all().size() - 1);
    }
    private UsageLedger.Entry result(String skill, String call, TaskState state) {
        return report(skill, adopt(skill, call), state);
    }
    private UsageLedger.Entry delivery(String skill, String request, Work work) {
        var receipt = SkillDeliveryEvidence.record(new SkillDeliveryEvidence.Context(request, "world", "session",
                7, OWNER.toString(), "EXECUTOR", "execution", work == null ? "" : work.publicId(),
                work == null ? "" : work.getToolCallId(),
                List.of(new SkillDeliveryEvidence.Ref("skill:" + skill, "v1"))), ledger);
        assertEquals(1, receipt.written()); assertFalse(receipt.ioFailed());
        return ledger.all().stream().filter(e -> receipt.artifacts().contains(e.artifactId())).findFirst().orElseThrow();
    }
    private static String resultId(UsageLedger.Entry row) throws Exception {
        return "sr-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(row.artifactId().getBytes(StandardCharsets.UTF_8)));
    }
    private void assertPending(UsageLedger.Entry row) throws Exception {
        Memo memo = queue.all().stream().filter(m -> m.id().equals(resultIdUnchecked(row))).findFirst().orElseThrow();
        assertEquals("skill_task_result", memo.stage());
        JsonObject snapshot = JsonParser.parseString(memo.snapshot()).getAsJsonObject();
        assertEquals(JsonParser.parseString(row.detail()).getAsJsonObject(), snapshot.getAsJsonObject("detail"),
                "complete original detail object; never substitute current registry content");
        assertEquals(row.artifactId(), snapshot.get("artifact_id").getAsString());
        assertEquals(row.source(), snapshot.get("source").getAsString());
        assertEquals(row.at(), snapshot.get("at").getAsString());
        assertEquals(row.kind(), snapshot.get("kind").getAsString());
        assertEquals(row.name(), snapshot.get("name").getAsString());
        assertEquals(row.companionId(), snapshot.get("companion_id").getAsString());
        assertEquals("RESULT", snapshot.get("phase").getAsString());
        assertEquals(row.outcome().name(), snapshot.get("outcome").getAsString());
        assertEquals(row.evidence().name(), snapshot.get("evidence").getAsString());
        assertEquals(row.validityClaimed(), snapshot.get("validity_claimed").getAsBoolean());
        assertFalse(snapshot.has("event_id"), "taskCall/factKey/artifactId must not masquerade as an event");
        assertFalse(snapshot.getAsJsonObject("detail").has("event_id"));
        assertFalse(row.validityClaimed()); assertEquals(UsageLedger.EvidenceLevel.NONE, row.evidence());
        var original = SkillDeliveryIntake.taskResultOf(row);
        var decoded = SkillDeliveryIntake.taskResultFromSnapshot(memo.snapshot());
        assertNotNull(original, "shared parser must accept the real original fact");
        assertNotNull(decoded, "snapshot must round-trip through the same shared identity parser");
        assertEquals(row, decoded.entry(), "retain every original Entry field");
        assertEquals(original, decoded);
    }
    private static String resultIdUnchecked(UsageLedger.Entry row) {
        try { return resultId(row); } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private void reload() throws Exception {
        queue = new MemoQueue(queue.file());
        intakeJournal = diskJournal(journal());
    }

    @Test void realTerminalResultsWithoutPresentedBecomeIndependentPendingMemos() {
        assertAll(Arrays.stream(new TaskState[]{TaskState.SUCCESS, TaskState.FAILED, TaskState.TIMEOUT, TaskState.CANCELLED})
                .map(state -> (Executable) () -> {
                    configure("terminal-" + state);
                    var row = result("sk-one", "action-" + state, state);
                    assertEquals(1, ledger.all().size(), "no PRESENTED is required by real adopt/report");
                    String original = Files.readString(ledger.file());
                    String skillBefore = GSON.toJson(registry.get("sk-one").orElseThrow());
                    assertEquals(1, enqueueCount(), state + " is one pending memo, not proof of effectiveness");
                    assertPending(row);
                    assertEquals(original, Files.readString(ledger.file()));
                    assertEquals(skillBefore, GSON.toJson(registry.get("sk-one").orElseThrow()));
                    reload();
                    assertEquals(0, enqueueCount());
                    assertEquals(1, queue.size());
                }));
    }

    @Test void deliveryJournalAndRemovedDeliveryCannotSwallowIndependentResultsOrLaterTask() throws Exception {
        configure("delivery-then-result");
        Work first = adopt("sk-one", "first-action");
        var delivered = delivery("sk-one", "first-delivery", first);
        assertEquals(1, enqueueCount());
        String sd = SkillDeliveryIntake.memoIdOf(delivered);
        assertTrue(intakeJournal.contains(sd));
        queue.commitDrain(queue.drain(1)); // Queue removal fixture, not a claimed learner review.
        var cancelled = report("sk-one", first, TaskState.CANCELLED);
        String sr = resultId(cancelled);
        assertNotEquals(sd, sr);
        assertEquals(1, enqueueCount(), "sd journal must not consume the independent result identity");
        assertPending(cancelled);
        invoke("report_skill", "sk-one", first);
        assertEquals(2, ledger.all().size(), "original producer replay is idempotent");
        assertEquals(0, enqueueCount());
        var success = result("sk-one", "second-action", TaskState.SUCCESS);
        assertNotEquals(sr, resultId(success));
        StormGuard.resetForTest(); // Isolate durable task identity from the existing version cooldown.
        assertEquals(1, enqueueCount());
        assertPending(success);
        List<String> persisted = Files.readAllLines(journal());
        assertEquals(3, persisted.size(), "one sd plus two original task results");
        reload();
        assertEquals(0, enqueueCount());
        assertEquals(2, queue.size());
        assertEquals(persisted, Files.readAllLines(journal()));
    }

    @Test void damagedOriginalIdentityIsRejectedAlongsideARealValidControl() {
        List<String> fields = List.of("world_id", "server_session", "epoch", "companion_id", "task_id",
                "task_tool_call_id", "skill_ref", "version", "task_identity", "source", "outcome", "artifact_id");
        assertAll(fields.stream().map(name -> (Executable) () -> {
            configure("damaged-" + name);
            var damaged = result("sk-damaged", "damaged-call", TaskState.SUCCESS);
            var good = result("sk-control", "control-call", TaskState.SUCCESS);
            List<String> lines = Files.readAllLines(ledger.file());
            JsonObject raw = JsonParser.parseString(lines.get(0)).getAsJsonObject();
            JsonObject detail = JsonParser.parseString(raw.get("detail").getAsString()).getAsJsonObject();
            switch (name) {
                case "source" -> raw.addProperty("source", "manual");
                case "outcome" -> raw.addProperty("outcome", "FAIL");
                case "artifact_id" -> raw.addProperty("artifact_id", damaged.artifactId() + "-wrong");
                case "epoch" -> detail.addProperty(name, 8);
                case "skill_ref" -> detail.addProperty(name, "skill:sk-other");
                case "version" -> detail.addProperty(name, "v2");
                case "task_identity" -> detail.getAsJsonArray(name).set(5, new com.google.gson.JsonPrimitive("wrong-call"));
                default -> detail.addProperty(name, name.equals("companion_id")
                        ? "00000000-0000-0000-0000-000000000002" : "wrong-" + name);
            }
            raw.addProperty("detail", detail.toString());
            Files.writeString(ledger.file(), raw + "\n" + lines.get(1) + "\n");
            assertEquals(UsageLedger.ReadStatus.READY, ledger.readSnapshot().status());
            String before = Files.readString(ledger.file());
            assertEquals(1, enqueueCount(), "reject damaged " + name + " while admitting the real valid control");
            assertEquals(List.of(resultId(good)), queue.all().stream().map(Memo::id).toList());
            assertPending(good);
            assertEquals(before, Files.readString(ledger.file()));
        }));
    }

    @Test void mixedDeliveryAndResultCandidatesShareOriginalPhysicalBudgetDuringJournalFailure() throws Exception {
        configure("shared-sixteen");
        List<UsageLedger.Entry> candidates = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            candidates.add(result("sk-result-" + i, "result-" + i, TaskState.SUCCESS));
            candidates.add(delivery("sk-delivery-" + i, "delivery-" + i, null));
        }
        candidates.add(delivery("sk-spare", "delivery-spare", null));
        assertEquals(17, ledger.all().size(), "all candidates come from the real producers");
        String original = Files.readString(ledger.file());
        List<String> expected = new ArrayList<>();
        for (var row : candidates) expected.add(row.phase() == UsageLedger.Phase.RESULT
                ? resultId(row) : SkillDeliveryIntake.memoIdOf(row));
        Path blocker = Files.createDirectory(journal());
        var failed = run(16);
        List<Memo> physical = List.copyOf(new MemoQueue(queue.file()).all());
        List<String> failedCommitted = expected.stream().filter(intakeJournal::contains).toList();
        assertTrue(Files.isDirectory(blocker));
        Files.delete(blocker); // Exact empty fault directory under this test's @TempDir.
        var repaired = run(16);
        List<String> repairedJournal = Files.isRegularFile(journal()) ? Files.readAllLines(journal()) : List.of();
        reload();
        var replay = run(16);
        assertAll(
                () -> assertEquals(0, failed.enqueued(), "failed journal never counts as durable success"),
                () -> assertEquals(16, failed.physicalAdded()),
                () -> assertEquals(16, failed.failed()),
                () -> assertEquals(16, physical.size(), "one shared budget across both candidate types"),
                () -> assertEquals(8, physical.stream().filter(m -> m.stage().equals("skill_task_result")).count()),
                () -> assertEquals(8, physical.stream().filter(m -> m.stage().equals("skill_delivery")).count()),
                () -> assertEquals(expected.subList(0, 16), physical.stream().map(Memo::id).toList(),
                        "preserve the original mixed ledger order"),
                () -> assertTrue(failedCommitted.isEmpty()),
                () -> assertEquals(16, repaired.duplicate(), "repair existing journal entries without new memos"),
                () -> assertEquals(1, repaired.physicalAdded()),
                () -> assertEquals(1, repaired.enqueued()),
                () -> assertEquals(expected, new MemoQueue(queue.file()).all().stream().map(Memo::id).toList()),
                () -> assertEquals(expected, repairedJournal),
                () -> assertEquals(0, replay.physicalAdded()),
                () -> assertEquals(0, replay.enqueued()),
                () -> assertEquals(17, replay.duplicate()),
                () -> assertEquals(repairedJournal, Files.readAllLines(journal())),
                () -> assertEquals(original, Files.readString(ledger.file())));
    }

    private static SkillDeliveryIntake.EnqueuedJournal diskJournal(Path path) throws Exception {
        Set<String> committed = Files.isRegularFile(path)
                ? new LinkedHashSet<>(Files.readAllLines(path)) : new LinkedHashSet<>();
        return new SkillDeliveryIntake.EnqueuedJournal() {
            public boolean contains(String id) { return committed.contains(id); }
            public void remember(String id) {
                try {
                    Files.writeString(path, id + "\n", java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);
                } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                committed.add(id);
            }
        };
    }

    @Test void resultJournalFailureRepairsSameObjectReloadsAndNeverConsumesCooldown() throws Exception {
        configure("result-repair");
        var row = result("sk-retry", "retry-action", TaskState.SUCCESS);
        String id = resultId(row);
        Path blocker = Files.createDirectory(dir.resolve("result-journal"));
        var journal = diskJournal(blocker);
        Set<String> fingerprints = new LinkedHashSet<>();
        SkillDeliveryIntake.Admission admission = (c, fp, now, once) -> {
            fingerprints.add(fp);
            return StormGuard.admitIfEnqueued(c, fp, now, once);
        };
        var scope = new SkillDeliveryIntake.Scope("world", "session", 7, OWNER.toString());
        var first = SkillDeliveryIntake.runWithTaskResults(ledger.all(), scope, queue, journal, admission, 1000L, 1);
        var retry = SkillDeliveryIntake.runWithTaskResults(ledger.all(), scope, queue, journal, admission, 1000L, 1);
        assertTrue(Files.isDirectory(blocker));
        Files.delete(blocker); // Exact empty fault directory created above, never a recursive delete.
        var repair = SkillDeliveryIntake.runWithTaskResults(ledger.all(), scope, queue, journal, admission, 1000L, 1);
        var replay = SkillDeliveryIntake.runWithTaskResults(ledger.all(), scope, new MemoQueue(queue.file()),
                diskJournal(blocker), admission, 1000L, 1);
        assertAll(
                () -> assertEquals(1, new MemoQueue(queue.file()).size()),
                () -> assertEquals(1, first.physicalAdded()),
                () -> assertEquals(0, first.enqueued()),
                () -> assertEquals(1, first.failed()),
                () -> assertEquals(0, retry.physicalAdded()),
                () -> assertEquals(1, retry.failed()),
                () -> assertEquals(0, repair.enqueued()),
                () -> assertEquals(0, repair.physicalAdded()),
                () -> assertEquals(1, repair.duplicate()),
                () -> assertTrue(journal.contains(id)),
                () -> assertEquals(List.of(id), Files.isRegularFile(blocker) ? Files.readAllLines(blocker) : List.of()),
                () -> assertEquals(0, replay.physicalAdded()),
                () -> assertEquals(1, replay.duplicate()),
                () -> {
                    assertEquals(1, fingerprints.size(), "real candidate fingerprint must be observed");
                    for (String fp : fingerprints) assertTrue(StormGuard.admitIfEnqueued(OWNER.toString(), fp,
                            1000L, () -> true), "failed append/journal repair cannot consume cooldown");
                });
    }

    @Test void snapshotAdapterRejectsIncompleteOrCoercedEntryMetadata() throws Exception {
        configure("snapshot-strict");
        var row = result("sk-snapshot", "snapshot-call", TaskState.SUCCESS);
        var scan = SkillDeliveryIntake.runWithTaskResults(ledger.all(), CURRENT, queue, intakeJournal,
                StormGuard::admitIfEnqueued, 1000L, 16);
        assertEquals(1, scan.enqueued());
        Memo memo = queue.all().stream().filter(m -> m.id().equals(resultIdUnchecked(row))).findFirst().orElseThrow();
        assertNotNull(SkillDeliveryIntake.taskResultFromSnapshot(memo.snapshot()),
                "control snapshot must be a complete typed Entry");
        JsonObject good = JsonParser.parseString(memo.snapshot()).getAsJsonObject();
        List<String> required = List.of("artifact_id", "kind", "name", "phase", "outcome", "source", "at",
                "companion_id", "evidence");
        assertAll(required.stream().map(name -> (Executable) () -> {
            JsonObject missing = good.deepCopy();
            missing.remove(name);
            assertNull(SkillDeliveryIntake.taskResultFromSnapshot(missing.toString()), "missing " + name);
        }));
        assertAll(required.stream().map(name -> (Executable) () -> {
            JsonObject numeric = good.deepCopy();
            numeric.addProperty(name, 7);
            assertNull(SkillDeliveryIntake.taskResultFromSnapshot(numeric.toString()), "numeric " + name);
        }));
        JsonObject noClaim = good.deepCopy();
        noClaim.remove("validity_claimed");
        JsonObject numberClaim = good.deepCopy();
        numberClaim.addProperty("validity_claimed", 1);
        JsonObject stringDetail = good.deepCopy();
        stringDetail.addProperty("detail", "{}");
        JsonObject blankAt = good.deepCopy();
        blankAt.addProperty("at", " ");
        assertAll(
                () -> assertNull(SkillDeliveryIntake.taskResultFromSnapshot(noClaim.toString()), "missing validity_claimed"),
                () -> assertNull(SkillDeliveryIntake.taskResultFromSnapshot(numberClaim.toString()), "numeric validity_claimed"),
                () -> assertNull(SkillDeliveryIntake.taskResultFromSnapshot(stringDetail.toString()), "detail must be object"),
                () -> assertNull(SkillDeliveryIntake.taskResultFromSnapshot(blankAt.toString()), "blank at"),
                () -> assertNull(SkillDeliveryIntake.taskResultFromSnapshot("")),
                () -> assertNull(SkillDeliveryIntake.taskResultFromSnapshot("{}")),
                () -> assertNull(SkillDeliveryIntake.taskResultFromSnapshot("null")),
                () -> assertNull(SkillDeliveryIntake.taskResultOf(null)));
    }

    /** Full original row is the positive control; each missing metadata field must refuse the typed fact. */
    @Test void typedEntryWithBlankMetadataIsNotAResultFact() throws Exception {
        configure("typed-complete");
        var row = result("sk-typed", "typed-call", TaskState.SUCCESS);
        assertNotNull(SkillDeliveryIntake.taskResultOf(row), "complete real row must stay a typed fact");
        assertAll(
                () -> assertNull(SkillDeliveryIntake.taskResultOf(rebuild(row, "", row.kind(), row.name(),
                        row.source(), row.at(), row.companionId(), row.evidence())), "blank artifact_id"),
                () -> assertNull(SkillDeliveryIntake.taskResultOf(rebuild(row, row.artifactId(), "", row.name(),
                        row.source(), row.at(), row.companionId(), row.evidence())), "blank kind"),
                () -> assertNull(SkillDeliveryIntake.taskResultOf(rebuild(row, row.artifactId(), row.kind(), "",
                        row.source(), row.at(), row.companionId(), row.evidence())), "blank name"),
                () -> assertNull(SkillDeliveryIntake.taskResultOf(rebuild(row, row.artifactId(), row.kind(), row.name(),
                        "", row.at(), row.companionId(), row.evidence())), "blank source"),
                () -> assertNull(SkillDeliveryIntake.taskResultOf(rebuild(row, row.artifactId(), row.kind(), row.name(),
                        row.source(), "", row.companionId(), row.evidence())), "blank at"),
                () -> assertNull(SkillDeliveryIntake.taskResultOf(rebuild(row, row.artifactId(), row.kind(), row.name(),
                        row.source(), row.at(), "", row.evidence())), "blank companion_id"),
                () -> assertNull(SkillDeliveryIntake.taskResultOf(rebuild(row, row.artifactId(), row.kind(), row.name(),
                        row.source(), row.at(), row.companionId(), null)), "null evidence"));
    }


    @Test void exactSelectedBodyAndScopeMatchedOriginalCallReachActualReviewPrompt() throws Exception {
        configure("review-input");
        var row = result("sk-input", "original-input-call", TaskState.SUCCESS);
        assertEquals(1, enqueueCount());
        Memo memo = queue.all().get(0);
        var fact = SkillDeliveryIntake.taskResultFromSnapshot(memo.snapshot());
        assertNotNull(fact);
        String body = SkillBodyStore.read(dir, fact.bodyRef());
        Files.createDirectories(dir.resolve("monitor"));
        Files.writeString(dir.resolve("monitor/tools.jsonl"),
                linkedTool("event-exact", "world", "session", 7, OWNER.toString(), "original-input-call", "original task terminal") + "\n"
                + linkedTool("event-neighbor", "world", "session", 7, OWNER.toString(), "other-call", "DO_NOT_INCLUDE_NEIGHBOR") + "\n"
                + linkedTool("event-other-world", "other-world", "session", 7, OWNER.toString(), "original-input-call", "DO_NOT_INCLUDE_OTHER_WORLD") + "\n"
                + linkedTool("event-other-owner", "world", "session", 7, "another-owner", "original-input-call", "DO_NOT_INCLUDE_OTHER_OWNER") + "\n");
        String ledgerBefore = Files.readString(ledger.file());
        String registryBefore = Files.readString(dir.resolve("skills.jsonl"));
        String slice = TrajectorySlices.forMemo(dir, OWNER, memo);
        String prompt = reviewPrompt(memo, slice);
        assertAll(
                () -> assertTrue(prompt.contains(body), "same original selected body must reach the real prompt builder"),
                () -> assertTrue(slice.contains("body_status=COMPLETE")),
                () -> assertTrue(slice.contains("event-exact")),
                () -> assertFalse(prompt.contains("DO_NOT_INCLUDE_"), "never use time neighbors or another scope/owner"),
                () -> assertTrue(slice.length() <= 2400),
                () -> assertEquals(ledgerBefore, Files.readString(ledger.file())),
                () -> assertEquals(registryBefore, Files.readString(dir.resolve("skills.jsonl"))),
                () -> assertEquals(memo, new MemoQueue(queue.file()).all().get(0)));
    }

    @Test void missingExactTraceIsNotInventedFromNearbyCalls() throws Exception {
        configure("review-no-trace");
        result("sk-no-trace", "only-original-call", TaskState.SUCCESS);
        assertEquals(1, enqueueCount());
        Memo memo = queue.all().get(0);
        Files.createDirectories(dir.resolve("monitor"));
        Files.writeString(dir.resolve("monitor/tools.jsonl"), linkedTool("neighbor", "world", "session", 7,
                OWNER.toString(), "not-the-original-call", "DO_NOT_INCLUDE_NEIGHBOR") + "\n");
        String slice = TrajectorySlices.forMemo(dir, OWNER, memo);
        assertAll(
                () -> assertTrue(slice.contains("trace_status=NOT_AVAILABLE")),
                () -> assertTrue(slice.contains("body_status=COMPLETE")),
                () -> assertFalse(slice.contains("DO_NOT_INCLUDE_NEIGHBOR")));
    }

    @Test void currentRegistryRevisionCannotSubstituteTheOriginalSelectedBodyInReview() throws Exception {
        configure("review-old-body");
        var row = result("sk-original", "original-version-call", TaskState.SUCCESS);
        assertEquals(1, enqueueCount());
        Memo memo = queue.all().get(0);
        String original = SkillBodyStore.read(dir, SkillDeliveryIntake.taskResultOf(row).bodyRef());
        String changed = "DO_NOT_SUBSTITUTE_CURRENT_BODY";
        String bodyRef = SkillBodyStore.write(dir, "sk-original", 2, Integer.toHexString(changed.hashCode()), changed);
        var revised = registry.register(new ArtifactDraft(ArtifactKind.EXPERIENCE, "sk-original",
                "stay safe for sk-original", changed, List.of("night sk-original"), List.of(), List.of(), "LOW",
                List.of(), List.of("memo-next"), "EXECUTOR"), "sk-original", bodyRef, true);
        assertTrue(revised.stored()); assertEquals(2, revised.entry().version());
        String prompt = reviewPrompt(memo, TrajectorySlices.forMemo(dir, OWNER, memo));
        assertAll(() -> assertTrue(prompt.contains(original)), () -> assertFalse(prompt.contains(changed)));
    }

    @Test void changedOriginalBodyDigestProducesUnknownWithoutLeakingSubstitutedBody() throws Exception {
        configure("review-digest");
        var row = result("sk-digest", "digest-call", TaskState.SUCCESS);
        assertEquals(1, enqueueCount());
        Memo memo = queue.all().get(0);
        Path body = dir.resolve("skill-bodies").resolve(SkillDeliveryIntake.taskResultOf(row).bodyRef().substring("skill-body:".length()));
        Files.writeString(body, "DO_NOT_INCLUDE_CORRUPTED_BODY");
        String slice = TrajectorySlices.forMemo(dir, OWNER, memo);
        assertAll(
                () -> assertTrue(slice.contains("body_status=UNKNOWN")),
                () -> assertFalse(slice.contains("DO_NOT_INCLUDE_CORRUPTED_BODY")),
                () -> assertFalse(slice.contains("body_status=COMPLETE")));
    }

    @Test void skillDeliveryWithoutHistoricalBodyDigestStatesNotAvailable() throws Exception {
        configure("review-delivery");
        delivery("sk-delivery", "delivery-only-request", null);
        assertEquals(1, enqueueCount());
        String slice = TrajectorySlices.forMemo(dir, OWNER, queue.all().get(0));
        assertAll(
                () -> assertTrue(slice.contains("body_status=NOT_AVAILABLE")),
                () -> assertTrue(slice.contains("trace_status=NOT_AVAILABLE")),
                () -> assertFalse(slice.contains("body_status=COMPLETE")));
    }

    @Test void oversizedSelectedBodyMarksUnknownLimitInsteadOfPretendingTruncationIsComplete() throws Exception {
        configure("review-body-budget");
        String body = "bounded original skill body ".repeat(140);
        String bodyRef = SkillBodyStore.write(dir, "sk-long", 1, Integer.toHexString(body.hashCode()), body);
        var registration = registry.register(new ArtifactDraft(ArtifactKind.EXPERIENCE, "sk-long",
                "stay safe for sk-long", body, List.of("night sk-long"), List.of(), List.of(), "LOW",
                List.of(), List.of("memo-long"), "EXECUTOR"), "sk-long", bodyRef, true);
        assertTrue(registration.stored());
        result("sk-long", "long-body-call", TaskState.SUCCESS);
        assertEquals(1, enqueueCount());
        String slice = TrajectorySlices.forMemo(dir, OWNER, queue.all().get(0));
        assertAll(
                () -> assertTrue(slice.contains("body_status=UNKNOWN_LIMIT")),
                () -> assertFalse(slice.contains("body_status=COMPLETE")),
                () -> assertTrue(slice.length() <= 2400));
    }

    @Test void malformedResultSnapshotAndWrongOwnerAreUnknownNotCurrentBodyFallback() throws Exception {
        configure("review-invalid");
        result("sk-invalid", "invalid-call", TaskState.SUCCESS);
        assertEquals(1, enqueueCount());
        Memo original = queue.all().get(0);
        Memo invalid = new Memo(original.id(), original.problem(), original.stage(), original.tried(), "{}", original.createdAt());
        String missing = TrajectorySlices.forMemo(dir, OWNER, invalid);
        String foreign = TrajectorySlices.forMemo(dir, UUID.fromString("00000000-0000-0000-0000-000000000002"), original);
        assertAll(
                () -> assertTrue(missing.contains("source_status=UNKNOWN")),
                () -> assertTrue(foreign.contains("source_status=UNKNOWN")),
                () -> assertFalse(foreign.contains("body_status=COMPLETE")));
    }

    @Test void productionReviewerRetainsRealTrajectoryInputConsumer() throws Exception {
        Path current = Path.of("").toAbsolutePath(), source = null;
        for (; current != null; current = current.getParent()) {
            Path candidate = current.resolve("src/main/java/com/dwinovo/numen/plugins/learner/LearnerReviewer.java");
            if (Files.isRegularFile(candidate)) { source = candidate; break; }
        }
        assertNotNull(source);
        String text = Files.readString(source);
        assertTrue(text.contains(".forMemo(cfgDir, companionId, m)"));
        assertTrue(text.contains("memos, prior, rejections, usageText, hitsByMemo, slicesByMemo"));
    }


    @Test void clippedExactCallResultMustRemainUnknownInsteadOfAnUnqualifiedTrace() throws Exception {
        configure("review-trace-clip");
        result("sk-clip", "clip-call", TaskState.SUCCESS);
        assertEquals(1, enqueueCount());
        Memo memo = queue.all().get(0);
        Files.createDirectories(dir.resolve("monitor"));
        Path tools = dir.resolve("monitor/tools.jsonl");
        Files.writeString(tools, linkedTool("event-180", "world", "session", 7,
                OWNER.toString(), "clip-call", "x".repeat(180)) + "\n");
        String exact = TrajectorySlices.forMemo(dir, OWNER, memo);
        assertTrue(exact.contains("trace_status=EXACT_CALL"), "exactly 180 characters fit without clipping");
        Files.writeString(tools, linkedTool("event-181", "world", "session", 7,
                OWNER.toString(), "clip-call", "x".repeat(181)) + "\n");
        String clipped = TrajectorySlices.forMemo(dir, OWNER, memo);
        assertAll(
                () -> assertTrue(clipped.contains("trace_status=UNKNOWN_LIMIT"), "one clipped result is incomplete evidence"),
                () -> assertFalse(clipped.contains("trace_status=EXACT_CALL")),
                () -> assertTrue(clipped.contains("event-181"), "the exact event identity remains available"),
                () -> assertTrue(clipped.contains("…")),
                () -> assertTrue(clipped.length() <= 2400));
    }

    @Test void traceRowCapKeepsOnlyOriginalTailRowsAndMarksLimit() throws Exception {
        configure("review-trace-rows");
        result("sk-rows", "row-call", TaskState.SUCCESS);
        assertEquals(1, enqueueCount());
        Memo memo = queue.all().get(0);
        Files.createDirectories(dir.resolve("monitor"));
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 6; i++) lines.append(linkedTool("event-row-" + i, "world", "session", 7,
                OWNER.toString(), "row-call", "original-row-" + i)).append('\n');
        Files.writeString(dir.resolve("monitor/tools.jsonl"), lines);
        assertTrue(TrajectorySlices.forMemo(dir, OWNER, memo).contains("trace_status=EXACT_CALL"));
        lines.append(linkedTool("event-row-6", "world", "session", 7, OWNER.toString(), "row-call", "original-row-6")).append('\n');
        Files.writeString(dir.resolve("monitor/tools.jsonl"), lines);
        String limited = TrajectorySlices.forMemo(dir, OWNER, memo);
        assertAll(
                () -> assertTrue(limited.contains("trace_status=UNKNOWN_LIMIT")),
                () -> assertFalse(limited.contains("event-row-0")),
                () -> assertTrue(limited.contains("event-row-1") && limited.contains("event-row-6")),
                () -> assertTrue(limited.length() <= 2400));
    }

    @Test void boundedTraceTailCannotPretendTheEntireOriginalJournalWasRead() throws Exception {
        configure("review-trace-tail");
        result("sk-tail", "tail-call", TaskState.SUCCESS);
        assertEquals(1, enqueueCount());
        Files.createDirectories(dir.resolve("monitor"));
        Files.writeString(dir.resolve("monitor/tools.jsonl"), "x".repeat(4 * 1024 * 1024 + 20) + "\n"
                + linkedTool("event-tail-exact", "world", "session", 7, OWNER.toString(), "tail-call", "original-tail-result") + "\n");
        String limited = TrajectorySlices.forMemo(dir, OWNER, queue.all().get(0));
        assertAll(
                () -> assertTrue(limited.contains("trace_status=UNKNOWN_LIMIT")),
                () -> assertTrue(limited.contains("event-tail-exact")),
                () -> assertFalse(limited.contains("xxxxxxxx")),
                () -> assertTrue(limited.length() <= 2400));
    }

    @Test void originalBodyRefCannotEscapeTheExistingBodyStore() throws Exception {
        configure("review-body-store");
        result("sk-store", "store-call", TaskState.SUCCESS);
        assertEquals(1, enqueueCount());
        Memo original = queue.all().get(0);
        String secret = "DO_NOT_INCLUDE_OUTSIDE_BODY_STORE";
        Files.writeString(dir.resolve("outside-body.txt"), secret);
        JsonObject snapshot = JsonParser.parseString(original.snapshot()).getAsJsonObject();
        snapshot.getAsJsonObject("detail").addProperty("body_ref", "skill-body:../outside-body.txt");
        snapshot.getAsJsonObject("detail").addProperty("body_digest", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(secret.getBytes(StandardCharsets.UTF_8))));
        Memo escaped = new Memo(original.id(), original.problem(), original.stage(), original.tried(), snapshot.toString(), original.createdAt());
        String slice = TrajectorySlices.forMemo(dir, OWNER, escaped);
        assertAll(() -> assertTrue(slice.contains("body_status=UNKNOWN")), () -> assertFalse(slice.contains(secret)));
    }

    private static String reviewPrompt(Memo memo, String slice) {
        return PriorRound.buildUserPrompt(List.of(memo), PriorRound.Summary.none(), null, "",
                Map.of(), Map.of(memo.id(), slice));
    }

    /** Same original tool_result envelope/keys as CompanionBrain.journalTaskOutcome, not a time-neighbor join. */
    private static String linkedTool(String event, String world, String server, long epoch, String owner,
                                     String call, String result) {
        JsonObject envelope = new JsonObject(), data = new JsonObject();
        envelope.addProperty("event_id", event); envelope.addProperty("timestamp", "2026-11-14T05:00:00Z");
        envelope.addProperty("type", "tool_result"); envelope.addProperty("world_id", world);
        envelope.addProperty("server_session", server); envelope.addProperty("world_epoch", epoch);
        data.addProperty("companion_id", owner); data.addProperty("tool_call_id", call);
        data.addProperty("tool", "goto"); data.addProperty("journaled_by", "server-async");
        data.addProperty("success", true); data.addProperty("result", result); envelope.add("data", data);
        return envelope.toString();
    }

    private static UsageLedger.Entry rebuild(UsageLedger.Entry row, String artifactId, String kind, String name,
                                             String source, String at, String companionId,
                                             UsageLedger.EvidenceLevel evidence) {
        return new UsageLedger.Entry(artifactId, kind, name, row.phase(), row.outcome(), row.detail(),
                source, at, row.validityClaimed(), companionId, evidence);
    }

    /** New merged run must not let an incomplete RESULT suppress a complete delivery for the same task. */
    @Test void badMetadataResultDoesNotSuppressACompleteDeliveryInNewRun() throws Exception {
        configure("join-consistency");
        Work work = adopt("sk-join", "join-action");
        var delivered = delivery("sk-join", "join-delivery", work);
        var result = report("sk-join", work, TaskState.SUCCESS);
        List<String> lines = Files.readAllLines(ledger.file());
        assertEquals(2, lines.size());
        JsonObject resultRaw = JsonParser.parseString(lines.get(1)).getAsJsonObject();
        resultRaw.addProperty("at", "");
        Files.writeString(ledger.file(), lines.get(0) + "\n" + resultRaw + "\n");
        var snapshot = ledger.readSnapshot();
        assertEquals(UsageLedger.ReadStatus.READY, snapshot.status());
        var scan = SkillDeliveryIntake.runWithTaskResults(snapshot.rows(), CURRENT, queue, intakeJournal,
                StormGuard::admitIfEnqueued, 1000L, 16);
        assertAll(
                () -> assertEquals(1, scan.enqueued(), "complete delivery must still enqueue"),
                () -> assertEquals(1, queue.size()),
                () -> assertEquals(List.of(SkillDeliveryIntake.memoIdOf(delivered)),
                        queue.all().stream().map(Memo::id).toList()),
                () -> assertFalse(intakeJournal.contains(SkillDeliveryIntake.resultMemoIdOf(result)),
                        "incomplete RESULT must not become a typed fact or suppress the delivery"));
    }
}
