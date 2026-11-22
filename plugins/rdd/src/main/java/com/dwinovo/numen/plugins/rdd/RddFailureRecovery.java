package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.dwinovo.numen.rdd.fail.*;
import com.dwinovo.numen.rdd.policy.ResourceBudget;
import com.dwinovo.numen.rdd.policy.RiskGate;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.dwinovo.numen.plugins.rdd.RddDetector.countInventory;
import static com.dwinovo.numen.plugins.rdd.RddDetector.LOG;

/** Per-detector failed-attempt recovery and parking, not a second recovery policy.
 * Reuses existing classifiers, optional-step policy and watcher instances.
 * Inventory reads remain fresh at their original call sites; no sample cache or body driver is added. */
final class RddFailureRecovery {
    /** 身体任务结束但条件未达成时的最大重试次数。 */
    private static final int MAX_BODY_RETRIES = 3;
    /** Level 2 重试：失败的当前二级最多自动重跑次数（每次让 AI 换策略再试）。 */
    private static final int MAX_SUBTASK_RETRIES = 2;

    /** Level 2 重试计数：绑定当前二级（二级变了才重置），避免被误清。 */
    private final Map<UUID, RetryState> retries = new ConcurrentHashMap<>();
    private record RetryState(String subtaskId, int count) {}
    /** 已达重试上限被"停车"为 FAILED 的二级（uuid→subtaskId）。停车后不再自动重试/nudge，只留资产检测。 */
    private final Map<UUID, String> gapParked = new ConcurrentHashMap<>();

    private final RddFurnaceWatch furnaceWatch;
    private final RddStallWatcher stallWatcher;
    private final RddParkedWatcher parkedWatcher;

    RddFailureRecovery(RddFurnaceWatch furnaceWatch, RddStallWatcher stallWatcher, RddParkedWatcher parkedWatcher) {
        this.furnaceWatch = furnaceWatch;
        this.stallWatcher = stallWatcher;
        this.parkedWatcher = parkedWatcher;
    }

    // Separate delegates preserve every caller's original cleanup scope and order.
    void clearRetries() { retries.clear(); }
    void clearParked() { gapParked.clear(); }
    void clearRetries(UUID companionId) { retries.remove(companionId); }
    void clearParked(UUID companionId) { gapParked.remove(companionId); }

    void parkRejectedDeathReplan(NumenPlayer ap, RddRuntime rt) {
        var chain = rt.chain();
        var current = chain.currentSubtask();
        var status = chain.currentSubtaskStatus();
        if (status == SubtaskStatus.RUNNING || status == SubtaskStatus.STALLED) {
            chain.markFailed(current.id(), "death settlement could not replan; old plan must not dispatch");
        }
        retries.put(ap.getUUID(), new RetryState(current.id(), MAX_SUBTASK_RETRIES));
        // Both are required: retries blocks retry/start; gapParked blocks another automatic failure replan.
        gapParked.put(ap.getUUID(), current.id());
        RddPlugin.clearBody(ap.getUUID());
        parkedWatcher.watch(ap, rt, current);
        RddPlugin.saveRuntimes();
        RddPlugin.publishTaskSnapshot(ap.getUUID(), "death_replan_rejected_parked");
    }

