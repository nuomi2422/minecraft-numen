package com.dwinovo.numen.experience.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一条经验的完整定义。它是<b>可独立检索、可验证、可复用</b>的单元，不是一句日志。
 *
 * <p>{@code id} 即稳定去重键：由 {@link #stableKey} 派生（type + 规范化 title），
 * 同名经验只会合并不会无限追加。
 *
 * <p>核心字段语义：
 * <ul>
 *   <li>{@code description} — 发生了什么（现象）。</li>
 *   <li>{@code rationale} — 为什么这条值得保留（AI 自反思溯源）。</li>
 *   <li>{@code rootCause} — 根因是什么。</li>
 *   <li>{@code recommendedResponse} — 以后遇到应该怎么做。</li>
 *   <li>{@code triggerStrings/toolNames/tags} — 什么情况下应该再次想起。</li>
 *   <li>{@code maturity/verifiedCount/counterexamples} — 可信度与验证证据。</li>
 * </ul>
 */
public record ExperienceEntry(
        String id,
        ExperienceType type,
        String title,
        String description,
        String rationale,
        String rootCause,
        String recommendedResponse,
        List<String> triggerStrings,
        List<String> toolNames,
        List<String> tags,
        ExperienceMaturity maturity,
        int verifiedCount,
        int priority,
        List<String> counterexamples,
        long createdAt,
        long verifiedAt,
        long lastAccessedAt,
        // ---- E8：验证状态变化（撤回 / 修订 / 降级）----
        // ⚠️ 这五个都是**元数据**，一律不参与 stableKey(type,title) ——
        //   改成熟度、撤回、标注取代关系都不该换掉这条经验的身份。
        //   否则「验证一次」就会变成「新的一条」，历史全断。
        boolean retracted,
        long retractedAt,
        String retractedReason,
        String supersedes,
        int consecutiveFailures
) {

    /** 经验条目 JSONL 的文件格式版本；记录模型变了就 +1。 */
    public static final int FORMAT_VERSION = 1;

    /** 生成稳定去重键：type + 规范化 title。 */
    public static String stableKey(ExperienceType type, String title) {
        String t = title == null ? "" : title.trim().toLowerCase();
        return (type == null ? "UNKNOWN" : type.name()).toLowerCase() + "|" + t;
    }

    /**
     * <p><b>指纹</b>：比 {@link #stableKey} 再收紧一层的「是不是同一条经验」判定。</p>
     *
     * <p>{@link #stableKey} 只做 trim + 小写，所以同一个标题换个空格宽度/全角半角/大小写
     * 就会被当成两条 —— companion 重写同一条经验时确实会这样漂。指纹把这类
     * <b>人读起来是同一个标题</b>的差异吸收掉，只做四件事：</p>
     *
     * <ul>
     *   <li><b>连续空白折叠成单个空格</b>（不删除 —— 见下）</li>
     *   <li>全角 ASCII（{@code U+FF01–U+FF5E}）折成半角</li>
     *   <li>小写</li>
     *   <li>首尾空白裁掉</li>
     * </ul>
     *
     * <p><b>刻意不做</b>的事，理由是它们都会造成假合并（把两条真经验并成一条 = 丢证据，
     * 比多一条更糟）：</p>
     * <ul>
     *   <li><b>不删除内部空白</b>：{@code "1 23"} 与 {@code "12 3"} 都是坐标/参数，
     *       删掉空白后两者都变成 {@code "123"}。</li>
     *   <li><b>不剥首尾标点</b>：{@code "-64"} 与 {@code "64"}、{@code "C#"} 与 {@code "C"}、
     *       {@code "[0,1)"} 与 {@code "(0,1]"} 指的不是一回事，括号和连字符在标题里带语义。</li>
     *   <li><b>不删词、不排序、不同义改写、不截断长度</b>。</li>
     * </ul>
     */
    public static String fingerprint(ExperienceType type, String title) {
        return (type == null ? "UNKNOWN" : type.name()).toLowerCase() + "|" + normalizeForFingerprint(title);
    }

    /** 本条的指纹（便捷方法，供 store 做同一条判定）。 */
    public String fingerprint() {
        return fingerprint(type, title);
    }

    /**
     * title 的指纹归一：连续空白折叠 + 全角折半角 + 小写 + trim。
     *
     * <p>NBSP（{@code U+00A0}）等 {@code Character.isWhitespace()} 不认的空格<b>不</b>折叠 ——
     * 保守优先，漏合并只是多一条，误合并是丢证据。</p>
     *
     * <p>包可见，便于单测直接钉住每一档归一行为。</p>
     */
    static String normalizeForFingerprint(String title) {
        if (title == null || title.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(title.length());
        boolean pendingSpace = false;
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if (Character.isWhitespace(c)) {
                pendingSpace = sb.length() > 0;   // 前导空白直接丢，词中间的留一个
                continue;
            }
            if (c >= '！' && c <= '～') {
                c = (char) (c - 0xFEE0);   // 全角 ASCII(U+FF01..U+FF5E) → 半角
            }
            if (pendingSpace) {
                sb.append(' ');
                pendingSpace = false;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** 复制一条并替换证据字段（evidence 更新专用，保留其它内容）。 */
    public ExperienceEntry withEvidence(ExperienceMaturity newMaturity, int newVerifiedCount,
                                        long newVerifiedAt, List<String> newCounterexamples) {
        return builder().from(this)
                .maturity(newMaturity)
                .verifiedCount(newVerifiedCount)
                .verifiedAt(newVerifiedAt)
                .counterexamples(newCounterexamples)
                .lastAccessedAt(System.currentTimeMillis())
                .build();
    }

    // ============================================================
    // E8：验证状态变化 —— 撤回 / 修订 / 降级
    // ============================================================

    /** 这条经验是否已被撤回（撤回是**标记**，不是删除 —— 证据要留着可审计）。 */
    public boolean isRetracted() {
        return retracted;
    }

    /** 这条经验是否已被**更新的条目取代**（由 {@link #supersedes()} 反查索引得出）。 */
    public boolean isSupersededBy(List<ExperienceEntry> allEntries) {
        if (allEntries == null || supersedes == null || supersedes.isBlank()) {
            return false;
        }
        for (ExperienceEntry e : allEntries) {
            if (e != null && supersedes.equals(e.supersedes())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 「这条经验现在还能不能当可信的用」的**如实读数**。
     *
     * <p>⚠️ 调用方<b>必须</b>把这个当门禁用，不能只看 {@link #maturity()}：
     * 成熟度只会往上走（见 {@code ExperienceStore.maturityFor}），所以一条被证伪过、
     * 甚至已被撤回的 {@code GENERALIZED} 经验，光看成熟度仍然「可信」。</p>
     *
     * @param supersededByNewer 传入 {@link #isSupersededBy(List)} 的结果（避免每个调用方自己扫）
     * @return 空串 = 可以当可信的；非空 = <b>不可信的确切原因</b>（可注入给人看）
     */
    public String unusableReason(boolean supersededByNewer) {
        if (retracted) {
            return "已撤回"
                    + (retractedReason == null || retractedReason.isBlank() ? "（没写理由）" : "：" + retractedReason)
                    + (retractedAt > 0 ? "（" + retractedAt + "）" : "");
        }
        if (supersededByNewer) {
            return "已被更新的条目取代（supersedes 链上的后继者存在）";
        }
        return "";
    }

    /** 标记撤回（E8）。已撤回的再撤一次会**覆盖理由**而不是报错（幂等）。 */
    public ExperienceEntry retracted(String reason) {
        return builder().from(this)
                .retracted(true)
                .retractedAt(System.currentTimeMillis())
                .retractedReason(reason)
                .lastAccessedAt(System.currentTimeMillis())
                .build();
    }

    /** 撤销撤回（E8）：误撤回要能救回来，且**如实记录它被救回来过**。 */
    public ExperienceEntry unretracted() {
        return builder().from(this)
                .retracted(false)
                .retractedAt(0L)
                .retractedReason("")
                .lastAccessedAt(System.currentTimeMillis())
                .build();
    }

    /** 标注本条目取代了哪一条（E8 修订链）。空 = 不取代任何东西。 */
    public ExperienceEntry supersedes(String olderId) {
        return builder().from(this)
                .supersedes(olderId)
                .lastAccessedAt(System.currentTimeMillis())
                .build();
    }

    /**
     * 连续失败计数（E8 降级的判据）。
     *
     * <p>为什么不用 {@code counterexamples.size()}：那个是<b>累计证据</b>，成功一次
     * 也仍然留着（那是对「在什么条件下不成立」的记录，丢了才是丢证据）。
     * 而「该不该降级」问的是<b>最近这一段连续有多不对</b> —— 判据不同，不能混用。</p>
     */
    public int consecutiveFailures() {
        return Math.max(0, consecutiveFailures);
    }

    /** 成功一次：连续失败归零（但<b>不清</b> counterexamples —— 那是证据）。 */
    public ExperienceEntry withConsecutiveFailures(int v) {
        return builder().from(this)
                .consecutiveFailures(Math.max(0, v))
                .lastAccessedAt(System.currentTimeMillis())
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("v", FORMAT_VERSION);
        o.addProperty("id", id);
        o.addProperty("type", type == null ? null : type.name());
        o.addProperty("title", title);
        o.addProperty("description", description);
        o.addProperty("rationale", rationale);
        o.addProperty("root_cause", rootCause);
        o.addProperty("recommended_response", recommendedResponse);
        o.add("trigger_strings", strArray(triggerStrings));
        o.add("tool_names", strArray(toolNames));
        o.add("tags", strArray(tags));
        o.addProperty("maturity", maturity == null ? null : maturity.name());
        o.addProperty("verified_count", verifiedCount);
        o.addProperty("priority", priority);
        o.add("counterexamples", strArray(counterexamples));
        o.addProperty("created_at", createdAt);
        o.addProperty("verified_at", verifiedAt);
        o.addProperty("last_accessed_at", lastAccessedAt);
        // E8 五个字段。retracted=false / retractedAt=0 / supersedes="" / consecutiveFailures=0
        // 是「没发生过」—— 照样写出来，让「从没被验证过」和「文件里没这个键」能区分开。
        o.addProperty("retracted", retracted);
        o.addProperty("retracted_at", retractedAt);
        o.addProperty("retracted_reason", retractedReason == null ? "" : retractedReason);
        o.addProperty("supersedes", supersedes == null ? "" : supersedes);
        o.addProperty("consecutive_failures", Math.max(0, consecutiveFailures));
        return o;
    }

    /** 容错解析：缺字段/坏字段一律落到默认值，不抛异常（坏行在 store 层直接跳过）。 */
    public static ExperienceEntry fromJson(JsonObject o) {
        return builder()
                .id(str(o, "id"))
                .type(parseEnum(ExperienceType.class, str(o, "type")))
                .title(str(o, "title"))
                .description(str(o, "description"))
                .rationale(str(o, "rationale"))
                .rootCause(str(o, "root_cause"))
                .recommendedResponse(str(o, "recommended_response"))
                .triggerStrings(strList(o, "trigger_strings"))
                .toolNames(strList(o, "tool_names"))
                .tags(strList(o, "tags"))
                .maturity(parseEnum(ExperienceMaturity.class, str(o, "maturity")))
                .verifiedCount(intVal(o, "verified_count"))
                .priority(intVal(o, "priority"))
                .counterexamples(strList(o, "counterexamples"))
                .createdAt(longVal(o, "created_at"))
                .verifiedAt(longVal(o, "verified_at"))
                .lastAccessedAt(longVal(o, "last_accessed_at"))
                // E8：缺字段一律当「没发生过」（旧数据一条都没这些键，必须照常读）
                .retracted(boolVal(o, "retracted"))
                .retractedAt(longVal(o, "retracted_at"))
                .retractedReason(str(o, "retracted_reason"))
                .supersedes(str(o, "supersedes"))
                .consecutiveFailures(intVal(o, "consecutive_failures"))
                .build();
    }

    // ---- codec helpers ----

    private static JsonArray strArray(List<String> values) {
        JsonArray a = new JsonArray();
        if (values != null) {
            for (String s : values) {
                if (s != null) {
                    a.add(s);
                }
            }
        }
        return a;
    }

    private static List<String> strList(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        JsonElement el = o.get(key);
        if (el != null && el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                if (e.isJsonPrimitive()) {
                    out.add(e.getAsString());
                }
            }
        }
        return out;
    }

    private static String str(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? "" : el.getAsString();
    }

    private static int intVal(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? 0 : el.getAsInt();
    }

    private static long longVal(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? 0L : el.getAsLong();
    }

    private static boolean boolVal(JsonObject o, String key) {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull()) {
            return false;
        }
        try {
            return el.getAsBoolean();
        } catch (RuntimeException e) {
            // ⚠️ 坏值不抛：这份数据可能手改过，「读不出真假」不该让整个文件被跳过
            //   （那正是 B1 那条断链的成因：一条坏行把整个经验库变成 0 条）。
            return false;
        }
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> type, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (T c : type.getEnumConstants()) {
            if (c.name().equalsIgnoreCase(name)) {
                return c;
            }
        }
        return null;
    }

    // ---- builder ----

    public static final class Builder {
        private String id;
        private ExperienceType type;
        private String title = "";
        private String description = "";
        private String rationale = "";
        private String rootCause = "";
        private String recommendedResponse = "";
        private List<String> triggerStrings = List.of();
        private List<String> toolNames = List.of();
        private List<String> tags = List.of();
        private ExperienceMaturity maturity = ExperienceMaturity.OBSERVED;
        private int verifiedCount;
        private int priority = 50;
        private List<String> counterexamples = List.of();
        private long createdAt;
        private long verifiedAt;
        private long lastAccessedAt;
        private boolean retracted;
        private long retractedAt;
        private String retractedReason = "";
        private String supersedes = "";
        private int consecutiveFailures;

        public Builder from(ExperienceEntry e) {
            if (e == null) {
                return this;
            }
            return id(e.id())
                    .type(e.type())
                    .title(e.title())
                    .description(e.description())
                    .rationale(e.rationale())
                    .rootCause(e.rootCause())
                    .recommendedResponse(e.recommendedResponse())
                    .triggerStrings(e.triggerStrings())
                    .toolNames(e.toolNames())
                    .tags(e.tags())
                    .maturity(e.maturity())
                    .verifiedCount(e.verifiedCount())
                    .priority(e.priority())
                    .counterexamples(e.counterexamples())
                    .createdAt(e.createdAt())
                    .verifiedAt(e.verifiedAt())
                    .lastAccessedAt(e.lastAccessedAt())
                    // E8 元数据必须一起搬，否则 withEvidence()/retracted() 走一遍就把它们抹成默认值
                    .retracted(e.retracted())
                    .retractedAt(e.retractedAt())
                    .retractedReason(e.retractedReason())
                    .supersedes(e.supersedes())
                    .consecutiveFailures(e.consecutiveFailures());
        }

        public Builder id(String v) {
            this.id = v;
            return this;
        }

        public Builder type(ExperienceType v) {
            this.type = v;
            return this;
        }

        public Builder title(String v) {
            this.title = v;
            return this;
        }

        public Builder description(String v) {
            this.description = v;
            return this;
        }

        public Builder rationale(String v) {
            this.rationale = v;
            return this;
        }

        public Builder rootCause(String v) {
            this.rootCause = v;
            return this;
        }

        public Builder recommendedResponse(String v) {
            this.recommendedResponse = v;
            return this;
        }

        public Builder triggerStrings(List<String> v) {
            this.triggerStrings = v;
            return this;
        }

        public Builder toolNames(List<String> v) {
            this.toolNames = v;
            return this;
        }

        public Builder tags(List<String> v) {
            this.tags = v;
            return this;
        }

        public Builder maturity(ExperienceMaturity v) {
            this.maturity = v;
            return this;
        }

        public Builder verifiedCount(int v) {
            this.verifiedCount = v;
            return this;
        }

        public Builder priority(int v) {
            this.priority = v;
            return this;
        }

        public Builder counterexamples(List<String> v) {
            this.counterexamples = v;
            return this;
        }

        public Builder createdAt(long v) {
            this.createdAt = v;
            return this;
        }

        public Builder verifiedAt(long v) {
            this.verifiedAt = v;
            return this;
        }

        public Builder lastAccessedAt(long v) {
            this.lastAccessedAt = v;
            return this;
        }

        public Builder retracted(boolean v) {
            this.retracted = v;
            return this;
        }

        public Builder retractedAt(long v) {
            this.retractedAt = v;
            return this;
        }

        public Builder retractedReason(String v) {
            this.retractedReason = v;
            return this;
        }

        public Builder supersedes(String v) {
            this.supersedes = v;
            return this;
        }

        public Builder consecutiveFailures(int v) {
            this.consecutiveFailures = v;
            return this;
        }

        public ExperienceEntry build() {
            long now = System.currentTimeMillis();
            long created = createdAt > 0 ? createdAt : now;
            String t = title == null ? "" : title.trim();
            String d = description == null ? "" : description.trim();
            if (id == null || id.isBlank()) {
                id = stableKey(type, t);
            }
            return new ExperienceEntry(
                    id,
                    type,
                    t,
                    d,
                    nz(rationale),
                    nz(rootCause),
                    nz(recommendedResponse),
                    copy(triggerStrings),
                    copy(toolNames),
                    copy(tags),
                    maturity == null ? ExperienceMaturity.OBSERVED : maturity,
                    verifiedCount,
                    priority <= 0 ? 50 : priority,
                    copy(counterexamples),
                    created,
                    verifiedAt,
                    lastAccessedAt > 0 ? lastAccessedAt : created,
                    retracted,
                    retractedAt > 0 ? retractedAt : 0L,
                    nz(retractedReason),
                    nz(supersedes),
                    Math.max(0, consecutiveFailures)
            );
        }

        private static String nz(String s) {
            return s == null ? "" : s;
        }

        private static List<String> copy(List<String> src) {
            if (src == null || src.isEmpty()) {
                return List.of();
            }
            Set<String> seen = new LinkedHashSet<>();
            for (String s : src) {
                if (s != null && !s.isBlank()) {
                    seen.add(s.trim());
                }
            }
            return List.copyOf(seen);
        }
    }
}
