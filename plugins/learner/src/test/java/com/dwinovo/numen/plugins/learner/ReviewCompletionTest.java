package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.plugins.learner.core.*;
import com.dwinovo.numen.plugins.learner.contract.*;
import com.dwinovo.numen.plugins.learner.skills.*;
import com.dwinovo.numen.api.WorldContextStamp;
import com.google.gson.Gson;
import org.junit.jupiter.api.AfterEach;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-11 C1: production completion selection + real temporary MemoQueue, no model or live data. */
class ReviewCompletionTest {
    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static Memo memo(String version, String use) {
        return new Memo("expand-review:exp:e1@"+version+"#"+use,"review", "EXPERIENCE_EXPAND", "expand",
                "{\"ref\":\"exp:e1\",\"version\":\""+version+"\",\"use_id\":\""+use+"\"}",1L);
    }
    private static void entry(Path dir, UUID id, String version, boolean retracted, String publication) throws Exception {
        Files.writeString(dir.resolve("experience-"+id+".jsonl"),"{\"id\":\"e1\",\"content_fingerprint\":\""+version+
                "\",\"retracted\":"+retracted+",\"publication\":\""+publication+"\"}\n");
    }
    private static ReviewCompletion check(Path dir, UUID id, List<Memo> batch) {
        return new ReviewCompletion(batch,m -> ReviewTargetGate.check(dir,id,m));
    }
    @Test void versionChangedRetiresOldWithoutArtifactAndNewVersionAdvances(@TempDir Path dir) throws Exception {
        entry(dir,A,"v2",false,"TRIAL");
        Memo old=memo("v1","u1"), fresh=memo("v2","u2");
        MemoQueue q=new MemoQueue(dir.resolve("memo-A.json"));q.append(old);q.append(fresh);
        var batch=q.drain(10);var completion=check(dir,A,batch);
        Set<String> judged=new HashSet<>();for(Memo m:batch)if(completion.permits(m.id()))judged.add(m.id());
        AtomicInteger artifacts=new AtomicInteger();
        for(Memo m:batch)if(completion.effective(m.id(),judged))artifacts.incrementAndGet();
        assertFalse(completion.effective(old.id(),Set.of(old.id())),"even stale preselected verdict cannot write");
        assertEquals(1,artifacts.get());
        var result=completion.settle(q,batch,judged);
        assertEquals(1,result.terminalSkipped());assertEquals(1,result.committed());assertEquals(0,q.size());
        q.append(old);assertEquals(0,q.size(),"existing persistent seen prevents revival");
    }
    @Test void retractedAndRevokedAreTerminalNotLearned(@TempDir Path dir) throws Exception {
        for(String reason:List.of("RETRACTED","REVOKED")) {
            entry(dir,A,"v1",reason.equals("RETRACTED"),reason.equals("REVOKED")?"REVOKED":"TRIAL");
            Memo m=memo("v1",reason);MemoQueue q=new MemoQueue(dir.resolve(reason+".json"));q.append(m);
            var c=check(dir,A,q.drain(2));assertEquals(reason,c.target(m.id()).reason());
            assertFalse(c.effective(m.id(),Set.of(m.id())));
            var r=c.settle(q,q.drain(2),Set.of());assertEquals(1,r.terminalSkipped());assertEquals(0,r.committed());assertEquals(0,q.size());
        }
    }
    @Test void everyUnknownReasonRemainsRetryable(@TempDir Path dir) {
        for(String reason:List.of("READ_FAILED","EXPERIENCE_FILE_MISSING","VERSION_UNKNOWN","VERSION_UNVERIFIED","MISSING","BAD_SNAPSHOT","BAD_REF")) {
            Memo m=memo("v1",reason);MemoQueue q=new MemoQueue(dir.resolve(reason+".json"));q.append(m);
            var c=new ReviewCompletion(q.drain(2),x->new ReviewTargetGate.Check(false,reason));
            assertFalse(c.terminal(m.id()));assertFalse(c.effective(m.id(),Set.of(m.id())));
            var r=c.settle(q,q.drain(2),Set.of());assertEquals(0,r.terminalSkipped());assertEquals(0,r.committed());assertEquals(1,q.size());
        }
    }
    @Test void actualReaderFailureAndUnknownFingerprintStayQueued(@TempDir Path dir) throws Exception {
        Memo m=memo("v1","unknown");MemoQueue q=new MemoQueue(dir.resolve("m.json"));q.append(m);
        entry(dir,A,"",false,"TRIAL");var c=check(dir,A,q.drain(2));
        assertEquals("VERSION_UNVERIFIED",c.target(m.id()).reason());c.settle(q,q.drain(2),Set.of());assertEquals(1,q.size());
        Files.writeString(dir.resolve("experience-"+A+".jsonl"),"{\"id\":\"e1\",BROKEN\n");
        c=check(dir,A,q.drain(2));assertEquals("READ_FAILED",c.target(m.id()).reason());c.settle(q,q.drain(2),Set.of());assertEquals(1,q.size());
    }
    @Test void companionFilesAndMixedBatchRemainIndependent(@TempDir Path dir) throws Exception {
        entry(dir,A,"v2",false,"TRIAL");entry(dir,B,"v1",false,"TRIAL");Memo m=memo("v1","same-use");
        MemoQueue qa=new MemoQueue(dir.resolve("a.json")),qb=new MemoQueue(dir.resolve("b.json"));qa.append(m);qb.append(m);
        var ca=check(dir,A,qa.drain(2));var cb=check(dir,B,qb.drain(2));
        assertTrue(ca.terminal(m.id()));assertTrue(cb.permits(m.id()));
        ca.settle(qa,qa.drain(2),Set.of());cb.settle(qb,qb.drain(2),Set.of(m.id()));assertEquals(0,qa.size());assertEquals(0,qb.size());
    }
    @Test void mixedTerminalValidAndUnknownBatchCanAdvance(@TempDir Path dir) {
        Memo old=memo("v1","old"), valid=memo("v2","new"), retry=memo("","unknown");
        MemoQueue q=new MemoQueue(dir.resolve("mixed.json"));for(Memo m:List.of(old,valid,retry))q.append(m);
        var batch=q.drain(10);
        var c=new ReviewCompletion(batch,m->m.id().equals(old.id())?new ReviewTargetGate.Check(false,"VERSION_MISMATCH"):
                m.id().equals(valid.id())?new ReviewTargetGate.Check(true,"OK"):new ReviewTargetGate.Check(false,"READ_FAILED"));
        var r=c.settle(q,batch,Set.of(valid.id()));
        assertEquals(1,r.committed());assertEquals(1,r.terminalSkipped());
        assertEquals(List.of(retry.id()),q.drain(10).stream().map(Memo::id).toList());
    }
    @Test void changedReviewContextRestoresSampleWithoutRegisteringArtifacts(@TempDir Path dir) {
        Memo m = new Memo("delivery-context-race", "review", "skill_delivery", "historical sample",
                "{\"world_id\":\"historical-world\",\"server_session\":\"historic\",\"epoch\":1}", 1L);
        MemoQueue q = new MemoQueue(dir.resolve("delivery-context.json"));
        assertTrue(q.append(m));
        var batch = q.drain(2);
        var start = new com.dwinovo.numen.api.WorldContextStamp.Token(Map.of("world_id", "w", "server_session", "s"), 3);
        var changed = new com.dwinovo.numen.api.WorldContextStamp.Token(Map.of("world_id", "w", "server_session", "s"), 4);
        var completion = new ReviewCompletion(batch, x -> ReviewTargetGate.check(dir, A, x, start, changed));
        AtomicInteger artifacts = new AtomicInteger();
        if (completion.effective(m.id(), Set.of(m.id()))) artifacts.incrementAndGet();
        var settlement = completion.settle(q, batch, Set.of(m.id()));
        assertAll(() -> assertEquals(0, artifacts.get()),
                () -> assertEquals(0, settlement.committed()),
                () -> assertEquals(0, settlement.terminalSkipped()),
                () -> assertEquals(1, q.size()),
                () -> assertEquals(m.snapshot(), q.all().get(0).snapshot()));
    }
    @Test void restartedReviewCanFinishHistoricalSampleWithItsNewStableContext(@TempDir Path dir) throws Exception {
        Memo m = new ReviewTargetGateTest.SkillFixture(dir, A).sample("skill_task_result");
        MemoQueue q = new MemoQueue(dir.resolve("historical-result.json"));
        assertTrue(q.append(m));
        var batch = q.drain(2);
        var start = new com.dwinovo.numen.api.WorldContextStamp.Token(Map.of("world_id", "new-world", "server_session", "new-server"), 9);
        var current = new com.dwinovo.numen.api.WorldContextStamp.Token(Map.of("server_session", "new-server", "world_id", "new-world"), 9);
        var completion = new ReviewCompletion(batch, x -> ReviewTargetGate.check(dir, A, x, start, current));
        assertTrue(completion.effective(m.id(), Set.of(m.id())));
        var settlement = completion.settle(q, batch, Set.of(m.id()));
        assertEquals(1, settlement.committed());
        assertEquals(0, q.size());
    }
    @Test void productionUsesTheOriginalReviewTokenAtTheCommitGate() throws Exception {
        Path cur = Paths.get("").toAbsolutePath(), src = null;
        for (; cur != null; cur = cur.getParent()) {
            Path p = cur.resolve("src/main/java/com/dwinovo/numen/plugins/learner/LearnerReviewTool.java");
            if (Files.isRegularFile(p)) { src = p; break; }
        }
        assertNotNull(src);
        String text = Files.readString(src);
        int start = text.indexOf("final com.dwinovo.numen.api.WorldContextStamp.Token reviewStart");
        int async = text.indexOf("Thread.ofVirtual().name(\"learner-review-");
        assertTrue(start >= 0 && start < async, "source wiring only: original review token captured before async work");
        assertTrue(text.contains("batch, outcome, priorRead, reviewStart)"));
        assertTrue(text.contains("id, memo, reviewStart, reviewCurrent)"));
    }