    void handleSubtaskFailure(NumenPlayer ap, RddRuntime rt, Subtask current) {
        if (!RddPlugin.supervisionEnabled()) {
            return; // 空转止血：暂停监督不自动重跑/不引导自编译，保持 FAILED 等主人
        }
        RetryState rs = retries.get(ap.getUUID());
        int n = (rs != null && rs.subtaskId().equals(current.id())) ? rs.count() : 0;
        if (n >= MAX_SUBTASK_RETRIES) {
            if (RddOptionalFood.canSkip(current, countInventory(ap))
                    && CompanionTickDispatcher.currentTaskFor(ap.getUUID()) == null) {
                String reason = "optional food unavailable or unreachable; continue mainline";
                if (RddOptionalFood.foodAlreadyCovered(current, countInventory(ap))) {
                    // 埋点：重复采集已充足的资源（食物目标已被背包/派生等价覆盖，仍派了采集）
                    RddInstrumentation.publish(RddInstrumentation.REPEAT_GATHER, Map.of(
                            "companionId", ap.getUUID().toString(),
                            "task", current.id(),
                            "reason", "gather step for already-covered food reached retry limit",
                            "context", Map.of("lastObservedInventory", countInventory(ap))));
                }
                rt.skipSubtask(current.id(), reason);
                RddPlugin.clearBody(ap.getUUID());
                retries.remove(ap.getUUID());
                gapParked.remove(ap.getUUID());
                parkedWatcher.clear(ap.getUUID());
                stallWatcher.clear(ap.getUUID());
                furnaceWatch.remove(ap.getUUID());
                RddPlugin.finishResolvedPrimary(ap.getUUID(), rt);
                RddMonitor.publish("subtask_skipped", Map.of(
                        "companionId", ap.getUUID().toString(), "subtask", current.id(), "reason", reason));
                RddPlugin.publishTaskSnapshot(ap.getUUID(), "optional_food_skipped");
                return;
            }
            // Exhausted retries do not identify the cause. Report once, then keep this subtask
            // "停车"为 FAILED：不 retrySubtask、也不清 retries（cap 清零会进 FAILED→重试→RUNNING→
            // body 重派→失败 的无限循环，每次 nudge 都会触发额外模型调用）。停车后只留真实资产检测——
            // AI 或主人真攒够资产，由 FAILED 分支的 early_achievement 自动验收推进，不堵恢复路径。
            if (!current.id().equals(gapParked.get(ap.getUUID()))) {
                gapParked.put(ap.getUUID(), current.id());
                parkedWatcher.clear(ap.getUUID());
                // 埋点：同一任务重启 ≥N 次仍失败 → loop_detected（记录一次，停车即出）
                RddInstrumentation.publish(RddInstrumentation.LOOP_DETECTED, Map.of(
                        "companionId", ap.getUUID().toString(),
                        "task", current.id(),
                        "reason", "same subtask restarted beyond retry budget",
                        "context", Map.of("attempts", n, "threshold", MAX_SUBTASK_RETRIES,
                                "lastObservedInventory", countInventory(ap))));
                // P2.0 接线：把“原因未分类的耗尽失败”交给硬分类器，按出口给可审计事件 + 定向提醒。
                // 仍只停车、不自动改链状态（REPLAN 交上层/主人决定），避免无限循环。
                Map<String, Integer> inv = countInventory(ap);
                FailureEvent fe = FailureEvent.of(current.id(), rt.chain().currentPrimary().id(),
                        FailureKind.UNKNOWN, "auto-retries exhausted; root cause not established");
                FailureContext fc = new FailureContext(hasBackupEquipment(inv), hasBase(rt), true, false);
                RecoveryDecision rd = FailureClassifier.classify(fe, fc);
                // P3：把出口落成“恢复动作 + 步骤骨架”（具体方案仍交 AI/Planner）
                List<String> gaps = ResourceBudget.missingFor(
                        RiskGate.levelForText(current.description()), inv);
                RecoveryPlan plan = RecoveryPolicy.plan(rd, gaps);
                RddPlugin.nudge(ap.getUUID(), nudgeForPlan(plan, current.description()));
                RddMonitor.publish("subtask_parked", Map.of(
                        "companionId", ap.getUUID().toString(), "subtask", current.id(),
                        "failureClass", fe.kind().name(), "recovery", rd.outcome().name(),
                        "action", plan.action().name(), "auto", rd.auto(), "reason", rd.reason()));
                if (plan.action() == RecoveryAction.REQUEST_REPLAN
                        && RddPlugin.requestReplan(ap.getUUID(), fe.reason())) {
                    // P4：转入重规划流程（进入 REPLANNING + 带真实状态重分解），不再 parking 守望
                    return;
                }
                // P2-B：把"这个任务值不值得/做不做得到"的判断结构化上报给 Supervisor（只读，不改链）。
                // 触发条件=资源耗尽停车；建议动作取自恢复计划，供监督层重新决策。
                RddTaskQualityReporter.report(ap, current,
                        failureKindToQuality(fe.kind()), rsSafeCount(ap), "",
                        plan.action().name());
                // 预算耗尽等导致未能重规划 → 回落停车守望（下面继续）
            }
            parkedWatcher.watch(ap, rt, current);
            return;
        }
        retries.put(ap.getUUID(), new RetryState(current.id(), n + 1));
        rt.chain().retrySubtask(current.id());
        rt.startCurrent();
        RddPlugin.nudge(ap.getUUID(), "这个目标（" + current.description() + "）失败了，再试一次。换个策略：检查材料、换工具、或换位置。");
        RddMonitor.publish("subtask_retry", Map.of("subtask", current.id(), "retry", n + 1, "max", MAX_SUBTASK_RETRIES));
    }

