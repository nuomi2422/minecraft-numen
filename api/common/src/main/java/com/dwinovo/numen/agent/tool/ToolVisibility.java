package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.NumenPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 执行者（同伴内脑）侧的工具可见性。
 *
 * <p>2026-10-30 主人定：「我们添加的工具太冗杂，只保留基建 + 建链，其余隐藏（不是删）」。
 * 实测依据：`turn 2: convo=5 msgs, tools=123, tokens=41090` —— 5 条消息撑不起 41k，
 * 说明 123 条工具定义是**整包**发出去的（`ToolRegistry.all()`，2026-10-24 渐进披露被删后就这样）。
 *
 * <p>过滤器只作用于**装配给内脑的那份清单**；MCP/外部视图仍是全量（监督要看得到全部），
 * 分层语义与 152 号的 L0/L1/L2 一致。
 *
 * <p>配置：`config/numen/tool-visibility.json`
 * <pre>{"executor":{"mode":"whitelist","tools":["mine","craft",…]}}</pre>
 * 文件缺失、读不了、解析失败、mode 不是 whitelist ⇒ **一律返回全量**（宁可见得多，不可把人锁死）。
 */
public final class ToolVisibility {

    private static final String FILE = "tool-visibility.json";

    private static volatile long cachedStamp = Long.MIN_VALUE;
    private static volatile Set<String> cachedWhitelist = null;

    private ToolVisibility() {}

    /** 按配置裁剪给内脑的工具清单；任何异常都退回全量。 */
    public static List<NumenTool> forExecutor(List<NumenTool> all) {
        Set<String> keep = whitelist();
        if (keep == null || keep.isEmpty()) return all;
        List<NumenTool> out = new ArrayList<>(keep.size());
        for (NumenTool t : all) {
            if (t != null && keep.contains(t.name())) out.add(t);
        }
        // 兜底：配置写坏了（比如全是拼错的工具名）不许把同伴削成哑巴
        return out.isEmpty() ? all : out;
    }

    private static Set<String> whitelist() {
        try {
            Path p = NumenPaths.config().resolve(FILE);
            if (!Files.isRegularFile(p)) { cachedWhitelist = null; return null; }
            long stamp = Files.getLastModifiedTime(p).toMillis();
            Set<String> cached = cachedWhitelist;
            if (cached != null && stamp == cachedStamp) return cached;
            Set<String> parsed = parse(new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8));
            cachedWhitelist = parsed;
            cachedStamp = stamp;
            return parsed;
        } catch (RuntimeException | java.io.IOException e) {
            return null;
        }
    }

    /** 极简解析：只认 executor.mode=whitelist 与 executor.tools 数组，不引入 JSON 依赖。 */
    static Set<String> parse(String json) {
        if (json == null || json.isBlank()) return null;
        int i = json.indexOf("\"executor\"");
        if (i < 0) return null;
        String tail = json.substring(i);
        if (!tail.contains("\"whitelist\"")) return null;
        int arr = tail.indexOf("[");
        if (arr < 0) return null;
        int end = tail.indexOf("]", arr);
        if (end < 0) return null;
        String body = tail.substring(arr + 1, end);
        Set<String> out = new LinkedHashSet<>();
        for (String raw : body.split(",")) {
            String s = raw.trim();
            if (s.length() > 1 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
                out.add(s.substring(1, s.length() - 1));
            }
        }
        return out.isEmpty() ? null : out;
    }
}
