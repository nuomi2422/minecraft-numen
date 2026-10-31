package com.dwinovo.numen.plugins.learner.funnel;

/**
 * 检索漏斗的七个阶段（158 §3.4）。
 *
 * <h2>为什么必须七阶分开记</h2>
 * 158 §3.4 原文：「每一阶独立记录，不能把未适用、适用但未召回、召回但未展开混成 Skill 无效」。
 * 这四种情况的<b>修法完全不同</b>：
 * <ul>
 *   <li>未适用 —— 触发条件写错了（Skill 本身没问题）；</li>
 *   <li>适用未召回 —— 检索/排序/预算问题；</li>
 *   <li>召回未展开 —— 目录写得没有吸引力，或预算不给；</li>
 *   <li>展开未采用 —— 正文没用/不可信。</li>
 * </ul>
 * 混成一个「Skill 无效」，等于把所有问题都推给内容作者。
 *
 * <h2>为什么 APPLICABLE 是第 0 阶且不经 LLM</h2>
 * 158 §3.4：由结构化世界状态、当前目标、角色、前置条件和排除条件<b>机械判定</b>。
 * 让模型判「适不适用」会把一个可复现的过滤变成一次不可复现的采样。
 *
 * <p>纯 JVM，可离线单测。
 */
public enum FunnelStage {

    /** 第 0 阶：机械判定是否适用（不过 LLM）。 */
    APPLICABLE(0),
    /** 第 1 阶：进了候选集。 */
    RECALLED(1),
    /** 第 2 阶：出现在模型能看到的上下文里。 */
    PRESENTED(2),
    /** 第 3 阶：读了正文。 */
    EXPANDED(3),
    /** 第 4 阶：被明确采用（不等于执行）。 */
    ADOPTED(4),
    /** 第 5 阶：真的执行了（跑了一个 AC / 用了携带器）。 */
    EXECUTED(5),
    /** 第 6 阶：有了世界效果（不等于任务成功）。 */
    EFFECT(6);

    private final int rank;

    FunnelStage(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    /** 下一个阶段；最后一阶返回 {@code null}。 */
    public FunnelStage next() {
        for (FunnelStage s : values()) {
            if (s.rank == rank + 1) {
                return s;
            }
        }
        return null;
    }
}
