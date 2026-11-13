package com.dwinovo.numen.plugins.learner.core;

import com.dwinovo.numen.plugins.learner.LearnerPluginReviewTestAccess;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-13 C1：真实消费者反例（跑在 STUB 上应全红，用于先钉预期再接线）。
 * 6 条：跨 scope 不入 / 同 episode 重放去重 / 版本独立 / 风暴抑制后窗口过期可再入且不占预算 /
 * append 失败不标成功 / 已有 RESULT 不再当未复核候选（且不启动 review）。
 */
class SkillDeliveryIntakeTest {
    private static final String C = "a43d753e-790d-472b-8d5b-dc18ac0afbc0";
    private static final String W = "live-x";
    private static final String S = "4e30be55-0097-4ff3-b986-bfe52faab2fc";
    private static final UUID OWNER = UUID.fromString(C);

    @TempDir Path dir;

    private static SkillDeliveryIntake.Scope scope(String worldId) {
        return new SkillDeliveryIntake.Scope(worldId, S, 1L, C);
    }

    private static UsageLedger.Entry row(UsageLedger ledger, String artifactId, String ref, String version,
                                         String worldId, String requestId,
                                         UsageLedger.Phase phase, UsageLedger.Outcome outcome) {
        JsonObject detail = new JsonObject();
        detail.addProperty("skill_ref", ref);
        detail.addProperty("version", version);
        detail.addProperty("world_id", worldId);
        detail.addProperty("server_session", S);
        detail.addProperty("epoch", 1L);
        detail.addProperty("request_id", requestId);
        detail.addProperty("use_key", "episode");
        detail.addProperty("delivery_only", true);
        return ledger.append(artifactId, "SKILL", ref, phase, outcome, detail.toString(),
                "request-delivery:" + requestId, C);
    }

    private static SkillDeliveryIntake.EnqueuedJournal journal() {
        return new SkillDeliveryIntake.EnqueuedJournal() {
            final Set<String> ids = new HashSet<>();
            public boolean contains(String memoId) { return ids.contains(memoId); }
            public void remember(String memoId) { ids.add(memoId); }
        };
    }

    @Test
    void foreignScopeRowIsNeverEnqueued() {
        UsageLedger ledger = new UsageLedger(dir);
        row(ledger, "skill:sk-a@v1#k=x", "skill:sk-a", "v1", "other-world", "req-1",
                UsageLedger.Phase.PRESENTED, UsageLedger.Outcome.UNKNOWN);
        MemoQueue queue = new MemoQueue(dir.resolve("memos.json"));

        var scan = SkillDeliveryIntake.run(ledger.all(), scope(W), queue, journal(),
                (c, fp, now, once) -> once.getAsBoolean(), 1000L, 16);

        assertEquals(1, scan.foreignScope(), "旧 world 的账本行只计数，不入当前队列");
        assertEquals(0, scan.enqueued());
        assertTrue(queue.all().isEmpty());
    }

    @Test
    void sameEpisodeReplayDoesNotDoubleEnqueue() {
        UsageLedger ledger = new UsageLedger(dir);
        row(ledger, "skill:sk-a@v1#k=e1", "skill:sk-a", "v1", W, "req-1",
                UsageLedger.Phase.PRESENTED, UsageLedger.Outcome.UNKNOWN);
        MemoQueue queue = new MemoQueue(dir.resolve("memos.json"));
        var journal = journal();

        var first = SkillDeliveryIntake.run(ledger.all(), scope(W), queue, journal,
                (c, fp, now, once) -> once.getAsBoolean(), 1000L, 16);
        var second = SkillDeliveryIntake.run(ledger.all(), scope(W), queue, journal,
                (c, fp, now, once) -> once.getAsBoolean(), 2000L, 16);

        assertEquals(1, first.enqueued());
        assertEquals(0, second.enqueued(), "同 artifactId 重放不再入队");
        assertEquals(1, queue.all().size());
        assertEquals("skill_delivery", queue.all().get(0).stage());
    }

