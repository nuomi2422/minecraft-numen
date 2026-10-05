package com.dwinovo.numen.api.carrier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 携带器规则的<b>持久化 + 审批</b>存储（2026-10-05）。
 *
 * <p><b>为什么需要它</b>：此前 {@link CarrierRules#DEFAULT} 是<b>写死的静态列表</b>，
 * 生产上两个调用点都传它：
 * <ul>
 *   <li>{@code Memo.assessCarrier()} —— 复审时的 what-if</li>
 *   <li>{@code RddCarryHint} —— 真正的运行时携带提示</li>
 * </ul>
 * ⇒ 学习者产出的携带器草稿<b>没有任何地方能装下它</b>，写出来就等于沉底。
 * 本类给出那条缺失的落点：<b>草稿 → 候选（待审批）→ 人工/工具显式批准 → 进生效链</b>。
 *
 * <p><b>★ 红线：绝不自动生效</b>。本类<b>不提供</b>「按数量自动批准」之类的入口 ——
 * 携带器会决定同伴在危急时刻带什么，自动化批准 = 让学习者自己给自己授权。
 * 批准只能由<b>显式的一次调用</b>产生，且回执里带批准人给的理由。
 *
 * <p><b>落盘位置</b>：{@code <configDir>/carriers/}
 * <ul>
 *   <li>{@code candidates.json} —— 待审批候选（覆盖写，条数少）</li>
 *   <li>{@code approved.jsonl} —— 已批准规则，<b>只追加</b>（append-only）</li>
 * </ul>
 * append-only 是有意的：批准过的规则<b>不该被后来的重写悄悄抹掉</b>，
 * 要撤销就追加一条同名同 when 的停用记录，或者人工编辑文件（能被 diff 看见）。
 *
 * <p><b>为什么放在 api 而不是 learner</b>：{@link CarrierChain} 在 api，
 * 而 {@code numen-plugin.gradle} 刻意封死跨插件 import。消费者（rdd 插件的
 * {@code RddCarryHint}）必须能 import 它 ⇒ 只能在 api。
 */
public final class CarrierRuleStore {

    /** 生效链 = 内置 DEFAULT + 已批准规则。 */
    public static volatile List<CarrierChain.Rule> OVERRIDE = null;

    private static volatile Path configDir;

    private CarrierRuleStore() {
    }

    /**
     * 装上配置目录并<b>立刻</b>把生效链刷成 DEFAULT + 已批准。
     *
     * <p>插件 setup 时调一次。装之前 {@link #effective()} 返回 {@link CarrierRules#DEFAULT}，
     * 所以<b>即使忘了装也只是「没有额外规则」，不是崩溃</b> —— 但那样草稿就永远不生效，
     * 所以 {@link #effective()} 在未安装时会返回带警告标记的链（见下）。
     */
    public static synchronized void install(Path dir) {
        configDir = dir;
        reload();
    }

    /** 重读已批准规则（批准/拒收之后调；不必重启游戏就能生效）。 */
    public static synchronized void reload() {
        List<CarrierChain.Rule> extra = loadApproved();
        OVERRIDE = extra.isEmpty() ? null : append(extra);
    }

    /**
     * 生效规则链。
     *
     * <p><b>没装目录时</b>：返回 DEFAULT，并把第一条规则名后面挂一个
     * {@code [carrier-store 未安装]} 标记 —— 让「为什么新携带器不生效」在
     * {@code why} 文本里<b>看得见</b>，而不是变成查不到根因的哑故障。
     */
    public static List<CarrierChain.Rule> effective() {
        List<CarrierChain.Rule> o = OVERRIDE;
        if (o != null) {
            return o;
        }
        if (configDir == null) {
            List<CarrierChain.Rule> base = CarrierRules.DEFAULT;
            if (base.isEmpty()) {
                return base;
            }
            CarrierChain.Rule first = base.get(0);
            List<CarrierChain.Rule> marked = new ArrayList<>(base);
            marked.set(0, new CarrierChain.Rule(
                    first.name() + " [carrier-store 未安装，新携带器不会生效]",
                    first.applies(), first.carry(), first.fix()));
            return List.copyOf(marked);
        }
        return CarrierRules.DEFAULT;
    }

    private static List<CarrierChain.Rule> append(List<CarrierChain.Rule> extra) {
        List<CarrierChain.Rule> all = new ArrayList<>(CarrierRules.DEFAULT);
        all.addAll(extra);
        return List.copyOf(all);
    }

    // ── 候选（待审批）─────────────────────────────────────────────────────────

    /** 一条待审批候选。 */
    public record Candidate(String artifactId, String name, String when, List<String> carry,
                            List<String> fix, String sourceReviewId, String sourceMemoId,
                            String submittedAt) {
    }

    /**
     * 校验一份草稿并<b>登记为候选</b>。
     *
     * @return 候选的 artifactId；失败抛 {@link IllegalArgumentException}，<b>消息里带原因</b>
     */
    public static synchronized String submitCandidate(Path dir, String artifactId, String body,
                                                      String sourceReviewId, String sourceMemoId) {
        Draft d = Draft.parse(body);
        Path f = candidatesFile(dir);
        Map<String, JsonObject> all = readCandidates(dir);
        JsonObject rec = new JsonObject();
        rec.addProperty("artifact_id", artifactId);
        rec.addProperty("name", d.name());
        rec.addProperty("when", d.when());
        rec.addProperty("carry", String.join("\n", d.carry()));
        rec.addProperty("fix", String.join("\n", d.fix()));
        rec.addProperty("source_review_id", nz(sourceReviewId));
        rec.addProperty("source_memo_id", nz(sourceMemoId));
        rec.addProperty("submitted_at", java.time.Instant.now().toString());
        rec.addProperty("status", "PENDING");
        all.put(artifactId, rec);
        writeCandidates(f, all);
        return artifactId;
    }

    /** 列出待审批候选（按 artifactId 有序）。 */
    public static synchronized List<Candidate> candidates(Path dir) {
        List<Candidate> out = new ArrayList<>();
        for (var e : readCandidates(dir).entrySet()) {
            JsonObject o = e.getValue();
            out.add(new Candidate(e.getKey(), str(o, "name"), str(o, "when"),
                    lines(o, "carry"), lines(o, "fix"), str(o, "source_review_id"),
                    str(o, "source_memo_id"), str(o, "submitted_at")));
        }
        return out;
    }

    /**
     * ★ 审批流的动作①：<b>批准</b>一条候选 → 写进 {@code approved.jsonl}。
     *
     * <p><b>批准只接受候选 id</b>，不接受现写一份规则 —— 否则「审批」会退化成
     * 「顺手写了个新规则并当场批准」，那就不是审批了。
     *
     * @param reason 批准理由；<b>必填且不许空白</b> —— 没理由的批准无法追责
     * @throws IllegalArgumentException 候选不存在 / 理由为空
     * @throws IllegalStateException 同名规则已批准过（不许静默重复）
     */
    public static synchronized CarrierChain.Rule approve(Path dir, String artifactId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("批准必须给理由：没理由的批准无法追责");
        }
        Map<String, JsonObject> all = readCandidates(dir);
        JsonObject rec = all.get(artifactId);
        if (rec == null) {
            throw new IllegalArgumentException("没有这条候选: " + artifactId
                    + "（先 learner_carrier submit，或 id 写错了）");
        }
        String name = str(rec, "name");
        String when = str(rec, "when");
        for (CarrierChain.Rule r : loadApproved(dir)) {
            if (r.name().equals(name)) {
                throw new IllegalStateException("同名规则已批准过: " + name
                        + "。要改就人工编辑 " + approvedFile(dir) + "（append-only 有意如此）");
            }
        }
        Predicate<CarrierChain.Facts> p = compile(when);
        CarrierChain.Rule rule = new CarrierChain.Rule(name, p,
                lines(rec, "carry"), lines(rec, "fix"));
        appendLine(approvedFile(dir), toJson(name, when, rule.carry(), rule.fix(), artifactId, reason));
        rec.addProperty("status", "APPROVED");
        rec.addProperty("approved_reason", reason);
        rec.addProperty("approved_at", java.time.Instant.now().toString());
        writeCandidates(candidatesFile(dir), all);
        reload();
        return rule;
    }

    /**
     * ★ 审批流的动作②：<b>拒收</b>一条候选。
     *
     * <p>拒收<b>只改状态、不删记录</b> —— 拒了就要能回答「当时为什么拒」。
     */
    public static synchronized void reject(Path dir, String artifactId, String reason) {
        Map<String, JsonObject> all = readCandidates(dir);
        JsonObject rec = all.get(artifactId);
        if (rec == null) {
            throw new IllegalArgumentException("没有这条候选: " + artifactId);
        }
        rec.addProperty("status", "REJECTED");
        rec.addProperty("reject_reason", reason == null ? "" : reason);
        rec.addProperty("rejected_at", java.time.Instant.now().toString());
        writeCandidates(candidatesFile(dir), all);
    }

    /** 候选当前状态（{@code PENDING/APPROVED/REJECTED}），不存在返回 {@code NOT_FOUND}。 */
    public static synchronized String candidateStatus(Path dir, String artifactId) {
        JsonObject rec = readCandidates(dir).get(artifactId);
        return rec == null ? "NOT_FOUND" : str(rec, "status");
    }

    // ── 已批准规则的读写 ──────────────────────────────────────────────────────

    private static Path dir(Path base) {
        return base.resolve("carriers");
    }

    private static Path candidatesFile(Path base) {
        return dir(base).resolve("candidates.json");
    }

    private static Path approvedFile(Path base) {
        return dir(base).resolve("approved.jsonl");
    }

    private static Map<String, JsonObject> readCandidates(Path base) {
        Path f = candidatesFile(base);
        Map<String, JsonObject> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(f)) {
            return out;
        }
        try {
            JsonElement e = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
            if (!e.isJsonObject()) {
                return out;
            }
            for (var en : e.getAsJsonObject().entrySet()) {
                if (en.getValue().isJsonObject()) {
                    out.put(en.getKey(), en.getValue().getAsJsonObject());
                }
            }
        } catch (IOException | RuntimeException ex) {
            // 坏文件不许拖垮查询，也不许静默删（人要看）
            return out;
        }
        return out;
    }

    private static void writeCandidates(Path f, Map<String, JsonObject> all) {
        JsonObject root = new JsonObject();
        for (var e : all.entrySet()) {
            root.add(e.getKey(), e.getValue());
        }
        try {
            Files.createDirectories(f.getParent());
            String tmp = f.resolveSibling(f.getFileName() + ".tmp").toString();
            Files.writeString(Path.of(tmp), root.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(Path.of(tmp), f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("写候选表失败: " + f + " -> " + e, e);
        }
    }

    private static void appendLine(Path f, String line) {
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("写已批准规则失败: " + f + " -> " + e, e);
        }
    }

    /**
     * 把一条已批准规则写成 approved.jsonl 的一行。
     *
     * <p><b>when 必须显式传进来</b>：谓词是编译出来的 lambda，<b>取不回原文</b> ——
     * 曾试过从 Rule 反查，结果写出空 when、批准后规则静默不生效（正是本工程最怕的那类哑故障）。
     */
private static String toJson(String name, String when, List<String> carry, List<String> fix,
                                 String artifactId, String reason) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("when", when);
        o.addProperty("carry", String.join("\n", carry));
        o.addProperty("fix", String.join("\n", fix));
        o.addProperty("from_artifact", artifactId);
        o.addProperty("reason", reason);
        o.addProperty("approved_at", java.time.Instant.now().toString());
        return o.toString();
    }

    private static List<CarrierChain.Rule> loadApproved() {
        return loadApproved(configDir);
    }

    private static List<CarrierChain.Rule> loadApproved(Path base) {
        List<CarrierChain.Rule> out = new ArrayList<>();
        Path f = approvedFile(base);
        if (!Files.isRegularFile(f)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) {
                    continue;
                }
                JsonObject o;
                try {
                    o = JsonParser.parseString(s).getAsJsonObject();
                } catch (RuntimeException ex) {
                    continue;
                }
                String name = str(o, "name");
                String when = str(o, "when");
                List<String> carry = lines(o, "carry");
                List<String> fix = lines(o, "fix");
                if (name.isBlank() || carry.isEmpty()) {
                    continue;
                }
                Predicate<CarrierChain.Facts> p;
                try {
                    p = compile(when);
                } catch (RuntimeException ex) {
                    continue;
                }
                CarrierChain.Rule r = new CarrierChain.Rule(name, p, carry, fix);
                out.add(r);
            }
        } catch (IOException e) {
            return out;
        }
        return out;
    }

    // ── when 表达式：编译成谓词 ─────────────────────────────────────────────

    /** 草稿形状：{@code {"name":..,"when":..,"carry":[..],"fix":[..]}}。 */
    public record Draft(String name, String when, List<String> carry, List<String> fix) {
        public static Draft parse(String body) {
            if (body == null || body.isBlank()) {
                throw new IllegalArgumentException("草稿正文为空：没有内容就不该声称「已产出」");
            }
            JsonObject o;
            try {
                o = JsonParser.parseString(body).getAsJsonObject();
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("草稿不是 JSON 对象，写不成携带器规则: " + e.getMessage());
            }
            String name = str(o, "name");
            String when = str(o, "when");
            List<String> carry = arr(o, "carry");
            List<String> fix = arr(o, "fix");
            if (name.isBlank()) {
                throw new IllegalArgumentException("草稿缺 name：没有名字的规则在 why 里不可读");
            }
            if (carry.isEmpty()) {
                throw new IllegalArgumentException("草稿 " + name + " 的 carry 为空："
                        + "一条什么都不带的规则没有意义");
            }
            if (when.isBlank()) {
                throw new IllegalArgumentException("草稿 " + name + " 缺 when："
                        + "没条件的规则会对所有情况生效 —— 那不是携带器，是全局开关");
            }
            compile(when);
            return new Draft(name, when, carry, fix);
        }
    }

    /**
     * 编译 when 表达式。
     *
     * <p>语法（<b>刻意极简</b>，能表达携带器真正需要的判断，别的都拒）：
     * <ul>
     *   <li>{@code hp<=4} / {@code hp>=10} —— 血量数值比较</li>
     *   <li>{@code band=CRITICAL} —— 血量档</li>
     *   <li>{@code hostile=1} —— 「附近有敌对生物」开关</li>
     *   <li>{@code passive=1}</li>
     *   <li>{@code has=weapon} —— 身上有某类装备（武器/护甲/手上手持）</li>
     *   <li>用 {@code ,} 连接表示 <b>AND</b></li>
     * </ul>
     *
     * <p><b>遇到不认识的词就抛错</b>，绝不忽略它 —— 忽略一个条件会让规则在
     * 「本该不生效」的时候生效，比报错危险得多。
     */
    public static Predicate<CarrierChain.Facts> compile(String when) {
        if (when == null || when.isBlank()) {
            throw new IllegalArgumentException("when 为空");
        }
        List<Predicate<CarrierChain.Facts>> parts = new ArrayList<>();
        for (String rawTok : when.split(",")) {
            String tok = rawTok.trim().toLowerCase(Locale.ROOT);
            if (tok.isEmpty()) {
                continue;
            }
            parts.add(compileOne(tok));
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("when 里没有一个条件: " + when);
        }
        return f -> {
            for (Predicate<CarrierChain.Facts> p : parts) {
                if (!p.test(f)) {
                    return false;
                }
            }
            return true;
        };
    }

    private static Predicate<CarrierChain.Facts> compileOne(String tok) {
        // hp<=4 / hp>=10 / hp<4 / hp>10
        for (String op : new String[]{"<=", ">=", "<", ">", "="}) {
            int i = tok.indexOf(op);
            if (i > 0) {
                String key = tok.substring(0, i).trim();
                String val = tok.substring(i + op.length()).trim();
                if ("hp".equals(key)) {
                    int n;
                    try {
                        n = Integer.parseInt(val);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("hp 比较的右边不是整数: " + tok);
                    }
                    return switch (op) {
                        case "<=" -> f -> f.hasHp() && f.hp() <= n;
                        case ">=" -> f -> f.hasHp() && f.hp() >= n;
                        case "<" -> f -> f.hasHp() && f.hp() < n;
                        default -> f -> f.hasHp() && f.hp() == n;
                    };
                }
                if ("band".equals(key)) {
                    String want = val.toUpperCase(Locale.ROOT);
                    return f -> f.hpBand().equals(want);
                }
                return f -> f.get(key).equalsIgnoreCase(val);
            }
        }
        int eq = tok.indexOf('=');
        if (eq > 0) {
            String key = tok.substring(0, eq).trim();
            String val = tok.substring(eq + 1).trim();
            switch (key) {
                case "band":
                    return f -> f.hpBand().equalsIgnoreCase(val);
                case "hostile":
                    return f -> truthy(val) == f.hostileNearby();
                case "passive":
                    return f -> truthy(val) == f.passiveNearby();
                case "has":
                    return hasItem(val);
                default:
                    return f -> f.get(key).equalsIgnoreCase(val);
            }
        }
        switch (tok) {
            case "hostile":
                return CarrierChain.Facts::hostileNearby;
            case "passive":
                return CarrierChain.Facts::passiveNearby;
            case "low_hp":
                return f -> f.hasHp() && f.hp() <= 4;
            case "critical":
                return f -> "CRITICAL".equals(f.hpBand());
            case "has_weapon":
                return hasItem("weapon");
            case "has_armor":
                return hasItem("armor");
            case "unknown_hand":
                return hasItem("hand");
            default:
                throw new IllegalArgumentException("不认识的 when 条件: '" + tok + "'。"
                        + "支持: hp<=N / hp>=N / band=X / hostile=1 / passive=1 / has=weapon|armor|hand /"
                        + " hostile / passive / low_hp / critical / has_weapon / has_armor / unknown_hand（用 , 连接）");
        }
    }

    private static Predicate<CarrierChain.Facts> hasItem(String what) {
        return switch (what.toLowerCase(Locale.ROOT)) {
            case "weapon" -> CarrierChain.Facts::hasRealWeapon;
            case "armor" -> CarrierChain.Facts::hasRealArmor;
            case "hand" -> CarrierChain.Facts::handItemUnrecognized;
            default -> f -> f.get("item_" + what).equalsIgnoreCase("1");
        };
    }

    private static boolean truthy(String v) {
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.equals("1") || s.equals("true") || s.equals("yes")) {
            return true;
        }
        if (s.equals("0") || s.equals("false") || s.equals("no") || s.equals("")) {
            return false;
        }
        throw new IllegalArgumentException("开关值只认 1/0/true/false/yes/no，收到: " + v);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static List<String> lines(JsonObject o, String k) {
        List<String> out = new ArrayList<>();
        String s = str(o, k);
        for (String p : s.split("\n")) {
            String t = p.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static List<String> arr(JsonObject o, String k) {
        List<String> out = new ArrayList<>();
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull()) {
            return out;
        }
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            for (JsonElement x : a) {
                String t = x.getAsString().trim();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
            return out;
        }
        return lines(o, k);
    }
}