    @Test void productionCompletePinsSideEffectGateAndRealSettlement() throws Exception {
        Path cur=Paths.get("").toAbsolutePath(),src=null;
        for(;cur!=null;cur=cur.getParent()) {Path p=cur.resolve("src/main/java/com/dwinovo/numen/plugins/learner/LearnerReviewTool.java");if(Files.isRegularFile(p)){src=p;break;}}
        assertNotNull(src);String text=Files.readString(src);
        int consumer=text.indexOf("completion.consume(LearnerPlugin.configDir(), queue, batch, outcome.verdicts(),");
        assertTrue(consumer>=0, "Tool must call the same real consumer exercised below");
        assertTrue(text.contains("for (Verdict v : consumed.verdicts())"));
        String core=Files.readString(src.resolveSibling("ReviewCompletion.java"));
        int gate=core.indexOf("if (!effective(v.memoId(), judged)) continue;");
        assertTrue(gate>=0);assertTrue(core.indexOf("var regReport",gate)>gate,"source pin only: contract/register follows selection");
        assertTrue(core.contains("settle(queue, batch, judged)"));
        assertTrue(text.contains("reviewedEv.put(\"terminal_skipped\", settlement.terminalSkipped())"));
    }

    @Test void skillDeliveryUnknownContextSurvivesReloadAndCommitsOnlyUnderNewStableToken(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("delivery-restart.json");
        Memo m = new ReviewTargetGateTest.SkillFixture(dir, A).sample("skill_delivery");
        MemoQueue first = new MemoQueue(file);
        assertTrue(first.append(m));
        List<Memo> batch = first.drain(2);
        var start = new com.dwinovo.numen.api.WorldContextStamp.Token(Map.of("world_id", "live-world", "server_session", "live-server"), 7);
        var current = new com.dwinovo.numen.api.WorldContextStamp.Token(Map.of("world_id", "live-world", "server_session", "live-server"), 8);
        var completion = new ReviewCompletion(batch, x -> ReviewTargetGate.check(dir, A, x, start, current));
        assertAll(
                () -> assertEquals("REVIEW_CONTEXT_CHANGED", completion.target(m.id()).reason()),
                () -> assertFalse(completion.terminal(m.id()), "changed context is retryable, not terminal"),
                () -> assertFalse(completion.effective(m.id(), Set.of(m.id())), "judged alone must not authorize"));
        var restored = completion.settle(first, batch, Set.of(m.id()));
        assertAll(
                () -> assertEquals(0, restored.committed()),
                () -> assertEquals(0, restored.terminalSkipped()),
                () -> assertEquals(1, restored.restored()),
                () -> assertEquals(1, first.size()));
        MemoQueue reloaded = new MemoQueue(file);
        assertAll(
                () -> assertEquals(1, reloaded.size()),
                () -> assertEquals(m.snapshot(), reloaded.all().get(0).snapshot()),
                () -> assertEquals(m.id(), reloaded.all().get(0).id()));
        List<Memo> second = reloaded.drain(2);
        var stable = new com.dwinovo.numen.api.WorldContextStamp.Token(Map.of("world_id", "live-world", "server_session", "live-server"), 9);
        var again = new ReviewCompletion(second, x -> ReviewTargetGate.check(dir, A, x, stable, stable));
        assertTrue(again.effective(m.id(), Set.of(m.id())));
        var settled = again.settle(reloaded, second, Set.of(m.id()));
        assertAll(
                () -> assertEquals(1, settled.committed()),
                () -> assertEquals(0, settled.terminalSkipped()),
                () -> assertEquals(0, reloaded.size()));
    }