    @Test
    void differentVersionsAreIndependent() {
        UsageLedger ledger = new UsageLedger(dir);
        row(ledger, "skill:sk-a@v1#k=e1", "skill:sk-a", "v1", W, "req-1",
                UsageLedger.Phase.PRESENTED, UsageLedger.Outcome.UNKNOWN);
        row(ledger, "skill:sk-a@v2#k=e1", "skill:sk-a", "v2", W, "req-1",
                UsageLedger.Phase.PRESENTED, UsageLedger.Outcome.UNKNOWN);
        MemoQueue queue = new MemoQueue(dir.resolve("memos.json"));

        var scan = SkillDeliveryIntake.run(ledger.all(), scope(W), queue, journal(),
                (c, fp, now, once) -> once.getAsBoolean(), 1000L, 16);

        assertEquals(2, scan.enqueued(), "版本独立：v1/v2 各一条");
        assertEquals(2, queue.all().size());
    }

    @Test
    void suppressedRowDoesNotConsumeBudgetAndRetriesAfterWindow() {
        UsageLedger ledger = new UsageLedger(dir);
        row(ledger, "skill:sk-a@v1#k=e1", "skill:sk-a", "v1", W, "req-1",
                UsageLedger.Phase.PRESENTED, UsageLedger.Outcome.UNKNOWN);
        row(ledger, "skill:sk-b@v1#k=e1", "skill:sk-b", "v1", W, "req-1",
                UsageLedger.Phase.PRESENTED, UsageLedger.Outcome.UNKNOWN);
        MemoQueue queue = new MemoQueue(dir.resolve("memos.json"));
        var journal = journal();
        // 第一条被 StormGuard 拒绝（模拟窗口内），第二条允许：被拒不占预算。
        var scan = SkillDeliveryIntake.run(ledger.all(), scope(W), queue, journal,
                (c, fp, now, once) -> fp.contains("sk-b") && once.getAsBoolean(), 1000L, 1);

        assertEquals(1, scan.enqueued(), "预算=1 仍能入被允许的那条");
        assertEquals(1, scan.stormSuppressed());
        assertEquals(1, queue.all().size());

        // 窗口过期（调用方传入允许）：同一条可再入 —— 只能靠原节流窗口，不靠 retry set。
        var retry = SkillDeliveryIntake.run(ledger.all(), scope(W), queue, journal,
                (c, fp, now, once) -> once.getAsBoolean(), 3000L, 16);
        assertEquals(1, retry.enqueued(), "窗口过后原节流放行，补入 1 条");
        assertEquals(2, queue.all().size());
    }

    @Test
    void appendFailureIsNotMarkedAsSuccess() throws IOException {
        UsageLedger ledger = new UsageLedger(dir);
        row(ledger, "skill:sk-a@v1#k=e1", "skill:sk-a", "v1", W, "req-1",
                UsageLedger.Phase.PRESENTED, UsageLedger.Outcome.UNKNOWN);
        Path blocked = dir.resolve("blocked");
        Files.writeString(blocked, "x");
        MemoQueue bad = new MemoQueue(blocked.resolve("memos.json"));
        var journal = journal();

        var scan = SkillDeliveryIntake.run(ledger.all(), scope(W), bad, journal,
                (c, fp, now, once) -> once.getAsBoolean(), 1000L, 16);

        assertEquals(1, scan.failed(), "写队列失败如实计数");
        assertEquals(0, scan.enqueued());
        assertFalse(journal.contains(SkillDeliveryIntake.memoIdOf(ledger.all().get(0))),
                "失败不得写持久去重（否则下轮永远补不进）");
    }

