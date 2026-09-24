package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 卡死监督：判断"看起来在跑但实际没动"，并决定拍醒 / 恢复 / 升级失败。
 *
 * <p>核心是<b>资产指纹</b>（背包计数 + 方块位置 + 容器内容 + 身体进度）。
 * 连续 {@link #STALL_AFTER_TICKS} 次检测指纹不变 → 判 STALLED 并拍醒将军；
 * 拍醒后给 {@link #STALL_RESPONSE_TICKS} 的响应窗；窗口过了仍不动 → 换措辞再拍；
 * 拍 {@link #MAX_NUDGES} 次无效 → 判失败（Level 1 恢复兜底，绝不伪造完成）。
 *
 * <p>顺带累计"当前二级卡死次数"，达 {@link #CAPABILITY_GAP_AFTER_STALLS} 即发能力缺口信号。
 *
 * <p>只读世界真身；状态只增不减地绑在当前二级上（二级换了才重置）。
 */
final class RddStallWatcher {

    private static final Logger LOG = LoggerFactory.getLogger(RddStallWatcher.class);

    /** 卡死监督：资产指纹（背包+位置）连续多少次检测无变化即判 STALLED（1 次/秒）。
     *  取 15 而非 5：AI 的 LLM 轮次（DeepSeek 思考 + 工具链）可能要 10~15 秒，
     *  太短会把"正在思考/刚起步"误判成卡死。 */
    private static final int STALL_AFTER_TICKS = RddStallPolicy.IDLE_GRACE_CHECKS;
    /** 拍醒后的响应窗口：STALLED 后给 AI 这么长时间行动（资产变化则恢复），
     *  仍未动才 re-nudge / 升级。避免"拍完不到 2 秒就判失败"。 */
    private static final int STALL_RESPONSE_TICKS = 25;
    /** 拍醒上限：超过则判失败（Level 1 恢复兜底）。 */
    private static final int MAX_NUDGES = 2;
    /** Level 3：当前二级累计卡死多少次即判定能力不足（AI 反复拍醒仍无法达成目标资产）。 */
    private static final int CAPABILITY_GAP_AFTER_STALLS = 3;

    private final RddFurnaceWatch furnaceWatch;

    /** 卡死监督状态：记录每个同伴当前二级的资产指纹与未变化计数。 */
    private final Map<UUID, StallState> stalls = new ConcurrentHashMap<>();

    private record StallState(String subtaskId, String fingerprint, int unchangedTicks, int nudges) {}

    /** Level 3 卡死累计：当前二级累计卡死次数（AI 反复拍醒仍无目标资产进展 → 能力不足）。 */
    private final Map<UUID, StallCount> stallCounts = new ConcurrentHashMap<>();

    private record StallCount(String subtaskId, int total) {}

    RddStallWatcher(RddFurnaceWatch furnaceWatch) {
        this.furnaceWatch = furnaceWatch;
    }

    /**
     * 卡死检测：资产指纹连续 {@link #STALL_AFTER_TICKS} 次无变化
     * → markStalled + 拍醒（nudge）。返回 true 表示本次判定卡死，上层停止推进。
     */
    boolean track(NumenPlayer ap, RddRuntime rt, Subtask current) {
        if (!RddPlugin.supervisionEnabled()) {
            return false; // 空转止血：暂停监督不做卡死检测/拍醒/能力升级，资产推进照常
        }
        RddStallPolicy.Observation observation = observe(ap, rt, current);
        String fp = observation.fingerprint();
        StallState st = stalls.get(ap.getUUID());
        if (st == null || !st.subtaskId().equals(current.id())) {
            stalls.put(ap.getUUID(), new StallState(current.id(), fp, 0, 0));
            return false;
        }
        RddStallPolicy.Check check = RddStallPolicy.check(st.fingerprint(), st.unchangedTicks(), observation);
        if (check.changed()) {
            // Assets, container output, cooking, or body work counters changed.
            stalls.put(ap.getUUID(), new StallState(current.id(), fp, 0, 0));
            return false;
        }
        int unchanged = check.unchanged();
        if (observation.waiting() && unchanged >= STALL_AFTER_TICKS
                && unchanged % STALL_AFTER_TICKS == 0 && !check.stalled()) {
            RddMonitor.publish("subtask_work_wait", Map.of(
                    "companionId", ap.getUUID().toString(), "subtask", current.id(),
                    "source", observation.source(), "unchangedChecks", unchanged,
                    "graceRemainingSeconds", check.remaining(), "graceLimitSeconds", check.limit()));
        }
        if (check.stalled()) {
            // Repeated stalls are an observation, not proof of missing software capability.
            StallCount sc = stallCounts.get(ap.getUUID());
            int total = (sc != null && sc.subtaskId().equals(current.id())) ? sc.total() + 1 : 1;
            stallCounts.put(ap.getUUID(), new StallCount(current.id(), total));
            if (total >= CAPABILITY_GAP_AFTER_STALLS) {
                RddPlugin.nudge(ap.getUUID(), "这个目标反复没有进展。先核对真实工具结果、附近资源、路径、装备和模型连接；不要仅凭重复失败推断缺软件工具，只有确认能力缺口后再考虑自编译。");
                RddMonitor.publish("subtask_stall_escalated", Map.of(
                        "companionId", ap.getUUID().toString(), "subtask", current.id(), "failureClass", "UNKNOWN",
                        "reason", "repeated stalls (" + total + "); cause requires evidence"));
                stallCounts.remove(ap.getUUID());
            }
            rt.chain().markStalled(current.id(), "observed work unchanged for " + check.limit() + " checks");
            RddPlugin.nudge(ap.getUUID(), "你的目标「" + current.description() + "」还在，但可见资产、容器生产和身体进度在观察窗口内没有变化。请核对真实工具结果、材料和生产条件，再决定下一步。");
            RddMonitor.publish("subtask_stalled", Map.of("subtask", current.id(),
                    "reason", "observed work unchanged", "source", observation.source(),
                    "graceRemainingSeconds", 0, "graceLimitSeconds", check.limit()));
            // 重置响应窗计数：从 STALLED 起给 AI STALL_RESPONSE_TICKS 秒响应时间
            stalls.put(ap.getUUID(), new StallState(current.id(), fp, 0, st.nudges() + 1));
            return true;
        }
        stalls.put(ap.getUUID(), new StallState(current.id(), fp, unchanged, st.nudges()));
        return false;
    }

    /**
     * STALLED 监督恢复：行为（资产/位置）恢复 → 回 RUNNING；仍在拍醒期 → 换措辞再拍；
     * 多次拍醒无效 → 判失败（Level 1 恢复兜底）。
     */
    void handleStalled(NumenPlayer ap, RddRuntime rt, Subtask current) {
        String fp = observe(ap, rt, current).fingerprint();
        StallState st = stalls.get(ap.getUUID());
        if (!RddPlugin.supervisionEnabled()) {
            // 空转止血：暂停监督。AI 自己动了 → 回 RUNNING；否则保持卡住标记，绝不拍醒/绝不判失败。
            if (st != null && st.subtaskId().equals(current.id()) && st.fingerprint().equals(fp)) {
                return; // 仍冻结：挂起不动，不打扰 AI
            }
            rt.chain().resumeFromStalled(current.id());
            RddMonitor.publish("subtask_resumed", Map.of("subtask", current.id(), "reason", "progress while supervision paused"));
            stalls.put(ap.getUUID(), new StallState(current.id(), fp, 0, 0));
            return;
        }
        if (st == null) {
            stalls.put(ap.getUUID(), new StallState(current.id(), fp, 0, 1));
            RddPlugin.nudge(ap.getUUID(), "你卡住了吗？缺什么工具或材料？");
            return;
        }
        if (!st.fingerprint().equals(fp)) {
            // Real production can recover a stalled task without moving items into inventory yet.
            rt.chain().resumeFromStalled(current.id());
            RddMonitor.publish("subtask_resumed", Map.of("subtask", current.id()));
            stalls.put(ap.getUUID(), new StallState(current.id(), fp, 0, 0));
            return;
        }
        // 资产仍无变化：先给 AI 一个响应窗口，窗口内不打扰（AI 可能正在思考/规划）
        if (st.unchangedTicks() + 1 < STALL_RESPONSE_TICKS) {
            stalls.put(ap.getUUID(), new StallState(current.id(), fp, st.unchangedTicks() + 1, st.nudges()));
            return;
        }
        if (st.nudges() >= MAX_NUDGES) {
            // 多次拍醒无效 → Level 1 恢复：判失败（不伪造完成）
            rt.chain().markFailed(current.id(), "stalled after " + MAX_NUDGES + " nudges without progress");
            RddMonitor.publish("subtask_failed", Map.of("subtask", current.id(), "reason", "stalled after nudges"));
            stalls.remove(ap.getUUID());
            LOG.warn("[rdd] 二级目标卡死升级失败: {}", current.id());
            return;
        }
        // 响应窗口已过、资产仍无变化 → 换措辞再拍一次
        RddPlugin.nudge(ap.getUUID(), "你还没动。告诉我你卡在哪一步？如果缺工具，现在就调 selfcompile_request。");
        RddMonitor.publish("subtask_stalled", Map.of("subtask", current.id(), "reason", "still stalled, re-nudge"));
        stalls.put(ap.getUUID(), new StallState(current.id(), fp, 0, st.nudges() + 1));
    }

    /** 当前二级的资产指纹（背包计数 + 位置），供卡死监督与停车守望共用。 */
    String fingerprint(NumenPlayer ap) {
        String inv = RddDetector.countInventory(ap).toString();
        var pos = ap.blockPosition();
        return inv + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /** Server-thread observation only. Fuel/elapsed/deadline/task-id changes are not work progress. */
    RddStallPolicy.Observation observe(NumenPlayer ap, RddRuntime rt, Subtask current) {
        UUID uuid = ap.getUUID();
        RddFurnaceWatch.FurnaceWatch watch = furnaceWatch.track(ap, rt, current);
        AbstractContainerMenu menu = ap.containerMenu;
        StringBuilder progress = new StringBuilder();
        if (menu != null && menu != ap.inventoryMenu) RddFurnaceWatch.appendContainer(progress, menu, ap);
        boolean production = false;
        if (watch != null) {
            if (watch.menu() != menu) RddFurnaceWatch.appendContainer(progress, watch.menu(), ap);
            // Vanilla getBurnProgress is cooking progress; getLitProgress is only fuel countdown.
            progress.append("|cooking=").append(watch.menu().getBurnProgress());
            production = watch.menu().isLit() && !watch.menu().getSlot(0).getItem().isEmpty();
        }
        var body = CompanionTickDispatcher.currentTaskFor(uuid);
        boolean active = body != null && !body.getState().isTerminal();
        if (active) progress.append("|body=").append(body.getToolName()).append(':').append(body.describe());
        String source = production ? "furnace_production" : active ? "body_task:" + body.publicId() : "idle";
        return new RddStallPolicy.Observation(fingerprint(ap), progress.toString(), production || active, source);
    }

    /** 清掉某同伴的全部卡死监督状态（完成/失败/跳过时调用）。 */
    void clear(UUID uuid) {
        stalls.remove(uuid);
        stallCounts.remove(uuid);
    }

    void clearAll() {
        stalls.clear();
        stallCounts.clear();
    }
}
