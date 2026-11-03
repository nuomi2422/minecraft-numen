package com.dwinovo.numen.rdd.side;

import java.util.UUID;

/**
 * 支线激活时保存的主线恢复令牌。
 *
 * <p>它<b>不</b>复制整条 {@code TaskChain}——恢复时重新向活的 {@link MainlineValidator}
 * 查主线，令牌对不上就结束支线但不恢复旧引用（交给当前主线/空闲状态重新选择）。
 */
public record MainlineResumeToken(
        UUID companionId,
        String chainInstanceId,
        String goalId,
        String primaryId,
        String subtaskId,
        int planRevision,
        long generation) {

    /**
     * 2026-11-02（169 审阅）：同 goalId/同 primaryId/同 planRevision 的两条链可以重名（新链从 0 起），
     * 只比代次存在 ABA：旧令牌可能在新链同号同版本时误命中。加 chainInstanceId 后跨链必然不等。
     */
    public boolean matches(String chainInstanceId, String goalId, String primaryId, String subtaskId,
                           int planRevision) {
        return eq(this.chainInstanceId, chainInstanceId)
                && eq(this.goalId, goalId)
                && eq(this.primaryId, primaryId)
                && eq(this.subtaskId, subtaskId)
                && this.planRevision == planRevision;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
