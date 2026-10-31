package com.dwinovo.numen.plugins.learner.sample;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 学习样本的生命周期状态（158 §8）。
 *
 * <h2>为什么要有状态机，而不是一个 boolean</h2>
 * 158 §8 把「样本在安全停顿点批量复盘」写成一条链：样本先攒着（{@code OPEN}），
 * 攒够了/超时了才 {@code READY_FOR_REVIEW}，被某次复盘取走时 {@code IN_REVIEW}，
 * 复盘产出并通过质量门才 {@code RESOLVED}，最后 TTL 归档 {@code ARCHIVED}。
 * 这些阶段各自是<b>不同证据</b>：入队 ≠ 复盘过，复盘过 ≠ 出结论。
 * 用一个 boolean 或一个 {@code reviewed} 字段表达，必然出现「复盘失败了但标记已复盘」
 * 这类不可回查的错误。所以这里用显式状态 + 显式迁移表。
 *
 * <h2>为什么 DEFERRED / INVALID / DUPLICATE 是三个不同的词</h2>
 * 它们是三种完全不同的结局，合并会丢信息：
 * <ul>
 *   <li>{@code DEFERRED} —— 现在不评，以后还要评（预算不足、安全窗口不对）；</li>
 *   <li>{@code INVALID} —— 样本本身不成立（证据缺失、世界代际已换），以后也不评；</li>
 *   <li>{@code DUPLICATE} —— 已确认与别的样本同源，证据并到那条去，本条不再单独评。</li>
 * </ul>
 * 把 DUPLICATE 写成 INVALID 会让「去重到底省了多少复盘」这个指标永远算不出来。
 *
 * <h2>ARCHIVED 是终态，而且只归档不删除</h2>
 * 158 §8 明确「TTL 只归档，不删除证据」。所以 ARCHIVED 之后没有出边，
 * 但样本对象和它的 occurrence 仍然可读 —— 归档的是<b>待办</b>，不是事实。
 *
 * <p>纯 JVM、无 MC/NUMEN 依赖，可离线单测。
 */
public enum SampleState {

    /** 已登记，还没进复盘窗口。158 §8 的默认起点。 */
    OPEN,

    /** 到了复盘窗口（数量/超时/高优先级/任务停顿/运行结束之一）。 */
    READY_FOR_REVIEW,

    /** 已被某次复盘取走。同一同伴同一时刻只应有一条 IN_REVIEW，见 LearnerReviewTool 的互斥。 */
    IN_REVIEW,

    /** 复盘出了结论且产物过了门。注意：<b>不等于</b>产物已生效。 */
    RESOLVED,

    /** TTL 到期归档。终态；证据仍可读。 */
    ARCHIVED,

    /** 现在不评，以后还要评。 */
    DEFERRED,

    /** 样本不成立，以后也不评。 */
    INVALID,

    /** 已确认与既有样本同源，并到那条去。 */
    DUPLICATE;

    private static final Map<SampleState, Set<SampleState>> NEXT = new EnumMap<>(SampleState.class);

    static {
        NEXT.put(OPEN, EnumSet.of(READY_FOR_REVIEW, DEFERRED, INVALID, DUPLICATE));
        NEXT.put(READY_FOR_REVIEW, EnumSet.of(IN_REVIEW, DEFERRED, INVALID, DUPLICATE));
        // 延迟的样本必须能回来，否则一次预算不足就永久埋掉一个真实失败。
        NEXT.put(DEFERRED, EnumSet.of(READY_FOR_REVIEW, INVALID, DUPLICATE));
        NEXT.put(IN_REVIEW, EnumSet.of(RESOLVED, DEFERRED, INVALID));
        NEXT.put(RESOLVED, EnumSet.of(ARCHIVED));
        NEXT.put(ARCHIVED, EnumSet.noneOf(SampleState.class));
        NEXT.put(INVALID, EnumSet.noneOf(SampleState.class));
        NEXT.put(DUPLICATE, EnumSet.noneOf(SampleState.class));
    }

    /** 是否允许迁移到 {@code target}。自己到自己一律不允许（重复写状态多半是并发 bug）。 */
    public boolean canMoveTo(SampleState target) {
        if (target == null || target == this) {
            return false;
        }
        return NEXT.get(this).contains(target);
    }

    /** 是否还能被复盘取走。 */
    public boolean reviewable() {
        return this == OPEN || this == READY_FOR_REVIEW || this == DEFERRED;
    }

    /** 是否已经「了结」（不再占用复盘待办）。 */
    public boolean terminal() {
        return this == ARCHIVED || this == INVALID || this == DUPLICATE;
    }
}
