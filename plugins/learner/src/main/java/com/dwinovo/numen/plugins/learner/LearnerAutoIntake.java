package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.plugins.learner.core.CandidateGate;
import com.dwinovo.numen.plugins.learner.core.CandidateMemo;
import com.dwinovo.numen.plugins.learner.core.FeedbackChannel;
import com.dwinovo.numen.plugins.learner.core.FeedbackEvent;
import com.dwinovo.numen.plugins.learner.core.Memo;
import com.dwinovo.numen.plugins.learner.core.MemoQueue;
import com.dwinovo.numen.plugins.learner.core.StormGuard;
import com.dwinovo.numen.plugins.learner.core.T3Seen;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// E2 消费提醒：队列非空时催一次复盘（复用既有 learner_review 管线，不另造执行器）

/**
 * E1 自动入队（81 号 §14.4 第一轮）：真实失败事件 → 候选，**不依赖 AI 记得调 learner_intake**。
 *
 * <h2>为什么要有它（2026-10-25 实测）</h2>
 * 当天 instrumentation 有 6 条 T2 命中（5 空转 + 1 死亡），`learner_*` 工具全窗口 0 次调用 ——
 * 「该不该学」的判定与队列都建好了，但唯一的入口是 AI 自觉调用，而它**从不调用**。
 * 这正是核准回执点出的第四瓶颈：发现问题→定谁处理→执行→确认，四步之间没有稳定交接。
 * 本类补的是第一跳：**事件自己流进队列**。
 *
 * <h2>与 LearnerIntakeTool（手动口）的关系 —— 共用一切，不另造入口</h2>
 * <ul>
 *   <li>同一个事件源（`FeedbackChannel.pull`，只读）；</li>
 *   <li>同一个判定（`CandidateGate.evaluate`）；</li>
 *   <li>同一个风暴抑制（`StormGuard.admit`，按（类型,指纹）分桶）；</li>
 *   <li>同一个队列（`MemoQueue`）与同一套质量门 —— 自动口**没有任何放宽**。</li>
 * </ul>
 * 所以它不是「绕过」，是把手动口那套逐字复用成「没人按也会跑」的形式。
 *
 * <h2>边界（与手动口完全一致，一处不加）</h2>
 * <ol>
 *   <li>只读事件源 + 只写学习者自己的 memo 队列；不碰世界、不派身体（RL-9 不变）；</li>
 *   <li>不写经验库 —— 入队的是<b>待审候选</b>，不是经验；提炼仍归 learner_review；</li>
 *   <li>每条入队都落 learner.jsonl 事件（`auto_intake`），决策可逐条对账；</li>
 *   <li>读失败/队列写失败 = 记一次失败并停这一轮，绝不让它拖垮 tick（观测旁路纪律）。</li>
 * </ol>
 *
 * <h2>为什么按「事件身份」去重而不是靠 StormGuard</h2>
 * 手动口靠调用方传 cursor；自动口没有调用方，所以自己记每类事件源的游标。
 * 游标按（存档代际 + 事件源文件）存，换存档即重算 —— 与 FeedbackChannel 的 generation
 * 同一口径，避免把上一世的字节偏移量用在这一世。
 */
final class LearnerAutoIntake {

    private LearnerAutoIntake() {}

    /** 扫描间隔：每 30 秒（600 tick）看一次事件流。学习不需要亚秒级响应。 */
    static final long SCAN_INTERVAL_TICKS = 600L;

    /** 单轮入队上限：与手动口同值（16）。风暴抑制之后仍要防一次灌满队列。 */
    private static final int MAX_ENQUEUE_PER_SCAN = 16;

    /** 游标文件：<configDir>/learner-auto-cursor.json。进程外可读可删（删了=从头扫，靠幂等键防重）。 */
    private static final String CURSOR_FILE = "learner-auto-cursor.json";

    /** 开关文件：<configDir>/learner-auto-intake.json，形如 {"enabled":true}。默认<b>开</b>。 */
    private static final String TOGGLE_FILE = "learner-auto-intake.json";

    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    /** （存档代际 → 游标字节）。只进内存，落盘见 {@link #loadCursor}/{@link #saveCursor}。 */
    private static final Map<String, Long> CURSORS = new ConcurrentHashMap<>();