    /** V18 IDs and retrieval shape, not a live replay or evidence that any sample was lost. */
    @Test void v18ThreeMatchedVerdictsReportOnlyTwoDurableRetriesNotQueueDepth(@TempDir Path dir) throws Exception {
        List<Memo> originals = List.of(
                ordinary("tf-craft-d3f42996-tools-1794427275663-0"),
                ordinary("ai-instr-358825343625100-8"),
                ordinary("tf-mine-664867ab-tools-1794428156223-1"));
        MemoQueue queue = fullQueue(dir.resolve("v18.json"), originals);
        List<Memo> before = queue.all(), batch = queue.drain(3);
        List<Verdict> verdicts = List.of(
                retrieved(originals.get(0).id(), "NEW"),
                retrieved(originals.get(1).id(), "REVISE"),
                retrieved(originals.get(2).id(), "NEW"));
        assertEquals(batch.stream().map(Memo::id).toList(), verdicts.stream().map(Verdict::memoId).toList(),
                "all three responses target the actual batch; Z3 rejection is not an ID mismatch");
        ReviewCompletion completion = new ReviewCompletion(batch, m -> new ReviewTargetGate.Check(true, "OK"));
        Set<String> judged = new HashSet<>();
        for (Verdict verdict : verdicts) {
            if (RetrievalGate.problem(verdict) == null && completion.permits(verdict.memoId())) judged.add(verdict.memoId());
        }
        assertEquals(Set.of(originals.get(1).id()), judged, "keep the original Z3 guard");
        var result = completion.settle(queue, batch, judged);
        List<Memo> expected = new ArrayList<>(before);
        expected.remove(originals.get(1));
        assertAll(
                () -> assertEquals(1, result.committed()),
                () -> assertEquals(2, result.restored(), "count only this review's durable retries, not 63 pending memos"),
                () -> assertEquals(0, result.terminalSkipped()),
                () -> assertEquals(63, queue.size()),
                () -> assertEquals(expected, new MemoQueue(queue.file()).all(), "all unrelated/historical memos stay exact"));
    }

    @Test void fullSnapshotQueueReportsThreeExistingRetriesNot64(@TempDir Path dir) {
        List<Memo> originals = List.of(ordinary("a"), ordinary("b"), ordinary("c"));
        MemoQueue queue = fullQueue(dir.resolve("full.json"), originals);
        List<Memo> before = queue.all(), batch = queue.drain(3);
        var result = retryCompletion(batch).settle(queue, batch, Set.of());
        assertAll(
                () -> assertEquals(0, result.committed()),
                () -> assertEquals(3, result.restored(), "already durable snapshot members still count as retry-ready"),
                () -> assertEquals(64, queue.size()),
                () -> assertEquals(before, new MemoQueue(queue.file()).all()));
    }

    /** Controlled public-API counterexample: not a claim of V18 loss during normal snapshot drain. */
    @Test void fullQueueMissingOriginalDoesNotClaimThatRetryWasRestored(@TempDir Path dir) {
        List<Memo> originals = List.of(ordinary("a"), ordinary("b"), ordinary("c"));
        MemoQueue queue = fullQueue(dir.resolve("missing.json"), originals);
        List<Memo> batch = queue.drain(3);
        assertEquals(1, queue.commitDrain(List.of(originals.get(2))));
        assertTrue(queue.append(ordinary("replacement")));
        List<Memo> before = queue.all();
        var result = retryCompletion(batch).settle(queue, batch, Set.of());
        assertAll(
                () -> assertEquals(2, result.restored(), "queue full: the missing original was NOT restored"),
                () -> assertEquals(0, result.committed()),
                () -> assertEquals(64, queue.size()),
                () -> assertEquals(before, new MemoQueue(queue.file()).all()));
    }

