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
 * <p>完成口径（2026-10-30 主人裁定起）= <b>把出生点绑到床上</b>（对床右键）
 * 或 <b>真睡到天亮自然醒</b>（{@link SideTaskWorldPort.Wake#DAYBREAK}）。
 * 天没亮就醒（被打醒/被传送）算中断，退回找床重试；
 * 找床窗口内没绑上 → SKIPPED（不是失败），主线立即恢复。
 *
 * <p>为什么改：原判据只有 DAYBREAK，而"睡满一夜"要跨 10000+ tick；实测（2026-10-30
 * 05:44Z 那条支线）同伴附近没床，只能在原地干等，主线被冻结 8 分钟才等到天亮。
 * "绑出生点"是玩家真正要的那一步，而且 2 秒内就能判出来。
 *
 * <p>纯 JVM：只依赖 {@link SideTaskWorldPort} 抽象，不 import Minecraft。
 */
public final class SleepSideTaskType implements SideTaskType {

    public static final String TYPE_ID = "sleep";
    public static final String PHASE_SEEKING = "SEEKING_BED";
    public static final String PHASE_SLEEPING = "SLEEPING";
    /** 保护超时：从触发起最多允许的 tick 数（10 分钟）。正常睡/天亮跳过都远小于它。 */
    public static final long DEADLINE_TICKS = 12_000L;
    /**
     * 找床窗口：从触发起这么多 tick（2 分钟）内没绑上出生点 → 放弃，把主线还回去。
     *
     * <p>只约束"找床"阶段（还没睡过）；已经睡着的不受它限制（天亮自然收）。
     */
    public static final long SEEK_WINDOW_TICKS = 2_400L;
    /** 数据键：支线刚创建时出生点是否已绑（用来防"昨晚绑过"冒充"今晚绑了"）。 */
    public static final String KEY_SPAWN_BOUND_AT_START = "spawn_bound_at_start";
    /** 数据键：本条支线期间是否真的躺下睡过。 */
    public static final String KEY_EVER_SLEPT = "ever_slept";
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
        UUID companionId = task.companionId();
        if (task.pastDeadline(world.gameTime())) {
            return SideTaskVerdict.skip("sleep deadline passed before a completed night");
        }
        SideTaskWorldPort.Wake wake = world.pollWake(companionId);
        if (wake == SideTaskWorldPort.Wake.DAYBREAK) {
            return SideTaskVerdict.complete("slept through the night and woke at daybreak");
        }

        // ★ 2026-10-30 新判据：**右键床、出生点绑上 = 完成**（不必睡满一夜）。
        //   第一次评估先把"起始是否已绑"记进 data（**只记录、不早返回**，否则会白吃一拍）；
        //   之后只在"从没绑 → 绑上了"时完成，否则昨晚绑过的那张床会让每条支线一秒自我完成。
        boolean boundNow = world.isSpawnBound(companionId);
        Map<String, String> data = new LinkedHashMap<>(task.data());
        String boundAtStart = data.get(KEY_SPAWN_BOUND_AT_START);
        boolean firstSeen = boundAtStart == null;
        if (firstSeen) {
            data.put(KEY_SPAWN_BOUND_AT_START, Boolean.toString(boundNow));
        }
        boolean boundAtStartFlag = firstSeen ? boundNow : Boolean.parseBoolean(boundAtStart);

        if (world.isSleeping(companionId)) {
            data.put("sleeping", "true");
            data.put(KEY_EVER_SLEPT, "true");
            return SideTaskVerdict.keep(PHASE_SLEEPING, data);
        }
        if (boundNow && !boundAtStartFlag) {
            return SideTaskVerdict.complete(
                    "spawn point bound at a bed (right-click) — sleeping the whole night is not required");
        }
        // 2026-10-24（主人指出）：**白天到了就该收**，不必等到 12,000 tick 的 deadline。
        // 之前只有 deadline 一条出口，于是白天开出来的睡眠支线会白占主线十分钟
        // （实机还留下过两条早已失效同伴的 ACTIVE sleep 支线）。
        if (world.hasDayNightCycle() && world.isDay()) {
            return SideTaskVerdict.skip("daytime: sleep is impossible until night falls");
        }
        // ★ 找床窗口：2 分钟没绑上就撤回主线。实测代价：05:44Z 那条支线冻结主线 8 分钟才等到天亮。
        long elapsed = world.gameTime() - task.createdAtGameTime();
        boolean everSlept = "true".equals(data.get(KEY_EVER_SLEPT));
        if (!everSlept && elapsed >= SEEK_WINDOW_TICKS) {
            return SideTaskVerdict.skip("no bed bound within " + (SEEK_WINDOW_TICKS / 20L)
                    + "s — releasing the mainline instead of holding it all night");
        }
        data.remove("sleeping");
        if (PHASE_SLEEPING.equals(task.phase())) {
            data.put("last", wake == SideTaskWorldPort.Wake.INTERRUPTED ? "interrupted_wake" : "left_bed");
        }
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
                + "系统建议你现在【去睡觉】（睡觉支线：**建议，不是命令**；主线已临时冻结，"
                + "做完、超时、或你拒绝后都会恢复主线）。"
                // 2026-10-28（主人第三点）：「要不要去是你决定的……万一那里很危险，我回去了也没有用」。
                // 支线文案必须给出拒绝的出口，否则执行侧只能照着办。
                + "★ 若这趟本身会送命或根本做不到（床边有怪、附近没床也来不及挖三填一、身上没武器、"
                + "天马上就亮），**你可以拒绝**：调用 rdd_decline_side 写清你看到的理由，"
                + "支线立刻结束（记 DECLINED，不是失败）、主线马上恢复，今天不会再问你同一件事。"
                + "做法：用 scan_blocks 找床（tag 用 #minecraft:beds），用 goto 走到床边，站定后调用 sleep。"
                + "只在床边、且是夜里才调用 sleep；白天被原版拒绝不是工具坏了，按返回的原因处理。"
                // 2026-10-30（主人裁定）：完成判据 = 绑出生点，不是睡满一夜。
                + "★★ 完成判据 =【把出生点绑到这张床】（走到床边对床右键/调用 sleep 就会绑上）。"
                + "**不需要睡满一夜**：绑上出生点这条支线就完成、主线立刻恢复；能顺便睡到天亮当然更好。"
                + "★ 时限：从触发算起约 2 分钟内没绑上出生点，系统会自动撤回主线（记 SKIPPED，不算失败）——"
                + "所以附近没床时**别硬找、别干等**，直接 rdd_decline_side 写清理由。"
                + "★ 若附近根本没有床（也来不及杀羊做床），就【挖三填一】保命："
                + "原地向下挖 3 格成一个 1×1 竖井，自己站进去，再用方块把头顶那格封住（三面墙一面顶），"
                + "躲在里面熬到天亮——没有床也能活下来，别在夜里硬顶怪物。"
                // 2026-10-24 实机：模型把"挖三填一"反复在心里演练，每轮都先 get_self_status 再重新规划，
                // 十几轮没有一个动作落地（P0 卡死）。把"读状态不算进展"直接写进它的上下文。
                + "★★ 读一次 get_self_status 就够：**重复读状态不算进展**。"
                + "看完状态后的下一步必须是真动作（build/mine/goto/interact_at 之一），"
                + "挖三填一用 build 的 ops 一次给出全部格子，不要一格格试。"
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
