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
        TaskRecord active;
        final Map<String, TaskRecord> done = new HashMap<>();
        public UUID companionId() { return OWNER; }
        public WorldContextStamp.Token identity() { return stamp; }
        public TaskRecord current() { return active; }
        public TaskRecord finished(String id) { return done.get(id); }
        void finish(TaskState state) {
            active.setState(state);
            active.setResult(state == TaskState.SUCCESS ? TaskResult.ok("arrived") : TaskResult.fail("ended"));
            done.put(active.publicId(), active); active = null;
        }
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
