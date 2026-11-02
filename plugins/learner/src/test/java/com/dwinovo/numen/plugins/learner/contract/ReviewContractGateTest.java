package com.dwinovo.numen.plugins.learner.contract;

import com.dwinovo.numen.plugins.learner.core.Experience;
import com.dwinovo.numen.plugins.learner.core.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 适配器 + 闸门（159 E）。
 *
 * <p>钉死：旧格式<b>如实</b>搬（不补证据，不猜触发条件）；默认 shadow 不拦；
 * enforce 才拦；坏配置不关死门。
 */
class ReviewContractGateTest {

    private static Verdict verdict(Verdict.Action action, Experience exp) {
        // 10 参构造：memoId/actions/conf/reasoning/exp/ac/carrier/selfCompile/usage/rewrittenQuery
        return new Verdict("m-1", List.of(action), 0.6, "洞里太黑，没带火把就往下挖",
                exp, "", "", "", List.of(), List.of());
    }

    private static Experience experience() {
        return new Experience("夜里在洞口停留会被围住", "带火把", "白天",
                "敌对实体数突然上升", "上次死时就是没光", "低", "自由文本证据",
                "survival", List.of("EXECUTOR"));
    }

    @Test
    void 旧格式如实搬_证据不补_触发条件不猜() {
        Submission.Draft d = ReviewContractAdapter.toSubmission(
                verdict(Verdict.Action.WRITE_EXPERIENCE, experience()), "s-1", Submission.Mode.INTERNAL);

        assertEquals(1, d.artifacts().size());
        ArtifactDraft a = d.artifacts().get(0);
        assertEquals(ArtifactKind.EXPERIENCE, a.kind());
        assertEquals("夜里在洞口停留会被围住", a.purpose());
        assertTrue(a.evidence().isEmpty(), "旧载荷没有结构化证据，一个字都不许补");
        // 2026-11-03（外部学习者）：旧断言「triggerConditions 必须为空」与 2026-11-02 的适配器
        // 变更是冲突的——ReviewContractAdapter.fromExperience 注释写明：EXPERIENCE 产物若不带
        // triggerConditions/evidence，WRITE_EXPERIENCE 这条 artifact 永远 missing_field → 契约
        // REJECT → 不可注入。所以它用 experience.preconditions 充当 triggerConditions（同义：机制
        // 在什么条件下成立）。这里守住原意（**observableSignal 不许被当成触发条件**），
        // 而不是守住"必须为空"这个已被有意改掉的形状。
        assertEquals(List.of("带火把"), a.triggerConditions(), "触发条件取自 preconditions");
        assertFalse(a.triggerConditions().contains("敌对实体数突然上升"),
                "observableSignal 不是触发条件，硬塞会让影子统计失真");
        assertEquals(List.of("带火把"), a.preconditions());
        assertEquals(List.of("白天"), a.excludeConditions());
        assertEquals(List.of("m-1"), a.sampleRefs());
    }

    @Test
    void 旧判定按新契约几乎都要被拒() {
        Submission.Result r = Submission.validate(ReviewContractAdapter.toSubmission(
                verdict(Verdict.Action.WRITE_EXPERIENCE, experience()), "s-1", Submission.Mode.INTERNAL), null);

        assertFalse(r.accepted(), "这正是影子模式要量出来的事实");
        assertTrue(r.codes().contains("claim_without_evidence"));
        assertTrue(r.codes().contains("artifact#0:artifact_without_evidence"));
    }

    @Test
    void 没有草稿位的动作不产出产物() {
        Submission.Draft d = ReviewContractAdapter.toSubmission(
                new Verdict("m-2", List.of(Verdict.Action.SELF_COMPILE, Verdict.Action.NO_ACTION),
                        0.5, "x", null, "", "", "", List.of(), List.of()), "s-1", Submission.Mode.INTERNAL);
        assertTrue(d.artifacts().isEmpty(), "声明了却写不出草稿 = 没有产物，不许造空壳");
    }

    @Test
    void 默认shadow不拦但记结论() {
        Submission.Draft d = ReviewContractAdapter.toSubmission(
                verdict(Verdict.Action.WRITE_EXPERIENCE, experience()), "s-1", Submission.Mode.INTERNAL);
        ReviewContractGate.Decision dec = ReviewContractGate.evaluate(ReviewContractGate.Mode.SHADOW, d, null);

        assertFalse(dec.blocked(), "默认不拦：旧格式直接 ENFORCE 会让学习线停摆且没人知道原因");
        assertEquals(ReviewDraft.Status.REJECT, dec.status());
        assertFalse(dec.codes().isEmpty());
    }

    @Test
    void enforce才拦() {
        Submission.Draft d = ReviewContractAdapter.toSubmission(
                verdict(Verdict.Action.WRITE_EXPERIENCE, experience()), "s-1", Submission.Mode.INTERNAL);
        assertTrue(ReviewContractGate.evaluate(ReviewContractGate.Mode.ENFORCE, d, null).blocked());
    }

    @Test
    void off完全不跑() {
        assertNull(ReviewContractGate.evaluate(ReviewContractGate.Mode.OFF, null, null),
                "OFF 连计数都不做，返回 null 才是诚实的");
    }

    @Test
    void 配置解析与坏配置兜底() {
        assertEquals(ReviewContractGate.Mode.OFF, ReviewContractGate.parseMode("{\"mode\":\"off\"}"));
        assertEquals(ReviewContractGate.Mode.ENFORCE, ReviewContractGate.parseMode("{\"mode\":\"ENFORCE\"}"));
        assertEquals(ReviewContractGate.Mode.SHADOW, ReviewContractGate.parseMode("{\"mode\":\"???\"}"));
        assertEquals(ReviewContractGate.Mode.SHADOW, ReviewContractGate.parseMode("{oops"));
        assertEquals(ReviewContractGate.Mode.SHADOW, ReviewContractGate.parseMode("{}"));
    }

    @Test
    void 空配置内容也是shadow() {
        // ⚠ 变异测试 M58 逼出来的：只测了非空 JSON，于是「读到一个空文件」这条分支没人看 ——
        //   把它改成 ENFORCE 测试照样全绿，而线上真出现空文件时学习线会当场停摆。
        assertEquals(ReviewContractGate.Mode.SHADOW, ReviewContractGate.parseMode(null));
        assertEquals(ReviewContractGate.Mode.SHADOW, ReviewContractGate.parseMode(""));
        assertEquals(ReviewContractGate.Mode.SHADOW, ReviewContractGate.parseMode("   "));
    }

    @Test
    void 缺配置文件就是shadow(@TempDir Path dir) {
        assertEquals(ReviewContractGate.Mode.SHADOW, ReviewContractGate.mode(dir));
        assertFalse(Files.exists(dir.resolve(ReviewContractGate.FILE_NAME)), "默认档不建文件，免得看起来被人配过");
    }

    @Test
    void 配置文件生效(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(ReviewContractGate.FILE_NAME), "{\"mode\":\"enforce\"}");
        assertEquals(ReviewContractGate.Mode.ENFORCE, ReviewContractGate.mode(dir));
    }

    @Test
    void 空判定安全() {
        Submission.Draft d = ReviewContractAdapter.toSubmission(null, "s-1", Submission.Mode.INTERNAL);
        assertEquals(0, d.artifacts().size());
        assertEquals(0, d.review().claims().size());
    }
}
