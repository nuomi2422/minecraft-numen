package com.dwinovo.numen.plugins.learner.contract;

import com.dwinovo.numen.plugins.learner.core.Experience;
import com.dwinovo.numen.plugins.learner.core.Verdict;

import java.util.ArrayList;
import java.util.List;

/**
 * 旧 {@link Verdict} → 新契约 {@link Submission.Draft} 的<strong>适配器</strong>（159 E）。
 *
 * <h2>它为什么必须存在，而且必须承认自己是"有损"的</h2>
 * 现有复盘产物是七字段经验草稿 + 四类草稿位，<b>没有结构化证据引用</b>：
 * {@code Experience.evidence} 是一段自由文本，不是 `block:`/`event:` 引用。
 * 所以这里做不出一条"满足新契约"的提交 —— 这是事实，不是实现缺陷。
 *
 * <p>适配器的职责因此只有两个：
 * <ol>
 *   <li>把旧载荷<b>如实搬</b>到新结构（不补字段、不猜证据）；</li>
 *   <li>让闸门能在真实复盘路径上跑一遍，<b>量出</b>「按新契约会有多少条不通过」。</li>
 * </ol>
 *
 * <h2>为什么映射时宁缺不补</h2>
 * 把 {@code observableSignal} 硬塞成 triggerConditions、把自由文本证据硬塞成 {@code event:} 引用，
 * 会让影子统计显示"大部分通过" —— 而那个数字是假的，后面据此把闸门切到 ENFORCE 就会立刻炸。
 * 现在如实映射，影子数据才会告诉真相：**旧格式基本过不了新契约**。
 *
 * <p>纯 JVM，可离线单测。
 */
public final class ReviewContractAdapter {

    private ReviewContractAdapter() {
    }

    /**
     * 把一条判定搬成一次提交。
     *
     * @param sampleId 这条判定对应的样本 id；旧流程没有，传 memoId 或空
     */
    public static Submission.Draft toSubmission(Verdict v, String sampleId, Submission.Mode mode) {
        if (v == null) {
            return new Submission.Draft(mode, sampleId, new ReviewDraft.Draft(sampleId, List.of()), List.of());
        }
        List<ReviewDraft.Claim> claims = new ArrayList<>();
        // 旧判定只有一段自由推理：如实搬成一条「事件事实」，证据留空。
        // 留空不是偷懒 —— 旧载荷里真的没有可核验的引用。
        claims.add(new ReviewDraft.Claim(ReviewDraft.Section.FACT,
                clip(v.reasoning(), 400), List.of()));

        List<ArtifactDraft> artifacts = new ArrayList<>();
        for (Verdict.Action a : v.actions()) {
            ArtifactDraft d = switch (a) {
                case WRITE_EXPERIENCE -> fromExperience(v.experience(), v.memoId());
                case USE_AC -> v.acScriptDraft() == null || v.acScriptDraft().isBlank()
                        ? null
                        : new ArtifactDraft(ArtifactKind.ACX_REQUEST,
                        "AC from " + nz(v.memoId()), clip(v.acScriptDraft(), 80), v.acScriptDraft(),
                        List.of(), List.of(), List.of(), "", List.of(), List.of(nz(v.memoId())), "EXECUTOR");
                case USE_CARRIER -> v.carrierDraft() == null || v.carrierDraft().isBlank()
                        ? null
                        : new ArtifactDraft(ArtifactKind.AUTO_ACTION,
                        "carrier from " + nz(v.memoId()), clip(v.carrierDraft(), 80), v.carrierDraft(),
                        List.of(), List.of(), List.of(), "", List.of(), List.of(nz(v.memoId())), "EXECUTOR");
                case SELF_COMPILE -> v.selfCompileRequest() == null || v.selfCompileRequest().isBlank()
                        ? null
                        : new ArtifactDraft(ArtifactKind.CAPABILITY_REQUEST,
                        "capability from " + nz(v.memoId()), clip(v.selfCompileRequest(), 80),
                        v.selfCompileRequest(), List.of(), List.of(), List.of(), "",
                        List.of(), List.of(nz(v.memoId())), "EXECUTOR");
                case SIDE_TASK -> v.sideTaskDraft() == null || v.sideTaskDraft().isBlank()
                        ? null
                        : new ArtifactDraft(ArtifactKind.SIDE_TASK,
                        "side task from " + nz(v.memoId()), clip(v.sideTaskDraft(), 80), v.sideTaskDraft(),
                        List.of(), List.of(), List.of(), "", List.of(), List.of(nz(v.memoId())), "EXECUTOR");
                case NO_ACTION, NEEDS_HUMAN -> null;
            };
            if (d != null) {
                artifacts.add(d);
            }
        }
        return new Submission.Draft(mode, sampleId, new ReviewDraft.Draft(sampleId, claims), artifacts);
    }

    private static ArtifactDraft fromExperience(Experience e, String memoId) {
        if (e == null) {
            return null;
        }
        String purpose = firstNonBlank(e.mechanism(), e.observableSignal(), "未命名经验");
        // triggerConditions 旧格式没有对应字段：observableSignal 语义接近但不是触发条件，
        // 硬塞会让影子统计失真，所以这里留空（缺字段会被闸门如实判出来）。
        return new ArtifactDraft(
                ArtifactKind.EXPERIENCE,
                clip(purpose, 40),
                purpose,
                join(e.derivation(), e.efficiency()),
                List.of(),
                splitOne(e.failureConditions()),
                splitOne(e.preconditions()),
                "",
                List.of(),
                List.of(nz(memoId)),
                String.join(",", e.audience() == null ? List.of() : e.audience()));
    }

    private static List<String> splitOne(String s) {
        return s == null || s.isBlank() ? List.of() : List.of(s.trim());
    }

    private static String join(String a, String b) {
        String x = a == null ? "" : a;
        String y = b == null ? "" : b;
        if (x.isBlank()) {
            return y;
        }
        return y.isBlank() ? x : x + "\n" + y;
    }

    private static String firstNonBlank(String... xs) {
        for (String x : xs) {
            if (x != null && !x.isBlank()) {
                return x.trim();
            }
        }
        return "";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String clip(String s, int max) {
        String t = s == null ? "" : s.trim().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max);
    }
}
