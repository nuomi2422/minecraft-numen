package com.dwinovo.numen.rdd.side.death;

import com.dwinovo.numen.rdd.side.SideTask;
import com.dwinovo.numen.rdd.side.SideTaskDecision;
import com.dwinovo.numen.rdd.side.SideTaskState;
import com.dwinovo.numen.rdd.side.SideTaskType;
import com.dwinovo.numen.rdd.side.SideTaskVerdict;
import com.dwinovo.numen.rdd.side.SideTaskWorldPort;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 真正的死亡恢复支线：主线冻结，执行 AI 先判断是否值得回收，再决定动作。
 *
 * <p>这里的 deadline 是防止支线无限冻结主线的业务保护线，不是 Minecraft 掉落物
 * 的 despawn 结论。掉落所在区块未加载时，世界端口必须返回 UNKNOWN/CHUNK_UNLOADED；
 * 只有观察到 RECOVERED 或 DROPS_ABSENT，支线才结束并允许上层重算损失影响。
 */
public final class DeathRecoverySideTaskType implements SideTaskType {

    public static final String TYPE_ID = DeathRecoverySideTaskTrigger.TYPE_ID;
    public static final String PHASE_DECIDE = "DECIDE_RECOVERY";
    public static final String PHASE_RECOVER = "RECOVER_DROPS";
    public static final String PHASE_REEQUIP = "REEQUIP_AND_RESUME";
    /** 已选择重置主线：等执行 AI 调 rdd_restart_mainline；不在这里越权改链。 */
    public static final String PHASE_RESET_MAINLINE = "RESET_MAINLINE";
    public static final String KEY_DECISION = "decision";

    /** 业务保护线；不代表掉落实体已经消失。 */
    public static final long DEADLINE_TICKS = 24_000L;

    @Override
    public String typeId() {
        return TYPE_ID;
    }

    @Override
    public long deadlineTicks() {
        return DEADLINE_TICKS;
    }

    @Override
    public SideTask create(CreationContext ctx) {
        Map<String, String> data = new LinkedHashMap<>(ctx.data());
        data.putAll(DeathRecoverySideTaskTrigger.take(ctx.companionId()));
        data.putIfAbsent("decision", "PENDING");
        return new SideTask(ctx.companionId(), ctx.instanceId(), TYPE_ID, ctx.generation(),
                ctx.scheduledGameDay(), ctx.gameTime(), ctx.deadlineGameTime(),
                SideTaskState.ACTIVE, PHASE_DECIDE, data, ctx.mainlineToken());
    }

    /** 死亡事实已存在，开之前不再由通用规则阻挡；危险取舍交给执行 AI。 */
    @Override
    public SideTaskDecision decide(UUID companionId, SideTaskWorldPort world) {
        return SideTaskDecision.EXECUTE;
    }

