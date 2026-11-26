package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.policy.LossImpactAnalyzer;
import com.dwinovo.numen.rdd.policy.LocalRepairTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * \u652f\u7ebf\u4efb\u52a1\uff08\u76f4\u7ebf\u4efb\u52a1 / Local Repair Task\uff09\u5206\u53d1\u5668 \u2014\u2014 \u63d2\u4ef6\u4fa7\u63a5\u7ebf\u3002
 *
 * <p>\u3010\u7528\u6237\u67b6\u6784\u6982\u5ff5 1/4 \u7684\u63a5\u7ebf\u3011\u6b7b\u4ea1/\u7269\u54c1\u4e22\u5931\u7b49\u7a81\u53d1\u65f6\uff0c\u751f\u6210\u4e00\u6761"\u4e0d\u6574\u94fe\u91cd\u89c4\u5212"\u7684\u652f\u7ebf\u4efb\u52a1\uff0c
 * \u7ecf\u4e2d\u65ad\u98ce\u66b4\u62a4\u680f\u5224\u5b9a\u540e\uff0c\u7528 nudge \u6ce8\u5165\u6267\u884c\u4f53\uff1a"\u5148\u5904\u7406\u773c\u524d\u95ee\u9898\uff0c\u522b\u52a8\u4e3b\u94fe"\u3002
 * \u7eaf\u903b\u8f91\u5728 rdd-core \u7684 {@link LocalRepairTask}\uff1b\u672c\u7c7b\u53ea\u505a\u5bbf\u4e3b\u4fa7\u72b6\u6001\u4e0e\u6295\u653e\u3002
 *
 * <p>\u4e0d\u6539 TaskChain \u6838\u5fc3\uff1b\u4e0d\u6d3e\u5de5\uff08nudge \u662f\u63d0\u793a\uff0c\u6267\u884c\u4f53\u81ea\u51b3\uff09\uff1b\u7ea2\u7ebf\u4e0d\u52a8\u3002
 */
final class RddRepairDispatch {

    private RddRepairDispatch() {}

    /** \u6bcf\u540c\u4f34\u7684\u6700\u8fd1\u652f\u7ebf\u4efb\u52a1\uff08\u89c2\u6d4b/\u751f\u6210\u53bb\u91cd\u7528\uff09\u3002 */
    private static final Map<UUID, LocalRepairTask.Task> LAST_REPAIR = new ConcurrentHashMap<>();
    /** \u6bcf\u540c\u4f34\u7684\u62a4\u680f\u8ffd\u8e2a\uff08\u51b7\u5374/\u9891\u7387/\u8fde\u7eed\u5931\u8d25\uff09\u3002 */
    private static final Map<UUID, LocalRepairTask.Guard.Tracker> TRACKERS = new ConcurrentHashMap<>();
    private static final AtomicLong ID_SEQ = new AtomicLong();
    private static final LocalRepairTask.Guard GUARD = LocalRepairTask.Guard.defaults();
    /**
     * D20-C1：支线创建时的游戏 tick（超时结账用）。
     *
     * <p>为什么要记这个：Task 的 timeoutTicks 只是「说明这次支线该多久」，但宿主侧
     * 没人看它 —— 支线失败/超时后 {@code onRepairFailure} 零调用者、{@code LAST_REPAIR}
     * 永不清除，台账永远 UNVERIFIED（FV 终验实测）。这条 Map 补上「什么时候创建的」，
     * {@link #tickTimeout} 到点把没结账的支线结成 CONFIRMED_LOST。
     */
    private static final Map<UUID, Long> REPAIR_CREATED_TICK = new ConcurrentHashMap<>();

    /** F6：当前支线对应的死亡批次 seq（结账的身份，不是"最近那条"）。 */
    private static final Map<UUID, Integer> OPEN_DEATH_SEQ = new ConcurrentHashMap<>();
    /** 纯 JVM 可见的统一 death_recovery 接管标记；不能从这里反向加载 NeoForge SideTaskHost。 */
    private static final Map<UUID, Integer> DEATH_RECOVERY_HANDOFF = new ConcurrentHashMap<>();

