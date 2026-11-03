package com.dwinovo.numen.plugins.rdd;

/**
 * 2026-11-02：侧任务类型比较（大小写不敏感）。
 *
 * <p>为什么单独成类：模型会传 `RECOVERY`，当前类型是 `recovery`；原来用 equals 判成「类型变了」，
 * 拒绝被挡 = 被动接受侧任务。纯逻辑放这里，单测不用拖 NumenTool。
 */
final class RddSideType {

    private RddSideType() {}

    static boolean matches(String expected, String current) {
        if (expected == null || current == null) return false;
        return expected.trim().equalsIgnoreCase(current.trim());
    }
}