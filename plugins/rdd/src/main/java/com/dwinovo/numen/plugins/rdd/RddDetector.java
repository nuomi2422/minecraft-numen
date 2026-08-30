package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.HardCodedEvaluator;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.core.TaskChain;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * RDD 的硬编码检测驱动器：服务端每 tick 跑一次（节流到 1 秒），对每个活跃任务链
 * 读<b>真实背包</b>，用 {@link HardCodedEvaluator} 判定当前 HARD_CODED 二级目标是否满足，
 * 满足则推进任务链。
 *
 * <p>这是 RDD「检测说话」的落地：不读 AI 自报、不读工具返回，只读世界真身。
 * 二级目标完成 → 自动推进 → 全部完成 → 简化 Supervisor（真实检测已证实）确认 → 目标完成。
 */
final class RddDetector {
    private static final Logger LOG = LoggerFactory.getLogger(RddDetector.class);
    /** 每多少 tick 检测一次；20 tick = 1 秒。 */
    private static final int TICKS_PER_CHECK = 20;

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
            // 只在 ACTIVE（正在执行二级目标）时检测；AWAITING/COMPLETED/REPLANNING 不碰。
            if (chain.primaryStatus() != PrimaryGoalStatus.ACTIVE) {
                return;
            }
            Subtask current = chain.currentSubtask();
            if (current.detectionMode() != DetectionMode.HARD_CODED
                    || chain.currentSubtaskStatus() != SubtaskStatus.RUNNING) {
                return;
            }
            Map<String, Integer> counts = countInventory(ap);
            if (!HardCodedEvaluator.matches(current.condition(), counts)) {
                return;
            }
            boolean completed = rt.applyHardCoded(current.id(), true);
            if (!completed) {
                return;
            }
            LOG.info("[rdd] 二级目标完成: {} ({})", current.id(), current.description());
            // 二级全完成 → 一级进入 AWAITING_SUPERVISOR：所有 HARD_CODED 条件已被真实检测
            // 证实，Supervisor 直接确认（AI 评估器作为兜底在本轮不接管）。
            if (chain.primaryStatus() == PrimaryGoalStatus.AWAITING_SUPERVISOR) {
                rt.applySupervisor(new SupervisorDecision(
                        SupervisorDecisionType.CONFIRM,
                        chain.currentPrimary().id(),
                        "all hard-coded conditions met in the real world"));
                LOG.info("[rdd] 一级目标完成: {}", chain.currentPrimary().description());
            }
        } catch (RuntimeException e) {
            // 检测失败不能拖垮服务端 tick。
            LOG.warn("[rdd] 检测 tick 异常: {}", e.toString());
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
