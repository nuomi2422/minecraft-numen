package com.dwinovo.numen.agent.llm;

/**
 * G4c-s109c（A 复审 MUST-FIX 1/3）：保存「上一对实测/本地估算」及其 provider 标签。
 *
 * <p><b>为什么单独成类</b>：压缩/清空只应该清「自动压缩闸用的 lastPromptTokens」，
 * 不应该清「估计器比例」——比例是估计器属性，与历史长度无关。把校准对从会话历史里
 * 拆出来，压缩后首轮仍能用上一对比例，不会退化成 3.4× 高估（enforce 下会卡一个窗口）。
 *
 * <p><b>provider 失效</b>：本地估算的偏差随 provider/model 变；换模型后沿用旧比例是错的。
 * 记住更新时的 tag，当前 tag 不同就退回本轮 trimmed，并让调用方打一次警告。
 */
public final class BudgetGateCalibration {

    private long measured;
    private long trimmed;
    private String providerTag = "";

    /** 响应回来时更新配对；任何一项 &lt;=0 视为无效（不覆盖已有有效值）。 */
    public synchronized void update(long measuredTokens, long trimmedEstimate, String provider) {
        if (measuredTokens <= 0 || trimmedEstimate <= 0) return;
        this.measured = measuredTokens;
        this.trimmed = trimmedEstimate;
        this.providerTag = provider == null ? "" : provider;
    }

    /** 当前 provider 下这对样本是否可用。 */
    public synchronized boolean usable(String currentProviderTag) {
        if (measured <= 0 || trimmed <= 0) return false;
        String now = currentProviderTag == null ? "" : currentProviderTag;
        return providerTag.isEmpty() || providerTag.equals(now);
    }

    /** 本轮判额输入：可用则按比例校准本轮估算，否则原样用本轮估算。 */
    public synchronized long gate(long trimmedEstimate, String currentProviderTag) {
        long current = Math.max(0L, trimmedEstimate);
        if (!usable(currentProviderTag)) return current;
        return BudgetGateEstimate.calibrated(current, measured, trimmed);
    }

    /** 写进 budget_gate.source。 */
    public synchronized String source(String currentProviderTag) {
        return usable(currentProviderTag) ? "calibrated" : "trimmed";
    }

    /** 写进 budget_gate.ratio；不可用时 0。 */
    public synchronized double ratio(String currentProviderTag) {
        return usable(currentProviderTag) ? BudgetGateEstimate.rawRatio(measured, trimmed) : 0.0;
    }

    public synchronized long measured() {
        return measured;
    }

    public synchronized long trimmed() {
        return trimmed;
    }

    public synchronized String providerTag() {
        return providerTag;
    }

    /** 清空（换人设/换同伴等真正需要重置时用；压缩/清空上下文**不**调用）。 */
    public synchronized void clear() {
        measured = 0;
        trimmed = 0;
        providerTag = "";
    }
}
