package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 轨迹切片（契约 §2/§6）：给一条 memo 拼出「前置状态 → 动作/工具结果 → 后置状态」。
 *
 * <p>数据源只读现有 monitor 文件，不另造账本：
 * {@code instrumentation.jsonl}（锚点事件）、{@code tools.jsonl}（工具调用/结果）、
 * {@code state.jsonl}（同伴状态快照，含 items 数）。
 *
 * <p>锚点解析：
 * <ul>
 *   <li>`ai-<eventId>`（auto_intake）→ 直接取 eventId；</li>
 *   <li>`tf-…`（工具失败聚合）→ 从 snapshot 的 `窗口=<first>..` 取首个事件 id。</li>
 * </ul>
 *
 * <p><b>读不到就说读不到</b>：锚点找不到时返回“未找到（不编造）”，
 * 窗口内没有工具调用时明说“读不到 ≠ 没发生”。
 */
public final class TrajectorySlices {

    private static final long BEFORE_MS = 60_000L;
    private static final long AFTER_MS = 30_000L;
    // 2026-10-26：6 行/900 字只够看一个片段，自然任务常在死亡前有十几到二十几次调用；
    // 提到 24 行/2400 字，仍小于复盘 prompt 的输入预算，但能覆盖"前置状态→动作→后置"整段。
    private static final int MAX_TOOL_ROWS = 24;
    private static final int MAX_CHARS = 2400;

    private TrajectorySlices() {}

    /** 给一条 memo 生成切片文本；拿不到锚点或读失败时返回空串/诚实说明。 */
    public static String forMemo(Path configDir, UUID companionId, Memo memo) {
        if (configDir == null || memo == null) return "";
        String anchor = anchorOf(memo);
        if (anchor.isBlank()) return "";
        long t = anchorTime(configDir, anchor);
        if (t < 0) {
            return "轨迹切片: 锚点事件 " + anchor + " 在 instrumentation.jsonl 未找到（不编造）";
        }
        long from = t - BEFORE_MS;
        long to = t + AFTER_MS;
        String cid = companionId == null ? "" : companionId.toString();
        List<String> tools = toolRows(configDir, cid, from, to);
        String state = stateRow(configDir, cid, from, to);
        StringBuilder sb = new StringBuilder();
        sb.append("轨迹切片（锚点 ").append(anchor).append("，窗口 -60s/+30s）：");
        if (!state.isBlank()) sb.append("\n- 状态: ").append(state);
        if (tools.isEmpty()) {
            sb.append("\n- 动作: 窗口内没有工具调用记录（读不到 ≠ 没发生）");
        } else {
            sb.append("\n- 动作:");
            for (String row : tools) sb.append("\n  · ").append(row);
        }
        String out = sb.toString();
        return out.length() > MAX_CHARS ? out.substring(0, MAX_CHARS) + "…" : out;
    }

    private static String anchorOf(Memo memo) {
        String id = memo.id() == null ? "" : memo.id();
        if (id.startsWith("ai-")) return id.substring(3);
        if (id.startsWith("tf-")) {
            String snap = memo.snapshot() == null ? "" : memo.snapshot();
            // 旧格式（中文键）：窗口=<first>..<last>
            int i = snap.indexOf("窗口=");
            if (i >= 0) {
                int j = snap.indexOf("..", i);
                if (j > i) return snap.substring(i + 3, j).trim();
            }
            // 现行格式（ToolFailureIntake.snapshotOf）：window_first=<first>, window_last=<last>
            // ★ 2026-10-26 测试抓到：只认旧中文键时，所有 tf- memo 的 anchor 都为空，
            //   轨迹切片整段缺席——B1 的自然工具失败永远没有轨迹。两种键都认。
            i = snap.indexOf("window_first=");
            if (i >= 0) {
                int j = snap.indexOf(',', i);
                String first = j > i ? snap.substring(i + "window_first=".length(), j)
                        : snap.substring(i + "window_first=".length());
                first = first.trim();
                if (!first.isEmpty()) return first;
            }
        }
        return "";
    }

