package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.provider.IToolSpec;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 渐进披露的<b>格式所有者</b>:目录怎么写、展开块怎么渲染、怎么从对话里读回
 * "哪些工具已经展开"——三件事都在这一个类里,因为它们是同一份格式的两头。
 * 拆开放的话,改了渲染忘了改解析,闸就会在无人察觉的情况下永远放行。
 *
 * <h2>为什么要有这套东西</h2>
 * 常驻工具(见 {@link NumenTool.Residency})的完整定义每轮都随请求发出;其余的只在
 * 系统提示里留一行摘要,模型要用时调 {@code find_tools} 取回完整定义。省的是每一轮
 * 的输入,不是一次性的。
 *
 * <h2>展开状态从哪儿看</h2>
 * <b>从对话记录里推导,不另记一份。</b> 判据只有一条:schema 在不在上下文里——而
 * {@code find_tools} 的那条工具结果还在不在,对话自己就是答案。压缩把它总结掉了,
 * {@link #expandedIn} 自然就不再认这个名字,模型也确实看不见那份 schema 了,重新
 * 取一次即可。副本集合会在压缩后骗人:它还记着"展开过",而模型手里已经没有参数
 * 定义,于是照着记忆瞎填。
 */
public final class ToolDisclosure {

    /** 展开块的首行标记。渲染与解析共用这一个常量——格式只有一个主人。 */
    static final String OPEN_PREFIX = "<functions expanded=\"";
    private static final String OPEN_SUFFIX = "\">";
    private static final String CLOSE = "</functions>";

    /**
     * 目录里一行摘要的长度上限——超了截断加省略号,目录是索引不是文档。
     *
     * <p><b>★ 2026-10-02 已从 40 回退到 96（用户裁决，原为 96）</b>：
     * 2026-10-01 我把这个数砍到 40，实测发现把 59 行目录里 42 行截成了半句话
     * （{@code build "Construct or clear blocks as ONE backgr…"}）。
     * <p><b>为什么必须回到 96</b>：这张目录的唯一职责是让模型判断「要不要展开这个工具」。
     * 说明变半句话 = 目录失去判别力 = 模型猜错、多展开、每次多几千 token；
     * <b>省下的钱远小于多展开的代价，而且实测是负收益</b>。
     * <p><b>这是本仓库的一条红线</b>：原作者为让模型**用得对**而写的 description / schema
     * 是执行层的一部分，不是待优化的冗余。详见 {@code 30-主要功能不回退清单.md}。
     * 想省这里的 token，只能走「让目录一行同时带上参数名 + 完整首句」的改写，
     * <b>不能靠砍字数</b>——砍字数就是砍模型的理解能力。
     */
    static final int SUMMARY_MAX = 96;

    private static final Gson GSON = new Gson();

    private ToolDisclosure() {}

    /**
     * 渲染展开块——{@code find_tools} 的返回值。首行把展开的名字列在
     * {@code expanded} 属性里,{@link #expandedIn} 只认这一行,不去解析下面的 JSON:
     * 解析自己吐出去的 JSON 结构,格式一动就断。
     */
    public static String render(Collection<? extends IToolSpec> tools) {
        List<String> names = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        for (IToolSpec t : tools) {
            names.add(t.name());
            JsonObject fn = new JsonObject();
            fn.addProperty("name", t.name());
            fn.addProperty("description", t.description());
            fn.add("parameters", GSON.toJsonTree(t.parameterSchema()));
            body.append("<function>").append(GSON.toJson(fn)).append("</function>\n");
        }
        return OPEN_PREFIX + String.join(",", names) + OPEN_SUFFIX + "\n" + body + CLOSE;
    }

    /**
     * 从对话记录里推导已展开的工具名。扫 {@code role=tool} 的消息内容,认首行标记。
     *
     * @param conversation 本次请求实际发出去的消息(不是当下的对话)——闸该按
     *                     <b>模型看见了什么</b>判,而不是按之后又长出了什么
     */
    public static Set<String> expandedIn(Collection<ConvoState.Msg> conversation) {
        Set<String> out = new LinkedHashSet<>();
        if (conversation == null) return out;
        for (ConvoState.Msg msg : conversation) {
            // 只认工具结果:展开块是 find_tools 回的东西,出现在别处就不是凭据
            if (msg instanceof ConvoState.Msg.Tool t && t.content() != null) {
                collectNames(t.content(), out);
            }
        }
        return out;
    }

    /** 一段文本里所有展开块标记的名字。同一条消息里可能有多个块。 */
    static void collectNames(String text, Set<String> out) {
        int from = 0;
        while (true) {
            int open = text.indexOf(OPEN_PREFIX, from);
            if (open < 0) return;
            int start = open + OPEN_PREFIX.length();
            int end = text.indexOf(OPEN_SUFFIX, start);
            if (end < 0) return;
            for (String raw : text.substring(start, end).split(",")) {
                String name = raw.strip();
                if (!name.isEmpty()) out.add(name);
            }
            from = end + OPEN_SUFFIX.length();
        }
    }

    /** 文中是否含展开块首行标记。压不压之前先问这个,不扫正文。 */
    public static boolean hasExpandedBlock(String text) {
        return text != null && text.contains(OPEN_PREFIX);
    }

    /**
     * 把展开块压成一行「已展开过 + 参数名」的索引。
     *
     * <p><b>为什么</b>：2026-10-02 实机请求体实测，一次 {@code find_tools} 展开的完整
     * schema（实测 {@code build} 一条就 8,708 字符 ≈ 2,177 token）会<b>永久留在历史里</b>，
     * 每轮原样重发，占单次请求 19% —— 比 system prompt 里 {@code ENTITY_PROMPT} 整段还大。
     * schema 是<b>可再生的</b>（{@code find_tools} 随时能再展开一次），留���历史里纯浪费。
     *
     * <p><b>刻意不保留 {@code <functions expanded=…>} 首行标记</b>：那行是
     * {@link #expandedIn} 判定「已展开、目录里不必再列」的凭据。留着它 = 目录不再列这几个
     * 工具 + 历史里又没���完整 schema = 模型照着记忆瞎填（本类原注释警告过的坑）。
     * 去掉它，{@code <deferred_tools>} 目录照常列着它们，{@code find_tools} 随时能取回
     * 完整定义 —— <b>恢复链路闭环</b>。
     *
     * <p><b>参数名必须留</b>：不留的话模型连「这工具要哪些参数」都记不住，只能瞎编。
     * 留参数名 + 留「需要就重新 find_tools」这句，压才有意义。
     *
     * <p><b>原件不进历史但没丢</b>：{@code tool_result} 原文本来就落 {@code monitor/tools.jsonl}
     * （外部审计副本），这里只是<b>组装请求时</b>换一份发给模型，不改会话历史本体。
     */
    public static String slimExpanded(String text) {
        if (!hasExpandedBlock(text)) return text;
        StringBuilder out = new StringBuilder();
        int from = 0;
        while (true) {
            int open = text.indexOf(OPEN_PREFIX, from);
            if (open < 0) { out.append(text, from, text.length()); break; }
            int nameEnd = text.indexOf(OPEN_SUFFIX, open + OPEN_PREFIX.length());
            if (nameEnd < 0) { out.append(text, from, text.length()); break; }
            int bodyStart = nameEnd + OPEN_SUFFIX.length();
            int close = text.indexOf(CLOSE, bodyStart);
            if (close < 0) { out.append(text, from, text.length()); break; }
            out.append(text, from, open);
            out.append(slimBlock(text.substring(open + OPEN_PREFIX.length(), nameEnd),
                    text.substring(bodyStart, close)));
            from = close + CLOSE.length();
        }
        return out.toString();
    }

    private static String slimBlock(String names, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("<functions-slimmed expanded=\"").append(names).append("\">");
        int from = 0;
        int n = 0;
        while (true) {
            int open = body.indexOf("<function>", from);
            if (open < 0) break;
            int close = body.indexOf("</function>", open);
            if (close < 0) break;
            if (n++ > 0) sb.append("; ");
            sb.append(oneLine(body.substring(open + "<function>".length(), close)));
            from = close + "</function>".length();
        }
        if (n == 0) sb.append(names);
        sb.append(" — full schemas are NOT in context any more.")
                .append(" Call find_tools again to re-expand before using these tools.");
        return sb.append("</functions-slimmed>").toString();
    }

    /** {@code {"name":"build","parameters":{"properties":{...}}}} → {@code build(ops)}。 */
    private static String oneLine(String json) {
        try {
            JsonObject fn = GSON.fromJson(json, JsonObject.class);
            if (fn == null || !fn.has("name")) return "?";
            String name = fn.get("name").getAsString();
            JsonElement params = fn.get("parameters");
            if (params == null || !params.isJsonObject()) return name + "()";
            JsonObject props = params.getAsJsonObject().getAsJsonObject("properties");
            if (props == null) return name + "()";
            StringBuilder sb = new StringBuilder(name).append('(');
            boolean first = true;
            for (String key : props.keySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(key);
            }
            return sb.append(')').toString();
        } catch (RuntimeException e) {
            // 解析不了就只写个占位：宁可少信息，也**绝不**把整块原文放回去
            return "?";
        }
    }

    /**
     * 目录——进系统提示的恒定区块,一行一个延迟工具。顺序随传入顺序(注册顺序),
     * 同一组工具两次生成必须逐字节相同,否则系统提示不稳定,前缀缓存白瞎。
     */
    public static String catalog(Collection<? extends IToolSpec> deferred) {
        if (deferred.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("<deferred_tools>\n");
        for (IToolSpec t : deferred) {
            sb.append(t.name()).append(" — ").append(summaryOf(t)).append('\n');
        }
        return sb.append("</deferred_tools>").toString();
    }

    /**
     * 目录里那一行摘要:取描述的第一句。<b>不新增字段</b>——摘要与描述是同一件事的
     * 两种长度,分开写必然有一天对不上。
     */
    public static String summaryOf(IToolSpec tool) {
        String d = tool.description();
        if (d == null) return "";
        String flat = d.replaceAll("\\s+", " ").strip();
        int cut = flat.length();
        for (int i = 0; i < flat.length() - 1; i++) {
            char c = flat.charAt(i);
            if ((c == '.' || c == '。' || c == ';' || c == '；') && flat.charAt(i + 1) == ' ') {
                cut = i + 1;
                break;
            }
            if (c == '。' || c == '；') {   // 中文标点后面通常不跟空格
                cut = i + 1;
                break;
            }
        }
        String first = flat.substring(0, cut).strip();
        if (first.length() <= SUMMARY_MAX) return first;
        return first.substring(0, SUMMARY_MAX - 1).strip() + "…";
    }

    /**
     * 她调了一个没展开的工具时的回话。<b>先说错在哪,再说怎么办</b>——只报"未知工具"
     * 的话,她会以为名字拼错了,换个写法再试一遍,白费一轮。
     */
    public static String notExpanded(String name) {
        return "工具 " + name + " 的参数定义尚未取回。先调 find_tools(\"select:" + name
                + "\") 拿到它的完整定义,再调用它。";
    }
}