    private static boolean hasBackupEquipment(Map<String, Integer> inv) {
        int chest = inv.getOrDefault("minecraft:iron_chestplate", 0)
                + inv.getOrDefault("minecraft:diamond_chestplate", 0);
        return chest >= 2;
    }

    private static boolean hasBase(RddRuntime rt) {
        try {
            return rt.assets().snapshot().stream()
                    .anyMatch(e -> "world_base".equals(e.observation().type()));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static String nudgeForPlan(RecoveryPlan plan, String desc) {
        StringBuilder sb = new StringBuilder("「" + desc + "」失败诊断：" + plan.action().name() + "。");
        if (!plan.steps().isEmpty()) {
            sb.append("按顺序：").append(String.join(" → ", plan.steps())).append("。");
        }
        if (plan.action() == RecoveryAction.REQUEST_REPLAN) {
            sb.append("（原地重试无效，报告卡点请主人重下 /goal）");
        }
        return sb.toString();
    }

    void maybeRetryOrFail(NumenPlayer ap, RddRuntime rt, Subtask current) {
        if (current.body() == null) {
            return;
        }
        var state = RddPlugin.bodyState(ap.getUUID());
        if (state == null || !state.subtaskId().equals(current.id())) {
            return; // 没给这个二级派过身体
        }
        // Yield is a synchronous mutation: an empty body slot does not establish non-execution.
        // Preserve the original body state for explicit inspection; never silently resubmit it.
        if ("rdd_report_yield".equals(current.body().taskType())) return;
        // 身体任务还占着当前槽 = 还在跑，不判。
        if (CompanionTickDispatcher.currentTaskFor(ap.getUUID()) != null) {
            return;
        }
        // assist 协助模式下 RDD 不自动提交工具(防双驾驶):重试也跳过,直接判失败提醒。
        // 空转止血：暂停监督 → 不重派也不判失败，清掉身体状态静置等主人。
        if (!RddPlugin.supervisionEnabled()) {
            RddPlugin.clearBody(ap.getUUID());
            return;
        }
        if (state.submitCount() < MAX_BODY_RETRIES && RddPlugin.bodySubmissionEnabled()) {
            RddPlugin.rememberBody(ap.getUUID(), current.id(), state.submitCount() + 1);
            RddBodyDispatcher.resubmit(ap, current);
            LOG.info("[rdd] 身体任务结束未达成，重试 {} 次: {}", state.submitCount() + 1, current.id());
            RddMonitor.publish("subtask_retry", Map.of(
                    "subtask", current.id(), "retry", state.submitCount() + 1, "max", MAX_BODY_RETRIES));
        } else {
            // 埋点：身体任务被反复重启仍失败 → loop_detected（同任务重启超过 MAX_BODY_RETRIES）
            RddInstrumentation.publish(RddInstrumentation.LOOP_DETECTED, Map.of(
                    "companionId", ap.getUUID().toString(),
                    "task", current.id(),
                    "reason", "body task restarted beyond retry budget without satisfying condition",
                    "context", Map.of("attempts", state.submitCount(), "threshold", MAX_BODY_RETRIES)));
            rt.chain().markFailed(current.id(), "body task ended without satisfying condition");
            RddPlugin.clearBody(ap.getUUID());
            LOG.warn("[rdd] 二级目标失败（身体任务结束未达成）: {}", current.id());
            RddMonitor.publish("subtask_failed", Map.of(
                    "subtask", current.id(), "reason", "body task ended without satisfying condition"));
        }
    }

    private static com.dwinovo.numen.rdd.fail.TaskQualityReport.Reason failureKindToQuality(FailureKind kind) {
        if (kind == null) return com.dwinovo.numen.rdd.fail.TaskQualityReport.Reason.OTHER;
        return switch (kind) {
            case RESOURCE_MISSING -> com.dwinovo.numen.rdd.fail.TaskQualityReport.Reason.ASSET_UNREACHABLE;
            case TARGET_LOST -> com.dwinovo.numen.rdd.fail.TaskQualityReport.Reason.ASSET_UNREACHABLE;
            case SOFTWARE_DEFECT -> com.dwinovo.numen.rdd.fail.TaskQualityReport.Reason.SUSPECTED_PLAN_DEFECT;
            default -> com.dwinovo.numen.rdd.fail.TaskQualityReport.Reason.OTHER;
        };
    }

    private int rsSafeCount(NumenPlayer ap) {
        RetryState rs = retries.get(ap.getUUID());
        return rs == null ? 0 : rs.count();
    }
}
