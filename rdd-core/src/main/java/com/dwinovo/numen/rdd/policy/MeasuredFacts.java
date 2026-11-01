package com.dwinovo.numen.rdd.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「实测说做不到」的事，别再排进计划（167 §19 第 4 条）。

 * <p>实机证据（2026-11-01）：规划输入的第 128 行已经写着
 * 「实测确认铁镐挖不动黑曜石：手持铁镐时 inspect_block 返回 needs_correct_tool=true」，
 * 可新计划（第 139 行）仍然写「用现有铁镐去挖黑曜石」——于是阶段 2 空转 19 分钟，
 * obsidian 卡在 7/10、mine 一次没发。

 * <p>本类只做两件事：从文本里**抽**出这类否决性事实，以及**检查**新计划是否又违反它。
 * 不改变计划、不拦，只让冲突可见（与 {@link PlanConsistency} 同口径）。
 */
public final class MeasuredFacts {

    private MeasuredFacts() {}

    /** 一条否决性事实：用 {@code tool} 做不了 {@code item}。 */
    public record Forbidden(String tool, String item, String why) {}

    /** 「X挖不动Y」式的中文陈述。 */
    private static final Pattern CANT_MINE =
            Pattern.compile("([\\u4e00-\\u9fa5a-z_]{1,10})\\s*挖不动\\s*([\\u4e00-\\u9fa5a-z_]{1,12})");
    /** 「needs_correct_tool」常与具体方块名同行出现。 */
    private static final Pattern NEEDS_TOOL =
            Pattern.compile("([\\u4e00-\\u9fa5]{1,8})\\s*[（(]?[^。\\n]{0,20}needs_correct_tool");

    /** 从任意段落里抽否决性事实；重复的只留一条。 */
    public static List<Forbidden> extract(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<Forbidden> out = new ArrayList<>();
        Matcher m = CANT_MINE.matcher(text);
        while (m.find()) add(out, new Forbidden(m.group(1).trim(), m.group(2).trim(), "挖不动"));
        Matcher n = NEEDS_TOOL.matcher(text);
        while (n.find()) add(out, new Forbidden("", n.group(1).trim(), "needs_correct_tool"));
        return List.copyOf(out);
    }

    private static void add(List<Forbidden> out, Forbidden f) {
        if (f.item() == null || f.item().isBlank()) return;
        for (Forbidden e : out) {
            if (e.item().equals(f.item())) return;
        }
        out.add(f);
    }

    /** 计划里「挖/采/获取某物」的步骤，与已确认做不到的事相撞时返回冲突描述。 */
    public static List<String> violations(List<Forbidden> facts, List<PlanConsistency.Subtask> subtasks) {
        if (facts == null || facts.isEmpty() || subtasks == null) return List.of();
        List<String> out = new ArrayList<>();
        for (Forbidden f : facts) {
            for (PlanConsistency.Subtask s : subtasks) {
                if (s == null) continue;
                String desc = s.description() == null ? "" : s.description();
                if (!desc.contains(f.item())) continue;
                if (!(desc.contains("挖") || desc.contains("采") || desc.contains("获取"))) continue;
                out.add(f.item() + "（实测：" + (f.tool().isBlank() ? "工具" : f.tool())
                        + "做不了，原因 " + f.why() + "）却被排成「" + brief(desc) + "」");
                break;
            }
        }
        return List.copyOf(out);
    }

    private static String brief(String s) {
        String t = s.replace('\n', ' ').trim();
        return t.length() <= 40 ? t : t.substring(0, 40) + "…";
    }
}