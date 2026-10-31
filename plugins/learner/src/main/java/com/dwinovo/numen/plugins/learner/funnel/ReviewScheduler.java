package com.dwinovo.numen.plugins.learner.funnel;

/**
 * 复盘调度的触发判定（158 §8，159 H）。
 *
 * <h2>五种触发，一个顺序</h2>
 * 158 §8：达到数量、普通样本等待超时、高优先级样本、任务安全切换点或运行结束。
 * 顺序不是随意排的，按「错过就没了」的程度：
 * <ol>
 *   <li><b>运行结束</b> —— 再不做，这批样本就随存档沉底了；</li>
 *   <li><b>高优先级</b> —— 死亡/严重受伤这类，晚做的价值快速衰减；</li>
 *   <li><b>数量阈值</b> —— 常规批量；</li>
 *   <li><b>等待超时</b> —— 样本少但放太久了，再放就失真；</li>
 *   <li><b>任务安全停顿</b> —— 最不紧急，但比"永远不做"好。</li>
 * </ol>
 *
 * <h2>空队列绝不触发</h2>
 * 158 §8 原文：「空队列不得重复唤起」。这不是优化，是防"复盘空转烧预算"——
 * 一次空复盘会真的调用一次 LLM，成本比多等一分钟高得多。
 *
 * <p>纯 JVM、无时钟依赖（时刻由调用方传），可离线单测。
 */
public final class ReviewScheduler {

    /**
     * 队列现状（由调用方采集）。
     *
     * @param pending       待复盘样本数
     * @param highPriority  其中高优先级（死亡/严重受伤等）的数量
     * @param oldestPendingAt 最老待复盘样本的时间；{@code 0} 表示未知
     * @param taskSafePause 当前是否处于任务安全停顿点
     * @param runEnded      本次运行是否已结束
     */
    public record Queue(int pending, int highPriority, long oldestPendingAt,
                        boolean taskSafePause, boolean runEnded) {
        public Queue {
            pending = Math.max(0, pending);
            highPriority = Math.max(0, highPriority);
        }

        public static Queue empty() {
            return new Queue(0, 0, 0L, false, false);
        }
    }

    /** 触发原因。 */
    public enum Trigger {
        NONE,
        RUN_END,
        HIGH_PRIORITY,
        COUNT,
        TIMEOUT,
        SAFE_PAUSE
    }

    private ReviewScheduler() {
    }

    /**
     * 该不该现在复盘。
     *
     * @param countThreshold 数量阈值；{@code <= 0} 视为不启用
     * @param timeoutMillis  等待超时；{@code <= 0} 视为不启用
     */
    public static Trigger due(Queue q, long nowMillis, int countThreshold, long timeoutMillis) {
        if (q == null || q.pending() <= 0) {
            // 空队列不唤起：一次空复盘就是一次真实的模型调用。
            return Trigger.NONE;
        }
        if (q.runEnded()) {
            return Trigger.RUN_END;
        }
        if (q.highPriority() > 0) {
            return Trigger.HIGH_PRIORITY;
        }
        if (countThreshold > 0 && q.pending() >= countThreshold) {
            return Trigger.COUNT;
        }
        if (timeoutMillis > 0 && q.oldestPendingAt() > 0
                && nowMillis - q.oldestPendingAt() >= timeoutMillis) {
            return Trigger.TIMEOUT;
        }
        if (q.taskSafePause()) {
            return Trigger.SAFE_PAUSE;
        }
        return Trigger.NONE;
    }
}
