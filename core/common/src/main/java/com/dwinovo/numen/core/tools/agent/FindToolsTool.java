package com.dwinovo.numen.core.tools.agent;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.agent.tool.ToolDisclosure;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 渐进披露的展开器:目录里只有一行摘要的工具,用这个把完整定义取回来。
 *
 * <p>两种入口,因为她有两种处境:目录里看见了确切名字就 {@code select:a,b} 直取;
 * 只知道想干什么就丢关键词进来搜。少了搜索,她得把目录整个扫一遍再猜名字;少了直取,
 * 明明知道名字还要绕一趟搜索。
 *
 * <p>返回的是 {@link ToolDisclosure#render} 出来的展开块——它同时是<b>给模型看的
 * 定义</b>和<b>给闸看的凭据</b>:下一轮 {@code ToolDisclosure.expandedIn} 从对话里
 * 读回这一块的首行,才认这些名字可以调用。所以这条结果被压缩掉之后工具自动重新上锁,
 * 那不是缺陷,是她确实已经看不见参数定义了。
 */
public final class FindToolsTool implements NumenTool {

    /** 一次最多返回几个定义——她一次要一组是常态,但整本目录倒回去就失去意义了。 */
    static final int MAX_RESULTS = 8;

    private static final String SELECT = "select:";
    /** 同一 query 第几次开始只回一行提示（第 LOCK_AT+1 次恢复完整定义，避免永久失明）。 */
    private static final int LOCK_AT = 3;

    /**
     * 「同一份定义被反复取」的计数（2026-10-24 实测：一分钟内 6 次 select:sleep，
     * 人就在床边 1 格外却一次都没调 sleep）。
     *
     * <p><b>只计数、不拦截</b>：定义照常返回。原作者语义是「用到再取」，
     * 压掉定义会让压缩之后的上下文彻底失明；这里只加一行告警把"够了就动手"讲明白。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> REPEAT =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static String repeatKey(String query) {
        return query.toLowerCase(Locale.ROOT);
    }

    private static String names(List<NumenTool> hits) {
        StringBuilder sb = new StringBuilder();
        for (NumenTool t : hits) {
            if (sb.length() > 0) sb.append(',');
            sb.append(t.name());
        }
        return sb.toString();
    }
    private static final Gson GSON = new Gson();

    private record Args(String query) {}

    @Override
    public String name() {
        return "find_tools";
    }

    /** 常驻:它是取回其他工具的唯一入口,自己不能也躲在目录里。 */
    @Override
    public Residency residency() {
        return Residency.RESIDENT;
    }

    @Override
    public String description() {
        return """
                Load the full definitions of tools listed by name only in <deferred_tools>. \
                Those tools cannot be called until you load them here — you have their one-line \
                summary but not their parameters. Two forms: "select:name1,name2" fetches those \
                tools by exact name (use this when the catalogue already told you the name), or \
                plain keywords to search names and summaries. Fetch every tool you expect to need \
                for the task in ONE call — each call costs a round trip during which your body \
                stands still.""";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("query", "Either \"select:name1,name2\" for exact names, or keywords to search.")
                .build();
    }

    @Override
    public void invoke(ToolCall call) {
        try {
            Args a = GSON.fromJson(call.rawArgs(), Args.class);
            String q = a == null || a.query() == null ? "" : a.query().strip();
            if (q.isEmpty()) {
                call.complete(TaskResult.fail("find_tools needs a 'query'").toJson());
                return;
            }
            List<NumenTool> hits = q.toLowerCase(Locale.ROOT).startsWith(SELECT)
                    ? select(q.substring(SELECT.length()))
                    : search(q);
            if (hits.isEmpty()) {
                call.complete(TaskResult.fail(
                        "没有工具匹配 \"" + q + "\"。名字看 <deferred_tools> 目录。").toJson());
                return;
            }
            // 2026-10-24：实机抓到两种退化——
            //   ① 把「拿到定义」当「已准备好」，同一名单反复取（chat.jsonl 实证 33 次 select:mine）；
            //   ② 一次只取一个工具，来回七八轮（实测一轮里 equip_item/look_around/get_self_status/interact_at 各取一次）。
            // 两种都靠一句提示治：定义不是动作；要用的工具**一次取全**。
            //
            // 2026-10-24 晚（第二轮实测）：同一个名字**一分钟内取了 6 次**，人就在床边 1 格外，
            // 一次 sleep 都没调。据此加"重复计数"提示——注意：**仍然照常返回完整定义**
            // （原作者语义是"用到再取"，压掉定义会让压缩后的上下文彻底失明），只在前面加一行告警。
            //
            // 2026-10-24 夜（第三轮实测）：告警**挡不住**——同一形态原样复发，
            // select:collect_items 连取 8 次、模型明说"Now let me actually act: collect_items"却仍在取定义。
            // ⇒ 加**带逃生口的硬互锁**：占位 3 次只回一行提示（不再返回整份 schema），
            //   第 4 次立刻恢复完整定义。这样既不给循环续命，也绝不让她永久失明。
            int seen = REPEAT.merge(repeatKey(q), 1, Integer::sum);
            if (seen == LOCK_AT) {
                call.complete("<!-- " + names(hits) + " 的定义**已经在你的上下文里**（这是第 " + seen
                        + " 次取同一份）。本次不再重复返回 schema：直接调用它。"
                        + "若你确实看不到定义（上下文被压缩过），再取一次会立刻恢复完整定义。 -->");
                return;
            }
            if (seen > LOCK_AT) {
                REPEAT.put(repeatKey(q), 0);   // 逃生口复位：重新从"完整定义"开始
            }
            String warn = seen <= 1 ? ""
                    : "<!-- 同一份定义你已经取过 " + seen + " 次。它没变。"
                    + "现在缺的不是说明书，是动手：下一步请直接调用 " + names(hits) + "。 -->\n";
            call.complete(warn + ToolDisclosure.render(hits)
                    + "\n<!-- 上面是工具定义（说明书），不是动作。"
                    + "① 你的下一步必须直接调用其中一个工具；再次 find_tools 取同一名单不会带来任何进展。"
                    + "② 如果你预计还要用到别的工具，请用 select:a,b,c 一次性取全，不要一个工具一轮。 -->");
        } catch (RuntimeException ex) {
            call.complete(TaskResult.fail(ex.getMessage()).toJson());
        }
    }

    /** 按名字直取。用 {@code resolve} 而不是 {@code get}:大小写漂移不该白费一轮。 */
    private static List<NumenTool> select(String names) {
        List<NumenTool> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : names.split(",")) {
            String n = raw.strip();
            if (n.isEmpty() || !seen.add(n)) continue;
            NumenTool t = ToolRegistry.resolve(n);
            if (t != null && out.size() < MAX_RESULTS) out.add(t);
        }
        return out;
    }

    /**
     * 关键词搜。名字命中排在摘要命中前面——她打进来的词更可能是名字的一部分。
     * 只搜延迟工具:常驻的定义本来就在请求里,再"取"一遍纯属浪费一轮。
     */
    private static List<NumenTool> search(String query) {
        String[] terms = query.toLowerCase(Locale.ROOT).split("\\s+");
        List<NumenTool> byName = new ArrayList<>();
        List<NumenTool> bySummary = new ArrayList<>();
        for (NumenTool t : ToolRegistry.deferred()) {
            String name = t.name().toLowerCase(Locale.ROOT);
            String summary = ToolDisclosure.summaryOf(t).toLowerCase(Locale.ROOT);
            boolean nameHit = false;
            boolean summaryHit = false;
            for (String term : terms) {
                if (term.isEmpty()) continue;
                if (name.contains(term)) nameHit = true;
                else if (summary.contains(term)) summaryHit = true;
            }
            if (nameHit) byName.add(t);
            else if (summaryHit) bySummary.add(t);
        }
        byName.addAll(bySummary);
        return byName.size() > MAX_RESULTS ? byName.subList(0, MAX_RESULTS) : byName;
    }
}