    @Test
    void presentedWithExistingResultIsNotADeliveryCandidateAndStartsNoReview() {
        UsageLedger ledger = new UsageLedger(dir);
        String artifact = "skill:sk-a@v1#k=e1";
        row(ledger, artifact, "skill:sk-a", "v1", W, "req-1",
                UsageLedger.Phase.PRESENTED, UsageLedger.Outcome.UNKNOWN);
        row(ledger, artifact, "skill:sk-a", "v1", W, "req-1",
                UsageLedger.Phase.RESULT, UsageLedger.Outcome.SUCCESS);
        MemoQueue queue = new MemoQueue(dir.resolve("memos.json"));

        var scan = SkillDeliveryIntake.run(ledger.all(), scope(W), queue, journal(),
                (c, fp, now, once) -> once.getAsBoolean(), 1000L, 16);

        assertEquals(2, ledger.all().size(), "UsageLedger.all() 返回全部行（同一 artifact 的 PRESENTED 与 RESULT 都在）");
        assertEquals(1, scan.resultChannel(), "最新结论已进 RESULT 通道：不再走交付候选（不等于已复审）");
        assertEquals(0, scan.enqueued());
        assertTrue(queue.all().isEmpty());
        assertNull(LearnerPluginReviewTestAccess.runningReview(OWNER), "扫描不得启动 review");
    }

    @Test
    void realProducerRowFlowsThroughIntakeIntoMemoQueue() {
        UsageLedger ledger = new UsageLedger(dir);
        var produced = SkillDeliveryEvidence.record(new SkillDeliveryEvidence.Context(
                "req-prod-1", W, S, 1L, C, "EXECUTOR", "execution", "", "",
                List.of(new SkillDeliveryEvidence.Ref("skill:sk-prod", "v1"))), ledger);
        assertEquals(1, produced.written(), "真实 producer 写下一行 PRESENTED/UNKNOWN");
        MemoQueue queue = new MemoQueue(dir.resolve("memos.json"));

        var scan = SkillDeliveryIntake.run(ledger.all(), scope(W), queue, journal(),
                (c, fp, now, once) -> once.getAsBoolean(), 1000L, 16);

        assertEquals(1, scan.enqueued(), "producer → intake → MemoQueue 组合正控");
        assertEquals(1, queue.all().size());
        assertEquals("skill_delivery", queue.all().get(0).stage());
    }

    @Test
    void nonCanonicalOrIncompleteClaimsDoNotBecomeDelivery() {
        UsageLedger ledger = new UsageLedger(dir);
        String base = "\"world_id\":\"" + W + "\",\"server_session\":\"" + S + "\",\"epoch\":1";
        // 1) kind=SKILL 但 ref 非 canonical
        ledger.append("x1", "SKILL", "sk-a", UsageLedger.Phase.PRESENTED, UsageLedger.Outcome.UNKNOWN,
                "{\"skill_ref\":\"sk-a\",\"version\":\"v1\"," + base + ",\"request_id\":\"r1\"}",
                "manual:test", C);
        // 2) 坏 JSON detail
        ledger.append("x2", "SKILL", "skill:sk-b", UsageLedger.Phase.PRESENTED, UsageLedger.Outcome.UNKNOWN,
                "{not json", "request-delivery:r2", C);
        // 3) 缺 request_id（episode 身份不全）
        ledger.append("x3", "SKILL", "skill:sk-c", UsageLedger.Phase.PRESENTED, UsageLedger.Outcome.UNKNOWN,
                "{\"skill_ref\":\"skill:sk-c\",\"version\":\"v1\"," + base + "}",
                "request-delivery:r3", C);
        // 4) 半 task 身份：task_id 有、tool_call_id 缺，且自称 delivery-only
        ledger.append("x4", "SKILL", "skill:sk-d", UsageLedger.Phase.PRESENTED, UsageLedger.Outcome.UNKNOWN,
                "{\"skill_ref\":\"skill:sk-d\",\"version\":\"v1\"," + base
                        + ",\"request_id\":\"r4\",\"task_id\":\"t-1\",\"delivery_only\":true}",
                "request-delivery:r4", C);
        MemoQueue queue = new MemoQueue(dir.resolve("memos.json"));

        var scan = SkillDeliveryIntake.run(ledger.all(), scope(W), queue, journal(),
                (c, fp, now, once) -> once.getAsBoolean(), 1000L, 16);

        assertEquals(0, scan.enqueued(), "非 canonical/坏 JSON/缺 episode/半 task 都不算 delivery");
        assertTrue(queue.all().isEmpty());
    }

