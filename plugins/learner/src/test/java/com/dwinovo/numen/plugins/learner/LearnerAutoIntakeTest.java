package com.dwinovo.numen.plugins.learner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E1 自动入队（81 号 §14.4 第一轮）的离线契约测试。
 *
 * <p>要回答的三个问题（核准回执 §14.2「四步交接」的第一跳）：
 * <ol>
 *   <li>真实失败事件会不会<b>不靠任何人调用</b>就进队列；</li>
 *   <li>同一事件重放/重启会不会重复入队（幂等）；</li>
 *   <li>质量门/风暴抑制/队列上限是不是与手动口<b>同一套</b>（自动口没有放宽）。</li>
 * </ol>
 *
 * <p><b>为什么必须把静态状态拨回去</b>：LearnerAutoIntake 的开关/游标/幂等键都是静态的
 * （生产里它们就活到 JVM 关）。测试里不拨回去，前一个用例的游标会让后一个用例空转 ——
 * 那不是测「游标工作」，是测「用例顺序恰好对」，换个顺序就红。
 */
class LearnerAutoIntakeTest {

    private static final String COMPANION = "e502d17b-0000-0000-0000-000000000001";

    @Test
    void nudgeCooldownAllowsASecondNudgeAfterWindow() {
        // 2026-11-02 实测：旧实现先 get 判冷却、再 putIfAbsent 占位；第一次提醒后 key 永在，
        // 冷却到期也不会再提醒（23:28 起只发过 15:30:08 一条，队列 56 条、30 分钟静默）。
        java.util.Map<java.util.UUID, Long> state = new java.util.HashMap<>();
        java.util.UUID id = java.util.UUID.randomUUID();
        long t0 = 1_000_000L;
        assertFalse(LearnerAutoIntake.claimNudge(state, id, 2, t0), "低于门槛不发");
        assertTrue(LearnerAutoIntake.claimNudge(state, id, 3, t0), "首次发");
        assertFalse(LearnerAutoIntake.claimNudge(state, id, 3, t0 + 60_000L), "冷却内不发");
        assertTrue(LearnerAutoIntake.claimNudge(state, id, 3, t0 + 11 * 60_000L),
                "冷却到期必须能再发（旧实现在这里恒 false）");
    }

    @Test
    void undeliverableNudgeBacksOffInsteadOfRetryingEveryTick() {
        // 2026-11-02 00:29–00:32 实测：4 个不在本世界的旧同伴每 30 秒被重试（remove 冷却位当重试策略）。
        java.util.Map<java.util.UUID, Long> m = new java.util.HashMap<>();
        java.util.UUID id = java.util.UUID.randomUUID();
        long t0 = 9_000_000L;
        assertFalse(LearnerAutoIntake.nudgeBlocked(m, id, t0), "没有退避位就不拦");
        m.put(id, t0 + 30 * 60_000L);
        assertTrue(LearnerAutoIntake.nudgeBlocked(m, id, t0 + 60_000L), "退避期内不重试");
        assertFalse(LearnerAutoIntake.nudgeBlocked(m, id, t0 + 31 * 60_000L), "到期后允许再试");
        assertFalse(m.containsKey(id), "到期清位");
    }

    @Test
    void nudgeBacksOffAfterReviewFailures() {
        // L2：失败后不能按 10 分钟原速重催（一次 review 实测 35k tokens）。
        java.util.Map<java.util.UUID, Long> state = new java.util.HashMap<>();
        java.util.UUID id = java.util.UUID.randomUUID();
        long t0 = 5_000_000L;
        assertTrue(LearnerAutoIntake.claimNudge(state, id, 3, t0, 0), "首次发");
        assertFalse(LearnerAutoIntake.claimNudge(state, id, 3, t0 + 11 * 60_000L, 1),
                "失败 1 次后 11 分钟不够（退避 30 分钟）");
        assertTrue(LearnerAutoIntake.claimNudge(state, id, 3, t0 + 31 * 60_000L, 1),
                "失败 1 次满 30 分钟可再发");
        assertFalse(LearnerAutoIntake.claimNudge(state, id, 3, t0 + 61 * 60_000L, 2),
                "连续失败 2 次退避 60 分钟");
        assertTrue(LearnerAutoIntake.claimNudge(state, id, 3, t0 + 92 * 60_000L, 2),
                "满 60 分钟可再发");
        LearnerAutoIntake.noteReviewOk(id);
        assertTrue(LearnerAutoIntake.claimNudge(state, id, 3, t0 + 104 * 60_000L),
                "一次成功复盘后回到 10 分钟节奏");
    }

