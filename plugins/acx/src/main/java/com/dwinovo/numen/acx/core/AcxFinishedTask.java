package com.dwinovo.numen.acx.core;

import com.dwinovo.numen.acx.api.AcxCompletionProbe;
import java.util.Map;

/**
 * 「宿主任务已经离开槽位之后」→ 完成探针快照的**纯映射**（AC-B12b，2026-10-21）。
 *
 * <p><b>它修的是什么</b>：探针原先只看「槽里还有没有任务」，槽空就报 {@code done} ——
 * 于是任务 {@code FAILED} 时 AC 也拿到 SUCCESS（V-20261021-09 的「报成功但身体没动」）。
 *
 * <p><b>为什么放在 acx-core 而不是插件里</b>：这段判断原先埋在 {@code NumenToolBridgeX.probeFor}，
 * 要 Minecraft 服务器 + 同伴实体才跑得到，于是这种错只能靠实机碰运气发现。抽到这里之后，
 * 四条分支都能被离线用例钉住（不依赖 {@code com.dwinovo.numen.task} 包）。
 *
 * <p><b>唯一判据</b>：<b>只有 SUCCESS 才算完成</b>。终态是别的（FAILED / TIMEOUT / CANCELLED）→
 * failed；没有记录、记录属于别的任务、或态名不可判定 → <b>unknown</b>（调用方保留挂账，
 * 不许当成功，也不许立刻删账）。<b>「不知道成败」绝不许当成成功</b>。
 */
public final class AcxFinishedTask {

    private AcxFinishedTask() {}

    /**
     * @param wantedTaskId 探针要问的那个任务 id（空 = 无法确认归属，返回 UNKNOWN）
     * @param publicId     最近一次终态记录的 id（null = 没有记录）
     * @param stateName    终态名（{@code TaskState.name()}；null = 没有记录）
     */
    public static AcxCompletionProbe.Snapshot map(String wantedTaskId, String publicId, String stateName) {
        if (wantedTaskId == null || wantedTaskId.isBlank()) {
            return AcxCompletionProbe.Snapshot.unknown(
                    "没有 task_id，无法确认该终态属于本任务（不许跳过校验当成功）");
        }
        if (stateName == null) {
            return AcxCompletionProbe.Snapshot.unknown(
                    "槽已空且找不到该任务的终态记录（无法判断成败，保留挂账等待重试）");
        }
        if (wantedTaskId != null && !wantedTaskId.isBlank()
                && publicId != null && !wantedTaskId.equals(publicId)) {
            return AcxCompletionProbe.Snapshot.unknown(
                    "槽已空，且最近一次终态属于别的任务（" + publicId + "），无法判断本任务成败");
        }
        if ("SUCCESS".equals(stateName)) {
            return AcxCompletionProbe.Snapshot.done(Map.of(), "任务已完成（终态记录）");
        }
        if ("FAILED".equals(stateName) || "TIMEOUT".equals(stateName) || "CANCELLED".equals(stateName)) {
            return AcxCompletionProbe.Snapshot.failed("任务终态是 " + stateName + "（不是成功，按失败处理）");
        }
        // RUNNING/PENDING/未知态名都不是「该任务的终态」——不猜，交给调用方保留挂账。
        return AcxCompletionProbe.Snapshot.unknown("任务终态不可判定（" + stateName + "），保留挂账等待重试");
    }
}
