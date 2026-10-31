package com.dwinovo.numen.plugins.learner.skills;

import com.dwinovo.numen.plugins.learner.contract.ArtifactDraft;
import com.dwinovo.numen.plugins.learner.contract.ArtifactKind;

import java.util.List;

/**
 * 统一 Skill 目录条目（158 §3.3）。
 *
 * <h2>它和"经验"不是一回事</h2>
 * 经验是"知道什么"，Skill 是"在什么条件下、给谁、怎么用"。158 §3.3 把外壳定死为：
 * `skillId/version/kind/name/purpose/audience/trigger/exclude/preconditions/risk/
 * evidenceLevel/status/bodyRef/validationSignals/foundingSamples/supersedes/conflicts`。
 * 缺了 `audience` 就会被执行 AI 当规划知识用；缺了 `excludeConditions` 就会在错误场景触发。
 *
 * <h2>为什么版本是 int 而不是字符串</h2>
 * 修订必须能比较大小（"当前生效的是哪一版"），字符串版本号迟早出现 `v2` 与 `2` 并存。
 *
 * <h2>foundingSamples 不是可选装饰</h2>
 * 158 §7 要求「已登记 Skill 需定期抽验 founding samples 回放」。
 * 没有 founding samples 的条目<b>无法被抽验</b>，等于无法被证伪 —— 所以这里强制非空。
 *
 * <p>纯 JVM，可离线单测。
 */
public record SkillEntry(
        String skillId,
        int version,
        ArtifactKind kind,
        String name,
        String purpose,
        String audience,
        List<String> triggerConditions,
        List<String> excludeConditions,
        List<String> preconditions,
        String risk,
        String evidenceLevel,
        SkillStatus status,
        String bodyRef,
        List<String> validationSignals,
        List<String> foundingSamples,
        List<String> supersedes,
        List<String> conflicts
) {

    public SkillEntry {
        if (skillId == null || skillId.isBlank()) {
            throw new IllegalArgumentException("skillId 不能为空");
        }
        if (version < 1) {
            throw new IllegalArgumentException("版本必须 >= 1");
        }
        if (kind == null) {
            throw new IllegalArgumentException("Skill 必须有所属类型");
        }
        name = name == null ? "" : name.trim();
        purpose = purpose == null ? "" : purpose.trim();
        audience = audience == null ? "" : audience.trim();
        triggerConditions = triggerConditions == null ? List.of() : List.copyOf(triggerConditions);
        excludeConditions = excludeConditions == null ? List.of() : List.copyOf(excludeConditions);
        preconditions = preconditions == null ? List.of() : List.copyOf(preconditions);
        risk = risk == null ? "" : risk.trim();
        evidenceLevel = evidenceLevel == null ? "" : evidenceLevel.trim();
        status = status == null ? SkillStatus.DRAFT : status;
        bodyRef = bodyRef == null ? "" : bodyRef.trim();
        validationSignals = validationSignals == null ? List.of() : List.copyOf(validationSignals);
        foundingSamples = foundingSamples == null ? List.of() : List.copyOf(foundingSamples);
        supersedes = supersedes == null ? List.of() : List.copyOf(supersedes);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        if (foundingSamples.isEmpty()) {
            // 158 §7：没有 founding samples 就无法抽验回放 = 无法被证伪。
            throw new IllegalArgumentException("Skill 必须至少关联一个来源样本（foundingSamples）");
        }
    }

    /**
     * 身份指纹：与 {@link ArtifactDraft#fingerprint()} 同一套规则。
     *
     * <p>为什么复用同一套：产物与 Skill 条目是同一件东西的两个阶段。
     * 两套指纹规则会让"同一条产物两次登记变成两条 Skill"，而那是目录膨胀的起点。
     */
    public String fingerprint() {
        return kind.name()
                + "|" + norm(purpose)
                + "|" + normList(triggerConditions)
                + "|" + normList(excludeConditions)
                + "|" + norm(audience);
    }

    /** 修订：同 id、版本 +1、旧版本进 supersedes。 */
    public SkillEntry revise(String newBodyRef, List<String> newFoundingSamples) {
        List<String> nextSupersedes = new java.util.ArrayList<>(supersedes);
        nextSupersedes.add(skillId + "@v" + version);
        List<String> samples = new java.util.ArrayList<>(foundingSamples);
        if (newFoundingSamples != null) {
            for (String s : newFoundingSamples) {
                if (s != null && !s.isBlank() && !samples.contains(s)) {
                    samples.add(s);
                }
            }
        }
        return new SkillEntry(skillId, version + 1, kind, name, purpose, audience,
                triggerConditions, excludeConditions, preconditions, risk, evidenceLevel,
                status, newBodyRef == null ? bodyRef : newBodyRef, validationSignals,
                samples, nextSupersedes, conflicts);
    }

    private static String norm(String raw) {
        if (raw == null) {
            return "";
        }
        String s = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKC);
        return s.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    private static String normList(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        return items.stream().map(SkillEntry::norm).filter(s -> !s.isEmpty())
                .reduce((a, b) -> a + ";" + b).orElse("");
    }
}
