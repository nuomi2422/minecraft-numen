package com.dwinovo.numen.plugins.learner.contract;

import java.util.ArrayList;
import java.util.List;

/**
 * 产物草稿的机械校验（159 B：四类以上产物模板、每类质量门）。
 *
 * <h2>它只判"说清了没有"，不判"说得对不对"</h2>
 * 这是刻意的分界：机械能判的是<b>必备字段在不在</b>（自动动作有没有前置条件、
 * 支线任务有没有正文、任何产物有没有证据）。
 * "这个前置条件写得对不对"需要人/外部 Learner 判 —— 把两件事混在一道门里，
 * 结果就是门要么永远绿，要么永远红。
 *
 * <h2>为什么"没有证据"是硬失败</h2>
 * 158 §5：每个事实性断言要有字段级证据。产物是断言的容器，
 * 无证据的产物等于"凭印象造出来的规则"—— 它一旦生效，就再也没人能追溯它是从哪来的。
 *
 * <p>纯 JVM，可离线单测。
 */
public final class ArtifactValidator {

    /** 一处问题。 */
    public record Violation(String code, String detail) {
    }

    /** 校验结果。 */
    public record Verdict(boolean accepted, List<Violation> violations) {
        public Verdict {
            violations = violations == null ? List.of() : List.copyOf(violations);
        }

        public List<String> codes() {
            return violations.stream().map(Violation::code).toList();
        }
    }

    private ArtifactValidator() {
    }

    public static Verdict validate(ArtifactDraft d) {
        if (d == null) {
            return new Verdict(false, List.of(new Violation("no_artifact", "空产物")));
        }
        List<Violation> out = new ArrayList<>();
        if (d.name().isBlank()) {
            out.add(new Violation("no_name", d.kind() + " 没有名字，检索时无法被指认"));
        }
        for (String field : d.kind().requiredFields()) {
            if (!d.hasField(field)) {
                out.add(new Violation("missing_field:" + field,
                        d.kind().zh() + " 缺必备字段 " + field));
            }
        }
        if (d.evidence().isEmpty()) {
            out.add(new Violation("artifact_without_evidence", "产物没有证据引用，无法追溯来源"));
        }
        return new Verdict(out.isEmpty(), out);
    }
}
