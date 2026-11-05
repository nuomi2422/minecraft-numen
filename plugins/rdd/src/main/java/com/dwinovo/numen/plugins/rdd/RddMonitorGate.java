package com.dwinovo.numen.plugins.rdd;

import java.util.HashMap;
import java.util.Map;

/**
 * 「内容没变就不发」的判定（2026-11-05，纯逻辑，可离线测）。
 *
 * <p>为什么单独成类：审计指出「asset_snapshot 变少了」**不能证明去重是对的**——
 * 「去重把一切都吞了」在日志上长得一模一样（正例不可判别）。
 * 把判定抽出来之后，测试可以直接问：内容变了吗？变了要发；没变但过了心跳呢？也要发。
 *
 * <p>调用约定（顺序很重要）：先 {@link #shouldEmit}，**发布成功之后**再 {@link #commit}。
 * 反过来会在写盘失败时留下指纹，导致同一内容永远不再重试（事件永久丢失且无痕）。
 */
final class RddMonitorGate {

    private final long heartbeatMs;
    private final Map<String, String> lastSignature = new HashMap<>();
    private final Map<String, Long> lastPublishedAt = new HashMap<>();

    RddMonitorGate(long heartbeatMs) {
        this.heartbeatMs = Math.max(1L, heartbeatMs);
    }

    boolean shouldEmit(String type, String signature, long nowMs) {
        String prev = lastSignature.get(type);
        if (!signature.equals(prev)) {
            return true;
        }
        Long at = lastPublishedAt.get(type);
        return at != null && nowMs - at >= heartbeatMs;
    }

    void commit(String type, String signature, long nowMs) {
        lastSignature.put(type, signature);
        lastPublishedAt.put(type, nowMs);
    }
}
