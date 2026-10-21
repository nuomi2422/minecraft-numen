package com.dwinovo.numen.experience.api;

/**
 * 发布资格状态（G6a，75 号 §15.6 DRAFT/TRIAL/PUBLISHED/SUSPENDED/REVOKED）。
 *
 * <p><b>为什么需要它</b>：成熟度（{@link ExperienceMaturity}）答的是「这条经验有多可信」，
 * 发布资格答的是「这条经验<b>允不允许</b>被拿去指导行动」。两件事必须分开：
 * 一条被验证过很多次的经验，也可能因为主人叫停、环境变了、或它出自一次失败实验而被撤回。
 *
 * <p><b>谁是门</b>：只有 {@link #PUBLISHED} 能进 EXECUTOR 视图（派活时才可自动用）；
 * {@link #DRAFT} / {@link #TRIAL} 只能被规划/学习看到（作为待核实线索）；
 * {@link #REVOKED} 是终态 —— 三种角色<b>都不可检索</b>，不是「打上撤回标记继续展示」。
 *
 * <p><b>向后兼容</b>：老条目（JSONL 里没有这个键）一律解析成 {@link #PUBLISHED} ——
 * 升级不许把已经能用的知识全藏起来。新写入的条目由学习侧显式给 {@link #DRAFT}。
 */
public enum PublicationState {

    /** 刚学到、还没人审过：能进规划视图，绝不进执行视图。 */
    DRAFT,

    /** 试用：允许在受限范围内被采用（G6c 的对照期），仍不进执行视图。 */
    TRIAL,

    /** 正式发布：唯一允许进 EXECUTOR 视图的状态。 */
    PUBLISHED,

    /** 暂停：保留正文与历史，但不参与任何视图，等复审。 */
    SUSPENDED,

    /** 撤回（终态）：三种角色都不可检索；正文仍留档可审计。 */
    REVOKED;

    /** 终态判定：撤回之后不许再改回任何状态（要么新建一条，要么走修订链）。 */
    public boolean terminal() {
        return this == REVOKED;
    }

    /** 是否允许出现在执行视图（派活时自动用）。 */
    public boolean executable() {
        return this == PUBLISHED;
    }

    /** 是否允许被任何视图检索（REVOKED 是唯一例外）。 */
    public boolean retrievable() {
        return this != REVOKED;
    }

    /**
     * 合法迁移表。**只在这里定义一次**（不要在各工具里各写一份 if）：
     * <pre>
     * DRAFT      → TRIAL, PUBLISHED, REVOKED
     * TRIAL      → PUBLISHED, SUSPENDED, REVOKED
     * PUBLISHED  → SUSPENDED, REVOKED
     * SUSPENDED  → PUBLISHED, REVOKED
     * REVOKED    → （无）
     * </pre>
     */
    public boolean canTransitionTo(PublicationState next) {
        if (next == null || next == this) return false;
        if (terminal()) return false;
        return switch (this) {
            case DRAFT -> next == TRIAL || next == PUBLISHED || next == REVOKED;
            case TRIAL -> next == PUBLISHED || next == SUSPENDED || next == REVOKED;
            case PUBLISHED -> next == SUSPENDED || next == REVOKED;
            case SUSPENDED -> next == PUBLISHED || next == REVOKED;
            case REVOKED -> false;
        };
    }

    /**
     * 容错解析：null / 空白 / 未知值一律落到 {@link #PUBLISHED}（老条目兼容）。
     * 解析失败<b>不许</b>落到 DRAFT —— 那会让升级当天所有经验从执行视图里消失。
     */
    public static PublicationState parse(String raw) {
        if (raw == null || raw.isBlank()) return PUBLISHED;
        try {
            return PublicationState.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return PUBLISHED;
        }
    }
}
