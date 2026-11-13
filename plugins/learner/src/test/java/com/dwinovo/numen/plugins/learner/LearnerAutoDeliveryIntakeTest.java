package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.plugins.learner.core.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C1-B 2026-11-13: drive the real scanOnce, disk ledger, original journal and MemoQueue.
 * Only existing host inputs are injected; no imitation controller, LLM or game is invoked.
 * Physical append-after-journal-failure budget counterexamples belong to the separate Intake batch.
 */
class LearnerAutoDeliveryIntakeTest {
    @TempDir Path dir;
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final LearnerAutoIntake.DeliveryScope CURRENT =
            new LearnerAutoIntake.DeliveryScope("world", "session", 7);
    private final Map<UUID, MemoQueue> queues = new LinkedHashMap<>();
    private final AtomicInteger reads = new AtomicInteger();
    private UsageLedger ledger;

    @BeforeEach void setup() throws Exception {
        keys().clear();
        cursors().clear();
        set("configDirCache", dir);
        set("enabled", true);
        set("lastToggleCheck", -1L);
        ledger = new UsageLedger(dir);
        LearnerAutoIntake.installQueueResolverForTest(this::queue);
        LearnerAutoIntake.installDeliveryLedgerReaderForTest(() -> {
            reads.incrementAndGet();
            return ledger.readSnapshot();
        });
        StormGuard.resetForTest();
        T3Seen.resetForTest();
        Files.createDirectories(dir.resolve("monitor"));
    }

    @AfterEach void reset() throws Exception {
        LearnerAutoIntake.installDeliveryLedgerReaderForTest(null);
        LearnerAutoIntake.installQueueResolverForTest(null);
        set("configDirCache", null);
        set("enabled", true);
        keys().clear();
        cursors().clear();
        StormGuard.resetForTest();
        T3Seen.resetForTest();
    }

    private static void set(String name, Object value) throws Exception {
        Field f = LearnerAutoIntake.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }
    @SuppressWarnings("unchecked")
    private static Set<String> keys() throws Exception {
        Field f = LearnerAutoIntake.class.getDeclaredField("ENQUEUED");
        f.setAccessible(true);
        return (Set<String>) f.get(null);
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Long> cursors() throws Exception {
        Field f = LearnerAutoIntake.class.getDeclaredField("CURSORS");
        f.setAccessible(true);
        return (Map<String, Long>) f.get(null);
    }
    private MemoQueue queue(UUID id) {
        return queues.computeIfAbsent(id, k -> new MemoQueue(dir.resolve("learner-memos-" + k + ".json")));
    }
    private Path journal() { return dir.resolve("learner-enqueued.jsonl"); }
    private int scan() { return LearnerAutoIntake.scanOnce(() -> CURRENT); }

    private UsageLedger.Entry delivery(String request, UUID owner, String skill) {
        return delivery(request, owner, skill, CURRENT);
    }
    private UsageLedger.Entry delivery(String request, UUID owner, String skill,
                                       LearnerAutoIntake.DeliveryScope scope) {
        var result = SkillDeliveryEvidence.record(new SkillDeliveryEvidence.Context(request,
                scope.worldId(), scope.serverSession(), scope.epoch(), owner.toString(),
                "EXECUTOR", "execution", "", "",
                List.of(new SkillDeliveryEvidence.Ref("skill:" + skill, "v1"))), ledger);
        assertEquals(1, result.written(), result.toString());
        assertFalse(result.ioFailed());
        return ledger.all().stream().filter(e -> result.artifacts().contains(e.artifactId()))
                .findFirst().orElseThrow();
    }

    private String event(String id, UUID owner) {
        // Distinct reason is part of the real CandidateGate fingerprint: avoid storm fixture collisions.
        return "{\"schema_version\":1,\"event_id\":\"" + id + "\",\"timestamp\":\"" + Instant.now()
                + "\",\"source\":\"numen\",\"category\":\"rdd_instrumentation\",\"type\":\"loop_detected\","
                + "\"data\":{\"companionId\":\"" + owner + "\",\"reason\":\"" + id + "\"}}";
    }
    private void instrumentation(int count) throws Exception {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) lines.add(event("ev-" + i, A));
        Files.writeString(dir.resolve("monitor/instrumentation.jsonl"), String.join("\n", lines) + "\n");
    }
    private void deferred(UUID owner, int count) {
        long due = System.currentTimeMillis() - 11 * 60_000L;
        for (int i = 0; i < count; i++) {
            String id = "deferred-" + owner + "-" + i;
            SuppressedStore.append(dir, owner, new Memo(id, "p", "deferred", "t", "s", due),
                    id, "SUPPRESSED_COOLDOWN", due);
        }
    }