    @Test void sameIdDifferentBodyDoesNotCountAsOriginalDurableRetry(@TempDir Path dir) throws Exception {
        List<Memo> originals = List.of(ordinary("a"), ordinary("b"), ordinary("c"));
        MemoQueue queue = fullQueue(dir.resolve("changed.json"), originals);
        List<Memo> batch = queue.drain(3), changed = new ArrayList<>(queue.all());
        changed.set(0, new Memo("a", "different body", "learning", "other tried", "other snapshot", 2L));
        Files.writeString(queue.file(), new Gson().toJson(changed));
        var result = retryCompletion(batch).settle(queue, batch, Set.of());
        assertAll(
                () -> assertEquals(2, result.restored(), "ID occupancy alone is not the original sample"),
                () -> assertEquals(changed, new MemoQueue(queue.file()).all(), "do not overwrite the conflicting record"));
    }

    @Test void duplicateIdDoesNotCountAsExactlyOneOriginalDurableRetry(@TempDir Path dir) throws Exception {
        List<Memo> originals = List.of(ordinary("a"), ordinary("b"), ordinary("c"));
        MemoQueue queue = fullQueue(dir.resolve("duplicate.json"), originals);
        List<Memo> batch = queue.drain(3), duplicated = new ArrayList<>(queue.all());
        duplicated.set(3, originals.get(0));
        Files.writeString(queue.file(), new Gson().toJson(duplicated));
        var result = retryCompletion(batch).settle(queue, batch, Set.of());
        assertAll(
                () -> assertEquals(2, result.restored(), "ambiguous duplicate identity is not one durable original"),
                () -> assertEquals(duplicated, new MemoQueue(queue.file()).all(), "do not repair history as a side effect"));
    }

    @Test void actualRestoreWriteFailureCannotProduceSuccessReceiptOrDiscardClaim(@TempDir Path dir) throws Exception {
        List<Memo> batch = List.of(ordinary("claimed-a"), ordinary("claimed-b"), ordinary("claimed-c"));
        String reviewId = "rev-write-failure";
        assertTrue(ReviewBatchStore.save(dir, reviewId, batch));
        Path claim = ReviewBatchStore.file(dir, reviewId);
        byte[] claimedBytes = Files.readAllBytes(claim);
        Path blocker = dir.resolve("blocked-parent");
        Files.writeString(blocker, "not a directory");
        MemoQueue queue = new MemoQueue(blocker.resolve("memos.json"));
        assertThrows(IllegalStateException.class, () -> retryCompletion(batch).settle(queue, batch, Set.of()));
        assertAll(
                () -> assertEquals("not a directory", Files.readString(blocker)),
                () -> assertArrayEquals(claimedBytes, Files.readAllBytes(claim)),
                () -> assertEquals(batch, ReviewBatchStore.load(dir, reviewId)));
    }

    private static Memo ordinary(String id) {
        return new Memo(id, "historical problem " + id, "learning", "original tried", "original snapshot", 1L);
    }

    private static MemoQueue fullQueue(Path file, List<Memo> originals) {
        MemoQueue queue = new MemoQueue(file);
        for (Memo memo : originals) assertTrue(queue.append(memo));
        for (int i = originals.size(); i < MemoQueue.MAX_QUEUE; i++) assertTrue(queue.append(ordinary("outside-review-" + i)));
        assertEquals(64, queue.size());
        return queue;
    }

    private static ReviewCompletion retryCompletion(List<Memo> batch) {
        return new ReviewCompletion(batch, m -> new ReviewTargetGate.Check(true, "OK"));
    }

    private static Verdict retrieved(String id, String decision) {
        Verdict verdict = Verdict.parse("?", "{\"memo_id\":\"" + id + "\",\"actions\":[\"WRITE_EXPERIENCE\"],"
                + "\"confidence\":0.7,\"reasoning\":\"V18 retrieval shape\",\"retrieval\":{\"decision\":\"" + decision
                + "\",\"reason\":\"recorded high-similarity hit\",\"hits\":[{\"entry_ref\":\"exp:existing\","
                + "\"title\":\"existing entry\",\"why\":\"retrieved\",\"high_similarity\":true}]}}");
        assertNotNull(verdict);
        return verdict;
    }

    @Test void skillTaskResultMissingContextSurvivesReloadAndCommitsUnderStableToken(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("result-restart.json");
        Memo m = new ReviewTargetGateTest.SkillFixture(dir, A).sample("skill_task_result");
        MemoQueue first = new MemoQueue(file);
        assertTrue(first.append(m));
        List<Memo> batch = first.drain(2);
        var incomplete = new com.dwinovo.numen.api.WorldContextStamp.Token(Map.of("world_id", "live-world"), 5);
        assertAll(
                () -> assertEquals("REVIEW_CONTEXT_UNKNOWN", ReviewTargetGate.check(dir, A, m, incomplete, incomplete).reason()),
                () -> assertEquals("REVIEW_CONTEXT_UNKNOWN", ReviewTargetGate.check(dir, A, m, null, incomplete).reason()),
                () -> assertFalse(ReviewTargetGate.check(dir, A, m, incomplete, incomplete).terminal()));
        var completion = new ReviewCompletion(batch, x -> ReviewTargetGate.check(dir, A, x, incomplete, incomplete));
        assertFalse(completion.effective(m.id(), Set.of(m.id())));
        var restored = completion.settle(first, batch, Set.of(m.id()));
        assertAll(
                () -> assertEquals(0, restored.committed()),
                () -> assertEquals(0, restored.terminalSkipped()),
                () -> assertEquals(1, restored.restored()),
                () -> assertEquals(1, first.size()));
        MemoQueue reloaded = new MemoQueue(file);
        assertAll(
                () -> assertEquals(1, reloaded.size()),
                () -> assertEquals(m.snapshot(), reloaded.all().get(0).snapshot()));
        List<Memo> second = reloaded.drain(2);
        var stable = new com.dwinovo.numen.api.WorldContextStamp.Token(Map.of("world_id", "live-world", "server_session", "live-server"), 5);
        var again = new ReviewCompletion(second, x -> ReviewTargetGate.check(dir, A, x, stable, stable));
        assertTrue(again.effective(m.id(), Set.of(m.id())));
        var settled = again.settle(reloaded, second, Set.of(m.id()));
        assertAll(
                () -> assertEquals(1, settled.committed()),
                () -> assertEquals(0, settled.terminalSkipped()),
                () -> assertEquals(0, reloaded.size()));
    }

