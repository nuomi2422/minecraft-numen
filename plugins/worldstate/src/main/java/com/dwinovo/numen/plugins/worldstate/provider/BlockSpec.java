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

    /**
     * 位置样本（{@code spec@x,y,z}）按到 {@code center} 的距离排序，近的在前。
     *
     * <p>为什么在采集器里排：调用方要的是"最近的几格"；样本有上限，按扫描顺序取前 N
     * 会漏掉近的。排序键解析失败的那些**原样排在后面**，不丢样本。
     */
    static List<String> sortByDistance(List<String> samples, int cx, int cy, int cz) {
        if (samples == null || samples.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(samples);
        out.sort((a, b) -> {
            double da = distanceSq(a, cx, cy, cz);
            double db = distanceSq(b, cx, cy, cz);
            return Double.compare(da, db);
        });
        return out;
    }

    /**
     * 把一条样本按距离插进"最多 {@code cap} 个、近的在前"的列表里。
     *
     * <p>为什么不是"先收满再排序"：那样收的是扫描顺序的前 N 个，可能漏掉真正近的。
     * 这里始终保留最近的 N 个，代价是每次插入 O(cap)。
     */
    static void insertNearest(List<String> list, String sample, int cap, int cx, int cy, int cz) {
        if (list == null || sample == null || cap <= 0) return;
        double d = distanceSq(sample, cx, cy, cz);
        int at = 0;
        while (at < list.size() && distanceSq(list.get(at), cx, cy, cz) <= d) at++;
        if (at >= cap) return;                 // 比现有 N 个都远 → 不要
        list.add(at, sample);
        while (list.size() > cap) list.remove(list.size() - 1);
    }

    private static double distanceSq(String sample, int cx, int cy, int cz) {
        try {
            int at = sample.lastIndexOf('@');
            if (at < 0) return Double.MAX_VALUE;
            String[] xyz = sample.substring(at + 1).split(",");
            if (xyz.length != 3) return Double.MAX_VALUE;
            int x = Integer.parseInt(xyz[0].trim());
            int y = Integer.parseInt(xyz[1].trim());
            int z = Integer.parseInt(xyz[2].trim());
            double dx = x - cx, dy = y - cy, dz = z - cz;
            return dx * dx + dy * dy + dz * dz;
        } catch (RuntimeException e) {
            return Double.MAX_VALUE;
        }
    }

    private static void addSpec(List<String> out, String raw) {
        String spec = raw == null ? "" : raw.trim();
        if (spec.isEmpty() || out.contains(spec)) return;
        out.add(spec);
    }
}