    @Test void noNewInstrumentationStillConsumesRealDeliveryExactlyOnce() throws Exception {
        instrumentation(0);
        var row = delivery("empty-stream", A, "empty-stream");
        assertEquals(1, scan());
        assertEquals(1, reads.get(), "one complete ledger read per scan");
        String id = SkillDeliveryIntake.memoIdOf(row);
        assertTrue(queue(A).contains(id));
        assertEquals("skill_delivery", queue(A).all().get(0).stage());
        assertTrue(keys().contains(id));
        assertEquals(1, Files.readAllLines(journal()).size());
        assertEquals(0, scan());
        assertEquals(2, reads.get());
        assertEquals(1, queue(A).size());
        assertEquals(UsageLedger.Phase.PRESENTED, ledger.all().get(0).phase());
        assertEquals(UsageLedger.Outcome.UNKNOWN, ledger.all().get(0).outcome());
        assertFalse(ledger.all().get(0).validityClaimed());
    }

    @Test void missingInstrumentationDiscoversOwnerWithoutPrebuiltMemoFile() throws Exception {
        var row = delivery("new-owner", B, "new-owner");
        Path memo = dir.resolve("learner-memos-" + B + ".json");
        assertFalse(Files.exists(memo));
        assertFalse(Files.exists(dir.resolve("monitor/instrumentation.jsonl")));
        assertEquals(1, scan());
        assertTrue(Files.isRegularFile(memo));
        assertTrue(queue(B).contains(SkillDeliveryIntake.memoIdOf(row)));
        assertEquals(1, reads.get());
    }

    @Test void disabledAndNullConfigStopBeforeLedgerIdentityOrQueueAccess() throws Exception {
        delivery("switch", A, "switch");
        AtomicInteger scopes = new AtomicInteger();
        java.util.function.Supplier<LearnerAutoIntake.DeliveryScope> identity = () -> {
            scopes.incrementAndGet(); return CURRENT;
        };
        set("enabled", false);
        assertEquals(0, LearnerAutoIntake.scanOnce(identity));
        set("enabled", true);
        set("configDirCache", null);
        assertEquals(0, LearnerAutoIntake.scanOnce(identity));
        assertEquals(0, reads.get());
        assertEquals(0, scopes.get());
        assertTrue(queues.isEmpty());
        assertFalse(Files.exists(journal()));
    }

    @Test void scopeMustComeFromCurrentHostAndCannotBeInferredFromLedger() throws Exception {
        delivery("scope", A, "scope");
        assertEquals(0, LearnerAutoIntake.scanOnce());
        assertEquals(0, LearnerAutoIntake.scanOnce(() -> null));
        for (var scope : List.of(new LearnerAutoIntake.DeliveryScope("", "session", 7),
                new LearnerAutoIntake.DeliveryScope("world", "", 7),
                new LearnerAutoIntake.DeliveryScope("world", "session", 0),
                new LearnerAutoIntake.DeliveryScope("other-world", "session", 7),
                new LearnerAutoIntake.DeliveryScope("world", "other-session", 7),
                new LearnerAutoIntake.DeliveryScope("world", "session", 8))) {
            assertEquals(0, LearnerAutoIntake.scanOnce(() -> scope));
        }
        assertFalse(Files.exists(journal()));
        assertEquals(0, queue(A).size());
        assertEquals(1, scan(), "authoritative matching current identity finally admits");
    }

    @Test void malformedLedgerKeepsLegacyRowsButDeliveryWaitsForCompleteSnapshot() throws Exception {
        delivery("malformed", A, "malformed");
        String original = Files.readString(ledger.file());
        Files.writeString(ledger.file(), original + "{bad-json\n{\"phase\":\"UNKNOWN_PHASE\"}\n");
        var snapshot = ledger.readSnapshot();
        assertEquals(UsageLedger.ReadStatus.MALFORMED, snapshot.status());
        assertEquals(2, snapshot.malformedLines());
        assertEquals(1, ledger.all().size(), "legacy diagnostic partial rows remain available");
        assertEquals(0, scan(), "an unread RESULT must not be treated as absent");
        assertFalse(Files.exists(journal()));
        Files.writeString(ledger.file(), original + "{\"phase\":\"PRESENTED\",\"validity_claim\":{}}\n");
        assertEquals(UsageLedger.ReadStatus.MALFORMED, ledger.readSnapshot().status());
        assertThrows(RuntimeException.class, () -> ledger.all(), "legacy bad-field exception remains unchanged");
        assertEquals(0, scan());
        Files.writeString(ledger.file(), original);
        assertEquals(1, scan());
    }

