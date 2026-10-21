package com.dwinovo.numen.task;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 最近若干条走到终态的任务，按 {@link TaskRecord#publicId()} 索引。
 *
 * <p><b>为什么需要（AC-B12b 复审，2026-10-21）</b>：完成探针原先只看「最近一次终态」这一个槽
 * （{@code CompanionBrain.lastFinished}）。如果 AC 的异步任务 {@code t1} 成功结算后、探针再次
 * 被调用前，又有任务 {@code t2} 结算，{@code lastFinished} 就被 {@code t2} 覆盖；探针按
 * {@code t1} 去查会得到「终态属于别的任务」→ 把**真正成功**的 t1 报成 failed（false negative）。
 * 保留一小段按任务号索引的终态记录，就能按 id 精确回答，而不是靠「最近一个」猜。
 *
 * <p>为什么有界：终态记录只在 AC resume 时被查一次，保留最近 {@value #MAX_RECORDS} 条足够覆盖
 * 正常的暂停/恢复窗口；无界会让长跑进程慢慢攒住已经没人问的任务结果。
 *
 * <p>线程模型：{@code onSettled} 在服务端 tick 线程调用，探针在 AC 引擎线程读 —— 全部方法
 * {@code synchronized}，{@link #find} 返回的是已经不可变的终态记录引用。
 */
final class RecentFinishedTasks {

    /** 保留条数：AC 的暂停/恢复窗口通常只有几条任务，32 条足够且内存有界。 */
    static final int MAX_RECORDS = 32;

    private final Map<String, TaskRecord> byId = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TaskRecord> eldest) {
            return size() > MAX_RECORDS;
        }
    };

    /** 记一条终态任务；null / 无 id 的记录直接忽略（不许用空键制造假命中）。 */
    synchronized void remember(TaskRecord record) {
        if (record == null) return;
        String id = record.publicId();
        if (id == null || id.isBlank()) return;
        byId.put(id, record);
    }

    /** 按任务号查最近终态；查不到返回 null（调用方必须按「不知道成败」处理，不许当成功）。 */
    synchronized TaskRecord find(String taskId) {
        if (taskId == null || taskId.isBlank()) return null;
        return byId.get(taskId);
    }

    /** 测试/诊断用：当前保留条数。 */
    synchronized int size() {
        return byId.size();
    }
}
