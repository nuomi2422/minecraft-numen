package com.dwinovo.numen.plugins.learner.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LC5（78 号 §4「自动防御」/§7）：防御规则候选的<b>影子验证与限域启用</b>状态机。
 *
 * <h2>它守的三条铁律（78 号 §4 原文）</h2>
 * <ol>
 *   <li><b>一次强事件只建候选</b>——高优先入队，但绝不因此派动作、不提高权限；
 *       「已批准的反应立即工作」归现有控制器，本类不碰执行层。</li>
 *   <li><b>新规则先影子</b>（SHADOW）：只记「本会触发/没触发」的事实，不产生任何动作。
 *       影子期收集：真触发次数、误触发（相似无危险场景命中）、冷却命中。</li>
 *   <li><b>限域启用</b>：影子数据达标 + <b>显式 approve（必须带理由）</b> 才进 ACTIVE；
 *       ACTIVE 仍带 constraints（适用条件，如「仅夜间」「仅岩浆半径 3 格内」）与
 *       cooldownMillis。错误触发随时可停（SUSPEND 不需要理由审查——刹车优先）。</li>
 * </ol>
 *
 * <h2>为什么是纯 JVM 状态机而不是执行层钩子</h2>
 * 78 号 §7 LC5 边界原文：「不改另一会话执行层抢占逻辑」。执行层（EntityAgentLoop/
 * 控制器）归执行侧会话；本类只产出<b>判定与事实</b>（该不该触发、影子期表现如何），
 * 消费方（现有安全反应控制器）按自己的节奏接。状态迁移全部显式、可审计、可回退。
 *
 * <p><b>与 CandidateGate 的分工</b>：那边判「这个事件该不该成为学习候选」（入队判定）；
 * 这边判「这个防御<b>规则草案</b>影子期表现如何、敢不敢启用」（启用判定）。
 * 强事件（death/starvation 等 T2 命中）先经 CandidateGate 入候选，提炼成规则草案后
 * 走本类的影子验证——不是每个死亡都直接变成防御规则。
 */
public final class DefenseRuleDraft {

    /** 状态机：SHADOW → ACTIVE ⇄ SUSPENDED；RETIRED 终态。 */
    public enum State {
        /** 影子期：只记事实不派动作。新建草案唯一起点。 */
        SHADOW,
        /** 限域启用：approve 后，带 constraints 与冷却。 */
        ACTIVE,
        /** 暂停：错误触发立即可停（不需理由）；可恢复。 */
        SUSPENDED,
        /** 终态撤回。 */
        RETIRED
    }

    /** 一次影子观察的事实。dangerous=false 的是「相似无危险」反例样本。 */
    public record Observation(boolean dangerous, long atMillis, String contextNote) {}

    /** 一次影子评估的结论（SHADOW 期每次强事件/相似事件后调用）。 */
    public record ShadowVerdict(boolean wouldTrigger, String reason, int truePositives,
                                 int falsePositives, int cooldownHits) {}

    /** 启用门槛（78 号 §4「真触发+相似无危险反例+冷却+停止」）。 */
    public static final int MIN_TRUE_POSITIVES = 2;
    public static final int MAX_FALSE_POSITIVES = 0;

    private final String ruleId;
    private final String description;
    private final long cooldownMillis;
    private final List<String> constraints;
    private State state = State.SHADOW;
    private final List<Observation> observations = new ArrayList<>();
    private long lastWouldTriggerAt = Long.MIN_VALUE;
    private int truePositives;
    private int falsePositives;
    private int cooldownHits;
    private String approvedReason;

    public DefenseRuleDraft(String ruleId, String description,
                            List<String> constraints, long cooldownMillis) {
        this.ruleId = ruleId == null ? "" : ruleId.trim();
        this.description = description == null ? "" : description.trim();
        this.cooldownMillis = Math.max(0, cooldownMillis);
        this.constraints = constraints == null ? List.of() : List.copyOf(constraints);
    }

    // ---- 影子期 ----

