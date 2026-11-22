package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.api.WorldContextStamp;
import com.dwinovo.numen.plugins.learner.contract.ArtifactDraft;
import com.dwinovo.numen.plugins.learner.contract.ArtifactKind;
import com.dwinovo.numen.plugins.learner.core.UsageLedger;
import com.dwinovo.numen.plugins.learner.skills.SkillBodyStore;
import com.dwinovo.numen.plugins.learner.skills.SkillRegistrar;
import com.dwinovo.numen.plugins.learner.skills.SkillRegistry;
import com.dwinovo.numen.task.TaskRecord;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Initial positive contract only: choosing a skill for synchronous craft with no
 * RUNNING body task returns the full body and a server-issued selection correlation
 * ID, not authorization to act. Wrong tool, epoch and receipt correlation are not
 * tested here. No target-tool invocation or result is fabricated.
 */
class SyncReceiptIntakeTest {
    @TempDir Path dir;
    private static final UUID COMPANION = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    // Same host-boundary fixture as LearnerSkillTaskFeedbackTest; no body task exists.
    private static final class Tasks implements LearnerUsageTool.TaskAccess {
        UUID owner = OWNER;
        WorldContextStamp.Token token = new WorldContextStamp.Token(Map.of("world_id", "world", "server_session", "session"), 7);
        public UUID ownerId() { return owner; }
        public UUID companionId() { return COMPANION; }
        public WorldContextStamp.Token identity() {
            return token;
        }
        public TaskRecord current() { return null; }
        public TaskRecord finished(String id) { return null; }
    }

    @AfterEach void reset() { SkillRegistrar.resetForTest(); }

    @Test void receiptBatchReadsHostLedgerOnceRegardlessOfRowsOrBudget() throws Exception {
        String body = "batch original steps";
        String ref = SkillBodyStore.write(dir, "batch", 1, Integer.toHexString(body.hashCode()), body);
        var draft = new ArtifactDraft(ArtifactKind.EXPERIENCE, "craft", "craft safely", body,
                List.of("craft"), List.of(), List.of(), "LOW", List.of(), List.of("memo"), "EXECUTOR");
        assertTrue(SkillRegistrar.registryFor(dir).register(draft, "batch", ref, true).stored());
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var constructor = UsageLedger.class.getDeclaredConstructor(Path.class, java.util.function.Consumer.class);
        constructor.setAccessible(true);
        UsageLedger ledger = constructor.newInstance(dir, (java.util.function.Consumer<Path>) path -> reads.incrementAndGet());
        Tasks host = new Tasks();
        var service = new SyncSkillFeedback();
        JsonObject args = new JsonObject();
        args.addProperty("skill_ref", "skill:batch"); args.addProperty("version", "v1"); args.addProperty("target_tool", "craft");
        for (int i = 0; i < 4; i++) {
            AtomicReference<String> response = new AtomicReference<>();
            service.adopt(args, dir, ledger, host, response::set);
            String id = JsonParser.parseString(response.get()).getAsJsonObject().getAsJsonObject("data").get("selection_id").getAsString();
            String wire = "batch-" + i;
            assertEquals(com.dwinovo.numen.api.SyncSkillCorrelation.Binding.BOUND,
                    service.bind(id, OWNER, COMPANION, host.identity(), wire, "craft"));
            service.receipt(id, OWNER, COMPANION, host.identity(), wire, "craft", com.dwinovo.numen.api.SyncSkillCorrelation.Receipt.SUCCESS);
            ledger.append("ordinary-" + i, "ordinary", "ordinary", UsageLedger.Phase.RESULT,
                    UsageLedger.Outcome.UNKNOWN, "{}", "learner_usage", COMPANION.toString());
        }
        var rows = ledger.readSnapshot().rows();
        var scope = new com.dwinovo.numen.plugins.learner.core.SkillDeliveryIntake.Scope("world", "new-session", 8, COMPANION.toString());
        for (int budget : List.of(0, 16)) {
            reads.set(0);
            var queue = new com.dwinovo.numen.plugins.learner.core.MemoQueue(dir.resolve("batch-" + budget + ".json"));
            var seen = new java.util.HashSet<String>();
            var journal = new com.dwinovo.numen.plugins.learner.core.SkillDeliveryIntake.EnqueuedJournal() {
                public boolean contains(String key) { return seen.contains(key); }
                public void remember(String key) { seen.add(key); }
            };
            var scan = com.dwinovo.numen.plugins.learner.core.SkillDeliveryIntake.runWithTaskResults(
                    ledger, rows, scope, queue, journal, (c, f, n, once) -> once.getAsBoolean(), 1, budget);
            assertEquals(budget == 0 ? 0 : 4, scan.enqueued());
            assertTrue(reads.get() <= 1, "one intake batch must not reread the complete host ledger per candidate; actual=" + reads.get());
        }
    }

