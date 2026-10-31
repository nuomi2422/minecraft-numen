package com.dwinovo.numen.plugins.learner.sample;

import java.util.List;

/**
 * 样本准入判定：把一次「事件上报」变成 {@code 新建样本 / 并入既有样本 / 疑似重复}（158 §6）。
 *
 * <h2>它为什么必须是一个纯函数</h2>
 * 去重是全系统最容易出「时好时坏」的地方，而时好时坏几乎都来自「判定逻辑里揉了 IO、时钟
 * 和上次的状态」。所以这里不读文件、不取系统时间、不写日志：输入候选 + 输入两个查得的
 * 既有样本，输出决定。存储与持久化在 {@link SampleStore}。这样单测可以对判定本身
 * 做穷举，而不用起文件系统。
 *
 * <h2>三种结果为什么不能合并成两种</h2>
 * <ul>
 *   <li>{@link Outcome#NEW} —— 确实是新样本；</li>
 *   <li>{@link Outcome#MERGED} —— 强指纹命中，同一次事故的又一次上报，只追加 occurrence；</li>
 *   <li>{@link Outcome#POSSIBLE_DUPLICATE} —— 强指纹不中但弱指纹命中（同一时间桶、同目标、
 *       同世界，只有归因不同）。158 §6 要求这时<b>保留独立样本并标记</b>，不许替人决定合并，
 *       也不许当成新事件从零复盘 —— 所以它是第三种，不是 NEW 的一种。</li>
 * </ul>
 *
 * <p>纯 JVM、无 IO、无时钟，可离线单测。
 */
public final class SampleAdmission {

    private SampleAdmission() {
    }

    /**
     * 一次事件上报。
     *
     * @param caveSignature 归因签名（归一化由 {@link SampleFingerprint} 负责，这里存原文）
     */
    public record Candidate(
            String companionId,
            String triggerKind,
            String triggerDetail,
            String causeSignature,
            String runId,
            String goalId,
            String planVersion,
            String worldId,
            String dimension,
            long occurredAt,
            String evidenceRef,
            String source
    ) {
        public Candidate {
            companionId = companionId == null ? "" : companionId;
            triggerKind = triggerKind == null ? "" : triggerKind;
            triggerDetail = triggerDetail == null ? "" : triggerDetail;
            causeSignature = causeSignature == null ? "" : causeSignature;
            runId = runId == null ? "" : runId;
            goalId = goalId == null ? "" : goalId;
            planVersion = planVersion == null ? "" : planVersion;
            worldId = worldId == null ? "" : worldId;
            dimension = dimension == null ? "" : dimension;
            evidenceRef = evidenceRef == null ? "" : evidenceRef;
            source = source == null ? "" : source;
            if (companionId.isBlank()) {
                throw new IllegalArgumentException("候选必须带 companionId：跨同伴去重会把 A 的事故算到 B 头上");
            }
            if (triggerKind.isBlank()) {
                throw new IllegalArgumentException("候选必须带 triggerKind");
            }
        }

        public SampleOccurrence occurrence() {
            return new SampleOccurrence(evidenceRef, occurredAt, source);
        }
    }

    /** 判定结果种类。 */
    public enum Outcome {
        /** 新样本。 */
        NEW,
        /** 并入既有样本（只追加 occurrence）。 */
        MERGED,
        /** 弱指纹命中：独立成样，但标记疑似重复并关联。 */
        POSSIBLE_DUPLICATE
    }

    /**
     * 一次判定。
     *
     * @param sample          处理后的样本（MERGED 时是追加过 occurrence 的新对象）
     * @param matchedSampleId 命中的既有样本 id（NEW 时为空串）
     * @param reason          给人看的判定理由（可查性：合并必须能解释为什么合并）
     */
    public record Decision(Outcome outcome, LearningSample sample, String matchedSampleId,
                           String reason, List<SampleEvent> events) {
        public Decision {
            reason = reason == null ? "" : reason;
            matchedSampleId = matchedSampleId == null ? "" : matchedSampleId;
            events = events == null ? List.of() : List.copyOf(events);
        }

        /** 无事件视图（只关心数据形状的调用方用；生产路径一律用 5 参版本带事件）。 */
        public Decision(Outcome outcome, LearningSample sample, String matchedSampleId, String reason) {
            this(outcome, sample, matchedSampleId, reason, List.of());
        }

        public boolean created() {
            return outcome != Outcome.MERGED;
        }
    }