    /**
     * 影子评估：这条规则<b>本会</b>触发吗？只记事实，任何状态下都不派动作。
     *
     * @param dangerous     这是一次危险场景吗（强事件=真；相似无危险=反例样本）
     * @param wouldTrigger  规则条件命中吗（由规则草案自己的判定逻辑给出，本类只收结果）
     * @param nowMillis     当前时间（冷却判定）
     * @param contextNote   场景注记（如「夜间岩浆边」「白天平地」——审计用）
     */
    public synchronized ShadowVerdict observeShadow(boolean dangerous, boolean wouldTrigger,
                                                    long nowMillis, String contextNote) {
        if (state != State.SHADOW && state != State.ACTIVE) {
            return new ShadowVerdict(false, "state=" + state + " 不收影子观察",
                    truePositives, falsePositives, cooldownHits);
        }
        observations.add(new Observation(dangerous, nowMillis,
                contextNote == null ? "" : contextNote.trim()));
        if (!wouldTrigger) {
            // 漏触发的事实也留（dangerous=true 却没命中 = 漏），但不算误触发
            return new ShadowVerdict(false, "规则条件未命中",
                    truePositives, falsePositives, cooldownHits);
        }
        // 冷却：同规则两次触发的最小间隔（防「一次风暴里反复触发」）
        if (lastWouldTriggerAt != Long.MIN_VALUE
                && nowMillis - lastWouldTriggerAt < cooldownMillis) {
            cooldownHits++;
            return new ShadowVerdict(false, "冷却中（" + (nowMillis - lastWouldTriggerAt)
                    + " < " + cooldownMillis + "）", truePositives, falsePositives, cooldownHits);
        }
        lastWouldTriggerAt = nowMillis;
        if (dangerous) {
            truePositives++;
        } else {
            falsePositives++;   // 相似无危险场景命中 = 误触发（78 号判据核心）
        }
        return new ShadowVerdict(true, dangerous ? "危险场景命中（真触发）" : "无危险场景命中（误触发）",
                truePositives, falsePositives, cooldownHits);
    }

    // ---- 迁移 ----

    /** 影子数据达标了吗（启用的机器侧条件之一；另一侧是显式 approve）。 */
    public synchronized boolean shadowQualifies() {
        return state == State.SHADOW
                && truePositives >= MIN_TRUE_POSITIVES
                && falsePositives <= MAX_FALSE_POSITIVES;
    }

    /**
     * 限域启用：影子达标 + <b>显式批准（理由必填）</b>。
     * 缺一不可——机器数据再好也不自动启用（78 号 §4「不因一次危险就提高权限」）。
     */
    public synchronized boolean approve(String reason) {
        if (state != State.SHADOW || !shadowQualifies() || reason == null || reason.isBlank()) {
            return false;
        }
        this.approvedReason = reason.trim();
        this.state = State.ACTIVE;
        return true;
    }

    /** 错误触发立即可停（SUSPENDED；不需要审查——刹车权高于一切）。ACTIVE/SHADOW 都能停。 */
    public synchronized boolean suspend() {
        if (state == State.SHADOW || state == State.ACTIVE) {
            state = State.SUSPENDED;
            return true;
        }
        return false;
    }

    /** 恢复（SUSPENDED → SHADOW 重新走影子；不直接回 ACTIVE——停过一次的规则要重新验）。 */
    public synchronized boolean resumeAsShadow() {
        if (state == State.SUSPENDED) {
            state = State.SHADOW;
            truePositives = 0;
            falsePositives = 0;
            cooldownHits = 0;
            lastWouldTriggerAt = Long.MIN_VALUE;
            return true;
        }
        return false;
    }

    /** 终态撤回。 */
    public synchronized boolean retire() {
        if (state != State.RETIRED) {
            state = State.RETIRED;
            return true;
        }
        return false;
    }

    // ---- 读数 ----

    public synchronized State state() { return state; }
    public synchronized String ruleId() { return ruleId; }
    public synchronized String approvedReason() { return approvedReason; }
    public synchronized List<String> constraints() { return constraints; }
    public synchronized long cooldownMillis() { return cooldownMillis; }
    public synchronized int truePositives() { return truePositives; }
    public synchronized int falsePositives() { return falsePositives; }

    /** 面板/审计读数。 */
    public synchronized Map<String, Object> readout() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule_id", ruleId);
        m.put("description", description);
        m.put("state", state.name());
        m.put("true_positives", truePositives);
        m.put("false_positives", falsePositives);
        m.put("cooldown_hits", cooldownHits);
        m.put("shadow_qualifies", shadowQualifies());
        m.put("constraints", constraints);
        m.put("cooldown_millis", cooldownMillis);
        m.put("note", "SHADOW 只记事实不派动作；启用需影子达标+显式批准；错误触发随时可停");
        return m;
    }
}