    @TempDir
    Path tmp;

    private java.util.Map<java.util.UUID, com.dwinovo.numen.plugins.learner.core.MemoQueue> queuesForTest;

    private com.dwinovo.numen.plugins.learner.core.MemoQueue queueForTest(String id) {
        // 与注入的 resolver 同口径：读队列也按需建（开关关闭时没人为我建过队列，
        // 但断言"队列空"仍然成立——空文件队列 size()==0）。
        return queuesForTest.computeIfAbsent(java.util.UUID.fromString(id), k -> {
            try {
                java.nio.file.Files.createDirectories(tmp);
                return new com.dwinovo.numen.plugins.learner.core.MemoQueue(
                        tmp.resolve("learner-memos-" + k + ".json"));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @BeforeEach
    void resetStaticState() throws Exception {
        // 每个用例独立：清游标/幂等键/开关，configDir 指到临时目录
        cursors().clear();
        enqueuedKeys().clear();
        setStatic("configDirCache", tmp.toAbsolutePath());
        setStatic("lastToggleCheck", -1L);
        setStatic("enabled", true);
        // 队列解析器注入：测试直接 new MemoQueue（LearnerPlugin 的类链要 NeoForge）
        queuesForTest = new java.util.concurrent.ConcurrentHashMap<>();
        LearnerAutoIntake.installQueueResolverForTest(
                id -> queuesForTest.computeIfAbsent(id, k -> {
                    try {
                        return new com.dwinovo.numen.plugins.learner.core.MemoQueue(
                                tmp.resolve("learner-memos-" + k + ".json"));
                    } catch (RuntimeException e) {
                        throw e;
                    }
                }));
        com.dwinovo.numen.plugins.learner.core.T3Seen.resetForTest();
        com.dwinovo.numen.plugins.learner.core.StormGuard.resetForTest();
        Files.createDirectories(tmp.resolve("monitor"));
    }

    @AfterEach
    void clearToggleFileAgain() throws Exception {
        // 防止别的用例意外生成开关文件影响顺序无关性
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Long> cursors() throws Exception {
        Field f = LearnerAutoIntake.class.getDeclaredField("CURSORS");
        f.setAccessible(true);
        return (java.util.Map<String, Long>) f.get(null);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Set<String> enqueuedKeys() throws Exception {
        Field f = LearnerAutoIntake.class.getDeclaredField("ENQUEUED");
        f.setAccessible(true);
        return (java.util.Set<String>) f.get(null);
    }

    private static void setStatic(String name, Object value) throws Exception {
        Field f = LearnerAutoIntake.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    private static void writeInstrumentation(Path monitorDir, String... lines) throws IOException {
        Path f = monitorDir.resolve("instrumentation.jsonl");
        Files.write(f, String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String deathLine(String eventId, String companion) {
        return deathLineAt(eventId, companion, java.time.Instant.now().toString());
    }

    private static String deathLineAt(String eventId, String companion, String ts) {
        return "{\"schema_version\":1,\"event_id\":\"" + eventId + "\",\"timestamp\":\"" + ts + "\","
                + "\"source\":\"numen\",\"category\":\"rdd_instrumentation\",\"type\":\"death\","
                + "\"data\":{\"companionId\":\"" + companion + "\",\"deathKind\":\"mob\",\"deathAttacker\":\"Zombie\","
                + "\"reason\":\"mob\",\"task\":\"subtask-x\"}}";
    }

    private static String loopLine(String eventId, String companion) {
        return "{\"schema_version\":1,\"event_id\":\"" + eventId + "\",\"timestamp\":\"" + java.time.Instant.now().toString() + "\","
                + "\"source\":\"numen\",\"category\":\"rdd_instrumentation\",\"type\":\"loop_detected\","
                + "\"data\":{\"companionId\":\"" + companion + "\",\"reason\":\"loop\"}}";
    }

    @Test
    void realFailureEventsFlowIntoQueueWithoutAnyoneCalling() throws IOException {
        writeInstrumentation(tmp.resolve("monitor"),
                deathLine("ev-1", COMPANION),
                loopLine("ev-2", COMPANION));
        int n = LearnerAutoIntake.scanOnce();
        assertEquals(2, n, "死亡+空转都是 T2 命中，应全部入队——不需要任何人调 learner_intake");
        var queue = queueForTest(COMPANION);
        assertEquals(2, queue.size());
    }

    @Test
    void replayedSameEventsAreNotEnqueuedTwice() throws IOException {
        writeInstrumentation(tmp.resolve("monitor"), deathLine("ev-1", COMPANION));
        assertEquals(1, LearnerAutoIntake.scanOnce());
        // 文件没变，再扫一次：没有新事件（游标推进），也不会重复入队
        assertEquals(0, LearnerAutoIntake.scanOnce());
        var queue = queueForTest(COMPANION);
        assertEquals(1, queue.size());
    }

    @Test
    void freshJvmSeesSameEventAgainButIdempotenceKeyStopsIt() throws Exception {
        // 重启模拟：游标/幂等键清零（进程重启即如此），但事件文件还在
        writeInstrumentation(tmp.resolve("monitor"), deathLine("ev-1", COMPANION));
        LearnerAutoIntake.scanOnce();
        // —— 模拟重启：静态状态全部回零 ——
        cursors().clear();
        enqueuedKeys().clear();
        LearnerAutoIntake.scanOnce();
        var queue = queueForTest(COMPANION);
        // 游标文件跨重启存着字节偏移 → 第二次从偏移起读 → 0 新事件 → 不会重复入队
        assertEquals(1, queue.size(), "重启后游标从盘上恢复，同一事件不得二次入队");
    }

    @Test
    void staleEventsAreCountedButNotEnqueued() throws IOException {
        // 2026-11-02：换代重扫会把 10-31 从未入过队的老事件当新事件（队列 56→64）。
        // 新鲜度闸：超过 2h 的事件只计 stale，不入队。
        String oldTs = java.time.Instant.now().minus(java.time.Duration.ofDays(2)).toString();
        writeInstrumentation(tmp.resolve("monitor"),
                deathLineAt("ev-old", COMPANION, oldTs),
                deathLine("ev-new", COMPANION));
        int n = LearnerAutoIntake.scanOnce();
        assertEquals(1, n, "只有新鲜事件入队");
        assertEquals(1, queueForTest(COMPANION).size(), "两天前的死亡不得在今天才变成新 memo");
    }

    @Test
    void eventWithoutTimestampTakesFreshPath() throws IOException {
        // 刻意的语义：没有时间戳 = 无法证明它老，走新鲜路径（宁可保留，不静默丢）。
        writeInstrumentation(tmp.resolve("monitor"), deathLineAt("ev-no-ts", COMPANION, ""));
        assertEquals(1, LearnerAutoIntake.scanOnce());
        assertEquals(1, queueForTest(COMPANION).size());
    }

    @Test
    void staleDeferredEntryIsDroppedInsteadOfRequeued() throws IOException {
        // 2026-11-02：admitDeferred 原来绕过 2h 闸 —— 10-31 被 StormGuard 抑制的老条目
        // 冷却到期后从后门放回（17:39 deferred_admitted=4 全是老的）。
        java.util.UUID id = java.util.UUID.fromString(COMPANION);
        long threeDaysAgo = System.currentTimeMillis() - 3L * 24 * 3600 * 1000;
        var old = new com.dwinovo.numen.plugins.learner.core.Memo(
                "ai-instr-old-suppressed", "death old", "death", "tried", "snap", threeDaysAgo);
        com.dwinovo.numen.plugins.learner.core.SuppressedStore.append(
                tmp, id, old, "fp-old", "SUPPRESSED_COOLDOWN", threeDaysAgo);

        writeInstrumentation(tmp.resolve("monitor"));
        assertEquals(0, LearnerAutoIntake.scanOnce());
        assertEquals(0, queueForTest(COMPANION).size(), "3 天前的暂存条目不得从后门放回");
    }

    @Test
    void seenHitDeferredEntryIsNotAdmittedAgain() throws IOException {
        // append=true 但 seen 命中：不得计 admitted、不得再写 journal。
        java.util.UUID id = java.util.UUID.fromString(COMPANION);
        long recent = System.currentTimeMillis() - 11 * 60 * 1000;   // 已过 10min 冷却、且未过 2h
        var m = new com.dwinovo.numen.plugins.learner.core.Memo(
                "ai-instr-seen-deferred", "p", "death", "tried", "snap", recent);
        var queue = queueForTest(COMPANION);
        assertTrue(queue.append(m));
        assertTrue(queue.commitDrain(queue.drain(1)) > 0, "先入队再复核移除 → id 进 seen");

        com.dwinovo.numen.plugins.learner.core.SuppressedStore.append(
                tmp, id, m, "fp", "SUPPRESSED_COOLDOWN", recent);
        Path journal = tmp.resolve("learner-enqueued.jsonl");
        long before = Files.exists(journal) ? Files.readAllLines(journal).size() : 0;

        writeInstrumentation(tmp.resolve("monitor"));
        assertEquals(0, LearnerAutoIntake.scanOnce());
        assertEquals(0, queue.size(), "seen 命中的暂存条目不得重新入队");
        long after = Files.exists(journal) ? Files.readAllLines(journal).size() : 0;
        assertEquals(before, after, "不得再写重复 journal 行");
    }

    @Test
    void dueDeferredEntryGoesBackToStoreWhenQueueIsFull() throws IOException {
        // 队列满时：不放回、也不丢 —— 回暂存区等下一轮。
        var queue = queueForTest(COMPANION);
        java.util.UUID id = java.util.UUID.fromString(COMPANION);
        for (int i = 0; i < 64; i++) {
            queue.append(new com.dwinovo.numen.plugins.learner.core.Memo(
                    "fill-" + i, "p", "death", "t", "s", System.currentTimeMillis()));
        }
        assertEquals(64, queue.size(), "先把队列塞满");
        long recent = System.currentTimeMillis() - 11 * 60 * 1000;
        var m = new com.dwinovo.numen.plugins.learner.core.Memo(
                "ai-instr-due-full", "p", "death", "tried", "snap", recent);
        com.dwinovo.numen.plugins.learner.core.SuppressedStore.append(
                tmp, id, m, "fp", "SUPPRESSED_COOLDOWN", recent);

        writeInstrumentation(tmp.resolve("monitor"));
        int n = LearnerAutoIntake.scanOnce();
        assertEquals(0, n);
        assertEquals(64, queue.size(), "满队列不能再进");
        assertTrue(Files.readString(
                        com.dwinovo.numen.plugins.learner.core.SuppressedStore.fileFor(tmp, id))
                .contains("ai-instr-due-full"), "写不进队列的条目必须回暂存区，不能丢");
    }

    @Test
    void deathInQueueReachesNudgeThresholdEvenWhenAlone() {
        // 目标要求：每一次死亡都要变成经验，不能等后面两次失败才触发复盘。
        assertEquals(1, LearnerAutoIntake.effectiveDepth(1, false));
        assertEquals(3, LearnerAutoIntake.effectiveDepth(1, true));
        assertEquals(5, LearnerAutoIntake.effectiveDepth(5, true));
    }

    @Test
    void nudgeTextReportsTheRealCountEvenWhenDeathLowersTheGate() {
        // 审核窗口 06:08：death 只降门槛，不许把「1 条」说成「3 条」。
        String text = LearnerAutoIntake.nudgeText(1);
        assertTrue(text.contains("队列里有 1 条"), text);
        assertFalse(text.contains("队列里有 3 条"), text);
    }

    @Test
    void autoReviewOnlyWhenPreviousNudgeWentUnanswered() {
        long cooldown = 30L * 60L * 1000L;
        long now = 100_000_000L;
        // 队列太浅
        assertFalse(LearnerAutoIntake.autoReviewDue(now - 700_000L, now - 900_000L, null, now, 2, false));
        // 没有上一条提醒
        assertFalse(LearnerAutoIntake.autoReviewDue(null, 0L, null, now, 5, false));
        // 上一条提醒距现在不到 10 分钟 → 不抢跑（与 nudge 冷却联动，显式钉住）
        assertFalse(LearnerAutoIntake.autoReviewDue(now - 5L * 60_000L, now - 900_000L, null, now, 5, false));
        // 上次提醒之后有 review 启动 → 有人响应，不兜底
        assertFalse(LearnerAutoIntake.autoReviewDue(now - 700_000L, now - 100_000L, null, now, 5, false));
        // 已在跑
        assertFalse(LearnerAutoIntake.autoReviewDue(now - 700_000L, now - 900_000L, null, now, 5, true));
        // 30 分钟冷却内不再兜底
        assertFalse(LearnerAutoIntake.autoReviewDue(now - 700_000L, now - 900_000L, now - 10_000L, now, 5, false));
        // 上次提醒无人响应 + 冷却已过 → 兜底
        assertTrue(LearnerAutoIntake.autoReviewDue(now - 700_000L, now - 900_000L, now - cooldown, now, 5, false));
    }

    @Test
    void eventsForOtherCompanionsAreNotAttributedToMine() throws IOException {
        String other = "e44fe5f8-0000-0000-0000-000000000002";
        writeInstrumentation(tmp.resolve("monitor"),
                deathLine("ev-1", other),
                deathLine("ev-2", COMPANION));
        int n = LearnerAutoIntake.scanOnce();
        // 两个同伴各自的死亡 → 各入各的队列（2 是对的）；判错的是"把别人的写进我的"
        assertEquals(2, n, "每个同伴的死亡都该进它自己的队列——自动口不挑同伴");
        assertEquals(1, queueForTest(COMPANION).size(), "我的队列只有我的死亡");
        assertEquals(1, queueForTest(other).size(), "它的队列只有它的死亡");
    }

    @Test
    void toggleOffStopsAutoIntakeEntirely() throws Exception {
        writeInstrumentation(tmp.resolve("monitor"), deathLine("ev-1", COMPANION));
        // 直接把 enabled 拨到 false（文件版测试用反射太绕；reloadToggle 已有自己的路径）
        setStatic("enabled", false);
        setStatic("lastToggleCheck", System.currentTimeMillis() + 3600_000L);   // 10 秒内不再重读开关
        assertEquals(0, LearnerAutoIntake.scanOnce());
        var queue = queueForTest(COMPANION);
        assertEquals(0, queue.size());
        assertFalse(LearnerAutoIntake.enabled());
    }

    @Test
    void generationChangeDoesNotDuplicateEnqueue() throws Exception {
        // 2026-10-25 实机抓的：世界重载 → 新 generation → 游标归零 → 81 条历史重扫，
        // 新 JVM 的内存幂等集是空的 → 同 8 条事件再次入队（e44 队列 15→21）。
        // 修复=幂等集落盘（learner-enqueued.jsonl）。本用例模拟两次独立 JVM。
        writeInstrumentation(tmp.resolve("monitor"), deathLine("ev-1", COMPANION));
        assertEquals(1, LearnerAutoIntake.scanOnce());
        assertEquals(1, queueForTest(COMPANION).size());

        // —— 模拟重启 + 换存档代：内存全清，游标清零（新 generation 从 0 扫）——
        setStatic("configDirCache", tmp.toAbsolutePath());
        enqueuedKeys().clear();
        cursors().clear();
        // 注意：configDirCache 指向同一个 tmp，盘上的 journal 与游标文件都还在
        assertEquals(0, LearnerAutoIntake.scanOnce(),
                "盘上幂等日志挡住换代重扫——同一事件不得二次入队");
        assertEquals(1, queueForTest(COMPANION).size());
        assertTrue(java.nio.file.Files.isRegularFile(tmp.resolve("learner-enqueued.jsonl")),
                "幂等日志必须落盘（否则下次重启又是空的）");
    }

    @Test
    void unreadableSourceReportsFailureAndDoesNotThrow() throws IOException {
        // instrumentation.jsonl 不存在：读失败 → 0 入队 + 不抛（增强旁路不许拖垮调用方）
        assertEquals(0, LearnerAutoIntake.scanOnce());
    }
}