    static void markDeathRecoveryHandoff(UUID companionId, int deathSeq) {
        if (companionId != null && deathSeq >= 0) DEATH_RECOVERY_HANDOFF.put(companionId, deathSeq);
    }

    static void clearDeathRecoveryHandoff(UUID companionId, int deathSeq) {
        if (companionId == null) return;
        DEATH_RECOVERY_HANDOFF.remove(companionId, deathSeq);
    }

    /** 非重复死亡：先恢复防护，再判断死亡点是否值得且能够安全回收。 */
    private static final String DEFAULT_STEPS =
            "先回基地取齐并实际穿好备用装备（四槽护甲、主手武器、副手盾、工具和食物）；再评估死亡点 {at} 的距离、遮挡、威胁与剩余掉落窗口（约 5 分钟）；夜间或危险则先睡觉或等到白天；仅安全可达才回收并逐项核数，否则如实上报；最后回主链";
    private static final String DEFAULT_GOAL =
            "死亡支线：备份优先，再评估安全回收；不盲冲、不整链重规划；完成后回主链";

    /** F4a 重复死亡：先活着离开并补给，再评估，绝不直接冲回死亡点。 */
    private static final String REPEAT_DEATH_STEPS =
            "① 立刻离开 {at} 附近（刚在那里死了第二次）② 回基地补血/吃饱/换齐备用装备 ③ 评估距离、遮挡、威胁与掉落窗口，夜间/危险先等白天 ④ 仅安全可达才回收并逐项核数";
    private static final String REPEAT_DEATH_GOAL =
            "重复死亡支线：先撤离、补给并评估，再安全回收掉落物；宁可不捡也不要再死一次";

    /**
     * A2（94 号）判据③：恢复分支的**机读步骤序列**（与上面的散文步骤一一对应）。
     *
     * <p>以前只有 nudge 里的一段话与 LocalRepairTask 的 stepsText，监测台/判据只能读自然语言；
     * 这两个常量让「先备份/撤离补给 → 再评估 → 安全才回收」变成事件里的有序枚举，可断言顺序。
     */
    private static final java.util.List<String> STEPS_NORMAL = java.util.List.of(
            "fetch_backup_gear", "assess_recovery", "recover_drops_if_safe", "resume_main_chain");
    private static final java.util.List<String> STEPS_REPEAT_DEATH = java.util.List.of(
            "leave_death_zone", "heal_and_resupply", "assess_recovery", "recover_drops_if_safe");

