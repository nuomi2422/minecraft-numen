package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.TaskChain;

import java.util.Map;

/**
 * 044 B2 · 重规划准入的唯一消费者（2026-11-13 提取；同日由 f82 真红基线推进到修复批）。
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
 * <p><b>2026-11-13 修复批（相对 f82 红基线）</b>：
 * <ul>
 *   <li>预算改为<b>转换成功后才记账</b>：拒绝一律不花，接受才花一次有界预算；</li>
 *   <li>{@link Trigger#SURVIVAL} 复用现成的 ACTIVE 事实变化转换（{@code enterReplanningFromNegotiation}），
 *       不再被 {@code enterReplanningFromStuck} 的 RUNNING 条件拒绝；FAILURE 的 STUCK guard 原样保留；</li>
 *   <li>不把旧二级伪标成 FAILED/STALLED；协商/失败各自的 key 与上限、预算耗尽后的 park 观测原位不动；</li>
 *   <li>{@link #planningAttempt(Trigger)}：只有 FAILURE 让规划器看到“上一版被判不可执行”(1)，
 *       协商/生存抢占传 0，宿主必须直接用这个值而不是按 {@code fromNegotiation} 猜。</li>
 * </ul>
 *
 * <p><b>触发源只表达来源</b>：{@link Trigger} 是程序给的；reason 文本只解释，不参与路由。
 */
final class RddReplanTransition {

    /** 谁发起的重规划：失败卡死 / 士兵协商 / 生存（安全）抢占。 */
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
        try {
            if (trigger == Trigger.NEGOTIATION || trigger == Trigger.SURVIVAL) {
                // 事实变化类（协商、生存抢占）复用现成的 ACTIVE 转换：允许 RUNNING，
                // 且不把旧二级伪标成 FAILED/STALLED；STUCK guard 原样保留给 FAILURE。
                chain.enterReplanningFromNegotiation(reason);
            } else {
                chain.enterReplanningFromStuck(reason);
            }
        } catch (RuntimeException ex) {
            // 拒绝不记账：只有真的进 REPLANNING 才花掉一次有界预算。
            return new Result(classify(ex), ex.toString());
        }
        counts.merge(budgetKey, 1, Integer::sum);
        if (onAccepted != null) {
            onAccepted.run();
        }
        return new Result(Outcome.ACCEPTED, "");
    }

    /**
     * 身体抢占的所有权判定：只停“进入前捕获的那个任务对象”。
     *
     * <p>{@code TaskSlot.record} 在槽里是稳定实例，换任务才换对象，所以同实例 = 同 owner；
     * 被替换（新动作已经派发）或已清空，一律不碰。纯对象相等判定，不建第二本账。
     */
    static boolean ownsCapturedBody(Object expectedAction, Object currentAction) {
        return expectedAction != null && expectedAction == currentAction;
    }

    /**
     * 恢复路由判定：只有“普通失败驱动、且调用方没跳过恢复”的请求才可能走缺失型恢复
     * （派恢复支线 / 插恢复节点）。生存抢占与协商都不走恢复路由，保证 typed 入口一定进
     * REPLANNING，不会被恢复支线提前 return 而让 Detector 把“已点名恢复”误签成“已抢占”。
     */
    static boolean usesRecoveryRoute(Trigger trigger, boolean skipRecoverySideTask) {
        return trigger == Trigger.FAILURE && !skipRecoverySideTask;
    }

    /**
     * 规划器 attempt 语义：只有<b>失败驱动</b>才表示“上一版被判不可执行”（1）；
     * 协商与生存抢占是事实变化（0），否则会给规划器灌“上一版不可执行”的假话。
     *
     * <p>宿主必须直接用这个值，不能再按 {@code fromNegotiation} 三元判断——SURVIVAL 不是协商。
     */
    static int planningAttempt(Trigger trigger) {
        return trigger == Trigger.FAILURE ? 1 : 0;
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
