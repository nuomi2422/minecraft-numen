package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-05（审计）：**"事件变少了"不能证明去重是对的**——"去重把一切都吞了"在日志上一样。
 * 所以这里直接问判定：内容变了要发吗？没变但不该发吗？过了心跳呢？
 */
class RddMonitorGateTest {

    private static final long MIN = 60_000L;

    @Test
    void sameContentIsNotEmittedTwice() {
        var gate = new RddMonitorGate(10 * MIN);
        assertTrue(gate.shouldEmit("t", "sig-A", 1000));
        gate.commit("t", "sig-A", 1000);
        assertFalse(gate.shouldEmit("t", "sig-A", 2000), "内容没变、没到心跳 → 不发");
    }

    @Test
    void changedContentIsAlwaysEmitted() {
        var gate = new RddMonitorGate(10 * MIN);
        gate.commit("t", "sig-A", 1000);
        assertTrue(gate.shouldEmit("t", "sig-B", 2000), "内容变了必须发——这条防的是「去重把一切都吞了」");
    }

    @Test
    void heartbeatForcesOneEventWhenNothingChanges() {
        var gate = new RddMonitorGate(10 * MIN);
        gate.commit("t", "sig-A", 0);
        assertFalse(gate.shouldEmit("t", "sig-A", 9 * MIN));
        assertTrue(gate.shouldEmit("t", "sig-A", 10 * MIN), "到心跳必须强发一条，否则会被误判卡死");
    }

    @Test
    void typesAreIndependent() {
        var gate = new RddMonitorGate(10 * MIN);
        gate.commit("taskchain_snapshot", "sig-A", 0);
        assertTrue(gate.shouldEmit("asset_snapshot", "sig-A", 1000), "不同 type 不能互相压制");
    }

    @Test
    void failedPublishDoesNotCommitSoItRetries() {
        var gate = new RddMonitorGate(10 * MIN);
        // 模拟：判定通过但写盘失败 → 不 commit
        assertTrue(gate.shouldEmit("t", "sig-A", 1000));
        assertTrue(gate.shouldEmit("t", "sig-A", 2000), "没有 commit 就必须还能重试（否则事件永久丢失）");
    }
}
