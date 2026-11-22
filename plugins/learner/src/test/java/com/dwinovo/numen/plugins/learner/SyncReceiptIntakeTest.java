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

    @Test void liveReceiptFlowsThroughOriginalIntakeAndReview() {
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
        SyncSkillFeedback.INSTANCE.adopt(args, dir, ledger, host, reply::set);
        String id = JsonParser.parseString(reply.get()).getAsJsonObject().getAsJsonObject("data").get("selection_id").getAsString();
        var svc = SyncSkillFeedback.INSTANCE;
        assertEquals(com.dwinovo.numen.api.SyncSkillCorrelation.Binding.BOUND, svc.bind(id, OWNER, COMPANION, host.identity(), "wire-exact", "craft"));
        svc.receipt(id, OWNER, COMPANION, host.identity(), "wire-exact", "craft", com.dwinovo.numen.api.SyncSkillCorrelation.Receipt.SUCCESS);
        var row = ledger.all().get(0);
        assertNotNull(svc.confirm(row));
        var fake = new UsageLedger.Entry(row.artifactId(), row.kind(), row.name(), row.phase(), row.outcome(),
                row.detail().replace("wire-exact", "wire-fake"), row.source(), row.at(), false, row.companionId(), row.evidence());
        assertNull(svc.confirm(fake));
        var queue = new com.dwinovo.numen.plugins.learner.core.MemoQueue(dir.resolve("memo.json"));
        java.util.Set<String> seen = new java.util.HashSet<>();
        var journal = new com.dwinovo.numen.plugins.learner.core.SkillDeliveryIntake.EnqueuedJournal() {
            public boolean contains(String key) { return seen.contains(key); }
            public void remember(String key) { seen.add(key); }
        };
        var scope = new com.dwinovo.numen.plugins.learner.core.SkillDeliveryIntake.Scope("world", "session", 7, COMPANION.toString());
        var scan = com.dwinovo.numen.plugins.learner.core.SkillDeliveryIntake.runWithTaskResults(List.of(fake), scope, queue, journal, (c, f, n, once) -> once.getAsBoolean(), 1, 1);
        assertEquals(0, scan.enqueued());
        scan = com.dwinovo.numen.plugins.learner.core.SkillDeliveryIntake.runWithTaskResults(List.of(row), scope, queue, journal, (c, f, n, once) -> once.getAsBoolean(), 1, 1);
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
        assertEquals(0, com.dwinovo.numen.plugins.learner.core.SkillDeliveryIntake.runWithTaskResults(List.of(row), scope, queue, journal, (c, f, n, once) -> once.getAsBoolean(), 1, 1).enqueued());
        SkillRegistrar.registryFor(dir).retire("sk-1", "test retirement");
        assertFalse(ReviewTargetGate.check(dir, COMPANION, memo, host.identity(), host.identity()).ok());
    }
}
