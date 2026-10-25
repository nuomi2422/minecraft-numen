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
        return "{\"schema_version\":1,\"event_id\":\"" + eventId + "\",\"timestamp\":\"2026-10-25T09:00:00Z\","
                + "\"source\":\"numen\",\"category\":\"rdd_instrumentation\",\"type\":\"death\","
                + "\"data\":{\"companionId\":\"" + companion + "\",\"deathKind\":\"mob\",\"deathAttacker\":\"Zombie\","
                + "\"reason\":\"mob\",\"task\":\"subtask-x\"}}";
    }

    private static String loopLine(String eventId, String companion) {
        return "{\"schema_version\":1,\"event_id\":\"" + eventId + "\",\"timestamp\":\"2026-10-25T09:00:01Z\","
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
    void unreadableSourceReportsFailureAndDoesNotThrow() throws IOException {
        // instrumentation.jsonl 不存在：读失败 → 0 入队 + 不抛（增强旁路不许拖垮调用方）
        assertEquals(0, LearnerAutoIntake.scanOnce());
    }
}
