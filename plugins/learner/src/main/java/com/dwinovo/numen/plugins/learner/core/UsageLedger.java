package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 产物<b>使用账本</b>：把「呈现 → 采用 → 执行 → 结果」串起来（S3，2026-10-05）。
 *
 * <p><b>为什么需要</b>：第二批把「学习者产出」接通之后，缺了另一半 ——
 * 产出被下游拿去用了吗？用成了吗？<b>不接这一段，学习就永远只在自我循环里打转</b>：
 * 复审产出 → 落箱 → 被采纳 → 然后<b>没有任何证据回到下一轮复审</b>。
 *
 * <h3>★ 本类最重要的两条规则（都是踩过才知道的）</h3>
 * <ol>
 *   <li><b>「被呈现后成功」只算候选关联，不许自动加有效性证据</b>。
 *       <p>时间上的先后不是因果。若「呈现过 + 后来成功了」就自动算有效，
 *       那账本会源源不断产出**自我印证**的垃圾证据 —— 而它看起来是数据，不是猜测。
 *       所以有效性证据只能由人<b>显式</b> {@link #claimValidity} 写，且必须给理由。
 *   <li><b>取消之后迟到的成功不许把结论翻回成功</b>。
 *       <p>这条是照着 ACX 已知缺陷 AC-B19 写的（「取消后迟到的成功回执会把这次运行翻回
 *       SUCCESS」）。同一个病会在账本里再犯一次，所以这里<b>先钉死</b>：
 *       迟到事件照样记录（不丢证据），但<b>结论不翻转</b>。
 * </ol>
 *
 * <p>落盘：{@code <configDir>/usage-ledger.jsonl}（append-only，只追加不改写）。
 */
public final class UsageLedger {

    /** 产物在链路上的阶段，<b>只能前进</b>。 */
    public enum Phase {
        /** 摆到同伴面前了（被召回/被注入提示）。 */
        PRESENTED(0),
        /** 被明确采用（人点了采纳 / 调了 approve）。 */
        ADOPTED(1),
        /** 真去执行了（跑了一个 AC / 用了一次携带器）。 */
        EXECUTED(2),
        /** 有了结果。 */
        RESULT(3);

        private final int rank;

        Phase(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }

        static Phase of(String s) {
            if (s == null) {
                return null;
            }
            for (Phase p : values()) {
                if (p.name().equalsIgnoreCase(s.trim())) {
                    return p;
                }
            }
            return null;
        }
    }

    public enum Outcome {
        SUCCESS, FAIL, CANCELED, UNKNOWN
    }

    /** 一条账。 */
    public record Entry(String artifactId, String kind, String name, Phase phase, Outcome outcome,
                         String detail, String source, String at, boolean validityClaimed,
                         String companionId) {
    }

    /** 阶段只能前进：早于已记录阶段的 arriving 事件算「迟到」。 */
    public static final class LateArrival extends RuntimeException {
        public final Phase seen;
        public final Phase arriving;

        LateArrival(Phase seen, Phase arriving) {
            super("迟到事件：已记到 " + seen + "，又收到更早的 " + arriving
                    + "（阶段只前进，不许回退）");
            this.seen = seen;
            this.arriving = arriving;
        }
    }

    private final Path file;

    public UsageLedger(Path configDir) {
        this.file = configDir.resolve("usage-ledger.jsonl");
    }

    public Path file() {
        return file;
    }

    /**
     * 追加一条。
     *
     * <p><b>⚠️ 慎用无同伴的重载</b>：那会写出 {@code companionId=""} 的记录，
     * 之后按同伴过滤时它<b>对任何同伴都不可见</b> —— 症状是「明明记了，AI 却说没记过」。
     * 生产路径请用带 {@code companionId} 的重载（{@link LearnerUsageTool} 已接上）。
     *
     * @throws LateArrival       阶段回退（迟到事件）
     * @throws IllegalArgumentException 阶段/结果自相矛盾（如 RESULT 却带 UNKNOWN 的成功）
     */
    public synchronized Entry append(String artifactId, String kind, String name,
                                     Phase phase, Outcome outcome, String detail, String source) {
        return append(artifactId, kind, name, phase, outcome, detail, source, "");
    }

    /**
     * 追加一条，并记下<b>哪个同伴</b>的（隔离靠它，见 {@link #promptBlock(UsageLedger, java.util.UUID)}）。
     */
    public synchronized Entry append(String artifactId, String kind, String name,
                                     Phase phase, Outcome outcome, String detail, String source,
                                     String companionId) {
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("artifact_id 不能为空：账本条目必须能追到具体产物");
        }
        if (phase == null) {
            throw new IllegalArgumentException("phase 不能为空");
        }
        Outcome o = outcome == null ? Outcome.UNKNOWN : outcome;
        if (phase != Phase.RESULT && o != Outcome.UNKNOWN) {
            throw new IllegalArgumentException("只有 RESULT 阶段才带结果，phase=" + phase
                    + " 却带了 outcome=" + o);
        }
        Entry latest = latestOf(artifactId);
        if (latest != null && phase.rank() < latest.phase().rank()) {
            throw new LateArrival(latest.phase(), phase);
        }
        // ★ 规则 2：取消后迟到的成功 —— 记录，但**不改结论**
        Outcome recorded = o;
        boolean lateAfterCancel = false;
        if (latest != null && latest.outcome() == Outcome.CANCELED && o == Outcome.SUCCESS) {
            recorded = Outcome.CANCELED;
            lateAfterCancel = true;
        }
        Entry e = new Entry(artifactId, nz(kind), nz(name), phase, recorded,
                nz(detail), nz(source), java.time.Instant.now().toString(), false, nz(companionId));
        writeLine(e, lateAfterCancel
                ? "迟到成功：已按取消结论记账，不翻转（AC-B19 同款病的预防）"
                : "");
        return e;
    }

    /**
     * ★ 唯一的有效性证据入口：<b>必须显式调用 + 必须给理由</b>。
     *
     * <p>不存在「自动判定有效性」的路径 —— 这是本类的核心约定。
     */
    public synchronized void claimValidity(String artifactId, String reason, String byWho) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("声明有效性必须给理由：没理由的有效性证据无法追责");
        }
        Entry latest = latestOf(artifactId);
        if (latest == null) {
            throw new IllegalArgumentException("账本里没有这个产物: " + artifactId);
        }
        if (latest.outcome() != Outcome.SUCCESS) {
            throw new IllegalStateException("只有 SUCCESS 的产物才能声明有效性，当前是 "
                    + latest.outcome() + "（不许拿失败/取消/未知当有效证据）");
        }
        Entry marked = new Entry(latest.artifactId(), latest.kind(), latest.name(), latest.phase(),
                latest.outcome(), latest.detail(), latest.source(), java.time.Instant.now().toString(), true,
                latest.companionId());
        writeLine(marked, "显式声明有效性：" + reason.trim() + "（by " + nz(byWho) + "）");
    }

    /** 某个产物最新的一条；没有则 null。 */
    public synchronized Entry latestOf(String artifactId) {
        Entry best = null;
        for (Entry e : all()) {
            if (!e.artifactId().equals(artifactId)) {
                continue;
            }
            if (best == null || e.phase().rank() >= best.phase().rank()) {
                best = e;
            }
        }
        return best;
    }

    /** 全账（按写入顺序）。 */
    public synchronized List<Entry> all() {
        List<Entry> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.isEmpty()) {
                    continue;
                }
                JsonObject o;
                try {
                    o = JsonParser.parseString(s).getAsJsonObject();
                } catch (RuntimeException ex) {
                    // 坏行跳过但**不删**（append-only 账本，坏了要留给人看）
                    continue;
                }
                Phase p = Phase.of(str(o, "phase"));
                if (p == null) {
                    continue;
                }
                out.add(new Entry(str(o, "artifact_id"), str(o, "kind"), str(o, "name"), p,
                        outcomeOf(str(o, "outcome")), str(o, "detail"), str(o, "source"),
                        str(o, "at"), o.has("validity_claim") && o.get("validity_claim").getAsBoolean(),
                        str(o, "companion_id")));
            }
        } catch (IOException e) {
            return out;
        }
        return out;
    }

    /** 每个产物的最终结论（最新阶段的那条）。 */
    public synchronized Map<String, Entry> conclusions() {
        Map<String, Entry> out = new LinkedHashMap<>();
        for (Entry e : all()) {
            Entry cur = out.get(e.artifactId());
            if (cur == null || e.phase().rank() >= cur.phase().rank()) {
                out.put(e.artifactId(), e);
            }
        }
        return out;
    }

    /**
     * ★ 候选关联清单：呈现过且后来成功了，但<b>还没人声明有效性</b>。
     *
     * <p>这是给人/AI 去<b>审</b>的队列，<b>不是</b>证据本身。
     */
    public synchronized List<Entry> associationCandidates() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : conclusions().values()) {
            if (e.outcome() == Outcome.SUCCESS && !e.validityClaimed()) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * 摆进复审 prompt 的一段：上轮产物的使用结果。
     *
     * <p>措辞是刻意的：只说「用过、成没成」，<b>不说「所以有效」</b>。
     */
    public String promptBlock() {
        return promptBlock(this, null);
    }

    /**
     * 摆进复审 prompt 的一段：<b>本同伴</b>产物的使用结果。
     *
     * <p>措辞是刻意的：只说「用过、成没成」，<b>不说「所以有效」</b>。
     *
     * @param companionId 只看这个同伴的；<b>null ⇒ 一条都不给</b>
     *                    （不退化成「把全部都喂出去」—— 账本是共享文件，
     *                    那样等于把别的同伴的成败当成自己的经验）
     */
    public static String promptBlock(UsageLedger ledger, java.util.UUID companionId) {
        if (ledger == null || companionId == null) {
            return "（没有本同伴的使用记录可参考。）\n";
        }
        String want = companionId.toString();
        Map<String, Entry> cs = new LinkedHashMap<>();
        for (Entry e : ledger.conclusions().values()) {
            if (want.equals(e.companionId())) {
                cs.put(e.artifactId(), e);
            }
        }
        if (cs.isEmpty()) {
            return "（本同伴还没有产物使用记录。）\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("== 你自己产物的使用情况（事实，不是结论）==\n");
        int n = 0;
        for (Entry e : cs.values()) {
            if (n++ >= 8) {
                sb.append("…（还有 ").append(cs.size() - 8).append(" 条）\n");
                break;
            }
            sb.append("  - [").append(e.kind()).append(' ').append(e.name()).append("] ");
            sb.append("阶段=").append(e.phase()).append(" 结果=").append(e.outcome());
            if (e.validityClaimed()) {
                sb.append("（已显式声明有效性）");
            } else if (e.outcome() == Outcome.SUCCESS) {
                sb.append("（★只是候选关联，**不等于有效**；要算证据得有人显式声明）");
            }
            if (!e.detail().isBlank()) {
                sb.append(" 说明=").append(clip(e.detail()));
            }
            sb.append('\n');
        }
        sb.append("★ 被用过且成功**本身不构成有效性证据** —— 时间上的先后不是因果。\n");
        return sb.toString();
    }

    private void writeLine(Entry e, String extra) {
        JsonObject o = new JsonObject();
        o.addProperty("artifact_id", e.artifactId());
        o.addProperty("kind", e.kind());
        o.addProperty("name", e.name());
        o.addProperty("phase", e.phase().name());
        o.addProperty("outcome", e.outcome().name());
        o.addProperty("detail", e.detail());
        o.addProperty("source", e.source());
        o.addProperty("at", e.at());
        o.addProperty("validity_claim", e.validityClaimed());
        // ★ 同伴 id 必须落盘：隔离全靠它（共享账本文件里混着所有同伴的记录）
        o.addProperty("companion_id", nz(e.companionId()));
        if (extra != null && !extra.isBlank()) {
            o.addProperty("note", extra);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, o.toString() + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            // 变量名不能与上面的 Entry e 重复（catch 参数遮蔽方法参数 = 编译错）
            throw new IllegalStateException("写使用账本失败: " + file + " -> " + ex, ex);
        }
    }

    private static Outcome outcomeOf(String s) {
        for (Outcome o : Outcome.values()) {
            if (o.name().equalsIgnoreCase(s == null ? "" : s.trim())) {
                return o;
            }
        }
        return Outcome.UNKNOWN;
    }

    /**
 * 按 <b>memoId</b> 反查该判定产出物的使用结果（B1 的另一半）。
 *
 * <p><b>为什么这是关键的一步</b>：两侧键空间本来不同源
 * （事实侧 {@code stageKey} vs 使用侧 {@code artifact_id}），
 * 而 {@code ArtifactOutbox} 的 {@code artifact_id = kind + 同伴 + memoId + 摘要}
 * <b>本身含 memoId</b> ⇒ 从 artifact 反推 memo 就能把使用结果挂回具体判定，
 * 于是「判定 → 产物 → 执行结果」有了共同键。
 *
 * <p><b>只给事实</b>：每项只含 kind/name/phase/outcome/artifact_id/run 计数，
 * <b>不含有效性判断</b>。
 *
 * @param ledger   账本
 * @param memoId   要查的判定对应的 memoId
 * @param kinds    只查这几类产物
 * @return 没查到时返回空列表（<b>调用方要区分「没产物」与「查不到账本」</b>）
 */
    public static List<Map<String, Object>> usageByMemo(UsageLedger ledger, String memoId,
                                                        java.util.Set<ArtifactOutbox.Kind> kinds) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (ledger == null || memoId == null || memoId.isBlank() || kinds == null || kinds.isEmpty()) {
            return out;
        }
        // artifact_id 形如 <kind>-<companion>-<memoId>-<hash>：
        // memoId 前面隔着同伴 UUID，所以判据是「以 kind 开头」+「含 -<memoId>- 片段」。
        // 用带横线的片段而不是裸 memoId ⇒ m-1 不会命中 m-10 的产物（裸包含会串）。
        for (Map.Entry<String, Entry> e : ledger.conclusions().entrySet()) {
            String artifactId = e.getKey();
            String needle = "-" + memoId + "-";
            for (ArtifactOutbox.Kind k : kinds) {
                if (!artifactId.startsWith(k.wire() + "-") || !artifactId.contains(needle)) {
                    continue;
                }
                Entry v = e.getValue();
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("artifact_id", artifactId);
                one.put("kind", k.wire());
                one.put("name", v.name());
                one.put("phase", v.phase().name());
                one.put("outcome", v.outcome().name());
                one.put("validity_claimed", v.validityClaimed());
                if (v.companionId() != null && !v.companionId().isBlank()) {
                    one.put("companion_id", v.companionId());
                }
                out.add(one);
                break;
            }
        }
        return out;
    }

    /** 某个产物在账本里出现过几个不同来源的执行（粗略的「跑过几次」）。 */
    public int runCountOf(String artifactId) {
        int n = 0;
        for (Entry e : all()) {
            if (artifactId.equals(e.artifactId()) && e.source() != null
                    && e.source().startsWith(AcxExecutionReflector.SOURCE_PREFIX)) {
                n++;
            }
        }
        return n;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String clip(String s) {
        String t = nz(s).trim().replaceAll("\\s+", " ");
        return t.length() <= 120 ? t : t.substring(0, 120) + "…";
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
    }

    /** 给工具回执用的小地图。 */
    public Map<String, Object> toMap(List<Entry> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> rs = new ArrayList<>();
        for (Entry e : rows) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("artifact_id", e.artifactId());
            one.put("kind", e.kind());
            one.put("name", e.name());
            one.put("phase", e.phase().name());
            one.put("outcome", e.outcome().name());
            one.put("validity_claimed", e.validityClaimed());
            if (!e.detail().isBlank()) {
                one.put("detail", e.detail());
            }
            rs.add(one);
        }
        m.put("rows", rs);
        return m;
    }

    /** 供诊断：账本文件是否可读（读不到要明说，不许假装空账本）。 */
    public boolean readable() {
        return !Files.exists(file) || Files.isReadable(file);
    }

    /** 空账本判定（读不到时返回 true 会伪装成「没记录」，所以单独给）。 */
    public boolean exists() {
        return Files.isRegularFile(file);
    }
}