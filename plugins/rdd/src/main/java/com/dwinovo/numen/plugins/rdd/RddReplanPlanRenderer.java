package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.TaskChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders the three existing plan-history blocks used by RddPlugin during replanning.
 * Kept separate from the host so real TaskChain fixtures need no Minecraft startup;
 * callers pass a chain, and no planning state or world facts are owned here.
 * Why not keep host wrappers: they duplicate the boundary without isolating this logic.
 * Text, iteration order, bounds and best-effort exception fallbacks are compatibility contracts.
 */
final class RddReplanPlanRenderer {
    // Preserve the original warning category without loading the Minecraft host.
    private static final Logger LOG = LoggerFactory.getLogger("com.dwinovo.numen.plugins.rdd.RddPlugin");

    private RddReplanPlanRenderer() {}

    /**
     * 把「上一版的二级清单」渲染给规划器（2026-09-29「重规划没变化」修复）。
     *
     * <p>为什么需要：重规划时规划器只拿到一级 theme，**从未看到旧计划长什么样**。
     * 在同一个信息集上再推一次，必然得到同一个结果 —— 实机就是「重规划和之前
     * 基本没什么区别」。显式给出旧计划后，它才有对照物可说「这里不对，换个做法」。
     *
     * <p>有界：最多 {@value #REPLAN_PLAN_ECHO_MAX} 条、每条描述截断，避免把规划
     * prompt 撑爆（这本身也是「上下文瘦身」要管的量）。
     */
    private static final int REPLAN_PLAN_ECHO_MAX = 12;
    private static final int REPLAN_PLAN_DESC_MAX = 120;

    static String renderPreviousPlanForReplan(TaskChain chain) {
        try {
            PrimaryGoal primary = chain.currentPrimary();
            if (primary == null || primary.subtasks() == null || primary.subtasks().isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (Subtask s : primary.subtasks()) {
                if (s == null || shown >= REPLAN_PLAN_ECHO_MAX) {
                    break;
                }
                String desc = s.description() == null ? "" : s.description().trim();
                if (desc.length() > REPLAN_PLAN_DESC_MAX) {
                    desc = desc.substring(0, REPLAN_PLAN_DESC_MAX) + "…";
                }
                sb.append("  ").append(shown + 1).append(". ").append(desc);
                java.util.Map<String, Object> cond = s.condition();
                if (cond != null && !cond.isEmpty()) {
                    sb.append("  [条件 ").append(cond.keySet()).append("]");
                }
                if (!s.resourceUses().isEmpty()) sb.append(" [resource_uses declaration=")
                        .append(com.dwinovo.numen.rdd.api.ResourceUse.declarations(s.resourceUses())).append("]");
                sb.append('\n');
                shown++;
            }
            if (shown == 0) {
                return "";
            }
            if (primary.subtasks().size() > shown) {
                sb.append("  …（另有 ").append(primary.subtasks().size() - shown).append(" 条未列）\n");
            }
            return sb.toString().strip();
        } catch (RuntimeException e) {
            // 渲染旧计划失败不该阻断重规划：没有对照物只是质量下降，不是功能中断
            LOG.warn("[rdd] 渲染上一版计划失败: {}", e.toString());
            return "";
        }
    }

    /**
     * 重规划输入②：当前一级<b>之后</b>的一级（主题 + 前置门）。
     *
     * <p>2026-10-28（主人第二点）：「我知道我还有备用装备……他知道这个是什么东西、拿来做什么的，
     * 所以他才好重新规划整条任务。当然还要把那个他之前做过的任务和后面还要做的任务全都发给他」。
     * 此前后面几级从来没进过重规划 prompt —— 规划器只能看见眼前这一级，自然写不出
     * "别去下界了，先回主世界补装备"这种跨阶段的判断。
     */
    static String renderRemainingPlanForReplan(TaskChain chain) {
        try {
            StringBuilder sb = new StringBuilder();
            java.util.List<PrimaryGoal> all = chain.goal().primaryGoals();
            PrimaryGoal cur = chain.currentPrimary();
            int idx = all.indexOf(cur);
            for (int i = idx + 1; i >= 0 && i < all.size(); i++) {
                PrimaryGoal p = all.get(i);
                sb.append("  ").append(i - idx).append(". ").append(p.description());
                if (!p.waitFor().isEmpty()) {
                    sb.append("（进入前需持有：");
                    java.util.List<String> needs = new java.util.ArrayList<>();
                    for (var r : p.waitFor()) {
                        needs.add(r.assetKey() + "×" + r.minimum());
                    }
                    sb.append(String.join("、", needs)).append("）");
                }
                sb.append('\n');
            }
            return sb.toString().strip();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * 重规划输入③：这条链<b>已经做过的事</b>（终态 + 尝试次数）。
     *
     * <p>此前只给"已完成阶段键"（一句话），重规划无从知道"她上一次已经试了两次、都是怎么败的"。
     * 有界（最多 {@value #REPLAN_DONE_MAX} 条），只列有终态的节点。
     */
    private static final int REPLAN_DONE_MAX = 20;

    static String renderDoneForReplan(TaskChain chain) {
        try {
            java.util.Map<String, com.dwinovo.numen.rdd.api.SubtaskStatus> statuses = chain.subtaskStatuses();
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (PrimaryGoal p : chain.goal().primaryGoals()) {
                if (shown >= REPLAN_DONE_MAX) {
                    break;
                }
                for (Subtask s : p.subtasks()) {
                    if (shown >= REPLAN_DONE_MAX) {
                        break;
                    }
                    var st = statuses.get(s.id());
                    if (st == null) {
                        continue;
                    }
                    String name = st.name();
                    if (!("COMPLETED".equals(name) || "SKIPPED".equals(name)
                            || "PAUSED".equals(name) || "FAILED".equals(name))) {
                        continue;
                    }
                    String desc = s.description() == null ? "" : s.description().trim();
                    if (desc.length() > REPLAN_PLAN_DESC_MAX) {
                        desc = desc.substring(0, REPLAN_PLAN_DESC_MAX) + "…";
                    }
                    sb.append("  - [").append(name).append("] ").append(desc);
                    int attempts = chain.attempts(s.id());
                    if (attempts > 0) {
                        sb.append("（试了 ").append(attempts).append(" 次）");
                    }
                    sb.append('\n');
                    shown++;
                }
            }
            if (shown == 0) {
                return "";
            }
            if (sb.length() == 0) {
                return "";
            }
            return sb.toString().strip();
        } catch (RuntimeException e) {
            return "";
        }
    }

}
