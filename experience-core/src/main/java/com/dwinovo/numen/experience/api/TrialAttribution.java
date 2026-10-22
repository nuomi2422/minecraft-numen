package com.dwinovo.numen.experience.api;

import java.util.ArrayList;
import java.util.List;

/**
 * G6c：把「这条经验被端到她面前」和「紧接着那次任务成没成」缝成一条 {@link TrialUse}。
 *
 * <h2>为什么要这一步</h2>
 * 呈现（进了执行请求）不等于被用；被用也不等于有用。而运行时目前**没有**「谁用了它」的接线，
 * 所以试用账只能**从事件派生**：呈现时刻 + 随后第一个任务结局。
 *
 * <h2>归因口径（写死）</h2>
 * <ul>
 *   <li>一条呈现，只认它之后 {@link #WINDOW_MILLIS} 内的**第一个**任务结局；</li>
 *   <li>窗口内没有任务收尾 → {@code INCONCLUSIVE}（用了没？不知道 —— 不许当成成功）；</li>
 *   <li>同一个任务结局只归给**最近一次**呈现，避免一条经验吃掉别人带来的成功。</li>
 * </ul>
 *
 * <p>这是**保守**归因：宁可算不清，也不要虚报成功。试用判定的价值全在「它会不会骗人」。
 */
public final class TrialAttribution {

    /** 呈现之后多久之内的任务算「由它陪跑」。两分钟：够一次常规任务，又不会把半小时后的事算进来。 */
    public static final long WINDOW_MILLIS = 120_000L;

    /** 一次呈现：某条经验在某时刻出现在执行请求里。 */
    public record Presentation(String entryRef, long millis) {}

    /** 一次任务收尾。 */
    public record TaskOutcome(long millis, boolean success) {}

    private TrialAttribution() {}

    /**
     * @param presentations 按时间升序的所有呈现
     * @param outcomes      按时间升序的所有任务收尾
     * @return 每个「呈现」对应的一条使用（顺序与 presentations 一致）
     */
    public static List<TrialUse> attribute(List<Presentation> presentations, List<TaskOutcome> outcomes) {
        List<TrialUse> out = new ArrayList<>();
        if (presentations == null || presentations.isEmpty()) {
            return out;
        }
        List<TaskOutcome> tasks = outcomes == null ? List.of() : outcomes;
        java.util.Set<Integer> consumed = new java.util.HashSet<>();
        for (Presentation p : presentations) {
            int hit = -1;
            for (int i = 0; i < tasks.size(); i++) {
                if (consumed.contains(i)) continue;
                TaskOutcome t = tasks.get(i);
                if (t.millis() < p.millis()) continue;                 // 呈现之前结束的任务不算
                if (t.millis() - p.millis() > WINDOW_MILLIS) break;    // 超出窗口，后面的更远
                if (p.entryRef() == null || p.entryRef().isBlank()) break;
                hit = i;
                break;
            }
            if (hit < 0) {
                out.add(new TrialUse(p.entryRef(), p.millis(), TrialUse.Outcome.INCONCLUSIVE));
                continue;
            }
            consumed.add(hit);
            TaskOutcome t = tasks.get(hit);
            out.add(new TrialUse(p.entryRef(), p.millis(),
                    t.success() ? TrialUse.Outcome.SUCCESS : TrialUse.Outcome.FAILURE));
        }
        return out;
    }
}