    @Test
    void restartReloadsPersistedJournalAndDoesNotReenqueue() throws IOException {
        UsageLedger ledger = new UsageLedger(dir);
        row(ledger, "skill:sk-a@v1#k=e1", "skill:sk-a", "v1", W, "req-1",
                UsageLedger.Phase.PRESENTED, UsageLedger.Outcome.UNKNOWN);
        Path journalFile = dir.resolve("learner-enqueued.jsonl");
        MemoQueue queue = new MemoQueue(dir.resolve("memos.json"));

        var firstJournal = fileJournal(journalFile);
        var first = SkillDeliveryIntake.run(ledger.all(), scope(W), queue, firstJournal,
                (c, fp, now, once) -> once.getAsBoolean(), 1000L, 16);
        assertEquals(1, first.enqueued());
        assertTrue(Files.isRegularFile(journalFile), "入队后必须落持久 journal");

        // 模拟重启：新实例只从盘上重载，不复用内存 set。
        var reloaded = fileJournal(journalFile);
        var second = SkillDeliveryIntake.run(ledger.all(), scope(W), queue, reloaded,
                (c, fp, now, once) -> once.getAsBoolean(), 2000L, 16);
        assertEquals(0, second.enqueued(), "重启后持久 journal 仍挡住重复入队");
        assertEquals(1, queue.all().size());
    }

    @Test
    void journalFailureRetainsQueueAndRepairsWithoutClaimingAnotherEnqueue() {
        UsageLedger ledger = new UsageLedger(dir);
        var delivery = row(ledger, "skill:sk-a@v1#k=journal", "skill:sk-a", "v1", W, "req-journal",
                UsageLedger.Phase.PRESENTED, UsageLedger.Outcome.UNKNOWN);
        MemoQueue queue = new MemoQueue(dir.resolve("memos.json"));
        Set<String> persisted = new HashSet<>();
        boolean[] fail = {true};
        var journal = new SkillDeliveryIntake.EnqueuedJournal() {
            public boolean contains(String id) { return persisted.contains(id); }
            public void remember(String id) {
                if (fail[0]) throw new IllegalStateException("journal unavailable");
                persisted.add(id);
            }
        };
        StormGuard.resetForTest();
        try {
            var first = SkillDeliveryIntake.run(List.of(delivery), scope(W), queue, journal,
                    StormGuard::admitIfEnqueued, 1000L, 16);
            assertEquals(1, first.failed());
            assertEquals(0, first.enqueued());
            assertEquals(1, queue.size(), "the already-written queue entry must survive journal failure");
            assertFalse(journal.contains(SkillDeliveryIntake.memoIdOf(delivery)));
            fail[0] = false;
            var repaired = SkillDeliveryIntake.run(List.of(delivery), scope(W), queue, journal,
                    StormGuard::admitIfEnqueued, 1000L, 16);
            assertEquals(0, repaired.enqueued(), "repair is not a second queue append");
            assertEquals(1, repaired.duplicate());
            assertEquals(0, repaired.failed());
            assertEquals(1, queue.size());
            assertTrue(journal.contains(SkillDeliveryIntake.memoIdOf(delivery)));
            var another = row(ledger, "skill:sk-a@v1#k=another", "skill:sk-a", "v1", W, "req-another",
                    UsageLedger.Phase.PRESENTED, UsageLedger.Outcome.UNKNOWN);
            var next = SkillDeliveryIntake.run(List.of(another), scope(W), queue, journal,
                    StormGuard::admitIfEnqueued, 1000L, 16);
            assertEquals(1, next.enqueued(), "failed append transaction and journal repair consume no cooldown");
        } finally {
            StormGuard.resetForTest();
        }
    }

