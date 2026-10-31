package com.dwinovo.numen.plugins.learner.contract;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限矩阵：内部学习者不许把自动动作直接推到生产（158 §7）。
 */
class PermissionMatrixTest {

    private static ArtifactDraft of(ArtifactKind kind) {
        // 六类必备字段全给齐：本类只测权限，不该被字段校验挡住（否则测的是别的东西）
        return new ArtifactDraft(kind, "n", "p", "b", List.of("t"), List.of("x"), List.of("pre"),
                "LOW", List.of(EvidenceRef.parse("block:dc-1")), List.of(), "EXECUTOR");
    }

    @Test
    void 内部的经验与支线放行() {
        assertEquals(PermissionMatrix.Ruling.ALLOW,
                PermissionMatrix.rulingFor(Submission.Mode.INTERNAL, ArtifactKind.EXPERIENCE).ruling());
        assertEquals(PermissionMatrix.Ruling.ALLOW,
                PermissionMatrix.rulingFor(Submission.Mode.INTERNAL, ArtifactKind.SIDE_TASK).ruling());
        assertEquals(PermissionMatrix.Ruling.ALLOW,
                PermissionMatrix.rulingFor(Submission.Mode.INTERNAL, ArtifactKind.TOOL_NOTE).ruling());
    }

    @Test
    void 内部的自动动作与ACX只到试用态() {
        assertEquals(PermissionMatrix.Ruling.TRIAL_ONLY,
                PermissionMatrix.rulingFor(Submission.Mode.INTERNAL, ArtifactKind.AUTO_ACTION).ruling());
        assertEquals(PermissionMatrix.Ruling.TRIAL_ONLY,
                PermissionMatrix.rulingFor(Submission.Mode.INTERNAL, ArtifactKind.ACX_REQUEST).ruling());
        assertEquals(PermissionMatrix.Ruling.TRIAL_ONLY,
                PermissionMatrix.rulingFor(Submission.Mode.INTERNAL, ArtifactKind.CAPABILITY_REQUEST).ruling());
    }

    @Test
    void 外部同类型放行但受质量门与授权限制() {
        PermissionMatrix.Decision d = PermissionMatrix.rulingFor(Submission.Mode.EXTERNAL, ArtifactKind.AUTO_ACTION);
        assertEquals(PermissionMatrix.Ruling.ALLOW, d.ruling());
        assertTrue(d.code().startsWith("gated:"), "矩阵放行不等于没限制，代码里要能看出下游仍需把关");
    }

    @Test
    void 缺参数一律拒() {
        assertEquals(PermissionMatrix.Ruling.DENY, PermissionMatrix.rulingFor(null, ArtifactKind.EXPERIENCE).ruling());
        assertEquals(PermissionMatrix.Ruling.DENY, PermissionMatrix.rulingFor(Submission.Mode.INTERNAL, null).ruling());
    }

    @Test
    void 批量检查与产物顺序一一对应() {
        List<ArtifactDraft> arts = List.of(of(ArtifactKind.EXPERIENCE), of(ArtifactKind.AUTO_ACTION));
        List<PermissionMatrix.Decision> rs = PermissionMatrix.check(Submission.Mode.INTERNAL, arts);
        assertEquals(2, rs.size());
        assertEquals(PermissionMatrix.Ruling.ALLOW, rs.get(0).ruling());
        assertEquals(PermissionMatrix.Ruling.TRIAL_ONLY, rs.get(1).ruling());
    }

    @Test
    void 提交里能看出哪个产物只准进试用() {
        ReviewDraft.Draft review = new ReviewDraft.Draft("s-1", List.of(
                new ReviewDraft.Claim(ReviewDraft.Section.FACT, "被僵尸打死",
                        List.of(EvidenceRef.parse("block:dc-turn-3")))));
        Submission.Draft d = new Submission.Draft(Submission.Mode.INTERNAL, "s-1", review,
                List.of(of(ArtifactKind.EXPERIENCE), of(ArtifactKind.AUTO_ACTION)));

        Submission.Result r = Submission.validate(d, id -> java.util.Optional.of(TestBlocks.decisionContext(id)));
        assertTrue(r.accepted(), "试用态不是拒绝：" + r.codes());
        assertEquals(1, r.trialOnly().size());
        assertTrue(r.trialOnly().get(0).startsWith("artifact#1:trial_only:AUTO_ACTION"));
    }

    @Test
    void 外部同类提交不进试用态() {
        ReviewDraft.Draft review = new ReviewDraft.Draft("s-1", List.of(
                new ReviewDraft.Claim(ReviewDraft.Section.FACT, "x",
                        List.of(EvidenceRef.parse("block:dc-turn-3")))));
        Submission.Draft d = new Submission.Draft(Submission.Mode.EXTERNAL, "s-1", review,
                List.of(of(ArtifactKind.AUTO_ACTION)));

        Submission.Result r = Submission.validate(d, id -> java.util.Optional.of(TestBlocks.decisionContext(id)));
        assertTrue(r.accepted());
        assertTrue(r.trialOnly().isEmpty());
    }
}
