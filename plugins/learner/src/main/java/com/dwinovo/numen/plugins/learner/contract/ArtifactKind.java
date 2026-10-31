package com.dwinovo.numen.plugins.learner.contract;

import java.util.List;

/**
 * 学习产物的分型（158 §3.2）。
 *
 * <h2>为什么必须分型，而不是"一条经验"了事</h2>
 * 158 §3.2 原文：同一样本可以不产出、复用、修订或新建多个产物 ——
 * 经验、支线任务、工具说明、自动动作、ACX/状态机、能力缺口请求。
 * 它们的<b>验证方式、风险等级、允许谁提交</b>完全不同：
 * 经验只要"说得对"，自动动作要"跑起来真不出事"，ACX 要"能回放"。
 * 用同一个模板装这六种东西，就会出现「一条经验因为没有回执被当成无效」这种荒谬结论。
 *
 * <h2>每类都有"必备字段"，不是风格问题</h2>
 * 例如自动动作没有前置条件，执行时就会在错误的世界状态下触发 ——
 * 那是真实事故，不是文档瑕疵。所以必备字段写在枚举里，由
 * {@link ArtifactValidator} 机械检查。
 *
 * <p>纯 JVM，可离线单测。
 */
public enum ArtifactKind {

    /** 经验：一段"下次遇到类似情况可以这么做"的可检索知识。 */
    EXPERIENCE("经验", List.of("purpose", "triggerConditions", "evidence")),

    /** 支线任务：临时插入的补救/探索任务。 */
    SIDE_TASK("支线任务", List.of("purpose", "triggerConditions", "body", "evidence")),

    /** 工具说明：某个工具怎么用、有什么坑。 */
    TOOL_NOTE("工具说明", List.of("purpose", "body", "evidence")),

    /**
     * 自动动作：不需要模型在场也能触发的规则。
     * 必须有前置条件与风险等级 —— 缺前置条件等于"任何时刻都可能触发"。
     */
    AUTO_ACTION("自动动作", List.of("purpose", "triggerConditions", "excludeConditions", "preconditions", "risk", "evidence")),

    /** ACX / 状态机：要能回放、能断点续跑的脚本。 */
    ACX_REQUEST("ACX/状态机", List.of("purpose", "triggerConditions", "body", "evidence")),

    /** 能力缺口请求：不是"怎么做"，而是"我们缺什么能力"。 */
    CAPABILITY_REQUEST("能力缺口请求", List.of("purpose", "body", "evidence"));

    private final String zh;
    private final List<String> required;

    ArtifactKind(String zh, List<String> required) {
        this.zh = zh;
        this.required = List.copyOf(required);
    }

    public String zh() {
        return zh;
    }

    /** 必备字段名（与 {@link ArtifactDraft} 的组件同名，便于报错定位）。 */
    public List<String> requiredFields() {
        return required;
    }
}
