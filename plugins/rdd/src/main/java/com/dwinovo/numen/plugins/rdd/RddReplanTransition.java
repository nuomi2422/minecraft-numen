package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.TaskChain;

import java.util.Map;

/**
 * 044 B2 · 重规划准入的唯一消费者（2026-11-13 首阶段机械提取，行为与提取前逐字一致）。
 *
 * <p><b>为什么要这个类</b>：V03 实机（{@code attack-core-V03/latest.log:15906-15927}）里，
 * 生存抢占在 RUNNING 上走失败驱动入口，被 {@code TaskChain.enterReplanningFromStuck} 抛
 * {@code current subtask is not stuck: RUNNING}，{@code RddPlugin} 捕获后返回 false，链停在 ACTIVE、
 * 没有新任务。要能把“拒绝也要花钱吗”“来源是程序还是文本”这些契约冻结成可执行的测试，
 * 准入与预算记账必须是同一段产品代码，而不是测试另写一份。
 *
 * <p><b>为什么在 plugins:rdd 而不是 rdd-core</b>：它是插件的宿主决策（预算键/上限、入口选择、
 * 接受后清理身体派发记录），不是链自己的状态机；并且 {@code plugins:rdd} 的测试 JVM 没有 MC 类，
 * 放进独立纯 JVM 类后，测试用真 {@link TaskChain} 调它即可，不必加载 {@code RddPlugin}。
 *
 * <p><b>首阶段刻意保留的旧行为</b>：
 * <ul>
 *   <li>预算先判、<b>先 merge 再转换</b>（所以“拒绝也花 1 次”仍会发生，是真反例的一部分）；</li>
 *   <li>{@link Trigger#SURVIVAL} 仍走 {@code enterReplanningFromStuck}，RUNNING 仍被拒；</li>
 *   <li>协商/失败各自的 key 与上限、预算耗尽后的 park 观测都由调用方原位保留。</li>
 * </ul>
 *
 * <p><b>触发源只表达来源</b>：{@link Trigger} 是程序给的；reason 文本只解释，不参与路由。
 */
final class RddReplanTransition {

    /** 谁发起的重规划。首阶段三者中 SURVIVAL 与 FAILURE 同路。 */
    enum Trigger { FAILURE, NEGOTIATION, SURVIVAL }

    /** 准入结果；除 ACCEPTED 外都表示链未进入 REPLANNING。 */
    enum Outcome {
        ACCEPTED,
        BUDGET_EXHAUSTED,
        NOT_ACTIVE,
        NOT_EXPANDED,
        NO_SUBTASK,
        NOT_STUCK,
        ILLEGAL
    }

    /** 结果 + 原异常文本（调用方要按旧格式打 warn，不重编原因）。 */
    record Result(Outcome outcome, String error) {
        boolean accepted() {
            return outcome == Outcome.ACCEPTED;
        }
    }

    private RddReplanTransition() {
    }

    /**
     * 一次重规划请求：判预算 →（旧行为）计数 → 转换状态 → 接受后回调。
     *
     * @param counts    调用方的预算表（生产是 {@code RddPlugin.REPLAN_COUNTS}）
     * @param budgetKey 预算键（失败/生存共用，协商带 {@code |nego}）
     * @param limit     该来源的上限
     * @param onAccepted 仅在接受成功后执行一次（真实宿主用它清身体派发记录）；拒绝时绝不执行
     */
    static Result request(TaskChain chain, Trigger trigger, String reason,
                          Map<String, Integer> counts, String budgetKey, int limit,
                          Runnable onAccepted) {
        if (chain == null) return new Result(Outcome.ILLEGAL, "no active RDD task chain");
        if (chain.currentPrimary() == null) return new Result(Outcome.ILLEGAL, "no current primary");
        int used = counts.getOrDefault(budgetKey, 0);
        if (used >= limit) {
            return new Result(Outcome.BUDGET_EXHAUSTED, "replan budget exhausted: " + used + "/" + limit);
        }
        // 首阶段机械提取：保留“先计数、后转换”的旧行为，拒绝仍消耗预算（V03 冻结反例依赖它）。
        counts.merge(budgetKey, 1, Integer::sum);
        try {
            if (trigger == Trigger.NEGOTIATION) {
                chain.enterReplanningFromNegotiation(reason);
            } else {
                // SURVIVAL 首阶段与 FAILURE 同路：RUNNING 仍会被明确拒绝，不伪标 STUCK。
                chain.enterReplanningFromStuck(reason);
            }
        } catch (RuntimeException ex) {
            return new Result(classify(ex), ex.toString());
        }
        if (onAccepted != null) {
            onAccepted.run();
        }
        return new Result(Outcome.ACCEPTED, "");
    }

    /**
     * 只把链的原有异常翻译成可读结果，用于日志与测试断言；不参与准入判断。
     *
     * <p>TaskChain 的拒绝文案稳定且有限（not stuck / only an ACTIVE / unexpanded / no current subtask）。
     */
    private static Outcome classify(RuntimeException ex) {
        String message = String.valueOf(ex.getMessage());
        if (message.contains("not stuck")) return Outcome.NOT_STUCK;
        if (message.contains("only an ACTIVE")) return Outcome.NOT_ACTIVE;
        if (message.contains("unexpanded")) return Outcome.NOT_EXPANDED;
        if (message.contains("no current subtask")) return Outcome.NO_SUBTASK;
        return Outcome.ILLEGAL;
    }
}
