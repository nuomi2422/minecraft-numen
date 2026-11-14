package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.api.WorldContextStamp;
import com.dwinovo.numen.plugins.learner.contract.ArtifactDraft;
import com.dwinovo.numen.plugins.learner.contract.ArtifactKind;
import com.dwinovo.numen.plugins.learner.core.*;
import com.dwinovo.numen.plugins.learner.skills.*;
import com.dwinovo.numen.task.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-08 C1 review-result commit gate. */
class ReviewTargetGateTest {
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static Memo memo(String ref, String version) {
        return new Memo("m", "p", "EXPERIENCE_EXPAND", "t", "ref=" + ref + " version=" + version, 1L, "learning", List.of());
    }

    private static com.dwinovo.numen.api.WorldContextStamp.Token reviewToken(String world, String server, long epoch) {
        return new com.dwinovo.numen.api.WorldContextStamp.Token(
                java.util.Map.of("world_id", world, "server_session", server), epoch);
    }
    private static Memo skillMemo(String stage) {
        // A durable sample from a previous session is historical evidence, not the active review token.
        return new Memo("historical-" + stage, "review", stage, "historical sample, not proof of validity",
                "{\"world_id\":\"historical-world\",\"server_session\":\"old-server\",\"epoch\":1}", 1L);
    }
    @Test void historicalSkillSampleCanBeReviewedInANewStableReviewContext(@TempDir Path dir) throws Exception {
        SkillFixture fixture = new SkillFixture(dir, C);
        Memo delivery = fixture.sample("skill_delivery"), result = fixture.sample("skill_task_result");
        var start = reviewToken("live-current", "server-current", 8L);
        var current = reviewToken("live-current", "server-current", 8L);
        assertAll(
                () -> assertTrue(ReviewTargetGate.check(dir, C, delivery, start, current).ok()),
                () -> assertTrue(ReviewTargetGate.check(dir, C, result, start, current).ok()));
    }
    @Test void changedReviewEpochCannotCommitDeliveryVerdict() {
        var check = ReviewTargetGate.check(null, C, skillMemo("skill_delivery"),
                reviewToken("w", "s", 1), reviewToken("w", "s", 2));
        assertAll(() -> assertFalse(check.ok()), () -> assertFalse(check.terminal()));
    }
    @Test void changedReviewServerCannotCommitResultVerdict() {
        var check = ReviewTargetGate.check(null, C, skillMemo("skill_task_result"),
                reviewToken("w", "server-a", 1), reviewToken("w", "server-b", 1));
        assertAll(() -> assertFalse(check.ok()), () -> assertFalse(check.terminal()));
    }
    @Test void changedReviewWorldCannotCommitSkillVerdict() {
        var check = ReviewTargetGate.check(null, C, skillMemo("skill_delivery"),
                reviewToken("world-a", "s", 1), reviewToken("world-b", "s", 1));
        assertAll(() -> assertFalse(check.ok()), () -> assertFalse(check.terminal()));
    }
    @Test void unknownReviewContextCannotBecomePermission() {
        var good = reviewToken("w", "s", 1);
        var unknown = new com.dwinovo.numen.api.WorldContextStamp.Token(null, 0);
        assertAll(
                () -> assertFalse(ReviewTargetGate.check(null, C, skillMemo("skill_delivery"), null, good).ok()),
                () -> assertFalse(ReviewTargetGate.check(null, C, skillMemo("skill_delivery"), good, unknown).ok()),
                () -> assertFalse(ReviewTargetGate.check(null, C, skillMemo("skill_delivery"), reviewToken("", "s", 1), good).ok()),
                () -> assertFalse(ReviewTargetGate.check(null, C, skillMemo("skill_task_result"), good, reviewToken("w", "s", 0)).ok()));
    }
    @Test void legacyEntryCannotAuthorizeSkillReviewWithoutContext() {
        assertAll(
                () -> assertFalse(ReviewTargetGate.check(null, C, skillMemo("skill_delivery")).ok()),
                () -> assertFalse(ReviewTargetGate.check(null, C, skillMemo("skill_task_result")).ok()));
    }
    @Test void unrelatedLegacyMemoKeepsItsOriginalMeaning() {
        Memo ordinary = new Memo("ordinary", "old", "learning", "old sample", "not a skill delivery", 1L);
        assertTrue(ReviewTargetGate.check(null, C, ordinary, null, null).ok());
    }


