package com.dwinovo.numen.plugins.learner.core;

import java.util.Map;

/**
 * 复盘草稿的<b>落库资格</b>判定（2026-10-31）。
 *
 * <p><b>为什么单独抽出来</b>：2026-10-27 的自动落库（`LearnerReviewTool.autoStoreExperience`）
 * 用 `publishable` 当门槛，但渲染处从来只写 `publishable=false`（映射失败时），
 * 没有任何一行把它置 true —— 于是自动落库从上线起就是死代码，经验库再也没有新条目。
 * 同时质量门里的 Q2/Q4 按设计恒为 UNDECIDABLE，{@code Verdict.PASS} 当前不可达；
 * 若继续把「可发布」当「可落库」，就算补上 true 也永远落不了。</p>
 *
 * <p><b>本类的语义</b>：落库 = 把草稿写进经验库（publication=DRAFT，执行视图仍由发布门控制），
 * 不是发布。条件只有三条：<b>有草稿壳</b>、<b>草稿带 type</b>（没有 type 进不了 ExperienceStore）、
 * <b>质量门没有硬失败</b>（REVIEW_REQUIRED 允许，REJECT 不允许）。
 * 发布资格（{@code publishable}）另算，不要混用。</p>
 */
public final class ExperiencePersistencePolicy {

    private ExperiencePersistencePolicy() {}

    /**
     * @param draftShell 复盘回执里的 {@code experience_draft}（形状 {@code {entry, mapping}}）；null = 没有草稿
     * @param gateHardFail 质量门是否硬失败（REJECT）
     * @return 是否应调用唯一出口 {@code experience_learn} 把它写成 DRAFT
     */
    public static boolean storable(Object draftShell, boolean gateHardFail) {
        if (draftShell == null || gateHardFail) {
            return false;
        }
        if (!(draftShell instanceof Map<?, ?> shell)) {
            return false;
        }
        Object entry = shell.get("entry");
        if (!(entry instanceof Map<?, ?> em)) {
            return false;
        }
        Object type = em.get("type");
        return type != null && !String.valueOf(type).isBlank();
    }
}
