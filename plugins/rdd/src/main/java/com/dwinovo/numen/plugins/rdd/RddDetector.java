package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.HardCodedEvaluator;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.dwinovo.numen.rdd.fail.FailureClassifier;
import com.dwinovo.numen.rdd.fail.FailureContext;
import com.dwinovo.numen.rdd.fail.FailureEvent;
import com.dwinovo.numen.rdd.fail.FailureKind;
import com.dwinovo.numen.rdd.fail.RecoveryAction;
import com.dwinovo.numen.rdd.fail.RecoveryDecision;
import com.dwinovo.numen.rdd.fail.RecoveryPlan;
import com.dwinovo.numen.rdd.fail.RecoveryPolicy;
import com.dwinovo.numen.rdd.policy.ResourceBudget;
import com.dwinovo.numen.rdd.policy.RiskGate;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RDD 的硬编码检测驱动器 + 身体执行桥：服务端每 tick 跑一次（节流到 1 秒）。
 *
 * <p>对每个活跃任务链：
 * <ol>
 *   <li>当前二级 PENDING → 自动启动（首个由 sink 启动，后续由这里推进）</li>
 *   <li>二级带 body 且未提交 → 经 {@link ToolRegistry#resolve} 找身体工具直接
 *       {@code onServerCall} 提交（每二级只提交一次）</li>
 *   <li>读<b>真实背包</b>，HARD_CODED 满足 → 推进</li>
 *   <li>身体任务已结束（槽空）但条件未满足 → 重试 ≤{@link #MAX_BODY_RETRIES}，否则 markFailed</li>
 * </ol>
 *
 * <p>只读世界真身，不读 AI 自报。二级全完成 → 简化 Supervisor CONFIRM → 一级完成。
 */
final class RddDetector {
    private static final Logger LOG = LoggerFactory.getLogger(RddDetector.class);
    /** 每多少 tick 检测一次；20 tick = 1 秒。 */
    private static final int TICKS_PER_CHECK = 20;
    /** 身体任务结束但条件未达成时的最大重试次数。 */
    private static final int MAX_BODY_RETRIES = 3;
    /** Level 2 重试：失败的当前二级最多自动重跑次数（每次让 AI 换策略再试）。 */
    private static final int MAX_SUBTASK_RETRIES = 2;
    /** 资产提前触发：单个 tick 内最多连跳过多少个"资产已满足"的二级（防极端长链死循环）。 */
    private static final int MAX_INSTANT_PASS = 32;

    /** 熔炉/容器观测：GUI 关闭后保留快照 + 容器内容指纹（燃料不算产出）。 */
    private final RddFurnaceWatch furnaceWatch = new RddFurnaceWatch();

    /** 卡死监督：指纹跟踪 + 拍醒 / 恢复 / 升级失败，顺带累计能力缺口。 */
    private final RddStallWatcher stallWatcher = new RddStallWatcher(furnaceWatch);

    /** Level 2 重试计数：绑定当前二级（二级变了才重置），避免被误清。 */
    private final Map<UUID, RetryState> retries = new ConcurrentHashMap<>();
    private record RetryState(String subtaskId, int count) {}
    /** 已达重试上限被"停车"为 FAILED 的二级（uuid→subtaskId）。停车后不再自动重试/nudge，只留资产检测。 */
    private final Map<UUID, String> gapParked = new ConcurrentHashMap<>();
    /** 停车守望：停车后长期无进展要能再拍醒，拍醒预算耗尽要发可见事件（不能无声冻结）。 */
    private final RddParkedWatcher parkedWatcher = new RddParkedWatcher(stallWatcher);

    private final RddSurplus surplus = new RddSurplus();

    private int tickCounter;
    /** 资产 populate 节流：每 5 次检测（约 5 秒）把背包物品写进 AssetRegistry。 */
    private int assetTick;
    /** 世界资产只观察已加载范围；每 30 秒一次，避免把“资产库”变成全图扫描器。 */
    private int worldAssetTick;

    RddDetector() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppedEvent event) -> {
                    furnaceWatch.clear();
                    stallWatcher.clearAll();
                    retries.clear();
                    gapParked.clear();
                    parkedWatcher.clearAll();
                    surplus.clear();
                    tickCounter = 0;
                    assetTick = 0;
                    worldAssetTick = 0;
                });
    }

    void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        if (++tickCounter < TICKS_PER_CHECK) {
            return;
        }
        tickCounter = 0;
        assetTick = (assetTick + 1) % 5;
        worldAssetTick = (worldAssetTick + 1) % 30;
        // 空转止血：每次心跳刷新监督开关（flag 文件由监测台/人写，pause=停拍醒）
        RddPlugin.refreshSupervisionFlag();
        // 重启恢复：磁盘有任务但内存无 → 加载为 RddRuntime（幂等，RECOVERING）
        RddPlugin.restoreRuntimes();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!(p instanceof NumenPlayer ap)) {
                continue;
            }
            RddRuntime rt = RddPlugin.runtime(ap.getUUID());
            if (rt == null) {
                continue;
            }
            tickRuntime(ap, rt);
        }
        // 持久化：保存活跃任务链（1 秒一次，文件小，原子写）
        RddPlugin.saveRuntimes();
    }

    private void tickRuntime(NumenPlayer ap, RddRuntime rt) {
        try {
            TaskChain chain = rt.chain();
            PrimaryGoalStatus ps = chain.primaryStatus();
            // 真实背包先扫+缓存（规划注入数据源）；缓存是女仆属性，收尾/监督态也照常更新。
            Map<String, Integer> counts = countInventory(ap);
            surplus.inspect(ap, chain, counts);
            RddPlugin.cacheInventory(ap.getUUID(), counts);
            // Observe even parked/failed/unexpanded chains. A control-state early
            // return must not make the monitoring station appear frozen.
            if (assetTick == 0) populateAssets(ap, rt, chain.currentSubtask(), counts);
            if (worldAssetTick == 0) {
                RddWorldAssetObserver.Result observed = RddWorldAssetObserver.observe(ap, rt.assets());
                RddPlugin.saveAssets(ap.getUUID());
                RddPlugin.publishAssetSnapshot(ap.getUUID(), "lazy_world_observation", observed);
            }
            // 监督/收尾态不由检测驱动。
            if (ps == PrimaryGoalStatus.AWAITING_SUPERVISOR
                    || ps == PrimaryGoalStatus.REPLANNING
                    || ps == PrimaryGoalStatus.COMPLETED
                    || ps == PrimaryGoalStatus.FAILED) {
                return;
            }
            // P0-4 重启恢复：在途链恢复为 RECOVERING 后不许静默瞒报续跑——先发恢复事件，
            // 再按真实资产检测照常推进（资产已满足会走 early-achievement 直接验收；
            // 未满足就恢复监督/卡死/重试，与 FAILED 的"真实资产推进照常"哲学一致）。
            if (ps == PrimaryGoalStatus.RECOVERING) {
                RddPlugin.publishTaskSnapshot(ap.getUUID(), "chain_recovering");
                RddMonitor.publish("chain_recovering", Map.of(
                        "companionId", ap.getUUID().toString(),
                        "primary", chain.currentPrimary().id(),
                        "reason", "in-flight chain restored after restart; resuming under live observation"));
                rt.resumeFromRecovering();
            }
            // 刚推进到新的当前一级(PENDING/WAITING)：先过依赖门(wait_for)，没过就保持 WAITING 不派给 AI。
            if (ps == PrimaryGoalStatus.PENDING || ps == PrimaryGoalStatus.WAITING) {
                // 观察行(懒边界)：当前一级到达但未展开 -> 上报目标驱动器(展开权持有者)，Detector 绝不自己展开/调 LLM。
                if (chain.currentPrimary().unexpanded()) {
                    RddGoalDriver.needExpansion(ap.getUUID());
                    RddPlugin.reportExpansionNeeded(ap.getUUID(),
                            chain.currentPrimary().id(), chain.currentPrimary().description());
                    return;
                }
                if (!rt.activateCurrentFromRegistry()) {
                    RddMonitor.publish("primary_waiting", Map.of(
                            "primary", chain.currentPrimary().id(),
                            "reason", "dependency assets not present"));
                    RddPlugin.publishTaskSnapshot(ap.getUUID(), "primary_dependency_waiting");
                    return;
                }
                RddMonitor.publish("dependency_met", Map.of("primary", chain.currentPrimary().id()));
            }
            if (chain.primaryStatus() != PrimaryGoalStatus.ACTIVE) {
                return;
            }
            // 自动启动当前二级（首个由 sink 启动，推进后由这里继续；AI_ASSISTED 也在内）。
            if (chain.currentSubtaskStatus() == SubtaskStatus.PENDING) {
                rt.startCurrent();
            }
            SubtaskStatus status = chain.currentSubtaskStatus();
            // STALLED → 监督恢复分支：行为恢复则回到 RUNNING，多次拍醒无效则升级失败。
            if (status == SubtaskStatus.STALLED) {
                stallWatcher.handleStalled(ap, rt, chain.currentSubtask());
                return;
            }
            // FAILED → 先重跑真实资产检测：条件此刻已满足(如 AI 失败后自己攒够) → 直接验收推进
            //（含暂停期，对齐增2"只停主动干预、真实资产检测+推进照常"；FAILED 只表"那次尝试失败"非"目标未达成"）。
            // applyHardCodedResult 要求 RUNNING → 先 FAILED→PENDING(retry)→RUNNING(start) 再走正常完成路径。
            // 仍未满足 → Level 2 局部恢复：预算内自动重跑该二级（AI 换策略再试）。
            if (status == SubtaskStatus.FAILED) {
                Subtask cur = chain.currentSubtask();
                if (cur.detectionMode() == DetectionMode.HARD_CODED
                        && conditionMatches(ap, cur, counts)) {
                    RddMonitor.publish("early_achievement", Map.of(
                            "subtask", cur.id(), "description", cur.description(),
                            "reason", "FAILED but assets now satisfied, completed on real inventory"));
                    rt.chain().retrySubtask(cur.id()); // FAILED→PENDING（仅 FAILED 可 retry）
                    rt.startCurrent();                 // PENDING→RUNNING
                    completeSubtask(ap, rt, cur);      // applyHardCoded(RUNNING)→COMPLETED+推进
                    return;
                }
                handleSubtaskFailure(ap, rt, cur);
                return;
            }
            if (status != SubtaskStatus.RUNNING) {
                return;
            }
            // ==== 资产提前触发(EarlyAchievement)：资产已满足的硬编码二级不拍醒/不派身体，直接过，
            // 同 tick 连跳一串已满足的二级；到一级边界自动过依赖门进入下一级。 ====
            int guard = 0;
            while (chain.primaryStatus() == PrimaryGoalStatus.ACTIVE && guard++ < MAX_INSTANT_PASS) {
                if (chain.currentSubtaskStatus() == SubtaskStatus.PENDING) {
                    if (chain.currentSubtask().detectionMode() != DetectionMode.HARD_CODED) {
                        break;
                    }
                    rt.startCurrent();
                }
                if (chain.currentSubtaskStatus() != SubtaskStatus.RUNNING) {
                    break;
                }
                Subtask cur = chain.currentSubtask();
                if (cur.detectionMode() != DetectionMode.HARD_CODED) {
                    break;
                }
                if (!conditionMatches(ap, cur, counts)) {
                    break;
                }
                RddMonitor.publish("early_achievement", Map.of(
                        "subtask", cur.id(), "description", cur.description(),
                        "reason", "assets already present, skipped AI execution"));
                completeSubtask(ap, rt, cur);
                if (chain.currentSubtask() == cur && chain.primaryStatus() == PrimaryGoalStatus.ACTIVE) return;
                if (chain.primaryStatus() == PrimaryGoalStatus.PENDING) {
                    // 一级全完成被 CONFIRM → 进入下一级：下一级可能未展开(懒边界)→ 只上报驱动器，绝不 activate(会抛)
                    if (chain.currentPrimary().unexpanded()) {
                        RddGoalDriver.needExpansion(ap.getUUID());
                        RddPlugin.reportExpansionNeeded(ap.getUUID(),
                                chain.currentPrimary().id(), chain.currentPrimary().description());
                        break;
                    }
                    if (rt.activateCurrentFromRegistry()) {
                        RddMonitor.publish("dependency_met", Map.of("primary", chain.currentPrimary().id()));
                    } else {
                        RddMonitor.publish("primary_waiting", Map.of(
                                "primary", chain.currentPrimary().id(),
                                "reason", "dependency assets not present"));
                        RddPlugin.publishTaskSnapshot(ap.getUUID(), "primary_dependency_waiting");
                        break;
                    }
                }
                counts = countInventory(ap);
            }
            if (chain.primaryStatus() != PrimaryGoalStatus.ACTIVE) {
                return;
            }
            Subtask current = chain.currentSubtask();
            // AI_ASSISTED 二级交还 AI 驱动，不做卡死监督（保持原语义）。
            if (current.detectionMode() != DetectionMode.HARD_CODED) {
                return;
            }
            if (chain.currentSubtaskStatus() != SubtaskStatus.RUNNING) {
                return;
            }
            // 资产 populate：把背包物品写进 AssetRegistry（节流），rdd_status 据此报真实资产。
            // 卡死监督：资产指纹（背包+位置）连续未变化 → 判 STALLED 并拍醒将军。
            // 空转止血：暂停监督 → 跳过卡死检测与身体驱动(不 nudge / 不自动重派)，只留资产检测推进。
            if (RddPlugin.supervisionEnabled() && stallWatcher.track(ap, rt, current)) {
                return;
            }
            // assist 协助模式下暂停自动工具提交(防双驾驶):工具执行交还 NUMEN 内置 AI,
            // RDD 只保留资产检测 / 目标完成判定 / 异常提醒。
            if (RddPlugin.supervisionEnabled() && RddPlugin.bodySubmissionEnabled()) {
                RddBodyDispatcher.maybeSubmit(ap, current);
            }
            if (conditionMatches(ap, current, counts)) {
                completeSubtask(ap, rt, current);
                return;
            }
            maybeRetryOrFail(ap, rt, current);
        } catch (RuntimeException e) {
            // 检测失败不能拖垮服务端 tick。
            LOG.warn("[rdd] 检测 tick 异常: {}", e.toString());
        } finally {
            if (assetTick == 0) RddPlugin.publishTaskSnapshot(ap.getUUID(), "periodic_observation");
        }
    }

    /** FAILED 二级的 Level 2 局部恢复：预算内重置重跑 + 拍醒提示换策略；预算耗尽 → Level 3。 */
    private void handleSubtaskFailure(NumenPlayer ap, RddRuntime rt, Subtask current) {
        if (!RddPlugin.supervisionEnabled()) {
            return; // 空转止血：暂停监督不自动重跑/不引导自编译，保持 FAILED 等主人
        }
        RetryState rs = retries.get(ap.getUUID());
        int n = (rs != null && rs.subtaskId().equals(current.id())) ? rs.count() : 0;
        if (n >= MAX_SUBTASK_RETRIES) {
            if (RddOptionalFood.canSkip(current, countInventory(ap))
                    && CompanionTickDispatcher.currentTaskFor(ap.getUUID()) == null) {
                String reason = "optional food unavailable or unreachable; continue mainline";
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


    /** 是否有“备用装备”：至少两件铁/钻石胸甲（一件在穿 + 一件备用）——保守近似。 */
    private static boolean hasBackupEquipment(Map<String, Integer> inv) {
        int chest = inv.getOrDefault("minecraft:iron_chestplate", 0)
                + inv.getOrDefault("minecraft:diamond_chestplate", 0);
        return chest >= 2;
    }

    /** 是否已有基地（world_base 观测）。 */
    private static boolean hasBase(RddRuntime rt) {
        try {
            return rt.assets().snapshot().stream()
                    .anyMatch(e -> "world_base".equals(e.observation().type()));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** 按恢复计划给定向提醒（含动作与有序步骤；不改链状态，REPLAN 交主人/上层）。 */
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

    /** 把背包物品写进 AssetRegistry（GLOBAL 作用域，来源=当前二级）。观测证据：inventory_scan。 */
    private void populateAssets(NumenPlayer ap, RddRuntime rt, Subtask current, Map<String, Integer> counts) {
        try {
            String envId = ap.level().dimension().location().toString();
            Map<String, Integer> observed = new HashMap<>(counts);
            // A consumed item is an observed zero, not a permanently held asset.
            for (var entry : rt.assets().snapshot()) {
                if ("inventory_scan".equals(entry.observation().type())) observed.putIfAbsent(entry.assetId(), 0);
            }
            for (Map.Entry<String, Integer> e : observed.entrySet()) {
                Map<String, Object> value = new HashMap<>();
                value.put("count", e.getValue());
                Observation obs = new Observation(
                        "obs-" + e.getKey().hashCode() + "-" + System.nanoTime(),
                        "inventory_scan", "rdd_detector", envId,
                        System.currentTimeMillis(), value);
                rt.assets().apply(obs, e.getKey(), AssetScope.GLOBAL, current == null ? null : current.id());
            }
        } catch (RuntimeException ex) {
            // 资产 populate 失败不影响检测主流程
            LOG.warn("[rdd] 资产 populate 异常: {}", ex.toString());
        }
    }
    /** 世界真身满足条件 → 推进；二级全完成 → 简化 Supervisor CONFIRM。 */
    private void completeSubtask(NumenPlayer ap, RddRuntime rt, Subtask current) {
        if (surplus.hold(ap, rt.chain(), current, countInventory(ap))) return;
        boolean completed = rt.applyHardCoded(current.id(), true);
        RddPlugin.clearBody(ap.getUUID());
        retries.remove(ap.getUUID());
        stallWatcher.clear(ap.getUUID());
        gapParked.remove(ap.getUUID());
        parkedWatcher.clear(ap.getUUID());
        furnaceWatch.remove(ap.getUUID());
        if (!completed) {
            return;
        }
        LOG.info("[rdd] 二级目标完成: {} ({})", current.id(), current.description());
        RddMonitor.publish("subtask_completed", Map.of(
                "subtask", current.id(), "description", current.description(), "condition", current.condition(),
                "companionId", ap.getUUID().toString(), "evidenceSource",
                com.dwinovo.numen.rdd.core.WorldFactConditions.knownType(current.condition().get("type"))
                        ? "server_world_fact" : "server_inventory"));
        RddPlugin.publishTaskSnapshot(ap.getUUID(), "subtask_completed");
        TaskChain chain = rt.chain();
        // P0 辅助留档：二级完成细节（不用于恢复，供后续 Context/Planner 参考）
        RddPlugin.recordSubtaskFact(ap.getUUID(), chain.goal(), chain.currentPrimary().description(), current.description());
        if (chain.primaryStatus() == PrimaryGoalStatus.AWAITING_SUPERVISOR) {
            PrimaryGoal completedPrimary = chain.currentPrimary();
            rt.applySupervisor(new SupervisorDecision(
                    SupervisorDecisionType.CONFIRM,
                    chain.currentPrimary().id(),
                    "all hard-coded conditions met in the real world"));
            // P0：把"该阶段已完成"记为可靠事实（跨重绑/跨重启继承，防已达成阶段被重跑）
            RddPlugin.recordStageFact(ap.getUUID(), chain.goal(), completedPrimary.description());
            LOG.info("[rdd] 一级目标完成: {}", completedPrimary.description());
            RddMonitor.publish("goal_completed", Map.of(
                    "goal", completedPrimary.id(), "description", completedPrimary.description(),
                    "companionId", ap.getUUID().toString()));
            RddPlugin.publishTaskSnapshot(ap.getUUID(), "primary_completed");
            RddPlugin.clearBody(ap.getUUID());
        }
    }

    /** 给当前二级派过身体、身体任务已不在位、条件仍未满足 → 重试或判失败。 */
    private void maybeRetryOrFail(NumenPlayer ap, RddRuntime rt, Subtask current) {
        if (current.body() == null) {
            return;
        }
        var state = RddPlugin.bodyState(ap.getUUID());
        if (state == null || !state.subtaskId().equals(current.id())) {
            return; // 没给这个二级派过身体
        }
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
            rt.chain().markFailed(current.id(), "body task ended without satisfying condition");
            RddPlugin.clearBody(ap.getUUID());
            LOG.warn("[rdd] 二级目标失败（身体任务结束未达成）: {}", current.id());
            RddMonitor.publish("subtask_failed", Map.of(
                    "subtask", current.id(), "reason", "body task ended without satisfying condition"));
        }
    }

    static boolean conditionMatches(NumenPlayer ap, Subtask task, Map<String, Integer> counts) {
        if (task.condition().containsKey("type") && !"inventory".equals(task.condition().get("type")))
            return com.dwinovo.numen.rdd.core.WorldFactConditions.valid(task.condition())
                    && RddWorldFacts.matches(ap, task.condition());
        return HardCodedEvaluator.matches(task.condition(), counts);
    }

    /** 统计背包里每种物品的数量，用命名空间 ID（minecraft:oak_log）作 key。 */
    static Map<String, Integer> countInventory(NumenPlayer ap) {
        Map<String, Integer> counts = new HashMap<>();
        var inv = ap.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String key = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            counts.merge(key, stack.getCount(), Integer::sum);
        }
        return counts;
    }
}
