package com.dwinovo.numen.plugins.learner.sample;

import java.util.Objects;

/**
 * 样本的一次「出现」记录（158 §3.1 / §6）。
 *
 * <h2>为什么 occurrence 必须独立成一条，而不是把样本计数加一</h2>
 * 158 §6 要求「同一对象重复上报时只追加 occurrence，<b>保留最早和最近证据</b>」。
 * 如果只记一个 {@code reportCount}，最早那次发生在什么时间、凭哪条证据、
 * 当时环境如何，全都不可回查 —— 而「最早一次」恰恰是判断「这个问题稳态还是偶发」
 * 的唯一依据。所以这里是完整的一条，不是计数器。
 *
 * <h2>为什么不去重 occurrence 的内容</h2>
 * 同一条证据被重复上报两次（比如 intake 重试）不应该变成两条 occurrence ——
 * 那会让「复发次数」虚高，进而把普通经验误升成高频模式。
 * 去重键用 {@code (evidenceRef, occurredAt)}，见 {@link LearningSample#withOccurrence}。
 *
 * @param evidenceRef 指向原始证据的引用（instrumentation event_id / tools.jsonl 行 / context.jsonl 记录）
 * @param occurredAt  该事件在游戏侧发生的时间（毫秒 epoch）
 * @param source      来源通道（{@code instrumentation} / {@code auto_intake} / {@code manual_intake} / {@code harness}）
 * @param note        这一条与其它条的不同之处（可为空；空也要能区分「没写」与「写了空」）
 */
public record SampleOccurrence(String evidenceRef, long occurredAt, String source, String note) {

    public SampleOccurrence {
        evidenceRef = evidenceRef == null ? "" : evidenceRef.trim();
        source = source == null ? "" : source.trim();
        note = note == null ? "" : note;
    }

    public SampleOccurrence(String evidenceRef, long occurredAt, String source) {
        this(evidenceRef, occurredAt, source, "");
    }

    /** 去重键。同证据同时间视为同一条出现。 */
    public String dedupeKey() {
        return evidenceRef + "@" + occurredAt;
    }

    /**
     * 稳定出现 id（160 §3 的最小关联键 {@code occurrenceId}）。
     *
     * <p>为什么是派生而不是存一个字段：occurrence 的身份严格由「哪条证据 + 什么时候」决定，
     * 再存一个随机 id 就多了一处可以和身份不一致的地方 —— 而 161 §6.4 记的正是
     * 「同一份数据两处来源，改一处漏一处」那类断链。派生 id 不可能与身份漂移。
     *
     * <p>同一证据重复投递得到同一个 occurrenceId，因此事件层天然幂等。
     */
    public String occurrenceId() {
        return "oc-" + SampleFingerprint.shortHash(dedupeKey());
    }

    public boolean sameAs(SampleOccurrence other) {
        return other != null && dedupeKey().equals(other.dedupeKey());
    }
}
