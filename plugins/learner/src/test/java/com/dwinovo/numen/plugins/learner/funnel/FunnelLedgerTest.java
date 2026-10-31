package com.dwinovo.numen.plugins.learner.funnel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 漏斗账本：七阶独立、幂等、停在哪一阶必须说得清（158 §3.4）。
 */
class FunnelLedgerTest {

    private static final long T0 = 1_700_000_000_000L;

    private static FunnelLedger.Entry e(String entry, FunnelStage s, FunnelLedger.Caller c) {
        return new FunnelLedger.Entry(entry, s, c, T0, "", "");
    }

    @Test
    void 七阶可分别记分别查() {
        FunnelLedger l = new FunnelLedger();
        l.record(e("sk-1", FunnelStage.APPLICABLE, FunnelLedger.Caller.EXECUTOR));
        l.record(e("sk-1", FunnelStage.RECALLED, FunnelLedger.Caller.EXECUTOR));

        assertEquals(2, l.stagesOf("sk-1", FunnelLedger.Caller.EXECUTOR).size());
        assertEquals(1, l.countAt(FunnelStage.APPLICABLE, FunnelLedger.Caller.EXECUTOR));
        assertEquals(0, l.countAt(FunnelStage.APPLICABLE, FunnelLedger.Caller.LEARNER),
                "学习者与执行者必须分开记");
    }

    @Test
    void 重复写被幂等挡下并计数() {
        FunnelLedger l = new FunnelLedger();
        assertTrue(l.record(e("sk-1", FunnelStage.RECALLED, FunnelLedger.Caller.EXECUTOR)));
        assertFalse(l.record(e("sk-1", FunnelStage.RECALLED, FunnelLedger.Caller.EXECUTOR)));

        assertEquals(1, l.countAt(FunnelStage.RECALLED, FunnelLedger.Caller.EXECUTOR),
                "重复写会让漏斗计数翻倍，而漏斗计数是判断哪一阶在漏的唯一依据");
        assertEquals(1, l.duplicates());
    }

    @Test
    void 同一阶段不同发生键可各记一次() {
        FunnelLedger l = new FunnelLedger();
        l.record(new FunnelLedger.Entry("sk-1", FunnelStage.RECALLED, FunnelLedger.Caller.EXECUTOR, T0, "", "task-1"));
        l.record(new FunnelLedger.Entry("sk-1", FunnelStage.RECALLED, FunnelLedger.Caller.EXECUTOR, T0, "", "task-2"));
        assertEquals(2, l.countAt(FunnelStage.RECALLED, FunnelLedger.Caller.EXECUTOR));
    }

    @Test
    void 停在适用未召回() {
        FunnelLedger l = new FunnelLedger();
        l.record(e("sk-1", FunnelStage.APPLICABLE, FunnelLedger.Caller.EXECUTOR));

        var stop = l.whereStopped("sk-1", FunnelLedger.Caller.EXECUTOR);
        assertEquals(FunnelStage.APPLICABLE, stop.reached());
        assertEquals(FunnelStage.RECALLED, stop.missing());
        assertEquals("适用未召回", stop.verdict());
    }

    @Test
    void 停在各阶时措辞按缺失的那一阶命名() {
        // 措辞必须精确到"缺哪一阶"：说「召回未展开」既可能指停在 RECALLED 也可能指停在 PRESENTED，
        // 复盘时会被读错，而复盘结论会直接决定改哪里。
        FunnelLedger l = new FunnelLedger();
        l.record(e("sk-1", FunnelStage.APPLICABLE, FunnelLedger.Caller.EXECUTOR));
        assertEquals("适用未召回", l.whereStopped("sk-1", FunnelLedger.Caller.EXECUTOR).verdict());

        l.record(e("sk-1", FunnelStage.RECALLED, FunnelLedger.Caller.EXECUTOR));
        assertEquals("已召回未呈现", l.whereStopped("sk-1", FunnelLedger.Caller.EXECUTOR).verdict());

        l.record(e("sk-1", FunnelStage.PRESENTED, FunnelLedger.Caller.EXECUTOR));
        assertEquals(FunnelStage.EXPANDED, l.whereStopped("sk-1", FunnelLedger.Caller.EXECUTOR).missing());
        assertEquals("已呈现未展开", l.whereStopped("sk-1", FunnelLedger.Caller.EXECUTOR).verdict());

        l.record(e("sk-1", FunnelStage.EXPANDED, FunnelLedger.Caller.EXECUTOR));
        assertEquals("已展开未采用", l.whereStopped("sk-1", FunnelLedger.Caller.EXECUTOR).verdict());
    }

    @Test
    void 乱序写不会把最高阶算错() {
        FunnelLedger l = new FunnelLedger();
        // 直接从工具调用进入（先记了 EXPANDED），事后才补 APPLICABLE
        l.record(e("sk-1", FunnelStage.EXPANDED, FunnelLedger.Caller.EXECUTOR));
        l.record(e("sk-1", FunnelStage.APPLICABLE, FunnelLedger.Caller.EXECUTOR));

        assertEquals(FunnelStage.EXPANDED, l.whereStopped("sk-1", FunnelLedger.Caller.EXECUTOR).reached());
    }

    @Test
    void 没有记录时是没跑过不是不适用() {
        FunnelLedger l = new FunnelLedger();
        var stop = l.whereStopped("sk-none", FunnelLedger.Caller.EXECUTOR);
        assertNull(stop.reached());
        assertEquals(FunnelStage.APPLICABLE, stop.missing());
        assertTrue(stop.verdict().startsWith("no_record"));
    }

    @Test
    void 走完七阶是reachedEnd() {
        FunnelLedger l = new FunnelLedger();
        for (FunnelStage s : FunnelStage.values()) {
            l.record(e("sk-1", s, FunnelLedger.Caller.EXECUTOR));
        }
        var stop = l.whereStopped("sk-1", FunnelLedger.Caller.EXECUTOR);
        assertEquals(FunnelStage.EFFECT, stop.reached());
        assertNull(stop.missing());
        assertTrue(stop.verdict().startsWith("reached_end"));
    }

    @Test
    void 漏斗一行能直接渲染() {
        FunnelLedger l = new FunnelLedger();
        l.record(e("sk-1", FunnelStage.APPLICABLE, FunnelLedger.Caller.EXECUTOR));
        l.record(e("sk-1", FunnelStage.APPLICABLE, FunnelLedger.Caller.LEARNER));
        assertEquals("APPLICABLE=1/1", l.funnelLine().get(0));
        assertEquals(7, l.funnelLine().size());
    }

    @Test
    void 空条目直接拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> new FunnelLedger.Entry("", FunnelStage.RECALLED, FunnelLedger.Caller.EXECUTOR, T0, "", ""));
        assertThrows(IllegalArgumentException.class,
                () -> new FunnelLedger.Entry("sk-1", null, FunnelLedger.Caller.EXECUTOR, T0, "", ""));
    }
}