    @Test void missingEmptyAndUnreadableLedgerAreDistinctAndRecoverable() throws Exception {
        assertEquals(UsageLedger.ReadStatus.MISSING, ledger.readSnapshot().status());
        assertEquals(0, scan());
        Files.writeString(ledger.file(), "\n");
        assertEquals(UsageLedger.ReadStatus.READY, ledger.readSnapshot().status());
        assertTrue(ledger.all().isEmpty());
        assertEquals(0, scan());
        Files.delete(ledger.file());
        Files.createDirectory(ledger.file());
        assertEquals(UsageLedger.ReadStatus.UNREADABLE, ledger.readSnapshot().status());
        assertTrue(ledger.all().isEmpty(), "legacy all still returns empty on IO failure");
        assertEquals(0, scan());
        Files.delete(ledger.file());
        delivery("repaired-ledger", A, "repaired-ledger");
        assertEquals(1, scan());
    }

    @Test void originalJournalFailureRepairAndReloadNeverClaimsMemorySuccessEarly() throws Exception {
        var row = delivery("journal-fail", A, "journal-retry");
        String id = SkillDeliveryIntake.memoIdOf(row);
        Files.createDirectory(journal()); // Real deterministic IO fault; no ACL/admin dependence.
        assertEquals(0, scan());
        assertTrue(queue(A).contains(id), "original queue append survived journal failure");
        assertFalse(keys().contains(id), "failed durable write must not publish ENQUEUED");
        assertEquals(1, queue(A).size());
        Files.delete(journal()); // Only our known empty @TempDir blocker.
        assertEquals(0, scan(), "repair journal for the same queued id without counting it new");
        assertTrue(keys().contains(id));
        assertEquals(1, Files.readAllLines(journal()).size());
        delivery("journal-next-request", A, "journal-retry");
        assertEquals(1, scan(), "failed/repair-only transaction must not eat cooldown");
        assertEquals(2, queue(A).size());
        Files.writeString(journal(), "{\"id\":\"ai-legacy\"}\n", java.nio.file.StandardOpenOption.APPEND);
        keys().clear();
        queues.clear();
        LearnerAutoIntake.install(dir);
        assertTrue(keys().contains("ai-legacy"), "same original journal restores both channels");
        assertTrue(keys().contains(id));
        assertEquals(0, scan());
        assertEquals(2, queue(A).size());
        assertEquals(3, Files.readAllLines(journal()).size());
    }

    @Test void boundedLegacyKeySetDoesNotEraseJustCommittedDeliveryKey() throws Exception {
        for (int i = 0; i < 4096; i++) keys().add("ai-capacity-" + i);
        var row = delivery("capacity", A, "capacity");
        assertEquals(1, scan());
        assertTrue(keys().contains(SkillDeliveryIntake.memoIdOf(row)));
        assertTrue(keys().size() <= 4096);
        assertEquals(1, Files.readAllLines(journal()).size());
    }

    @Test void instrumentationAndDeferredUseBudgetBeforeDeliveryAndOwnersRemainIndependent() throws Exception {
        instrumentation(13);
        deferred(A, 2);
        var first = delivery("budget-first", A, "budget-first");
        var later = delivery("budget-later", A, "budget-later");
        delivery("other-owner", B, "other-owner");
        assertEquals(17, scan(), "A=13 instrumentation+2 deferred+1 delivery; B=1");
        assertEquals(16, queue(A).size());
        assertEquals(1, queue(B).size());
        assertTrue(queue(A).contains(SkillDeliveryIntake.memoIdOf(first)));
        assertFalse(keys().contains(SkillDeliveryIntake.memoIdOf(later)));
        assertTrue(queue(A).all().get(0).id().startsWith("ai-"));
        assertEquals("deferred", queue(A).all().get(13).stage());
        assertEquals("skill_delivery", queue(A).all().get(15).stage());
        assertEquals(1, reads.get(), "one snapshot shared across both owners");
        assertEquals(1, scan(), "leftover delivery is retryable next scan with no new events");
        assertEquals(17, queue(A).size());
    }

    @Test void fullPriorOwnerBudgetDoesNotJournalOrCoolDownDelivery() throws Exception {
        instrumentation(16);
        var row = delivery("budget-zero", A, "budget-zero");
        assertEquals(16, scan());
        assertFalse(keys().contains(SkillDeliveryIntake.memoIdOf(row)));
        assertEquals(16, Files.readAllLines(journal()).size());
        assertEquals(1, scan(), "next scan can admit without waiting on a delivery cooldown");
        assertTrue(queue(A).contains(SkillDeliveryIntake.memoIdOf(row)));
    }

