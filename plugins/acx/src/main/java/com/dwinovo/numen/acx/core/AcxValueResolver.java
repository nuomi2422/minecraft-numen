package com.dwinovo.numen.acx.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * {@code $} 引用解析器。搬自 DD 版 {@code AcRunner.resolveValue}（:600-653）。
 *
 * <p>三种写法：</p>
 * <ul>
 *   <li>{@code $prev.xxx} —— 上一步的输出字段</li>
 *   <li>{@code $<stepId>.xxx} —— 跨步引用任意指定步骤的输出字段</li>
 *   <li>{@code $input.xxx} —— 本次 AC 外部传入的参数</li>
 * </ul>
 *
 * <p><b>关键行为</b>：引用解析不到时<b>保留原字符串</b>并记 WARN，不抛异常、不返回 null。
 * 这样「引用写错」的表现是「参数原样传下去让积木自己报错」，
 * 而不是「整条 AC 在加载/运行期炸掉」——后者会掩盖真正的错误位置。</p>
 */
public final class AcxValueResolver {

    private static final Logger LOG = Logger.getLogger(AcxValueResolver.class.getName());

    public static final String PREV = "prev";
    public static final String INPUT = "input";

    private final Map<String, Object> lastOutput;
    private final Map<String, Object> input;
    private final Map<String, Map<String, Object>> allOutputs;

    public AcxValueResolver(Map<String, Object> lastOutput,
                            Map<String, Object> input,
                            Map<String, Map<String, Object>> allOutputs) {
        this.lastOutput = lastOutput == null ? Map.of() : lastOutput;
        this.input = input == null ? Map.of() : input;
        this.allOutputs = allOutputs == null ? Map.of() : allOutputs;
    }

    /** 递归解析 params 里所有 {@code $} 引用。 */
    public Map<String, Object> resolveParams(Map<String, Object> params) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        if (params == null) {
            return resolved;
        }
        for (Map.Entry<String, Object> e : params.entrySet()) {
            resolved.put(e.getKey(), resolve(e.getValue()));
        }
        return resolved;
    }

    /** 递归解析任意值：String 走引用规则，Map / List 逐元素递归，其余原样返回。 */
    public Object resolve(Object value) {
        if (value instanceof String s) {
            return resolveString(s);
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                resolved.put(String.valueOf(e.getKey()), resolve(e.getValue()));
            }
            return resolved;
        }
        if (value instanceof List<?> list) {
            List<Object> resolved = new ArrayList<>(list.size());
            for (Object item : list) {
                resolved.add(resolve(item));
            }
            return resolved;
        }
        return value;
    }

    private Object resolveString(String s) {
        if (s.startsWith("$")) {
            int dot = s.indexOf('.');
            if (dot > 1) {
                String head = s.substring(1, dot);
                String key = s.substring(dot + 1);
                if (PREV.equals(head)) {
                    return lookup(lastOutput, key, "$prev." + key, s);
                }
                if (INPUT.equals(head)) {
                    return lookup(input, key, "$input." + key, s);
                }
                Map<String, Object> stepOutput = allOutputs.get(head);
                if (stepOutput != null) {
                    return lookup(stepOutput, key, "$" + head + "." + key, s);
                }
                LOG.warning("$" + head + " 步骤不存在，保留原字串");
                return s;
            }
        }
        return s;
    }

    /**
     * 字段查找：先整体查，再按点路径逐层下钻。
     *
     * <p>点路径是 Numen 适配层加的需求：{@code get_self_status} 这类工具的输出是
     * 嵌套结构（{@code position:{x,y,z}}），脚本里 {@code $before.position.x} 必须能取到
     * 叶子值，否则「读位置 → 依位置移动」这条最小闭环根本写不出来。</p>
     */
    private static Object lookup(Map<String, Object> root, String key, String label, String original) {
        Object v = root.get(key);
        if (v != null) {
            return v;
        }
        if (key.indexOf('.') >= 0) {
            Object cur = root;
            for (String seg : key.split("\\.")) {
                if (!(cur instanceof Map<?, ?> m)) {
                    cur = null;
                    break;
                }
                cur = m.get(seg);
                if (cur == null) {
                    break;
                }
            }
            if (cur != null) {
                return cur;
            }
        }
        LOG.warning(label + " 引用的字段不存在，保留原字串");
        return original;
    }

    /**
     * 把字符串转数字，用于条件求值。
     *
     * <p>未解析的 {@code $} 引用会返回 0 —— 这是 DD 的原行为（{@code AcRunner.toDouble} :579-585）：
     * 让 {@code while} 至少能进一次，而不是因为引用写错就永远不循环。</p>
     */
    public static double toDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (Exception ignored) {
                return 0.0;
            }
        }
        return 0.0;
    }

    public static int toInt(Object v, int fallback) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return fallback;
        }
    }
}