package com.dwinovo.numen.agent.llm;

/**
 * G4c-s109b（A-M1）：把**本轮**的本地裁后估算校准成厂商口径的判额输入。
 *
 * <p><b>为什么需要</b>：S109 第一版直接拿上一轮实测 {@code lastPromptTokens} 判本轮
 * （{@code EntityAgentLoop}），本轮新增的工具结果/消息没有计入，主方向偏松。这里不猜
 * 「本轮实际是多少」，而是用上一对（实测 / 同一份本地估算）的比例，把**本轮的本地估算**
 * 校准过去 —— 估算形状是本轮的，比例是最近一次真正请求的。
 *
 * <p><b>为什么夹取</b>：一次异常样本（例如上一轮被裁得很小、或 usage 帧异常）可能把比例
 * 带到 5×/0.05×。判额是闸门，宁可保守地夹在 {@value #MIN_RATIO}–{@value #MAX_RATIO}，
 * 也不让单次异常决定一整轮的放行/拦截。
 *
 * <p>纯函数，不读配置、不碰 IO；{@code estimateMultiplier} 仍只作用于 reserve，不在这里。
 */
public final class BudgetGateEstimate {

    /** 校准比例下界：低于它说明上一对样本不可信，夹回。 */
    public static final double MIN_RATIO = 0.20;
    /** 校准比例上界。 */
    public static final double MAX_RATIO = 2.00;

    private BudgetGateEstimate() {}

    /**
     * @param trimmedEstimate 本轮已组装请求的本地估算（含 wire tools / system / 裁后历史）
     * @param lastMeasured    上一轮厂商实测 promptTokens（&lt;=0 = 没有）
     * @param lastTrimmed     上一轮同一口径的本地估算（&lt;=0 = 没有）
     * @return 本轮判额输入；没有可用历史时原样返回 {@code trimmedEstimate}
     */
    public static long calibrated(long trimmedEstimate, long lastMeasured, long lastTrimmed) {
        long trimmed = Math.max(0L, trimmedEstimate);
        if (lastMeasured <= 0 || lastTrimmed <= 0) return trimmed;
        double ratio = (double) lastMeasured / (double) lastTrimmed;
        if (!Double.isFinite(ratio) || ratio <= 0.0) return trimmed;
        ratio = Math.max(MIN_RATIO, Math.min(MAX_RATIO, ratio));
        return Math.max(0L, Math.round(trimmed * ratio));
    }

    /** 判额输入的来源，写进 {@code budget_gate.source}，便于事后区分冷启动/校准。 */
    public static String source(long lastMeasured, long lastTrimmed) {
        return (lastMeasured > 0 && lastTrimmed > 0) ? "calibrated" : "trimmed";
    }

    /** 上一对样本的比例（夹取前），只用于观测；不可用时返回 0。 */
    public static double rawRatio(long lastMeasured, long lastTrimmed) {
        if (lastMeasured <= 0 || lastTrimmed <= 0) return 0.0;
        double ratio = (double) lastMeasured / (double) lastTrimmed;
        return Double.isFinite(ratio) ? ratio : 0.0;
    }
}