    private static long anchorTime(Path configDir, String eventId) {
        long t = timeFrom(configDir.resolve("monitor/instrumentation.jsonl"), eventId);
        if (t >= 0) return t;
        // B1 的 tf- memo 的 window_first 是 **tools.jsonl** 的 event_id（tools-...），
        // 不是 instrumentation 事件。只查 instrumentation 会把整段轨迹判成“未找到”。
        return timeFrom(configDir.resolve("monitor/tools.jsonl"), eventId);
    }

    private static long timeFrom(Path f, String eventId) {
        if (!Files.isRegularFile(f)) return -1;
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (!line.contains(eventId)) continue;
                JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                if (eventId.equals(str(o, "event_id"))) {
                    return Instant.parse(str(o, "timestamp")).toEpochMilli();
                }
            }
        } catch (RuntimeException | IOException ignored) {
        }
        return -1;
    }

    private static List<String> toolRows(Path configDir, String cid, long from, long to) {
        List<String> out = new ArrayList<>();
        Path f = configDir.resolve("monitor/tools.jsonl");
        if (!Files.isRegularFile(f)) return out;
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (out.size() >= MAX_TOOL_ROWS) break;
                if (line.isBlank()) continue;
                JsonObject o;
                try {
                    o = JsonParser.parseString(line).getAsJsonObject();
                } catch (RuntimeException e) {
                    continue;
                }
                if (!"tool_result".equals(str(o, "type"))) continue;
                JsonObject d = o.has("data") && o.get("data").isJsonObject() ? o.getAsJsonObject("data") : null;
                if (d == null) continue;
                if (!cid.isBlank() && !cid.equals(str(d, "companion_id"))) continue;
                long ts;
                try {
                    ts = Instant.parse(str(o, "timestamp")).toEpochMilli();
                } catch (RuntimeException e) {
                    continue;
                }
                if (ts < from || ts > to) continue;
                String tool = str(d, "tool");
                if (tool.isBlank()) continue;
                String result = str(d, "result");
                boolean ok = true;
                String msg = "";
                try {
                    JsonObject r = JsonParser.parseString(result).getAsJsonObject();
                    if (r.has("success") && r.get("success").isJsonPrimitive()) ok = r.get("success").getAsBoolean();
                    msg = str(r, "message");
                } catch (RuntimeException ignored) {
                    msg = clip(result, 80);
                }
                out.add("[" + hhmmss(ts) + "] " + tool + (ok ? " ok" : " FAILED") + (msg.isBlank() ? "" : " · " + clip(msg, 80)));
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static String stateRow(Path configDir, String cid, long from, long to) {
        Path f = configDir.resolve("monitor/state.jsonl");
        if (!Files.isRegularFile(f)) return "";
        String first = "", last = "";
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                JsonObject o;
                try {
                    o = JsonParser.parseString(line).getAsJsonObject();
                } catch (RuntimeException e) {
                    continue;
                }
                if (!"companion_state".equals(str(o, "type"))) continue;
                JsonObject d = o.has("data") && o.get("data").isJsonObject() ? o.getAsJsonObject("data") : null;
                if (d == null) continue;
                if (!cid.isBlank() && !cid.equals(str(d, "companion_id"))) continue;
                long ts;
                try {
                    ts = Instant.parse(str(o, "timestamp")).toEpochMilli();
                } catch (RuntimeException e) {
                    continue;
                }
                if (ts < from || ts > to) continue;
                String row = "[" + hhmmss(ts) + "] items=" + (d.has("items") ? d.get("items").getAsInt() : -1)
                        + " effects=" + (d.has("effects") ? d.get("effects").getAsInt() : -1);
                if (first.isBlank()) first = row;
                last = row;
            }
        } catch (IOException ignored) {
        }
        if (first.isBlank()) return "";
        return first.equals(last) ? first : first + "  →  " + last;
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return "";
        try {
            return o.get(key).getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    private static String hhmmss(long ms) {
        java.time.LocalTime t = java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(ms), java.time.ZoneId.systemDefault());
        return String.format("%02d:%02d:%02d", t.getHour(), t.getMinute(), t.getSecond());
    }
}
