package com.dwinovo.numen.agent.retrieval;

/**
 * 检索侧自己的「成熟度」最小枚举。
 *
 * <p>与ExperienceAPI {@code ExperienceMaturity} 的值对齐但不 import ——
 * G5a 是纯契约、不来 experience-core（两模块可独立编辑、独立实机）。
 * 未来 B4 的正式接线再把两个枚举对上（映射在适配层）。
 */
public enum Maturity {
    OBSERVED(0),
    ATTEMPTED(1),
    VERIFIED(2),
    GENERALIZED(3);

    public final int rank;

    Maturity(int rank) { this.rank = rank; }
}
