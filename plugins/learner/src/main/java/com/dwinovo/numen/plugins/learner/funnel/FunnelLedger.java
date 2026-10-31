package com.dwinovo.numen.plugins.learner.funnel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 检索漏斗账本（158 §3.4，159 H）。
 *
 * <h2>为什么必须另立一本账，而不是扩 {@code UsageLedger.Phase}</h2>
 * 161 §6.1 实测：`UsageLedger.Phase` 只有 PRESENTED/ADOPTED/EXECUTED/RESULT，
 * 且 `LateArrival` 对更早阶段<b>抛异常</b>。而漏斗的第 0/1 阶（APPLICABLE/RECALLED）
 * 在 PRESENTED <b>之前</b>，塞进同一个 enum 必然抛。
 *
 * <p>更深一层：那本账记的是"产物被用成什么样"，这本账记的是"检索为什么没走到"。
 * 硬合成一本，就会出现「因为没召回所以记成未执行」这种既不是失败也不是成功的怪物状态。
 *
 * <h2>为什么这一本允许乱序写、但不许重复写</h2>
 * 第 0 阶可能在事后才判定（世界状态刷新后），第 3 阶可能先于第 2 阶被记（直接从工具调用进入）。
 * 强制单调会把这些真实路径判成错误。但<b>同一 (条目, 阶段, 调用方, 发生键)</b> 只记一次 ——
 * 重复写会让漏斗计数翻倍，而漏斗计数正是判断"哪一阶在漏"的唯一依据。
 *
 * <h2>「停在哪一阶」是这本账的核心读数</h2>
 * {@link #whereStopped} 直接回答：这条能力是"未适用"、"适用未召回"、"召回未展开"、
 * "展开未采用"、"采用未执行"还是"执行无效"。用户在复盘时要的就是这一句。
 *
 * <p>纯 JVM、无 IO，可离线单测。
 */
public final class FunnelLedger {

    /** 谁触发的（学习者与执行者必须分开记：158 §3.4）。 */
    public enum Caller {
        LEARNER,
        EXECUTOR
    }

    /**
     * 一条漏斗记录。
     *
     * @param entryRef     被检索的条目（skillId@version 或 experience id）
     * @param occurrenceKey 同一条目在同一次任务里的第几次检索（默认空串）
     * @param reason       为什么停/为什么过（排除条件命中、预算裁掉……）
     */
    public record Entry(String entryRef, FunnelStage stage, Caller caller,
                        long at, String reason, String occurrenceKey) {
        public Entry {
            entryRef = entryRef == null ? "" : entryRef.trim();
            if (entryRef.isEmpty()) {
                throw new IllegalArgumentException("entryRef 不能为空");
            }
            if (stage == null || caller == null) {
                throw new IllegalArgumentException("阶段与调用方都不能为空");
            }
            reason = reason == null ? "" : reason;
            occurrenceKey = occurrenceKey == null ? "" : occurrenceKey.trim();
        }

        String key() {
            return entryRef + "|" + stage.name() + "|" + caller.name() + "|" + occurrenceKey;
        }
    }

    /**
     * 停止点读数。
     *
     * @param reached 已经到达的最高阶（没有任何记录时为 {@code null}）
     * @param missing 下一阶（即"停在哪"）；已走完 7 阶时为 {@code null}
     * @param verdict 一句话结论（给面板/回执）
     * @param gaps    比 {$reached} 更早、却没有任何记录的阶（阶梯断点）
     */
    public record Stop(FunnelStage reached, FunnelStage missing, String verdict, List<FunnelStage> gaps) {
        public Stop {
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
        }

        /** 无断点视图（老调用点用）。 */
        public Stop(FunnelStage reached, FunnelStage missing, String verdict) {
            this(reached, missing, verdict, List.of());
        }

        public boolean hasGaps() {
            return !gaps.isEmpty();
        }
    }

    private final Map<String, Entry> byKey = new LinkedHashMap<>();
    private int duplicates;

    /** 记一条。返回 {@code false} 表示这条已经记过（幂等，不重复计数）。 */
    public synchronized boolean record(Entry e) {
        if (e == null) {
            return false;
        }
        if (byKey.containsKey(e.key())) {
            duplicates++;
            return false;
        }
        byKey.put(e.key(), e);
        return true;
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(byKey.values());
    }

    /** 被幂等挡下的重复写入次数（诚实计数）。 */
    public synchronized int duplicates() {
        return duplicates;
    }

    /** 某条目某调用方已经到达过的阶段集合。 */
    public synchronized Set<FunnelStage> stagesOf(String entryRef, Caller caller) {
        Set<FunnelStage> out = new LinkedHashSet<>();
        for (Entry e : byKey.values()) {
            if (e.entryRef().equals(entryRef) && e.caller() == caller) {
                out.add(e.stage());
            }
        }
        return out;
    }

    /**
     * 停在哪一阶。
     *
     * <p>判据是「最高到达阶的下一阶」。第 0 阶都没有记录时返回 `null` 并说明"没有判定记录"——
     * 那是"没跑过"，不是"不适用"，两者不能混。
     */
    public synchronized Stop whereStopped(String entryRef, Caller caller) {
        Set<FunnelStage> stages = stagesOf(entryRef, caller);
        if (stages.isEmpty()) {
            return new Stop(null, FunnelStage.APPLICABLE, "no_record：这条还没有任何检索记录");
        }
        FunnelStage highest = null;
        for (FunnelStage s : stages) {
            if (highest == null || s.rank() > highest.rank()) {
                highest = s;
            }
        }
        // 第 0 阶可能是**否定结论**（排除条件命中 = 这条这次不适用）。
        // 那种情况下"停在哪一阶"的正确回答是"不适用"，而不是"适用未召回"——
        // 后者会让人去查检索，而真正该改的是这条的触发/排除条件。
        String denyReason = "";
        for (Entry e : byKey.values()) {
            if (e.entryRef().equals(entryRef) && e.caller() == caller
                    && e.stage() == FunnelStage.APPLICABLE
                    && !e.reason().isBlank() && !"applicable".equals(e.reason())) {
                denyReason = e.reason();
            }
        }
        if (!denyReason.isBlank()) {
            return new Stop(FunnelStage.APPLICABLE, FunnelStage.RECALLED,
                    "不适用（" + denyReason + "）：检索不会再看到它，该改的是触发/排除条件");
        }

        FunnelStage missing = highest.next();
        // 措辞按"缺失的那一阶"命名，不按"已到达的那一阶"——
        // 「召回未展开」既可能指停在 RECALLED 也可能指停在 PRESENTED，含糊的措辞在复盘时会被读错。
        String verdict = switch (missing) {
            case null -> "reached_end：七阶全部有记录";
            case APPLICABLE -> "未判定适用性";
            case RECALLED -> "适用未召回";
            case PRESENTED -> "已召回未呈现";
            case EXPANDED -> "已呈现未展开";
            case ADOPTED -> "已展开未采用";
            case EXECUTED -> "已采用未执行";
            case EFFECT -> "已执行未见效果";
        };
        // 阶梯断点：比最高阶更早、却没有任何记录的阶。
        // 为什么不改 verdict：措辞会随"缺不缺阶"抖动，读的人分不清是出问题还是本来如此；
        // 把断点单列成字段，面板可以只在需要时突出它。
        List<FunnelStage> gaps = new ArrayList<>();
        if (highest != null) {
            for (FunnelStage s : FunnelStage.values()) {
                if (s.rank() < highest.rank() && !stages.contains(s)) {
                    gaps.add(s);
                }
            }
        }
        return new Stop(highest, missing, verdict, gaps);
    }

    /** 某阶段某调用方的计数（面板读数）。 */
    public synchronized int countAt(FunnelStage stage, Caller caller) {
        int n = 0;
        for (Entry e : byKey.values()) {
            if (e.stage() == stage && e.caller() == caller) {
                n++;
            }
        }
        return n;
    }

    /** 全部阶段计数（按阶段顺序，便于直接渲染漏斗）。 */
    public synchronized List<String> funnelLine() {
        List<String> out = new ArrayList<>();
        for (FunnelStage s : FunnelStage.values()) {
            out.add(s.name() + "=" + countAt(s, Caller.EXECUTOR) + "/" + countAt(s, Caller.LEARNER));
        }
        return out;
    }
}