    @AfterEach void clearFixtureRegistry() { SkillRegistrar.resetForTest(); }

    @Test void missingRegistryCannotAuthorizeStableDeliveryAndRestorationKeepsOriginal(@TempDir Path dir) throws Exception {
        SkillFixture f = new SkillFixture(dir, C);
        Memo sample = f.sample("skill_delivery");
        Path file = dir.resolve("skills.jsonl");
        byte[] original = Files.readAllBytes(file);
        Files.delete(file);
        assertRetry(dir, C, sample, "SKILL_REGISTRY_UNKNOWN");
        Files.write(file, original);
        assertTrue(stableCheck(dir, C, new MemoQueue(dir.resolve("review.json")).all().get(0)).ok(),
                "same durable sample may advance after the original target is readable again");
    }

    @Test void revokedCurrentSkillDoesNotDeleteHistoricalDeliveryOrResult(@TempDir Path dir) throws Exception {
        SkillFixture f = new SkillFixture(dir, C);
        Memo delivery = f.sample("skill_delivery"), result = f.sample("skill_task_result");
        assertTrue(f.registry.retire(SkillFixture.SKILL, "controlled lifecycle counterexample").stored());
        byte[] original = Files.readAllBytes(dir.resolve("skills.jsonl"));
        assertAll(
                () -> assertRetry(dir.resolve("delivery"), dir, C, delivery, "SKILL_REVOKED"),
                () -> assertRetry(dir.resolve("result"), dir, C, result, "SKILL_REVOKED"),
                () -> assertArrayEquals(original, Files.readAllBytes(dir.resolve("skills.jsonl"))));
    }

    @Test void changedCurrentVersionCannotAuthorizeOldResultButFreshVersionCanAdvance(@TempDir Path dir) throws Exception {
        SkillFixture f = new SkillFixture(dir, C);
        Memo old = f.sample("skill_task_result");
        f.revise("new original body without coordinates");
        assertRetry(dir, C, old, "SKILL_VERSION_CHANGED");
        Memo fresh = f.sample("skill_delivery");
        assertTrue(stableCheck(dir, C, fresh).ok(), "new version is independent of the older pending fact");
    }

    @Test void suspendedAndDraftTargetsAreNotCurrentPermission(@TempDir Path dir) throws Exception {
        for (String status : List.of("SUSPENDED", "DRAFT")) {
            Path sub = Files.createDirectories(dir.resolve(status));
            SkillFixture f = new SkillFixture(sub, C);
            Memo sample = f.sample("skill_delivery");
            JsonObject row = JsonParser.parseString(Files.readString(sub.resolve("skills.jsonl"))).getAsJsonObject();
            row.addProperty("status", status); // Controlled disk lifecycle snapshot, not a new production writer.
            Files.writeString(sub.resolve("skills.jsonl"), row + "\n");
            assertRetry(sub, C, sample, "SKILL_NOT_RETRIEVABLE");
        }
    }

    @Test void missingBodyRemainsRetryableAndOriginalBodyRepairEnablesSameSample(@TempDir Path dir) throws Exception {
        SkillFixture f = new SkillFixture(dir, C);
        Memo result = f.sample("skill_task_result");
        Path body = f.bodyFile();
        byte[] bytes = Files.readAllBytes(body);
        Files.delete(body);
        assertRetry(dir, C, result, "SKILL_BODY_UNKNOWN");
        Files.write(body, bytes);
        assertTrue(stableCheck(dir, C, new MemoQueue(dir.resolve("review.json")).all().get(0)).ok());
    }

    @Test void changedBodyCannotBeAuthorizedByUnchangedVersion(@TempDir Path dir) throws Exception {
        SkillFixture f = new SkillFixture(dir, C);
        Memo delivery = f.sample("skill_delivery"), result = f.sample("skill_task_result");
        Files.writeString(f.bodyFile(), "tampered current body");
        assertAll(
                () -> assertRetry(dir.resolve("delivery"), dir, C, delivery, "SKILL_BODY_UNKNOWN"),
                () -> assertRetry(dir.resolve("result"), dir, C, result, "SKILL_BODY_UNKNOWN"));
    }

