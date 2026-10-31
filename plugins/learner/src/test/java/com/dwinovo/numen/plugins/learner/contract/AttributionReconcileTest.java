package com.dwinovo.numen.plugins.learner.contract;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双 Learner 对账：一致 / 子集 / 冲突三态 + 分歧度（158 §1）。
 */
class AttributionReconcileTest {

    private static AttributionReconcile.Side side(Submission.Mode m, String... cs) {
        return new AttributionReconcile.Side(m, List.of(cs));
    }

    @Test
    void 完全一致是AGREE且分歧为零() {
        var r = AttributionReconcile.reconcile(
                side(Submission.Mode.INTERNAL, "血量不足", "夜间外出"),
                side(Submission.Mode.EXTERNAL, " 血量不足 ", "夜间外出"));
        assertEquals(AttributionReconcile.Relation.AGREE, r.relation());
        assertEquals(0.0, r.divergence());
        assertFalse(r.conflict());
    }

    @Test
    void 一方多说了几条算SUBSET不算冲突() {
        var r = AttributionReconcile.reconcile(
                side(Submission.Mode.INTERNAL, "血量不足"),
                side(Submission.Mode.EXTERNAL, "血量不足", "夜间外出"));
        assertEquals(AttributionReconcile.Relation.SUBSET, r.relation(),
                "把子集当冲突会让分歧率虚高、复核队列被「说得更细的那一方」灌满");
        assertEquals(0.0, r.divergence());
        assertTrue(r.onlyExternal().contains("夜间外出"));
        assertTrue(r.onlyInternal().isEmpty());
    }

    @Test
    void 互相排斥的结论是CONFLICT且给出分歧度() {
        var r = AttributionReconcile.reconcile(
                side(Submission.Mode.INTERNAL, "血量不足"),
                side(Submission.Mode.EXTERNAL, "照明不足"));
        assertEquals(AttributionReconcile.Relation.CONFLICT, r.relation());
        assertTrue(r.conflict());
        assertEquals(1.0, r.divergence(), "两个完全不重合的结论，分歧度应为 1");
    }

    @Test
    void 部分重合的冲突分歧度按Jaccard算() {
        // 交集 1（A），并集 3（A,B,C）→ divergence = 1 - 1/3
        var r = AttributionReconcile.reconcile(
                side(Submission.Mode.INTERNAL, "A", "B"),
                side(Submission.Mode.EXTERNAL, "A", "C"));
        assertEquals(AttributionReconcile.Relation.CONFLICT, r.relation());
        assertEquals(1.0 - 1.0 / 3.0, r.divergence(), 1e-9);
        assertTrue(r.onlyInternal().contains("b"));
        assertTrue(r.onlyExternal().contains("c"));
    }

    @Test
    void 大小写与全角空白不算不同() {
        var r = AttributionReconcile.reconcile(
                side(Submission.Mode.INTERNAL, "ＡＢ　Ｃ"),
                side(Submission.Mode.EXTERNAL, "ab c"));
        assertEquals(AttributionReconcile.Relation.AGREE, r.relation());
    }

    @Test
    void 两边都空算一致() {
        var r = AttributionReconcile.reconcile(side(Submission.Mode.INTERNAL), side(Submission.Mode.EXTERNAL));
        assertEquals(AttributionReconcile.Relation.AGREE, r.relation());
        assertEquals(0.0, r.divergence());
    }

    @Test
    void 一边为空另一边有结论是SUBSET() {
        var r = AttributionReconcile.reconcile(
                side(Submission.Mode.INTERNAL),
                side(Submission.Mode.EXTERNAL, "照明不足"));
        assertEquals(AttributionReconcile.Relation.SUBSET, r.relation());
        assertEquals(0.0, r.divergence());
    }

    @Test
    void 空指针输入不崩() {
        var r = AttributionReconcile.reconcile(null, null);
        assertEquals(AttributionReconcile.Relation.AGREE, r.relation());
    }
}
