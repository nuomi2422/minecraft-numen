package com.dwinovo.numen.plugins.learner.contract;

import java.util.List;

/**
 * 一条学习产物的草稿（158 §3.2 / §6）。
 *
 * <h2>产物身份：指纹独立于正文</h2>
 * 161 §6.3 实测到一个真实冲突：现有 `artifact_id` 里含**正文哈希**，
 * 于是正文改一个字就是"新产物"，与 158 §6 要求的"同义产物进 reuse/revise/conflict"直接打架。
 *
 * <p>所以这里的指纹<strong>刻意不看正文</strong>：
 * `kind + 归一化 purpose + trigger/exclude + audience`。
 * 正文变了是**修订**（同一产物、更高版本），不是新产物。
 * 正文该进的是内容哈希，那是另一件事（防篡改），不能兼任身份。
 *
 * <h2>为什么保留 body 却不参与指纹</h2>
 * body 是产物内容，必须有；但"这是不是同一个产物"与"内容改没改"是两个问题。
 * 混在一起就无法回答「这条经验被修订过几次」。
 *
 * <p>纯 JVM，可离线单测。
 */
public record ArtifactDraft(
        ArtifactKind kind,
        String name,
        String purpose,
        String body,
        List<String> triggerConditions,
        List<String> excludeConditions,
        List<String> preconditions,
        String risk,
        List<EvidenceRef> evidence,
        List<String> sampleRefs,
        String audience
) {

    public ArtifactDraft {
        if (kind == null) {
            throw new IllegalArgumentException("产物必须有类型");
        }
        name = name == null ? "" : name.trim();
        purpose = purpose == null ? "" : purpose.trim();
        body = body == null ? "" : body;
        triggerConditions = triggerConditions == null ? List.of() : List.copyOf(triggerConditions);
        excludeConditions = excludeConditions == null ? List.of() : List.copyOf(excludeConditions);
        preconditions = preconditions == null ? List.of() : List.copyOf(preconditions);
        risk = risk == null ? "" : risk.trim();
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        sampleRefs = sampleRefs == null ? List.of() : List.copyOf(sampleRefs);
        audience = audience == null ? "" : audience.trim();
    }

    /**
     * 产物指纹：<b>只由身份字段决定</b>，与 body 无关。
     *
     * <p>归一化用 {@code trim + 小写 + 空白压缩}：中文输入法会产出全角与多余空格，
     * 不归一化就会出现「看起来一样的两个产物」。
     */
    public String fingerprint() {
        return kind.name()
                + "|" + norm(purpose)
                // 逐元素归一后再拼：整体 norm 会留下「 t1 ;t2」这种分隔符旁的空格，
                // 于是 " t1 " 与 "t1" 被判成两个不同的触发条件。
                + "|" + normList(triggerConditions)
                + "|" + normList(excludeConditions)
                + "|" + norm(audience);
    }

    /** 内容摘要只用于防篡改，<b>不参与身份</b>。 */
    public String contentHash() {
        return Integer.toHexString(body.hashCode());
    }

    /** 字段是否"在场且非空"（数组字段看是否非空）。 */
    public boolean hasField(String field) {
        return switch (field) {
            case "purpose" -> !purpose.isBlank();
            case "body" -> !body.isBlank();
            case "risk" -> !risk.isBlank();
            case "triggerConditions" -> !triggerConditions.isEmpty();
            case "excludeConditions" -> !excludeConditions.isEmpty();
            case "preconditions" -> !preconditions.isEmpty();
            case "evidence" -> !evidence.isEmpty();
            default -> false;
        };
    }

    /**
     * 归一化：NFKC（全角→半角）→ 空白压缩 → 转小写。
     *
     * <p>少了 NFKC 这一步，「ＡＢ」与「ab」会算成两个不同产物 ——
     * 而它们在任何人的眼里都是同一个。中文输入法产出全角是常态，
     * 这个坑与 {@code SampleFingerprint.normalize} 是同一个（161 §6 已记过）。
     */
    private static String normList(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        return items.stream()
                .map(ArtifactDraft::norm)
                .filter(s -> !s.isEmpty())
                .reduce((a, b) -> a + ";" + b)
                .orElse("");
    }

    private static String norm(String raw) {
        if (raw == null) {
            return "";
        }
        String s = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKC);
        return s.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }
}