    /**
     * 生成并（经护栏）投放一条死亡支线任务。 返回 true = 已投放（nudge 注入）。
     * 死亡触发优先级 CRITICAL（护栏放行）。
     *
     * <p><b>顺序是"先恢复备用装备，再评估并安全回收"</b>：
     * 裸装或装备全丢时直接冲回死亡点会重复死亡；必须先取齐并实际穿好备用装备，
     * 再根据距离、遮挡、现场威胁、昼夜与剩余掉落窗口决定是否回收。
     * 夜间或危险时先睡觉/等到白天；不可达或不值得就如实上报，绝不盲冲。
     *
     * <p><b>F4a 重复死亡</b>：同一点短时间内又死一次时，
     * "先回去捡"恰恰是送命的那一步。此时顺序是「先撤离 + 补给 → 再评估 → 安全才捡」，
     * 且**仍然把剩余秒数带进文案**（R07 已冻结：倒计时必须在它眼前）。
     *
     * @param deathAt 死亡点坐标文本（如 "425, 75, -298"），可空
     * @param dropTimeline 死亡台账渲染出的整张表（每次死亡一行 + 各自还剩多少秒），可空
     * @param repeatDeath 同一点短时间内又死了一次（F4a）
     * @param death 本次死亡记录（把批次身份带进支线；可空 = 退化为不带 seq 的旧路径）
     */
    static boolean onDeath(UUID companionId, String goalId, String deathAt, String dropTimeline,
                           boolean repeatDeath,
                           com.dwinovo.numen.rdd.core.RddDeathLedger.Death death) {
        if (companionId == null) return false;
        RddPlugin.ensureLedgerLoaded(companionId);
        long now = RddInstrumentation.currentGameTimeTicks();
        LocalRepairTask.Priority pri = LocalRepairTask.defaultPriority(LocalRepairTask.Trigger.DEATH);
        LocalRepairTask.Guard.Tracker tr = TRACKERS.computeIfAbsent(companionId, k -> new LocalRepairTask.Guard.Tracker());
        LocalRepairTask.Guard.Decision d = GUARD.evaluate(tr, LocalRepairTask.Trigger.DEATH, now, pri);
        if (d != LocalRepairTask.Guard.Decision.ALLOW) {
            RddMonitor.publish("repair_task_suppressed", Map.of(
                    "companionId", companionId.toString(), "trigger", "DEATH", "decision", d.name()));
            return false;
        }
        String at = (deathAt == null || deathAt.isBlank()) ? "死亡点" : deathAt;
        // ★ S1 读路径（2026-10-24）；2026-10-25 按 81 号 §4.1 改口径：
        //   死亡**这一瞬间**背包还没被重新读到，所以此刻只能报 inventoryKnown=false，
        //   不能拿空表冒充"背包是空的"。真正的净损失/备用判断推迟到
        //   {@link #recomputeAfterDeath}（回收完成或超时结账那一拍，手里有真实 counts）。
        LossImpactAnalyzer.LossImpact impact = analyzeDeathLoss(companionId, death);
        // 台账原样拼进 nudge：倒计时必须在**她眼前**，规划器看不到就等于没有。
        String timeline = (dropTimeline == null || dropTimeline.isBlank()) ? "" : ("\n" + dropTimeline);
        String semanticNote = impact == null ? "" : renderSemanticNote(impact);
        String id = "repair-" + companionId + "-" + ID_SEQ.incrementAndGet();
        String parent = (goalId == null || goalId.isBlank()) ? ("goal-" + companionId) : goalId;
        LocalRepairTask.Task task = LocalRepairTask.Task.of(id, parent, "death-event",
                LocalRepairTask.Trigger.DEATH, pri, 5 * 60 * 20,
                LocalRepairTask.ResumePolicy.RESEARCH_TARGET,
                repeatDeath ? REPEAT_DEATH_STEPS.replace("{at}", at) : DEFAULT_STEPS.replace("{at}", at),
                "ESCALATE",
                repeatDeath ? REPEAT_DEATH_GOAL : DEFAULT_GOAL);
        LAST_REPAIR.put(companionId, task);
        REPAIR_CREATED_TICK.put(companionId, now);   // D20-C1：超时结账的起点
        // 批次身份跟着支线走：回收判据必须知道"结账的是哪一次死亡"（F6）。
        if (death != null) {
            OPEN_DEATH_SEQ.put(companionId, death.seq());
        }
        tr.recordFire(LocalRepairTask.Trigger.DEATH, now);
        Map<String, Object> dispatched = new LinkedHashMap<>();
        dispatched.put("companionId", companionId.toString());
        dispatched.put("repairId", id);
        dispatched.put("priority", pri.name());
        dispatched.put("deathAt", at);
        dispatched.put("dropTimeline", dropTimeline == null ? "" : dropTimeline);
        dispatched.put("deathSeq", death == null ? -1 : death.seq());
        dispatched.put("trackedItemKinds", death == null ? 0 : death.lostItems().size());
        dispatched.put("repeatDeath", repeatDeath);
        // A2 判据③：恢复分支步骤机读（顺序即执行顺序）；散文 stepsText 保留供人读。
        dispatched.put("steps", repeatDeath ? STEPS_REPEAT_DEATH : STEPS_NORMAL);
        dispatched.put("stepsText", repeatDeath
                ? REPEAT_DEATH_STEPS.replace("{at}", at)
                : DEFAULT_STEPS.replace("{at}", at));
        // ★ S1：损失影响分析结果落事件（判据 S1——监测台从这一个键看到
        //   「丢了什么重要的、影响了哪个目标、建议补充还是先确认」）。
        if (impact != null) {
            Map<String, Object> li = new LinkedHashMap<>();
            li.put("importantLoss", impact.importantLoss());
            li.put("backupSufficient", impact.backupSufficient());
            li.put("lossConfirmed", impact.lossConfirmed());
            li.put("recommendation", impact.recommendation().name());
            List<Map<String, Object>> goals = new ArrayList<>();
            for (LossImpactAnalyzer.GoalImpact g : impact.affectedGoals()) {
                Map<String, Object> gm = new LinkedHashMap<>();
                gm.put("goalId", g.goalId());
                gm.put("requirementKey", g.requirementKey());
                gm.put("minimum", g.minimum());
                gm.put("available", g.available());
                gm.put("coveredBy", g.coveredBy());
                goals.add(gm);
            }
            li.put("affectedGoals", goals);
            li.put("summary", impact.summary());
            dispatched.put("lossImpact", li);
        }
        RddMonitor.publish("repair_task_dispatched", dispatched);
        if (repeatDeath) {
            RddPlugin.nudge(companionId, "[支线任务/死亡] 你**刚在同一个地方又死了一次**。"
                    + "回去拿掉落物不是现在该做的事：你现在身上什么都没有，回去只会被再杀一次。"
                    + "\n先做这四件事：① 立刻离开 " + at + " 附近（那地方已经杀了你两次）"
                    + " ② 回基地/安全处补血、吃饱，并取齐穿好备用护甲、武器、盾、工具和食物 "
                    + " ③ 评估死亡点距离、遮挡、现场威胁与剩余掉落窗口；夜间或危险先睡觉/等到白天 "
                    + " ④ 仅安全可达才回去捡并逐项核数（下面有时间表）" + timeline
                    + "\n如果这一趟不安全或不值得，就**明确上报**：宁可少捡，也不要再死一次。"
                    + semanticNote
                    + "\n不要为这件事重规划整条主链。");
            return true;
        }
        RddPlugin.nudge(companionId, "[支线任务/死亡] 你刚死一次。掉落物在 "
                + at + " 附近——**注意：掉落物约 5 分钟就会消失**。"
                + "顺序是：先回基地取齐并实际穿好备用护甲、武器、盾、工具和食物；"
                + "再评估 " + at + " 的距离、遮挡、现场威胁与剩余窗口，夜间或危险先睡觉/等到白天；"
                + "仅安全可达才回收并逐项核数，否则如实上报，最后回主链继续。" + semanticNote
                + "\n不要盲冲死亡点，也不要为这件事重规划整条主链。" + timeline);
        return true;
    }

