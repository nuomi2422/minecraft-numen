package com.dwinovo.numen.agent.tool;

import java.util.UUID;

/**
 * H3（108 号）：工具调用旁路观察者。
 *
 * <p>为什么需要：A3 的 {@code redundant_query} 判据是 harness **归档后处理**（{@code realtime:false}），
 * 跑的时候不告警 ⇒ 同伴不会自我纠正。要做「运行时可感知」，就得在工具真正被调用时留一个旁路。
 *
 * <p>约定：
 * <ul>
 *   <li><b>只读旁路</b>：观察者抛异常一律吞掉，绝不影响工具调用本身；</li>
 *   <li><b>不改变回执</b>：观察者不能改参数、不能改结果、不能拒绝调用；</li>
 *   <li><b>未注册 = 零开销</b>：没人注册时这里只做一次 null 判断。</li>
 * </ul>
 */
public final class ToolCallObserver {

    /** 一次已发生的工具调用（参数只给长度，避免把整段 JSON 传给观察者）。 */
    @FunctionalInterface
    public interface Listener {
        void onToolCall(UUID companionId, String toolCallId, String toolName, int argsChars);
    }

    private static volatile Listener listener;

    private ToolCallObserver() {}

    public static void install(Listener l) {
        listener = l;
    }

    public static void clear() {
        listener = null;
    }

    /** 由服务端工具派发路径调用；任何异常都不许冒泡。 */
    public static void fire(UUID companionId, String toolCallId, String toolName, int argsChars) {
        Listener l = listener;
        if (l == null || toolName == null) return;
        try {
            l.onToolCall(companionId, toolCallId == null ? "" : toolCallId, toolName, argsChars);
        } catch (RuntimeException ignored) {
            // 观察者自己的问题不许影响工具调用
        }
    }

    /** 兼容旧调用点：没有 call id 当作内部调用（""）。 */
    public static void fire(UUID companionId, String toolName, int argsChars) {
        fire(companionId, "", toolName, argsChars);
    }
}