    /**
     * 判定。
     *
     * @param sameFingerprint 由存储按强指纹查得的既有样本（无则 null）
     * @param sameWeak        由存储按弱指纹查得的既有样本（无则 null；可与 sameFingerprint 相同）
     * @param bucketMillis    时间桶宽度
     * @param nowMillis       登记时刻（用于 createdAt；<b>不参与指纹</b>）
     * @param newSampleId     仅当需要新建时使用的 id
     */
    public static Decision decide(Candidate c,
                                  LearningSample sameFingerprint,
                                  LearningSample sameWeak,
                                  long bucketMillis,
                                  long nowMillis,
                                  String newSampleId) {
        if (c == null) {
            throw new IllegalArgumentException("候选不能为 null");
        }
        long bucket = SampleFingerprint.bucketOf(c.occurredAt(), bucketMillis);
        String fp = SampleFingerprint.of(c.companionId(), c.triggerKind(),
                c.goalId(), c.planVersion(), c.causeSignature(),
                c.worldId(), c.dimension(), bucket);
        String weak = SampleFingerprint.weakOf(c.companionId(), c.triggerKind(),
                c.goalId(), c.planVersion(), c.worldId(), c.dimension(), bucket);

        if (sameFingerprint != null) {
            LearningSample merged = sameFingerprint.withOccurrence(c.occurrence());
            boolean added = merged != sameFingerprint && merged.occurrenceCount() != sameFingerprint.occurrenceCount();
            String reason = added
                    ? "强指纹命中样本 " + sameFingerprint.sampleId() + "（同一时间桶、同目标、同归因），追加 occurrence"
                    : "强指纹命中样本 " + sameFingerprint.sampleId() + "，但同一条证据已记过，不重复计次";
            // 事件类型区分「又出现一次」与「同一条证据被重复投递」——
            // 160 §4 把这两件事分成 SAMPLE_OCCURRENCE_ADDED 与 SAMPLE_DEDUPED，
            // 混成一种，事后就算不出「去重救了多少次重复投递」。
            String evType = added ? SampleEvent.SAMPLE_OCCURRENCE_ADDED : SampleEvent.SAMPLE_DEDUPED;
            String occId = merged.occurrenceCount() > 0
                    ? merged.occurrences().get(merged.occurrenceCount() - 1).occurrenceId()
                    : c.occurrence().occurrenceId();
            SampleEvent ev = SampleEvent.of(evType, nowMillis,
                    sameFingerprint.runId(), SampleEvent.ACTOR_INTERNAL_LEARNER,
                    merged, occId, SampleEvent.refs(c.evidenceRef()));
            return new Decision(Outcome.MERGED, merged, sameFingerprint.sampleId(), reason, List.of(ev));
        }

        if (newSampleId == null || newSampleId.isBlank()) {
            throw new IllegalArgumentException("新建样本必须给 sampleId");
        }

        LearningSample created = LearningSample.create(
                newSampleId, c.companionId(), c.triggerKind(), c.triggerDetail(),
                c.causeSignature(), c.runId(), c.goalId(), c.planVersion(),
                c.worldId(), c.dimension(), bucket, fp, weak,
                c.source(), nowMillis, c.occurrence());

        if (sameWeak != null) {
            // 158 §6：判断不了是否相同 ⇒ 保留独立样本 + 标记；关联只用于「排障时能跳过去看」，
            // 不用于合并证据 —— 归因不同就是不同的事，证据混在一起会让两条结论都不可回查。
            LearningSample marked = created
                    .markPossibleDuplicate(sameWeak.sampleId())
                    .relatedTo(sameWeak.sampleId());
            // 疑似重复 = 创建 + 去重判定两件事都发生了，所以两条事件都要发：
            // 只发 SAMPLE_CREATED 会让人以为这是一次干净的新事件。
            SampleEvent createdEv = SampleEvent.of(SampleEvent.SAMPLE_CREATED, nowMillis,
                    c.runId(), SampleEvent.ACTOR_INTERNAL_LEARNER, marked,
                    c.occurrence().occurrenceId(), SampleEvent.refs(c.evidenceRef()));
            SampleEvent dedupEv = SampleEvent.of(SampleEvent.SAMPLE_DEDUPED, nowMillis,
                    c.runId(), SampleEvent.ACTOR_INTERNAL_LEARNER, marked,
                    c.occurrence().occurrenceId(), SampleEvent.refs(c.evidenceRef()));
            return new Decision(Outcome.POSSIBLE_DUPLICATE, marked, sameWeak.sampleId(),
                    "弱指纹命中样本 " + sameWeak.sampleId()
                            + "（同时间桶/同目标/同世界，但归因不同），按 158 §6 保留独立样本并标记",
                    List.of(createdEv, dedupEv));
        }

        SampleEvent createdEvent = SampleEvent.of(SampleEvent.SAMPLE_CREATED, nowMillis,
                c.runId(), SampleEvent.ACTOR_INTERNAL_LEARNER, created,
                c.occurrence().occurrenceId(), SampleEvent.refs(c.evidenceRef()));
        return new Decision(Outcome.NEW, created, "", "无同指纹、无同弱指纹，登记为新样本",
                List.of(createdEvent));
    }
}