    /** Late-callback tests use the existing journal fixture: no live state or un-restored globals. */
    @Test void abortedStaleCallbackKeepsInterruptedTerminalAndOneRestore(@TempDir Path dir) throws Exception {
        try (var f = new LearnerMonitorJournalTest.Fixture(dir)) {
            MemoQueue queue = new MemoQueue(dir.resolve("late.json"));
            assertTrue(queue.append(ordinary("late-callback")));
            List<Memo> batch = queue.drain(1);
            String id = "rev-late-aborted";
            LearnerPlugin.beginReview(id, f.companion, "RUNNING", 1);
            AtomicInteger restores = new AtomicInteger(), publishes = new AtomicInteger();
            LearnerPlugin.registerInflight(id, () -> { restores.incrementAndGet(); queue.restore(batch); });
            assertEquals(1, LearnerPlugin.abortInFlight());
            assertEquals("INTERRUPTED", LearnerPlugin.review(id).state());
            assertTrue(ReviewCompletion.discardLate(LearnerPlugin.wasAborted(id), false, () -> {
                restores.incrementAndGet();
                queue.restore(batch);
                LearnerPlugin.finishReview(id, "STALE_WORLD", 0);
                publishes.incrementAndGet();
            }));
            assertAll(() -> assertEquals(1, restores.get(), "shutdown already restored exactly once"),
                    () -> assertEquals(0, publishes.get(), "no stale publication after abort"),
                    () -> assertEquals("INTERRUPTED", LearnerPlugin.review(id).state()),
                    () -> assertEquals(batch, new MemoQueue(queue.file()).all()));
        }
    }

    @Test void unabortedStaleCallbackStillRestoresAndFinishesOnce(@TempDir Path dir) throws Exception {
        try (var f = new LearnerMonitorJournalTest.Fixture(dir)) {
            MemoQueue queue = new MemoQueue(dir.resolve("stale.json"));
            assertTrue(queue.append(ordinary("genuine-stale")));
            List<Memo> batch = queue.drain(1);
            String id = "rev-late-stale";
            LearnerPlugin.beginReview(id, f.companion, "RUNNING", 1);
            AtomicInteger restores = new AtomicInteger(), publishes = new AtomicInteger();
            assertTrue(ReviewCompletion.discardLate(false, false, () -> {
                restores.incrementAndGet();
                queue.restore(batch);
                LearnerPlugin.finishReview(id, "STALE_WORLD", 0);
                publishes.incrementAndGet();
            }));
            assertAll(() -> assertEquals(1, restores.get()), () -> assertEquals(1, publishes.get()),
                    () -> assertEquals("STALE_WORLD", LearnerPlugin.review(id).state()),
                    () -> assertEquals(batch, new MemoQueue(queue.file()).all()));
        }
    }

    @Test void currentUnabortedCallbackContinuesWithoutStaleSideEffects() {
        AtomicInteger staleCalls = new AtomicInteger();
        assertFalse(ReviewCompletion.discardLate(false, true, staleCalls::incrementAndGet));
        assertEquals(0, staleCalls.get());
    }

    @Test void abortedCurrentCallbackDoesNotEnqueueMoreServerWork() {
        AtomicInteger staleCalls = new AtomicInteger();
        assertTrue(ReviewCompletion.discardLate(true, true, staleCalls::incrementAndGet));
        assertEquals(0, staleCalls.get());
    }

    @Test void failedAbortRestoreKeepsDurableBatchAndDanglingTerminal(@TempDir Path dir) throws Exception {
        try (var f = new LearnerMonitorJournalTest.Fixture(dir)) {
            String id = "rev-late-abort-failed";
            MemoQueue queue = new MemoQueue(dir.resolve("failed-abort.json"));
            assertTrue(queue.append(ordinary("late-abort-failed")));
            List<Memo> batch = queue.drain(1);
            LearnerPlugin.beginReview(id, f.companion, "RUNNING", 1);
            assertTrue(ReviewBatchStore.save(dir, id, batch));
            LearnerPlugin.registerInflight(id, () -> { throw new IllegalStateException("fixture restore failed"); });
            assertEquals(1, LearnerPlugin.abortInFlight());
            assertTrue(LearnerPlugin.wasAborted(id));
            assertEquals("RUNNING", LearnerPlugin.review(id).state());
            AtomicInteger staleCalls = new AtomicInteger();
            assertTrue(ReviewCompletion.discardLate(LearnerPlugin.wasAborted(id), false, () -> {
                staleCalls.incrementAndGet();
                queue.restore(batch);
                LearnerPlugin.finishReview(id, "STALE_WORLD", 0);
            }));
            assertAll(() -> assertEquals(0, staleCalls.get()),
                    () -> assertEquals("RUNNING", LearnerPlugin.review(id).state()),
                    () -> assertEquals(batch, ReviewBatchStore.load(dir, id)),
                    () -> assertTrue(Files.exists(dir.resolve(LearnerPlugin.REVIEW_SENTINEL))));
        }
    }

    @AfterEach void clearSequentialRegistry() { SkillRegistrar.resetForTest(); }