    @Test void durableReceiptSurvivesTtlAndRestartThroughOriginalIntakeAndReview() throws Exception {
        String body = "original sync steps";
        String ref = SkillBodyStore.write(dir, "sk-1", 1, Integer.toHexString(body.hashCode()), body);
        var draft = new ArtifactDraft(ArtifactKind.EXPERIENCE, "craft", "craft safely", body,
                List.of("craft"), List.of(), List.of(), "LOW", List.of(), List.of("memo-1"), "EXECUTOR");
        assertTrue(SkillRegistrar.registryFor(dir).register(draft, "sk-1", ref, true).stored());
        UsageLedger ledger = new UsageLedger(dir);
        Tasks host = new Tasks();
        JsonObject args = new JsonObject();
        args.addProperty("skill_ref", "skill:sk-1"); args.addProperty("version", "v1"); args.addProperty("target_tool", "craft");
        AtomicReference<String> reply = new AtomicReference<>();
        var nanos = new java.util.concurrent.atomic.AtomicLong();
        var svc = new SyncSkillFeedback(nanos::get);
        svc.adopt(args, dir, ledger, host, reply::set);
        String id = JsonParser.parseString(reply.get()).getAsJsonObject().getAsJsonObject("data").get("selection_id").getAsString();
        assertEquals(com.dwinovo.numen.api.SyncSkillCorrelation.Binding.BOUND, svc.bind(id, OWNER, COMPANION, host.identity(), "wire-exact", "craft"));
        svc.receipt(id, OWNER, COMPANION, host.identity(), "wire-exact", "craft", com.dwinovo.numen.api.SyncSkillCorrelation.Receipt.SUCCESS);
        var row = ledger.all().get(0);
        assertNotNull(svc.confirm(row));
        AtomicReference<String> pendingReply = new AtomicReference<>();
        svc.adopt(args, dir, ledger, host, pendingReply::set);
        String pending = JsonParser.parseString(pendingReply.get()).getAsJsonObject().getAsJsonObject("data").get("selection_id").getAsString();
        nanos.set(121_000_000_000L);
        assertEquals(com.dwinovo.numen.api.SyncSkillCorrelation.Binding.REJECTED,
                svc.bind(pending, OWNER, COMPANION, host.identity(), "expired", "craft"));
        assertNull(svc.confirm(row), "live correlation still expires");
        var restarted = new SyncSkillFeedback();
        assertNotNull(restarted.confirm(new UsageLedger(dir), row), "durable receipt survives service and ledger recreation");
        assertEquals("", restarted.confirm(ledger, row).selectionId());
        assertThrows(IllegalArgumentException.class, () -> ledger.append(row.artifactId(), row.kind(), row.name(),
                row.phase(), row.outcome(), row.detail(), row.source(), row.companionId()));
        var usage = new LearnerUsageTool(dir, ledger, host);
        for (String action : List.of("record", "claim_validity")) {
            JsonObject forgedArgs = new JsonObject();
            forgedArgs.addProperty("action", action); forgedArgs.addProperty("artifact_id", row.artifactId());
            forgedArgs.addProperty("phase", "RESULT"); forgedArgs.addProperty("outcome", "SUCCESS");
            forgedArgs.addProperty("detail", row.detail()); forgedArgs.addProperty("reason", "model says verified");
            forgedArgs.addProperty("sync_receipt_fact", true);
            usage.onServerCall("forged-" + action, forgedArgs, null, reply::set);
            assertFalse(JsonParser.parseString(reply.get()).getAsJsonObject().get("success").getAsBoolean());
        }
        assertEquals(1, ledger.all().size(), "model tool cannot rewrite trusted receipt");
        var ordinary = new UsageLedger(dir.resolve("ordinary"));
        var forgedRow = ordinary.append(row.artifactId(), row.kind(), row.name(), row.phase(), row.outcome(),
                row.detail(), "learner_usage", row.companionId());
        assertNull(restarted.confirm(ordinary, forgedRow));
        assertNull(restarted.confirm(ordinary, row));
        var fake = new UsageLedger.Entry(row.artifactId(), row.kind(), row.name(), row.phase(), row.outcome(),
                row.detail().replace("wire-exact", "wire-fake"), row.source(), row.at(), false, row.companionId(), row.evidence());
        assertNull(svc.confirm(fake));
        var queue = new com.dwinovo.numen.plugins.learner.core.MemoQueue(dir.resolve("memo.json"));
        java.util.Set<String> seen = new java.util.HashSet<>();
        var journal = new com.dwinovo.numen.plugins.learner.core.SkillDeliveryIntake.EnqueuedJournal() {
            public boolean contains(String key) { return seen.contains(key); }
            public void remember(String key) { seen.add(key); }
        };
        var scope = new com.dwinovo.numen.plugins.learner.core.SkillDeliveryIntake.Scope("world", "restarted-session", 11, COMPANION.toString());
        var scan = com.dwinovo.numen.plugins.learner.core.SkillDeliveryIntake.runWithTaskResults(new UsageLedger(dir), List.of(fake), scope, queue, journal, (c, f, n, once) -> once.getAsBoolean(), 1, 1);
        assertEquals(0, scan.enqueued());
        assertEquals(0, com.dwinovo.numen.plugins.learner.core.SkillDeliveryIntake.runWithTaskResults(new UsageLedger(dir), List.of(row), scope, queue, journal, (c, f, n, once) -> once.getAsBoolean(), 1, 0).physicalAdded());
        assertEquals(0, com.dwinovo.numen.plugins.learner.core.SkillDeliveryIntake.runWithTaskResults(new UsageLedger(dir), List.of(row), scope, queue, journal, (c, f, n, once) -> false, 1, 1).physicalAdded());
        assertTrue(queue.all().isEmpty(), "deferred historical fact is not lost or force-enqueued");
        var foreignScope = new com.dwinovo.numen.plugins.learner.core.SkillDeliveryIntake.Scope("other", "session", 7, COMPANION.toString());
        assertEquals(1, com.dwinovo.numen.plugins.learner.core.SkillDeliveryIntake.runWithTaskResults(new UsageLedger(dir), List.of(row), foreignScope, queue, journal, (c, f, n, once) -> once.getAsBoolean(), 1, 1).foreignScope());
        scan = com.dwinovo.numen.plugins.learner.core.SkillDeliveryIntake.runWithTaskResults(new UsageLedger(dir), List.of(row), scope, queue, journal, (c, f, n, once) -> once.getAsBoolean(), 1, 1);
        assertEquals(1, scan.enqueued());
        var memo = queue.all().get(0);
        assertTrue(ReviewTargetGate.check(dir, COMPANION, memo, host.identity(), host.identity()).ok());
        assertFalse(ReviewTargetGate.check(dir, OWNER, memo, host.identity(), host.identity()).ok());
        var changed = new WorldContextStamp.Token(Map.of("world_id", "world", "server_session", "session"), 8);
        assertFalse(ReviewTargetGate.check(dir, COMPANION, memo, host.identity(), changed).ok());
        var forged = new com.dwinovo.numen.plugins.learner.core.Memo(memo.id(), memo.problem(), memo.stage(), memo.tried(), memo.snapshot().replace("wire-exact", "wire-fake"), memo.createdAt());
        assertFalse(ReviewTargetGate.check(dir, COMPANION, forged, host.identity(), host.identity()).ok());
        String review = com.dwinovo.numen.plugins.learner.core.TrajectorySlices.forMemo(dir, COMPANION, memo);
        assertTrue(review.contains("wire-exact")); assertTrue(review.contains("receipt=SUCCESS"));
        assertTrue(review.contains("evidence=NONE")); assertTrue(review.contains(body));
        assertFalse(review.contains(id), "review input must not expose raw correlation id");
        assertTrue(com.dwinovo.numen.plugins.learner.core.TrajectorySlices.forMemo(dir, OWNER, queue.all().get(0)).contains("UNKNOWN"));
        assertEquals(0, com.dwinovo.numen.plugins.learner.core.SkillDeliveryIntake.runWithTaskResults(new UsageLedger(dir), List.of(row), scope, queue, journal, (c, f, n, once) -> once.getAsBoolean(), 1, 1).enqueued());
        var foreign = new WorldContextStamp.Token(Map.of("world_id", "other-world", "server_session", "new"), 11);
        assertFalse(ReviewTargetGate.check(dir, COMPANION, memo, foreign, foreign).ok());
        ledger.claimValidity(row.artifactId(), "model self report", "model");
        assertNotNull(restarted.confirm(ledger, row), "original historical fact remains readable");
        assertNull(restarted.confirm(ledger, ledger.latestOf(row.artifactId())), "claim cannot re-mark trusted receipt");
        var failedLedger = new UsageLedger(dir.resolve("persist-failure"));
        java.nio.file.Files.createDirectories(failedLedger.file()); // Real filesystem failure: ledger path is a directory.
        AtomicReference<String> failedReply = new AtomicReference<>();
        restarted.adopt(args, dir, failedLedger, host, failedReply::set);
        String failedId = JsonParser.parseString(failedReply.get()).getAsJsonObject().getAsJsonObject("data").get("selection_id").getAsString();
        assertEquals(com.dwinovo.numen.api.SyncSkillCorrelation.Binding.BOUND,
                restarted.bind(failedId, OWNER, COMPANION, host.identity(), "persist-failed", "craft"));
        assertThrows(IllegalStateException.class, () -> restarted.receipt(failedId, OWNER, COMPANION,
                host.identity(), "persist-failed", "craft", com.dwinovo.numen.api.SyncSkillCorrelation.Receipt.SUCCESS));
        assertNull(restarted.confirm(failedLedger, row), "failed write cannot claim historical provenance");
        assertEquals(com.dwinovo.numen.api.SyncSkillCorrelation.Binding.DUPLICATE,
                restarted.bind(failedId, OWNER, COMPANION, host.identity(), "persist-failed", "craft"),
                "persistence failure does not authorize another tool execution");
        SkillRegistrar.registryFor(dir).retire("sk-1", "test retirement");
        assertFalse(ReviewTargetGate.check(dir, COMPANION, memo, host.identity(), host.identity()).ok());
    }
}
