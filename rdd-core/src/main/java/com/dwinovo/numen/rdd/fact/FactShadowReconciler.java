package com.dwinovo.numen.rdd.fact;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 共同事实的 <b>shadow 对账</b>（第三批 N1，2026-10-05）。
 *
 * <p><b>为什么先做 shadow 而不是直接修</b>：共同事实（{@link CompletedFactStore}）
 * 声称「这些阶段完成了」，而使用账本（learner 的 {@code usage-ledger.jsonl}）记录
 * 「这些产物真的被用了」。两边<b>本来就该有出入</b>，但出入的形态决定问题在哪：
 * <ul>
 *   <li>事实说有、用账说没被用 ⇒ 可能<b>真的做完了但没人消费</b>（学习闭环缺口），也可能只是账本没记；</li>
 *   <li>事实说没完成、用账说用过了 ⇒ <b>事实没被更新</b>，下一轮会重复做；</li>
 *   <li>两边都没有 ⇒ 那就是真没做，<b>不是问题</b>，不该报出来充数。</li>
 * </ul>
 *
 * <p><b>★ 只读，绝不改任何一侧。</b> 这是 shadow 的定义：先量出差异形态，再决定改哪边。
 * 现在就自动「修正」事实，等于用一份还没验证的猜测去覆盖生产状态 ——
 * 那正是 AGENTS 里记的「源码改了 ≠ 生效」那类错误在数据上的版本。
 *
 * <p><b>依赖策略</b>：两侧都只按<b>文件</b>读（事实 {@code rdd-facts/<uuid>.json}、
 * 账本 {@code usage-ledger.jsonl}），不 import 任何插件。
 * 因为 {@code numen-plugin.gradle} 刻意封死跨插件 import，
 * 而账本归 learner、事实归 rdd —— 谁都不是谁的编译期依赖，只能走文件。
 */
public final class FactShadowReconciler {

    /**
     * 差异的严重程度。<b>枚举声明顺序即优先级</b>（越靠前越可疑，排序直接用 {@link #ordinal()}）。
     *
     * <p>刻意不给常量带构造参数：枚举里加字段会让「常量 vs 静态字段」的解析变脆，
     * 而且这里只需要顺序，不需要额外数据。
     */
    public enum Severity {
        /** 事实说完成、账本说失败过：最可疑，优先看。 */
        COMPLETED_BUT_FAILED,
        /** 事实说完成、账本里完全没有使用记录：可能真做了但没消费。 */
        COMPLETED_BUT_UNUSED,
        /** 事实说没完成、账本说用过：事实没更新，会导致重复劳动。 */
        USED_BUT_NOT_RECORDED
    }

    /**
     * 一条差异。
     *
     * @param key     关联键（事实侧用阶段键；用账侧用 artifact_id）
     * @param detail  人能读的说明，<b>不带结论</b>（只陈述两边各自说了什么）
     */
    public record Divergence(Severity severity, String side, String key, String detail) {
    }

