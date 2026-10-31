package com.dwinovo.numen.plugins.learner.funnel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 漏斗面板渲染：执行者/学习者分开、读不全要透出、停点给一句话。
 */
class FunnelReportTest {

    private static FunnelReplay.Result replayWith(FunnelLedger.Entry... entries) {
        return new FunnelReplay.Result(List.of(entries), 3, List.of("brand_new_code"), List.of("ref_without_id"));
    }

    private static FunnelLedger.Entry e(String ref, FunnelStage s, FunnelLedger.Caller c, String reason) {
        return new FunnelLedger.Entry(ref, s, c, 1L, reason, "occ");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 七阶都要出现且执行者与学习者分开() {
        // ⚠ 两个计数必须**不相等**，否则"把 learner 写成 executor"这种变异能活下来
        //   （变异测试 M87 就是这么活下来的：原用例两边都是 1）。
        FunnelReplay.Result replay = replayWith(
                e("a", FunnelStage.RECALLED, FunnelLedger.Caller.EXECUTOR, ""),
                e("b", FunnelStage.RECALLED, FunnelLedger.Caller.EXECUTOR, ""),
                e("a", FunnelStage.RECALLED, FunnelLedger.Caller.LEARNER, ""));
        FunnelLedger ledger = FunnelReport.ledgerOf(replay);

        Map<String, Object> out = FunnelReport.summary(ledger, replay);
        List<Map<String, Object>> stages = (List<Map<String, Object>>) out.get("stages");
        assertEquals(7, stages.size(), "七阶一个都不能少，否则漏斗看着是断的");
        Map<String, Object> recalled = stages.stream()
                .filter(m -> "RECALLED".equals(m.get("stage"))).findFirst().orElseThrow();
        assertEquals(2, recalled.get("executor"));
        assertEquals(1, recalled.get("learner"), "合成一个总数就分不清「给谁看的没生效」");
    }

    @Test
    void 读不全必须透出而不是显示漂亮的0() {
        FunnelReplay.Result replay = replayWith();
        Map<String, Object> out = FunnelReport.summary(FunnelReport.ledgerOf(replay), replay);

        assertEquals(3, out.get("skipped_lines"));
        assertEquals(List.of("brand_new_code"), out.get("unknown_reasons"));
        assertEquals(List.of("ref_without_id"), out.get("problems"));
        assertTrue(String.valueOf(out.get("note")).contains("skipped_lines"));
    }

    @Test
    void 停点给一句话结论() {
        FunnelLedger ledger = FunnelReport.ledgerOf(replayWith(
                e("a", FunnelStage.APPLICABLE, FunnelLedger.Caller.EXECUTOR, "applicable"),
                e("a", FunnelStage.RECALLED, FunnelLedger.Caller.EXECUTOR, ""),
                e("a", FunnelStage.PRESENTED, FunnelLedger.Caller.EXECUTOR, "")));

        Map<String, Object> out = FunnelReport.stopped(ledger, "a", FunnelLedger.Caller.EXECUTOR);
        assertEquals("PRESENTED", out.get("reached"));
        assertEquals("EXPANDED", out.get("missing"));
        assertEquals("已呈现未展开", out.get("verdict"));
    }

    @Test
    void 不适用时停点说的是不适用() {
        FunnelLedger ledger = FunnelReport.ledgerOf(replayWith(
                e("a", FunnelStage.APPLICABLE, FunnelLedger.Caller.EXECUTOR, "applicability_fail")));
        Map<String, Object> out = FunnelReport.stopped(ledger, "a", FunnelLedger.Caller.EXECUTOR);
        assertTrue(String.valueOf(out.get("verdict")).startsWith("不适用"));
    }

    @Test
    void 空输入不崩且说清没记录() {
        Map<String, Object> out = FunnelReport.stopped(null, "", null);
        assertTrue(String.valueOf(out.get("verdict")).startsWith("no_record"));
        Map<String, Object> sum = FunnelReport.summary(null, null);
        assertEquals(0, sum.get("entries"));
        assertEquals(0, sum.get("skipped_lines"));
    }

    @Test
    void 回放记录进账本后重复计为幂等() {
        FunnelReplay.Result replay = replayWith(
                e("a", FunnelStage.RECALLED, FunnelLedger.Caller.EXECUTOR, ""),
                e("a", FunnelStage.RECALLED, FunnelLedger.Caller.EXECUTOR, ""));
        FunnelLedger ledger = FunnelReport.ledgerOf(replay);

        assertEquals(1, ledger.entries().size(), "同一 (条目,阶段,调用方,发生键) 只该记一条");
        assertEquals(1, ledger.duplicates());
    }
}
