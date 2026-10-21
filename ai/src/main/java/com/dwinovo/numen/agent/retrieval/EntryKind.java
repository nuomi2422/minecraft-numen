package com.dwinovo.numen.agent.retrieval;

/**
 * 条目正文类型的最小枚举（75 号 §15.5 `EntryDescriptor.kind`）。
 *
 * <p>与领域的经验/学习器对齐：本批 trait 只覆盖判据测试里的 kind，其它 kind 由真实
 * provider 按需扩展（枚举加值不会破坏旧 deserialize 的 read path，因为消费者按
 * switch 处理（PortToolAdapter 那种 default 抛异常的风格只有一处））。
 */
public enum EntryKind {
    /** 学习者 / 经验海的一条经验（成功 / 失败都算）。 */
    EXPERIENCE,
    /** 一条可执行流程（G5b 的 `executionRef` 才接得上，本批只占位留位）。 */
    PROCEDURE,
    /** 前提条件/环境情报/资产缺口这类「世界事实」性质的内容。 */
    INFO,
    /** 用户明确要看的警告（危险/不可执行说明）。 */
    WARNING,
    /** 未分类：别把 UNKNOWN 当默认，provider 给什么就是什么。 */
    UNKNOWN
}
