package com.dwinovo.numen.task;

/** 异步任务完成回执的提示文案（2026-11-06）。 */
public final class TaskCompletionText {

    /** 完成回执统一追加的一句，减少“任务已结束后还调 task_stop”的空转。 */
    public static final String STOP_HINT = "（任务已结束，无需 task_stop；确需中止请先 task_status 确认在跑。）";

    private TaskCompletionText() {}

    /** 幂等追加：已带提示就不再重复；空消息只返回提示本身。 */
    public static String withStopHint(String message) {
        String body = message == null ? "" : message;
        if (body.contains("无需 task_stop")) return body;
        return body.isBlank() ? STOP_HINT : body + STOP_HINT;
    }
}
