package com.dwinovo.numen.rdd.side.death;

import java.util.Locale;

/** 有限、可审计的死亡恢复策略；模型不能创建新的策略值。 */
public enum DeathRecoveryDecision {
    RECOVER_DROPS_FIRST,
    FETCH_BACKUP_THEN_CONTINUE,
    ABANDON_RECOVERY,
    PREPARE_AND_REPLAN;

    public static DeathRecoveryDecision parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
