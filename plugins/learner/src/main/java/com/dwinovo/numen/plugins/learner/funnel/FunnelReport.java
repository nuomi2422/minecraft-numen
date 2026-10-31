package com.dwinovo.numen.plugins.learner.funnel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 漏斗面板的<strong>纯渲染</strong>（给 `learner_funnel` 工具用）。
 *
 * <h2>为什么单独一个类</h2>
 * 工具类是 {@code NumenTool}，要 MC 运行时才能跑，等于这段逻辑一行都测不到 ——
 * 而它恰恰是"哪一阶在漏"的唯一出口，写错会让人去改错地方。
 * 所以这里只吃账本与回放结果、只吐 Map，可以离线穷举。
 *
 * <h2>三条规矩</h2>
 * <ul>
 *   <li>学习者与执行者<b>分开列</b>：合成一个总数就分不清"给谁看的没生效"；</li>
 *   <li>`skipped` 与 `unknownReasons` 必须透出：日志读坏或原因码漂移时，
 *       面板要显示"我读不全"，而不是显示一个漂亮的 0；</li>
 *   <li>停点结论直接给一句话（`verdict`），不让人自己拼。</li>
 * </ul>
 *
 * <p>纯 JVM、无 IO，可离线单测。
 */
public final class FunnelReport {

    private FunnelReport() {
    }

    /** 七阶计数总览。 */
    public static Map<String, Object> summary(FunnelLedger ledger, FunnelReplay.Result replay) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> stages = new ArrayList<>();
        for (FunnelStage s : FunnelStage.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", s.name());
            row.put("executor", ledger == null ? 0 : ledger.countAt(s, FunnelLedger.Caller.EXECUTOR));
            row.put("learner", ledger == null ? 0 : ledger.countAt(s, FunnelLedger.Caller.LEARNER));
            stages.add(row);
        }
        out.put("stages", stages);
        out.put("entries", ledger == null ? 0 : ledger.entries().size());
        out.put("duplicates", ledger == null ? 0 : ledger.duplicates());
        out.put("skipped_lines", replay == null ? 0 : replay.skipped());
        // 窗口外的行单独透出：它不是"日志坏了"，也不是"没有发生"。
        out.put("out_of_window_lines", replay == null ? 0 : replay.outOfWindow());
        out.put("unknown_reasons", replay == null ? List.of() : replay.unknownReasons());
        if (replay != null && !replay.problems().isEmpty()) {
            out.put("problems", replay.problems());
        }
        out.put("note", "计数来自指定时间窗内的日志回放；skipped_lines>0 说明有行没读进来，"
                + "out_of_window_lines 是窗外行数 —— 别把 0 当成「没有发生」");
        return out;
    }

    /** 某条能力的停点。 */
    public static Map<String, Object> stopped(FunnelLedger ledger, String entryRef, FunnelLedger.Caller caller) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entry_ref", entryRef == null ? "" : entryRef);
        out.put("caller", (caller == null ? FunnelLedger.Caller.EXECUTOR : caller).name());
        if (ledger == null || entryRef == null || entryRef.isBlank()) {
            out.put("verdict", "no_record：没有给条目或没有账本");
            return out;
        }
        FunnelLedger.Stop stop = ledger.whereStopped(entryRef, caller);
        out.put("reached", stop.reached() == null ? "" : stop.reached().name());
        out.put("missing", stop.missing() == null ? "" : stop.missing().name());
        out.put("verdict", stop.verdict());
        return out;
    }

    /** 从回放结果建一本账（工具每次调用都重建，避免跨调用持有陈旧状态）。 */
    public static FunnelLedger ledgerOf(FunnelReplay.Result replay) {
        FunnelLedger ledger = new FunnelLedger();
        if (replay != null) {
            for (FunnelLedger.Entry e : replay.entries()) {
                ledger.record(e);
            }
        }
        return ledger;
    }
}