    /**
     * 幂等键：入队成功的 memo_id 一律见过就跳（重放/重启/**换存档代**都不重复入队）。
     *
     * <p>2026-10-25 实机抓到：只在内存里放这个集合挡不住「换代重扫」——每次世界重载
     * 生成新 generation，游标按代归零，81 条历史全部重扫；新 JVM 的内存集合是空的，
     * 于是同 8 条事件再次入队（e44 队列 15→21）。核准回执 Q4 说的「事件身份」
     * 必须落盘才成立。存成 append-only 的 id 行文件（¦learner-enqueued.jsonl¦），
     * 与游标同一目录、同一原子纪律；读取失败=按空集处理（宁可重复入队，
     * 也不让观测面的损坏阻塞学习主流程——重复会被复盘的质量门看见并拒绝）。
     */
    private static final java.util.Set<String> ENQUEUED = ConcurrentHashMap.newKeySet();
    private static final int MAX_ENQUEUED_KEYS = 4096;
    /** 已入队 id 的持久日志（一行一个 JSON 对象 {"id":...}）。 */
    private static final String ENQUEUED_JOURNAL = "learner-enqueued.jsonl";

    private static volatile boolean enabled = true;
    private static volatile long lastToggleCheck = -1L;
    private static volatile Path configDirCache;

    // ── E2：消费提醒（81 号 §14.4：队列只进不出=新瓶颈） ─────────────────────
    // 入队解决「没人发现」，这一段解决「发现了没人处理」。做法不是再造一个
    // headless 复盘器（那会复制一套质量门与落盘语义），而是把「该复盘了」
    // 作为**系统事件**推给执行 AI，让它调既有 learner_review —— 走的还是
    // 同一条 LLM→质量门→草稿→投递箱 管线，闸门一个不少。
    /** 复盘提醒冷却（毫秒）：同一同伴 10 分钟内只催一次。 */
    static final long REVIEW_NUDGE_COOLDOWN_MS = 10L * 60L * 1000L;
    /** 深度达到该值才催（太少不值得打断执行）。 */
    static final int REVIEW_NUDGE_MIN_DEPTH = 3;
    private static final Map<UUID, Long> LAST_REVIEW_NUDGE = new ConcurrentHashMap<>();

    /**
     * 队列解析器：生产用 LearnerPlugin::queue；测试注入直建 MemoQueue。
     * 这层接缝存在的唯一理由：LearnerPlugin 的类加载链要 NeoForge（纯 JVM 单测会
     * NoClassDefFoundError），而自动入队本身是纯逻辑 —— 不把队列来源做成注入，
     * 这段逻辑一行都测不到。
     */
    // 默认 null：生产 setup() 里装（见 install）——写在这里的初始化器会在类加载时就
    // 触碰 LearnerPlugin，而它的类链要 NeoForge（纯 JVM 单测 NoClassDefFoundError）。
    private static volatile java.util.function.Function<UUID, MemoQueue> queueResolver;

    /** setup 时调一次：记下 configDir、读开关、重建跨会话 T3 计数。 */
    static void install(Path configDir) {
        configDirCache = configDir;
        reloadToggle();
        loadEnqueuedJournal();
        rebuildT3FromHistory();
    }

