package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.agent.context.ContextBudget;
import com.dwinovo.numen.agent.llm.CompactSplit;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.event.EventQueue;
import com.dwinovo.numen.event.EventTypes;

import java.util.List;

/**
 * 当前工具链里主人 <b>typed</b> 请求的请求级锚点（055 约束连续性第一片）。
 *
 * <p><b>为什么需要它</b>：5c416 实机（C28）里，本轮带「只读／不移动」的 {@code <query>}
 * 在第 20 条消息、最终 wire 却只有 6 条——会话分段按末 24 条整体替换、replay 再按 token
 * 截尾，两步都把那条 user 消息裁掉了，最终请求里没有任何"当前主人说了什么"。
 *
 * <p><b>范围刻意窄</b>：只保<b>一条</b>工具链（从主人开口到模型给出最终回复）。不是跨任务
 * 永久记忆，不囤所有 User，不解析任意历史文本——只认 {@link EventTypes#QUERY} 这一种
 * 队列类型（{@code enqueueOwnerWords} 是主人话的唯一咽喉，系统事件/工具结果不经过它）。
 *
 * <p><b>为什么是必知区而不是"另加一段"</b>：全文进入 {@link ContextBudget.Section}
 * {@code mandatory=true}；装不下就整轮 DENIED、由 {@code contextBudgetProblem()} 明确拒绝，
 * 与"静默截掉一条禁令"相反。本类不做任何字符截断。
 */
final class OwnerRequestAnchor {

    /** runtime_state 里的段落名（进丢弃原因与 monitor 事件，必须稳定可 grep）。 */
    static final String SECTION = "owner_request";

    private static final OwnerRequestAnchor NONE = new OwnerRequestAnchor("", 0L);

    private final String text;
    private final long atMs;

    private OwnerRequestAnchor(String text, long atMs) {
        this.text = text == null ? "" : text;
        this.atMs = atMs;
    }

    static OwnerRequestAnchor none() {
        return NONE;
    }

    /**
     * 从本拍取到的队列条目里认领主人请求：<b>只认 {@code EventTypes.QUERY}</b>。
     *
     * <p>同一批里可能有多条主人话（连发/桥接合并）：整批按顺序拼成一个锚点，
     * <b>不吞掉本批尚未执行的前一条要求</b>；下一批 QUERY 再整体替换。
     * 系统事件、工具注入、goal 进度文本都不是主人指令，永远不能产生锚点。
     */
    static OwnerRequestAnchor adopt(OwnerRequestAnchor previous, List<EventQueue.Entry> taken) {
        OwnerRequestAnchor base = previous == null ? NONE : previous;
        if (taken == null || taken.isEmpty()) {
            return base;
        }
        StringBuilder joined = new StringBuilder();
        long at = 0L;
        for (EventQueue.Entry entry : taken) {
            if (entry == null || !EventTypes.QUERY.equals(entry.type())) {
                continue;
            }
            String inner = inner(entry.text());
            if (inner.isBlank()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append('\n');
            }
            joined.append(inner);
            at = entry.ts();
        }
        return joined.length() == 0 ? base : new OwnerRequestAnchor(joined.toString(), at);
    }

    static OwnerRequestAnchor of(String wire, long atMs) {
        String inner = inner(wire);
        return inner.isBlank() ? NONE : new OwnerRequestAnchor(inner, atMs);
    }

    boolean present() {
        return !text.isEmpty();
    }

    String text() {
        return text;
    }

    long atMs() {
        return atMs;
    }

    /** 全文进必知区；token 用项目唯一那把尺（{@link CompactSplit}），本类不造第二把。 */
    ContextBudget.Section section() {
        String xml = xml();
        int tokens = CompactSplit.estimateTokens(List.<ConvoState.Msg>of(new ConvoState.Msg.User(xml)));
        return new ContextBudget.Section(SECTION, xml, true, tokens);
    }

    String xml() {
        if (!present()) {
            return "";
        }
        return "<owner_request source=\"query\">" + escape(text) + "</owner_request>";
    }

    /**
     * 队列里存的是 {@code <query>原话</query>}（命令还有 {@code </query>} 之后的展开正文）。
     * 取内层原话，保留展开部分；这不改变白名单——只有 QUERY 条目会走到这里。
     */
    static String inner(String wire) {
        if (wire == null) {
            return "";
        }
        String raw = wire.strip();
        int open = raw.indexOf("<query>");
        int close = raw.indexOf("</query>");
        if (open >= 0 && close > open) {
            String echo = raw.substring(open + "<query>".length(), close).strip();
            String expanded = raw.substring(close + "</query>".length()).strip();
            return expanded.isEmpty() ? echo : echo + "\n" + expanded;
        }
        return raw;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
