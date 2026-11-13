package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-13 C1 学习者侧接缝需求：交付 → 现有 UsageLedger 的一条 PRESENTED/UNKNOWN。
 * 本类不跑 Java（B2 占槽）时只是规格；启用后应全绿。
 */
class SkillDeliveryEvidenceTest {
    private static final String C = "a43d753e-790d-472b-8d5b-dc18ac0afbc0";
    private static final String W = "live-daddc3495116e710";
    private static final String S = "4e30be55-0097-4ff3-b986-bfe52faab2fc";

    @TempDir Path dir;

    private static SkillDeliveryEvidence.Context ctx(String requestId, String worldId, String serverSession,
                                                     long epoch, String taskId, String toolCallId,
                                                     SkillDeliveryEvidence.Ref... refs) {
        return new SkillDeliveryEvidence.Context(requestId, worldId, serverSession, epoch,
                C, "EXECUTOR", "execution", taskId, toolCallId, List.of(refs));
    }

    private static SkillDeliveryEvidence.Ref ref(String id, String version) {
        return new SkillDeliveryEvidence.Ref("skill:" + id, version);
    }

    @Test
    void fullContextWithoutTaskWritesOnePresentedUnknownDeliveryOnly() {
        UsageLedger ledger = new UsageLedger(dir);
        var result = SkillDeliveryEvidence.record(ctx("req-1", W, S, 1, null, null, ref("sk-a", "v1")), ledger);

        assertEquals(1, result.written());
        assertFalse(result.noIdentity());
        assertFalse(result.ioFailed());
        assertEquals(1, ledger.all().size());
        UsageLedger.Entry e = ledger.all().get(0);
        assertEquals(UsageLedger.Phase.PRESENTED, e.phase());
        assertEquals(UsageLedger.Outcome.UNKNOWN, e.outcome());
        assertEquals(C, e.companionId());
        assertTrue(e.artifactId().startsWith("skill:sk-a@v1#k="), "幂等键用摘要，不拼冒号/井号串联");
        assertEquals(SkillDeliveryEvidence.SOURCE_PREFIX + "req-1", e.source());
        assertTrue(e.detail().contains("\"delivery_only\":true"));
    }

    @Test
    void replayingTheSameBatchDoesNotDoubleWrite() {
        UsageLedger ledger = new UsageLedger(dir);
        var first = SkillDeliveryEvidence.record(ctx("req-1", W, S, 1, null, null, ref("sk-a", "v1")), ledger);
        var second = SkillDeliveryEvidence.record(ctx("req-1", W, S, 1, null, null, ref("sk-a", "v1")), ledger);
        assertEquals(1, first.written());
        assertEquals(0, second.written());
        assertEquals(1, second.skipped());
        assertEquals(1, ledger.all().size(), "同一 episode/ref/version 重放只留一条");
    }

    @Test
    void sameBodyTaskAcrossRequestsWritesOnlyOneCandidate() {
        UsageLedger ledger = new UsageLedger(dir);
        var r1 = SkillDeliveryEvidence.record(ctx("req-1", W, S, 1, "t-9", "call-9", ref("sk-a", "v1")), ledger);
        var r2 = SkillDeliveryEvidence.record(ctx("req-2", W, S, 1, "t-9", "call-9", ref("sk-a", "v1")), ledger);
        assertEquals(1, r1.written());
        assertEquals(0, r2.written(), "同一 body 任务的多轮请求只应有一条候选");
        assertEquals(1, ledger.all().size());
        assertTrue(ledger.all().get(0).detail().contains("\"use_key\":\"task\""), "有 body 任务按 task 归并");
        assertTrue(ledger.all().get(0).detail().contains("\"delivery_only\":false"));
    }

    @Test
    void differentEpisodeOrVersionIsIndependent() {
        UsageLedger ledger = new UsageLedger(dir);
        SkillDeliveryEvidence.record(ctx("req-1", W, S, 1, null, null, ref("sk-a", "v1")), ledger);
        SkillDeliveryEvidence.record(ctx("req-2", W, S, 1, null, null, ref("sk-a", "v1")), ledger);
        SkillDeliveryEvidence.record(ctx("req-1", W, S, 1, null, null, ref("sk-a", "v2")), ledger);
        assertEquals(3, ledger.all().size(), "episode 与版本相互独立，各自一行");
    }

    @Test
    void missingRequestIdOrWorldWritesNothingInsteadOfPseudoUnknownEpisode() {
        UsageLedger ledger = new UsageLedger(dir);
        var noRequest = SkillDeliveryEvidence.record(ctx("", W, S, 1, null, null, ref("sk-a", "v1")), ledger);
        var noWorld = SkillDeliveryEvidence.record(ctx("req-1", "", S, 1, null, null, ref("sk-a", "v1")), ledger);
        var noEpoch = SkillDeliveryEvidence.record(ctx("req-1", W, S, -1, null, null, ref("sk-a", "v1")), ledger);
        assertTrue(noRequest.noIdentity());
        assertTrue(noWorld.noIdentity());
        assertTrue(noEpoch.noIdentity());
        assertEquals(0, ledger.all().size(), "缺身份不落行、不造 episode=UNKNOWN 伪候选");
    }

