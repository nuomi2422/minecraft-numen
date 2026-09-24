package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.RddRuntime;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 停车守望：停车不等于可以静默死掉。
 *
 * <p>背景（实测教训）：停车（{@code gapParked}）本意是"别再自动重试烧 token"，
 * 但 tick 在 FAILED 分支就 return、卡死检测只在 RUNNING 跑——结果停车 = 永远没人拍醒，
 * AI 结束当前回合后无人喂目标即<b>永久静默</b>，且外部看不见（实测冻结 15 分钟零活动）。
 *
 * <p>这里补一层低频率守望：连续 {@link #PARKED_WATCH_CHECKS} 次检测（1 次/秒）
 * 资产指纹无变化才再拍一次；拍 {@link #MAX_PARKED_NUDGES} 次后只发可见事件
 * （{@code subtask_parked_silent}），把"需要人或外部介入"明确暴露出来，
 * 而不是让整条链无声冻结，也不再烧模型调用。
 */
final class RddParkedWatcher {

    /** 连续这么多次检测（1 次/秒）资产指纹无变化才再拍一次。 */
    private static final int PARKED_WATCH_CHECKS = 300;
    /** 同一个停车二级最多再拍醒几次；之后只发可见事件，不再烧模型调用。 */
    private static final int MAX_PARKED_NUDGES = 3;

    private final RddStallWatcher stallWatcher;

    /** 停车守望状态（uuid→指纹/未变化计数/已拍醒次数）：停车也不能静默死掉。 */
    private final Map<UUID, ParkedWatch> parkedWatches = new ConcurrentHashMap<>();

    private record ParkedWatch(String subtaskId, String fingerprint, int unchanged, int nudges) {}

    RddParkedWatcher(RddStallWatcher stallWatcher) {
        this.stallWatcher = stallWatcher;
    }

    /** 对一个停车为 FAILED 的二级做低频守望：长期无进展就再拍一次，预算耗尽后只发事件。 */
    void watch(NumenPlayer ap, RddRuntime rt, Subtask current) {
        UUID uuid = ap.getUUID();
        String fp = stallWatcher.observe(ap, rt, current).fingerprint();
        ParkedWatch w = parkedWatches.get(uuid);
        if (w == null || !w.subtaskId().equals(current.id())) {
            parkedWatches.put(uuid, new ParkedWatch(current.id(), fp, 0, 0));
            return;
        }
        if (!w.fingerprint().equals(fp)) {
            // 仍有变化（AI 自己在推进 / 资产真被攒够）→ 重置窗口，不打扰
            parkedWatches.put(uuid, new ParkedWatch(current.id(), fp, 0, w.nudges()));
            return;
        }
        int unchanged = w.unchanged() + 1;
        if (unchanged < PARKED_WATCH_CHECKS) {
            parkedWatches.put(uuid, new ParkedWatch(current.id(), fp, unchanged, w.nudges()));
            return;
        }
        int nudged = w.nudges() + 1;
        parkedWatches.put(uuid, new ParkedWatch(current.id(), fp, 0, nudged));
        if (w.nudges() >= MAX_PARKED_NUDGES) {
            if (w.nudges() == MAX_PARKED_NUDGES) {
                RddMonitor.publish("subtask_parked_silent", Map.of(
                        "companionId", uuid.toString(), "subtask", current.id(),
                        "reason", "parked and unchanged after " + MAX_PARKED_NUDGES
                                + " nudges; needs external intervention"));
            }
            return; // 预算耗尽：只报一次，不再烧模型调用
        }
        RddPlugin.nudge(uuid, "你的目标「" + current.description() + "」还在，但停车后一直没被判出进展。"
                + "先确认真实卡点：查看工具终态、附近资源、路径和装备；"
                + "方向不对就换一条路（换地点、换材料来源、换工具），不要原样重复。");
        RddMonitor.publish("subtask_parked_renudge", Map.of(
                "companionId", uuid.toString(), "subtask", current.id(),
                "nudge", nudged, "max", MAX_PARKED_NUDGES, "reason", "parked with no progress"));
    }

    /** 清掉某同伴的停车守望状态（二级变化/完成/跳过时调用）。 */
    void clear(UUID uuid) {
        parkedWatches.remove(uuid);
    }

    void clearAll() {
        parkedWatches.clear();
    }
}
