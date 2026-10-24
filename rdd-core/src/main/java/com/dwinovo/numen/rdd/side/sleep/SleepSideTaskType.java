package com.dwinovo.numen.rdd.side.sleep;

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
 * 睡觉支线的硬编码实现（第一条正式支线，也是通用框架的第一个样本）。
 *
 * <p>完成口径 = <b>睡到天亮自然醒</b>（{@link SideTaskWorldPort.Wake#DAYBREAK}）；天没亮就醒
 * （被打醒/被传送）算中断，退回找床重试；到 deadline 还没睡成 → SKIPPED_TIMEOUT（不是失败）。
 *
 * <p>纯 JVM：只依赖 {@link SideTaskWorldPort} 抽象，不 import Minecraft。
 */
public final class SleepSideTaskType implements SideTaskType {

    public static final String TYPE_ID = "sleep";
    public static final String PHASE_SEEKING = "SEEKING_BED";
    public static final String PHASE_SLEEPING = "SLEEPING";
    /** 保护超时：从触发起最多允许的 tick 数（10 分钟）。正常睡/天亮跳过都远小于它。 */
    public static final long DEADLINE_TICKS = 12_000L;
    /** 战斗中推迟后的重评估间隔（约 10 秒）。 */
    public static final int COMBAT_RETRY_TICKS = 200;
    /** 身体被占用（正在引羊/建造等）推迟后的重评估间隔（约 5 秒）。 */
    public static final int BUSY_RETRY_TICKS = 100;
    /** 主目标文本命中这些词 → 判定"主线明确要夜间活动"，本次睡觉跳过（下一晚再判断）。 */
    private static final String[] NIGHT_MARKERS = {
            "夜晚活动", "夜间活动", "保持夜晚", "保持黑夜", "不能天亮", "别天亮",
            "整夜", "通宵", "keep night", "stay night"
    };

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
        return new SideTask(ctx.companionId(), ctx.instanceId(), TYPE_ID, ctx.generation(),
                ctx.scheduledGameDay(), ctx.gameTime(), ctx.deadlineGameTime(),
                SideTaskState.ACTIVE, PHASE_SEEKING, Map.of(), ctx.mainlineToken());
    }

    /**
     * 开之前问一句：稳定意图（主线要夜间活动）优先跳过；战斗/身体占用则推迟到安全停顿点；
     * 否则正常执行。确定规则优先，不做每 tick 的模型取舍。
     */
    @Override
    public SideTaskDecision decide(UUID companionId, SideTaskWorldPort world) {
        // 2026-10-24（主人指出）：睡眠只在夜里成立 —— 白天根本睡不着（原版直接拒绝），
        // 开一条白天执行的睡觉支线＝白占主线 10 分钟。**按时间窗判定，而不是只靠 deadline**。
        if (world.hasDayNightCycle() && world.isDay() && !world.isSleeping(companionId)) {
            return SideTaskDecision.skip("daytime_no_sleep_needed");
        }
        String objective = world.mainlineObjective(companionId);
        if (objective != null) {
            for (String marker : NIGHT_MARKERS) {
                if (objective.contains(marker)) {
                    return SideTaskDecision.skip("mainline_keeps_night:" + marker);
                }
            }
        }
        if (world.inCombat(companionId)) {
            return SideTaskDecision.defer(COMBAT_RETRY_TICKS, "in_combat");
        }
        if (world.bodyBusy(companionId)) {
            return SideTaskDecision.defer(BUSY_RETRY_TICKS, "mid_operation");
        }
        return SideTaskDecision.EXECUTE;
    }

    @Override
    public SideTaskVerdict evaluate(SideTask task, SideTaskWorldPort world) {
        if (task.pastDeadline(world.gameTime())) {
            return SideTaskVerdict.skip("sleep deadline passed before a completed night");
        }
        SideTaskWorldPort.Wake wake = world.pollWake(task.companionId());
        if (wake == SideTaskWorldPort.Wake.DAYBREAK) {
            return SideTaskVerdict.complete("slept through the night and woke at daybreak");
        }
        if (world.isSleeping(task.companionId())) {
            return SideTaskVerdict.keep(PHASE_SLEEPING, Map.of("sleeping", "true"));
        }
        // 2026-10-24（主人指出）：**白天到了就该收**，不必等到 12,000 tick 的 deadline。
        // 之前只有 deadline 一条出口，于是白天开出来的睡眠支线会白占主线十分钟
        // （实机还留下过两条早已失效同伴的 ACTIVE sleep 支线）。
        if (world.hasDayNightCycle() && world.isDay()) {
            return SideTaskVerdict.skip("daytime: sleep is impossible until night falls");
        }
        Map<String, String> data = PHASE_SLEEPING.equals(task.phase())
                ? Map.of("last", wake == SideTaskWorldPort.Wake.INTERRUPTED ? "interrupted_wake" : "left_bed")
                : Map.of();
        return SideTaskVerdict.keep(PHASE_SEEKING, data);
    }

    @Override
    public String renderContext(SideTask task, SideTaskWorldPort world) {
        long remaining = Math.max(0L, task.deadlineGameTime() - world.gameTime());
        long remainingSeconds = remaining / 20L;
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("scope", "SIDE");
        attrs.put("type", TYPE_ID);
        attrs.put("instance", task.instanceId());
        attrs.put("generation", Long.toString(task.generation()));
        attrs.put("game_day", Long.toString(task.scheduledGameDay()));
        attrs.put("phase", task.phase());
        attrs.put("remaining_seconds", Long.toString(remainingSeconds));
        StringBuilder attrXml = new StringBuilder();
        for (Map.Entry<String, String> e : attrs.entrySet()) {
            attrXml.append(' ').append(e.getKey()).append("=\"").append(esc(e.getValue())).append('"');
        }
        return "<side_task" + attrXml + ">"
                + "你现在最高优先的目标是【去睡觉】（睡觉支线；主线已临时冻结，做完或超时后系统会恢复主线）。"
                + "做法：用 scan_blocks 找床（tag 用 #minecraft:beds），用 goto 走到床边，站定后调用 sleep。"
                + "只在床边、且是夜里才调用 sleep；白天被原版拒绝不是工具坏了，按返回的原因处理。"
                + "sleep 返回成功只代表你躺下了，不等于整条支线完成——真正睡着由系统硬判定；"
                + "睡到天亮自然醒来才算完成。"
                + "★ 若附近根本没有床（也来不及杀羊做床），就【挖三填一】保命："
                + "原地向下挖 3 格成一个 1×1 竖井，自己站进去，再用方块把头顶那格封住（三面墙一面顶），"
                + "躲在里面熬到天亮——没有床也能活下来，别在夜里硬顶怪物。"
                // 2026-10-24 实机：模型把"挖三填一"反复在心里演练，每轮都先 get_self_status 再重新规划，
                // 十几轮没有一个动作落地（P0 卡死）。把"读状态不算进展"直接写进它的上下文。
                + "★★ 读一次 get_self_status 就够：**重复读状态不算进展**。"
                + "看完状态后的下一步必须是真动作（build/mine/goto/interact_at 之一），"
                + "挖三填一用 build 的 ops 一次给出全部格子，不要一格格试。"
                + "若附近有怪、太远，按原版返回的原因处理，别硬顶。"
                + "若附近有怪、太远，按原版返回的原因处理，别硬顶。"
                + "</side_task>";
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