    @Test void selectedResultBodyDigestMustMatchOriginalReadableBody(@TempDir Path dir) throws Exception {
        SkillFixture f = new SkillFixture(dir, C);
        Memo result = f.sample("skill_task_result");
        JsonObject snapshot = JsonParser.parseString(result.snapshot()).getAsJsonObject();
        snapshot.getAsJsonObject("detail").addProperty("body_digest", "0".repeat(64));
        Memo changed = withSnapshot(result, snapshot.toString());
        assertNotNull(SkillDeliveryIntake.taskResultFromSnapshot(changed.snapshot()), "original typed parser still accepts task identity");
        assertRetry(dir, C, changed, "SKILL_BODY_MISMATCH");
    }

    @Test void selectedResultBodyRefMustNotBeSubstitutedByCurrentBody(@TempDir Path dir) throws Exception {
        SkillFixture f = new SkillFixture(dir, C);
        Memo result = f.sample("skill_task_result");
        JsonObject snapshot = JsonParser.parseString(result.snapshot()).getAsJsonObject();
        snapshot.getAsJsonObject("detail").addProperty("body_ref", "skill-body:other-original.md");
        assertRetry(dir, C, withSnapshot(result, snapshot.toString()), "SKILL_BODY_MISMATCH");
    }

    @Test void originalResultCompanionCannotBorrowAnotherCompanionsReview(@TempDir Path dir) throws Exception {
        SkillFixture f = new SkillFixture(dir, C);
        assertRetry(dir, UUID.fromString("00000000-0000-0000-0000-0000000000bb"),
                f.sample("skill_task_result"), "SKILL_COMPANION_MISMATCH");
    }

    @Test void malformedSnapshotsCannotBecomePermissionFromStableReviewContext(@TempDir Path dir) throws Exception {
        SkillFixture f = new SkillFixture(dir, C);
        Memo delivery = f.sample("skill_delivery"), result = f.sample("skill_task_result");
        JsonObject missing = JsonParser.parseString(result.snapshot()).getAsJsonObject();
        missing.remove("source");
        assertAll(
                () -> assertRetry(dir.resolve("delivery"), dir, C, withSnapshot(delivery, "{bad"), "SKILL_SNAPSHOT_UNKNOWN"),
                () -> assertRetry(dir.resolve("result"), dir, C, withSnapshot(result, missing.toString()), "SKILL_SNAPSHOT_UNKNOWN"));
    }

    @Test void malformedRegistryTailCannotAuthorizeLastGoodButPossiblyStaleRecord(@TempDir Path dir) throws Exception {
        SkillFixture f = new SkillFixture(dir, C);
        Memo sample = f.sample("skill_delivery");
        Files.writeString(dir.resolve("skills.jsonl"), "{broken-later-lifecycle\n", java.nio.file.StandardOpenOption.APPEND);
        assertRetry(dir, C, sample, "SKILL_REGISTRY_UNKNOWN");
    }

    @Test void actualRegistryReadFailureCannotAuthorizeCurrentTarget(@TempDir Path dir) throws Exception {
        SkillFixture f = new SkillFixture(dir, C);
        Memo sample = f.sample("skill_delivery");
        Files.delete(dir.resolve("skills.jsonl"));
        Files.createDirectory(dir.resolve("skills.jsonl"));
        assertRetry(dir, C, sample, "SKILL_REGISTRY_UNKNOWN");
    }

    private static ReviewTargetGate.Check stableCheck(Path dir, UUID owner, Memo memo) {
        var stable = reviewToken("new-review-world", "new-review-server", 9);
        return ReviewTargetGate.check(dir, owner, memo, stable, stable);
    }

    private static void assertRetry(Path dir, UUID owner, Memo sample, String reason) throws Exception {
        assertRetry(dir, dir, owner, sample, reason);
    }

