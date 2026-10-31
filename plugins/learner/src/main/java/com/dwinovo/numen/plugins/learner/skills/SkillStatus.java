package com.dwinovo.numen.plugins.learner.skills;

/**
 * Skill 条目的发布状态（与 experience 侧 `PublicationState` 同语义）。
 *
 * <p>为什么不用一个 boolean：158 §3.3 明确「目录登记不等于验证完成，发布不等于强注入，
 * 展开不等于采用」—— 这三件事各自是不同证据，压成一个开关就再也分不开。
 *
 * <p>纯 JVM，可离线单测。
 */
public enum SkillStatus {
    /** 草稿：已登记，未经质量门。 */
    DRAFT,
    /** 试用：只在受控范围可见。 */
    TRIAL,
    /** 已发布：可见，但仍不等于"验证有效"。 */
    PUBLISHED,
    /** 暂停：保留历史，不参与检索。 */
    SUSPENDED,
    /** 撤回：终态。 */
    REVOKED;

    /** 是否还能被检索到。 */
    public boolean retrievable() {
        return this == TRIAL || this == PUBLISHED;
    }
}
