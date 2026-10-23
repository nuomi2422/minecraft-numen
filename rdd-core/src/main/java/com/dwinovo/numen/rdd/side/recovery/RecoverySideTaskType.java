package com.dwinovo.numen.rdd.side.recovery;

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
 * 恢复支线：重要东西确认丢了之后，**临时接管主线**，把它取回来，再交还主线。
 *
 * <p><b>为什么不是"重规划整段重写"</b>（2026-10-23 主人要求）：丢了装备/材料时，原计划往往还是对的，
 * 只是缺东西。整段重写会把原本可行的做法丢掉；正确做法是插一条支线，主线冻结、支线跑完自动恢复。
 *
 * <p>完成口径 =<b>背包里重新出现了目标物品</b>（数量 ≥ 需求）。中断/超时 → SKIP，不动主线。
 */
public final class RecoverySideTaskType implements SideTaskType {

    public static final String TYPE_ID = "recovery";
    public static final String PHASE_SEEKING = "SEEKING_DROPS";
    public static final String PHASE_COLLECTING = "COLLECTING";

    /** 保护超时：10 分钟（掉落物 5 分钟就消失，这个上限只是兜底）。 */
    public static final long DEADLINE_TICKS = 12_000L;

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
        // 引擎的 CreationContext 不带负载，恢复支线的 item/count 由触发器静态点名表提供。
        Map<String, String> pending = RecoverySideTaskTrigger.take(ctx.companionId());
        if (pending != null && !pending.isEmpty()) {
            data.putAll(pending);
        }
        return new SideTask(ctx.companionId(), ctx.instanceId(), TYPE_ID, ctx.generation(),
                ctx.scheduledGameDay(), ctx.gameTime(), ctx.deadlineGameTime(),
                SideTaskState.ACTIVE, PHASE_SEEKING, Map.copyOf(data), ctx.mainlineToken());
    }

    /** 开之前问一句：已经拿回来了就别开；否则正常执行。 */
    @Override
    public SideTaskDecision decide(UUID companionId, SideTaskWorldPort world) {
        String item = null;
        try {
            if (world != null) {
                // decide 拿不到 data，按"未知物品"处理：仍执行，evaluate 会按 data 判定
                item = null;
            }
        } catch (RuntimeException ignore) {
            // 判定失败就当没拿回来
        }
        if (item != null && world != null && world.countItem(companionId, item) > 0) {
            return SideTaskDecision.skip("已拿回 " + item);
        }
        return SideTaskDecision.EXECUTE;
    }

    @Override
    public SideTaskVerdict evaluate(SideTask task, SideTaskWorldPort world) {
        String item = task.data() == null ? null : task.data().get("item");
        int need = 1;
        try {
            String raw = task.data() == null ? null : task.data().get("count");
            if (raw != null) need = Math.max(1, Integer.parseInt(raw));
        } catch (RuntimeException ignore) {
            need = 1;
        }
        if (item == null || item.isBlank()) {
            // 没写清目标物品 = 这次支线没有可判定的完成条件，超时会把它收掉
            return SideTaskVerdict.keep(PHASE_COLLECTING, Map.of("note", "no item specified"));
        }
        int have = world == null ? 0 : world.countItem(task.companionId(), item);
        if (have >= need) {
            return SideTaskVerdict.complete("背包里已有 " + item + " ×" + have);
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put("item", item);
        data.put("have", Integer.toString(have));
        data.put("need", Integer.toString(need));
        return SideTaskVerdict.keep(PHASE_COLLECTING, data);
    }

    @Override
    public String renderContext(SideTask task, SideTaskWorldPort world) {
        String item = task.data() == null ? "丢失的装备与材料" : task.data().getOrDefault("item", "丢失的装备与材料");
        return "【恢复支线】主线已暂停。请把「" + item + "」找回来：走到掉落/死亡地点拾取；"
                + "拿到后支线会自动结束，主线随即恢复。不要改主线计划。";
    }
}
