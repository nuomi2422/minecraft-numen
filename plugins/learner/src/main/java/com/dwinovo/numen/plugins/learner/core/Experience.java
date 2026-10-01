package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条<b>结构化</b>的经验（{@code 45} 号 §2 用户定的七字段格式）。
 *
 * <p><b>为什么要有这个 record（2026-10-01 实机抓到的）</b>：
 * 用户 2026-10-01 口述七字段格式时，它<b>只是一段文档描述</b>，
 * 实机证据：{@code learner_review} 真跑了 LLM，判定质量也对
 *（唯一该沉淀的那条抓到了真 bug、置信度 0.95），
 * 但它给出的 {@code experience_draft} <b>是一段散文，七个字段一个都没结构化</b>。
 *
 * <p><b>所以这一条把「格式」从文档变成代码</b>：模型必须交出 7 个具名字段，
 * 拿不出来就是拿不出来（{@link #completeness()} 会如实说），
 * <b>而不是</b>把一段话说得像有结构。
 *
 * <p><b>B21 在这里的含义</b>：缺字段用「<b>字段不出现</b>」表达，
 * <b>禁止</b>用 {@code ""} / {@code "无"} / {@code "未知"} 冒充。
 * {@link #toMap()} 只放真值。
 *
 * <p><b>卡严程度</b>：用户 2026-10-01 定「先不严，第 2 批再卡」。
 * 所以本 record <b>只卡字段齐全 + 两个关键字段非空</b>，
 * <b>不卡「像不像真东西」</b> —— 后者（第 2 批）是「拿到下一批同类场景去对」。
 */
public record Experience(
        String mechanism,
        String preconditions,
        String failureConditions,
        String observableSignal,
        String derivation,
        String efficiency,
        String evidence
) {

    private static final Gson GSON = new Gson();

    /** 七字段的规范名（给 prompt 用，也给渲染用）。顺序即 {@code 45} §2 的顺序。 */
    public static final List<String> FIELDS = List.of(
            "mechanism", "preconditions", "failureConditions",
            "observableSignal", "derivation", "efficiency", "evidence");

    /** 中文名，只用于给人看（prompt 与报错）。 */
    private static final Map<String, String> CN = Map.of(
            "mechanism", "机制",
            "preconditions", "前置条件",
            "failureConditions", "失效条件",
            "observableSignal", "可观察信号",
            "derivation", "推导步骤",
            "efficiency", "效率",
            "evidence", "证据");

    public Experience {
        mechanism = nz(mechanism);
        preconditions = nz(preconditions);
        failureConditions = nz(failureConditions);
        observableSignal = nz(observableSignal);
        derivation = nz(derivation);
        efficiency = nz(efficiency);
        evidence = nz(evidence);
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    /** 取某个字段的规范化值。 */
    public String field(String name) {
        return switch (name) {
            case "mechanism" -> mechanism;
            case "preconditions" -> preconditions;
            case "failureConditions" -> failureConditions;
            case "observableSignal" -> observableSignal;
            case "derivation" -> derivation;
            case "efficiency" -> efficiency;
            case "evidence" -> evidence;
            default -> "";
        };
    }

    /** 七个字段里<b>非空</b>的有几个（0..7）。 */
    public int filledCount() {
        int n = 0;
        for (String f : FIELDS) {
            if (!field(f).isBlank()) {
                n++;
            }
        }
        return n;
    }

    /** 七个字段里<b>缺</b>的有哪些（返回中文名）。 */
    public List<String> missingFields() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String f : FIELDS) {
            if (field(f).isBlank()) {
                out.add(CN.getOrDefault(f, f));
            }
        }
        return out;
    }

    /**
     * 「关键字段齐不齐」。
     *
     * <p><b>为什么只有这两个是关键</b>：{@code 45} §2 自己标了「失效条件（常被漏）」，
     * 而没有 {@code 失效条件} 或 {@code 可观察信号} 的经验，
     * 既不知道自己什么时候会害人，<b>也没法在世界里核对它到底成不成立</b> ——
     * 那正是 V1「判据要落在可查的外部事实上」要排除的东西。
     */
    public boolean requiredComplete() {
        return !failureConditions.isBlank() && !observableSignal.isBlank();
    }

    /** 本轮（第 1 批）的合格线：<b>七字段全填 + 两个关键字段非空</b>。 */
    public boolean acceptable() {
        return filledCount() == FIELDS.size() && requiredComplete();
    }

    /** 如实报告为什么不合格 —— 空字符串表示合格。 */
    public String unacceptableReason() {
        if (acceptable()) {
            return "";
        }
        List<String> miss = missingFields();
        StringBuilder sb = new StringBuilder();
        if (!miss.isEmpty()) {
            sb.append("缺字段 ").append(String.join("、", miss));
        }
        if (!requiredComplete()) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            List<String> key = new java.util.ArrayList<>();
            if (failureConditions.isBlank()) {
                key.add("失效条件");
            }
            if (observableSignal.isBlank()) {
                key.add("可观察信号");
            }
            sb.append("关键字段 ").append(String.join("、", key)).append(" 为空");
        }
        return sb.toString();
    }

    /** 渲染：<b>只放真值</b>（B21），缺失的字段直接不出现。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String f : FIELDS) {
            String v = field(f);
            if (!v.isBlank()) {
                m.put(f, v);
            }
        }
        return m;
    }

    /** 给人看的一行摘要（缺什么直接说）。 */
    public String summary() {
        if (acceptable()) {
            return mechanism;
        }
        return "不合格[" + unacceptableReason() + "]: " + (mechanism.isBlank() ? "(无机制)" : mechanism);
    }

    /**
     * 从 LLM 的 JSON 对象解析。
     *
     * <p><b>刻意不接「一段散文」当经验</b>：老格式的 {@code experience_draft} 是字符串，
     * 本方法<b>不会</b>把字符串塞进 {@code mechanism} 蒙过去 ——
     * 那样会让「格式已落地」这件事<b>看起来成立</b>，而实际仍然是一段散文。
     *
     * @return 解析出来的经验；<b>对象不存在或全空时返回 {@code null}</b>（缺失就缺失）
     */
    public static Experience parse(JsonObject verdictObj) {
        if (verdictObj == null || !verdictObj.has("experience") || verdictObj.get("experience").isJsonNull()) {
            return null;
        }
        JsonElement e = verdictObj.get("experience");
        if (!e.isJsonObject()) {
            return null;
        }
        JsonObject o = e.getAsJsonObject();
        Experience x = new Experience(
                optString(o, "mechanism"),
                optString(o, "preconditions"),
                optString(o, "failureConditions"),
                optString(o, "observableSignal"),
                optString(o, "derivation"),
                optString(o, "efficiency"),
                optString(o, "evidence"));
        return x.filledCount() == 0 ? null : x;
    }

    private static String optString(JsonObject o, String k) {
        if (o == null || !o.has(k) || o.get(k).isJsonNull()) {
            return "";
        }
        JsonElement e = o.get(k);
        if (!e.isJsonPrimitive()) {
            return "";
        }
        return e.getAsString();
    }

    /** 给 prompt 用的格式说明（中文，直接可读）。 */
    public static String promptSpec() {
        return "experience 必须是**对象**，且正好这 7 个键：\n"
                + "  {\"mechanism\":\"世界规律是什么\","
                + "\"preconditions\":\"满足哪些条件才能用\","
                + "\"failureConditions\":\"什么情况下这条会害你\","
                + "\"observableSignal\":\"在世界里看得到的判据\","
                + "\"derivation\":\"从信号到动作的因果链\","
                + "\"efficiency\":\"相比朴素做法省多少\","
                + "\"evidence\":\"从哪来的\"}\n"
                + "硬要求：failureConditions 与 observableSignal **不许留空**。"
                + "填不出这两项，说明你还没真的理解这件事 → 这种情况应该给 NO_ACTION，"
                + "而不是硬凑一段话。\n"
                + "反例（不合格）：\"experience_draft\":\"hp=0 不能当缺失\" 这种一段散文 —— "
                + "它没有结构，不接受。";
    }
}