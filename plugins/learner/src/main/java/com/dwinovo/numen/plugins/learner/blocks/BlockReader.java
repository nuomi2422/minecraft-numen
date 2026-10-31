package com.dwinovo.numen.plugins.learner.blocks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 环境块读取器：{@code sample_open / block_list / block_expand / timeline_next / timeline_previous}
 * 的<strong>唯一实现</strong>（159 D 段）。
 *
 * <h2>为什么这些原语要收在一个类里，而不是各给一个工具</h2>
 * 158 §4 要的不是五个独立工具，而是<b>一条可回查的读取轨迹</b>：
 * 打开 → 列目录 → 展开几块 → 前后翻页。如果每个动作各自实现，
 * 「展开之前有没有列过目录」「翻页用的是哪个锚点」就没人统一记账，
 * 复盘时无法回答「它当时到底看了什么」。
 *
 * <h2>三条硬规矩</h2>
 * <ol>
 *   <li><b>目录不带正文</b>：{@link #list()} 返回 {@code summaryOnly()}。
 *       目录塞正文等于每次复盘都把整份环境塞进上下文（161 §6.6 的必知区爆炸）。</li>
 *   <li><b>失败也要记账</b>：展开一个不存在的块要落 {@code BLOCK_EXPAND_FAILED}，
 *       而不是静静返回空 —— 否则「它看过但没有」和「它想看看不到」就分不开了。</li>
 *   <li><b>没展开 = 没读</b>：{@link #endSession} 给所有未展开的块补记 {@code BLOCK_SKIP}。
 *       这条让"我当时看到了"这种说法第一次变得可核验。</li>
 * </ol>
 *
 * <h2>它不做什么</h2>
 * 不采集环境事实（那是 worldstate / context.jsonl 的事）、不判断块内容对不对、
 * 不生成 {@code DecisionContextBlock}（那是执行侧写入的事实，学习者只能读）。
 *
 * <p>纯 JVM、无 IO、时钟由调用方传入，可离线单测。
 */
public final class BlockReader {

    private final String sampleId;
    private final ReadLedger ledger;
    private final List<EnvBlock> ordered = new ArrayList<>();
    private final Map<String, EnvBlock> byId = new LinkedHashMap<>();
    private final Set<String> expanded = new HashSet<>();
    private boolean ended;

    private BlockReader(String sampleId, List<EnvBlock> blocks, ReadLedger ledger) {
        this.sampleId = sampleId;
        this.ledger = ledger == null ? new ReadLedger() : ledger;
        if (blocks != null) {
            for (EnvBlock b : blocks) {
                if (b == null) {
                    continue;
                }
                if (byId.containsKey(b.blockId())) {
                    // 重复 blockId 是上游 bug：静默覆盖会让"读到的是哪一块"永远说不清。
                    throw new IllegalArgumentException("重复的 blockId: " + b.blockId());
                }
                byId.put(b.blockId(), b);
                ordered.add(b);
            }
        }
    }

    /**
     * 打开一个样本的读取会话。
     *
     * @param blocks 该样本的全部块（顺序即时间线顺序）
     */
    public static BlockReader open(String sampleId, List<EnvBlock> blocks, ReadLedger ledger, long nowMillis) {
        if (sampleId == null || sampleId.isBlank()) {
            throw new IllegalArgumentException("sampleId 不能为空");
        }
        BlockReader r = new BlockReader(sampleId, blocks, ledger);
        r.ledger.record(ReadLedger.CALLER_LEARNER, ReadLedger.Action.SAMPLE_OPEN, "",
                nowMillis, "sample=" + sampleId + " blocks=" + r.ordered.size());
        return r;
    }

    public String sampleId() {
        return sampleId;
    }

    public ReadLedger ledger() {
        return ledger;
    }

    public int blockCount() {
        return ordered.size();
    }

    /** 列目录：只给摘要，不带正文。 */
    public synchronized List<EnvBlock> list(long nowMillis) {
        checkNotEnded("list");
        ledger.record(ReadLedger.CALLER_LEARNER, ReadLedger.Action.BLOCK_LIST, "",
                nowMillis, "count=" + ordered.size());
        List<EnvBlock> out = new ArrayList<>(ordered.size());
        for (EnvBlock b : ordered) {
            out.add(b.summaryOnly());
        }
        return out;
    }

    /** 展开正文。找不到就记账后返回空，<b>不伪造一个空块</b>。 */
    public synchronized Optional<EnvBlock> expand(String blockId, long nowMillis) {
        checkNotEnded("expand");
        EnvBlock b = blockId == null ? null : byId.get(blockId.trim());
        if (b == null) {
            ledger.record(ReadLedger.CALLER_LEARNER, ReadLedger.Action.BLOCK_EXPAND_FAILED,
                    blockId == null ? "" : blockId, nowMillis, "no_such_block");
            return Optional.empty();
        }
        expanded.add(b.blockId());
        ledger.record(ReadLedger.CALLER_LEARNER, ReadLedger.Action.BLOCK_EXPAND, b.blockId(),
                nowMillis, "kind=" + b.kind().name());
        return Optional.of(b);
    }

    /** 时间线向后（更新方向）翻一页，返回下一个块 id。 */
    public synchronized Optional<String> timelineNext(String fromBlockId, long nowMillis) {
        return step(fromBlockId, nowMillis, ReadLedger.Action.TIMELINE_NEXT, +1);
    }

    /** 时间线向前（更早方向）翻一页。 */
    public synchronized Optional<String> timelinePrevious(String fromBlockId, long nowMillis) {
        return step(fromBlockId, nowMillis, ReadLedger.Action.TIMELINE_PREVIOUS, -1);
    }

    private Optional<String> step(String fromBlockId, long nowMillis, ReadLedger.Action action, int delta) {
        checkNotEnded(action.name());
        int idx = indexOf(fromBlockId);
        if (idx < 0) {
            // 锚点不存在也要留痕：否则"翻页失败"与"翻页到底"分不开。
            ledger.record(ReadLedger.CALLER_LEARNER, action, fromBlockId == null ? "" : fromBlockId,
                    nowMillis, "anchor_missing");
            return Optional.empty();
        }
        int next = idx + delta;
        boolean inRange = next >= 0 && next < ordered.size();
        String target = inRange ? ordered.get(next).blockId() : "";
        ledger.record(ReadLedger.CALLER_LEARNER, action, target, nowMillis,
                "from=" + ordered.get(idx).blockId() + (inRange ? "" : " eof"));
        return inRange ? Optional.of(target) : Optional.empty();
    }

    /**
     * 结束会话：给<b>所有从未展开</b>的块补记 {@code BLOCK_SKIP}。
     *
     * <p>为什么必须机械补记，而不是等复盘模型自报"我跳过了 X"：
     * 自报会被顺手写成"我看过了"，而账本是唯一能反驳它的东西。
     * 只记一次（重复调用不会重复补记）。
     */
    public synchronized void endSession(long nowMillis) {
        if (ended) {
            return;
        }
        ended = true;
        for (EnvBlock b : ordered) {
            if (!expanded.contains(b.blockId())) {
                ledger.record(ReadLedger.CALLER_LEARNER, ReadLedger.Action.BLOCK_SKIP, b.blockId(),
                        nowMillis, "not_expanded");
            }
        }
    }

    public synchronized boolean ended() {
        return ended;
    }

    /** 已展开的块 id（供复盘时对照证据引用）。 */
    public synchronized List<String> expandedIds() {
        List<String> out = new ArrayList<>();
        for (EnvBlock b : ordered) {
            if (expanded.contains(b.blockId())) {
                out.add(b.blockId());
            }
        }
        return out;
    }

    private int indexOf(String blockId) {
        if (blockId == null) {
            return -1;
        }
        String id = blockId.trim();
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).blockId().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private void checkNotEnded(String op) {
        if (ended) {
            // 会话结束后还读，会得到一份账本里没有的"读"，等于伪造证据。
            throw new IllegalStateException("读取会话已结束，不能再 " + op);
        }
    }
}
