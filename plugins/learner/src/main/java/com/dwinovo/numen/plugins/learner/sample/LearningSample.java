package com.dwinovo.numen.plugins.learner.sample;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 一条学习样本（158 §3.1）。
 *
 * <h2>样本到底是什么</h2>
 * 158 §3.1 原话：「学习样本是一次事件或一段运行的可回放材料，<b>不是经验正文</b>」。
 * 所以这个类里<b>没有任何结论字段</b>：没有「原因是什么」、没有「应该怎么做」，
 * 只有「哪次运行、哪个同伴、什么目标版本、什么事件、哪段时间、凭哪些证据」。
 * 归因写在产物里（经验/支线/工具说明……），不写在样本里 —— 两者混在一起，
 * 就无法实现「同一样本双 Learner 各复盘一次」和「{@code ATTRIBUTION_CONFLICT}」。
 *
 * <h2>为什么证据是列表而不是一个字符串</h2>
 * 158 §5 要求每个事实性断言有字段级 {@code evidenceRefs}。样本侧必须能给出
 * 「这条样本覆盖了哪几条原始证据」，否则复盘时写的引用无法机械核验。
 *
 * <h2>为什么保留 earliest / latest 两个时间</h2>
 * 158 §6 要求合并时「保留最早和最近证据」。只有 latest 会丢掉「这个问题从什么时候开始」，
 * 只有 earliest 会丢掉「还在不在发生」。两个都要。
 *
 * <h2>不可变</h2>
 * record + {@code List.copyOf}，合并与状态迁移都返回新对象。为什么不用可变对象：
 * 样本会同时被「入队线程」和「复盘线程」看到，可变对象必然出现「复盘读到一半被追加 occurrence」
 * 的半截状态，而半截状态的结论无法回查。
 *
 * <p>纯 JVM、无 MC/NUMEN 依赖，可离线单测。
 */
