package com.dwinovo.numen.plugins.worldstate.provider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * blocks 主题的**规格串**解析（纯函数，无 Minecraft 依赖，可离线测）。
 *
 * <p>规格形如 `minecraft:chest` 或 `minecraft:wheat[age=7]`；多个规格逗号分隔，
 * 但**方括号内的逗号不是分隔符**。格式坏（缺等号/缺值）不静默忽略——那会把
 * "按状态过滤"悄悄退化成"按 id 过滤"。
 */
final class BlockSpec {

    private BlockSpec() {}

    /** 从 {@code block=a,b[age=7]} 或 {@code id=…} 里切出规格列表（保序去重）。 */
    static List<String> parseIds(String filter) {
        List<String> out = new ArrayList<>();
        if (filter == null) return out;
        String body = filter.trim();
        int eq = body.indexOf('=');
        if (eq < 0) return out;
        String key = body.substring(0, eq).trim();
        if (!"id".equals(key) && !"block".equals(key)) return out;
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char c : body.substring(eq + 1).toCharArray()) {
            if (c == '[') depth++;
            if (c == ']') depth = Math.max(0, depth - 1);
            if (c == ',' && depth == 0) {
                addSpec(out, cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        addSpec(out, cur.toString());
        return out;
    }

    /** 解析 `a=1,b=2`；格式坏返回 null。 */
    static Map<String, String> parseProps(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0 || eq == part.length() - 1) return null;
            out.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        return out;
    }

    /** 拆成 [id, props]；没有方括号时 props 为空表。格式坏返回 null。 */
    static String[] split(String spec) {
        String s = spec == null ? "" : spec.trim();
        int open = s.indexOf('[');
        if (open < 0) return new String[]{s, ""};
        int close = s.lastIndexOf(']');
        if (close < open) return null;
        return new String[]{s.substring(0, open).trim(), s.substring(open + 1, close)};
    }

    private static void addSpec(List<String> out, String raw) {
        String spec = raw == null ? "" : raw.trim();
        if (spec.isEmpty() || out.contains(spec)) return;
        out.add(spec);
    }
}
