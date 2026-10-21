package com.dwinovo.numen.task;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 同伴的<b>终态任务表</b>：按 {@code publicId()}（{@code tN}）索引最近结算/丢弃的任务。
 *
 * <p><b>为什么不是 {@code CompanionBrain.recentFinished}</b>（AC-B12b-3，2026-10-21 长跑实测）：
 * 身体死亡时 {@code CompanionTickDispatcher.onCompanionRemoved} 会
 * {@code finalizeActive}（把 CANCELLED 写进 brain 的表）之后立刻 {@code BRAINS.remove(id)} ——
 * <b>brain 连同那张表一起被扔掉</b>。复活时同一 UUID 拿到的是新 brain，表是空的
 * ⇒ AC 探针按 id 查不到 ⇒ 退化成「槽已空且找不到该任务的终态记录」= UNKNOWN（stale-null）。
 *
 * <p>所以终态记录必须<b>按同伴 UUID 独立存在</b>，不随身体/大脑的生死。
 * 每 UUID 有界（{@link RecentFinishedTasks#MAX_RECORDS} 条），防止长跑无限攒。
 *
 * <p>线程模型：写入在服务端 tick 线程（{@code TaskSlot.onSettled}），读取在 AC 引擎线程；
 * {@code ConcurrentHashMap} + 每同伴 {@code synchronized} 的 {@link RecentFinishedTasks}。
 */
final class TaskTerminalRegistry {

    /** 每个同伴保留条数（与 {@link RecentFinishedTasks#MAX_RECORDS} 同口径）。 */
    static final int MAX_PER_COMPANION = RecentFinishedTasks.MAX_RECORDS;

    private static final Map<UUID, RecentFinishedTasks> BY_COMPANION = new ConcurrentHashMap<>();

    private TaskTerminalRegistry() {}

    /** 记一条终态；companion 为 null / 记录无 id 时忽略（不制造假命中）。 */
    static void remember(UUID companion, TaskRecord record) {
        if (companion == null || record == null) return;
        BY_COMPANION.computeIfAbsent(companion, k -> new RecentFinishedTasks()).remember(record);
    }

    /** 按任务号查；查不到返回 null（调用方按「不知道成败」处理，不算失败）。 */
    static TaskRecord find(UUID companion, String taskId) {
        if (companion == null) return null;
        RecentFinishedTasks table = BY_COMPANION.get(companion);
        return table == null ? null : table.find(taskId);
    }

    /** 该同伴当前保留条数（测试/诊断）。 */
    static int size(UUID companion) {
        if (companion == null) return 0;
        RecentFinishedTasks table = BY_COMPANION.get(companion);
        return table == null ? 0 : table.size();
    }

    /** 清掉一个同伴的表（测试 / 换人设时用；生产路径没有调用点）。 */
    static void clear(UUID companion) {
        if (companion != null) BY_COMPANION.remove(companion);
    }
}