    /** 把盘上的入队 id 日志读回内存（一行一个 {"id":...}；坏行跳过不致命）。 */
    private static void loadEnqueuedJournal() {
        Path f = configDirCache == null ? null : configDirCache.resolve(ENQUEUED_JOURNAL);
        if (f == null || !Files.isRegularFile(f)) return;
        try (var reader = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    String id = com.google.gson.JsonParser.parseString(line)
                            .getAsJsonObject().get("id").getAsString();
                    if (id != null && !id.isBlank()) ENQUEUED.add(id);
                } catch (RuntimeException ignored) {
                    // 坏行=跳过；这条事件最坏被重复入队，复盘质量门会看见
                }
            }
        } catch (java.io.IOException ignored) {
            // 读不到=空集（诚实方向：宁可重复，不阻塞）
        }
        if (ENQUEUED.size() > MAX_ENQUEUED_KEYS) trimEnqueuedKeys();
    }

    /** 入队成功后把 id 追加进盘上日志（失败只丢防重，不丢队列条目本身）。 */
    private static void appendEnqueuedJournal(String memoId) {
        Path f = configDirCache == null ? null : configDirCache.resolve(ENQUEUED_JOURNAL);
        if (f == null) return;
        try {
            Files.createDirectories(f.getParent());
            String nl = System.lineSeparator();
            Files.writeString(f, "{\"id\":\"" + memoId + "\"}" + nl, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException ignored) {
            // 日志写失败不回滚队列（队列条目本身还在，最坏是下次重扫重复）
        }
    }

    private static void trimEnqueuedKeys() {
        // 上限防膨胀：清空内存集（盘上日志仍在，但旧 id 不再防重——最坏重复入队，
        // 质量门会看见；比让集合无限长更可接受）
        ENQUEUED.clear();
    }

    /**
     * 跨会话 T3 重建（81 号 §14.1 Q4）：把本存代的 instrumentation.jsonl 从头重放，
     * 失败族事件按（同伴,指纹）计数灌回 T3Seen。真源是事件文件本身，不另建账本。
     * 失败=计数表为空（会话内新事件照常 bump），不拖垮启动。
     */
    private static void rebuildT3FromHistory() {
        try {
            Path dir = configDirCache;
            if (dir == null) return;
            String generation = FeedbackChannel.generationForConfigDir(dir);
            List<FeedbackEvent> all = new ArrayList<>();
            long cursor = 0L;
            while (true) {
                FeedbackChannel.Pull pull = new FeedbackChannel.Pull();
                var page = FeedbackChannel.pull(dir.resolve("monitor/instrumentation.jsonl"),
                        generation, cursor, null, pull).events;
                if (page == null || page.isEmpty()) break;
                all.addAll(page);
                long next = pull.nextCursor;
                if (next <= cursor) break;
                cursor = next;
                if (all.size() > 100_000) break;   // 防御：事件文件异常膨胀时不无限扫
            }
            int counted = T3Seen.rebuildFrom(all);
            if (counted > 0) {
                LearnerMonitor.publish("t3_rebuilt", Map.of(
                        "generation", generation, "events_replayed", all.size(), "t2_counted", counted));
            }
        } catch (RuntimeException | NoClassDefFoundError e) {
            // 重建失败 = 计数退化为会话内（诚实边界不变），不影响入队主流程
            LearnerMonitor.publish("t3_rebuild_failed", Map.of("reason", safe(e)));
        }
    }

    static boolean enabled() {
        return enabled;
    }

    /** 生产入口：LearnerPlugin.setup 时调（默认队列解析器在这里装）。 */
    static void installDefaultQueueResolver() {
        if (queueResolver == null) {
            queueResolver = LearnerPlugin::queue;
        }
    }

    /** 测试接缝：换队列解析器。生产代码不许调。 */
    static void installQueueResolverForTest(java.util.function.Function<UUID, MemoQueue> r) {
        queueResolver = r;
    }

    /** 每 tick 由 LearnerMod 调；内部自己节流。观测/入队任何异常都不外抛。 */
    static void onServerTick(long gameTimeTicks) {
        try {
            if (configDirCache == null || !maybeReloadToggle()) return;
            if (!enabled) return;
            if (gameTimeTicks <= 0 || gameTimeTicks % SCAN_INTERVAL_TICKS != 0) return;
            scanOnce();
            // B1（契约 §4③）：普通工具失败的确定性采集（聚合后入同一个队列，不另造通道）
            try {
                ToolFailureIntake.scanOnce(configDirCache, queueResolver);
            } catch (RuntimeException | LinkageError ignored) {
                // 采集是增强旁路：它坏了不许影响 tick
            }
            maybeNudgeReview();
        } catch (RuntimeException | java.io.IOError ignored) {
            // 自动入队是增强旁路：它坏了不许影响游戏主循环
        }
    }

    private static boolean maybeReloadToggle() {
        long now = System.currentTimeMillis();
        if (lastToggleCheck > 0 && now - lastToggleCheck < 10_000L) return true;
        lastToggleCheck = now;
        reloadToggle();
        return true;
    }

    private static void reloadToggle() {
        Path dir = configDirCache;
        if (dir == null) return;
        try {
            Path f = dir.resolve(TOGGLE_FILE);
            if (!Files.isRegularFile(f)) {
                Files.createDirectories(dir);
                Map<String, Object> def = new LinkedHashMap<>();
                def.put("_note", "自动入队开关：true=自动把 T2/T3 命中的失败事件转成学习者候选；false=回到纯手动（learner_intake）。删文件=恢复默认开。");
                def.put("enabled", true);
                Files.writeString(f, GSON.toJson(def) + "\n", StandardCharsets.UTF_8);
                enabled = true;
                return;
            }
            String raw = Files.readString(f, StandardCharsets.UTF_8);
            enabled = !String.valueOf(com.google.gson.JsonParser.parseString(raw)
                    .getAsJsonObject().get("enabled")).equalsIgnoreCase("false");
        } catch (RuntimeException | java.io.IOException ignored) {
            enabled = true;   // 读失败=保持开（诚实方向：宁可多扫，不静默失明）
        }
    }

    /** 一次扫描：拉增量 → 逐条判定 → 入队 → 落事件。返回本轮入队数（测试用）。 */
    static int scanOnce() {
        // 开关也挡直调（测试/未来别的调用方）：关了就是关了，不许绕过。
        if (!enabled) return 0;
        Path dir = configDirCache;
        if (dir == null) return 0;
        String generation = FeedbackChannel.generationForConfigDir(dir);
        long cursor = loadCursor(generation);
        Path jsonl = dir.resolve("monitor/instrumentation.jsonl");
        FeedbackChannel.Pull pull = new FeedbackChannel.Pull();
        List<FeedbackEvent> events;
        try {
            events = FeedbackChannel.pull(jsonl, generation, cursor, null, pull).events;
        } catch (RuntimeException e) {
            LearnerMonitor.publish("auto_intake_failed", Map.of(
                    "reason", "read_failed: " + safe(e), "cursor", cursor));
            return 0;
        }
        if (events == null || events.isEmpty()) {
            saveCursor(generation, pull.nextCursor);   // 没有新事件也推进游标（文件被截短时防卡死）
            return 0;
        }

        // 事件归属的同伴各建一条队列；无主事件如实跳过（手动口同款三态）
        Map<UUID, Integer> enqueuedBy = new LinkedHashMap<>();
        int rejected = 0, unattributed = 0, stormSuppressed = 0, dupSkipped = 0, queueFull = 0;

        for (FeedbackEvent ev : events) {
            String owner = CandidateMemo.companionOf(ev);
            if (owner == null) { unattributed++; continue; }
            UUID companionId;
            try {
                companionId = UUID.fromString(owner);
            } catch (IllegalArgumentException e) { unattributed++; continue; }

            String fp = CandidateGate.fingerprint(ev.sourceType(), ev.observation());
            int seenBefore = T3Seen.seenBefore(companionId.toString(), fp);
            T3Seen.bump(companionId.toString(), fp);
            CandidateGate.Decision d = CandidateGate.evaluate(ev.sourceType(), ev.observation(), seenBefore);
            if (!d.enqueue()) { rejected++; continue; }

            String memoId = "ai-" + ev.eventId();
            if (!ENQUEUED.add(memoId)) { dupSkipped++; continue; }
            if (ENQUEUED.size() > MAX_ENQUEUED_KEYS) trimEnqueuedKeys();

            StormGuard.Ruling storm = StormGuard.admit(companionId.toString(), fp, System.currentTimeMillis());
            if (!storm.allow()) { stormSuppressed++; continue; }

            int soFar = enqueuedBy.getOrDefault(companionId, 0);
            if (soFar >= MAX_ENQUEUE_PER_SCAN) { continue; }

            Memo memo = CandidateMemo.from(ev, d);
            MemoQueue queue = queueResolver.apply(companionId);
            boolean ok;
            try {
                ok = queue.append(new Memo(memoId, memo.problem(), memo.stage(), memo.tried(),
                        memo.snapshot(), System.currentTimeMillis()));
            } catch (RuntimeException e) {
                LearnerMonitor.publish("auto_intake_failed", Map.of(
                        "reason", "queue_write_failed: " + safe(e), "memo_id", memoId));
                break;
            }
            if (ok) {
                enqueuedBy.merge(companionId, 1, Integer::sum);
                appendEnqueuedJournal(memoId);   // 落盘防「换代重扫」重复入队
            } else {
                queueFull++;
            }
        }

        long next = pull.nextCursor;
        saveCursor(generation, next);

        if (!enqueuedBy.isEmpty() || rejected > 0 || stormSuppressed > 0 || unattributed > 0 || dupSkipped > 0 || queueFull > 0) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("generation", generation);
            data.put("events_scanned", events.size());
            data.put("enqueued_by_companion", enqueuedBy);
            data.put("rejected_by_gate", rejected);
            data.put("storm_suppressed", stormSuppressed);
            data.put("unattributed", unattributed);
            data.put("duplicate_skipped", dupSkipped);
            data.put("queue_full", queueFull);
            LearnerMonitor.publish("auto_intake", data);
        }
        return enqueuedBy.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * E2 消费提醒：谁的队列积了 ¦REVIEW_NUDGE_MIN_DEPTH¦ 条以上，且冷却期已过，
     * 就给它推一条系统消息「learner_review 有 N 条待复盘」。真正的复盘仍由
     * 执行 AI 调工具完成 —— 质量门、上轮回顾、拒收回喂、投递箱全部走原管线。
     *
     * <p>预算边界：这里只发一条文本（不触发 LLM），LLM 成本由 learner_review
     * 自身的 review 角色 + BudgetPolicy 控制（E1 shadow 已接）；冷却防重复打扰。
     */
    static void maybeNudgeReview() {
        try {
            if (configDirCache == null || queueResolver == null) return;
            long now = System.currentTimeMillis();
            // 队列按同伴隔离；用游标事件里见过的同伴集合不可靠，改扫 memo 文件名
            java.util.List<UUID> companions = companionsWithMemos();
            for (UUID id : companions) {
                MemoQueue q = queueResolver.apply(id);
                int depth;
                try {
                    depth = q.size();
                } catch (RuntimeException e) {
                    continue;
                }
                if (depth < REVIEW_NUDGE_MIN_DEPTH) continue;
                Long last = LAST_REVIEW_NUDGE.get(id);
                if (last != null && now - last < REVIEW_NUDGE_COOLDOWN_MS) continue;
                if (LAST_REVIEW_NUDGE.putIfAbsent(id, now) != null) continue;
                boolean sent = LearnerPlugin.nudgeSystem(id,
                        "[学习者] 队列里有 " + depth + " 条待复盘的备忘录（都是真实失败事件，不是测试）。"
                        + "现在没有正在跑的复盘；下一轮请调用 learner_review 处理它们——"
                        + "该成经验的经验、该拒绝的拒绝（拒绝也要给理由），别让队列一直积着。");
                if (sent) {
                    LearnerMonitor.publish("review_nudge_sent", Map.of(
                            "companionId", id.toString(), "queue_depth", depth));
                } else {
                    LAST_REVIEW_NUDGE.remove(id);   // 没送出去就不占冷却
                }
            }
        } catch (RuntimeException | NoClassDefFoundError ignored) {
            // 提醒是增强旁路：坏了不许影响 tick
        }
    }

    /** 从 memo 文件名反推哪些同伴有待复盘队列（文件名即契约：learner-memos-<uuid>.json）。 */
    private static java.util.List<UUID> companionsWithMemos() {
        java.util.List<UUID> out = new java.util.ArrayList<>();
        try (var stream = Files.list(configDirCache)) {
            stream.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("learner-memos-") && n.endsWith(".json");
                    })
                    .forEach(p -> {
                        String n = p.getFileName().toString();
                        try {
                            out.add(UUID.fromString(n.substring("learner-memos-".length(), n.length() - ".json".length())));
                        } catch (IllegalArgumentException ignored) {
                        }
                    });
        } catch (java.io.IOException ignored) {
            // 目录读不到=没有队列可催
        }
        return out;
    }

    // ── 游标持久化（tmp + 原子替换，抄 MemoQueue 的做法） ──────────────────

    private static long loadCursor(String generation) {
        Long cached = CURSORS.get(generation);
        if (cached != null) return cached;
        try {
            Path f = cursorFile();
            if (Files.isRegularFile(f)) {
                var o = com.google.gson.JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                Long v = o.has(generation) && o.get(generation).isJsonPrimitive()
                        ? o.get(generation).getAsLong() : null;
                if (v != null) { CURSORS.put(generation, v); return v; }
            }
        } catch (RuntimeException | java.io.IOException ignored) {
            // 读不到=从 0 开始扫，幂等键会挡住重复入队
        }
        return 0L;
    }

    private static void saveCursor(String generation, long cursor) {
        CURSORS.put(generation, cursor);
        try {
            Path f = cursorFile();
            Files.createDirectories(f.getParent());
            var o = new com.google.gson.JsonObject();
            o.addProperty(generation, cursor);
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (RuntimeException | java.io.IOException ignored) {
            // 游标存不下=下次多扫一遍（幂等键挡住重复），不致命
        }
    }

    private static Path cursorFile() {
        return configDirCache.resolve(CURSOR_FILE);
    }

    private static String safe(Throwable e) {
        String s = String.valueOf(e.getMessage());
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