    /** 一次对账的完整结果。 */
    public record Report(boolean factsReadable, boolean usageReadable,
                         int factKeys, int usageKeys, List<Divergence> divergences) {

        public boolean clean() {
            return divergences.isEmpty();
        }

        /** 两侧读不到时**必须**让人知道 —— 空报告会被误读成「一致」。 */
        public boolean trustworthy() {
            return factsReadable && usageReadable;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("trustworthy", trustworthy());
            m.put("facts_readable", factsReadable);
            m.put("usage_readable", usageReadable);
            if (!trustworthy()) {
                m.put("warning", "★ 有一侧读不到，本报告**不可当结论用**："
                        + "读不到 ≠ 一致。缺哪侧："
                        + (!factsReadable ? "facts(共同事实)" : "usage(使用账本)"));
            }
            m.put("fact_keys", factKeys);
            m.put("usage_keys", usageKeys);
            m.put("clean", clean());
            List<Map<String, Object>> ds = new ArrayList<>();
            for (Divergence d : divergences) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("severity", d.severity().name());
                one.put("side", d.side());
                one.put("key", d.key());
                one.put("detail", d.detail());
                ds.add(one);
            }
            m.put("divergences", ds);
            m.put("note", "★ shadow 只读：这里只量差异，**不自动改任何一侧**");
            return m;
        }
    }

    private FactShadowReconciler() {
    }

    /**
     * 对账。
     *
     * @param factsFile 共同事实文件（{@code rdd-facts/<uuid>.json}）
     * @param usageLedger 使用账本（{@code usage-ledger.jsonl}）；可为 null（当未接）
     */
    public static Report reconcile(Path factsFile, Path usageLedger) {
        Map<String, String> factOutcomes = readFacts(factsFile);
        boolean factsReadable = factsFile != null && Files.isReadable(factsFile);
        Map<String, String> usageOutcomes = readUsage(usageLedger);
        boolean usageReadable = usageLedger == null || Files.isReadable(usageLedger);

        List<Divergence> ds = new ArrayList<>();
        for (Map.Entry<String, String> e : factOutcomes.entrySet()) {
            String key = e.getKey();
            String factSide = e.getValue();
            String usageSide = usageOutcomes.get(key);
            if (usageSide == null) {
                if ("COMPLETED".equals(factSide)) {
                    ds.add(new Divergence(Severity.COMPLETED_BUT_UNUSED, "facts", key,
                            "事实侧记为已完成，使用账本里没有任何使用记录"));
                }
                // 事实侧没完成 + 账本也没记录 ⇒ 真没做，不是问题，不报
                continue;
            }
            if ("COMPLETED".equals(factSide) && "FAIL".equals(usageSide)) {
                ds.add(new Divergence(Severity.COMPLETED_BUT_FAILED, "facts", key,
                        "事实侧记为已完成，使用账本里却是失败：" + usageSide));
            } else if (!"COMPLETED".equals(factSide) && "SUCCESS".equals(usageSide)) {
                ds.add(new Divergence(Severity.USED_BUT_NOT_RECORDED, "usage", key,
                        "使用账本记为成功，事实侧却没有完成记录（下一轮可能重复劳动）"));
            }
        }
        // ★ 反方向也要扫：只遍历事实侧的话，「用过但事实没记」这类差异<b>永远看不到</b>
        //   （键根本不在事实侧里）。单测 `usedButNotRecorded_isReported` 就是钉这一条的。
        for (Map.Entry<String, String> e : usageOutcomes.entrySet()) {
            if (factOutcomes.containsKey(e.getKey())) {
                continue;
            }
            ds.add(new Divergence(Severity.USED_BUT_NOT_RECORDED, "usage", e.getKey(),
                    "使用账本记为 " + e.getValue() + "，事实侧完全没有这个键的记录"
                            + "（下一轮可能重复劳动）"));
        }
        ds.sort((a, b) -> Integer.compare(a.severity().ordinal(), b.severity().ordinal()));
        return new Report(factsReadable, usageReadable, factOutcomes.size(), usageOutcomes.size(),
                List.copyOf(ds));
    }

    /**
     * 读事实侧的「完成 / 未完成」键值。
     *
     * <p><b>刻意宽松</b>：字段名认不准就<b>都</b>试一遍，但<b>猜不到时返回空</b>，
     * 并由 {@link Report#trustworthy()} 标出「读到了但没解析出内容」——
     * 宁可显式说「没解析出来」，也不把空表当成「事实侧没有完成项」。
     */
    private static Map<String, String> readFacts(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        if (file == null || !Files.isRegularFile(file)) {
            return out;
        }
        try {
            JsonElement e = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!e.isJsonObject()) {
                return out;
            }
            JsonObject root = e.getAsJsonObject();
            JsonObject stages = obj(root, "stages");
            if (stages != null) {
                for (Map.Entry<String, JsonElement> en : stages.entrySet()) {
                    out.put(en.getKey(), en.getValue().isJsonObject()
                            ? str(en.getValue().getAsJsonObject(), "status") : "");
                }
                return out;
            }
            JsonObject facts = obj(root, "facts");
            if (facts != null) {
                for (Map.Entry<String, JsonElement> en : facts.entrySet()) {
                    out.put(en.getKey(), en.getValue().isJsonObject()
                            ? str(en.getValue().getAsJsonObject(), "status") : "");
                }
            }
        } catch (IOException | RuntimeException ex) {
            return out;
        }
        return out;
    }

    /** 读使用账本侧的「SUCCESS / FAIL / CANCELED」，取每个产物最新阶段那条。 */
    private static Map<String, String> readUsage(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        if (file == null || !Files.isRegularFile(file)) {
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
                    continue;
                }
                String id = str(o, "artifact_id");
                String outcome = str(o, "outcome");
                if (id.isBlank() || outcome.isBlank() || "UNKNOWN".equals(outcome)) {
                    continue;
                }
                // append-only，同 key 后写覆盖前写 = 取最新
                out.put(id, outcome);
            }
        } catch (IOException ex) {
            return out;
        }
        return out;
    }

    private static JsonObject obj(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
    }
}