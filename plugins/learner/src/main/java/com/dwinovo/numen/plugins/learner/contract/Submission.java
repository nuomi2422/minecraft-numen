package com.dwinovo.numen.plugins.learner.contract;

import com.dwinovo.numen.plugins.learner.blocks.EnvBlock;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 一次学习提交（159 B：提交格式；内外双实现共用同一个口）。
 *
 * <h2>为什么内部与外部 Learner 要共用这一个外壳</h2>
 * 158 §1 要求两者"同一套读取原语、根因模板、产物模板、证据引用格式、提交格式和质量门"，
 * 区别只在材料权限和可提交产物权限。如果提交格式分两套，就会出现
 * 「外部 Learner 说 A 不合格、内部 Learner 说 A 合格」——而它们其实在看同一份草稿。
 *
 * <h2>"没有产物"是合法结局</h2>
 * 158 §3.2 原文：同一样本<b>可以不产出</b>。所以产物为空不算失败，
 * 状态记为 {@code NO_OUTPUT}。逼着每份复盘都产出点什么，只会催生垃圾条目。
 *
 * <p>纯 JVM，可离线单测。
 */
public final class Submission {

    /** 谁提交的。权限矩阵（F 段）按它区分，本类只记录。 */
    public enum Mode {
        INTERNAL,
        EXTERNAL
    }

    /** 一次提交。 */
    public record Draft(Mode mode, String sampleId, ReviewDraft.Draft review,
                        List<ArtifactDraft> artifacts) {
        public Draft {
            mode = mode == null ? Mode.INTERNAL : mode;
            sampleId = sampleId == null ? "" : sampleId.trim();
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        }
    }

    /** 提交结论。 */
    public record Result(boolean accepted, ReviewDraft.Status status,
                         List<String> codes, List<String> unresolved, List<String> trialOnly) {
        public Result {
            codes = codes == null ? List.of() : List.copyOf(codes);
            unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
            trialOnly = trialOnly == null ? List.of() : List.copyOf(trialOnly);
        }

        /** 无试用态视图（老调用点用）。 */
        public Result(boolean accepted, ReviewDraft.Status status,
                      List<String> codes, List<String> unresolved) {
            this(accepted, status, codes, unresolved, List.of());
        }
    }

    private Submission() {
    }

    /**
     * 校验一次提交：复盘草稿 + 全部产物。
     *
     * @param blockLookup 证据核验用（可为 null）
     */
    public static Result validate(Draft submission, Function<String, Optional<EnvBlock>> blockLookup) {
        List<String> codes = new ArrayList<>();
        Set<String> unresolved = new LinkedHashSet<>();

        if (submission == null) {
            return new Result(false, ReviewDraft.Status.REJECT, List.of("no_submission"), List.of());
        }
        if (submission.sampleId().isBlank()) {
            return new Result(false, ReviewDraft.Status.REJECT, List.of("no_sample"), List.of());
        }

        ReviewDraft.Validation review = ReviewDraft.validate(submission.review(), blockLookup);
        codes.addAll(review.codes());
        unresolved.addAll(review.unresolved());

        // 产物逐个独立判定：一个坏产物不许连坐其它产物（与 Outbox 的逐产物纪律一致）。
        for (int i = 0; i < submission.artifacts().size(); i++) {
            ArtifactValidator.Verdict v = ArtifactValidator.validate(submission.artifacts().get(i));
            if (!v.accepted()) {
                for (String c : v.codes()) {
                    codes.add("artifact#" + i + ":" + c);
                }
            }
        }

        // 158 §7 权限矩阵：内部学习者的自动动作/ACX 只能进试用态，不能直接生产生效。
        // 这一层刻意不看内容质量 —— 权限与质量是两件事，混在一步里就说不清"是被谁拦的"。
        List<String> trialOnly = new ArrayList<>();
        List<PermissionMatrix.Decision> rulings = PermissionMatrix.check(submission.mode(), submission.artifacts());
        for (int i = 0; i < rulings.size(); i++) {
            PermissionMatrix.Decision d = rulings.get(i);
            if (d.ruling() == PermissionMatrix.Ruling.DENY) {
                codes.add("artifact#" + i + ":" + d.code());
            } else if (d.ruling() == PermissionMatrix.Ruling.TRIAL_ONLY) {
                trialOnly.add("artifact#" + i + ":" + d.code());
            }
        }

        if (review.status() == ReviewDraft.Status.REJECT || !codes.isEmpty()) {
            return new Result(false, ReviewDraft.Status.REJECT, codes, List.copyOf(unresolved), trialOnly);
        }
        ReviewDraft.Status status = unresolved.isEmpty()
                ? ReviewDraft.Status.PASS
                : ReviewDraft.Status.PASS_WITH_UNRESOLVED;
        return new Result(true, status, List.of(), List.copyOf(unresolved), trialOnly);
    }
}