    private static void assertRetry(Path queueDir, Path configDir, UUID owner, Memo sample, String reason) throws Exception {
        Files.createDirectories(queueDir);
        MemoQueue q = new MemoQueue(queueDir.resolve("review.json"));
        assertTrue(q.append(sample));
        List<Memo> batch = q.drain(3);
        String rid = "rev-lifecycle";
        assertTrue(ReviewBatchStore.save(queueDir, rid, batch));
        byte[] claim = Files.readAllBytes(ReviewBatchStore.file(queueDir, rid));
        ReviewCompletion completion = new ReviewCompletion(batch, m -> stableCheck(configDir, owner, m));
        var target = completion.target(sample.id());
        assertAll(
                () -> assertFalse(target.ok(), "stable context alone must not authorize a stale/unknown target"),
                () -> assertEquals(reason, target.reason()),
                () -> assertFalse(target.terminal(), "no new policy deleting historical skill samples"),
                () -> assertFalse(completion.effective(sample.id(), Set.of(sample.id()))));
        var settled = completion.settle(q, batch, Set.of(sample.id()));
        assertAll(
                () -> assertEquals(0, settled.committed()),
                () -> assertEquals(0, settled.terminalSkipped()),
                () -> assertEquals(List.of(sample), new MemoQueue(q.file()).all()),
                () -> assertArrayEquals(claim, Files.readAllBytes(ReviewBatchStore.file(queueDir, rid))));
    }

    private static Memo withSnapshot(Memo memo, String snapshot) {
        return new Memo(memo.id(), memo.problem(), memo.stage(), memo.tried(), snapshot,
                memo.createdAt(), memo.category(), memo.evidence());
    }

    /** Test-only shared producer fixture: actual registry/body + delivery or adopt/report + original intake. */
    static final class SkillFixture implements LearnerUsageTool.TaskAccess {
        static final String SKILL = "sk-review-target";
        final Path dir;
        final UUID owner;
        final SkillRegistry registry;
        final UsageLedger ledger;
        final WorldContextStamp.Token token = reviewToken("old-world", "old-server", 1);
        TaskRecord active, finished;
        int sequence;
        SkillFixture(Path dir, UUID owner) throws Exception {
            this.dir = dir; this.owner = owner;
            Files.createDirectories(dir);
            registry = SkillRegistrar.registryFor(dir);
            ledger = new UsageLedger(dir);
            revise("exact original skill body without coordinates");
        }
        void revise(String body) {
            String ref = SkillBodyStore.write(dir, SKILL, 1, Integer.toHexString(body.hashCode()), body);
            assertNotNull(ref);
            var d = registry.register(new ArtifactDraft(ArtifactKind.EXPERIENCE, "target review",
                    "target lifecycle review", body, List.of("target lifecycle"), List.of(), List.of(), "LOW",
                    List.of(), List.of("original-memo"), "EXECUTOR"), SKILL, ref, true);
            assertTrue(d.stored(), d.reason());
            assertEquals(SKILL, d.entry().skillId());
        }
        Path bodyFile() {
            return dir.resolve("skill-bodies").resolve(registry.get(SKILL).orElseThrow().bodyRef().substring("skill-body:".length()));
        }
        Memo sample(String stage) throws Exception {
            String version = "v" + registry.get(SKILL).orElseThrow().version();
            if (stage.equals("skill_delivery")) {
                var r = SkillDeliveryEvidence.record(new SkillDeliveryEvidence.Context("request-" + ++sequence,
                        "old-world", "old-server", 1, owner.toString(), "EXECUTOR", "execution", "", "",
                        List.of(new SkillDeliveryEvidence.Ref("skill:" + SKILL, version))), ledger);
                assertEquals(1, r.written());
            } else {
                active = new TaskRecord("goto", "original-call-" + ++sequence, 1000) {};
                active.bindIdentity(token);
                active.setState(TaskState.RUNNING);
                invoke("adopt_skill", version);
                assertNotNull(active.skillSelection(), "real full reply binds this original task");
                active.setResult(TaskResult.ok("original task terminal"));
                active.setState(TaskState.SUCCESS);
                finished = active;
                active = null;
                invoke("report_skill", version);
            }
            MemoQueue queue = new MemoQueue(dir.resolve("intake.json"));
            Path journalFile = dir.resolve("intake-journal.txt");
            var journal = new SkillDeliveryIntake.EnqueuedJournal() {
                public boolean contains(String id) { try { return Files.exists(journalFile) && Files.readAllLines(journalFile).contains(id); }
                    catch (java.io.IOException e) { throw new IllegalStateException(e); } }
                public void remember(String id) { try { Files.writeString(journalFile, id + "\n", java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND); } catch (java.io.IOException e) { throw new IllegalStateException(e); } }
            };
            var scan = SkillDeliveryIntake.runWithTaskResults(ledger.readSnapshot().rows(),
                    new SkillDeliveryIntake.Scope("old-world", "old-server", 1, owner.toString()),
                    queue, journal, (c, fp, now, once) -> once.getAsBoolean(), 1000L, 16);
            assertTrue(scan.enqueued() > 0);
            return queue.all().stream().filter(m -> m.stage().equals(stage))
                    .filter(m -> stage.equals("skill_task_result") || JsonParser.parseString(m.snapshot()).getAsJsonObject()
                            .get("version").getAsString().equals(version)).reduce((a, b) -> b).orElseThrow();
        }
        private void invoke(String action, String version) {
            JsonObject args = new JsonObject();
            args.addProperty("action", action);
            args.addProperty("skill_ref", "skill:" + SKILL);
            args.addProperty("version", version);
            args.addProperty("task_id", (active == null ? finished : active).publicId());
            AtomicReference<String> reply = new AtomicReference<>();
            new LearnerUsageTool(dir, ledger, this).onServerCall("selection-" + sequence, args, null, reply::set);
            assertNotNull(reply.get());
            assertTrue(JsonParser.parseString(reply.get()).getAsJsonObject().get("success").getAsBoolean(), reply.get());
        }
        public UUID companionId() { return owner; }
        public WorldContextStamp.Token identity() { return token; }
        public TaskRecord current() { return active; }
        public TaskRecord finished(String id) { return finished != null && finished.publicId().equals(id) ? finished : null; }
    }

