package com.dwinovo.numen.rdd.fail;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Supervisor ↔ Numen 双向协商协议（人工批准 · 2026-09-25）。
 *
 * <p>从「指挥官单向派单」升级为「士兵可反馈 → 指挥官改单/协商」：
 * <pre>
 *   Supervisor ──派单(current_task+done_when)──▶ Numen
 *   Supervisor ◀──回执(ACCEPT/REJECT/COUNTER)─── Numen
 *   Supervisor ──改单/重规划/接受提议──────────▶ Numen
 * </pre>
 *
 * <p>士兵三种回执（结构化，不自由聊天）：
 * <ul>
 *   <li>{@link Kind#ACCEPT}：接单，正常干；</li>
 *   <li>{@link Kind#REJECT}：命令本身不对（与目标冲突/信息错），附理由；</li>
 *   <li>{@link Kind#COUNTER}：做不到/成本过高，附替代建议（协商）。</li>
 * </ul>
 *
 * <p>指挥官对 REJECT/COUNTER 的处理（由宿主 RddDetector/Planner 执行，本类只定数据）：
 * 接受提议 / 改单 / 触发重规划。纯 JVM、可单测。
 */
public record TaskNegotiation(
        Kind kind,
        String taskId,
        String reason,
        String suggestion) {

    /** 士兵回执类型。 */
    public enum Kind {
        /** 接单。 */
        ACCEPT,
        /** 命令不对（拒绝原命令）。 */
        REJECT,
        /** 做不到/太贵，附替代方案（协商）。 */
        COUNTER
    }

    public TaskNegotiation {
        Objects.requireNonNull(kind, "kind");
        taskId = taskId == null ? "" : taskId.trim();
        reason = reason == null ? "" : reason.trim();
        suggestion = suggestion == null ? "" : suggestion.trim();
    }

    /** 是否需要指挥官介入（改单/协商/重规划）。ACCEPT 不需要。 */
    public boolean needsSupervisorAction() {
        return kind == Kind.REJECT || kind == Kind.COUNTER;
    }

    public boolean hasSuggestion() {
        return !suggestion.isEmpty();
    }

    /** 渲染给 Supervisor/日志的一行。 */
    public String render() {
        StringBuilder sb = new StringBuilder("[").append(kind.name()).append("] ");
        if (!taskId.isEmpty()) sb.append(taskId).append(" — ");
        if (!reason.isEmpty()) sb.append("reason: ").append(reason).append("; ");
        if (hasSuggestion()) sb.append("suggest: ").append(suggestion);
        return sb.toString().trim();
    }

    public Map<String, Object> toEventData(String companionId) {
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("companionId", companionId == null ? "" : companionId);
        d.put("kind", kind.name());
        d.put("taskId", taskId);
        d.put("reason", reason);
        d.put("suggestion", suggestion);
        d.put("needsSupervisorAction", needsSupervisorAction());
        d.put("summary", render());
        return d;
    }

    /** 从工具参数解析（宽松：未知 kind → ACCEPT；缺字段容忍）。 */
    public static TaskNegotiation parse(String kind, String taskId, String reason, String suggestion) {
        Kind k = Kind.ACCEPT;
        if (kind != null) {
            try {
                k = Kind.valueOf(kind.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                k = Kind.ACCEPT;
            }
        }
        return new TaskNegotiation(k, taskId, reason, suggestion);
    }

    /** 合法的 kind 名（供工具 schema / 提示词列出）。 */
    public static List<String> kinds() {
        return List.of(Kind.ACCEPT.name(), Kind.REJECT.name(), Kind.COUNTER.name());
    }
}
