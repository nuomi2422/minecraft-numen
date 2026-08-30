package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.HardCodedEvaluator;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

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
    private static final Gson GSON = new Gson();

    private int tickCounter;

    void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        if (++tickCounter < TICKS_PER_CHECK) {
            return;
        }
        tickCounter = 0;
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
    }

    private void tickRuntime(NumenPlayer ap, RddRuntime rt) {
        try {
            TaskChain chain = rt.chain();
            if (chain.primaryStatus() != PrimaryGoalStatus.ACTIVE) {
                return;
            }
            // 自动启动当前二级：首个由 sink 启动，推进后由这里继续。
            if (chain.currentSubtaskStatus() == SubtaskStatus.PENDING) {
                rt.startCurrent();
            }
            Subtask current = chain.currentSubtask();
            if (current.detectionMode() != DetectionMode.HARD_CODED
                    || chain.currentSubtaskStatus() != SubtaskStatus.RUNNING) {
                return;
            }
            maybeSubmitBody(ap, current);
            Map<String, Integer> counts = countInventory(ap);
            if (HardCodedEvaluator.matches(current.condition(), counts)) {
                completeSubtask(ap, rt, current);
                return;
            }
            maybeRetryOrFail(ap, rt, current);
        } catch (RuntimeException e) {
            // 检测失败不能拖垮服务端 tick。
            LOG.warn("[rdd] 检测 tick 异常: {}", e.toString());
        }
    }

    /** 当前二级带 body 且还没提交过 → 提交一次。 */
    private void maybeSubmitBody(NumenPlayer ap, Subtask current) {
        BodyInstruction body = current.body();
        if (body == null) {
            return;
        }
        var state = RddPlugin.bodyState(ap.getUUID());
        if (state != null && state.subtaskId().equals(current.id())) {
            return;
        }
        submitBody(ap, current);
        RddPlugin.rememberBody(ap.getUUID(), current.id(), 1);
    }

    /** 直接调身体工具的服务端实现（同 tick 线程，安全）；工具内部走 TaskDispatch.setTask。 */
    private void submitBody(NumenPlayer ap, Subtask current) {
        BodyInstruction body = current.body();
        NumenTool tool = ToolRegistry.resolve(body.taskType());
        if (tool == null) {
            LOG.warn("[rdd] 身体工具 {} 不存在，该二级只检测不执行", body.taskType());
            return;
        }
        JsonObject args = new JsonObject();
        if (body.args() != null) {
            body.args().forEach((k, v) -> args.add(k, GSON.toJsonTree(v)));
        }
        try {
            tool.onServerCall(RddPlugin.nextBodyCallId(), args, ap, reply -> { });
            LOG.info("[rdd] 已提交身体任务 {} -> {} {}", current.id(), body.taskType(), body.args());
        } catch (RuntimeException e) {
            LOG.warn("[rdd] 提交身体任务失败 {}: {}", body.taskType(), e.toString());
        }
    }

    /** 世界真身满足条件 → 推进；二级全完成 → 简化 Supervisor CONFIRM。 */
    private void completeSubtask(NumenPlayer ap, RddRuntime rt, Subtask current) {
        boolean completed = rt.applyHardCoded(current.id(), true);
        RddPlugin.clearBody(ap.getUUID());
        if (!completed) {
            return;
        }
        LOG.info("[rdd] 二级目标完成: {} ({})", current.id(), current.description());
        TaskChain chain = rt.chain();
        if (chain.primaryStatus() == PrimaryGoalStatus.AWAITING_SUPERVISOR) {
            rt.applySupervisor(new SupervisorDecision(
                    SupervisorDecisionType.CONFIRM,
                    chain.currentPrimary().id(),
                    "all hard-coded conditions met in the real world"));
            LOG.info("[rdd] 一级目标完成: {}", chain.currentPrimary().description());
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
        if (state.submitCount() < MAX_BODY_RETRIES) {
            RddPlugin.rememberBody(ap.getUUID(), current.id(), state.submitCount() + 1);
            submitBody(ap, current);
            LOG.info("[rdd] 身体任务结束未达成，重试 {} 次: {}", state.submitCount() + 1, current.id());
        } else {
            rt.chain().markFailed(current.id(), "body task ended without satisfying condition");
            RddPlugin.clearBody(ap.getUUID());
            LOG.warn("[rdd] 二级目标失败（身体任务结束未达成）: {}", current.id());
        }
    }

    /** 统计背包里每种物品的数量，用命名空间 ID（minecraft:oak_log）作 key。 */
    private Map<String, Integer> countInventory(NumenPlayer ap) {
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
