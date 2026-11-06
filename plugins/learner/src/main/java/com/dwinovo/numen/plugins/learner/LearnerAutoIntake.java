package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.plugins.learner.core.CandidateGate;
import com.dwinovo.numen.plugins.learner.core.CandidateMemo;
import com.dwinovo.numen.plugins.learner.core.FeedbackChannel;
import com.dwinovo.numen.plugins.learner.core.FeedbackEvent;
import com.dwinovo.numen.plugins.learner.core.Memo;
import com.dwinovo.numen.plugins.learner.core.MemoQueue;
import com.dwinovo.numen.plugins.learner.core.StormGuard;
import com.dwinovo.numen.plugins.learner.core.SuppressedStore;
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

    /** 单轮从「被抑制暂存区」放回的上限（2026-10-26）：小步回流，别一次灌满。 */
    private static final int MAX_DEFERRED_PER_SCAN = 4;

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

    /**
     * 事件新鲜度上限（2026-11-02）：超过这个年龄的 instrumentation 事件只计 stale、不入队。
     *
     * <p>为什么要有：重启→代际变→游标归零→重扫历史，会把 10-31 从未入过队的老事件
     * 按「新事件」放进来（实测 01:25 队列 56→64，seen 只能挡「曾入队过」的 id）。
     * 学习线要学的是刚刚发生的事；两天前的死亡不该在今天才变成经验。
     * 没时间戳/解析失败的事件不猜，照旧处理。
     */
    static final long MAX_EVENT_AGE_MS = 2L * 60L * 60L * 1000L;
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
    /** 复盘失败后的退避：30 分钟起，按连续失败次数翻倍，封顶 60 分钟。 */
    static final long REVIEW_NUDGE_FAIL_MIN_MS = 30L * 60L * 1000L;
    static final long REVIEW_NUDGE_FAIL_MAX_MS = 60L * 60L * 1000L;
    /** 同伴 → 连续复盘失败次数（0/缺省 = 正常）。 */
    private static final java.util.Map<java.util.UUID, Integer> REVIEW_FAIL_STREAK =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 同伴 → 「提醒送不到」的退避截止时刻（防每 30 秒重试）。 */
    private static final java.util.Map<java.util.UUID, Long> NUDGE_UNDELIVERABLE_UNTIL =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 自动复盘（2026-11-02）：提醒发了没人响应时的兜底。
     *
     * <p>实测 18:13–19:23 七次 nudge 只有三次真的启动了 review —— 触发链最后一跳
     * 完全押在「执行 AI 自觉」上，队列不会自己降。这里在「上一次提醒之后没有任何
     * 复盘开始」时，直接按执行 AI 的同一条接口调用 learner_review（不是旁路、
     * 不复制管线：质量门/上轮回顾/commit/announce 全走原工具）。
     *
     * <p>真实代价：一次复盘实测 ~28–45k tokens，所以加 30 分钟冷却，且只在
     * 「上一次提醒未被响应」时触发一次；事件留痕 auto_review_started。
     */
    private static final long AUTO_REVIEW_COOLDOWN_MS = 30L * 60L * 1000L;
    private static final java.util.Map<java.util.UUID, Long> LAST_AUTO_REVIEW_AT =
            new java.util.concurrent.ConcurrentHashMap<>();
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

    /**
     * 时间戳可解析且早于上限 → stale。
     *
     * <p>没有时间戳 / 解析失败 → **走新鲜路径**（返回 false，照旧处理）：
     * 宁可保留一条时间未知的事件，也不静默丢；这是刻意语义，有测试钉住。
     */
    static boolean isStale(FeedbackEvent ev, long nowMs) {
        if (ev == null || ev.ts() == null || ev.ts().isBlank()) return false;
        try {
            long t = java.time.Instant.parse(ev.ts()).toEpochMilli();
            return nowMs - t > MAX_EVENT_AGE_MS;
        } catch (RuntimeException parseFailed) {
            return false;
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
        int rejected = 0, unattributed = 0, stormSuppressed = 0, dupSkipped = 0, queueFull = 0, stale = 0;
        long nowMs = System.currentTimeMillis();

        for (FeedbackEvent ev : events) {
            if (isStale(ev, nowMs)) { stale++; continue; }
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
            if (!storm.allow()) {
                stormSuppressed++;
                // 2026-10-26：抑制**不再丢弃**。实机代价是床边没睡那条最干净的样本被挡掉后
                // 永久消失（auto_intake storm_suppressed=1，手动 intake 再撞 10 分钟冷却）。
                // 改成降级暂存：冷却窗口过去后由 admitDeferred() 放回队列。
                if (configDirCache != null) {
                    Memo raw = CandidateMemo.from(ev, d);
                    long nowMillis = System.currentTimeMillis();
                    SuppressedStore.append(configDirCache, companionId,
                            new Memo(memoId, raw.problem(), raw.stage(), raw.tried(), raw.snapshot(), nowMillis),
                            fp, storm.code(), nowMillis);
                }
                continue;
            }

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
                boolean inQueue;
                try {
                    inQueue = queue.contains(memoId);
                } catch (RuntimeException e) {
                    inQueue = true;   // 查不了就按「已入队」处理，不制造第二条样本
                }
                if (inQueue) {
                    enqueuedBy.merge(companionId, 1, Integer::sum);
                    appendEnqueuedJournal(memoId);   // 落盘防「换代重扫」重复入队
                    // 样本层（旁路）：与手动口同一个入库入口，只换 source。
                    com.dwinovo.numen.plugins.learner.sample.SampleIntakeRuntime.shared(configDirCache).recordEvent(
                            ev, com.dwinovo.numen.plugins.learner.sample.SampleIntakeAdapter.SRC_AUTO, System.currentTimeMillis());
                } else {
                    // append=true 但队列里没有：seen 命中的重复事件，不得计新入队/写样本。
                    dupSkipped++;
                }
            } else {
                queueFull++;
            }
        }

        long next = pull.nextCursor;
        saveCursor(generation, next);

        // 冷却已过的「被抑制候选」放回队列（与入队同一条 append 路径；写不进就放回暂存区）
        int deferredAdmitted = admitDeferred(enqueuedBy);

        // TTL 归档（低频旁路，158 §8）：只归档不删证据；样本层没启用时是 no-op。
        // 时长可配（learner-sample-ttl.json）；7 天是**未校准**初值，写死等于在队列长期满的情况下永不归档。
        try {
            long ttl = com.dwinovo.numen.plugins.learner.sample.SampleTtlConfig.ttlMillis(configDirCache);
            int archived = com.dwinovo.numen.plugins.learner.sample.SampleIntakeRuntime
                    .shared(configDirCache)
                    .sweepExpired(System.currentTimeMillis(), ttl);
            if (archived > 0) {
                // 归档必须留痕：否则"样本怎么不见了"会变成一次考古。
                LearnerMonitor.publish("sample_ttl_archived", Map.of(
                        "archived", archived, "ttl_millis", ttl));
            }
        } catch (RuntimeException ignore) {
            // 归档失败不改扫描结果：它只是旁路。
        }

        if (!enqueuedBy.isEmpty() || rejected > 0 || stormSuppressed > 0 || unattributed > 0 || dupSkipped > 0 || queueFull > 0
                || deferredAdmitted > 0 || stale > 0) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("generation", generation);
            data.put("events_scanned", events.size());
            data.put("enqueued_by_companion", enqueuedBy);
            data.put("rejected_by_gate", rejected);
            data.put("storm_suppressed", stormSuppressed);
            data.put("unattributed", unattributed);
            data.put("duplicate_skipped", dupSkipped);
            data.put("queue_full", queueFull);
            data.put("deferred_admitted", deferredAdmitted);
            data.put("stale_skipped", stale);
            LearnerMonitor.publish("auto_intake", data);
        }
        return enqueuedBy.values().stream().mapToInt(Integer::intValue).sum();
    }


    /**
     * 把冷却已过的「被抑制候选」放回队列（2026-10-26）。
     *
     * <p>背景：StormGuard 原来把冷却窗口内的候选直接丢掉，实机上代价是
     * {@code bedDistance=2.83, isNight=true, sleeping=false} 这条最干净的样本被
     * {@code storm_suppressed=1} 挡掉后永久消失。现在抑制 = 暂存 + 冷却后放回。
     *
     * <p>写不回队列（满）就 {@link SuppressedStore#append} 放回暂存区，样本不丢。
     *
     * @param enqueuedBy 本轮已入队计数（放回也算占用同一轮上限）
     * @return 本轮放回几条
     */
    private static int admitDeferred(Map<UUID, Integer> enqueuedBy) {
        if (configDirCache == null || queueResolver == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        int admitted = 0;
        for (UUID id : SuppressedStore.companionsWithSuppressed(configDirCache)) {
            if (admitted >= MAX_DEFERRED_PER_SCAN) {
                break;
            }
            int room = MAX_ENQUEUE_PER_SCAN - enqueuedBy.getOrDefault(id, 0);
            if (room <= 0) {
                continue;
            }
            int cap = Math.min(room, MAX_DEFERRED_PER_SCAN - admitted);
            List<SuppressedStore.Entry> due =
                    SuppressedStore.drainDue(configDirCache, id, now, StormGuard.COOLDOWN_MILLIS, cap);
            for (SuppressedStore.Entry e : due) {
                // ★ 2026-11-02：暂存区放回也必须过同一道新鲜度闸 —— 否则 10-31 被风暴抑制的
                //   老条目冷却到期后又从后门进来（实测 17:39 deferred_admitted=4，全是老条目）。
                //   Entry 只带 memo.createdAt（抑制时刻），作为年龄代理：抑制超过 2h 的视为陈旧。
                //   边缘：无时间戳的老事件若“今天才被抑制”，会以 now 身份在 10 分钟后放回 ——
                //   这是已知限制（源事件缺 ts 时无法证明它老，与 scan 层「无 ts 走新鲜路径」同口径）。
                long bornAt = e.memo().createdAt();
                if (bornAt > 0 && now - bornAt > MAX_EVENT_AGE_MS) {
                    Map<String, Object> staleEv = new LinkedHashMap<>();
                    staleEv.put("memo_id", e.memo().id());
                    staleEv.put("age_ms", now - bornAt);
                    staleEv.put("reason", e.reason() == null ? "" : e.reason());
                    LearnerMonitor.publish("suppressed_stale_dropped", staleEv);
                    continue;
                }
                boolean ok;
                boolean inQueue = false;
                try {
                    MemoQueue q = queueResolver.apply(id);
                    ok = q.append(e.memo());
                    if (ok) inQueue = q.contains(e.memo().id());
                } catch (RuntimeException ex) {
                    ok = false;
                }
                if (ok && inQueue) {
                    admitted++;
                    enqueuedBy.merge(id, 1, Integer::sum);
                    appendEnqueuedJournal(e.memo().id());
                    LearnerMonitor.publish("suppressed_admitted", Map.of(
                            "memo_id", e.memo().id(),
                            "fingerprint", e.fingerprint(),
                            "reason", e.reason()));
                } else if (ok) {
                    // seen 命中：这条以前处理过，不再放回、也不计 admitted。
                } else {
                    // 队列满：放回暂存区再等一轮（at 拨到当前，不重算冷却）
                    SuppressedStore.append(configDirCache, id, e.memo(), e.fingerprint(), e.reason(), now);
                }
            }
        }
        return admitted;
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
                // 2026-11-02（目标要求：每一次死亡都要变成经验）：队列里有 death 时不等攒到 3 条，
                // 立刻允许提醒/兜底复盘 —— 否则一次死亡要等后面两次失败才可能被看到。
                boolean hasDeath = false;
                try {
                    for (Memo m : q.all()) {
                        if ("death".equals(m.stage())) {
                            hasDeath = true;
                            break;
                        }
                    }
                } catch (RuntimeException ignored) {
                    // 读不到就按原规则走，不假装有 death
                }
                // 2026-11-03：再加一条「等待超时」——最老待复盘 memo ≥30 分钟也算到门槛，
                // 否则单条非死亡事件（如 loop_detected）会无限期压着（158 §8 触发清单含"等待超时"）。
                long oldestAgeMs = 0;
                try {
                    for (Memo m : q.all()) {
                        long c = m.createdAt();
                        if (c > 0) oldestAgeMs = Math.max(oldestAgeMs, now - c);
                    }
                } catch (RuntimeException ignored) {
                    // 读不到按原规则走，不假装超时
                }
                // 真实深度用于报告/事件（不许造假数）；gateDepth 只用于门槛判断。
                int gateDepth = effectiveDepth(depth, hasDeath || staleEnough(oldestAgeMs));
                if (nudgeBlocked(NUDGE_UNDELIVERABLE_UNTIL, id, now)) continue;
                Long prevNudge = LAST_REVIEW_NUDGE.get(id);
                if (!claimNudge(LAST_REVIEW_NUDGE, id, gateDepth, now)) continue;
                boolean sent = LearnerPlugin.nudgeSystem(id, nudgeText(depth));
                if (sent) {
                    LearnerMonitor.publish("review_nudge_sent", Map.of(
                            "companionId", id.toString(), "queue_depth", depth));
                    // 上一次提醒之后一条 review 都没启动 → 这一跳不能继续押在执行 AI 自觉上。
                    long startedAt = LearnerPlugin.lastReviewStartedMs(id);
                    boolean running = LearnerPlugin.runningReview(id) != null;
                    if (autoReviewDue(prevNudge, startedAt, LAST_AUTO_REVIEW_AT.get(id), now, gateDepth, running)) {
                        maybeAutoReview(id, depth, now);
                    }
                } else {
                    // 送不到（同伴不在本世界 / 已归档）：不再用「remove 冷却位」当重试策略，
                    // 那样会每 30 秒重试一次（2026-11-02 00:29–00:32 实测 4 个旧同伴 × 每 30s 刷屏）。
                    LAST_REVIEW_NUDGE.remove(id);
                    NUDGE_UNDELIVERABLE_UNTIL.put(id, now + REVIEW_NUDGE_FAIL_MIN_MS);
                }
            }
        } catch (RuntimeException | NoClassDefFoundError ignored) {
            // 提醒是增强旁路：坏了不许影响 tick
        }
    }

    /** nudge 的正文（纯函数，便于钉住「说几条就是几条」）。 */
    static String nudgeText(int depth) {
        return "[学习者] 队列里有 " + depth + " 条待复盘的备忘录（都是真实失败事件，不是测试）。"
                + "现在没有正在跑的复盘；下一轮请调用 learner_review 处理它们"
                // ★ 2026-11-03（外部学习者、实机）：learner_review 是 DEFERRED 工具，不在常驻工具表里，
                //   要先 find_tools 拉到定义才能调。18:22:41 那次提醒被 AI 读到也口头接受了，但它
                //   没去 find_tools，5 分钟内一次 learner_review 都没调（#456）。提醒文案直接把这一步写出来。
                + "（learner_review 是延迟工具：先 find_tools 搜 learner_review 拉定义，再调用）——"
                + "该成经验的经验、该拒绝的拒绝（拒绝也要给理由），别让队列一直积着。";
    }

    /**
     * death 在队列里时按「已到门槛」处理（2026-11-02）。纯函数，供 nudge/自动复盘共用。
     * 理由：目标要求每一次死亡都要变成经验；按 3 条门槛，单次死亡会被无限期压后。
     */
    static int effectiveDepth(int depth, boolean hasDeath) {
        return hasDeath ? Math.max(depth, REVIEW_NUDGE_MIN_DEPTH) : depth;
    }

    /**
     * 单条非死亡 memo 的「等待超时」通道（2026-11-03，外部学习者）。
     *
     * <p>为什么要有它：158 §8 的调度触发里有一条「普通样本等待超时」，但原实现对 memo 只认
     * `depth >= 3`（death 另有 `effectiveDepth` 绕过）。实机 2026-11-03 17:22:30 一条
     * loop_detected memo 入队后 30 分钟既无 nudge 也无复盘——只要不攒到 3 条就无限期压着。
     *
     * <p>这里只放宽**门槛**（是否允许提醒/兜底复盘），不伪造 depth；真正的 LLM 调用仍受
     * `AUTO_REVIEW_COOLDOWN_MS`（30 分钟）与"已有 review 在跑"两道限制。阈值可调。
     */
    static final long REVIEW_STALE_MS = 30L * 60L * 1000L;

    /** 最老待复盘 memo 是否已超时。纯函数，便于钉住边界。 */
    static boolean staleEnough(long oldestAgeMs) {
        return oldestAgeMs >= REVIEW_STALE_MS;
    }

    /**
     * 提醒无人响应时的兜底：走执行 AI 的同一条工具接口（不另写管线）。
     *
     * <p>边界：队列 < 3 不跑；30 分钟内最多一次；已有 review 在跑不跑；
     * 找不到服务器/同伴/工具都如实放弃并留事件，不假装跑过。
     */
    /**
     * 纯判定：这次提醒之后该不该走自动复盘。抽出来是为了能在纯 JVM 单测里钉住策略，
     * 而不是靠实机撞。
     */
    static boolean autoReviewDue(Long prevNudge, long lastReviewStartedAt, Long lastAutoReviewAt,
                                 long now, int depth, boolean reviewRunning) {
        if (depth < REVIEW_NUDGE_MIN_DEPTH) return false;          // 队列太浅不值得花一次 LLM
        if (prevNudge == null) return false;                        // 还没有「上一次提醒」可比
        // 上一条提醒本身必须已经过了 nudge 冷却（≥10 分钟）。当前 nudge 触发时 prevNudge
        // 天然满足；这条显式写死，防以后有人调 nudge 冷却而忘了这里（审核窗口 03:50 要求）。
        if (now - prevNudge < REVIEW_NUDGE_COOLDOWN_MS) return false;
        if (lastReviewStartedAt > prevNudge) return false;          // 上次提醒有人响应了
        if (reviewRunning) return false;                            // 已经在跑
        return lastAutoReviewAt == null || now - lastAutoReviewAt >= AUTO_REVIEW_COOLDOWN_MS;
    }

    private static void maybeAutoReview(UUID id, int depth, long now) {
        try {
            if (depth < REVIEW_NUDGE_MIN_DEPTH) { skipped(id, depth, "queue_too_shallow"); return; }
            Long last = LAST_AUTO_REVIEW_AT.get(id);
            if (last != null && now - last < AUTO_REVIEW_COOLDOWN_MS) { skipped(id, depth, "cooldown"); return; }
            if (LearnerPlugin.runningReview(id) != null) { skipped(id, depth, "review_running"); return; }
            var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) { skipped(id, depth, "no_server"); return; }
            var companion = com.dwinovo.numen.entity.NumenPlayer.findByUuid(server, id);
            if (companion == null) { skipped(id, depth, "no_companion"); return; }
            var tool = com.dwinovo.numen.agent.tool.ToolRegistry.get("learner_review");
            if (tool == null) { skipped(id, depth, "no_tool"); return; }
            LAST_AUTO_REVIEW_AT.put(id, now);
            java.util.Map<String, Object> ev = new java.util.LinkedHashMap<>();
            ev.put("companion", id.toString());
            ev.put("queue_depth", depth);
            ev.put("reason", "nudge_unanswered");
            LearnerMonitor.publish("auto_review_started", ev);
            tool.onServerCall("auto-review-" + now, new com.google.gson.JsonObject(), companion, reply -> { });
        } catch (RuntimeException | NoClassDefFoundError e) {
            String msg = String.valueOf(e);
            LearnerMonitor.publish("auto_review_failed", java.util.Map.of(
                    "companion", id.toString(),
                    "error", msg.substring(0, Math.min(200, msg.length()))));
        }
    }

    /** 自动复盘静默早退的可观测记录（2026-11-06）：下次一眼看出卡在哪。 */
    private static void skipped(UUID id, int depth, String reason) {
        try {
            java.util.Map<String, Object> ev = new java.util.LinkedHashMap<>();
            ev.put("companion", id == null ? "" : id.toString());
            ev.put("queue_depth", depth);
            ev.put("reason", reason);
            LearnerMonitor.publish("auto_review_skipped", ev);
        } catch (RuntimeException ignored) { /* 观测失败不许影响主流程 */ }
    }

    /**
     * 冷却 + 门槛的纯判定：返回 true 才允许发，并在同一时刻占用冷却位。
     *
     * <p>★ 2026-11-02 修：旧写法先用 get 判冷却、再用 putIfAbsent 占位 ——
     * 第一次提醒之后 key 永远在，冷却到期也会被 putIfAbsent 挡掉 ⇒
     * **一次 JVM 生命周期只会提醒一次**（实测 23:28 起只发过 15:30:08 一条，
     * 队列 56 条、30 分钟不再触发）。这里改成标准的「读旧值 → 过期则覆盖」。
     */
    static boolean claimNudge(java.util.Map<java.util.UUID, Long> state,
                              java.util.UUID id, int depth, long now) {
        Integer streak = REVIEW_FAIL_STREAK.get(id);
        return claimNudge(state, id, depth, now, streak == null ? 0 : streak);
    }

    /**
     * 失败退避（2026-11-02 审核窗口）：复盘失败后不能按 10 分钟原速再催 ——
     * 一次 review 实测 35,429 tokens（15:31:59），失败驱动的高频重试正是 L2 禁止的。
     * 规则：连续失败 1 次 → 30 分钟，2 次 → 60 分钟（封顶）；一次成功复盘清零。
     */
    static boolean claimNudge(java.util.Map<java.util.UUID, Long> state,
                              java.util.UUID id, int depth, long now, int failStreak) {
        if (depth < REVIEW_NUDGE_MIN_DEPTH) return false;
        long cooldown = REVIEW_NUDGE_COOLDOWN_MS;
        if (failStreak > 0) {
            cooldown = Math.min(REVIEW_NUDGE_FAIL_MAX_MS, REVIEW_NUDGE_FAIL_MIN_MS * failStreak);
        }
        Long last = state.get(id);
        if (last != null && now - last < cooldown) return false;
        state.put(id, now);
        return true;
    }

    /**
     * 送不到的同伴（不在本世界/已归档）的退避判定：返回 true 表示本轮跳过。
     * 到期后自动清位，允许再试一次。
     */
    static boolean nudgeBlocked(java.util.Map<java.util.UUID, Long> undeliverable,
                                java.util.UUID id, long now) {
        Long until = undeliverable.get(id);
        if (until == null) return false;
        if (now < until) return true;
        undeliverable.remove(id);
        return false;
    }

    /** 复盘失败：记连续失败次数（供 nudge 退避）。 */
    static void noteReviewFailed(java.util.UUID id) {
        if (id == null) return;
        REVIEW_FAIL_STREAK.merge(id, 1, Integer::sum);
    }

    /** 复盘成功：清零失败计数，回到正常提醒节奏。 */
    static void noteReviewOk(java.util.UUID id) {
        if (id == null) return;
        REVIEW_FAIL_STREAK.remove(id);
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
