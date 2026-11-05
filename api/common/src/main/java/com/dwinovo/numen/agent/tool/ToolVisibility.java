package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.NumenPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具可见性分层（2026-10-30 主人定）。
 *
 * <p>两侧各管一份，互不影响：
 * <ul>
 *   <li><b>执行者（内脑）</b>{@link #forExecutor}：只发"基建 + 建链"，其余隐藏但保留；
 *       依据是实测 `turn: convo=5, tools=123, tokens=41090` —— 123 条定义整包发。</li>
 *   <li><b>外部 AI（MCP）</b>{@link #externalVisible}：只给"最核心的几件"——
 *       设任务链、对话、下指令、看状态；外部写代码的人不需要从 133 个工具里猜。</li>
 * </ul>
 *
 * <p>配置：`config/numen/tool-visibility.json`
 * <pre>
 * {"executor":{"mode":"whitelist","tools":["mine",…]},
 *  "external":{"mode":"whitelist","tools":["rdd_supervisor_submit",…]}}
 * </pre>
 * 任一侧缺失 / 解析失败 / 名单为空 ⇒ 该侧**回退全量**（宁可见得多，不能把同伴削成哑巴）。
 */
public final class ToolVisibility {

    private static final String FILE = "tool-visibility.json";

    private static volatile String cachedText = null;
    private static volatile long cachedStamp = Long.MIN_VALUE;

    private ToolVisibility() {}

    /**
     * 执行者侧：按白名单裁剪给内脑的工具清单。
     *
     * <p><b>RESIDENT 工具不参与裁剪</b>（2026-11-05 修）：白名单只管「哪些工具看起来可省」，
     * 但声明了 {@link NumenTool.Residency#RESIDENT} 的工具，源码里本来就写着「执行 AI 要一直知道」——
     * 2026-10-30 的白名单把它们一起滤掉了：learner_note 从 54 次/10 天掉到 6 天 0 次，
     * learner_announce / learner_carrier / learner_usage 同样消失。
     * 让白名单能清掉常驻工具，等于允许配置静默推翻代码里的决定——这类事故不该靠人一条条去发现。
     */
    public static List<NumenTool> forExecutor(List<NumenTool> all) {
        return applyWhitelist(all, whitelist("executor"));
    }

    /** 纯函数版（可测）：keep 为 null/空表示不裁剪。 */
    static List<NumenTool> applyWhitelist(List<NumenTool> all, Set<String> keep) {
        if (keep == null || keep.isEmpty() || all == null) return all;
        List<NumenTool> out = new ArrayList<>(keep.size());
        for (NumenTool t : all) {
            if (t == null) continue;
            if (keep.contains(t.name()) || isResident(t)) out.add(t);
        }
        return out.isEmpty() ? all : out;   // 名单全写错也不许把同伴削哑
    }

    private static boolean isResident(NumenTool tool) {
        try {
            return tool.residency() == NumenTool.Residency.RESIDENT;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 外部侧：这个工具要不要出现在 MCP 清单里。 */
    public static boolean externalVisible(String toolName) {
        Set<String> keep = whitelist("external");
        if (keep == null || keep.isEmpty()) return true;
        return keep.contains(toolName);
    }

    private static Set<String> whitelist(String section) {
        String json = read();
        if (json == null) return null;
        return parse(json, section);
    }

    private static String read() {
        try {
            Path p = NumenPaths.config().resolve(FILE);
            if (!Files.isRegularFile(p)) { cachedText = null; return null; }
            long stamp = Files.getLastModifiedTime(p).toMillis();
            if (cachedText != null && stamp == cachedStamp) return cachedText;
            String text = new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
            cachedText = text;
            cachedStamp = stamp;
            return text;
        } catch (RuntimeException | java.io.IOException e) {
            return null;
        }
    }

    /** 极简解析：只认 `"<section>" … "whitelist" … [ "a","b" ]`，不引入 JSON 依赖。 */
    static Set<String> parse(String json, String section) {
        if (json == null || json.isBlank() || section == null) return null;
        int i = json.indexOf("\"" + section + "\"");
        if (i < 0) return null;
        int arr = json.indexOf('[', i);
        int end = arr < 0 ? -1 : json.indexOf(']', arr);
        if (arr < 0 || end < 0) return null;
        if (!json.substring(i, arr).contains("whitelist")) return null;
        Set<String> out = new LinkedHashSet<>();
        for (String raw : json.substring(arr + 1, end).split(",")) {
            String s = raw.trim();
            if (s.length() > 1 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
                out.add(s.substring(1, s.length() - 1));
            }
        }
        return out.isEmpty() ? null : out;
    }
}
