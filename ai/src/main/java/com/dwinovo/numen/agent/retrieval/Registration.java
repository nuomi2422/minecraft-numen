package com.dwinovo.numen.agent.retrieval;

/**
 * 75 号 §15.5 `Registration` —— provider 插入注册表的结果。
 * 重复 {@code providerId} 被拒绝（不静默最后写入者覆盖）；注册方不许自行缝进执行权。
 */
public record Registration(String providerId, boolean ok, String message) {

    public static Registration accepted(String id) {
        return new Registration(id, true, "registered");
    }

    public static Registration rejected(String id, String reason) {
        return new Registration(id, false, reason);
    }
}
