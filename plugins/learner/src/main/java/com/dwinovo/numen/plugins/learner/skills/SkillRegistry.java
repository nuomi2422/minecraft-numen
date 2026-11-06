package com.dwinovo.numen.plugins.learner.skills;

import com.dwinovo.numen.plugins.learner.contract.ArtifactDraft;
import com.dwinovo.numen.plugins.learner.contract.ArtifactKind;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 统一 Skill 目录（158 §3.3 / §6，159 G）。
 *
 * <h2>三条分支而不是"总是新建"</h2>
 * 158 §6：Skill 指纹命中已有条目时进入「复用、修订或冲突」，<b>不静默创建同义副本</b>。
 * 三种结局必须分开，因为它们的下游完全不同：
 * <ul>
 *   <li>{@link Outcome#REUSE} —— 已有相同条目，什么也不做（但记一次命中，供统计）；</li>
 *   <li>{@link Outcome#REVISE} —— 同一件事有了新内容，版本 +1，旧版本进 supersedes；</li>
 *   <li>{@link Outcome#CONFLICT} —— 同名但条件不同，<b>不能自动合并</b>，交给人判断。</li>
 * </ul>
 *
 * <h2>为什么"同名不同指纹"判冲突而不是新建</h2>
 * 两条同名 Skill（比如两个「夜间别出门」）对检索是灾难：
 * 执行 AI 只会看到一个名字，而它的触发条件可能互相矛盾。
 * 自动新建会让目录里出现互相矛盾的同名条目，且没有任何地方会报警。
 *
 * <h2>双向回链</h2>
 * 条目 → 样本：`foundingSamples`；样本 → 条目：{@link #skillsOfSample}。
 * 单向链接等于查不到"这条经验是从哪来的"，那正是 158 §3.2 要消灭的断链。
 *
 * <p>纯 JVM、无 IO，可离线单测。
 */
public final class SkillRegistry {

    /** 登记结局。 */
    public enum Outcome {
        /** 新建条目。 */
        REGISTERED,
        /** 命中同指纹同内容：复用。 */
        REUSE,
        /** 命中同指纹不同内容：修订（旧版本进 supersedes）。 */
        REVISE,
        /** 同名不同指纹：冲突，需人工定夺。 */
        CONFLICT
    }

    /**
     * 一次登记结果。
     *
     * @param entry   处理后的条目（CONFLICT 时是<b>既有的</b>那条，新条目不落库）
     * @param reason  给人看的判定理由（可查性：合并/冲突必须能解释为什么）
     */
    public record Decision(Outcome outcome, SkillEntry entry, String reason) {
        public Decision {
            reason = reason == null ? "" : reason;
        }

        public boolean stored() {
            return outcome == Outcome.REGISTERED || outcome == Outcome.REVISE;
        }
    }

    private final Map<String, SkillEntry> byId = new LinkedHashMap<>();
    private final Map<String, String> byFingerprint = new LinkedHashMap<>();
    private final Map<String, String> byName = new LinkedHashMap<>();
    private final Map<String, Set<String>> bySample = new LinkedHashMap<>();

    /** 落盘文件；{@code null} 表示纯内存（单测/预览用）。 */
    private final Path file;
    private int skippedLines;

    /** 纯内存目录。 */
    public SkillRegistry() {
        this(null);
    }

    private SkillRegistry(Path file) {
        this.file = file;
    }

    /**
     * 打开（或新建）目录。
     *
     * <p>重建索引靠条目自己的 `fingerprint()` 与 `foundingSamples` ——
     * 不靠"记一张索引表"，否则索引与条目一旦分叉就再也没法重建。
     *
     * <p>坏行跳过并计数（不炸整份目录）：一条坏记录不该让整份历史读不出来。
     */
    public static SkillRegistry open(Path file) throws IOException {
        SkillRegistry reg = new SkillRegistry(file);
        if (file != null && Files.exists(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    reg.index(fromJson(JsonParser.parseString(line).getAsJsonObject()));
                } catch (RuntimeException e) {
                    reg.skippedLines++;
                }
            }
        }
        return reg;
    }

    /** 加载时跳过的坏行数（诚实计数）。 */
    public int skippedLines() {
        return skippedLines;
    }

    /** 测试隔离 / 空库。 */
    public void clear() {
        byId.clear();
        byFingerprint.clear();
        byName.clear();
        bySample.clear();
    }

    /**
     * 登记一条产物为 Skill 条目。
     *
     * @param bodyRef 正文引用（正文不入目录，目录只给标题层）
     */
    public Decision register(ArtifactDraft artifact, String skillId, String bodyRef) {
        return register(artifact, skillId, bodyRef, false);
    }

    /**
     * 登记一条产物为 Skill 条目，并指定它是否只准进试用态。
     *
     * @param trial 权限矩阵判成 `TRIAL_ONLY` 时传 true —— 状态记 {@link SkillStatus#TRIAL}，
     *              这样 `retrievable()` 与检索侧才可能真的区别对待（而不是日志里多一行）
     */
    public Decision register(ArtifactDraft artifact, String skillId, String bodyRef, boolean trial) {
        if (artifact == null) {
            return new Decision(Outcome.CONFLICT, null, "no_artifact");
        }
        SkillEntry candidate = new SkillEntry(
                skillId, 1, artifact.kind(), artifact.name(), artifact.purpose(),
                artifact.audience(), artifact.triggerConditions(), artifact.excludeConditions(),
                artifact.preconditions(), artifact.risk(), "",
                trial ? SkillStatus.TRIAL : SkillStatus.DRAFT, bodyRef,
                List.of(), artifact.sampleRefs(), List.of(), List.of());

        String fp = candidate.fingerprint();
        String existingId = byFingerprint.get(fp);
        if (existingId != null) {
            SkillEntry existing = byId.get(existingId);
            if (existing.bodyRef().equals(candidate.bodyRef())) {
                // 同指纹同正文：复用，不新增版本（否则每次复盘都会涨版本号）。
                return new Decision(Outcome.REUSE, existing,
                        "同指纹同正文，复用 " + existing.skillId() + "@v" + existing.version());
            }
            SkillEntry revised = existing.revise(candidate.bodyRef(), candidate.foundingSamples());
            store(revised);
            return new Decision(Outcome.REVISE, revised,
                    "同指纹不同正文，修订为 v" + revised.version()
                            + "（旧版 " + existing.skillId() + "@v" + existing.version() + " 进 supersedes）");
        }

        String sameNameId = byName.get(norm(candidate.name()));
        if (sameNameId != null) {
            // 同名不同指纹：条件不一样的两条同名条目会互相矛盾，且检索时只看得到名字。
            return new Decision(Outcome.CONFLICT, byId.get(sameNameId),
                    "同名条目已存在但指纹不同（" + byId.get(sameNameId).skillId()
                            + "），不能自动合并，需人工定夺");
        }

        store(candidate);
        return new Decision(Outcome.REGISTERED, candidate, "新条目 " + candidate.skillId() + "@v1");
    }

    /**
     * 外部/主人侧撤回（2026-11-06）：**不改检索实现**，只把状态写成 REVOKED 并追加版本；
     * retrievable() 已过滤 REVOKED。历史靠 supersedes 串起来，不删行。
     *
     * <p>为什么不给同伴工具：技能一撤就少一条经验，必须由外部/主人侧发起，避免 AI 自撤。
     */
    public Decision retire(String skillId, String reason) {
        SkillEntry current = skillId == null ? null : byId.get(skillId);
        if (current == null) {
            return new Decision(Outcome.CONFLICT, null, "unknown skillId: " + skillId);
        }
        if (current.status() == SkillStatus.REVOKED) {
            return new Decision(Outcome.REUSE, current, "already revoked: " + skillId);
        }
        java.util.List<String> nextSupersedes = new java.util.ArrayList<>(current.supersedes());
        nextSupersedes.add(current.skillId() + "@v" + current.version());
        SkillEntry revoked = new SkillEntry(current.skillId(), current.version() + 1, current.kind(),
                current.name(), current.purpose(), current.audience(), current.triggerConditions(),
                current.excludeConditions(), current.preconditions(), current.risk(),
                current.evidenceLevel(), SkillStatus.REVOKED, current.bodyRef(),
                current.validationSignals(), current.foundingSamples(), nextSupersedes, current.conflicts());
        store(revoked);
        return new Decision(Outcome.REVISE, revoked, "retired: " + (reason == null ? "" : reason));
    }

    /** 近重复提示（不拦截）：先按共享触发词收敛，再算 trigram Jaccard。 */
    public java.util.List<NearDuplicate> nearDuplicates(ArtifactDraft candidate, double threshold) {
        java.util.List<NearDuplicate> out = new java.util.ArrayList<>();
        if (candidate == null) return out;
        java.util.Set<String> candTrigrams = trigrams(candidate.name() + " " + candidate.purpose() + " " + candidate.triggerConditions());
        java.util.Set<String> candTokens = tokens(candidate.triggerConditions());
        for (SkillEntry e : byId.values()) {
            java.util.Set<String> tokens = tokens(e.triggerConditions());
            boolean shared = false;
            for (String t : candTokens) if (tokens.contains(t)) { shared = true; break; }
            if (!shared) continue;
            double score = jaccard(candTrigrams, trigrams(e.name() + " " + e.purpose() + " " + e.triggerConditions()));
            if (score >= threshold) out.add(new NearDuplicate(e.skillId(), score));
        }
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return out;
    }

    public record NearDuplicate(String existingId, double score) {}

    public static java.util.Set<String> tokens(java.util.List<String> conditions) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (conditions == null) return out;
        for (String c : conditions) {
            if (c == null) continue;
            for (String t : c.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}_]+")) if (!t.isBlank()) out.add(t);
        }
        return out;
    }

    static java.util.Set<String> trigrams(String text) {
        String norm = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (int i = 0; i + 3 <= norm.length(); i++) out.add(norm.substring(i, i + 3));
        return out;
    }

    static double jaccard(java.util.Set<String> a, java.util.Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 0.0;
        int inter = 0;
        for (String s : a) if (b.contains(s)) inter++;
        int union = a.size() + b.size() - inter;
        return union == 0 ? 0.0 : (double) inter / union;
    }

    private void store(SkillEntry e) {
        index(e);
        if (file != null) {
            try {
                persist();
            } catch (IOException ignored) {
                // 落盘失败不许炸复盘：目录在内存里仍然可用，下次写入会再试。
            }
        }
    }

    private void persist() throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        StringBuilder sb = new StringBuilder();
        for (SkillEntry e : byId.values()) {
            sb.append(toJson(e).toString()).append('\n');
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void index(SkillEntry e) {
        byId.put(e.skillId(), e);
        byFingerprint.put(e.fingerprint(), e.skillId());
        byName.put(norm(e.name()), e.skillId());
        for (String s : e.foundingSamples()) {
            bySample.computeIfAbsent(s, k -> new LinkedHashSet<>()).add(e.skillId());
        }
    }

    public Optional<SkillEntry> get(String skillId) {
        return Optional.ofNullable(skillId == null ? null : byId.get(skillId));
    }

    public int size() {
        return byId.size();
    }

    /** 全部条目（只读视图，给面板统计用；不暴露内部 map）。 */
    public List<SkillEntry> all() {
        return List.copyOf(byId.values());
    }

    /** 样本 → 它派生出的 Skill（双向回链的另一半）。 */
    public List<String> skillsOfSample(String sampleId) {
        Set<String> ids = bySample.get(sampleId);
        return ids == null ? List.of() : List.copyOf(ids);
    }

    /** 只列可检索的条目（DRAFT/SUSPENDED/REVOKED 不参与检索）。 */
    public List<SkillEntry> retrievable() {
        List<SkillEntry> out = new ArrayList<>();
        for (SkillEntry e : byId.values()) {
            if (e.status().retrievable()) {
                out.add(e);
            }
        }
        return out;
    }

    static JsonObject toJson(SkillEntry e) {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("skillId", e.skillId());
        o.addProperty("version", e.version());
        o.addProperty("kind", e.kind().name());
        o.addProperty("name", e.name());
        o.addProperty("purpose", e.purpose());
        o.addProperty("audience", e.audience());
        o.add("triggerConditions", arr(e.triggerConditions()));
        o.add("excludeConditions", arr(e.excludeConditions()));
        o.add("preconditions", arr(e.preconditions()));
        o.addProperty("risk", e.risk());
        o.addProperty("evidenceLevel", e.evidenceLevel());
        o.addProperty("status", e.status().name());
        o.addProperty("bodyRef", e.bodyRef());
        o.add("validationSignals", arr(e.validationSignals()));
        o.add("foundingSamples", arr(e.foundingSamples()));
        o.add("supersedes", arr(e.supersedes()));
        o.add("conflicts", arr(e.conflicts()));
        return o;
    }

    static SkillEntry fromJson(JsonObject o) {
        return new SkillEntry(
                str(o, "skillId"),
                (int) num(o, "version", 1),
                ArtifactKind.valueOf(str(o, "kind")),
                str(o, "name"),
                str(o, "purpose"),
                str(o, "audience"),
                list(o, "triggerConditions"),
                list(o, "excludeConditions"),
                list(o, "preconditions"),
                str(o, "risk"),
                str(o, "evidenceLevel"),
                SkillStatus.valueOf(str(o, "status")),
                str(o, "bodyRef"),
                list(o, "validationSignals"),
                list(o, "foundingSamples"),
                list(o, "supersedes"),
                list(o, "conflicts"));
    }

    private static JsonArray arr(List<String> xs) {
        JsonArray a = new JsonArray();
        for (String x : xs) {
            a.add(x);
        }
        return a;
    }

    private static String str(JsonObject o, String k) {
        var e = o.get(k);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static long num(JsonObject o, String k, long def) {
        var e = o.get(k);
        return e == null || e.isJsonNull() ? def : e.getAsLong();
    }

    private static List<String> list(JsonObject o, String k) {
        var e = o.get(k);
        List<String> out = new ArrayList<>();
        if (e != null && e.isJsonArray()) {
            for (var x : e.getAsJsonArray()) {
                if (!x.isJsonNull()) {
                    out.add(x.getAsString());
                }
            }
        }
        return out;
    }

    private static String norm(String raw) {
        if (raw == null) {
            return "";
        }
        String s = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKC);
        return s.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }
}
