package com.dwinovo.numen.plugins.experience;

import com.dwinovo.numen.experience.api.TrialAttribution;
import com.dwinovo.numen.experience.api.TrialStats;
import com.dwinovo.numen.experience.api.UsageLedger;
import com.dwinovo.numen.experience.api.UsageSample;
import com.dwinovo.numen.experience.api.UsageState;
import com.dwinovo.numen.experience.api.UsageStats;
import com.dwinovo.numen.experience.api.TrialUse;
import com.dwinovo.numen.experience.api.TrialVerdict;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * G6c：试用期的**运行时归因**。
 *
 * <p>把两件事接起来：
 * <ol>
 *   <li>这一轮执行请求里，哪些**试用中**的条目真的被端出来了（{@link #notePresented}）；</li>
 *   <li>紧接着任务收尾成没成（{@code CompanionEvent.TASK_FINISHED} → {@link #onTaskFinished}）。</li>
 * </ol>
 * 归因规则本身在 {@link TrialAttribution}（纯函数、已单测），本类只负责「记住 + 喂给它 + 记账」。
 *
 * <h2>为什么不落盘</h2>
 * 账从事件派生（C2 的口径）。这里的内存队列只是「最近若干次呈现」的暂存，
 * 掉电丢失的代价是「少一条归因」，不是「记错了」。真账随时可以从 monitor 事件重算。
 *
 * <h2>并发</h2>
 * 呈现发生在客户端渲染线程，任务收尾在服务端 tick 线程 —— 两边都调本类，
 * 所以方法都 {@code synchronized}，且只做 O(几十) 的小操作。
 */
public final class TrialRuntime {

    /** 每个同伴最多记这么多条呈现（够覆盖 120 秒窗口就行；再多只会浪费）。 */
    static final int MAX_PENDING = 64;

    /** 渲染块里「试用中」条目的 ref：`ref="exp:…" ...>（试用中）标题`。 */
    private static final Pattern TRIAL_REF = Pattern.compile("ref=\"(exp:[^\"]+)\"[^>]*>\\s*（试用中）");

    private static final Map<UUID, Deque<TrialAttribution.Presentation>> PENDING = new LinkedHashMap<>();
    private static final Map<String, List<TrialUse>> USES = new LinkedHashMap<>();
    /** G6a-ledger：同一批收尾事实的**五态**账（TrialUse 三态是给判定用的，这里是给 IC11 用的）。 */
    private static final Map<String, List<UsageSample>> USAGE = new LinkedHashMap<>();

    private TrialRuntime() {}

    /** 从渲染好的执行知识里挑出「试用中」的条目 ref（顺序即出现顺序）。 */
    public static List<String> trialRefsIn(String renderedText) {
        List<String> out = new ArrayList<>();
        if (renderedText == null || renderedText.isBlank()) {
            return out;
        }
        Matcher m = TRIAL_REF.matcher(renderedText);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** 记一次呈现（没给 ref 就不记 —— 没有归因对象）。 */
    public static synchronized void notePresented(UUID companion, List<String> refs, long nowMillis) {
        if (companion == null || refs == null || refs.isEmpty()) {
            return;
        }
        Deque<TrialAttribution.Presentation> q = PENDING.computeIfAbsent(companion, k -> new ArrayDeque<>());
        for (String ref : refs) {
            if (ref == null || ref.isBlank()) continue;
            q.addLast(new TrialAttribution.Presentation(ref, nowMillis));
            while (q.size() > MAX_PENDING) {
                q.removeFirst();
            }
        }
    }

    /**
     * 任务收尾：把「窗口内的呈现」按保守口径归因，并记账。
     *
     * @return 这次收尾归因出来的使用（没人认领就是空列表）
     */
    public static synchronized List<TrialUse> onTaskFinished(UUID companion, boolean success, long nowMillis) {
        // 兼容旧调用方：只有布尔时，成功=SUCCESS、失败=FAILURE（取消/中断拿不到，见下面的重载）
        return onTaskFinished(companion, success ? UsageState.SUCCESS : UsageState.FAILURE, "", nowMillis);
    }

    /**
     * G6a-ledger：任务收尾（带终态与产物 id）。
     *
     * <p>与布尔版相比多两件事：①终态**五态**入账（取消/中断不再并进失败）；②带 artifactId，
     * 于是「同一产物先取消后成功」在账里是两个 attempt，不会被串成一条结论。
     */
    public static synchronized List<TrialUse> onTaskFinished(UUID companion, UsageState state,
                                                           String artifactId, long nowMillis) {
        UsageState st = state == null ? UsageState.UNKNOWN : state;
        String artifact = artifactId == null ? "" : artifactId.trim();
        if (companion == null) {
            return List.of();
        }
        Deque<TrialAttribution.Presentation> q = PENDING.get(companion);
        if (q == null || q.isEmpty()) {
            return List.of();
        }
        // 只有「窗口内」的呈现还值得算；更早的已经过期，直接丢掉（它们各自已经算过 INCONCLUSIVE）
        List<TrialAttribution.Presentation> window = new ArrayList<>();
        while (!q.isEmpty()) {
            TrialAttribution.Presentation p = q.peekFirst();
            if (nowMillis - p.millis() > TrialAttribution.WINDOW_MILLIS) {
                q.removeFirst();
                record(new TrialUse(p.entryRef(), p.millis(), TrialUse.Outcome.INCONCLUSIVE));
                // 过期 = 没等到终态 ⇒ UNKNOWN（IC11：宁记「不知道」也不许丢）
                recordUsage(p.entryRef(), artifact, UsageState.UNKNOWN, p.millis());
                continue;
            }
            window.add(p);
            q.removeFirst();
        }
        if (window.isEmpty()) {
            return List.of();
        }
        List<TrialAttribution.TaskOutcome> outcomes =
                List.of(new TrialAttribution.TaskOutcome(nowMillis, st == UsageState.SUCCESS));
        List<TrialUse> uses = TrialAttribution.attribute(window, outcomes);
        for (TrialUse u : uses) {
            record(u);
            // 被认领的那条用真实终态；没被认领的（INCONCLUSIVE）记 UNKNOWN —— 两条事实各记各的
            recordUsage(u.entryRef(), artifact,
                    u.outcome() == TrialUse.Outcome.INCONCLUSIVE ? UsageState.UNKNOWN : st, nowMillis);
        }
        return uses;
    }

    /** 记一条五态样本：attempt 按「同 ref + 同产物」已有条数 +1（先取消后成功 = 两个 attempt）。 */
    private static void recordUsage(String entryRef, String artifactId, UsageState state, long atMillis) {
        List<UsageSample> list = USAGE.computeIfAbsent(entryRef, k -> new ArrayList<>());
        int attempt = 1;
        for (UsageSample s : list) {
            if (s.artifactId().equals(artifactId == null ? "" : artifactId.trim())) {
                attempt++;
            }
        }
        list.add(new UsageSample(entryRef, attempt, "", artifactId, "", state, atMillis));
    }

    /** G6a-ledger：某条经验的五态账（IC11 口径）。 */
    public static synchronized UsageStats usageStats(String entryRef) {
        return UsageLedger.derive(entryRef, (String) null, USAGE.getOrDefault(entryRef, List.of()));
    }

    private static void record(TrialUse use) {
        USES.computeIfAbsent(use.entryRef(), k -> new ArrayList<>()).add(use);
    }

    /** 某条经验到目前为止的试用账（测试与观测用）。 */
    public static synchronized TrialStats stats(String entryRef) {
        return TrialUse.derive(entryRef, USES.getOrDefault(entryRef, List.of()));
    }

    /** 某条经验当前的试用判定（只是建议）。 */
    public static synchronized TrialVerdict.Decision verdict(String entryRef) {
        return TrialVerdict.decide(stats(entryRef));
    }

    /** 清空（只给测试用；运行时没有「清零试用账」这回事）。 */
    static synchronized void resetForTest() {
        PENDING.clear();
        USES.clear();
    }
}