    private static LossImpactAnalyzer.LossImpact analyzeDeathLoss(UUID companionId,
            com.dwinovo.numen.rdd.core.RddDeathLedger.Death death) {
        return RddRepairLossAnalysis.analyzeDeathLoss(companionId, death);
    }

    static void recomputeLatestFromLedger(UUID companionId, Map<String, Integer> counts) {
        RddRepairLossAnalysis.recomputeLatestFromLedger(companionId, counts);
    }

    static LossImpactAnalyzer.LossImpact recomputeAfterDeath(UUID companionId,
            Map<String, Integer> counts, boolean lossConfirmed, String phase) {
        return RddRepairLossAnalysis.recomputeAfterDeath(companionId, counts, lossConfirmed, phase);
    }

    static LossImpactAnalyzer.LossImpact recomputeAfterDeath(UUID companionId,
            Map<String, Integer> counts, boolean lossConfirmed, String phase, int seqHint) {
        return RddRepairLossAnalysis.recomputeAfterDeath(companionId, counts, lossConfirmed, phase, seqHint);
    }

    static Map<String, Integer> backupFromBase(UUID companionId) {
        return RddRepairLossAnalysis.backupFromBase(companionId);
    }

    static String renderSemanticNote(LossImpactAnalyzer.LossImpact impact) {
        return RddRepairLossAnalysis.renderSemanticNote(impact);
    }

