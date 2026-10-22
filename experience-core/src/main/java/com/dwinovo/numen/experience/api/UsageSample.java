package com.dwinovo.numen.experience.api;

/**
 * 一条「使用样本」（G6a/IC11）：某条经验在某次尝试的某个相位被用到，最后落到哪个终态。
 *
 * <p><b>为什么是样本而不是计数器</b>：计数器会漂（谁回滚？谁补偿？），
 * 而样本是只增不改的事实，任何时候都能重算出同一份结论（见 {@link UsageLedger}）。
 *
 * <p><b>四个身份维度</b>（去重键 = 这四个一起）：
 * <ul>
 *   <li>{@code entryRef} —— 哪条经验；</li>
 *   <li>{@code attempt} —— 第几次尝试（同一产物被重派时 +1，用于「先取消后成功」不串结论）；</li>
 *   <li>{@code phase} —— 检索相位（planner/executor/learner），同一轮里可能各用一次；</li>
 *   <li>{@code artifactId} —— 具体产物/任务 id（父子样本靠它归到一起）。</li>
 * </ul>
 *
 * <p>{@code version} 是条目内容 hash：**缺版本不按名字猜**，不同版本分开统计。
 */
public record UsageSample(
        String entryRef,
        int attempt,
        String phase,
        String artifactId,
        String version,
        UsageState state,
        long atMillis) {

    public UsageSample {
        entryRef = trim(entryRef);
        phase = trim(phase);
        artifactId = trim(artifactId);
        version = trim(version);
        state = state == null ? UsageState.UNKNOWN : state;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * 「同一次投递」判定：四个身份维度 + **同一时间戳**全等（不看 state）。
     *
     * <p>为什么要把时间戳算进去：	1/	2 这类任务 id **每个会话都会重用**，
     * 只按 id 去重会把昨天和今天的 	1 吞成一条（2026-10-22 L6 用真数据踩到）。
     * 而「同一个事件被转述两次」时间戳必然相同，所以加上它既保住去重、又不误吞跨会话的同名 id。
     */
    public boolean sameDelivery(UsageSample other) {
        return other != null
                && entryRef.equals(other.entryRef)
                && attempt == other.attempt
                && phase.equals(other.phase)
                && artifactId.equals(other.artifactId)
                && atMillis == other.atMillis;
    }
}
