package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.api.WorldContextStamp;
import com.dwinovo.numen.api.SyncSkillCorrelation;
import com.dwinovo.numen.plugins.learner.contract.ArtifactDraft;
import com.dwinovo.numen.plugins.learner.contract.ArtifactKind;
import com.dwinovo.numen.plugins.learner.contract.ReviewContractGate;
import com.dwinovo.numen.plugins.learner.core.*;
import com.dwinovo.numen.plugins.learner.skills.*;
import com.dwinovo.numen.task.TaskRecord;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Real receipt producer -> original intake -> real consume and durable settlement.
 * Test-only draft, not applied to the queued 13f9 source until its run completes. */
class SyncReceiptCompletionTest {
    @TempDir Path dir;
    private static final UUID OWNER = new UUID(0, 71), COMPANION = new UUID(0, 72);
    private static final WorldContextStamp.Token ORIGINAL = new WorldContextStamp.Token(
            Map.of("world_id", "world", "server_session", "old-session"), 1);
    private static final WorldContextStamp.Token CURRENT = new WorldContextStamp.Token(
            Map.of("world_id", "world", "server_session", "review-session"), 2);
    @AfterEach void reset() { SkillRegistrar.resetForTest(); }

    private record Fixture(UsageLedger ledger, MemoQueue queue, Memo memo) {}
    private Fixture fixture() throws Exception {
        String body = "original synchronous craft method";
        String bodyRef = SkillBodyStore.write(dir, "sync-consume", 1, Integer.toHexString(body.hashCode()), body);
        var draft = new ArtifactDraft(ArtifactKind.EXPERIENCE, "craft", "craft safely", body,
                List.of("craft"), List.of(), List.of(), "LOW", List.of(), List.of("seed"), "EXECUTOR");
        assertTrue(SkillRegistrar.registryFor(dir).register(draft, "sync-consume", bodyRef, true).stored());
        UsageLedger ledger = new UsageLedger(dir);
        LearnerUsageTool.TaskAccess host = new LearnerUsageTool.TaskAccess() {
            public UUID ownerId() { return OWNER; }
            public UUID companionId() { return COMPANION; }
            public WorldContextStamp.Token identity() { return ORIGINAL; }
            public TaskRecord current() { return null; }
            public TaskRecord finished(String id) { return null; }
        };
        JsonObject args = new JsonObject();
        args.addProperty("skill_ref", "skill:sync-consume"); args.addProperty("version", "v1"); args.addProperty("target_tool", "craft");
        AtomicReference<String> response = new AtomicReference<>();
        SyncSkillFeedback service = new SyncSkillFeedback();
        service.adopt(args, dir, ledger, host, response::set);
        String id = JsonParser.parseString(response.get()).getAsJsonObject().getAsJsonObject("data").get("selection_id").getAsString();
        assertEquals(SyncSkillCorrelation.Binding.BOUND, service.bind(id, OWNER, COMPANION, ORIGINAL, "consume-wire", "craft"));
        service.receipt(id, OWNER, COMPANION, ORIGINAL, "consume-wire", "craft", SyncSkillCorrelation.Receipt.SUCCESS);
        MemoQueue queue = new MemoQueue(dir.resolve("consume-memos.json"));
        Set<String> seen = new HashSet<>();
        var journal = new SkillDeliveryIntake.EnqueuedJournal() {
            public boolean contains(String id) { return seen.contains(id); }
            public void remember(String id) { seen.add(id); }
        };
        assertEquals(1, SkillDeliveryIntake.runWithTaskResults(ledger, ledger.all(),
                new SkillDeliveryIntake.Scope("world", "review-session", 2, COMPANION.toString()), queue,
                journal, (c, f, n, once) -> once.getAsBoolean(), 1, 16).enqueued());
        return new Fixture(ledger, queue, queue.all().getFirst());
    }
    private static Verdict noAction(Memo memo) {
        return new Verdict(memo.id(), List.of(Verdict.Action.NO_ACTION), .8,
                "receipt is historical evidence only, not world effectiveness", null, "", "", "", List.of(), List.of());
    }
    private ReviewCompletion start(List<Memo> batch, AtomicReference<WorldContextStamp.Token> current) {
        return new ReviewCompletion(batch, memo -> ReviewTargetGate.check(dir, COMPANION, memo, CURRENT, current.get()));
    }
    private ReviewCompletion.Consumption consume(ReviewCompletion completion, Fixture fixture, List<Memo> batch) {
        return completion.consume(dir, fixture.queue(), batch, List.of(noAction(fixture.memo())), (kind, data) -> {});
    }
    private void assertRetry(Fixture fixture, ReviewCompletion.Consumption result) {
        assertEquals(0, result.settlement().committed());
        assertEquals(0, result.settlement().terminalSkipped());
        assertEquals(1, result.settlement().restored());
        assertTrue(result.verdicts().isEmpty());
        assertEquals(List.of(fixture.memo()), new MemoQueue(fixture.queue().file()).all());
    }
    @Test void historicalReceiptActuallyConsumesWithoutPromotingEffectiveness() throws Exception {
        Fixture f = fixture();
        var batch = f.queue().drain(3);
        var completion = start(batch, new AtomicReference<>(CURRENT));
        assertTrue(completion.permits(f.memo().id()));
        String registry = Files.readString(dir.resolve("skills.jsonl"));
        var done = consume(completion, f, batch);
        assertEquals(1, done.settlement().committed());
        assertEquals(0, new MemoQueue(f.queue().file()).size());
        assertEquals(List.of(f.memo().id()), done.verdicts().stream().map(Verdict::memoId).toList());
        assertEquals(registry, Files.readString(dir.resolve("skills.jsonl")));
        assertEquals(UsageLedger.EvidenceLevel.NONE, f.ledger().all().getFirst().evidence());
    }
    @Test void ledgerLossAfterInitialCheckRestoresOriginalEvenWithContractOff() throws Exception {
        Fixture f = fixture();
        Files.writeString(dir.resolve(ReviewContractGate.FILE_NAME), "{\"mode\":\"off\"}");
        var batch = f.queue().drain(3);
        var completion = start(batch, new AtomicReference<>(CURRENT));
        assertTrue(completion.permits(f.memo().id()));
        String registry = Files.readString(dir.resolve("skills.jsonl"));
        Files.writeString(f.ledger().file(), "");
        assertRetry(f, consume(completion, f, batch));
        assertEquals("SKILL_SYNC_SOURCE_UNKNOWN", completion.target(f.memo().id()).reason());
        assertEquals(registry, Files.readString(dir.resolve("skills.jsonl")));
    }
}