    /** 支线成功：清连续失败计数并移除。 */
    static void onRepairSuccess(UUID companionId) {
        LocalRepairTask.Guard.Tracker tr = TRACKERS.get(companionId);
        if (tr != null) tr.recordSuccess(LocalRepairTask.Trigger.DEATH);
        LocalRepairTask.Task done = LAST_REPAIR.remove(companionId);
        REPAIR_CREATED_TICK.remove(companionId);   // D20-C1：成功路径也清起点
        if (done != null) {
            RddMonitor.publish("repair_task_succeeded", Map.of(
                    "companionId", companionId.toString(), "repairId", done.id(),
                    "deathSeq", openDeathSeq(companionId),
                    "reason", "every tracked drop of THIS death batch is back in the real inventory"));
            // F6：结账必须带**本次支线对应的 deathSeq**，不能再用 -1（"最近那条"）。
            // -1 的真实危害：连死两次时，上一批的掉落物回来会把这一批的支线结掉。
            int seq = openDeathSeq(companionId);
            if (seq >= 0) {
                OPEN_DEATH_SEQ.remove(companionId);
                // 深审 R08：把"这次死亡已回收"落到台账，规划器才不会继续把它当待办。
                if (com.dwinovo.numen.rdd.core.RddDeathLedger.confirmRecovered(companionId, seq,
                        "all tracked drops of this batch observed back in inventory")) {
                    RddPlugin.saveLedger(companionId);   // F2：状态变了就落盘
                }
            }
        }
    }

    /** 当前支线对应的死亡批次 seq；没有（未派发/已结账）返回 -1。 */
    static int openDeathSeq(UUID companionId) {
        return companionId == null ? -1 : OPEN_DEATH_SEQ.getOrDefault(companionId, -1);
    }

    /**
     * F6：按批次核对掉落是否真的捡回来了。
     *
     * <p>只认<b>当前支线对应的那一次死亡</b>，逐项按该批次的 {@code lostItems} 核对。
     * 旧判据（任意一件 LOST 物品现在 &gt;0）会让 33 格掉落里捡回 1 格就判整条支线成功。
     *
     * @param counts 宿主刚扫描到的真实背包
     * @return satisfied=true 时才允许调用 {@link #onRepairSuccess(UUID)}；
     *         matched=false 表示批次对不上（不该结账）
     */
    static boolean checkBatchRecovered(UUID companionId, java.util.Map<String, Integer> counts) {
        int seq = openDeathSeq(companionId);
        if (seq < 0) return false;
        return com.dwinovo.numen.rdd.core.RddDeathLedger.checkBatchRecovery(companionId, seq, counts).satisfied();
    }

    /**
     * 部分回收：发进度事件，<b>不</b>结账。
     *
     * <p>进度必须可见，否则"她捡了 3/10 却没人告诉她还差多少"和"完全没捡"长得一模一样。
     */
    static void reportBatchProgress(UUID companionId, java.util.Map<String, Integer> counts) {
        int seq = openDeathSeq(companionId);
        if (seq < 0) return;
        var r = com.dwinovo.numen.rdd.core.RddDeathLedger.checkBatchRecovery(companionId, seq, counts);
        if (!r.matched() || r.tracked() <= 0 || !r.anyBack()) return;
        RddMonitor.publish("repair_task_progress", Map.of(
                "companionId", companionId.toString(),
                "deathSeq", r.seq(),
                "trackedItemKinds", r.tracked(),
                "outstandingItemKinds", r.outstanding(),
                "fraction", r.fraction(),
                "note", "partial pickup; the repair branch is NOT closed until every tracked drop is back"));
    }

    /**
     * 死亡掉落是否已经被观测到回收（决定死亡支线算不算完成）。
     *
     * <p>深审 R08（codex）：旧实现 \`onRepairSuccess\` **零生产调用**，支线派出去就没人管，
     * \`Guard.Tracker\` 的成功复位不可达 → "连续失败"只增不减，迟早把后续死亡支线全压掉。
     * 接在 \`RddDetector\` 观测到「背包重新出现死亡时丢的资产」处 —— 那是"捡回来了"唯一可靠的证据。
     */
    static boolean dropObservedRecovered(UUID companionId) {
        return !com.dwinovo.numen.rdd.core.RddDeathLedger.recovered(companionId).isEmpty();
    }

    /** \u652f\u7ebf\u5931\u8d25\uff1a\u7d2f\u8ba1\u8fde\u7eed\u5931\u8d25\uff08\u8fbe\u9608\u503c\u89e6\u53d1\u5347\u7ea7\u91cd\u89c4\u5212\uff09\u3002 */
    static void onRepairFailure(UUID companionId) {
        LocalRepairTask.Guard.Tracker tr = TRACKERS.get(companionId);
        if (tr != null) tr.recordFailure(LocalRepairTask.Trigger.DEATH);
    }