    @Test
    void seenOnlyAppendDoesNotClaimAnotherEnqueue() {
        UsageLedger ledger = new UsageLedger(dir);
        var delivery = row(ledger, "skill:sk-a@v1#k=seen", "skill:sk-a", "v1", W, "req-seen",
                UsageLedger.Phase.PRESENTED, UsageLedger.Outcome.UNKNOWN);
        MemoQueue queue = new MemoQueue(dir.resolve("memos.json"));
        assertEquals(1, SkillDeliveryIntake.run(ledger.all(), scope(W), queue, journal(),
                (c, fp, now, once) -> once.getAsBoolean(), 1000L, 16).enqueued());
        queue.commitDrain(queue.drain(1));
        assertTrue(queue.all().isEmpty());
        var emptyJournal = journal();
        var scan = SkillDeliveryIntake.run(List.of(delivery), scope(W), queue, emptyJournal,
                (c, fp, now, once) -> once.getAsBoolean(), 1000L, 16);
        assertEquals(0, scan.enqueued());
        assertEquals(1, scan.duplicate(), "append=true due to historical seen is not a fresh queue entry");
        assertFalse(emptyJournal.contains(SkillDeliveryIntake.memoIdOf(delivery)));
        assertTrue(queue.all().isEmpty());
    }

    @Test
    void failedCandidateDoesNotConsumeSuccessfulEnqueueBudget() {
        UsageLedger ledger = new UsageLedger(dir);
        row(ledger, "skill:sk-a@v1#k=oversized", "skill:sk-a", "v1", W, "r".repeat(4100),
                UsageLedger.Phase.PRESENTED, UsageLedger.Outcome.UNKNOWN);
        var good = row(ledger, "skill:sk-b@v1#k=good", "skill:sk-b", "v1", W, "req-good",
                UsageLedger.Phase.PRESENTED, UsageLedger.Outcome.UNKNOWN);
        MemoQueue queue = new MemoQueue(dir.resolve("memos.json"));
        var scan = SkillDeliveryIntake.run(ledger.all(), scope(W), queue, journal(),
                (c, fp, now, once) -> once.getAsBoolean(), 1000L, 1);
        assertEquals(1, scan.failed());
        assertEquals(1, scan.enqueued());
        assertTrue(queue.contains(SkillDeliveryIntake.memoIdOf(good)));
    }

    @Test
    void incompleteCurrentScopeAndReviewedArtifactsDoNotEnqueue() {
        UsageLedger ledger = new UsageLedger(dir);
        var delivery = row(ledger, "skill:sk-a@v1#k=scope", "skill:sk-a", "v1", W, "req-scope",
                UsageLedger.Phase.PRESENTED, UsageLedger.Outcome.UNKNOWN);
        assertTrue(SkillDeliveryIntake.selectDue(ledger.all(), new SkillDeliveryIntake.Scope(W, S, 0, C),
                Set.of(), Set.of(), 16).isEmpty());
        assertTrue(SkillDeliveryIntake.selectDue(ledger.all(), scope(W), Set.of(),
                Set.of(delivery.artifactId()), 16).isEmpty());
        assertTrue(SkillDeliveryIntake.selectDue(ledger.all(), scope(W),
                Set.of(SkillDeliveryIntake.memoIdOf(delivery)), Set.of(), 16).isEmpty());
        assertEquals(List.of(delivery), SkillDeliveryIntake.selectDue(ledger.all(), scope(W),
                Set.of(), Set.of(), 1));
    }

    /** 盘上 journal（与 LearnerAutoIntake.learner-enqueued.jsonl 同形：一行 {"id":...}）。 */
    private static SkillDeliveryIntake.EnqueuedJournal fileJournal(Path file) throws IOException {
        Set<String> ids = new HashSet<>();
        if (Files.isRegularFile(file)) {
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) continue;
                try {
                    ids.add(JsonParser.parseString(line).getAsJsonObject().get("id").getAsString());
                } catch (RuntimeException ignored) {
                    // 坏行跳过：最坏重复一条，复盘质量门可见
                }
            }
        }
        return new SkillDeliveryIntake.EnqueuedJournal() {
            public boolean contains(String memoId) { return ids.contains(memoId); }
            public void remember(String memoId) {
                ids.add(memoId);
                try {
                    Files.writeString(file, "{\"id\":\"" + memoId + "\"}\n",
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);
                } catch (IOException e) {
                    throw new IllegalStateException("journal write failed", e);
                }
            }
        };
    }
}