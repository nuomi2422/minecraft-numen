package com.dwinovo.numen.plugins.rdd;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 2026-11-07 B1: render-layer role views over one canonical fact map.
 * Never reads the world, never mutates the source map, never invents missing fields.
 */
final class RddRoleViews {
    private RddRoleViews() {}

    /** Canonical facts (already observed). Null/absent means unknown, not zero. */
    record Facts(Map<String, Object> values, Map<String, String> sourceRefs) {
        Facts {
            values = values == null ? Map.of() : Map.copyOf(values);
            sourceRefs = sourceRefs == null ? Map.of() : Map.copyOf(sourceRefs);
        }
    }

    record View(String role, String text, Map<String, String> sourceRefs, List<String> unknowns) {}

    static View planning(Facts f) {
        Map<String, Object> v = f.values();
        StringBuilder sb = new StringBuilder();
        sb.append("<situation>").append(text(v.get("task.current"))).append("</situation>\n");
        Object hp = v.get("self.hp"), food = v.get("self.food");
        if (hp == null || food == null) {
            sb.append("<state>hp/food UNKNOWN（未观测或身份/时效不可用，不得当满血/饱食）</state>\n");
        } else {
            sb.append("<state>血量 ").append(level(hp)).append(" · 饥饿 ").append(level(food)).append("</state>\n");
        }
        sb.append("<assets>").append(text(v.get("assets.summary"))).append("</assets>\n");
        // deliberately no inventory detail / containers / danger
        return new View("planning", sb.toString(), f.sourceRefs(), List.of());
    }

    static View execution(Facts f) {
        Map<String, Object> v = f.values();
        StringBuilder sb = new StringBuilder();
        for (String key : List.of("env", "inventory.detail", "containers", "danger", "task.current")) {
            Object val = v.get(key);
            sb.append("<").append(key.replace('.', '_')).append(">").append(text(val)).append("</")
                    .append(key.replace('.', '_')).append(">\n");
        }
        return new View("execution", sb.toString(), f.sourceRefs(), List.of());
    }

    static View review(Facts f) {
        Map<String, Object> v = f.values();
        Map<String, Object> post = v.get("post_state") instanceof Map<?, ?> m
                ? map(m) : null;
        StringBuilder sb = new StringBuilder();
        sb.append("<current_task>").append(text(v.get("task.current"))).append("</current_task>\n");
        if (post == null) {
            sb.append("<post_state status=\"UNKNOWN\">目标后态未采集，不得默认没变化</post_state>\n");
        } else {
            sb.append("<post_state target=\"").append(text(post.get("target"))).append("\" changed=\"")
                    .append(Boolean.TRUE.equals(post.get("changed"))).append("\" sourceRef=\"")
                    .append(text(post.get("sourceRef"))).append("\"/>\n");
        }
        return new View("review", sb.toString(), f.sourceRefs(), post == null ? List.of("post_state") : List.of());
    }

    static View external(Facts f) {
        Map<String, Object> v = f.values();
        return new View("external", "<episode>" + text(v.get("episode")) + "</episode>\n<outcome>"
                + text(v.get("outcome")) + "</outcome>\n", f.sourceRefs(), List.of());
    }

    /** 0 件明细与计数必须一致；不一致时明细优先，planning 用 UNKNOWN 而不是 0。 */
    static String executionInventoryCount(Facts f) {
        Object detail = f.values().get("inventory.detail");
        if (!(detail instanceof Map<?, ?> m)) return "UNKNOWN";
        long total = 0;
        for (Object count : m.values()) if (count instanceof Number n) total += n.longValue();
        return String.valueOf(total);
    }

    /** planning 库存可见性：任何一项不满足都输出 UNKNOWN，不写 0。 */
    static String planningInventory(Facts f) {
        Map<String, Object> v = f.values();
        boolean current = Boolean.TRUE.equals(v.get("self.current"));
        boolean identified = Boolean.TRUE.equals(v.get("self.identified"));
        boolean full = Boolean.TRUE.equals(v.get("self.coverageFull"));
        boolean truncated = Boolean.TRUE.equals(v.get("self.truncated"));
        Object detail = v.get("inventory.detail");
        if (!current || !identified || !full || truncated || !(detail instanceof Map<?, ?> m)) {
            return "库存未知（未观测/过期/身份不符/局部覆盖）";
        }
        return executionInventoryCount(f);
    }

    private static String level(Object raw) {
        if (!(raw instanceof Number n)) return text(raw);
        double pct = n.doubleValue();
        return pct >= 15 ? "满" : pct >= 8 ? "中" : "低";
    }

    private static Map<String, Object> map(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : raw.entrySet()) if (e.getKey() instanceof String k) out.put(k, e.getValue());
        return out;
    }

    private static String text(Object raw) {
        return raw == null ? "UNKNOWN" : String.valueOf(raw);
    }
}
