package com.dwinovo.numen.plugins.learner.sample;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 学习样本存储：JSONL 落盘 + 内存索引 + 重启幂等（158 §6 / 159 C）。
 *
 * <h2>为什么是 JSONL 而不是数据库</h2>
 * 159 §2 A 段的要求是「沿用 JSONL/文件桥，不新增跨插件编译依赖」。样本层要被 learner 写、
 * 被复盘读、被外部 Learner 离线读，还要能被 harness 直接拷进 run 证据目录。
 * 一行一个 JSON 的文件同时满足这三件事，而且出问题时能直接拿文本编辑器和 grep 看。
 *
 * <h2>重启幂等是怎么保证的（这是本类最核心的一条）</h2>
 * 幂等不靠「记一个已处理的列表」，靠<b>指纹索引能从盘上原样重建</b>：
 * {@link #open} 读回全部样本，用每条自己的 {@code fingerprint} 重建索引；
 * 之后再来一次同样的上报，走的就是「强指纹命中 ⇒ 追加 occurrence」。
 * 如果索引从内存重建、id 重新生成，重启后同一事故就会变成第二条样本 ——
 * 这正是 159 §3 剧本 1 要钉死的行为。
 *
 * <h2>为什么整文件重写，而不是追加</h2>
 * occurrence 追加会改已有样本的那一行，JSONL 追加语义表达不了「改一行」。
 * 所以采用与 {@code ExperienceStore.persist} 同款做法：内存为准 + 临时文件 + 原子移动。
 * 代价（必须知道）：样本量极大时每次写入是 O(样本数)。样本是「事件级」数据、按 TTL 归档，
 * 目前量级下可以接受；真要上万条，得改成「按样本分文件 + 索引文件」，那是一次迁移，
 * 不在本批。
 *
 * <p>线程安全：所有公开方法 {@code synchronized}。样本读写发生在复盘线程与 intake 线程，
 * 并发写同一个文件必然出现半截行。
 */
public final class SampleStore {

    /** 落盘格式版本。加字段不升版本；删改语义必须升。 */
    public static final int FORMAT_VERSION = 1;

    private final Path file;
    private final Path eventsFile;
    private final long bucketMillis;
    private final java.util.ArrayDeque<SampleEvent> recentEvents = new java.util.ArrayDeque<>();
    private static final int MAX_RECENT_EVENTS = 256;
    private final Map<String, LearningSample> byId = new LinkedHashMap<>();
    private final Map<String, String> byFingerprint = new HashMap<>();
    private final Map<String, List<String>> byWeak = new HashMap<>();
    private final List<String> loadProblems = new ArrayList<>();
    private int skippedLines;

    private SampleStore(Path file, long bucketMillis) {
        this.file = file;
        this.eventsFile = file.resolveSibling("samples-events.jsonl");
        this.bucketMillis = bucketMillis;
    }

    /** 用默认时间桶打开（不存在则视为空库；父目录缺了会建）。 */
    public static SampleStore open(Path file) throws IOException {
        return open(file, SampleFingerprint.DEFAULT_BUCKET_MILLIS);
    }

    public static SampleStore open(Path file, long bucketMillis) throws IOException {
        if (bucketMillis <= 0) {
            throw new IllegalArgumentException("bucketMillis 必须为正数");
        }
        SampleStore store = new SampleStore(file, bucketMillis);
        store.load();
        return store;
    }

    private void load() throws IOException {
        byId.clear();
        byFingerprint.clear();
        byWeak.clear();
        loadProblems.clear();
        skippedLines = 0;
        if (!Files.exists(file)) {
            return;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int lineNo = 0;
        for (String line : lines) {
            lineNo++;
            if (line == null || line.isBlank()) {
                continue;
            }
            try {
                LearningSample s = fromJson(JsonParser.parseString(line).getAsJsonObject());
                index(s);
            } catch (RuntimeException e) {
                // 坏行不静默丢弃：计数 + 留原文片段，加载后由 problems() 报出去。
                skippedLines++;
                if (loadProblems.size() < 20) {
                    loadProblems.add("L" + lineNo + ": " + e.getClass().getSimpleName() + " " + e.getMessage());
                }
            }
        }
    }

    private void index(LearningSample s) {
        byId.put(s.sampleId(), s);
        if (!s.fingerprint().isBlank()) {
            byFingerprint.put(s.fingerprint(), s.sampleId());
        }
        if (!s.weakFingerprint().isBlank()) {
            byWeak.computeIfAbsent(s.weakFingerprint(), k -> new ArrayList<>()).add(s.sampleId());
        }
    }

    /**
     * 准入一次上报并落盘。
     *
     * <p>新建时 sampleId 由强指纹决定（{@code s-<短哈希>}）：同一时间桶内的同一次事故，
     * 无论在内存里还是在重启后的进程里算，得到的都是同一个 id —— id 可复算，才能被日志引用。
     * 极小概率的哈希碰撞会加 {@code -2} 后缀，见 {@link #allocateId}。
     */
    public synchronized SampleAdmission.Decision admit(SampleAdmission.Candidate c, long nowMillis) throws IOException {
        long bucket = SampleFingerprint.bucketOf(c.occurredAt(), bucketMillis);
        String fp = SampleFingerprint.of(c.companionId(), c.triggerKind(),
                c.goalId(), c.planVersion(), c.causeSignature(),
                c.worldId(), c.dimension(), bucket);
        String weak = SampleFingerprint.weakOf(c.companionId(), c.triggerKind(),
                c.goalId(), c.planVersion(), c.worldId(), c.dimension(), bucket);

        String fpId = byFingerprint.get(fp);
        LearningSample same = fpId == null ? null : byId.get(fpId);
        LearningSample sameWeak = null;
        if (same == null) {
            List<String> weakIds = byWeak.get(weak);
            if (weakIds != null && !weakIds.isEmpty()) {
                sameWeak = byId.get(weakIds.get(weakIds.size() - 1));
            }
        }

        SampleAdmission.Decision d = SampleAdmission.decide(
                c, same, sameWeak, bucketMillis, nowMillis, allocateId(fp));

        index(d.sample());
        persist();
        // 事件先落盘再返回：160 §4 要求事件能被回放，只留在内存里就回放不了。
        // 事件文件是 append-only，坏一条不影响已写的；样本文件才是整文件重写。
        recordEvents(d.events());
        return d;
    }

    /**
     * TTL 归档（158 §8：<b>只归档，不删除证据</b>）。
     *
     * <p>只动「还能被复盘」的样本（OPEN / READY_FOR_REVIEW / DEFERRED）；
     * 已 RESOLVED / INVALID / DUPLICATE 的不碰 —— 那些已经有结论，不该因为超期被改写。
     *
     * <p>停手条件写死在这里：<b>occurrence 一条都不删</b>。归档的是"待办"，不是"事实"。
     * 真删了，之后任何"这条死亡到底发生过几次"都回答不了。
     *
     * @param ttlMillis 超过多久算过期；{@code <= 0} 抛错（静默不归档会让待办无限堆积）
     * @return 本次归档的条数
     */
    public synchronized int archiveExpired(long nowMillis, long ttlMillis) throws IOException {
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis 必须为正数，收到 " + ttlMillis);
        }
        int archived = 0;
        for (Map.Entry<String, LearningSample> e : byId.entrySet()) {
            LearningSample s = e.getValue();
            if (!s.state().reviewable()) {
                continue;
            }
            long at = s.latestEvidenceAt() > 0 ? s.latestEvidenceAt() : s.createdAt();
            if (at <= 0 || nowMillis - at < ttlMillis) {
                continue;
            }
            e.setValue(s.withState(SampleState.ARCHIVED));
            archived++;
        }
        if (archived > 0) {
            persist();
        }
        return archived;
    }

    /** 强指纹 → 稳定 id；撞了就加序号（保持可复算：同一个指纹永远得到同一个 id）。 */
    private String allocateId(String fingerprint) {
        String baseId = "s-" + SampleFingerprint.shortHash(fingerprint);
        String id = baseId;
        int n = 2;
        while (byId.containsKey(id) && !fingerprint.equals(byId.get(id).fingerprint())) {
            id = baseId + "-" + n;
            n++;
        }
        return id;
    }

    public synchronized Optional<LearningSample> get(String sampleId) {
        return Optional.ofNullable(sampleId == null ? null : byId.get(sampleId));
    }

    public synchronized List<LearningSample> samples() {
        return List.copyOf(byId.values());
    }

    public synchronized int sampleCount() {
        return byId.size();
    }

    public synchronized int occurrenceCount() {
        int n = 0;
        for (LearningSample s : byId.values()) {
            n += s.occurrenceCount();
        }
        return n;
    }

    private void recordEvents(List<SampleEvent> events) throws IOException {
        if (events.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (SampleEvent e : events) {
            sb.append(e.toJson().toString()).append('\n');
            recentEvents.addLast(e);
            while (recentEvents.size() > MAX_RECENT_EVENTS) {
                recentEvents.removeFirst();
            }
        }
        Path parent = eventsFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(eventsFile, sb.toString(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    /** 最近的事件（有界，防长跑无界增长）。用于状态面板与离线对账。 */
    public synchronized List<SampleEvent> recentEvents(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<SampleEvent> all = new ArrayList<>(recentEvents);
        int from = Math.max(0, all.size() - limit);
        return List.copyOf(all.subList(from, all.size()));
    }

    public Path file() {
        return file;
    }

    public Path eventsFile() {
        return eventsFile;
    }

    public long bucketMillis() {
        return bucketMillis;
    }

    /** 加载时被跳过的坏行数（诚实计数：不许假装文件是干净的）。 */
    public synchronized int skippedLines() {
        return skippedLines;
    }

    public synchronized List<String> problems() {
        return List.copyOf(loadProblems);
    }

    private void persist() throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        StringBuilder sb = new StringBuilder();
        for (LearningSample s : byId.values()) {
            sb.append(toJson(s).toString()).append('\n');
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ---------------------------------------------------------------- JSON

    static JsonObject toJson(LearningSample s) {
        JsonObject o = new JsonObject();
        o.addProperty("v", FORMAT_VERSION);
        o.addProperty("sampleId", s.sampleId());
        o.addProperty("runId", s.runId());
        o.addProperty("companionId", s.companionId());
        o.addProperty("goalId", s.goalId());
        o.addProperty("planVersion", s.planVersion());
        o.addProperty("triggerKind", s.triggerKind());
        o.addProperty("triggerDetail", s.triggerDetail());
        o.addProperty("causeSignature", s.causeSignature());
        o.addProperty("worldId", s.worldId());
        o.addProperty("dimension", s.dimension());
        o.addProperty("bucketIndex", s.bucketIndex());
        o.addProperty("fingerprint", s.fingerprint());
        o.addProperty("weakFingerprint", s.weakFingerprint());
        o.addProperty("state", s.state().name());
        o.addProperty("source", s.source());
        o.addProperty("createdAt", s.createdAt());
        o.addProperty("earliestEvidenceAt", s.earliestEvidenceAt());
        o.addProperty("latestEvidenceAt", s.latestEvidenceAt());
        o.addProperty("possibleDuplicate", s.possibleDuplicate());
        o.addProperty("possibleDuplicateOf", s.possibleDuplicateOf());
        JsonArray related = new JsonArray();
        for (String r : s.relatedSampleIds()) {
            related.add(r);
        }
        o.add("relatedSampleIds", related);
        JsonArray occs = new JsonArray();
        for (SampleOccurrence oc : s.occurrences()) {
            JsonObject jo = new JsonObject();
            jo.addProperty("evidenceRef", oc.evidenceRef());
            jo.addProperty("occurredAt", oc.occurredAt());
            jo.addProperty("source", oc.source());
            jo.addProperty("note", oc.note());
            occs.add(jo);
        }
        o.add("occurrences", occs);
        return o;
    }

    static LearningSample fromJson(JsonObject o) {
        List<String> related = new ArrayList<>();
        JsonElement rel = o.get("relatedSampleIds");
        if (rel != null && rel.isJsonArray()) {
            for (JsonElement e : rel.getAsJsonArray()) {
                if (!e.isJsonNull()) {
                    related.add(e.getAsString());
                }
            }
        }
        List<SampleOccurrence> occs = new ArrayList<>();
        JsonElement oe = o.get("occurrences");
        if (oe != null && oe.isJsonArray()) {
            for (JsonElement e : oe.getAsJsonArray()) {
                JsonObject jo = e.getAsJsonObject();
                occs.add(new SampleOccurrence(
                        str(jo, "evidenceRef"),
                        lng(jo, "occurredAt", 0L),
                        str(jo, "source"),
                        str(jo, "note")));
            }
        }
        return new LearningSample(
                str(o, "sampleId"),
                str(o, "runId"),
                str(o, "companionId"),
                str(o, "goalId"),
                str(o, "planVersion"),
                str(o, "triggerKind"),
                str(o, "triggerDetail"),
                str(o, "causeSignature"),
                str(o, "worldId"),
                str(o, "dimension"),
                lng(o, "bucketIndex", 0L),
                str(o, "fingerprint"),
                str(o, "weakFingerprint"),
                parseState(str(o, "state")),
                str(o, "source"),
                lng(o, "createdAt", 0L),
                lng(o, "earliestEvidenceAt", 0L),
                lng(o, "latestEvidenceAt", 0L),
                bool(o, "possibleDuplicate"),
                str(o, "possibleDuplicateOf"),
                related,
                occs);
    }

    private static SampleState parseState(String raw) {
        if (raw == null || raw.isBlank()) {
            return SampleState.OPEN;
        }
        try {
            return SampleState.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            // 不认识的状态原样暴露为 OPEN？不行 —— 那会把「已归档」读成「待复盘」。
            // 直接抛，由 load() 记进 problems，让坏行显形。
            throw new IllegalArgumentException("未知样本状态: " + raw);
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static long lng(JsonObject o, String key, long def) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? def : e.getAsLong();
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() && e.getAsBoolean();
    }
}
