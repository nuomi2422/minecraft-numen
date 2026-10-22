package com.dwinovo.numen.experience.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * G6a/IC11 的**纯 reducer**：一串 {@link UsageSample} → 一份 {@link UsageStats}。
 *
 * <p><b>四条纪律</b>（对应 IC11 四个分句）：
 * <ol>
 *   <li><b>五态都入账</b>：SUCCESS/FAILURE/CANCELED/INTERRUPTED/UNKNOWN 各记各的，UNKNOWN 不许静默丢；</li>
 *   <li><b>重复投递不双计</b>：同一 {@code (entryRef, attempt, phase, artifactId)} 再报一次只留第一条，计入 {@code duplicates}；</li>
 *   <li><b>同产物先取消后成功不串结论</b>：两个 attempt 都留在明细（{@code canceled} 计数仍在），
 *       但 {@code conclusion} 只看**最大 attempt**；{@code hadCancel} 单独记，两条事实并存；</li>
 *   <li><b>缺版本不按名字猜</b>：统计按 {@code version} 过滤，版本对不上的条数进 {@code excludedByVersion}。</li>
 * </ol>
 *
 * <p>不做 IO、不持有状态 —— 与 {@code TrialUse} 同一哲学：事实在事件里，结论随时可重算。
 */
public final class UsageLedger {

    private UsageLedger() {
    }

    /** 一批样本里出现过的版本（去重、排序；空版本也列出，便于调用方决定用哪个）。 */
    public static Set<String> versions(String ref, List<UsageSample> samples) {
        Set<String> out = new TreeSet<>();
        if (samples == null) return out;
        for (UsageSample s : samples) {
            if (s != null && s.entryRef().equals(safe(ref))) {
                out.add(s.version());
            }
        }
        return out;
    }

    /**
     * 派生一份账。
     *
     * @param ref     哪条经验（空串表示「全部」——只在调试/总量时用）
     * @param version 只统计这个内容版本；传 {@code null} 表示**不按版本过滤**（调用方明确接受混合）
     * @param samples 全部样本（可以是别的条目/版本的，这里会过滤）
     */
    public static UsageStats derive(String ref, String version, List<UsageSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return UsageStats.EMPTY;
        }
        String wantRef = safe(ref);
        String wantVersion = version == null ? null : version.trim();

        List<UsageSample> kept = new ArrayList<>();
        int duplicates = 0;
        int excludedByVersion = 0;

        for (UsageSample s : samples) {
            if (s == null) continue;
            if (!wantRef.isEmpty() && !s.entryRef().equals(wantRef)) continue;
            if (wantVersion != null && !s.version().equals(wantVersion)) {
                excludedByVersion++;
                continue;
            }
            boolean dup = false;
            for (UsageSample k : kept) {
                if (k.sameDelivery(s)) {
                    dup = true;
                    break;
                }
            }
            if (dup) {
                duplicates++;
            } else {
                kept.add(s);
            }
        }
        if (kept.isEmpty()) {
            return new UsageStats(0, 0, 0, 0, 0, duplicates, excludedByVersion,
                    UsageState.UNKNOWN, -1, false);
        }

        int success = 0, failure = 0, canceled = 0, interrupted = 0, unknown = 0;
        boolean hadCancel = false;
        UsageSample last = kept.get(0);
        for (UsageSample s : kept) {
            switch (s.state()) {
                case SUCCESS -> success++;
                case FAILURE -> failure++;
                case CANCELED -> {
                    canceled++;
                    hadCancel = true;
                }
                case INTERRUPTED -> interrupted++;
                case UNKNOWN -> unknown++;
            }
            // 结论取「最大 attempt」；attempt 相同再取更晚的时间（稳定、可复现）
            if (s.attempt() > last.attempt()
                    || (s.attempt() == last.attempt() && s.atMillis() > last.atMillis())) {
                last = s;
            }
        }
        return new UsageStats(success, failure, canceled, interrupted, unknown,
                duplicates, excludedByVersion, last.state(), last.attempt(), hadCancel);
    }

    /** 便利：同版本、只按 ref 派生（版本取该 ref 出现最多的那个；并列取字典序最小，稳定）。 */
    public static UsageStats derive(String ref, List<UsageSample> samples) {
        if (samples == null || samples.isEmpty()) return UsageStats.EMPTY;
        String wantRef = safe(ref);
        List<String> vs = new ArrayList<>(versions(wantRef, samples));
        if (vs.isEmpty()) return UsageStats.EMPTY;
        String best = vs.get(0);
        int bestCount = -1;
        for (String v : vs) {
            int c = 0;
            for (UsageSample s : samples) {
                if (s != null && s.entryRef().equals(wantRef) && s.version().equals(v)) c++;
            }
            if (c > bestCount) {
                bestCount = c;
                best = v;
            }
        }
        return derive(wantRef, best, samples);
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
