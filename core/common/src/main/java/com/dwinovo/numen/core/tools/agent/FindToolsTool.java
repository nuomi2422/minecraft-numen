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
                        "没有工具匹配 \"" + q + "\"。名字看 <deferred_tools> 目录。"
                                + familiesHint()).toJson());
                return;
            }
            // 2026-10-24 夜（主人裁决）：提示/互锁/硬拒绝三轮补丁全部删除。
            // 根因是渐进披露机制本身（上游 471d3861d 已删）；工具表已全量发送，这里只给定义、不拦截。
            call.complete(ToolDisclosure.render(hits));
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
            if (t != null && !t.externalOnly() && out.size() < MAX_RESULTS) out.add(t);
        }
        return out;
    }

    /**
     * 关键词搜。名字命中排在摘要命中前面——她打进来的词更可能是名字的一部分。
     *
     * <p><b>2026-11-02 修（主人点名「找都找不到」）</b>：原来只搜
     * {@link ToolRegistry#deferred()}。当时的前提是「常驻的定义本来就在请求里」，
     * 但白名单（{@code ToolVisibility.forExecutor}）在<b>发请求之前</b>就把一部分工具滤掉了：
     * 一个<b>常驻</b>工具一旦被白名单挡掉，它既不在 wire tools、也不进
     * {@code <deferred_tools>} 目录、关键词又搜不到——三头都看不见，只能靠
     * {@code select:准确名字} 硬猜。现在先搜延迟工具（目录里列着的那些，结果排前面），
     * 再补搜全表；命中常驻工具只是让她「知道有这个名字」，它的定义本来就在请求里，
     * 不会多花一轮。
     */
    private static List<NumenTool> search(String query) {
        String[] terms = query.toLowerCase(Locale.ROOT).split("\\s+");
        List<NumenTool> byName = new ArrayList<>();
        List<NumenTool> bySummary = new ArrayList<>();
        List<NumenTool> candidates = new ArrayList<>(ToolRegistry.deferred());
        Set<String> listed = new LinkedHashSet<>();
        for (NumenTool t : candidates) {
            if (t != null) listed.add(t.name());
        }
        for (NumenTool t : ToolRegistry.all()) {
            if (t != null && listed.add(t.name())) candidates.add(t);
        }
        for (NumenTool t : candidates) {
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

    /** 工具族清单（按前缀分组计数）：空结果时告诉她「有哪些类别」，比只说「看目录」有用。 */
    static String familiesHint() { return familiesHint(ToolRegistry.all()); }

    /** 纯函数版（可测）：按工具名前缀分组计数。 */
    static String familiesHint(java.util.List<NumenTool> tools) {
        java.util.TreeMap<String, Integer> groups = new java.util.TreeMap<>();
        if (tools == null) return "";
        for (NumenTool t : tools) {
            if (t == null || t.name() == null) continue;
            String n = t.name();
            int u = n.indexOf('_');
            String family = u > 0 ? n.substring(0, u + 1) : n;
            groups.merge(family, 1, Integer::sum);
        }
        if (groups.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(" 现有工具族：");
        boolean first = true;
        for (var e : groups.entrySet()) {
            if (!first) sb.append("、");
            sb.append(e.getKey()).append('(').append(e.getValue()).append(')');
            first = false;
        }
        sb.append("。要按族找，用族前缀当关键词（例如 rdd_、settlement_）。");
        return sb.toString();
    }
}
