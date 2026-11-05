package com.dwinovo.numen.agent.tool;

import java.util.Map;
import java.util.Locale;

/**
 * 模型常用短名 → 注册名（2026-11-05）。
 *
 * <p>来源：日志里有 9 次 `unknown tool: todo`（工具真名是 todowrite）。这类调用会白丢一个回合。
 * 只放**已经实测出现过**的别名，不做模糊匹配——猜错了会把参数喂给错的工具，比拒绝更糟。
 */
public final class ToolAliases {

    private static final Map<String, String> ALIASES = Map.of(
            "todo", "todowrite",
            "todos", "todowrite"
    );

    private ToolAliases() {}

    /** @return 规范名；没有别名就原样返回（不改大小写，注册表自己处理）。 */
    public static String resolve(String called) {
        if (called == null) return null;
        String key = called.trim().toLowerCase(Locale.ROOT);
        return ALIASES.getOrDefault(key, called);
    }
}
