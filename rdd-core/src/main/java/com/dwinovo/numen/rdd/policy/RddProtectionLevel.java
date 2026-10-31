package com.dwinovo.numen.rdd.policy;

/**
 * 保护级别（B6 / S6「连续死亡提高保护级别」的定级层）。
 *
 * <p>为什么单独一层而不是直接塞进 {@link ResourceBudget}：「死了几次」是事实，
 * 「因此要多苛刻」是策略。分开之后策略可以单测，不必为了验一条规则去跑世界。
 *
 * <p>升到 {@link #HIGH} 的门槛取 >=2 次窗口内未回收死亡：一次可能只是倒霉，两次才说明保护不够。
 */
public enum RddProtectionLevel {

    /** 窗口内没有未回收死亡。 */
    LOW,

    /** 窗口内有 1 次未回收死亡：维持现状，不加码。 */
    NORMAL,

    /** 窗口内 >=2 次未回收死亡：备用要求升一档。 */
    HIGH;

    /** 窗口内未回收死亡次数 -> 级别。负数按 0 处理（调用方不该传，但别让它炸）。 */
    public static RddProtectionLevel of(int recentUnrecoveredDeaths) {
        if (recentUnrecoveredDeaths >= 2) return HIGH;
        if (recentUnrecoveredDeaths == 1) return NORMAL;
        return LOW;
    }

    /** 是否需要加码（只有 HIGH）。 */
    public boolean escalates() {
        return this == HIGH;
    }
}