    @Override
    public SideTaskVerdict evaluate(SideTask task, SideTaskWorldPort world) {
        SideTaskWorldPort.DeathRecoveryFacts facts =
                world.deathRecoveryFacts(task.companionId(), task);
        Map<String, String> data = new LinkedHashMap<>(task.data());
        data.put("drop_state", facts.dropState().name());
        String rawDecision = data.getOrDefault(KEY_DECISION, "PENDING");
        DeathRecoveryDecision decision = "PENDING".equals(rawDecision)
                ? null : DeathRecoveryDecision.parse(rawDecision);
        if (!"PENDING".equals(rawDecision) && decision == null) {
            data.put("decision_rejected", "unknown_strategy");
            data.put(KEY_DECISION, "PENDING");
        }
        if (decision == DeathRecoveryDecision.ABANDON_RECOVERY) {
            data.put("terminal_reason", "executor_declined_recovery");
            return SideTaskVerdict.skip("执行 AI 在有限策略中选择放弃回收；交给损失影响分析", data);
        }
        if (decision == DeathRecoveryDecision.RESET_AND_RESTART) {
            // 169 T6 / 170 §5.1：旧主线（例如「再进下界取烈焰棒」）在装备全丢后已不安全。
            // 这里只冻结支线并等待执行 AI 显式调用 rdd_restart_mainline；
            // 不直接改链——策略工具没有、也不该有重写主线的权限。
            data.put("next_action", "rdd_restart_mainline");
            if (task.pastDeadline(world.gameTime())) {
                // 安全兜底：选了重置却一直没调用工具，不能把主线永久冻结。
                // 保护线到期后解冻，交给恢复规划按 UNKNOWN/未完成处理；不冒充已重置。
                data.put("terminal_reason", "reset_not_applied_before_deadline");
                return SideTaskVerdict.skip(
                        "已选择重置主线，但保护线到期仍未调用 rdd_restart_mainline；解冻主线交回恢复规划"
                                + "（旧链未被 reset，不能记 PASS）", data);
            }
            data.put("terminal_reason", "");
            return SideTaskVerdict.keep(PHASE_RESET_MAINLINE, data);
        }
        data.put("dangerous", Boolean.toString(facts.dangerous()));
        data.put("backup_available", Boolean.toString(facts.backupAvailable()));
        data.put("inventory_known", Boolean.toString(facts.inventoryKnown()));
        if (!facts.note().isBlank()) data.put("last_fact", facts.note());

        // 只有事实确认完成/不可回收才结束；UNKNOWN 和区块未加载绝不能伪装成丢失。
        if (facts.dropState() == SideTaskWorldPort.DeathDropState.RECOVERED) {
            data.put("terminal_reason", "drops_recovered");
            return SideTaskVerdict.complete("死亡批次掉落已观测回背包；进入备用/重新装战判定", data);
        }
        if (facts.dropState() == SideTaskWorldPort.DeathDropState.DROPS_ABSENT) {
            data.put("terminal_reason", "drops_absent_with_world_evidence");
            return SideTaskVerdict.skip("已观测掉落实体不可回收；交给损失影响分析，不把业务超时当物理消失", data);
        }
        if (task.pastDeadline(world.gameTime())) {
            data.put("terminal_reason", "recovery_decision_window_ended");
            return SideTaskVerdict.skip("恢复决策保护线到期；仍未拿到掉落事实，交给上层按 UNKNOWN 决定", data);
        }
        String phase = facts.dangerous() && facts.backupAvailable()
                ? PHASE_REEQUIP : PHASE_RECOVER;
        return SideTaskVerdict.keep(phase, data);
    }

    @Override
    public String renderContext(SideTask task, SideTaskWorldPort world) {
        String seq = task.data().getOrDefault("death_seq", "?");
        String at = task.data().getOrDefault("death_at", "死亡点未知");
        String dimension = task.data().getOrDefault("dimension", "维度未知");
        String state = task.data().getOrDefault("drop_state", "UNKNOWN");
        String dangerous = task.data().getOrDefault("dangerous", "false");
        String backup = task.data().getOrDefault("backup_available", "false");
        return "<death_recovery scope=SIDE death_seq=\"" + esc(seq)
                + "\" dimension=\"" + esc(dimension) + "\" death_at=\"" + esc(at)
                + "\" drop_state=\"" + esc(state) + "\" dangerous=\"" + esc(dangerous)
                + "\" backup_available=\"" + esc(backup) + ">"
                + "死亡恢复支线已接管，主线计算暂时冻结。先判断这批掉落是否值得回收："
                + "危险、距离、维度、区块加载状态、备用装备和原目标都要一起看。"
                + "若有备用装备且回死亡点会再次送命，先取备用/重新装战，再决定是否回收；"
                + "若就在附近且安全，可以直接回死亡点捡。"
                + "只能从固定策略中选择：RECOVER_DROPS_FIRST、FETCH_BACKUP_THEN_CONTINUE、"
                + "ABANDON_RECOVERY、PREPARE_AND_REPLAN、RESET_AND_RESTART；未知策略会被拒绝。"
                + "若旧主线（例如再进下界）在装备全丢后已不安全，选 RESET_AND_RESTART，"
                + "然后调用 rdd_restart_mainline 提交新目标（带 objective 与 reason）；"
                + "在重置生效前主线保持冻结。"
                + "也可以调用 rdd_decline_side 明确放弃本次回收，写清危险或收益不足；"
                + "放弃后才进入重要性损失分析和规划，不会因为五分钟业务保护线自动宣称物品消失。"
                + "区块未加载、掉落状态未知都不能当成没有。"
                + "</death_recovery>";
    }

    private static String esc(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
