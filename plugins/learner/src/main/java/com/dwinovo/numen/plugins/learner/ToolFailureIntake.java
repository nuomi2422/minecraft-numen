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
        /** 这条失败第一个计数行的写者（journaled_by）——F9：证据引用只认非 client 注入的源。 */
        String writer = "";
        long firstMs;
        long lastMs;
        final List<String> samples = new ArrayList<>();
        /** 已经算过的 tool_call_id —— 同一调用被多个 writer 记 2~3 行时只算一次。 */
        final java.util.Set<String> calls = new java.util.HashSet<>();
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
            // ★ 2026-11-01：只认 JSON boolean。缺字段 / 字符串 "UNKNOWN" / null 一律 continue，
            //   绝不 getAsBoolean() 隐式转 false —— 那会把「不知道」写成失败事实
            //   （当天 939 行 client 默认 false + 81 行 UNKNOWN 就是这么进来的）。
            Boolean ok = successOf(result);
            // 2026-11-02（审核窗口 07:42）：身体工具（goto/scan/eat/fish…）把结果写成**纯文本**，
            // 没有 success 信封 —— 之前整行跳过，真实失败（invalid arguments / blocked /
            // didn't eat … / no safe dry stance）一个都进不来。这里只按**明确前缀**判失败，
            // 命中即 1x 入队（用户要求：每次失败都要被看到）；模糊/成功回执仍 unknown，不猜。
            boolean clearTextFailure = false;
            if (ok == null && !result.isBlank()) {
                TextVerdict tv = classifyText(result);
                if (tv == TextVerdict.FAILURE) {
                    ok = Boolean.FALSE;
                    clearTextFailure = true;
                } else if (tv == TextVerdict.SUCCESS) {
                    ok = Boolean.TRUE;
                }
            }
            // data.success 的可信度按写者分：除了已删除的 client 写者（EntityAgentLoop 的历史行，
            // 缺字段默认 false，2026-11-01 当天 939 行），其余写者都是显式判定：
            // server-async/server-sync 按任务终态，client-local/client-synthetic 由 successOf 三态给出，
            // server 行根本不写 success（走 result 解析）。client-local 的真实失败没有 result 正文
            // （LocalToolCallLog.publishResult 只写布尔），所以不能只认 server-*。
            String writer = str(d, "journaled_by");
            if (ok == null && !"client".equals(writer)) {
                ok = boolOf(d.get("success"));
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
            // ★ 2026-11-01（实机复核 P1）：async_status=stopped 是**主动取消/被顶替**，
            //   不是工具失败 —— harness 的 action-ledger 同一口径（cancelled 不计失败）。
            //   failed/timeout 仍算失败。这里按状态分型，不回改历史行。
            String asyncStatus = str(d, "async_status");
            if (asyncStatus.endsWith("stopped")) continue;
            long ts = tsOf(o);
            // 新鲜度闸：首次扫描（游标 0）会把整份历史读一遍 —— 只聚合最近 30 分钟的失败，
            // 防「开机就把历史失败一次性灌进队列」。更老的失败仍计入扫描量，不参与窗口。
            long now = System.currentTimeMillis();
            if (ts < now - 30L * 60L * 1000L) continue;
            // 窗口 TTL：>30 分钟没再出现的失败模式作废（替代“成功即清窗”那条被证伪的规则）
            WINDOWS.entrySet().removeIf(en -> en.getValue().lastMs > 0 && now - en.getValue().lastMs > 30L * 60L * 1000L);
            String msg = messageOf(result);
            if (msg.isBlank()) msg = result.isBlank() ? "(local tool failed; no message)" : clip(result.trim(), 160);
            String cls = errorClass(tool, msg);
            String fp = digest(tool + "|" + cls).substring(0, 8);
            String key = toolKey + "|" + fp;
            String eventId = str(o, "event_id");
            Win w = WINDOWS.computeIfAbsent(key, k -> {
                Win n = new Win();
                n.firstMs = ts;
                n.firstId = eventId;
                n.cls = cls;
                n.writer = writer == null ? "" : writer;
                return n;
            });
            // 同一次调用会被 server / client / server-async 记 2~3 行（2026-11-01 实测 2365/2734）。
            // 学习单元是「失败了几次调用」，不是「落了几行日志」：同一 call 只算一次。
            String dedupeKey = str(d, "tool_call_id");
            if (dedupeKey.isBlank()) dedupeKey = eventId;
            if (!w.calls.add(dedupeKey)) continue;
            w.count++;
            w.lastId = eventId;
            w.lastMs = ts;
            if (w.samples.size() < MAX_SAMPLES) w.samples.add(clip(msg, 120));
            // 明确文本失败 1x 就入队；模糊形态仍要 3x（防单条误判造 memo）。
            // 但 async 任务行（server-async/sync、带 async_status）保持原 3x 语义：
            // 它们的终态失败本来就是显式判定，已有单测钉住「3 连败 → 1 条 memo」。
            boolean asyncRow = !str(d, "async_status").isBlank()
                    || "server-async".equals(writer) || "server-sync".equals(writer);
            if (w.count < THRESHOLD && !(clearTextFailure && !asyncRow)) continue;

            // 2026-11-02（A4）：1x 纯文本失败用**不含 firstId** 的稳定 id —— 否则窗口重建时
            // 同一形态会生成第二个 memoId（实测 tf-fish-…-pt-1），seen 挡不住，队列里出现重复。
            // 3x 窗口仍带 firstId（一个窗口 = 一次学习单元，需要可区分）。
            String shapePrefix = "tf-" + safe(tool) + "-" + fp;
            String memoId = shapePrefix + (clearTextFailure ? "" : "-" + safeSuffix(w.firstId));
            Memo memo = new Memo(memoId,
                    "工具 " + tool + " 连续执行失败 " + w.count + " 次（同一错误形态，不是参数被拒）：" + clip(msg, 160),
                    "tool_failure",
                    joinSamples(w.samples),
                    snapshotOf(configDir, cid, tool, w),
                    System.currentTimeMillis());
            boolean appended;
            boolean reallyInQueue = false;
            try {
                MemoQueue q = queueOf.apply(uuid);
                // 2026-11-03（外部学习者 #505/#509）：同一工具+错误形态在窗口内复发时，3x 窗口 id 带 firstId
                //   会生成**第二条 memo**（实测 tf-mine-664867ab：20:34:28 与 20:39:21 各一条，同指纹同错误、
                //   只是坐标差 4 格）。窗口内已有同前缀 memo 就不再入队，如实记一条 suppressed。
                if (sameShapeRecentlyQueued(q.all(), shapePrefix, memo.createdAt())) {
                    appended = false;
                    try {
                        LearnerMonitor.publish("tool_failure_same_shape_suppressed", Map.of(
                                "companion", String.valueOf(uuid), "tool", tool, "memo_id", memoId,
                                "reason", "same tool+error shape already queued within window"));
                    } catch (RuntimeException ignored) {
                        // 观测失败不影响主流程
                    }
                } else {
                    appended = q.append(memo);
                }
                // append=true 有两种含义：刚放进去 / 曾经入过队被去重。
                // 只有真在队列里才算「新入队」，否则重复事件会被记成新 memo 并写重复 sample。
                if (appended) reallyInQueue = q.contains(memo.id());
            } catch (RuntimeException e) {
                appended = false;
            }
            WINDOWS.remove(key);
            if (appended && reallyInQueue) {
                // 样本层（旁路）：工具失败口也走同一个入库入口 —— 161 §6.5 的三口收敛点。
                com.dwinovo.numen.plugins.learner.sample.SampleIntakeRuntime.shared(configDir).recordToolFailure(
                        String.valueOf(uuid), tool, cls, w.count, w.lastMs, w.firstId,
                        System.currentTimeMillis());
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
                + ", class=" + w.cls + ", journaled_by=" + (w.writer == null || w.writer.isBlank() ? "unknown" : w.writer)
                + ", window_first=" + w.firstId + ", window_last=" + w.lastId;
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

    /** 纯文本工具回执的三态（2026-11-02）：只按明确前缀判，模糊一律 UNKNOWN。 */
    enum TextVerdict { FAILURE, SUCCESS, UNKNOWN }

    private static final String[] TEXT_FAILURE_PREFIXES = {
            "didn't ", "did not ", "can't ", "cannot ", "couldn't ", "could not ",
            "failed", "no path", "nothing ", "not enough", "missing:",
            "invalid arguments", "blocked:", "no safe dry", "broke off"
    };

    private static final String[] TEXT_SUCCESS_PREFIXES = {
            "reached ", "arrived ", "ate ", "sleeping", "built ", "crafted ",
            "routed ", "look_around", "moved ", "placed "
    };

    /**
     * 判纯文本回执：只有**明确前缀**才算失败/成功；`interrupted after X/Y`（部分成功）
     * 与其余文本一律 UNKNOWN，不猜。前缀表覆盖 21:49–21:53 实测到的真实失败文案。
     */
    static TextVerdict classifyText(String result) {
        if (result == null || result.isBlank()) return TextVerdict.UNKNOWN;
        String t = result.trim().toLowerCase(java.util.Locale.ROOT);
        for (String p : TEXT_FAILURE_PREFIXES) {
            if (t.startsWith(p)) return TextVerdict.FAILURE;
        }
        if (t.startsWith("aim ") && t.contains("is blocked")) return TextVerdict.FAILURE;
        for (String p : TEXT_SUCCESS_PREFIXES) {
            if (t.startsWith(p)) return TextVerdict.SUCCESS;
        }
        return TextVerdict.UNKNOWN;
    }

    /** result 是**二次编码的 JSON 字符串**（{"success":false,"message":…}）。解析不出返回 null。 */
    private static Boolean successOf(String result) {
        try {
            JsonObject r = JsonParser.parseString(result).getAsJsonObject();
            return boolOf(r.get("success"));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** 只认 JSON boolean：字符串 "UNKNOWN"/"false"、数字、null 都是「不知道」，不猜。 */
    private static Boolean boolOf(com.google.gson.JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) return null;
        return e.getAsBoolean();
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

    /**
     * 2026-11-03（外部学习者）：同一条错误重复 N 次只写一行（"msg ×N"）。
     * 实测 tf-task_stop 那条 memo 的 tried 是同一句话三遍——重复串对复盘没有新信息，次数已写在 problem 里。
     * 不同错误仍逐条保留（顺序即出现顺序）。
     */
    static String joinSamples(java.util.List<String> samples) {
        if (samples == null || samples.isEmpty()) return "";
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String s : samples) {
            if (s == null) continue;
            counts.merge(s.trim(), 1, Integer::sum);
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
            parts.add(e.getValue() > 1 ? e.getKey() + " ×" + e.getValue() : e.getKey());
        }
        return String.join(" ｜ ", parts);
    }

    /** 同一工具+错误形态的去重窗口：30 分钟内不重复入队。 */
    static final long SAME_SHAPE_DEDUPE_MS = 30L * 60_000L;

    /** 队列里是否已有同前缀、且在窗口内创建的 memo（纯函数，便于单测）。 */
    static boolean sameShapeRecentlyQueued(java.util.List<Memo> queued, String shapePrefix, long nowMs) {
        if (queued == null || shapePrefix == null || shapePrefix.isBlank()) return false;
        for (Memo m : queued) {
            if (m == null) continue;
            String id = m.id() == null ? "" : m.id();
            if (!id.startsWith(shapePrefix)) continue;
            long age = nowMs - m.createdAt();
            if (age >= 0 && age < SAME_SHAPE_DEDUPE_MS) return true;
        }
        return false;
    }
}
