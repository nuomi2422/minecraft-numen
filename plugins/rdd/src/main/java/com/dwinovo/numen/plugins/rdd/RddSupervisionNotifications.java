package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.core.TaskChain;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Supervisory system-message delivery, per-state throttle and kickoff deduplication.
 * No body dispatch and no secondary task state: live chain, switches and API remain with the plugin.
 */
final class RddSupervisionNotifications {
    private RddSupervisionNotifications() {}
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(RddPlugin.class);

    /** 开工拍醒去重：uuid → 已经拍过开工的那个二级 id（每个二级都要能开工；换目标时清）。 */
    private static final Map<UUID, String> KICKOFF_SENT = new ConcurrentHashMap<>();
    /** 诊断：同二级只记一次"为什么没开工拍醒"，避免每 tick 刷事件。 */
    private static final Map<UUID, String> KICKOFF_SKIP_LOGGED = new ConcurrentHashMap<>();
    /**
     * 同一同伴两次拍醒的最小间隔（毫秒）。2026-10-03 实测：单小时 46 次、同一条话术 15 分钟内
     * 刷 42 次 —— 催工有六七个独立来源，单来源去重挡不住叠加，所以要一个跨来源的总闸。
     * 取 3 分钟：够 AI 读完上一条并真的动手（判据变了自然会放行），又不会把话重复说 40 遍。
     */
    private static final long MIN_NUDGE_INTERVAL_MS = 3L * 60L * 1000L;
    /** 最近一次**真的注入出去**的拍醒时刻，节流判据。键 = 同伴 + 当前二级（见 {@link #nudgeBucket}）：
     *  状态一变（换二级/换一级）就是新桶 → 天然放行，不再让上一阶段的 3 分钟窗口压住下一阶段。 */
    private static final Map<String, Long> LAST_NUDGE_AT = new ConcurrentHashMap<>();
    /**
     * 卡死监督的"拍醒"：把一句话注入内置 AI（效果和主人亲手打字一样）。
     * RDD 不抢方向盘，只在将军发愣时提醒它——缺工具会让它自己调 selfcompile_request。
     *
     * <h2>全局节流（2026-10-03）</h2>
     * 拍醒是一次<b>真实 LLM 请求</b>，而催工有六七个互相独立的来源（卡死/重试/暂停复评/
     * 停车/装备/死亡），<b>单来源的去重挡不住它们叠加</b>。实测 10-03 16 点那一小时就拍了
     * 46 次，16:49~17:05 之间同一条话术一字不变地刷了 42 次 —— 全是钱。
     * 所以这里放一个 <b>按状态分桶</b>的最小间隔闸：同一同伴、同一二级内，无论哪个分支想拍，
     * 3 分钟只放一次；换二级/换一级 = 新桶，立即放行（不再误伤阶段切换）。
     * 间隔到了之后状态真变了（判据变了）自然会再拍，所以这不是"降低监督强度"，
     * 是"不把同一句话说 40 遍"。
     *
     * @return 真的注入出去 → {@code true}；被闸挡下（暂停/限流/参数无效）→ {@code false}
     */
    public static boolean nudge(UUID companionId, String message) {
        if (!RddPlugin.supervisionEnabled()) {
            // 空转止血：监督暂停时绝不注入内置 AI（兜底闸；调用方也各自判了暂停）
            LOG.info("[rdd] 监督暂停,跳过拍醒: {}", message == null ? "" : message);
            return false;
        }
        if (RddPlugin.notificationApi() == null || companionId == null || message == null || message.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (!nudgeAllowed(companionId)) {
            long since = msSinceLastNudge(companionId);
            RddMonitor.publish("nudge_throttled", Map.of(
                    "companionId", companionId.toString(),
                    "sinceLastNudgeMs", since,
                    "minIntervalMs", MIN_NUDGE_INTERVAL_MS,
                    "message", message.length() > 120 ? message.substring(0, 120) : message,
                    "reason", "per-state nudge throttle; no repeat spend within the same subtask"));
            LOG.info("[rdd] nudge 节流({}ms < {}ms) {}: {}", since, MIN_NUDGE_INTERVAL_MS, companionId, message);
            return false;
        }
        try {
            // G4c S107b：拍醒是**系统事件**，不是主人的话 —— 走 enqueueSystem 而不是 enqueue。
            // 否则「卡死 → 重推」这个循环会被当成主人回合、绕过预算（2026-10-21 实机暴露）。
            var delivery = RddPlugin.notificationApi().enqueueSystem(companionId, message);
            if (delivery == null || delivery == com.dwinovo.numen.api.Delivery.REJECTED) {
                return false; // no confirmed handoff: do not consume either reminder clock
            }
            LAST_NUDGE_AT.put(nudgeBucket(companionId), now);
            Map<String, Object> data = RddPlugin.observationData(companionId);
            data.put("outputId", UUID.randomUUID().toString());
            data.put("companionId", companionId.toString());
            data.put("message", message);
            data.put("source", "supervisor");
            data.put("target", "numen");
            RddRuntime runtime = RddPlugin.runtime(companionId);
            if (runtime != null) data.put("taskChain", runtime.snapshot());
            RddMonitor.publish("supervisor_output", data);
            LOG.info("[rdd] nudge {}: {}", companionId, message);
            return true;
        } catch (RuntimeException e) {
            LOG.warn("[rdd] nudge failed: {}", e.toString());
            return false;
        }
    }

    /**
     * Identity of the one task state eligible for supervisory reminders. Owner
     * pause and every non-RUNNING state return null; no timer can revive them.
     */
    static String runningTaskIdentity(TaskChain chain) {
        if (chain == null || chain.primaryStatus() != PrimaryGoalStatus.ACTIVE) return null;
        Subtask current = chain.currentSubtask();
        if (current == null || chain.currentSubtaskStatus() != SubtaskStatus.RUNNING
                || chain.isOwnerPause(current.id())) return null;
        PrimaryGoal primary = chain.currentPrimary();
        if (primary == null) return null;
        return chain.chainInstanceId() + "|" + primary.id() + "|" + current.id()
                + "|" + chain.planRevision() + "|" + chain.ownerEpoch();
    }

    /**
     * Repeat-watcher delivery guard. Recheck immediately before enqueue so an old
     * candidate cannot speak into a replaced chain or a master-paused task.
     */
    static boolean nudgeIfRunning(UUID companionId, String expectedIdentity, String message) {
        if (companionId == null || expectedIdentity == null || message == null || message.isBlank()) return false;
        RddRuntime runtime = RddPlugin.runtime(companionId);
        if (runtime == null || !expectedIdentity.equals(runningTaskIdentity(runtime.chain()))) return false;
        return nudge(companionId, message);
    }

    /**
     * 总闸现在放不放行 —— <b>只读，不消费</b>。
     *
     * <p><b>为什么调用方需要它</b>（2026-10-06 核实）：{@code RddStallWatcher} 原来
     * <b>先</b>{@code markNudgedFor}（记「这个状态拍过了」）<b>再</b>调 {@link #nudge}，
     * 而 {@code nudge} 在监督暂停 / 本条总闸 / 参数无效时都会返回 {@code false} ⇒
     * <b>一次都没发出去，却把那个状态永久判成「已提醒」</b>。
     * 实测形态：判据 10 秒就报，总闸 180 秒挡下，于是 {@code nudge_throttled} 15 次
     * 而 {@code supervisor_output} 只有 9 次 —— 六成提醒被自己的记账顺序吃掉。
     *
     * <p>调用方正确姿势：<b>闸放行 → 发送 → 发送成功才记账</b>。
     */
    static boolean nudgeAllowed(UUID companionId) {
        return RddPlugin.supervisionEnabled() && RddPlugin.notificationApi() != null && companionId != null
                && msSinceLastNudge(companionId) >= MIN_NUDGE_INTERVAL_MS;
    }

    /** 距上次<b>成功</b>拍醒过去了多久（同一同伴+同一二级的桶）；从没拍过 ⇒ {@code Long.MAX_VALUE}。 */
    private static long msSinceLastNudge(UUID companionId) {
        Long last = LAST_NUDGE_AT.get(nudgeBucket(companionId));
        return last == null ? Long.MAX_VALUE : System.currentTimeMillis() - last;
    }

    /**
     * 节流桶键：同伴 + 当前二级 id。状态一变就是新桶 → 天然放行。
     *
     * <p>为什么不再 per-companion 全局（c6d35893 原设计）：全局桶让**任何一次拍醒**（哪怕是一次
     * 合理的"新阶段开工"）把下一条堵 3 分钟 —— 阶段/状态明明变了却被上一句的窗口压住，表现就是
     * "第二个阶段卡住不动"（2026-10-18 实测：kickoff 后下一二级 stall 被 nudge_throttled 挡下）。
     * 改成按状态分桶后，"同一状态内的跨来源去重"仍然成立，但换阶段不再被误伤。
     */
    private static String nudgeBucket(UUID companionId) {
        String state = "none";
        RddRuntime rt = companionId == null ? null : RddPlugin.runtime(companionId);
        if (rt != null) {
            try {
                Subtask cur = rt.chain().currentSubtask();
                if (cur != null) state = cur.id();
            } catch (RuntimeException ignored) {
                // 读链失败就用 none 桶，退化成原全局节流，不抛。
            }
        }
        return companionId + "|" + state;
    }

    /** 清掉某同伴所有状态的节流记录（任务清除/换目标/换阶段时调用）。 */
    static void clearNudgeThrottle(UUID companionId) {
        if (companionId == null) return;
        String prefix = companionId + "|";
        LAST_NUDGE_AT.keySet().removeIf(k -> k.startsWith(prefix));
    }

    static void clearNudgeThrottleAll() {
        LAST_NUDGE_AT.clear();
    }

    /**
     * 开工拍醒：某个一级的第一个可执行二级刚进入 RUNNING → 推一句"现在就动手"。
     *
     * <p>为什么必须有：{@code /goal} 被 RDD 接管后，客户端自身 goal 被清空
     * （{@code CompanionHome.setGoal(null)}），而整条 goal 路径（beginPlanning → Stage-A →
     * GoalDriver 展开 → Detector startCurrent）**一处都不 nudge** ⇒ 链生成了 AI 却不动手。
     * 这里是唯一的"开工"拍醒点，每个二级最多一次（阶段一换就再拍，避免被上一阶段的 3 分钟总闸吞掉）。
     *
     * <p><b>先发后记账</b>：{@code KICKOFF_SENT.put} 必须在 {@link #nudge} 返回 true 之后，
     * 否则被总闸挡一次就永久哑火（RddStallWatcher 记过的同类事故）。
     */
    static boolean kickoffNudge(UUID companionId, TaskChain chain) {
        return kickoffNudge(companionId, chain, null);
    }

    /** Producer-supplied token only; legacy callers and absent identity stay UNKNOWN. */
    static boolean kickoffNudge(UUID companionId, TaskChain chain,
                                com.dwinovo.numen.api.WorldContextStamp.Token observationToken) {
        if (companionId == null || chain == null) return false;
        if (!RddPlugin.supervisionEnabled() || RddPlugin.notificationApi() == null) {
            kickoffSkip(companionId, chain, "supervision_off_or_no_api", observationToken);
            return false;
        }
        if (RddPlugin.bodySubmissionEnabled()) {
            kickoffSkip(companionId, chain, "body_dispatch_enabled", observationToken);
            return false;
        }
        if (chain.primaryStatus() != PrimaryGoalStatus.ACTIVE) {
            kickoffSkip(companionId, chain, "primary_not_active", observationToken);
            return false;
        }
        Subtask cur = chain.currentSubtask();
        if (cur == null || chain.currentSubtaskStatus() != SubtaskStatus.RUNNING) {
            kickoffSkip(companionId, chain, cur == null ? "no_current_subtask" : "subtask_not_running", observationToken);
            return false;
        }
        if (cur.id().equals(KICKOFF_SENT.get(companionId))) {
            kickoffSkip(companionId, chain, "already_kicked_off", observationToken);
            return false;
        }
        if (!nudgeAllowed(companionId)) {
            // 新阶段的"开工"不能被上一阶段催工的 3 分钟总闸吞掉（每个二级都要能开工）
            clearNudgeThrottle(companionId);
        }
        String msg = "【开工】现在动手做这一步：「" + cur.description() + "」。"
                + "先调用工具真正执行，不要只回文字；做完把结果报回来，我核对真实资产后再推进下一步。"
                + "如果这一步现在做不了，用 report_task_concern 上报卡点，不要默默无视。";
        if (!nudge(companionId, msg)) {                                // 发失败不记账 → 下个 tick 重试
            kickoffSkip(companionId, chain, "nudge_failed", observationToken);
            return false;
        }
        KICKOFF_SENT.put(companionId, cur.id());
        Map<String, Object> data = RddPlugin.observationData(companionId);
        data.put("primary", chain.currentPrimary().id());
        data.put("subtask", cur.id());
        data.put("reason", "kickoff: a subtask just entered RUNNING");
        RddMonitor.publish("goal_kickoff", data, observationToken);
        LOG.info("[rdd] 开工拍醒 {} 二级 {}", companionId, cur.id());
        return true;
    }

    private static void kickoffSkip(UUID companionId, TaskChain chain, String reason,
                                    com.dwinovo.numen.api.WorldContextStamp.Token observationToken) {
        String key = chain != null && chain.currentSubtask() != null
                ? chain.currentSubtask().id() : "none";
        if (key.equals(KICKOFF_SKIP_LOGGED.get(companionId))) return;   // 同二级只记一次
        KICKOFF_SKIP_LOGGED.put(companionId, key);
        RddMonitor.publish("goal_kickoff_skipped", Map.of(
                "companionId", companionId.toString(),
                "subtask", key,
                "primaryStatus", chain == null ? "null" : chain.primaryStatus().name(),
                "reason", reason,
                "bodyDispatch", RddPlugin.bodySubmissionEnabled(),
                "supervision", RddPlugin.supervisionEnabled(),
                "apiBound", RddPlugin.notificationApi() != null), observationToken);
    }

    /** rdd_submit 用：AI 自己刚提交并启动的单，不需要再"开工拍醒"一次。 */
    static void markKickoffHandled(UUID companionId, String subtaskId) {
        if (companionId != null && subtaskId != null) {
            KICKOFF_SENT.put(companionId, subtaskId);
        }
    }

    static void clearKickoff(UUID companionId) {
        KICKOFF_SENT.remove(companionId);
    }

    static void clearKickoffAll() {
        KICKOFF_SENT.clear();
    }
}