public record LearningSample(
        /** 稳定 id。由 {@link SampleStore} 分配；同一个强指纹在同一个时间桶内永远对应同一个 id。 */
        String sampleId,
        String runId,
        String companionId,
        String goalId,
        String planVersion,
        /** 触发类型：death / starvation_death / loop_detected / repeat_gather / resource_waste ... */
        String triggerKind,
        /** 触发细节原文（给人和复盘模型看的，不参与指纹）。 */
        String triggerDetail,
        /** 归一化后的归因签名，只用于指纹。原始写法放 {@link #triggerDetail}。 */
        String causeSignature,
        String worldId,
        String dimension,
        long bucketIndex,
        String fingerprint,
        String weakFingerprint,
        SampleState state,
        /** 来源通道：instrumentation / auto_intake / manual_intake / harness。 */
        String source,
        long createdAt,
        long earliestEvidenceAt,
        long latestEvidenceAt,
        /** 是否被判定为「疑似重复」（158 §6：判断不了是否相同时保留独立样本并标记）。 */
        boolean possibleDuplicate,
        /** 疑似重复指向的样本 id；非疑似时为空串。 */
        String possibleDuplicateOf,
        /** 因目标版本/时间窗口/世界条件变化而关联的历史样本（158 §6「生成新样本并关联」）。 */
        List<String> relatedSampleIds,
        List<SampleOccurrence> occurrences
) {

    public LearningSample {
        Objects.requireNonNull(sampleId, "sampleId");
        if (sampleId.isBlank()) {
            throw new IllegalArgumentException("sampleId 不能为空");
        }
        state = state == null ? SampleState.OPEN : state;
        runId = runId == null ? "" : runId;
        companionId = companionId == null ? "" : companionId;
        goalId = goalId == null ? "" : goalId;
        planVersion = planVersion == null ? "" : planVersion;
        triggerKind = triggerKind == null ? "" : triggerKind;
        triggerDetail = triggerDetail == null ? "" : triggerDetail;
        causeSignature = causeSignature == null ? "" : causeSignature;
        worldId = worldId == null ? "" : worldId;
        dimension = dimension == null ? "" : dimension;
        fingerprint = fingerprint == null ? "" : fingerprint;
        weakFingerprint = weakFingerprint == null ? "" : weakFingerprint;
        source = source == null ? "" : source;
        possibleDuplicateOf = possibleDuplicateOf == null ? "" : possibleDuplicateOf;
        relatedSampleIds = relatedSampleIds == null ? List.of() : List.copyOf(relatedSampleIds);
        occurrences = occurrences == null ? List.of() : List.copyOf(occurrences);
        // 「标记了疑似重复却没给指向」是自相矛盾的状态：下游要按它去合并证据，必须能查到目标。
        if (possibleDuplicate && possibleDuplicateOf.isBlank()) {
            throw new IllegalArgumentException("possibleDuplicate=true 时必须给 possibleDuplicateOf");
        }
    }

    /** 首次登记用：一条 occurrence，state=OPEN。 */
    public static LearningSample create(String sampleId,
                                        String companionId,
                                        String triggerKind,
                                        String triggerDetail,
                                        String causeSignature,
                                        String runId,
                                        String goalId,
                                        String planVersion,
                                        String worldId,
                                        String dimension,
                                        long bucketIndex,
                                        String fingerprint,
                                        String weakFingerprint,
                                        String source,
                                        long createdAt,
                                        SampleOccurrence first) {
        Objects.requireNonNull(first, "first occurrence");
        return new LearningSample(
                sampleId, runId, companionId, goalId, planVersion,
                triggerKind, triggerDetail, causeSignature,
                worldId, dimension, bucketIndex,
                fingerprint, weakFingerprint,
                SampleState.OPEN, source, createdAt,
                first.occurredAt(), first.occurredAt(),
                false, "", List.of(), List.of(first));
    }

    /**
     * 追加一次出现（158 §6：重复上报只追加 occurrence）。
     *
     * <p>去重规则：{@code (evidenceRef, occurredAt)} 完全相同则<b>不</b>追加 ——
     * 那是同一条证据被重复投递（intake 重试），不是复发。把重试算成复发会让
     * 「出现次数」虚高，进而把普通经验误升成高频模式。
     *
     * <p>状态不动：追加 occurrence 不代表重新打开复盘。已 RESOLVED 的样本后来又发生，
     * 应该由调度决定是「回退到 READY_FOR_REVIEW」还是「另开新样本」，
     * 那是策略，不该藏在数据对象里偷偷改。
     */
    public LearningSample withOccurrence(SampleOccurrence occ) {
        Objects.requireNonNull(occ, "occurrence");
        for (SampleOccurrence existing : occurrences) {
            if (existing.sameAs(occ)) {
                return this;
            }
        }
        List<SampleOccurrence> next = new ArrayList<>(occurrences);
        next.add(occ);
        return new LearningSample(
                sampleId, runId, companionId, goalId, planVersion,
                triggerKind, triggerDetail, causeSignature,
                worldId, dimension, bucketIndex,
                fingerprint, weakFingerprint,
                state, source, createdAt,
                Math.min(earliestEvidenceAt, occ.occurredAt()),
                Math.max(latestEvidenceAt, occ.occurredAt()),
                possibleDuplicate, possibleDuplicateOf, relatedSampleIds, next);
    }

    /** 状态迁移。非法迁移直接抛，不静默忽略 —— 静默忽略正是「状态对不上还继续跑」的来源。 */
    public LearningSample withState(SampleState target) {
        Objects.requireNonNull(target, "target state");
        if (!state.canMoveTo(target)) {
            throw new IllegalStateException(
                    "样本 " + sampleId + " 不允许从 " + state + " 迁移到 " + target);
        }
        return new LearningSample(
                sampleId, runId, companionId, goalId, planVersion,
                triggerKind, triggerDetail, causeSignature,
                worldId, dimension, bucketIndex,
                fingerprint, weakFingerprint,
                target, source, createdAt,
                earliestEvidenceAt, latestEvidenceAt,
                possibleDuplicate, possibleDuplicateOf, relatedSampleIds, occurrences);
    }

    /** 标记疑似重复（158 §6 的 {@code possibleDuplicate}）。 */
    public LearningSample markPossibleDuplicate(String targetSampleId) {
        if (targetSampleId == null || targetSampleId.isBlank()) {
            throw new IllegalArgumentException("markPossibleDuplicate 需要目标样本 id");
        }
        return new LearningSample(
                sampleId, runId, companionId, goalId, planVersion,
                triggerKind, triggerDetail, causeSignature,
                worldId, dimension, bucketIndex,
                fingerprint, weakFingerprint,
                state, source, createdAt,
                earliestEvidenceAt, latestEvidenceAt,
                true, targetSampleId, relatedSampleIds, occurrences);
    }

    /** 关联历史样本（目标版本/时间窗/世界条件变化时）。重复关联同一 id 不叠加。 */
    public LearningSample relatedTo(String otherSampleId) {
        if (otherSampleId == null || otherSampleId.isBlank() || relatedSampleIds.contains(otherSampleId)) {
            return this;
        }
        List<String> next = new ArrayList<>(relatedSampleIds);
        next.add(otherSampleId);
        return new LearningSample(
                sampleId, runId, companionId, goalId, planVersion,
                triggerKind, triggerDetail, causeSignature,
                worldId, dimension, bucketIndex,
                fingerprint, weakFingerprint,
                state, source, createdAt,
                earliestEvidenceAt, latestEvidenceAt,
                possibleDuplicate, possibleDuplicateOf, next, occurrences);
    }

    public int occurrenceCount() {
        return occurrences.size();
    }

    /** 复盘待办是否已经了结。 */
    public boolean settled() {
        return state.terminal() || state == SampleState.RESOLVED;
    }

    /** 面板/日志读数（可查性：一条样本能自己说清自己从哪来）。 */
    public java.util.Map<String, Object> summary() {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("sampleId", sampleId);
        m.put("companionId", companionId);
        m.put("triggerKind", triggerKind);
        m.put("state", state.name());
        m.put("occurrences", occurrences.size());
        m.put("earliest", earliestEvidenceAt);
        m.put("latest", latestEvidenceAt);
        m.put("possibleDuplicate", possibleDuplicate);
        m.put("fingerprintShort", SampleFingerprint.shortHash(fingerprint));
        return m;
    }
}
