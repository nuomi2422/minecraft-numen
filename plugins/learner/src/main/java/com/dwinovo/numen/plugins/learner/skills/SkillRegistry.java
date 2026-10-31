package com.dwinovo.numen.plugins.learner.skills;

import com.dwinovo.numen.plugins.learner.contract.ArtifactDraft;
import com.dwinovo.numen.plugins.learner.contract.ArtifactKind;

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
        if (artifact == null) {
            return new Decision(Outcome.CONFLICT, null, "no_artifact");
        }
        SkillEntry candidate = new SkillEntry(
                skillId, 1, artifact.kind(), artifact.name(), artifact.purpose(),
                artifact.audience(), artifact.triggerConditions(), artifact.excludeConditions(),
                artifact.preconditions(), artifact.risk(), "", SkillStatus.DRAFT, bodyRef,
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

    private void store(SkillEntry e) {
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

    private static String norm(String raw) {
        if (raw == null) {
            return "";
        }
        String s = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKC);
        return s.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }
}
