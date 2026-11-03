package com.dwinovo.numen.rdd.side.death;

import java.util.Locale;

/** 有限、可审计的死亡恢复策略；模型不能创建新的策略值。 */
public enum DeathRecoveryDecision {
    RECOVER_DROPS_FIRST,
    FETCH_BACKUP_THEN_CONTINUE,
    ABANDON_RECOVERY,
    PREPARE_AND_REPLAN,
    /** 2026-11-02（170 §5.1）：旧主线已无意义（装备全丢/路线已死）——作废旧链、按新目标重新规划。
     * 本档只允许执行 AI 显式选择；选后仍须调用 rdd_restart_mainline 才真的改链。 */
    RESET_AND_RESTART;

    public static DeathRecoveryDecision parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