    private static final String EVENT = "tools-sequential-1-0";
    private static final WorldContextStamp.Token STABLE = new WorldContextStamp.Token(
            Map.of("world_id", "review-world", "server_session", "review-server"), 9);
    private static Verdict write(String memoId, String mechanism, String body) {
        Experience e = new Experience(mechanism, "current target needed", "target unavailable",
                "original task result", body, "avoid repeating the old attempt", EVENT, "FAILURE", List.of("EXECUTOR"));
        return new Verdict(memoId, List.of(Verdict.Action.WRITE_EXPERIENCE), .8, "original event supports this revision",
                e, "", "", "", "", List.of(), List.of(), new RetrievalDecision(
                List.of(new RetrievalDecision.Hit("skill:retrieved", mechanism, "same method", true, "TRIAL")),
                RetrievalDecision.Kind.REVISE, "same metadata, revised derivation only"));
    }
    private static Verdict noAction(Memo memo) {
        return new Verdict(memo.id(), List.of(Verdict.Action.NO_ACTION), .8, "historical task result only",
                null, "", "", "", List.of(), List.of());
    }
    private static Submission.Draft adapted(Verdict v) {
        return ReviewContractAdapter.toSubmission(v, v.memoId(), Submission.Mode.INTERNAL, "", List.of(EVENT));
    }
    private static ReviewTargetGateTest.SkillFixture seeded(Path dir, String mechanism) throws Exception {
        Files.createDirectories(dir.resolve("monitor"));
        Files.writeString(dir.resolve("monitor/tools.jsonl"), "{\"event_id\":\"" + EVENT + "\",\"event\":\"tool_result\"}\n");
        assertTrue(EvidenceJournal.unresolved(dir.resolve("monitor"), List.of(EVENT)).isEmpty());
        var draft = adapted(write("seed-" + mechanism, mechanism, "original derivation A"));
        var accepted = ReviewContractGate.evaluate(ReviewContractGate.Mode.SHADOW, draft, null);
        assertTrue(accepted.result().accepted(), accepted.codes().toString());
        return new ReviewTargetGateTest.SkillFixture(dir, A, draft);
    }
    private static Memo ordinaryWithEvidence(String id) {
        return new Memo(id, "body revision", "learning", "revised derivation only", "", 1L, "learning", List.of(EVENT));
    }
    private static Memo withOriginalTaskEvidence(ReviewTargetGateTest.SkillFixture fixture) throws Exception {
        Memo sample = fixture.sample("skill_task_result", true);
        try (AsyncJournalFixture journal = new AsyncJournalFixture(fixture.dir)) {
            OriginalAsyncJournalProducer.publish(fixture.owner, fixture.finished);
            journal.barrier();
            Memo original = SkillDeliveryIntake.withTaskResultEvidence(fixture.dir, fixture.owner, sample);
            assertEquals(1, original.evidence().size(), "self-revision requires the original task's persisted event");
            assertTrue(EvidenceJournal.unresolved(fixture.dir.resolve("monitor"), original.evidence()).isEmpty());
            return original;
        }
    }
    private static MemoQueue pending(Path dir, Memo... memos) {
        MemoQueue q = new MemoQueue(dir.resolve("sequential-review.json"));
        for (Memo memo : memos) assertTrue(q.append(memo));
        return q;
    }
    private static ReviewCompletion current(Path dir, List<Memo> batch) {
        var c = new ReviewCompletion(batch, m -> ReviewTargetGate.check(dir, A, m, STABLE, STABLE));
        for (Memo memo : batch) assertTrue(c.permits(memo.id()), "fixture must start with all current gates OK");
        return c;
    }
    private static ReviewCompletion.Consumption consume(ReviewCompletion c, Path dir, MemoQueue q,
                                                       List<Memo> batch, Verdict... verdicts) {
        return c.consume(dir, q, batch, List.of(verdicts), (event, data) -> {});
    }
    private static void restoredOriginal(MemoQueue q, Memo original) {
        assertEquals(List.of(original), new MemoQueue(q.file()).all(), "durable retry keeps the exact historical sample");
    }
    private static void assertRevision(ReviewTargetGateTest.SkillFixture f, int version, String body) throws Exception {
        var entry = SkillRegistry.open(f.dir.resolve("skills.jsonl")).get(f.skill).orElseThrow();
        assertEquals(version, entry.version());
        assertEquals(SkillStatus.TRIAL, entry.status());
        assertEquals(adapted(write("ignored", entry.purpose(), body)).artifacts().getFirst().body(),
                SkillBodyStore.read(f.dir, entry.bodyRef()));
    }
    private static void laterTarget(Path dir, String stage, String mode) throws Exception {
        var f = seeded(dir, "sequential target");
        Memo first = ordinaryWithEvidence("revision-first"), later = f.sample(stage);
        if (mode != null) Files.writeString(dir.resolve(ReviewContractGate.FILE_NAME), "{\"mode\":\"" + mode + "\"}");
        MemoQueue q = pending(dir, first, later);
        var batch = q.drain(3);
        var c = current(dir, batch);
        Verdict revision = write(first.id(), "sequential target", "revised derivation B");
        assertEquals(f.registry.get(f.skill).orElseThrow().fingerprint(), adapted(revision).artifacts().getFirst().fingerprint());
        var done = consume(c, dir, q, batch, revision, noAction(later));
        assertRevision(f, 2, "revised derivation B"); // first really revised current K, not a fake lifecycle writer
        assertAll(() -> assertEquals("SKILL_VERSION_CHANGED", c.target(later.id()).reason()),
                () -> assertFalse(c.terminal(later.id())),
                () -> assertFalse(done.judged().contains(later.id())),
                () -> assertEquals(List.of(first.id()), done.verdicts().stream().map(Verdict::memoId).toList()),
                () -> assertEquals(1, done.contractRows().size()),
                () -> assertEquals(1, done.settlement().committed()),
                () -> assertEquals(0, done.settlement().terminalSkipped()),
                () -> restoredOriginal(q, later));
    }

