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

    /** 测试用：清表。 */
    public static void resetForTest() {
        SEEN.clear();
    }

    private static String key(String companionId, String fingerprint) {
        return (companionId == null ? "" : companionId) + "|" + (fingerprint == null ? "" : fingerprint);
    }
}