    @Test void deferredGlobalLimitStaysFourAcrossOwnersBeforeDelivery() throws Exception {
        instrumentation(1); // Exercise real nonempty instrumentation branch, not its early return.
        deferred(A, 5);
        deferred(B, 5);
        delivery("defer-a", A, "defer-a");
        delivery("defer-b", B, "defer-b");
        assertEquals(7, scan(), "one instrumentation + four global deferred + two deliveries");
        long deferredCount = queues.values().stream().flatMap(q -> q.all().stream())
                .filter(m -> m.stage().equals("deferred")).count();
        assertEquals(4, deferredCount);
        for (MemoQueue q : queues.values()) {
            assertEquals("skill_delivery", q.all().get(q.size() - 1).stage());
        }
    }

    @Test void fullQueueDoesNotConsumeCooldownOrBlockAnotherOwner() throws Exception {
        for (int i = 0; i < MemoQueue.MAX_QUEUE; i++)
            assertTrue(queue(A).append(new Memo("fill-" + i, "p", "s", "t", "s", 1)));
        var row = delivery("full", A, "full");
        delivery("good-owner", B, "good-owner");
        assertEquals(1, scan());
        assertFalse(keys().contains(SkillDeliveryIntake.memoIdOf(row)));
        assertEquals(1, queue(B).size());
        assertEquals(1, queue(A).commitDrain(queue(A).drain(1)));
        assertEquals(1, scan());
        assertTrue(queue(A).contains(SkillDeliveryIntake.memoIdOf(row)));
    }

    @Test void corruptQueueContainsFailureDoesNotBlockGoodOwnerAndCanRecover() throws Exception {
        var row = delivery("corrupt", A, "corrupt");
        delivery("corrupt-good", B, "corrupt-good");
        Files.writeString(queue(A).file(), "{bad-json");
        assertEquals(1, scan());
        assertEquals("{bad-json", Files.readString(queue(A).file()), "preserve corrupt evidence");
        assertFalse(keys().contains(SkillDeliveryIntake.memoIdOf(row)));
        Files.delete(queue(A).file());
        assertEquals(1, scan());
        assertTrue(queue(A).contains(SkillDeliveryIntake.memoIdOf(row)));
    }

    @Test void appendIoFailureDoesNotBlockGoodOwnerAndCanRecoverImmediately() throws Exception {
        var row = delivery("append", A, "append");
        delivery("append-good", B, "append-good");
        Path blocker = dir.resolve("blocked-parent");
        Files.writeString(blocker, "regular file blocks directory creation");
        queues.put(A, new MemoQueue(blocker.resolve("memo.json")));
        assertEquals(1, scan());
        assertFalse(keys().contains(SkillDeliveryIntake.memoIdOf(row)));
        Files.delete(blocker);
        Files.createDirectory(blocker);
        assertEquals(1, scan());
        assertTrue(queue(A).contains(SkillDeliveryIntake.memoIdOf(row)));
    }

    @Test void oversizedFirstDeliveryDoesNotBlockLaterGoodRowForSameOwner() throws Exception {
        var oversized = delivery("oversized", A, "huge-" + "x".repeat(MemoQueue.MAX_FIELD_CHARS + 1));
        var good = delivery("after-oversized", A, "after-oversized");
        assertEquals(1, scan());
        assertFalse(keys().contains(SkillDeliveryIntake.memoIdOf(oversized)));
        assertFalse(queue(A).contains(SkillDeliveryIntake.memoIdOf(oversized)));
        assertTrue(queue(A).contains(SkillDeliveryIntake.memoIdOf(good)));
        assertEquals(1, queue(A).size());
        assertEquals(1, Files.readAllLines(journal()).size());
    }

    @Test void failedLedgerReadLeavesOldInstrumentationAndFutureScanAlive() throws Exception {
        instrumentation(1);
        LearnerAutoIntake.installDeliveryLedgerReaderForTest(() -> { throw new IllegalStateException("reader failure"); });
        assertEquals(1, scan());
        assertEquals(1, queue(A).size());
        LearnerAutoIntake.installDeliveryLedgerReaderForTest(ledger::readSnapshot);
        delivery("reader-repair", A, "reader-repair");
        assertEquals(1, scan());
        assertEquals(2, queue(A).size());
    }

    @Test void queueSeenHitDoesNotBecomeNewEnqueueOrJournalSuccess() throws Exception {
        var row = delivery("seen", A, "seen");
        String id = SkillDeliveryIntake.memoIdOf(row);
        assertTrue(queue(A).append(new Memo(id, "p", "s", "t", "s", 1)));
        assertEquals(1, queue(A).commitDrain(queue(A).drain(1)));
        assertEquals(0, scan());
        assertEquals(0, queue(A).size());
        assertFalse(keys().contains(id));
        assertFalse(Files.exists(journal()));
    }
}
