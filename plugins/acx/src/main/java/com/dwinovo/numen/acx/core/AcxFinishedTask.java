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
 * 三条分支都能被离线用例钉住（不依赖 {@code com.dwinovo.numen.task} 包）。
 *
 * <p><b>唯一判据</b>：<b>只有 SUCCESS 才算完成</b>。终态是别的（FAILED / TIMEOUT / CANCELLED）→
 * failed；没有记录、或者记录属于别的任务 → failed。<b>「不知道成败」绝不许当成成功</b>。
 */
public final class AcxFinishedTask {

    private AcxFinishedTask() {}

    /**
     * @param wantedTaskId 探针要问的那个任务 id（可为空 = 不校验 id）
     * @param publicId     最近一次终态记录的 id（null = 没有记录）
     * @param stateName    终态名（{@code TaskState.name()}；null = 没有记录）
     */
    public static AcxCompletionProbe.Snapshot map(String wantedTaskId, String publicId, String stateName) {
        if (stateName == null) {
            return AcxCompletionProbe.Snapshot.failed(
                    "槽已空且找不到该任务的终态记录（无法判断成败，按失败处理以免假完成）");
        }
        if (wantedTaskId != null && !wantedTaskId.isBlank()
                && publicId != null && !wantedTaskId.equals(publicId)) {
            return AcxCompletionProbe.Snapshot.failed(
                    "槽已空，且最近一次终态属于别的任务（" + publicId + "），按失败处理");
        }
        if ("SUCCESS".equals(stateName)) {
            return AcxCompletionProbe.Snapshot.done(Map.of(), "任务已完成（终态记录）");
        }
        return AcxCompletionProbe.Snapshot.failed("任务终态是 " + stateName + "（不是成功，按失败处理）");
    }
}
