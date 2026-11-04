package com.dwinovo.numen.plugins.learner;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 037/T16：learner 在"rdd 插件缺席"时的降级路径。
 *
 * <p>lea rner 只把 rdd-core 当 compileOnly，运行时由 rdd 插件提供；所以缺 rdd 是
 * <b>正常场景</b>，不是异常。要求：不崩、不假装"没有差异"，而是明说"没法对账"。
 */
class LearnerFactShadowAbsenceTest {

    @Test
    void missingReconcilerIsReportedAsAbsenceNotAsCleanReport() {
        var ex = assertThrows(LearnerFactShadowTool.MissingReconciler.class,
                () -> LearnerFactShadowTool.reconcile(Path.of("facts.json"), Path.of("ledger.jsonl"),
                        "com.dwinovo.numen.rdd.fact.DoesNotExistAnywhere"));
        assertTrue(ex.getMessage().contains("absent"), "要说清『类不在』：" + ex.getMessage());
        assertNotNull(ex.getCause(), "保留原始 ClassNotFoundException 便于排查");
    }

    @Test
    void realReconcilerIsStillReachableWhenRddIsPresent() throws Exception {
        Object report = LearnerFactShadowTool.reconcile(Path.of("facts.json"), Path.of("ledger.jsonl"),
                LearnerFactShadowTool.RECONCILER_CLASS);
        assertNotNull(report, "rdd 在场时必须真的调通，避免『守卫』把正常路径也挡了");
    }

    @Test
    void wrongClassStillFailsLoudlyInsteadOfReturningNull() {
        assertThrows(ReflectiveOperationException.class,
                () -> LearnerFactShadowTool.reconcile(Path.of("a"), Path.of("b"), "java.lang.String"));
    }
}
