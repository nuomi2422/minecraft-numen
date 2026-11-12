package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.carrier.CarrierAlarms;
import com.dwinovo.numen.api.carrier.CarrierChain;

import java.util.List;

/**
 * Turns existing alarm facts into a short execution choice at the alarm outlet.
 *
 * <p>WHY THIS EXISTS (2026-11-12, live death sequence 22–27): night and critical HP
 * reached the executor, but the shared alarm note forbade interrupting the current
 * goal. Sleep side tasks were deferred while the body was busy, so their shelter
 * advice never reached that request. Keep this text advisory and use only the
 * already sampled facts; no second world reader or body owner is introduced.
 *
 * <p>Why not force sleep or spawn a side task here: a nearby hostile can make the
 * bed unsafe, and a deferred side task has not acquired the body. The executor
 * must reassess the current world and choose a feasible survival action.
 */
final class RddAlarmResponseText {
    private static final long FRESH_MS = 10_000L;

    private RddAlarmResponseText() {}

    static String note(List<CarrierAlarms.Hit> alarms, CarrierChain.Facts facts, long nowMs) {
        if (alarms == null || alarms.isEmpty() || facts == null) return "";
        long ageMs = nowMs - facts.capturedAtMs();
        if (facts.capturedAtMs() <= 0 || ageMs < 0 || ageMs > FRESH_MS
                || !"COMPLETE".equals(facts.coverage())) {
            return "<note>提醒的事实已过期或覆盖不足，只作历史参考；重新确认当前环境后再决定。</note>";
        }
        boolean critical = alarms.stream().anyMatch(h -> "P0".equals(h.prio()));
        boolean night = alarms.stream().anyMatch(h -> "night".equals(h.rule()));
        if (!critical && !night) {
            return "<note>这些是建议，按当前任务和现场事实取舍；风险变化时重新评估。</note>";
        }
        StringBuilder note = new StringBuilder("<note>这是现场风险建议，不是自动命令。生存风险已变化，可以暂停或替换当前非生存动作；先确认此刻的威胁、可走的退路和身体状态，再选能落地的动作。") ;
        if (critical) {
            note.append("危急时先脱离直接威胁、争取回血或掩护，别为了完成原动作继续硬拼。");
        }
        if (night) {
            note.append("夜里优先评估安全落脚：附近有敌怪时先拉开距离或进掩体，安全且床可用时再睡；");
            if ("0".equals(facts.get("bed"))) {
                note.append("没有床且地形允许安全施工时，可考虑挖三填一：挖下约三格，进入后封顶避险；附近敌怪正攻击或脚下不安全时先撤，别原地硬挖。");
            } else {
                note.append("床不可安全使用或床数未知时，找可靠掩体过夜并重新核实条件。");
            }
        }
        return note.append("若场景已变化，重新评估并继续或调整原任务。</note>").toString();
    }
}
