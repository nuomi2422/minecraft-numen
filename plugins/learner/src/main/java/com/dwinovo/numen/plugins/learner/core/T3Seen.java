package com.dwinovo.numen.plugins.learner.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * T3「同一问题重复出现」的会话内计数表 —— 手动口与自动口<b>共用同一张</b>。
 *
 * <h2>为什么要单独一个类（2026-10-25）</h2>
 * 它原来长在 {@code LearnerIntakeTool} 的静态字段里。自动入队也要用它，但那个类
 * {@code implements NumenTool}，类加载会拖起整条 NeoForge 链 —— 纯 JVM 单测一碰就
 * {@code NoClassDefFoundError}。计数逻辑本身是纯的，不该被工具的宿主依赖绑架。
 *
 * <h2>诚实边界（不变）</h2>
 * 表是进程内的：重启即清零。调用方必须如实把这一点告诉消费者
 * （手动口的 `t3_state=session_only`），不许假装它是跨会话的代际计数。
 * 跨会话持久化是 81 号 §14 的 Q4，另批施工，不在这里偷偷做。
 *
 * <h2>键的纪律（LC2，78 号 §2.2）</h2>
 * 键必须是「companionId|指纹」：全局裸指纹会把 A 同伴的死亡计进 B 的模式，
 * 跨伙伴放大判定。见 {@code StormGuardTest.伙伴之间互不影响}。
 */
public final class T3Seen {

    private T3Seen() {}

    /** 表上限：超了整表清空（宁可丢计数，不要长成一坨内存）。与工具内旧实现同值。 */
    public static final int MAX_ENTRIES = 512;

    private static final Map<String, Integer> SEEN = new ConcurrentHashMap<>();

    /** 当前计数（不含本次）。 */
    public static int seenBefore(String companionId, String fingerprint) {
        return SEEN.getOrDefault(key(companionId, fingerprint), 0);
    }

    /** 计数 +1（含上限自清）。 */
    public static void bump(String companionId, String fingerprint) {
        if (SEEN.size() >= MAX_ENTRIES) {
            SEEN.clear();
        }
        SEEN.merge(key(companionId, fingerprint), 1, Integer::sum);
    }

    /**
     * 跨会话重建（81 号 §14.1 Q4：两个身份分开）：
     *
     * <ul>
     *   <li><b>事件身份</b>（同一事件重放只算一次）—— 由 LearnerAutoIntake 的
     *       幂等键（memo id = ai-<eventId>）与盘上**游标**负责；游标按存档代际落盘，
     *       重放的事件根本不会再被读到。</li>
     *   <li><b>问题指纹</b>（三次独立死亡属于同类问题，应累计三次）—— 由本方法负责：
     *       启动时把 {@code instrumentation.jsonl} **从头重放一遍**，把每个同伴的
     *       T2 类事件按指纹计数灌回本表。真源就是那份 append-only 的事件文件本身，
     *       <b>不另建计数账本</b>（核准回执：优先从既有持久记录恢复）。</li>
     * </ul>
     *
     * <p>为什么重放而不是存计数文件：计数文件是第二真源，和事件流必然漂移
     * （事件被轮转、计数没跟上）；重放的代价是一次线性扫描（当前文件 ~百行级），
     * 换来的是「计数永远可以从真源重新推导」——这与 LC3 的「日常样本可复算」同一条纪律。
     *
     * <p>时间范围：从**本存档代际**的第一条事件起算（FeedbackChannel.pull 按
     * generation 过滤），旧存档的旧问题不永久累加（核准回执：保留时间范围，
     * 避免旧版本问题永久计入）。
     *
     * @param events 本存代的全部历史事件（调用方用 FeedbackChannel 从头拉）
     * @param gate  判定器（只把 T2 能认的事件计进去；用 CandidateGate.fingerprint 同一算法）
     */
    public static int rebuildFrom(Iterable<FeedbackEvent> events) {
        resetForTest();
        java.util.Set<String> seenEventIds = new java.util.HashSet<>();
        int counted = 0;
        for (FeedbackEvent ev : events) {
            // 事件身份：同一 eventId（重放/轮转副本）只算一次。
            if (ev.eventId() == null || !seenEventIds.add(ev.eventId())) continue;
            String companion = companionKey(ev);
            if (companion == null) continue;
            String fingerprint = CandidateGate.fingerprint(ev.sourceType(), ev.observation());
            // 只数 T2 类（失败族）事件：成功/中性事件数进指纹表只会稀释"重复失败"信号。
            if (!isFailureFamily(ev.sourceType())) continue;
            bump(companion, fingerprint);
            counted++;
        }
        return counted;
    }

    private static String companionKey(FeedbackEvent ev) {
        return CandidateMemo.companionOf(ev);
    }

    private static boolean isFailureFamily(String sourceType) {
        if (sourceType == null) return false;
        return switch (sourceType) {
            case "death", "starvation_death", "loop_detected", "repeat_gather",
                 "recovery_failed", "asset_mismatch", "resource_waste" -> true;
            default -> false;
        };
    }

    /** 测试用：清表。 */
    public static void resetForTest() {
        SEEN.clear();
    }

    private static String key(String companionId, String fingerprint) {
        return (companionId == null ? "" : companionId) + "|" + (fingerprint == null ? "" : fingerprint);
    }
}