    static LocalRepairTask.Task lastRepair(UUID companionId) { return LAST_REPAIR.get(companionId); }

    /**
     * D20-C1：超时结账 —— 宿主每拍调（在 {@code deathDropsBackInInventory} 检查之前）。
     *
     * <p><b>修什么</b>：支线失败/超时后 {@code onRepairFailure} 零调用者（生产代码 grep 唯一命中
     * 是它自己的定义），{@code LAST_REPAIR} 里的任务永不清除。后果两个：
     * <ol>
     *   <li>{@code tickRuntime} 每拍空跑 {@code deathDropsBackInInventory}（背包扫描白做）；</li>
     *   <li>台账停 UNVERIFIED 永不结账 —— FV 终验实测（V-20261020-03 债①）。</li>
     * </ol>
     *
     * <p><b>结账语义</b>：超期 = 「这次支线没有在窗口内把掉落物捡回」→ 台账 {@code confirmLost}。
     * <b>不是</b>「物理上销毁了」—— 掉落物可能还在地上（区块没加载就不计龄），
     * 只是<b>本支线放弃追踪</b>。台账的 UNVERIFIED/CONFIRMED_LOST 语义见 RddDeathLedger.State。
     *
     * <p><b>75号 §3.3 对齐</b>：「结束恢复尝试不等于确认掉落销毁；不得用墙钟逾时替代实体证据」——
     * confirmLost 写的 evidence 明确说"支线超时放弃追踪"，不说"物品没了"。
     *
     * <p>★ 本方法刻意<b>不</b>调 {@code RddPlugin.saveLedger}：RddPlugin 的类加载链
     * （实现 NumenPlugin 接口）在纯 JVM 单测环境直接 NoClassDefFoundError。
     * 落盘由 {@code RddDetector.tickRuntime} 的调用方做（游戏环境必有 RddPlugin）——
     * {@code lastRepair} 返回 null 时如果台账刚被结过，调一次 saveLedger 即可。
     *
     * @return true = 本次调用把支线结掉了（宿主可据此跳过本拍的捡回检查并落盘台账）
     */
    static boolean tickTimeout(UUID companionId) {
        if (companionId == null) return false;
        // 统一 death_recovery 已接管驾驶与 deathSeq；旧兼容窗口不得清掉它的批次身份。
        if (DEATH_RECOVERY_HANDOFF.containsKey(companionId)) return false;
        LocalRepairTask.Task task = LAST_REPAIR.get(companionId);
        if (task == null) return false;
        Long created = REPAIR_CREATED_TICK.get(companionId);
        if (created == null) return false;
        long now = RddInstrumentation.currentGameTimeTicks();
        if (now - created < task.timeoutTicks()) return false;
        // 兼容旧 repair 的保护线到点只清掉旧内存工单；不能据此结算物理损失。
        // 真正的死亡恢复现在由 death_recovery SideTask 驱动，掉落状态由世界事实决定。
        LAST_REPAIR.remove(companionId);
        REPAIR_CREATED_TICK.remove(companionId);
        LocalRepairTask.Guard.Tracker tr = TRACKERS.get(companionId);
        if (tr != null) tr.recordFailure(LocalRepairTask.Trigger.DEATH);
        int seq = OPEN_DEATH_SEQ.getOrDefault(companionId, -1);
        if (seq >= 0) {
            OPEN_DEATH_SEQ.remove(companionId);
        }
        RddMonitor.publish("repair_task_window_ended", Map.of(
                "companionId", companionId.toString(),
                "repairId", task.id(),
                "deathSeq", seq,
                "timeoutTicks", task.timeoutTicks(),
                "note", "legacy repair window ended; ledger remains UNKNOWN until world evidence settles it"));
        return false;
    }

    static void remove(UUID companionId) {
        LAST_REPAIR.remove(companionId);
        REPAIR_CREATED_TICK.remove(companionId);   // D20-C1
        TRACKERS.remove(companionId);
        OPEN_DEATH_SEQ.remove(companionId);
        DEATH_RECOVERY_HANDOFF.remove(companionId);
        RddRepairLossAnalysis.remove(companionId);
    }
}
