package com.dwinovo.numen.agent.retrieval;

/**
 * G5a 最小检索契约 —— 检索预算（75 号 §15.5）。
 *
 * <p>先留必知区，再压缩可选目录；必知区自身装不下 → {@code DENIED} +
 * {@code CONSTRAINT_OVERFLOW}，不吞危险信息（IC09）。字符限额是独立硬上限，
 * 不能冒充精确 token 计费。
 *
 * <p>0 = 该项不限（与 {@code BudgetPolicy} 的 0 = 不限同语义）。
 */
public record RetrievalBudget(
        int maxCandidates,
        int maxRows,
        int maxExpanded,
        int maxTokens,
        int maxChars,
        int mandatoryReservedTokens,
        String tokenizerVersion) {

    public static RetrievalBudget unlimited() {
        return new RetrievalBudget(0, 0, 0, 0, 0, 0, "none");
    }
}
