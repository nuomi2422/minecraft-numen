package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.policy.AssetSemantics;
import com.dwinovo.numen.rdd.policy.LossImpactAnalyzer;
import com.dwinovo.numen.rdd.policy.LocalRepairTask;
import com.dwinovo.numen.rdd.policy.RequirementManifest;

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

    /**
     * 重算幂等键：{@code companion|deathSeq|phase}。
     *
     * <p>回收判据每拍都会问一次，"回收完成"与"超时结账"在边界上可能同拍/相邻拍都成立；
     * 没有这个集合，同一批损失会重复发事件、重复补一句 nudge（长会话里就是噪音洪水）。
     */
    private static final java.util.Set<String> RECOMPUTED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 非重复死亡的支线步骤（RL-7：先捡包后取备用，掉落物限时）。 */
    private static final String DEFAULT_STEPS =
            "先去世亡点 {at} 捡回掉落物（限时！约 5 分钟消失）；再取备用装备；然后回主链";
    private static final String DEFAULT_GOAL =
            "死亡支线：捡包限时优先（先捡后取备用），不整链重规划；完成后回主链";

    /** F4a 重复死亡：先活着离开，再回来捡。倒计时照样必须带上（R07）。 */
    private static final String REPEAT_DEATH_STEPS =
            "① 立刻离开 {at} 附近（刚在那里死了第二次）② 回基地补血/吃饱/换装备 ③ 再回来捡掉落物（限时）";
    private static final String REPEAT_DEATH_GOAL =
            "重复死亡支线：先撤离并补给，再限时回收掉落物；宁可不捡也不要再死一次";

    /**
     * 生成并（经护栏）投放一条死亡支线任务。 返回 true = 已投放（nudge 注入）。
     * 死亡触发优先级 CRITICAL（护栏放行）。
     *
     * <p><b>顺序是"先捡包、后取备用"（2026-09-29 用户实测纠正）</b>：
     * 掉落物约 <b>5 分钟</b>就 despawn，而"先回基地取备用装备"很容易把这段限时窗口耗光——
     * 那样就只捡回一部分、其余刷没了（用户原话：剪了，只剪了一部分，过 5 分钟有些就没了）。
     * 所以捡包**限时优先**，备用装备排后面；且必须把**死亡坐标**给它，否则它不知道去哪捡。
     *
     * <p><b>F4a 重复死亡例外</b>：同一点短时间内又死一次（实机 #3→#4 只隔 62 秒、坐标差 1 格）时，
     * "先回去捡"恰恰是送命的那一步 —— 她身上什么都没有，回到杀过她两次的地方再死一次。
     * 此时顺序改成「先撤离 + 补给 → 再回来捡」，且**仍然把剩余秒数带进文案**
     *（R07 已冻结：倒计时必须在它眼前）。
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
                    + "\n先做这三件事：① 立刻离开 " + at + " 附近（那地方已经杀了你两次）"
                    + " ② 回基地/安全处补血、吃饱、换掉破损装备 "
                    + " ③ 再回来捡掉落物（下面有时间表）" + timeline
                    + "\n如果装备掉得太惨、这一趟不值得冒险，就**明确说出来**：宁可少捡，也不要再死一次。"
                    + semanticNote
                    + "\n不要为这件事重规划整条主链。");
            return true;
        }
        RddPlugin.nudge(companionId, "[支线任务/死亡] 你刚死一次。掉落物在 "
                + at + " 附近——**注意：掉落物约 5 分钟就会消失**。"
                + "所以顺序是：先立刻回 " + at + " 把掉落物捡回来（限时优先），"
                + "再去取备用装备，最后回主链继续。" + semanticNote
                + "\n不要为这件事重规划整条主链。" + timeline);
        return true;
    }

    /**
     * S1 读路径（死亡瞬间）：只报"库存未知"，不下够/不够结论。
     *
     * <p><b>为什么不在这一拍读背包</b>：死亡与复活之间有一个窗口（原版掉落还在尸体上、
     * 同伴实体已 0 血），此时读到的"空"既可能是"全掉了"，也可能是"还没准备好"。
     * 把两者混成一个空表，就会在下一步推出"备用足够"——那是本批要修的核心假象
     * （81 号 §4.1：读取失败与真实空库存必须分开）。
     *
     * <p>所以这里固定传 {@code inventoryKnown=false}、{@code backup={}}：
     * 结论必然是 {@code CONFIRM_STOCK}，文案说"先确认库存"，绝不说"0 件"。
     * 真正的判定在 {@link #recomputeAfterDeath}。
     *
     * <p>fail-soft：任何一步炸了返回 null（事件不带 lossImpact 键、文案不带语义段），
     * 死亡支线本身照旧 —— 语义分析是增强，不是依赖（分析器「只产事实与建议」的红线）。
     */
    private static LossImpactAnalyzer.LossImpact analyzeDeathLoss(UUID companionId,
            com.dwinovo.numen.rdd.core.RddDeathLedger.Death death) {
        try {
            if (death == null || death.lostItems().isEmpty()) {
                return null;   // 没记录掉落清单（旧台账/无背包数据）→ 不硬凑分析
            }
            RequirementManifest.Manifest manifest = RddFactContext.currentManifest(companionId);
            List<RequirementManifest.Manifest> manifests =
                    manifest == null ? List.of() : List.of(manifest);
            Map<String, AssetSemantics> semantics =
                    RddSemanticsStore.forAnalyzer(RddSemanticsStore.semantics(companionId));
            return LossImpactAnalyzer.analyze(
                    new LinkedHashMap<>(death.lostItems()),
                    Map.of(),                       // 随身：未知，不是空
                    Map.of(),                       // 备用：P0 只认"真观测到"的基地容器
                    manifests,
                    semantics,
                    false,                          // 掉落还在地上：未确认丢失
                    false);                         // ★ 库存未确认（81 号 §4.1）
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * 死亡后重算：回收完成 / 超时结账那一拍，手里有**真实背包**才算得准。
     *
     * <p>三条输入都有出处：{@code counts} 是本拍真实扫描；{@code backup} 来自基地容器观测
     * （{@link #backupFromBase}，带新鲜度门槛）；{@code lossConfirmed} 由调用方按台账状态给
     * （回收成功 = false，超时结账 = true，语义是"本支线放弃追踪"而不是"物理销毁"）。
     *
     * <p>幂等：同一 {@code deathSeq + phase} 只发一次事件。重复调用只返回结论，不重复打扰。
     *
     * @return 分析结论；没有死亡记录/清单为空/异常时 null
     */
    /**
     * 与内存无关的兜底（2026-10-28 实机教训）：死亡后派的"修理工单"靠静态内存表计时
     * （REPAIR_CREATED_TICK / LAST_REPAIR），**实例一重启就清空**，于是"5 分钟超时重算"
     * 再也不会触发。实测 11:18 那次死亡工单事件在案，但 loss_impact_recomputed 恒为 0，
     * 而那段时间重启过两次。这里给一条不依赖内存的入口：对台账里最近一次死亡重算一次
     * （内部按 companion|seq|phase 去重，同一会话只算一次）。
     */
    static void recomputeLatestFromLedger(UUID companionId, java.util.Map<String, Integer> counts) {
        try {
            if (companionId == null) return;
            com.dwinovo.numen.rdd.core.RddDeathLedger.Death latest =
                    com.dwinovo.numen.rdd.core.RddDeathLedger.latest(companionId);
            if (latest == null || latest.lostItems().isEmpty()) return;
            // 2026-10-28：这里原本 catch 后静默吞掉 —— 而"重算一次都没发生"最可能就是被吞掉的异常。
            // 兜底仍然 fail-soft，但**必须留下证据**（一行日志胜过再猜三轮）。
            com.dwinovo.numen.core.Constants.LOG.info(
                    "[rdd-repair] recompute-from-ledger start companion={} latestSeq={} lostKinds={}",
                    companionId, latest.seq(), latest.lostItems().size());
            var impact = recomputeAfterDeath(companionId, counts, false, "restart", latest.seq());
            com.dwinovo.numen.core.Constants.LOG.info(
                    "[rdd-repair] recompute-from-ledger done companion={} seq={} impact={}",
                    companionId, latest.seq(), impact == null ? "null" : impact.recommendation());
        } catch (RuntimeException e) {
            // fail-soft：兜底不能拖垮 tick；但把异常打出来（原来这里是静默的，导致整条链无迹可查）
            com.dwinovo.numen.core.Constants.LOG.info(
                    "[rdd-repair] recompute-from-ledger FAILED companion={} : {}", companionId, e.toString());
        }
    }
    static LossImpactAnalyzer.LossImpact recomputeAfterDeath(UUID companionId,
                                                             Map<String, Integer> counts,
                                                             boolean lossConfirmed,
                                                             String phase) {
        return recomputeAfterDeath(companionId, counts, lossConfirmed, phase, -1);
    }

    /**
     * @param seqHint 调用方手上的死亡批次号（超时结账会先清 OPEN_DEATH_SEQ，必须由调用方留底）。
     *                -1 = 让它自己找（当前支线批次，找不到退最近一条）。
     */
    static LossImpactAnalyzer.LossImpact recomputeAfterDeath(UUID companionId,
                                                             Map<String, Integer> counts,
                                                             boolean lossConfirmed,
                                                             String phase,
                                                             int seqHint) {
        try {
            if (companionId == null) return null;
            com.dwinovo.numen.rdd.core.RddDeathLedger.Death death = deathForRecompute(companionId, seqHint);
            if (death == null || death.lostItems().isEmpty()) return null;
            String dedupeKey = companionId + "|" + death.seq() + "|" + phase;
            if (!RECOMPUTED.add(dedupeKey)) return null;   // 同一批次同一阶段只报一次
            if (RECOMPUTED.size() > 256) RECOMPUTED.clear();   // 有界：死亡很多次也不让集合长成漏勺

            // ★ 2026-10-28 实机（#239）：`LossImpactAnalyzer.analyze` 在没有"当前目标清单"时返回 null，
            //   于是整条重算在**没有 manifest 的那一拍**直接消失（实测：她全丢、台账 seq=15 有 25 件，
            //   但 loss_impact_recomputed 恒 0）。这里补一支"无清单也要留证"的兜底：
            //   至少把"丢了什么、多少件、这次是哪一批死亡"落成事件，别让事实消失。
            RequirementManifest.Manifest manifest0 = RddFactContext.currentManifest(companionId);
            if (manifest0 == null) {
                Map<String, Object> ev0 = new LinkedHashMap<>();
                ev0.put("companionId", companionId.toString());
                ev0.put("deathSeq", death.seq());
                ev0.put("phase", phase);
                ev0.put("noManifest", true);
                ev0.put("lostKinds", death.lostItems().size());
                ev0.put("lostTotal", death.lostItems().values().stream().mapToInt(Integer::intValue).sum());
                ev0.put("lostItems", new LinkedHashMap<>(death.lostItems()));
                ev0.put("inventoryKnown", counts != null);
                ev0.put("recommendation", "CONFIRM_STOCK");
                ev0.put("summary", "没有当前目标清单，无法按目标算影响；但这次确实丢了 "
                        + death.lostItems().size() + " 种物品，先确认背包再决定补不补");
                RddMonitor.publish("loss_impact_recomputed", ev0);
                return null;
            }            RequirementManifest.Manifest manifest = RddFactContext.currentManifest(companionId);
            List<RequirementManifest.Manifest> manifests =
                    manifest == null ? List.of() : List.of(manifest);
            Map<String, AssetSemantics> semantics =
                    RddSemanticsStore.forAnalyzer(RddSemanticsStore.semantics(companionId));
            Map<String, Integer> backup = backupFromBase(companionId);
            LossImpactAnalyzer.LossImpact impact = LossImpactAnalyzer.analyze(
                    new LinkedHashMap<>(death.lostItems()),
                    counts == null ? Map.of() : counts,
                    backup,
                    manifests,
                    semantics,
                    lossConfirmed,
                    counts != null);                 // 只有真扫过才算"库存未知解除"

            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("companionId", companionId.toString());
            ev.put("deathSeq", death.seq());
            ev.put("phase", phase);
            ev.put("recommendation", impact.recommendation().name());
            ev.put("reason", impact.reason());
            ev.put("importantLoss", impact.importantLoss());
            ev.put("backupSufficient", impact.backupSufficient());
            ev.put("lossConfirmed", impact.lossConfirmed());
            ev.put("inventoryKnown", impact.inventoryKnown());
            ev.put("backupKeyCount", backup.size());
            ev.put("summary", impact.summary());
            List<Map<String, Object>> goals = new ArrayList<>();
            for (LossImpactAnalyzer.GoalImpact g : impact.affectedGoals()) {
                Map<String, Object> gm = new LinkedHashMap<>();
                gm.put("goalId", g.goalId());
                gm.put("requirementKey", g.requirementKey());
                gm.put("minimum", g.minimum());
                gm.put("available", g.available());
                gm.put("backup", g.backup());
                gm.put("coveredBy", g.coveredBy());
                goals.add(gm);
            }
            ev.put("affectedGoals", goals);
            RddMonitor.publish("loss_impact_recomputed", ev);

            // 只在她"该改主意"的时候补一句：有备用 → 别去补货；确认缺口 → 去补。
            // CONFIRM_STOCK / NONE 不打扰（死亡那条 nudge 已经说过"先捡、先确认"）。
            if (impact.recommendation() == LossImpactAnalyzer.Recommendation.SWAP_BACKUP) {
                RddPlugin.nudge(companionId, "[损失影响] 回收这一趟结束：" + impact.summary()
                        + "\n→ **基地备用/替代已经够用**：换装继续原目标即可，不必为了这几件回炉重做。"
                        + "如果备用也已经被用掉，就说一声，再去补。");
            } else if (impact.recommendation() == LossImpactAnalyzer.Recommendation.SUPPLEMENT) {
                RddPlugin.nudge(companionId, "[损失影响] 回收结束，缺口确认：" + impact.summary()
                        + "\n这几件是真缺（不是没找到），接下来按目标需要补上；别把'没捡到'当成'已经够了'。");
            }
            return impact;
        } catch (RuntimeException ex) {
            return null;   // 增强旁路：分析炸了不影响支线结账
        }
    }

    /** 重算要盯的那次死亡：优先当前支线批次，批次已结账则用最近一条。 */
    private static com.dwinovo.numen.rdd.core.RddDeathLedger.Death deathForRecompute(UUID companionId,
                                                                                int seqHint) {
        int seq = seqHint >= 0 ? seqHint : openDeathSeq(companionId);
        if (seq >= 0) {
            var open = com.dwinovo.numen.rdd.core.RddDeathLedger.openDeath(companionId, seq);
            if (open != null) return open;
        }
        return com.dwinovo.numen.rdd.core.RddDeathLedger.latest(companionId);
    }

    /**
     * 已知备用：只读**缓存里的基地容器观测**（{@code world_base.important_items}，最多 24 种）。
     *
     * <p>三条诚实边界：
     * <ol>
     *   <li>只认"看到过的"：没进前 24 种类的物品视为<b>未观测</b>，不能据此判"没有"；</li>
     *   <li>有新鲜度门槛（10 分钟墙钟）：过期观测只能当线索，不能当"家里有"；</li>
     *   <li>不读世界、不扫容器：这一层在 tick 里被调用，绝不允许为了分析去翻区块。</li>
     * </ol>
     */
    static Map<String, Integer> backupFromBase(UUID companionId) {
        try {
            if (companionId == null) return Map.of();
            var rt = RddPlugin.runtime(companionId);
            if (rt == null || rt.assets() == null) return Map.of();
            long now = System.currentTimeMillis();
            for (var entry : RddAssetContext.worldAssets(rt.assets())) {
                if (!"world_base".equals(entry.observation().type())) continue;
                long age = now - entry.observation().observedAt();
                if (age < 0 || age > 10L * 60L * 1000L) return Map.of();   // 过期 = 未知，不装新鲜
                Object items = entry.observation().value().get("important_items");
                if (!(items instanceof Map<?, ?> map)) return Map.of();
                Map<String, Integer> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!(e.getKey() instanceof String key) || key.isBlank()) continue;
                    Integer n = asInt(e.getValue());
                    if (n != null && n > 0) out.put(key, n);
                }
                return out;
            }
        } catch (RuntimeException ignored) {
            // 备用读不到 = 未知；调用方会走 CONFIRM_STOCK，不会拿"空"当"没有"
        }
        return Map.of();
    }

    private static Integer asInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String str) {
            try { return Integer.parseInt(str.trim()); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    /**
     * S1 文案渲染：把分析结论翻译给执行 AI（判据 S2/S4）。
     *
     * <p>只有**有话说**时才渲染——importantLoss=false 且无受影响目标 = 没丢重要的，
     * 硬加一段只会教她「什么损失都值得大惊小怪」。SUPPLEMENT 时点明
     * 「目标 X 需要补 xxx ×N」（判据 S4 的行为通道：她下一步该去补）。
     */
    static String renderSemanticNote(LossImpactAnalyzer.LossImpact impact) {
        if (impact == null) return "";
        if (!impact.importantLoss() && impact.affectedGoals().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n[损失影响] ");
        sb.append(impact.summary());
        if (!impact.inventoryKnown()) {
            // 死亡瞬间的口径：读不到 ≠ 0。这句话必须出现，否则执行体容易把"还没数"当"已经没了"。
            sb.append("。此刻还没有重新读到背包（不是\"0 件\"）——先按上面的顺序捡包，捡完再核对。");
            return sb.toString();
        }
        switch (impact.recommendation()) {
            case SUPPLEMENT -> sb.append("。先按上面的顺序把能捡的捡回；捡不回的缺口（尤其受影响目标里的物品）需要重新补充，别装作没看见。");
            case CONFIRM_STOCK -> sb.append("。捡回后核对背包：只确认丢失的部分才需要补，不确定就先数一数再决定。");
            case SWAP_BACKUP -> sb.append("。备用/替代仍能满足需求：换装继续即可，不必专门补货。");
            default -> { return sb.toString(); }
        }
        return sb.toString();
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
        LocalRepairTask.Task task = LAST_REPAIR.get(companionId);
        if (task == null) return false;
        Long created = REPAIR_CREATED_TICK.get(companionId);
        if (created == null) return false;
        long now = RddInstrumentation.currentGameTimeTicks();
        if (now - created < task.timeoutTicks()) return false;
        // 到点：结账 + 清支线（与 onRepairSuccess 的清理路径保持一致）
        LAST_REPAIR.remove(companionId);
        REPAIR_CREATED_TICK.remove(companionId);
        LocalRepairTask.Guard.Tracker tr = TRACKERS.get(companionId);
        if (tr != null) tr.recordFailure(LocalRepairTask.Trigger.DEATH);
        int seq = OPEN_DEATH_SEQ.getOrDefault(companionId, -1);
        if (seq >= 0) {
            OPEN_DEATH_SEQ.remove(companionId);
            com.dwinovo.numen.rdd.core.RddDeathLedger.confirmLost(companionId, seq,
                    "repair branch timed out after " + (task.timeoutTicks() / 20) + "s; "
                            + "drops may still exist on the ground — this closes the branch, not the world");
        }
        RddMonitor.publish("repair_task_timed_out", Map.of(
                "companionId", companionId.toString(),
                "repairId", task.id(),
                "deathSeq", seq,
                "timeoutTicks", task.timeoutTicks(),
                "note", "branch abandoned; ledger set CONFIRMED_LOST (tracking-level, not physical evidence)"));
        return true;
    }

    static void remove(UUID companionId) {
        LAST_REPAIR.remove(companionId);
        REPAIR_CREATED_TICK.remove(companionId);   // D20-C1
        TRACKERS.remove(companionId);
        OPEN_DEATH_SEQ.remove(companionId);
        RECOMPUTED.removeIf(k -> companionId != null && k.startsWith(companionId.toString()));
    }
}
