package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.plugins.learner.core.Memo;
import com.dwinovo.numen.plugins.learner.core.MemoQueue;
import com.dwinovo.numen.plugins.learner.core.ReviewBatchStore;
import com.dwinovo.numen.plugins.learner.core.RetrievalGate;
import com.dwinovo.numen.plugins.learner.core.Verdict;
import com.google.gson.Gson;
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
    @Test void restartedReviewCanFinishHistoricalSampleWithItsNewStableContext(@TempDir Path dir) {
        Memo m = new Memo("historical-result", "review", "skill_task_result", "historical fact, not a live command",
                "{\"world_id\":\"old-world\",\"server_session\":\"old-server\",\"epoch\":1}", 1L);
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
        int gate=text.indexOf("if (!completion.effective(v.memoId(), judged)) continue;");
        assertTrue(gate>=0);assertTrue(text.indexOf("var regReport",gate)>gate,"source pin only: contract/register follows selection");
        assertTrue(text.contains("completion.settle(queue, batch, judged)"));
        assertTrue(text.contains("reviewedEv.put(\"terminal_skipped\", settlement.terminalSkipped())"));
    }

    @Test void skillDeliveryUnknownContextSurvivesReloadAndCommitsOnlyUnderNewStableToken(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("delivery-restart.json");
        Memo m = new Memo("delivery-restart", "review", "skill_delivery", "historical delivery sample",
                "{\"world_id\":\"old-world\",\"server_session\":\"old-server\",\"epoch\":1,\"request_id\":\"94ef928e\"}", 11L);
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
        Memo m = new Memo("result-restart", "review", "skill_task_result", "historical result sample",
                "{\"world_id\":\"old-world\",\"server_session\":\"old-server\",\"epoch\":1,\"result_id\":\"r1\"}", 12L);
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
}