    @Test
    void halfTaskIdentityIsRejectedInsteadOfFallingBackToDeliveryOnly() {
        UsageLedger ledger = new UsageLedger(dir);
        // 有 taskId 无 toolCallId / 有 toolCallId 无 taskId：不许退回 episode 掩盖半身份。
        var noCall = SkillDeliveryEvidence.record(ctx("req-1", W, S, 1, "t-9", "", ref("sk-a", "v1")), ledger);
        var noTask = SkillDeliveryEvidence.record(ctx("req-2", W, S, 1, "", "call-9", ref("sk-a", "v1")), ledger);
        assertTrue(noCall.noIdentity());
        assertTrue(noTask.noIdentity());
        assertEquals(0, ledger.all().size());
    }

    @Test
    void epochMustBePositive() {
        UsageLedger ledger = new UsageLedger(dir);
        var result = SkillDeliveryEvidence.record(ctx("req-1", W, S, 0, null, null, ref("sk-a", "v1")), ledger);
        assertTrue(result.noIdentity(), "epoch=0 是默认占位，不算真实 world token");
        assertEquals(0, ledger.all().size());
    }

    @Test
    void concurrentCallbacksWriteOnlyOnce() throws Exception {
        UsageLedger ledger = new UsageLedger(dir);
        var input = ctx("req-1", W, S, 1, null, null, ref("sk-a", "v1"));
        int n = 4;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        var done = new java.util.concurrent.CountDownLatch(1);
        var tasks = new java.util.ArrayList<java.util.concurrent.Future<SkillDeliveryEvidence.Result>>();
        for (int i = 0; i < n; i++) {
            tasks.add(pool.submit(() -> {
                done.await();
                return SkillDeliveryEvidence.record(input, ledger);
            }));
        }
        done.countDown();
        int written = 0;
        for (var f : tasks) written += f.get().written();
        pool.shutdown();
        assertEquals(1, written, "同一 ledger 上 read+append 同锁，并发只写一条");
        assertEquals(1, ledger.all().size());
    }

    @Test
    void taskArtifactIdDoesNotDependOnRequestId() {
        var a = SkillDeliveryEvidence.artifactId(ctx("req-1", W, S, 1, "t-9", "call-9"), ref("sk-a", "v1"), "task");
        var b = SkillDeliveryEvidence.artifactId(ctx("req-2", W, S, 1, "t-9", "call-9"), ref("sk-a", "v1"), "task");
        assertEquals(a, b, "任务幂等键不含 requestId：同一 body 任务跨轮只一条");
        var e1 = SkillDeliveryEvidence.artifactId(ctx("req-1", W, S, 1, null, null), ref("sk-a", "v1"), "episode");
        var e2 = SkillDeliveryEvidence.artifactId(ctx("req-2", W, S, 1, null, null), ref("sk-a", "v1"), "episode");
        assertNotEquals(e1, e2, "没有任务时 episode 按 requestId 区分");
    }

    @Test
    void directoryAtLedgerPathReportsIoFailureWithoutSuccessClaim() throws IOException {
        Path cfg = dir.resolve("cfg-dir");
        Files.createDirectories(cfg.resolve("usage-ledger.jsonl"));
        UsageLedger ledger = new UsageLedger(cfg);
        var result = SkillDeliveryEvidence.record(ctx("req-1", W, S, 1, null, null, ref("sk-a", "v1")), ledger);
        assertTrue(result.ioFailed(), "写不进去必须如实报 IO 失败");
        assertEquals(0, result.written());
    }

    @Test
    void corruptLineIsSkippedButDoesNotCauseDoubleWriteOfTheSameKey() throws IOException {
        Path cfg = dir.resolve("cfg-bad");
        Files.createDirectories(cfg);
        UsageLedger ledger = new UsageLedger(cfg);
        SkillDeliveryEvidence.record(ctx("req-1", W, S, 1, null, null, ref("sk-a", "v1")), ledger);
        Files.writeString(ledger.file(), "{not json}\n", StandardOpenOption.APPEND);
        var replay = SkillDeliveryEvidence.record(ctx("req-1", W, S, 1, null, null, ref("sk-a", "v1")), ledger);
        assertEquals(0, replay.written(), "坏行被跳过，但真行仍在，不得双写");
        assertEquals(1, ledger.all().size());
    }

    @Test
    void nonSkillRefsAreSkipped() {
        UsageLedger ledger = new UsageLedger(dir);
        var result = SkillDeliveryEvidence.record(
                ctx("req-1", W, S, 1, null, null, new SkillDeliveryEvidence.Ref("exp:some-exp", "v1")), ledger);
        assertEquals(0, result.written());
        assertEquals(1, result.skipped());
        assertEquals(0, ledger.all().size());
    }

    @Test
    void ioFailureIsReportedAndRetryCanCompleteOnce() throws IOException {
        Path blocked = dir.resolve("blocked");
        Files.writeString(blocked, "x");
        UsageLedger bad = new UsageLedger(blocked);
        var failed = SkillDeliveryEvidence.record(ctx("req-1", W, S, 1, null, null, ref("sk-a", "v1")), bad);
        assertTrue(failed.ioFailed(), "写盘失败必须如实上报，不得吞成成功");
        assertEquals(0, failed.written());

        UsageLedger good = new UsageLedger(dir.resolve("good"));
        var retried = SkillDeliveryEvidence.record(ctx("req-1", W, S, 1, null, null, ref("sk-a", "v1")), good);
        assertEquals(1, retried.written(), "重试用同一键补齐，且只补一次");
        assertEquals(1, good.all().size());
    }
}
