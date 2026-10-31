package com.dwinovo.numen.plugins.learner.blocks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * 学习者的<strong>读取账本</strong>（158 §4 末段 / 159 D 段补记）。
 *
 * <h2>它记的不是"读了什么"，而是"没读什么"</h2>
 * 158 §4 原文：`Learner 自己展开、跳过和翻页的行为也进入账本`。
 * 只记「展开了哪些块」是不够的 —— 复盘时最常被含糊过去的一句是
 * 「当时我看到它在岩浆边上」，而真实情况可能是它只展开了背包、压根没打开位置块。
 * 所以这里的核心规则是：<b>没记展开 = 没读</b>，并由 {@link BlockReader#endSession} 机械地补记跳过。
 *
 * <h2>为什么和 DecisionContext 分开</h2>
 * {@code DecisionContextBlock} 记的是<b>执行者当时收到了什么</b>，
 * 本账本记的是<b>学习者后来读了什么</b>。两者混在一本账里，就会出现
 * 「学习者读了完整日志，于是认为执行者也看到了」—— 这正是 158 §4 明令禁止的推理。
 * 所以是两本账、两个入口，任何一方都不能写另一方。
 *
 * <h2>有界</h2>
 * 与 {@code REVIEWS 32 条} 同一纪律：长会话里无界账本会吃内存，且旧条目永远不会再被看。
 *
 * <p>纯 JVM、无 IO、可离线单测。
 */
public final class ReadLedger {

    /**
     * 学习者身份的调用方标记。
     *
     * <p>与 {@link com.dwinovo.numen.plugins.learner.sample.SampleEvent#ACTOR_INTERNAL_LEARNER} 同值。
     * 故意不直接引用那个常量：账本不该因为事件类改名而编译失败。
     * 代价是可能漂移 —— 由 {@code ReadLedgerTest.调用方标记必须与事件层一致} 钉住。
     */
    public static final String CALLER_LEARNER = "learner:internal";

    /** 每个账本最多留这么多条（超出丢最旧）。 */
    public static final int MAX_ENTRIES = 512;

    /** 读取动作。 */
    public enum Action {
        /** 打开一个样本（拿到目录与索引）。 */
        SAMPLE_OPEN,
        /** 列块目录。 */
        BLOCK_LIST,
        /** 展开某块的正文。 */
        BLOCK_EXPAND,
        /** 想展开但块不存在 / 不可读（<b>必须记</b>：失败也要留痕）。 */
        BLOCK_EXPAND_FAILED,
        /** 会话结束时仍未展开的块（"没读"的机械留痕）。 */
        BLOCK_SKIP,
        /** 时间线向后翻。 */
        TIMELINE_NEXT,
        /** 时间线向前翻。 */
        TIMELINE_PREVIOUS
    }

    /**
     * 一条读取记录。
     *
     * @param caller 谁在读（学习者 vs 执行者，绝不混）
     * @param note   补充说明（锚点、失败原因、总数）
     */
    public record Entry(String caller, Action action, String blockId, long at, String note) {
        public Entry {
            caller = caller == null || caller.isBlank() ? "UNKNOWN" : caller.trim();
            Objects.requireNonNull(action, "action");
            blockId = blockId == null ? "" : blockId.trim();
            note = note == null ? "" : note;
        }
    }

    private final Deque<Entry> entries = new ArrayDeque<>();
    private int dropped;

    public ReadLedger() {
    }

    public synchronized void record(String caller, Action action, String blockId, long at, String note) {
        entries.addLast(new Entry(caller, action, blockId, at, note));
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
            dropped++;
        }
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    public synchronized List<Entry> entriesOf(Action action) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.action() == action) {
                out.add(e);
            }
        }
        return out;
    }

    public synchronized int countOf(Action action) {
        int n = 0;
        for (Entry e : entries) {
            if (e.action() == action) {
                n++;
            }
        }
        return n;
    }

    /** 因超出上限被丢弃的条数（诚实计数：账本不是无限账）。 */
    public synchronized int dropped() {
        return dropped;
    }

    public synchronized void clear() {
        entries.clear();
        dropped = 0;
    }
}
