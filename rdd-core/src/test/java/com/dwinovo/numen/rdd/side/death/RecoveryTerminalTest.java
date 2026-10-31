package com.dwinovo.numen.rdd.side.death;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 终止原因解析：两种键名都要认，否则结算恒 UNVERIFIED（2026-10-31 实机踩过）。 */
class RecoveryTerminalTest {

    @Test
    void 认得产出侧的下划线键() {
        // 这是 DeathRecoverySideTaskType 实际写的键；不认它就会读成空。
        Map<String, Object> d = new HashMap<>();
        d.put("terminal_reason", "drops_recovered");
        assertEquals("drops_recovered", RecoveryTerminal.parse(d));
        assertTrue(RecoveryTerminal.isRecovered(RecoveryTerminal.parse(d)));
    }

    @Test
    void 也认得历史驼峰键() {
        Map<String, Object> d = new HashMap<>();
        d.put("deathRecoveryTerminalReason", "drops_absent_with_world_evidence");
        assertEquals("drops_absent_with_world_evidence", RecoveryTerminal.parse(d));
        assertTrue(RecoveryTerminal.isConfirmedAbsent(RecoveryTerminal.parse(d)));
    }

    @Test
    void 驼峰优先且忽略空值() {
        Map<String, Object> d = new HashMap<>();
        d.put("deathRecoveryTerminalReason", "  ");
        d.put("terminal_reason", "executor_declined_recovery");
        assertEquals("executor_declined_recovery", RecoveryTerminal.parse(d), "空白的驼峰值应当让位给下划线键");
    }

    @Test
    void 都没有时返回空串而不是null() {
        assertEquals("", RecoveryTerminal.parse(null));
        assertEquals("", RecoveryTerminal.parse(Map.of()));
        assertFalse(RecoveryTerminal.isRecovered(""));
        assertFalse(RecoveryTerminal.isConfirmedAbsent(""));
    }
}