    @Test void realEarlierRegistrationInvalidatesLaterResultInDefaultShadow(@TempDir Path dir) throws Exception {
        laterTarget(dir, "skill_task_result", null);
    }
    @Test void realEarlierRegistrationInvalidatesLaterResultInEnforce(@TempDir Path dir) throws Exception {
        laterTarget(dir, "skill_task_result", "enforce");
    }
    @Test void realEarlierRegistrationInvalidatesLaterDelivery(@TempDir Path dir) throws Exception {
        laterTarget(dir, "skill_delivery", null);
    }
    @Test void offStillAuthenticatesCurrentTargetBeforeNoAction(@TempDir Path dir) throws Exception {
        var f = seeded(dir, "off target");
        Memo sample = f.sample("skill_task_result");
        MemoQueue q = pending(dir, sample); var batch = q.drain(2); var c = current(dir, batch);
        Files.writeString(dir.resolve(ReviewContractGate.FILE_NAME), "{\"mode\":\"off\"}");
        var registered = SkillRegistrar.register(dir, adapted(write("revision-off", "off target", "new off body B")), true);
        assertEquals(1, registered.revised());
        var done = consume(c, dir, q, batch, noAction(sample));
        assertAll(() -> assertEquals("SKILL_VERSION_CHANGED", c.target(sample.id()).reason()),
                () -> assertTrue(done.judged().isEmpty()), () -> assertTrue(done.verdicts().isEmpty()),
                () -> assertEquals(0, done.settlement().committed()), () -> restoredOriginal(q, sample));
    }
    @Test void revisedSkillFromActualCompletionCanBeSelectedAndReturnsItsOwnReceiptForReview(@TempDir Path dir) throws Exception {
        var fixture = seeded(dir, "adoption return path");
        Memo original = ordinaryWithEvidence("original-learning-memo");
        MemoQueue originalQueue = pending(dir, original);
        var batch = originalQueue.drain(3);
        var completed = consume(current(dir, batch), dir, originalQueue, batch,
                write(original.id(), "adoption return path", "new derivation adopted later"));
        assertEquals(1, completed.settlement().committed());
        var selected = SkillRegistry.open(dir.resolve("skills.jsonl")).get(fixture.skill).orElseThrow();
        assertEquals(2, selected.version());
        assertTrue(selected.foundingSamples().contains(original.id()), "new artifact retains its original learning memo");
        String originalBody = SkillBodyStore.read(dir, selected.bodyRef());
        UsageLedger ledger = new UsageLedger(dir);
        LearnerUsageTool.TaskAccess host = new LearnerUsageTool.TaskAccess() {
            public UUID ownerId() { return B; }
            public UUID companionId() { return A; }
            public WorldContextStamp.Token identity() { return STABLE; }
            public com.dwinovo.numen.task.TaskRecord current() { return null; }
            public com.dwinovo.numen.task.TaskRecord finished(String id) { return null; }
        };
        var tool = new LearnerUsageTool(dir, ledger, host);
        var args = new com.google.gson.JsonObject();
        args.addProperty("action", "adopt_sync_skill"); args.addProperty("skill_ref", "skill:" + selected.skillId());
        args.addProperty("version", "v2"); args.addProperty("target_tool", "craft");
        AtomicReference<String> reply = new AtomicReference<>();
        tool.onServerCall("actual-revised-adoption", args, null, reply::set);
        var response = com.google.gson.JsonParser.parseString(reply.get()).getAsJsonObject();
        assertTrue(response.get("success").getAsBoolean(), response.toString());
        assertEquals(originalBody, response.getAsJsonObject("data").get("body").getAsString());
        String id = response.getAsJsonObject("data").get("selection_id").getAsString();
        var correlation = SyncSkillFeedback.INSTANCE;
        assertEquals(com.dwinovo.numen.api.SyncSkillCorrelation.Binding.BOUND,
                correlation.bind(id, B, A, STABLE, "adopted-revision-wire", "craft"));
        correlation.receipt(id, B, A, STABLE, "adopted-revision-wire", "craft", com.dwinovo.numen.api.SyncSkillCorrelation.Receipt.SUCCESS);
        assertEquals(1, ledger.all().size());
        var receipt = ledger.all().getFirst();
        assertEquals(UsageLedger.EvidenceLevel.NONE, receipt.evidence());
        var fact = new SyncSkillFeedback().confirm(new UsageLedger(dir), receipt);
        assertNotNull(fact);
        assertEquals("v2", fact.version()); assertEquals(selected.bodyRef(), fact.bodyRef());
        MemoQueue reviewQueue = new MemoQueue(dir.resolve("adopted-revision-review.json"));
        Set<String> seen = new HashSet<>();
        var journal = new SkillDeliveryIntake.EnqueuedJournal() {
            public boolean contains(String key) { return seen.contains(key); }
            public void remember(String key) { seen.add(key); }
        };
        var scope = new SkillDeliveryIntake.Scope(STABLE.identity().get("world_id"), "later-review", 10, A.toString());
        assertEquals(1, SkillDeliveryIntake.runWithTaskResults(ledger, ledger.all(), scope, reviewQueue, journal,
                (c, f, n, once) -> once.getAsBoolean(), 1, 16).enqueued());
        Memo returned = reviewQueue.all().getFirst();
        assertEquals(receipt, SkillDeliveryIntake.syncEntryFromSnapshot(returned.snapshot()));
        String input = TrajectorySlices.forMemo(dir, A, returned);
        assertTrue(input.contains(originalBody)); assertTrue(input.contains("adopted-revision-wire"));
        assertTrue(input.contains("evidence=NONE"));
        assertTrue(ReviewTargetGate.check(dir, A, returned, STABLE, STABLE).ok());
        assertTrue(SkillRegistry.open(dir.resolve("skills.jsonl")).get(selected.skillId()).orElseThrow().foundingSamples().contains(original.id()));
    }

