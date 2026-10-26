package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.plugins.learner.core.FeedbackChannel;
import com.dwinovo.numen.plugins.learner.core.Memo;
import com.dwinovo.numen.plugins.learner.core.MemoQueue;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * B1（契约 §2/§4③）：普通工具失败的**确定性采集**。
 *
 * <p>为什么要有它：现有采集只认 instrumentation 的 7 类戏剧性失败（death/loop/…）；
 * 一夜 1783 次调用里的 120 次工具失败（craft/inspect/mine…）**一条都不进学习门**。
 * 本类直接读 {@code monitor/tools.jsonl} 的 `tool_result`（success=false），
 * 按（同伴, 工具, 错误指纹）聚合成**复盘单元**：
 *
 * <ul>
 *   <li>同一形态连续失败 {@link #THRESHOLD} 次 → 一条 memo（不是每次失败一条）；</li>
 *   <li>同一工具一次成功 → 关闭该工具的全部窗口（失败→成功闭环，不把“后来好了”也算教训）；</li>
 *   <li>memo id = `tf-<tool>-<错误指纹>-<窗口首个事件id>` —— 确定性，重启重扫也不会重复入队
 *       （{@link MemoQueue#append} 按 id 幂等）；</li>
 *   <li>游标按存档代际落盘（`tool-failure-cursor.json`），换代重算，不跨世界复用偏移。</li>
 * </ul>
 *
 * <p><b>边界</b>：只读事件文件 + 只写学习者自己的 memo 队列；不碰世界、不派身体动作（RL-9）。
 * 失败/读不到 = 记一条失败事件并停这一轮，绝不拖垮 tick。
 */
final class ToolFailureIntake {

    /** 连续失败到几次才生成一个复盘单元。3 = “一次是意外，两次是模式”的上限保守值。 */
    static final int THRESHOLD = 3;
    /** 单轮最多入队几条（防一次灌满队列）。 */
    private static final int MAX_MEMOS_PER_SCAN = 4;
    private static final int MAX_SAMPLES = 3;
    private static final String CURSOR_FILE = "tool-failure-cursor.json";

    private static final class Win {
        int count;
        String firstId = "";
        String lastId = "";
        String cls = "";
        long firstMs;
        long lastMs;
        final List<String> samples = new ArrayList<>();
    }

    /** (同伴|工具|错误指纹) → 当前窗口。 */
    private static final Map<String, Win> WINDOWS = new ConcurrentHashMap<>();
    /** 存档代际 → 已读行数。 */
    private static final Map<String, Integer> CURSORS = new ConcurrentHashMap<>();
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    private ToolFailureIntake() {}

    /** 仅供离线单测：清掉静态窗口/游标，避免用例顺序互相污染。 */
    static void resetForTest() {
        WINDOWS.clear();
        CURSORS.clear();
    }

    /** 扫一遍 tools.jsonl 增量。返回本轮入队条数。 */
    static int scanOnce(Path configDir, Function<UUID, MemoQueue> queueOf) {
        if (configDir == null || queueOf == null) return 0;
        Path toolsFile = configDir.resolve("monitor/tools.jsonl");
        if (!Files.isRegularFile(toolsFile)) return 0;
        String generation = FeedbackChannel.generationForConfigDir(configDir);
        int cursor = CURSORS.computeIfAbsent(generation, g -> loadCursor(configDir, g));
        List<String> lines;
        try {
            lines = Files.readAllLines(toolsFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LearnerMonitor.publish("tool_failure_intake_failed", Map.of("reason", "read_failed: " + e));
            return 0;
        }
        if (cursor > lines.size()) cursor = 0;   // 文件被截短 → 重扫（id 幂等兜底）

        int scanned = 0, memos = 0, dupOrFull = 0;
        Map<UUID, Integer> byCompanion = new LinkedHashMap<>();
        for (int i = cursor; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            scanned++;
            JsonObject o;
            try {
                o = JsonParser.parseString(line).getAsJsonObject();
            } catch (RuntimeException e) {
                continue;
            }
            if (!"tool_result".equals(str(o, "type"))) continue;
            JsonObject d = o.has("data") && o.get("data").isJsonObject() ? o.getAsJsonObject("data") : null;
            if (d == null) continue;
            String tool = str(d, "tool");
            String cid = str(d, "companion_id");
            String result = str(d, "result");
            // ★★ 2026-10-26 实机抓到污染：exp-runner / 外部 MCP 的停机调用也会落 tool_result
            //   （call_id=mcp-…，例如每轮结束的 task_stop / rdd_standby），它们不是同伴的决策。
            //   之前 B1 的唯一一条 memo 就是由三轮停机调用“三连败”拼出来的假事件。
            //   只采同伴自己的调用（call_…）；外部控制面一律不进学习。
            if (str(d, "tool_call_id").startsWith("mcp-")) continue;
            // result 为空不算“没数据”：本地工具的成败在 data.success 上（见下）。
            if (tool.isBlank() || cid.isBlank() || (result.isBlank() && !d.has("success"))) continue;
            // 两种落点都要认：身体工具把结果写在 result（二次编码 JSON）；
            // 客户端本地工具（load_skill/find_tools/todowrite…）把 success 直接写在 data。
            Boolean ok = successOf(result);
            if (ok == null && d.has("success") && d.get("success").isJsonPrimitive()) {
                try {
                    ok = d.get("success").getAsBoolean();
                } catch (RuntimeException ignored) {
                    ok = null;
                }
            }
            if (ok == null) continue;
            UUID uuid;
            try {
                uuid = UUID.fromString(cid);
            } catch (IllegalArgumentException e) {
                continue;
            }
            String toolKey = cid + "|" + tool;
            if (ok) {
                // ★ 2026-10-26 实机改：原来“同工具一次成功就清空全部窗口”，结果
                //   craft 这类高频工具中间成功一次（做了别的东西）就把失败模式抹平，
                //   永远凑不到阈值。改为：成功不抹窗，窗口按 TTL 过期（见下）。
                continue;
            }
            long ts = tsOf(o);
            // 新鲜度闸：首次扫描（游标 0）会把整份历史读一遍 —— 只聚合最近 30 分钟的失败，
            // 防「开机就把历史失败一次性灌进队列」。更老的失败仍计入扫描量，不参与窗口。
            long now = System.currentTimeMillis();
            if (ts < now - 30L * 60L * 1000L) continue;
            // 窗口 TTL：>30 分钟没再出现的失败模式作废（替代“成功即清窗”那条被证伪的规则）
            WINDOWS.entrySet().removeIf(en -> en.getValue().lastMs > 0 && now - en.getValue().lastMs > 30L * 60L * 1000L);
            String msg = messageOf(result);
            if (msg.isBlank()) msg = "(local tool failed; no message)";
            String cls = errorClass(tool, msg);
            String fp = digest(tool + "|" + cls).substring(0, 8);
            String key = toolKey + "|" + fp;
            String eventId = str(o, "event_id");
            Win w = WINDOWS.computeIfAbsent(key, k -> {
                Win n = new Win();
                n.firstMs = ts;
                n.firstId = eventId;
                n.cls = cls;
                return n;
            });
            w.count++;
            w.lastId = eventId;
            w.lastMs = ts;
            if (w.samples.size() < MAX_SAMPLES) w.samples.add(clip(msg, 120));
            if (w.count < THRESHOLD) continue;

            String memoId = "tf-" + safe(tool) + "-" + fp + "-" + safeSuffix(w.firstId);
            Memo memo = new Memo(memoId,
                    "工具 " + tool + " 连续执行失败 " + w.count + " 次（同一错误形态，不是参数被拒）：" + clip(msg, 160),
                    "tool_failure",
                    String.join(" ｜ ", w.samples),
                    snapshotOf(configDir, cid, tool, w),
                    System.currentTimeMillis());
            boolean appended;
            try {
                appended = queueOf.apply(uuid).append(memo);
            } catch (RuntimeException e) {
                appended = false;
            }
            WINDOWS.remove(key);
            if (appended) {
                memos++;
                byCompanion.merge(uuid, 1, Integer::sum);
                if (memos >= MAX_MEMOS_PER_SCAN) break;
            } else {
                dupOrFull++;
            }
        }
        CURSORS.put(generation, lines.size());
        saveCursor(configDir, generation, lines.size());
        if (scanned > 0) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("generation", generation);
            ev.put("lines_scanned", scanned);
            ev.put("enqueued_by_companion", byCompanion);
            ev.put("memos", memos);
            ev.put("skipped", dupOrFull);
            ev.put("windows_active", WINDOWS.size());
            LearnerMonitor.publish("tool_failure_intake", ev);
        }
        return memos;
    }

    /**
     * 环境快照（可被质量门 Q1 解析：逗号分隔的 ASCII key=value）。
     * 之前写成“窗口=… 同伴=…”——中文键+空格，Q1 的 kv 解析读不出任何事实，整条被判 REJECT。
     * items/effects 取该同伴最近一条 companion_state；读不到就写 -1（不当 0 冒充空背包）。
     */
    private static String snapshotOf(Path configDir, String cid, String tool, Win w) {
        int items = -1, effects = -1;
        Path state = configDir == null ? null : configDir.resolve("monitor/state.jsonl");
        if (state != null && Files.isRegularFile(state)) {
            try {
                List<String> lines = Files.readAllLines(state, StandardCharsets.UTF_8);
                for (int i = lines.size() - 1; i >= 0 && i > lines.size() - 400; i--) {
                    JsonObject o;
                    try {
                        o = JsonParser.parseString(lines.get(i)).getAsJsonObject();
                    } catch (RuntimeException e) {
                        continue;
                    }
                    if (!"companion_state".equals(str(o, "type"))) continue;
                    JsonObject d = o.has("data") && o.get("data").isJsonObject() ? o.getAsJsonObject("data") : null;
                    if (d == null || !cid.equals(str(d, "companion_id"))) continue;
                    items = d.has("items") ? d.get("items").getAsInt() : -1;
                    effects = d.has("effects") ? d.get("effects").getAsInt() : -1;
                    break;
                }
            } catch (IOException ignored) {
            }
        }
        return "items=" + items + ", effects=" + effects + ", tool=" + tool
                + ", class=" + w.cls + ", window_first=" + w.firstId + ", window_last=" + w.lastId;
    }

    // ── 解析工具 ─────────────────────────────────────────────

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return "";
        try {
            return o.get(key).getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** result 是**二次编码的 JSON 字符串**（{"success":false,"message":…}）。解析不出返回 null。 */
    private static Boolean successOf(String result) {
        try {
            JsonObject r = JsonParser.parseString(result).getAsJsonObject();
            if (r.has("success") && r.get("success").isJsonPrimitive()) {
                return r.get("success").getAsBoolean();
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static String messageOf(String result) {
        try {
            JsonObject r = JsonParser.parseString(result).getAsJsonObject();
            String m = str(r, "message");
            if (!m.isBlank()) return m;
            String e = str(r, "error");
            if (!e.isBlank()) return e;
        } catch (RuntimeException ignored) {
        }
        return clip(result, 120);
    }

    private static long tsOf(JsonObject o) {
        try {
            return Instant.parse(str(o, "timestamp")).toEpochMilli();
        } catch (RuntimeException e) {
            return System.currentTimeMillis();
        }
    }

    /**
     * 错误类别归一（2026-10-26 实机）：同一类失败的消息里嵌了物品名/坐标/数量，
     * 直接按文本会拆成不同窗口（"wooden_pickaxe is a 3x3 recipe…" vs "stone_pickaxe …"）。
     * 这里抽"类别"，让同一种失败合并；抽不出类别才退回文本指纹。
     */
    private static String errorClass(String tool, String msg) {
        String m = msg == null ? "" : msg.toLowerCase(java.util.Locale.ROOT);
        // 见调用处：craft 的失败原因多样（缺材料/缺工作台），但对“连续 craft 失败”这个
        // 学习单元来说它们是同一类——统一归类，避免被物品名/原因拆成不同窗口。
        if ("craft".equals(tool)) return "craft_failure";
        if (m.isEmpty()) return "empty";
        if (m.contains("is a 3x3 recipe") || m.contains("crafting table within reach")) return "craft_needs_table";
        if (m.contains("not enough materials")) return "craft_missing_materials";
        if (m.contains("requires") && (m.contains("pickaxe") || m.contains("tool"))) return "tool_tier";
        return norm(msg);
    }

    /** 归一化：只留字母数字与少量分隔，防止同一错误的措辞细节（坐标/数量）拆成不同窗口。 */
    private static String norm(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(java.util.Locale.ROOT).replaceAll("[0-9]+", "#");
        t = t.replaceAll("[^a-z#\\u4e00-\\u9fa5]+", "");
        // ★ 2026-10-26 实机：craft 失败的尾部（Nearest one is at 158,63,… / None within 16 blocks）
        //   会把同一条“需要工作台”的失败拆成不同窗口，永远凑不到阈值。截短到 48 字符，
        //   只保留错误类别前缀；代价是“缺材料 A”与“缺材料 B”会合并成同一类，可接受。
        return t.length() > 48 ? t.substring(0, 48) : t;
    }

    private static String digest(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static String safe(String s) {
        String t = s == null ? "" : s.replaceAll("[^A-Za-z0-9_-]", "_");
        return t.length() > 40 ? t.substring(0, 40) : t;
    }

    private static String safeSuffix(String id) {
        String t = id == null ? "" : id.replaceAll("[^A-Za-z0-9_-]", "");
        return t.length() > 24 ? t.substring(t.length() - 24) : t;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    // ── 游标（按代际）────────────────────────────────────────

    private static int loadCursor(Path configDir, String generation) {
        try {
            Path f = configDir.resolve(CURSOR_FILE);
            if (!Files.isRegularFile(f)) return 0;
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!generation.equals(str(o, "generation"))) return 0;
            return o.has("line") ? o.get("line").getAsInt() : 0;
        } catch (RuntimeException | IOException e) {
            return 0;
        }
    }

    private static void saveCursor(Path configDir, String generation, int line) {
        try {
            Path f = configDir.resolve(CURSOR_FILE);
            JsonObject o = new JsonObject();
            o.addProperty("generation", generation);
            o.addProperty("line", line);
            Files.writeString(f, GSON.toJson(o) + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }
}
