package com.dwinovo.numen.plugins.learner.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LC2（78 号工作单 §5/§7）：<b>失败风暴抑制</b> —— 同一个（同伴，问题指纹）在冷却窗口内
 * 只放行<b>第一次</b>候选入队，其余全部如实计数抑制。
 *
 * <h2>它防的是哪件事</h2>
 * 实测形态：一次 {@code learner_intake} pull 回 30 条同指纹 {@code loop_detected}——
 * 没有这一层，30 条全部入队，复盘队列被一次失败风暴灌满，学习预算（AI 注意力）被烧光
 * 在 30 份一模一样的候选上（78 号 §5「失败风暴烧光学习预算」）。
 *
 * <h2>与 T3 计数的分工（关键，别混）</h2>
 * {@link CandidateGate} 的 {@code seenBefore} 照常数<b>每一次</b>出现（模式检测：一次是意外，
 * 两次就是模式）；本类只把<b>入队</b>节流。也就是说：风暴的事实全留（SEEN 计数、
 * {@code suppressed} 读数），只是不重复打扰学习者 —— 78 号 §1「立即写原始事件/学习候选，
 * 去重与预算后处理」的「后处理」就是这里。
 *
 * <h2>指纹带伙伴身份</h2>
 * 键是 {@code (companionId, fingerprint)} —— A 同伴的死亡风暴不许替 B 同伴挡路
 * （78 号 §2.2「指纹计数须加入伙伴身份，避免跨伙伴放大」）。B 遇到同一问题时
 * 是它自己的第一次，照常入队。
 *
 * <h2>冷却窗口过后放行</h2>
 * 冷却是「别灌满队列」，不是「这问题不许再学」。窗口过后同一指纹再出现仍然入队 ——
 * 此时 T3 的 seenBefore 已经数到更高，候选 memo 里的模式计数是真实的。
 *
 * <h2>session-only（诚实边界）</h2>
 * 与 {@code LearnerIntakeTool.SEEN} 同一哲学：跨重启从头数，掉电的代价是
 * 「重启后第一条候选可能重复入一次队」，不是「记错了」。不假装跨代际。
 *
 * <p><b>纯 JVM、无状态依赖注入时钟</b>：{@code nowMillis} 由调用方传，
 * 单测里可以拨表。线程安全：静态 map + synchronized（调用点在工具线程，O(1) 操作）。
 */
public final class StormGuard {

    /**
     * 冷却窗口。⚠️ <b>未经实测校准</b>：取「比一次常规任务窗口长、比一次游戏会话短」
     * 的中间值（与 {@code ExperienceStore.DEMOTE_AFTER_CONSECUTIVE_FAILURES} 同款说明）。
     * 真实抑制率落在 {@code suppressed} 公开读数上 ⇒ 攒够样本回来校准这个常数。
     */
    public static final long COOLDOWN_MILLIS = 10 * 60 * 1000L;

    /** 每个键最多记这么多条状态（防长跑会话内存无界；与 SEEN 同一纪律）。 */
    static final int MAX_TRACKED = 512;

    /** 判定结论。{@code suppressedTotal} 是该键本会话累计被抑制次数（如实，不静默）。 */
    public record Ruling(boolean allow, String code, long windowMillis,
                         int suppressedTotal, long lastAdmittedAt) {

        public Ruling {
            code = code == null ? "ALLOW" : code;
        }

        /** 面板/回执读数。 */
        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("allow", allow);
            m.put("code", code);
            m.put("window_millis", windowMillis);
            m.put("suppressed_total", suppressedTotal);
            return m;
        }
    }

    /** (companionId|fingerprint) → 状态。 */
    private static final Map<String, State> STATES = new LinkedHashMap<>();

    private static final class State {
        long lastAdmittedAt = Long.MIN_VALUE;
        int suppressed;
    }

    private StormGuard() {}

    /**
     * 判定这一条要不要放行入队。**只判定，不负责计数入队以外的事实**——
     * 调用方（LearnerIntakeTool）在调本方法之前就该把 T3 的 seen 计数做完。
     */
    public static synchronized Ruling admit(String companionId, String fingerprint, long nowMillis) {
        String key = keyOf(companionId, fingerprint);
        State st = STATES.computeIfAbsent(key, k -> {
            if (STATES.size() >= MAX_TRACKED) {
                STATES.clear();   // 与 SEEN 同款：宁可丢节流状态，也不长成内存债
            }
            return new State();
        });
        if (st.lastAdmittedAt != Long.MIN_VALUE
                && nowMillis - st.lastAdmittedAt < COOLDOWN_MILLIS) {
            st.suppressed++;
            return new Ruling(false, "SUPPRESSED_COOLDOWN",
                    COOLDOWN_MILLIS - (nowMillis - st.lastAdmittedAt),
                    st.suppressed, st.lastAdmittedAt);
        }
        st.lastAdmittedAt = nowMillis;
        return new Ruling(true, "ALLOW", COOLDOWN_MILLIS, st.suppressed, nowMillis);
    }

    /** 某键目前的抑制读数（只读）。 */
    public static synchronized int suppressedTotal(String companionId, String fingerprint) {
        State st = STATES.get(keyOf(companionId, fingerprint));
        return st == null ? 0 : st.suppressed;
    }

    /** 全局读数（monitor 事件用）：本会话累计抑制多少条。 */
    public static synchronized int suppressedGrandTotal() {
        int n = 0;
        for (State st : STATES.values()) {
            n += st.suppressed;
        }
        return n;
    }

    /** 清空（只给测试用；运行时没有「清空节流账」这回事）。 */
    static synchronized void resetForTest() {
        STATES.clear();
    }

    private static String keyOf(String companionId, String fingerprint) {
        return (companionId == null ? "" : companionId.trim()) + "|" + (fingerprint == null ? "" : fingerprint);
    }
}
