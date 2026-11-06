package com.dwinovo.numen.plugins.worldstate.focus;

/** 显式短期焦点：单主体、按世界/会话隔离、到期失效；只记录选择，不存观测值。 */
public record FocusPin(String id, String companionId, String worldId, String sessionId,
                       String dimension, String topic, long createdAtMs, long expiresAtMs,
                       long maxAgeMs, int limit, int maxItems) {

    public boolean alive(long nowMs) { return nowMs < expiresAtMs; }
}
