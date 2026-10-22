package com.dwinovo.numen.experience.api;

import java.util.List;

/**
 * G6c 判据 C2：**从现有事件派生试用账，不新增台账**。
 *
 * <p>「用了一次」不是一句印象，而是一条可核对的事实：某条经验在某个时刻被某次任务用到、
 * 那次任务最后成了没有。这里只做**归约**（一串使用 → 四个数），
 * 数据从哪儿来由调用方决定（现在来自 `monitor/*.jsonl`，将来可以来自别的源）。
 *
 * <h2>为什么不在库里加计数器</h2>
 * 计数器一旦存下来就会和真实事件对不上（谁负责回滚？补偿？），
 * 而事件是只增不改的事实。派生随时可重算，这比一个会漂的计数器可靠。
 *
 * <h2>取用口径</h2>
 * <ul>
 *   <li>成功 = 那次任务收尾是 done/success；</li>
 *   <li>失败 = failed / timeout（<b>取消不算失败</b>，算无效）；</li>
 *   <li>其余（没有对应任务、任务还在跑）= 无效 —— 既不加成功也不加失败。</li>
 * </ul>
 */
public record TrialUse(String entryRef, long millis, Outcome outcome) {

    /** 一次使用的结局。 */
    public enum Outcome {
        SUCCESS,
        FAILURE,
        /** 取消/中断/没有对应任务 —— 既不算成功也不算失败。 */
        INCONCLUSIVE
    }

    public TrialUse {
        entryRef = entryRef == null ? "" : entryRef.trim();
    }

    /**
     * 归约：只数给定 ref 的那些使用。
     *
     * @param ref  要看的那条经验
     * @param uses 全部使用事件（可以是别的条目的，这里会过滤）
     */
    public static TrialStats derive(String ref, List<TrialUse> uses) {
        if (ref == null || uses == null || uses.isEmpty()) {
            return TrialStats.EMPTY;
        }
        int total = 0;
        int ok = 0;
        int bad = 0;
        long last = 0L;
        for (TrialUse u : uses) {
            if (u == null || !ref.equals(u.entryRef())) continue;
            total++;
            if (u.millis() > last) last = u.millis();
            if (u.outcome() == Outcome.SUCCESS) ok++;
            else if (u.outcome() == Outcome.FAILURE) bad++;
        }
        return new TrialStats(total, ok, bad, last);
    }
}
