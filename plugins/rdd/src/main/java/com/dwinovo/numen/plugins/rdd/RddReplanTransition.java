package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.TaskChain;
import com.dwinovo.numen.rdd.api.PrimaryGoalStatus;

import java.util.Map;
import java.util.UUID;

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
        /** 同链同 primary 的生存供食意图仍在，同型抢占不重复换计划/停体/花预算。 */
        DUPLICATE_SURVIVAL_PENDING,
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

    /**
     * 生存供食意图（typed 局部状态）。
     *
     * <p>生命周期：PENDING（重规划已被接受，尚未证明新计划真的落链）→ APPLIED（replace 成功，
     * 绑定当时的 chain.planRevision）。只认 token 身份：旧回调不得清/改新意图。
     * 只记录程序来源、链身份与计划代次，不从 description/reason 文本推断。
     */
    enum SurvivalState { PENDING, APPLIED }

    record SurvivalIntent(String chainInstanceId, String primaryId, RddSurvivalGate.Decision decision,
                          Object token, SurvivalState state, int requestedPlanRevision, Integer appliedPlanRevision) {
    }

    private static final Map<java.util.UUID, SurvivalIntent> SURVIVAL_INTENTS =
            new java.util.concurrent.ConcurrentHashMap<>();

    // 原 Detector 的同一份 60s 冷却；与意图一起失效，避免安全/死亡后新风险吃旧冷却。
    static final Map<UUID, Long> SURVIVAL_PREEMPT_AT = new java.util.concurrent.ConcurrentHashMap<>();

    private RddReplanTransition() {
    }

    /**
     * V04 三次 rev29/30/31 都是有效食物计划，却被同型饥饿反复替换（2026-11-13）。
     * 因此在预算/转换/停体之前核同一 typed 意图；不能靠加预算、延长冷却或描述文本认食物计划。
     * PENDING 无明确结果仍是 UNKNOWN；APPLIED 只保护实际应用的那一代计划。
     */
    static Result requestSurvival(UUID companionId, TaskChain chain, RddSurvivalGate.Decision decision,
                                  String reason, Map<String, Integer> counts, String budgetKey, int limit,
                                  Runnable onAccepted, Object token) {
        if (companionId == null || token == null || decision == null || decision == RddSurvivalGate.Decision.NONE
                || chain == null || chain.currentPrimary() == null) {
            return new Result(Outcome.ILLEGAL, "survival requires typed decision, owner and token");
        }
        SurvivalIntent current = SURVIVAL_INTENTS.get(companionId);
        if (current != null && sameScope(current, chain) && current.decision() == decision
                && (current.state() == SurvivalState.PENDING
                        ? current.requestedPlanRevision() == chain.planRevision()
                        : current.appliedPlanRevision() == chain.planRevision())) {
            return new Result(Outcome.DUPLICATE_SURVIVAL_PENDING, "same survival intent is still pending");
        }
        // 新请求被拒绝时不能先抹掉旧有效意图；仅真正接受的新请求才替换它。
        String chainId = chain.chainInstanceId();
        String primaryId = chain.currentPrimary().id();
        int requestedRevision = chain.planRevision();
        try {
            return request(chain, Trigger.SURVIVAL, reason, counts, budgetKey, limit, () -> {
                // 接受后、停体回调前装 PENDING：回调异常/失效不能被返回后的 put 复活。
                SURVIVAL_INTENTS.put(companionId, new SurvivalIntent(chainId, primaryId, decision,
                        token, SurvivalState.PENDING, requestedRevision, null));
                if (onAccepted != null) onAccepted.run();
            });
        } catch (RuntimeException ex) {
            abortPlan(companionId, token, chain);
            throw ex;
        }
    }

    private static boolean sameScope(SurvivalIntent intent, TaskChain chain) {
        return chain != null && chain.currentPrimary() != null
                && intent.chainInstanceId().equals(chain.chainInstanceId())
                && intent.primaryId().equals(chain.currentPrimary().id());
    }

    /**
     * 新计划 replace 成功后才绑定 appliedPlanRevision；旧回调 token 不匹配时什么都不做，
     * 因此“旧回调清掉新意图”不会发生。
     */
    static void markApplied(UUID companionId, Object token, int appliedPlanRevision) {
        if (companionId == null) return;
        SURVIVAL_INTENTS.computeIfPresent(companionId, (id, cur) -> cur.token() == token
                ? new SurvivalIntent(cur.chainInstanceId(), cur.primaryId(), cur.decision(), token,
                        SurvivalState.APPLIED, cur.requestedPlanRevision(), appliedPlanRevision)
                : cur);
    }

    /**
     * 空回/替换异常/server 缺失导致回落原计划：明确失败才释放（允许重试）。
     * 旧 token 不碰新意图；UNKNOWN（本轮未回调）不在此释放。
     */
    static void markAborted(UUID companionId, Object token) {
        if (companionId == null) return;
        SURVIVAL_INTENTS.computeIfPresent(companionId, (id, cur) -> {
            if (cur.token() != token) return cur;
            SURVIVAL_PREEMPT_AT.remove(id);
            return null;
        });
    }

    /** 明确应用失败的真实消费者；旧 callback 不得回退新意图正在使用的链。 */
    static void abortPlan(UUID companionId, Object token, TaskChain chain) {
        SurvivalIntent current = companionId == null ? null : SURVIVAL_INTENTS.get(companionId);
        if (current != null && current.token() != token) return;
        markAborted(companionId, token);
        // 安全观测可能已清 marker，原有效回调仍须回落；旧生命周期回调由原 ticket 拦截。
        if ((current == null || sameScope(current, chain)) && chain != null
                && chain.primaryStatus() == PrimaryGoalStatus.REPLANNING) {
            chain.resumeFromReplanning();
        }
    }

    /** 服务端适配器只交实际server引用；缺失判定与释放在同一可执行消费者中。 */
    static boolean requirePlanningServer(UUID companionId, Object token, TaskChain chain, Object server) {
        if (server != null) return true;
        abortPlan(companionId, token, chain);
        return false;
    }

    /**
     * 2026-11-13：空回、替换失败和替换后观测失败必须分开，不能靠宿主源码pin签通过。
     * 真正的替换由原宿主动作执行；本消费者负责判空、失败释放、成功绑定实际rev。
     * 保存/发布放在绑定之后、释放catch之外：已落链的计划不能因观测异常失去保护。
     */
    static <T> boolean applySurvivalPlan(UUID companionId, Object token, TaskChain chain,
                                       java.util.List<?> specs, java.util.function.Supplier<T> replace,
                                       java.util.function.Consumer<T> afterApplied) {
        if (specs == null || specs.isEmpty()) {
            abortPlan(companionId, token, chain);
            return false;
        }
        T applied;
        try {
            applied = replace.get();
        } catch (RuntimeException ex) {
            abortPlan(companionId, token, chain);
            throw ex;
        }
        markApplied(companionId, token, chain.planRevision());
        afterApplied.accept(applied);
        return true;
    }

    /** NONE 仅代表风险消退，不是供食完成；清同一份冷却使随后新风险可重新评估。 */
    static void observeSafe(UUID companionId) {
        invalidate(companionId);
    }

    static boolean hasSurvivalIntent(UUID companionId) {
        return companionId != null && SURVIVAL_INTENTS.containsKey(companionId);
    }

    /** 撤销 runtime / 换目标 / external_world_reset 共用，不改链历史或预算。 */
    static void invalidate(UUID companionId) {
        if (companionId == null) return;
        SURVIVAL_INTENTS.remove(companionId);
        SURVIVAL_PREEMPT_AT.remove(companionId);
    }

    /** 死亡打断 PENDING：先作废原异步票，再回落原链，不能留下永远 REPLANNING。 */
    static void onDeath(UUID companionId, TaskChain chain, Runnable invalidatePlanning) {
        // 即使刚观察过安全并清了 marker，死亡前的规划也属于旧身体，仍须作废原票。
        invalidatePlanning.run();
        invalidate(companionId);
        if (chain != null && chain.primaryStatus() == PrimaryGoalStatus.REPLANNING) {
            chain.resumeFromReplanning();
        }
    }

    /** ServerStoppedEvent：静态内存不得带进同存档的下一次 server。 */
    static void clearAll() {
        SURVIVAL_INTENTS.clear();
        SURVIVAL_PREEMPT_AT.clear();
    }

    /**
     * 一次重规划请求：判预算 → 转换成功 → 记账 → 接受后回调。
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
