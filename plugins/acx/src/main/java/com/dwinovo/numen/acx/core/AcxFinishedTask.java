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
 * （2026-10-21 acb12b3：现在是**六条分支**——3 个 UNKNOWN 来源分别单独有用例。）
 *
 * <p><b>唯一判据</b>：<b>只有 SUCCESS 才算完成</b>。FAILED / TIMEOUT → failed；
 * CANCELLED → unknown（身体被别的派活顶掉，不是做不到）；没有记录、记录属于别的任务、
 * 或态名不可判定 → <b>unknown</b>（调用方保留挂账，不许当成功，也不许立刻删账）。
 * <b>「不知道成败」绝不许当成成功</b>。
 */
public final class AcxFinishedTask {

    private AcxFinishedTask() {}

    /**
     * @param wantedTaskId 探针要问的那个任务 id（空 = 无法确认归属，返回 UNKNOWN）
     * @param publicId     最近一次终态记录的 id（null = 没有记录）
     * @param stateName    终态名（{@code TaskState.name()}；null = 没有记录）
     */
    public static AcxCompletionProbe.Snapshot map(String wantedTaskId, String publicId, String stateName) {
        return map(wantedTaskId, publicId, stateName, null);
    }

    /**
     * 2026-11-25（fix/acx-terminal-reason）：把终态记录自带的 {@code TaskResult.message} 一起映射。
     *
     * <p><b>它修的是什么</b>：宿主任务的结果里本来就有具体原因（例如 build 的
     * "breaking 12,63,-30 releases a falling block sitting above it"，见 BuildClearSafety），
     * 但这里只报泛因「任务终态是 FAILED」，内脑看不到真因，同一次失败连重试三次。
     * 同步路径（{@code NumenToolBridgeX.mapReply}）一直原样带出 message —— 异步终态要对齐，
     * 否则同一次失败在两条路径上说法不同。</p>
     *
     * <p><b>边界</b>：只对 FAILED / TIMEOUT 追加。SUCCESS 仍走既有 done 分支（原因不参与判成功）；
     * CANCELLED 仍是 UNKNOWN（"身体被抢"不是可判定的失败，也别把它的 message 当成结论）；
     * id 对不上的记录在追加消息<b>之前</b>就已返回 UNKNOWN —— 别人的 result 一个字都不许带出。</p>
     *
     * <p>不取日志、不改写文本：{@code terminalMessage} 就是原 {@code TaskResult.message}
     * （模型本来就会看到的那份摘要）。这里不新增裁剪；到模型的长度上限沿用现有工具结果出口 ——
     * {@code com.dwinovo.numen.task.TaskResult.MAX_RESULT_JSON_LENGTH}（16 KB，
     * api/common/.../task/TaskResult.java:42）及其钳位
     * {@code com.dwinovo.numen.network.payload.TaskResultPayload}（TaskResultPayload.java:54-62，
     * 同源别名见 :37）。</p>
     *
     * @param terminalMessage 原终态结果的 message（null/空白 = 没有可交代的原因，行为与旧版一致）
     */
    public static AcxCompletionProbe.Snapshot map(String wantedTaskId, String publicId, String stateName,
                                                 String terminalMessage) {
        if (wantedTaskId == null || wantedTaskId.isBlank()) {
            return AcxCompletionProbe.Snapshot.unknown(
                    "没有 task_id，无法确认该终态属于本任务（不许跳过校验当成功）");
        }
        if (stateName == null) {
            return AcxCompletionProbe.Snapshot.unknown(
                    "槽已空且找不到该任务的终态记录（无法判断成败，保留挂账等待重试）");
        }
        if (publicId == null) {
            // 有终态名却对不上任何任务 id —— 无法归属，不许当成功（acb12b3 MINOR-1）。
            return AcxCompletionProbe.Snapshot.unknown(
                    "终态记录没有任务 id（无法归属），保留挂账等待重试");
        }
        if (!wantedTaskId.equals(publicId)) {
            return AcxCompletionProbe.Snapshot.unknown(
                    "槽已空，且最近一次终态属于别的任务（" + publicId + "），无法判断本任务成败");
        }
        if ("SUCCESS".equals(stateName)) {
            return AcxCompletionProbe.Snapshot.done(Map.of(), "任务已完成（终态记录）");
        }
        // 2026-10-28（实机）：**CANCELLED ≠ 这一步做不到**。
        // 一只同伴只有一个异步槽，主线（RDD 链）下一次派活会 brain.current.cancel() 顶掉脚本的 goto，
        // 脚本就被判成 FAIL —— 但事实是"身体被抢走"，不是"目标不可达"。
        // 这里改判为「保留挂账等待重试」（unknown），把可恢复的冲突和真正的失败分开。
        if ("CANCELLED".equals(stateName)) {
            return AcxCompletionProbe.Snapshot.unknown(
                    "任务被取消（身体被别的派活顶掉，不是做不到）；保留挂账等待重试");
        }
        if ("FAILED".equals(stateName) || "TIMEOUT".equals(stateName)) {
            String head = "任务终态是 " + stateName + "（不是成功，按失败处理）";
            String reason = terminalMessage == null ? "" : terminalMessage.strip();
            return AcxCompletionProbe.Snapshot.failed(reason.isEmpty() ? head : head + ": " + reason);
        }
        // RUNNING/PENDING/未知态名都不是「该任务的终态」——不猜，交给调用方保留挂账。
        return AcxCompletionProbe.Snapshot.unknown("任务终态不可判定（" + stateName + "），保留挂账等待重试");
    }
}