    @Test void aCurrentSampleMayLegallyReviseItsOwnTargetExactlyOnce(@TempDir Path dir) throws Exception {
        var f = seeded(dir, "self revision");
        Memo sample = withOriginalTaskEvidence(f);
        MemoQueue q = pending(dir, sample); var batch = q.drain(2); var c = current(dir, batch);
        var done = consume(c, dir, q, batch, write(sample.id(), "self revision", "own legal body B"));
        assertRevision(f, 2, "own legal body B");
        assertAll(() -> assertEquals(1, done.settlement().committed()), () -> assertEquals(0, q.size()),
                () -> assertEquals(List.of(sample.id()), done.verdicts().stream().map(Verdict::memoId).toList()));
    }
    @Test void aLaterRevisionMustNotRetroactivelyRestoreAnAlreadyConsumedSample(@TempDir Path dir) throws Exception {
        var f = seeded(dir, "non retroactive target");
        Memo consumed = f.sample("skill_task_result"), revision = ordinaryWithEvidence("middle-revision"), later = f.sample("skill_task_result");
        assertNotEquals(consumed.id(), later.id());
        MemoQueue q = pending(dir, consumed, revision, later); var batch = q.drain(3); var c = current(dir, batch);
        var done = consume(c, dir, q, batch, noAction(consumed), write(revision.id(), "non retroactive target", "middle body B"), noAction(later));
        assertRevision(f, 2, "middle body B");
        assertAll(() -> assertEquals(2, done.settlement().committed()),
                () -> assertEquals(Set.of(consumed.id(), revision.id()), done.judged()),
                () -> assertEquals("SKILL_VERSION_CHANGED", c.target(later.id()).reason()),
                () -> restoredOriginal(q, later));
    }
    @Test void revisingOneSkillDoesNotBlockAnotherSkillsOriginalResult(@TempDir Path dir) throws Exception {
        var first = seeded(dir, "independent first"); var other = seeded(dir, "independent second");
        Memo revision = ordinaryWithEvidence("independent-revision"), result = other.sample("skill_task_result");
        assertNotEquals(first.skill, other.skill);
        MemoQueue q = pending(dir, revision, result); var batch = q.drain(3); var c = current(dir, batch);
        var done = consume(c, dir, q, batch, write(revision.id(), "independent first", "independent body B"), noAction(result));
        assertRevision(first, 2, "independent body B");
        assertAll(() -> assertEquals(2, done.settlement().committed()), () -> assertEquals(0, q.size()),
                () -> assertTrue(done.judged().contains(result.id())),
                () -> assertEquals(1, SkillRegistry.open(dir.resolve("skills.jsonl")).get(other.skill).orElseThrow().version()));
    }
    @Test void registryReadFailureAfterInitialCheckRestoresInsteadOfUsingCachedOk(@TempDir Path dir) throws Exception {
        var f = seeded(dir, "registry read target"); Memo sample = f.sample("skill_task_result");
        MemoQueue q = pending(dir, sample); var batch = q.drain(2); var c = current(dir, batch);
        Path registry = dir.resolve("skills.jsonl"); Files.delete(registry); Files.createDirectory(registry);
        var done = consume(c, dir, q, batch, noAction(sample));
        assertAll(() -> assertEquals("SKILL_REGISTRY_UNKNOWN", c.target(sample.id()).reason()),
                () -> assertTrue(done.judged().isEmpty()), () -> assertEquals(0, done.settlement().committed()),
                () -> assertEquals(0, done.settlement().terminalSkipped()), () -> restoredOriginal(q, sample));
    }
    @Test void failedBodyWriteDoesNotPretendTheEarlierSkillWasRevised(@TempDir Path dir) throws Exception {
        var f = seeded(dir, "body failure target"); Memo first = ordinaryWithEvidence("write-fails"), result = f.sample("skill_task_result");
        Verdict revision = write(first.id(), "body failure target", "blocked body B"); var artifact = adapted(revision).artifacts().getFirst();
        Files.createDirectory(dir.resolve("skill-bodies").resolve(f.skill + "@v1-" + artifact.contentHash() + ".md.tmp"));
        MemoQueue q = pending(dir, first, result); var batch = q.drain(3); var c = current(dir, batch);
        var done = consume(c, dir, q, batch, revision, noAction(result));
        Map<?, ?> report = (Map<?, ?>) done.contractRows().getFirst().get("skills");
        assertAll(() -> assertEquals("body_missing=1", report.get("error")),
                () -> assertEquals(0, report.get("revised")), () -> assertEquals(2, done.settlement().committed()),
                () -> assertEquals(0, q.size()),
                () -> assertEquals(1, SkillRegistry.open(dir.resolve("skills.jsonl")).get(f.skill).orElseThrow().version()));
    }
    @Test void duplicateVerdictCannotReplayOrInvalidateTheFirstSuccessfulSelfRevision(@TempDir Path dir) throws Exception {
        var f = seeded(dir, "duplicate target"); Memo sample = withOriginalTaskEvidence(f);
        MemoQueue q = pending(dir, sample); var batch = q.drain(2); var c = current(dir, batch);
        Verdict v = write(sample.id(), "duplicate target", "single body B");
        var done = consume(c, dir, q, batch, v, v);
        assertRevision(f, 2, "single body B");
        assertAll(() -> assertEquals(1, done.contractRows().size(), "one real side-effect attempt per memo"),
                () -> assertEquals(1, done.verdicts().size(), "Tool must not replay duplicate delivery/rendering"),
                () -> assertEquals(1, done.settlement().committed()), () -> assertEquals(0, q.size()));
    }
    @Test void theOriginalReviewTokenStillGatesEachNotYetConsumedVerdict(@TempDir Path dir) throws Exception {
        var f = seeded(dir, "callback target"); Memo sample = f.sample("skill_task_result");
        MemoQueue q = pending(dir, sample); var batch = q.drain(2);
        var now = new AtomicReference<>(STABLE);
        var c = new ReviewCompletion(batch, m -> ReviewTargetGate.check(dir, A, m, STABLE, now.get()));
        assertTrue(c.permits(sample.id()));
        now.set(new WorldContextStamp.Token(STABLE.identity(), STABLE.epoch() + 1));
        var done = consume(c, dir, q, batch, noAction(sample));
        assertAll(() -> assertEquals("REVIEW_CONTEXT_CHANGED", c.target(sample.id()).reason()),
                () -> assertTrue(done.judged().isEmpty()), () -> assertEquals(0, done.settlement().committed()),
                () -> restoredOriginal(q, sample));
    }
}