    @Test void retractedFailsClosed(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("experience-" + C + ".jsonl"),
                "{\"id\":\"e1\",\"publication\":\"TRIAL\",\"retracted\":true}\n");
        assertFalse(ReviewTargetGate.check(dir, C, memo("exp:e1", "v1")).ok());
    }

    @Test void missingFailsClosed(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("experience-" + C + ".jsonl"),
                "{\"id\":\"other\",\"publication\":\"TRIAL\",\"retracted\":false}\n");
        assertEquals("MISSING", ReviewTargetGate.check(dir, C, memo("exp:e1", "v1")).reason());
    }

    @Test void versionUnverifiableFailsClosed(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("experience-" + C + ".jsonl"),
                "{\"id\":\"e1\",\"publication\":\"TRIAL\",\"retracted\":false}\n");
        var c = ReviewTargetGate.check(dir, C, memo("exp:e1", "v1"));
        assertFalse(c.ok(), "版本不可核不得当有效 verdict");
        assertEquals("VERSION_UNVERIFIED", c.reason());
    }

    @Test void nonExpansionMemoNotGated() {
        assertTrue(ReviewTargetGate.check(null, C, new Memo("x", "p", "learning", "t", "s", 1L, "learning", List.of())).ok());
    }

    @Test void exactVersionMatches(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("experience-" + C + ".jsonl"),
                "{\"id\":\"e1\",\"publication\":\"TRIAL\",\"retracted\":false,\"content_fingerprint\":\"v1\"}\n");
        var c = ReviewTargetGate.check(dir, C, memo("exp:e1", "v1"));
        assertTrue(c.ok(), c.reason());
        assertEquals("OK", c.reason());
    }

    @Test void versionMismatchFailsClosed(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("experience-" + C + ".jsonl"),
                "{\"id\":\"e1\",\"publication\":\"TRIAL\",\"retracted\":false,\"content_fingerprint\":\"v2\"}\n");
        var c = ReviewTargetGate.check(dir, C, memo("exp:e1", "v1"));
        assertFalse(c.ok());
        assertEquals("VERSION_MISMATCH", c.reason());
    }
}
