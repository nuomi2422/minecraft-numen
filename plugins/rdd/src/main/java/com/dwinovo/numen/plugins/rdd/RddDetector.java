package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.entity.DeathInventorySnapshot;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.HardCodedEvaluator;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RDD 的硬编码检测驱动器 + 身体执行桥：服务端每 tick 跑一次（节流到 1 秒）。
 *
 * <p>对每个活跃任务链：
 * <ol>
 *   <li>当前二级 PENDING → 自动启动（首个由 sink 启动，后续由这里推进）</li>
 *   <li>二级带 body 且未提交 → 经 {@link ToolRegistry#resolve} 找身体工具直接
 *       {@code onServerCall} 提交（每二级只提交一次）</li>
 *   <li>读<b>真实背包</b>，HARD_CODED 满足 → 推进</li>
 *   <li>身体任务已结束（槽空）但条件未满足 → 重试 ≤{@code 3}，否则 markFailed</li>
 * </ol>
 *
 * <p>只读世界真身，不读 AI 自报。二级全完成 → 简化 Supervisor CONFIRM → 一级完成。
 */
final class RddDetector {
    static final Logger LOG = LoggerFactory.getLogger(RddDetector.class);
    /** 每多少 tick 检测一次；20 tick = 1 秒。 */
    private static final int TICKS_PER_CHECK = 20;
    /** 资产提前触发：单个 tick 内最多连跳过多少个"资产已满足"的二级（防极端长链死循环）。 */
    private static final int MAX_INSTANT_PASS = 32;

    /** 熔炉/容器观测：GUI 关闭后保留快照 + 容器内容指纹（燃料不算产出）。 */
    private final RddFurnaceWatch furnaceWatch = new RddFurnaceWatch();

    /** 卡死监督：指纹跟踪 + 拍醒 / 恢复 / 升级失败，顺带累计能力缺口。 */
    private final RddStallWatcher stallWatcher = new RddStallWatcher(furnaceWatch);

    /** 已因「停车态条件已达成」自动回退过的一级（uuid->primaryId）：同级只回退一次，防停车/回退乒乓。 */
    private final Map<UUID, String> earlyAcceptResumed = new ConcurrentHashMap<>();
    /**
     * 已发过的等待类事件（uuid -> "原因|一级id"），2026-09-28。
     *
     * <p>实测教训：{@code primary_waiting} / {@code subtask_parked_silent} 原本**每 tick 重发**，
     * 一次卡死就刷出 90+ 条几乎相同的记录，把监测台和日志淹掉，真正的新事件反而看不见。
     * 现在同一状态只发一次（边沿去重），**状态变化或换一级才会再发**；间隔 5 分钟兜底重播一次，
     * 免得"早就没人管了"这件事静默消失。
     */
    private final Map<UUID, String> waitingNotice = new ConcurrentHashMap<>();
    /**
     * 背包快满提醒的去重状态（用户 2026-09-28 点名要的功能：满之前就提醒，别等捡不起来才报错）。
     * 记「上次提醒时刻」与「上次提醒时的剩余格数」，供 {@link com.dwinovo.numen.rdd.policy.BackpackSpace#shouldWarn}
     * 判断：首次/更紧/过冷却才发，防刷屏（本项目有等待类事件每 tick 重发过的前科）。
     */
    private final Map<UUID, Long> backpackWarnedAt = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> backpackWarnedFree = new ConcurrentHashMap<>();

    /**
     * 等待类事件的边沿去重发布。
     *
     * @return true 表示这次真的发出去了（调用方可据此决定要不要做后续动作）
     */
    private boolean publishWaitingOnce(NumenPlayer ap, String key, String event, Map<String, ?> data) {
        long now = System.currentTimeMillis();
        String prev = waitingNotice.put(ap.getUUID(), key + "@" + now);
        if (prev != null && prev.startsWith(key + "@")) {
            long then = 0;
            try {
                then = Long.parseLong(prev.substring(key.length() + 1));
            } catch (NumberFormatException ignored) {
                // 解析不了就当新的，发出去
            }
            if (now - then < WAITING_NOTICE_REPEAT_MS) {
                return false;   // 同一状态、间隔内：不再刷屏
            }
            return true;        // 超过兜底间隔：重播一次，提醒仍然卡着
        }
        RddMonitor.publish(event, castMap(data));
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<String, ?> m) {
        return (Map<String, Object>) m;
    }

    /** 同一等待状态的重播间隔（5 分钟）：既不刷屏，又不让"没人管"静默。 */
    private static final long WAITING_NOTICE_REPEAT_MS = 5 * 60 * 1000L;
    /** 停车守望：停车后长期无进展要能再拍醒，拍醒预算耗尽要发可见事件（不能无声冻结）。 */
    private final RddParkedWatcher parkedWatcher = new RddParkedWatcher(stallWatcher);
    private final RddFailureRecovery failureRecovery = new RddFailureRecovery(furnaceWatch, stallWatcher, parkedWatcher);

    private final RddSurplus surplus = new RddSurplus();

    private int tickCounter;
    /** 资产 populate 节流：每 5 次检测（约 5 秒）把背包物品写进 AssetRegistry。 */
    private int assetTick;
    /** 世界资产只观察已加载范围；每 30 秒一次，避免把“资产库”变成全图扫描器。 */
    private int worldAssetTick;

    RddDetector() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppedEvent event) -> {
                    furnaceWatch.clear();
                    stallWatcher.clearAll();
                    failureRecovery.clearRetries();
                    failureRecovery.clearParked();
                    parkedWatcher.clearAll();
                    surplus.clear();
                    RddPlugin.clearNudgeThrottleAll();
                    tickCounter = 0;
                    assetTick = 0;
                    worldAssetTick = 0;
                });
    }

    void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        // 042 R2 条件 4（2026-11-05 审计）：危险采样**必须在心跳闸之外**。
        // 放在里面时，sampleOnTick 每秒才被调一次，它自己的 5 tick 闸永远满足 → 实际 1 Hz，
        // 而代码看起来是 4 Hz（"空转常量"是代码说一套、运行做一套的最小形态）。
        // 代价：29us × 4/s ≈ 0.12ms/s，可忽略；收益：保命信号延迟从 1 秒回到 0.25 秒。
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof NumenPlayer ap) {
                RddPlugin.recheckHomeBinding(ap);
                RddDangerFacts.sampleOnTick(ap);
            }
        }
        if (++tickCounter < TICKS_PER_CHECK) {
            return;
        }
        tickCounter = 0;
        assetTick = (assetTick + 1) % 5;
        worldAssetTick = (worldAssetTick + 1) % 30;
        // 空转止血：每次心跳刷新监督开关（flag 文件由监测台/人写，pause=停拍醒）
        RddPlugin.refreshSupervisionFlag();
        RddPlugin.refreshPauseFlag();   // 暂停开关（2026-09-30 用户要求可随时关，防误伤实验）
        // 单驾驶员：同一心跳刷新"是否允许 RDD 自动提交身体工具"（默认关；见 RddPlugin 字段注释）。
        RddPlugin.refreshBodyDispatchFlag();
        // 重启恢复：磁盘有任务但内存无 → 加载为 RddRuntime（幂等，RECOVERING）
        RddPlugin.restoreRuntimes();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!(p instanceof NumenPlayer ap)) {
                continue;
            }
            // 2026-10-25：每 tick 记账「这只同伴的真身体」——读世界（箱子存货）只认这个实例。
            RddCompanionBody.remember(ap);
            // A death-window body is not current task capability. Preserve the pre-death cache
            // (or leave it untouched when evidence is UNKNOWN) and skip all living runtime work.
            if (freezeDeathInventoryAndSkip(ap)) continue;
            long tick = RddClock.current(ap.serverLevel());   // 唯一 tick 来源（见 RddClock）
            // 039 U1：environment 主题由这里按 5 秒节奏显式刷新——读口只读缓存，没人刷就永远 PENDING。
            // 只放在服务端 tick，绝不在上下文组装线程里做。
            RddContainerMemory.refresh(ap, tick);
            RddObservation.refreshIfDue(ap, tick);
            // 042 R2：危险采样已移到心跳闸之外（见方法开头），这里不再重复调用。
            // P2-A【B】背包感知不依赖任务链：先无条件缓存实时背包，再判是否有 Runtime。
            // （感知世界 与 任务运行 不是一个生命周期；/goal 首次规划早于链建立，旧代码要求 rt!=null
            //  才扫背包 → 首次规划/判定读到空背包。）
            try {
                RddPlugin.cacheInventory(ap.getUUID(), countInventory(ap));
            } catch (RuntimeException ex) {
                LOG.warn("[rdd] 无条件背包缓存失败 {}: {}", ap.getUUID(), ex.toString());
            }
// 38号v3.6 B24：携带器缓存**只在主线程刷新**（这里就是 tick，已在服务端线程）。
            // 上下文构建那边只读缓存 —— contributeState 不保证在主线程，不能在那里读世界。
            // ⚠️ 2026-10-01 实机抓到：**必须放在 rt == null 的判断之前**。
            //    原来放在后面，于是「同伴没有活动任务链」时直接 continue → 永远不刷新，
            //    而那恰恰是最需要携带提醒的时刻（无任务 = 更该自己决定要不要先准备）。
            RddCarryHint.refresh(ap.serverLevel().getServer(), ap.getUUID());
            // B2b: environment uses detached SELF; no second recent-hurt fact cache.
            // 2026-10-08 正式睡觉支线：不要求必须有主线链——睡觉是基本的夜间行为，
            // 无链时也能触发（主线恢复令牌为空即可）。先驱动支线，再走主线检测（支线 active 时主线冻结）。
            SideTaskHost.tick(server, ap);
            // 2026-11-11: nearby machines must be observed even without a task chain.
            if (worldAssetTick == 0) {
                try {
                    RddWorldAssetObserver.Result observed = RddWorldAssetObserver.observe(ap, RddPlugin.assets(ap.getUUID()));
                    RddPlugin.saveAssets(ap.getUUID());
                    RddPlugin.publishAssetSnapshot(ap.getUUID(), "lazy_world_observation", observed);
                } catch (RuntimeException ex) {
                    LOG.warn("[rdd] passive world asset observation failed {}: {}", ap.getUUID(), ex.toString());
                }
            }
            RddRuntime rt = RddPlugin.runtime(ap.getUUID());
            if (rt == null) {
                continue;
            }
            // P1-C: finish the same pending stage before further primary-chain mutations/dispatch.
            if (RddPlugin.completionPending(ap.getUUID())) {
                RddPlugin.retryPendingCompletion(ap.getUUID(), rt);
                continue;
            }
            if (rt.chain().primaryStatus() == PrimaryGoalStatus.AWAITING_SUPERVISOR) {
                RddPlugin.finishResolvedPrimary(ap.getUUID(), rt);
                if (RddPlugin.completionPending(ap.getUUID())) continue;
            }
            // 2026-11-06 生存抢占：hunger<10/hp<8 时停当前身体任务并重规划（重规划会命中计划前闸直出进食链）。
            survivalPreempt(ap, tick);
            tickRuntime(ap, rt);
        }
        // 持久化：保存活跃任务链（1 秒一次，文件小，原子写）
        RddPlugin.saveRuntimes();
    }

    /**
     * 暂停中的二级：条件一旦满足立刻开回来，否则到期强制她复评。
     *
     * <p>2026-09-30 实机：PAUSE 触发两次（16:31 雪块在 4189 格外、16:49 河边高度不对），
     * 两次都卡死在 PAUSED —— 因为 {@code resumeFromPaused} 全仓只有单测在调。
     * 军师其实已经改好单了（grass_block>=1 → dirt>=1，通道里就有 dirt），链却不动。
     */
    private void handlePaused(NumenPlayer ap, RddRuntime rt, TaskChain chain,
                              Map<String, Integer> counts) {
        Subtask cur = chain.currentSubtask();
        if (cur == null) return;
        long now = ap.level().getGameTime();
        // 深审 R03：没有起点就补一个（重启恢复出的 PAUSED 链会走这里）。
        // 有了起点只读不消费，所以不会像旧实现那样"第一次问就把起点删了"。
        if (!rt.hasPausedMark(cur.id())) {
            rt.markPaused(cur.id(), now);
        }
        long pausedFor = rt.pausedTicks(cur.id(), now);

        // 主人暂停（OWNER_PAUSE）：**永不过期、绝不自动恢复**。
        // 深审 R04：主人说"原地待命"，60 秒后被系统当成"卡住"强行拉去干活，
        // 是把主人的明确指令当成故障 —— 后果比永远暂停更糟。
        if (isOwnerPause(chain, cur)) {
            return;
        }

        // Recovery pauses require the missing equipment, not the original trading condition or a timeout.
        if (chain.pauseReason(cur.id()) != null && chain.pauseReason(cur.id()).startsWith("recovery_not_verified:")) {
            if (!SideTaskHost.holdRecoveryPause(ap, chain)) {
                rt.clearPaused(cur.id());
                RddPlugin.publishTaskSnapshot(ap.getUUID(), "recovery_equipment_verified_resumed");
            }
            return;
        }
        // ① 条件其实已经满足了（规划器改过单、或她其实能做到）→ 通融：立刻开回来。
        if (conditionMatches(ap, cur, counts)) {
            try {
                chain.resumeFromPaused(cur.id());
                rt.clearPaused(cur.id());
                RddMonitor.publish("subtask_resumed", Map.of(
                        "subtask", cur.id(), "reason", "paused condition now satisfied"));
                RddPlugin.nudge(ap.getUUID(),
                        "「" + cur.description() + "」的暂停已经解除——条件现在满足了，开干。");
            } catch (RuntimeException ignored) { /* 状态刚变，交给下一 tick */ }
            return;
        }
        // ② 到期强制复评：暂停不是"永远停"（但主人暂停在上面已经提前返回了）。
        if (pausedFor >= PAUSE_RECHECK_TICKS) {
            RddMonitor.publish("subtask_pause_recheck", Map.of(
                    "subtask", cur.id(), "pausedSeconds", pausedFor / 20,
                    "reason", "paused too long without the condition becoming satisfiable"));
            RddPlugin.nudge(ap.getUUID(),
                    "「" + cur.description() + "」你已经暂停 " + (pausedFor / 20) + " 秒了，"
                    + "条件仍然不满足。两条路二选一：① 找一条真正可行的替代做法（说明是什么）；"
                    + "② 用 report_task_concern 上报，把这个目标从当前计划里移出去。"
                    + "不要无限期停在一个做不了的事上。");
            try {
                chain.resumeFromPaused(cur.id());
                // 2026-09-30 深审 R03/codex：这里原本是 clear+markPaused 重新起算，
                // 结果"恢复 RUNNING"之后起点还留着 —— 稍后再次 PAUSE 时 putIfAbsent
                // 会沿用**上次复评**的时间，于是没重新暂停满 60 秒就被催。
                // 正确做法：退出暂停就清干净（真正再次进入暂停时 markPaused 会重新记）。
                // 催促冷却若要，那是另一件事，不该借用暂停起点。
                rt.clearPaused(cur.id());
                RddMonitor.publish("subtask_resumed", Map.of(
                        "subtask", cur.id(), "reason", "pause recheck; back to running for a verdict"));
            } catch (RuntimeException ignored) { /* 下一 tick 再试 */ }
        }
    }

    /**
     * 这条暂停是不是主人下的（而不是士兵/规划器自己报的）。
     *
     * <p>深审 R04：两类暂停的意图完全不同 ——
     * 士兵 PAUSE 是"我做不了，等条件变"，到期该催她换办法；
     * 主人暂停是"原地待命"，**多久都不该被系统推翻**。
     * 判据是暂停原因前缀（TaskChain 只存字符串，没有独立的 PauseKind 字段——
     * 那是下一轮"RddRuntime 与 TaskChain 归一到同一份暂停元数据"要收敛的）。
     */
    private static boolean isOwnerPause(TaskChain chain, Subtask cur) {
        return chain != null && cur != null && chain.isOwnerPause(cur.id());
    }

    /**
     * 暂停多久强制复评：60 秒。
     *
     * <p>够她真的去试一条替代路线（跑一趟、问一次军师），又不会让一个做不了的目标
     * 无限期挂着占住整条链。实机 2026-09-30 两次 PAUSE 都因为"没人会把它开回来"卡死，
     * 这条就是兜底闸。
     */
    private static final long PAUSE_RECHECK_TICKS = 60L * 20L;

    /**
     * 背包里是否<b>把这一批死亡掉落真的捡回来了</b>（死亡支线完成判据，F6）。
     *
     * <p><b>旧判据的错在哪</b>（2026-09-30 实机 + 深审 R08）：它只问
     * "背包里<b>任何一件</b>当初标 LOST 的东西是不是 &gt;0"，于是
     * ① 33 格掉落里捡回 1 格就算整条支线成功；
     * ② {@code AssetHistory} 是长期累积表，上一批的物品回来会替这一批结账。
     *
     * <p><b>现在的判据</b>：只认<b>当前支线对应的那一次死亡</b>，
     * 按该批次自己记录的 {@code lostItems} 逐项核对（逻辑在
     * {@link com.dwinovo.numen.rdd.core.RddDeathLedger#checkBatchRecovery}，纯 JVM 可单测）。
     * 部分找回只发 {@code repair_task_progress}，<b>不</b>在这里结账。
     */
    private static boolean deathDropsBackInInventory(NumenPlayer ap, Map<String, Integer> counts) {
        try {
            RddPlugin.ensureLedgerLoaded(ap.getUUID());
            RddRepairDispatch.reportBatchProgress(ap.getUUID(), counts);
            return RddRepairDispatch.checkBatchRecovered(ap.getUUID(), counts);
        } catch (RuntimeException ignored) {
            // 台账读不到就判"没进展"，绝不让判据本身拖垮 tick
            return false;
        }
    }

    /**
     * 普通周期入口（2026-11-24 060 B 收窄）：只清旧生存意图、只驱动 PENDING 惰性展开，
     * <b>不</b>按 hunger/HP 分类、不请求重规划、不停身体、不花旧 60 秒预算。
     *
     * <p>为什么收窄：2026-11-24 实机三次 {@code survival_preempt}（hunger=9 hp=20 free=36）
     * 都不是真实紧急伤害，却触发停任务 + 重规划；而饥饿/低血事实已由
     * {@code SideTaskHost.tick} 先采样进上下文，普通周期再发第二道指令只会和 AI 自己的判断打架。
     * 真紧急的硬控制不来自这个日常分支：Numen 原紧急 FC 不动，
     * {@code RddPlugin.requestReplanForSurvival}（typed SURVIVAL）留给未来的硬 side。
     */
    private static void survivalPreempt(com.dwinovo.numen.entity.NumenPlayer ap, long tick) {
        try {
            // 2026-11-11: hunger while first expansion is pending is not a failed execution.
            // Reuse the lazy driver even when a survival side task owns the body.
            RddRuntime runtime = RddPlugin.runtime(ap.getUUID());
            if (runtime == null) return;
            TaskChain chain = runtime.chain();
            // 安全观测必须在所有早退之前：风险消退要释放上一轮生存意图与冷却，
            // 否则再跌回 9 会被旧 marker/冷却吞掉（V04 之后的设计要求）。
            int hunger = ap.getFoodData().getFoodLevel();
            double hp = ap.getHealth();
            if (hunger >= 10 && hp >= 8.0) {
                // 风险消退释放意图和同一份冷却，不代表完成供食承诺。
                RddReplanTransition.observeSafe(ap.getUUID());
            }
            // PENDING 未展开：沿用原惰性展开，不花失败预算。
            if (chain.primaryStatus() == PrimaryGoalStatus.PENDING
                    && chain.currentPrimary().unexpanded()) {
                RddGoalDriver.needExpansion(ap.getUUID());
                return;
            }
            // 支线占身体时主线冻结；非 ACTIVE 也不在这个普通分支里做任何事。
            if (SideTaskHost.blocksMainline(ap.getUUID())
                    || chain.primaryStatus() != PrimaryGoalStatus.ACTIVE) return;
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 普通周期生存观测失败: {}", ex.toString());
        }
    }

    private void tickRuntime(NumenPlayer ap, RddRuntime rt) {
        try {
            TaskChain chain = rt.chain();
            PrimaryGoalStatus ps = chain.primaryStatus();
            // 真实背包先扫+缓存（规划注入数据源）；缓存是女仆属性，收尾/监督态也照常更新。
            Map<String, Integer> counts = countInventory(ap);
            // ★ 2026-10-28（#238）兜底必须放在**所有早退之前**：stallWatcher 命中会 return，
            //   而"卡住"恰恰是死亡后最可能的状态 —— 放后面等于永不执行（第一版就犯了这个错）。
            RddRepairDispatch.recomputeLatestFromLedger(ap.getUUID(), counts);
            RddWaypoints.observe(ap);   // #252：记维度锚点（"门在哪"），供回家提示用
            // A3（95 号）：四层事实第一版（fact/progress，只读，不改规划）。放在所有早退之前，
            // 否则"卡住"这种最该被看到的状态会被早退挡掉（#238 的同一教训）。
            // 2026-11-05：只把「有活在推进」算作可能停滞；链已完成时站着不动不是停滞。
            boolean busyNow = ps == PrimaryGoalStatus.ACTIVE
                    || SideTaskHost.blocksMainline(ap.getUUID());
            RddSituationFacts.tick(ap, busyNow);
            // D20-C1：先跑超时结账 —— 过期支线清掉后，下面的 lastRepair 才不会对死支线空转。
            // tickTimeout 不落盘（单测环境 RddPlugin 类链炸）；这里在游戏环境补 saveLedger。
            int deathSeqAtTick = RddRepairDispatch.openDeathSeq(ap.getUUID());
            if (RddRepairDispatch.tickTimeout(ap.getUUID())) {
                // 81 号 P0-A：超时结账这一拍手里有真实背包 → 用**净损失**重算一次影响，
                // 给"确认缺口"或"备用其实够"一个可执行结论（死亡那一拍只能报"库存未知"）。
                RddRepairDispatch.recomputeAfterDeath(ap.getUUID(), counts, true, "timeout", deathSeqAtTick);
                RddPlugin.saveLedger(ap.getUUID());
            }
            // 死亡支线收尾（深审 R08）：背包里重新出现了死亡时丢的资产 = "捡回来了"的真实证据。
            // 这是 onRepairSuccess 唯一可信的触发点（比"AI 说自己捡了"可靠）——
            // 之前它零生产调用，支线派出去就没人管，连续失败只增不减。
            if (RddRepairDispatch.lastRepair(ap.getUUID()) != null
                    && deathDropsBackInInventory(ap, counts)) {
                // 81 号 P0-A：回收成功 = 净损失此刻可算（counts 是真的）→ 重算影响，
                // 再结账。顺序不能反：onRepairSuccess 会清 OPEN_DEATH_SEQ，重算就找不到批次。
                RddRepairDispatch.recomputeAfterDeath(ap.getUUID(), counts, false, "recovered", deathSeqAtTick);
                RddRepairDispatch.onRepairSuccess(ap.getUUID());
            }
            // 背包快满主动提醒（用户点名）：放在这里是因为它在**任何链状态**（停车/失败/未展开）
            // 都该生效——满了就是满了，跟目标推到第几级无关。see BackpackSpace。
            maybeWarnBackpackFull(ap);

            surplus.inspect(ap, chain, counts);
            // (039 U3 hook 见下)
            RddPlugin.cacheInventory(ap.getUUID(), counts);
            // P2.1：观测到即记资产历史 CURRENT（只记装备/工具/食物等有恢复价值者），供 Lost≠Gone 线索。
            RddPlugin.recordHistoryCurrent(ap.getUUID(), counts);
            // Observe even parked/failed/unexpanded chains. A control-state early
            // return must not make the monitoring station appear frozen.
            if (assetTick == 0) populateAssets(ap, rt, chain.currentSubtask(), counts);
            // 2026-10-28（主人第六点）：30 秒一拍扫"后面几级要用的东西现在手上/身边有没有"。
            // 只读、只发事件与上下文块，不改链、不判提前完成（那需要主人定的口径）。
            if (assetTick == 0) RddFutureNeeds.tick(ap.getUUID(), chain, counts);
            // 2026-10-28（主人第六点后半）：30 秒一拍盯"从无到有的装备/重要物" —— 只发事实 + 拍醒，
            // 收不收口由规划者决定（rdd_settle_stage / rdd_replan_chain）。
            if (assetTick == 0) RddCapabilityWatch.tick(ap.getUUID(), counts);
            // 039 U3：用统一观测做一次"装备要件齐没齐"的三态判定，只发事实事件，不改链、不判提前完成。
            // 关键：NOT_VERIFIABLE **不算满足**——读不到/过期不许被当成"有了"。
            if (assetTick == 0) evaluateGearCondition(ap, ap.level().getGameTime());
            if (worldAssetTick == 0) {
                // 第三批 N1 消费：共同事实快照 + 需求检测（30 秒一拍；只产事实与事件，不做裁决）。
                // 用本拍真实背包 counts（不是注册表缓存）—— 规划侧文档定调"实时扫描是持有唯一真相"。
                RddFactContext.tick(ap.getUUID(), ap.level().dimension().location().toString(), rt, counts);
            }
            // 2026-11-13 V05/V06：原观测后消费者，保留本拍捕获的 ps 和同一计时表。
            Map<String, Integer> countsAtAdmission = counts; // 下游会重新赋值 counts；宿主捕获此处原快照。
            if (RddMainlineAdmission.stopAfterObservation(ap.getUUID(), chain, ps,
                    replanEnteredAt, System::currentTimeMillis, new RddMainlineAdmission.Host() {
                        @Override public boolean sideActive() {
                            return SideTaskHost.blocksMainline(ap.getUUID());
                        }
                        @Override public void activeProposal() {
                            RddEarlyProposalPublisher.publish(ap, chain, countsAtAdmission);
                        }
                        @Override public boolean negotiate() {
                            return tickNegotiation(ap, rt, chain);
                        }
                        @Override public void watchParked() {
                            tryAcceptSatisfiedWhileParked(ap, rt, chain, ps);
                            try {
                                Subtask parked = chain.currentSubtask();
                                if (parked != null) parkedWatcher.watch(ap, rt, parked);
                            } catch (RuntimeException ignored) { /* fail-soft: 监督异常不得拖垮 tick */ }
                        }
                        @Override public void recovering() {
                            RddPlugin.publishTaskSnapshot(ap.getUUID(), "chain_recovering");
                            RddMonitor.publish("chain_recovering", Map.of(
                                    "companionId", ap.getUUID().toString(),
                                    "primary", chain.currentPrimary().id(),
                                    "reason", "in-flight chain restored after restart; resuming under live observation"));
                            rt.resumeFromRecovering();
                        }
                        @Override public void timeoutResumed(long waitedMs) {
                            RddPlugin.clearBody(ap.getUUID());
                            RddMonitor.publish("replanning_timed_out", Map.of(
                                    "companionId", ap.getUUID().toString(),
                                    "waited_ms", waitedMs,
                                    "action", "resumeFromReplanning (回退重跑旧计划)"));
                            RddPlugin.publishTaskSnapshot(ap.getUUID(), "replanning_timeout_resumed");
                        }
                        @Override public void timeoutFailed(long waitedMs, RuntimeException failure) {
                            RddMonitor.publish("replanning_timed_out", Map.of(
                                    "companionId", ap.getUUID().toString(),
                                    "waited_ms", waitedMs,
                                    "action", "resume failed: " + failure));
                        }
                    })) {
                return;
            }
            // Recheck recovery before wait_for; releasing an unstarted step returns to admission next tick.
            if (chain.currentSubtask() != null && chain.isPaused(chain.currentSubtask().id())
                    && chain.pauseReason(chain.currentSubtask().id()) != null
                    && chain.pauseReason(chain.currentSubtask().id()).startsWith("recovery_not_verified:")) {
                handlePaused(ap, rt, chain, counts);
                return;
            }
            // 刚推进到新的当前一级(PENDING/WAITING)：先过依赖门(wait_for)，没过就保持 WAITING 不派给 AI。
            if (ps == PrimaryGoalStatus.PENDING || ps == PrimaryGoalStatus.WAITING) {
                // 观察行(懒边界)：当前一级到达但未展开 -> 上报目标驱动器(展开权持有者)，Detector 绝不自己展开/调 LLM。
                if (chain.currentPrimary().unexpanded()) {
                    RddGoalDriver.needExpansion(ap.getUUID());
                    RddPlugin.reportExpansionNeeded(ap.getUUID(),
                            chain.currentPrimary().id(), chain.currentPrimary().description());
                    return;
                }
                if (!riskGateAllows(ap, chain.currentPrimary().description())) {
                    publishWaitingOnce(ap, "riskgate|" + chain.currentPrimary().id(), "primary_waiting", Map.of(
                            "primary", chain.currentPrimary().id(),
                            "reason", "risk gate: high-risk stage not prepared (equipment/potion/recovery point)"));
                    // 2026-10-31：这里原先每 tick 都发一次快照（实机 rdd.jsonl 962 行里
                    // taskchain_snapshot 占 704 行），把真正有用的证据淹掉了。
                    // 同一 primary 的结果按 10 秒去重；换阶段立刻发一次。
                    if (shouldPublishGatedSnapshot(ap.getUUID(), chain.currentPrimary().id())) {
                        RddPlugin.publishTaskSnapshot(ap.getUUID(), "primary_risk_gated");
                    }
                    watchParkedIfStuck(ap, rt, chain); // 监督扩面：缺料/风险门长时间没过也催工
                    return;
                }
                if (!rt.activateCurrentFromSnapshot(RddPlugin.planningSnapshot(ap.getUUID()))) {
                    publishWaitingOnce(ap, "dependency|" + chain.currentPrimary().id(), "primary_waiting", Map.of(
                            "primary", chain.currentPrimary().id(),
                            "reason", "dependency assets not present"));
                    // 与风险门同样的理由：快照每 tick 发会把证据淹掉；按 10 秒节流，换阶段立刻发。
                    if (shouldPublishGatedSnapshot(ap.getUUID(), chain.currentPrimary().id(), "dependency")) {
                        RddPlugin.publishTaskSnapshot(ap.getUUID(), "primary_dependency_waiting");
                    }
                    watchParkedIfStuck(ap, rt, chain); // 监督扩面：依赖门长时间没过也催工
                    return;
                }
                captureStatBaselineAtStart(ap, rt);
                RddMonitor.publish("dependency_met", Map.of("primary", chain.currentPrimary().id()));
            }
            if (chain.primaryStatus() != PrimaryGoalStatus.ACTIVE) {
                return;
            }
            if (RddPlugin.consumeDeferredDeathReplan(ap.getUUID(), chain, () -> parkRejectedDeathReplan(ap, rt))) {
                return;
            }
            // 自动启动当前二级（首个由 sink 启动，推进后由这里继续；AI_ASSISTED 也在内）。
            if (chain.currentSubtaskStatus() == SubtaskStatus.PENDING) {
                rt.startCurrent();
                captureStatBaselineAtStart(ap, rt);
            }
            SubtaskStatus status = chain.currentSubtaskStatus();
            // PAUSED = 士兵说"这个现在做不了，先放着"（2026-09-30 两次实战触发）。
            //
            // 这里必须有处理，否则是**单向门**：`resumeFromPaused` 全仓只有单测在调，
            // 生产零调用 → 暂停一次就永久卡死。实机 00:31 与 00:49 两次都卡在这里，
            // 而规划器其实已经改好单了（grass_block >= 1 → dirt >= 1），链却不动。
            //
            // 语义不是"永远停"，而是"停到有理由再开"：
            //   ① 条件已经满足（规划器改过单、目标其实可行了）→ 立刻开回来，这是通融主路径；
            //   ② 条件仍不满足 → 到期强制复评（{@link #PAUSE_RECHECK_TICKS}），
            //      提示她「要么找到办法，要么把这个目标移出去」，不让她无限期停在做不了的事上。
            if (status == SubtaskStatus.PAUSED) {
                handlePaused(ap, rt, chain, counts);
                return;
            }
            // STALLED → 监督恢复分支：行为恢复则回到 RUNNING，多次拍醒无效则升级失败。
            if (status == SubtaskStatus.STALLED) {
                // #468（2026-11-03）：卡死判失败前先求一次真实条件；人已经站到基地/东西已够时，
                // stalled 状态不能把完成品判死。先 STALLED→RUNNING 再走正常完成路径。
                Subtask stalled = chain.currentSubtask();
                if (stalled != null && stalled.detectionMode() == DetectionMode.HARD_CODED
                        && conditionMatches(ap, stalled, counts)) {
                    try {
                        chain.resumeFromStalled(stalled.id());
                        boolean done = completeSubtask(ap, rt, stalled);
                        if (done) {
                            RddMonitor.publish("subtask_completed_after_stall", Map.of(
                                    "subtask", stalled.id(), "condition", stalled.condition(),
                                    "reason", "condition matched while STALLED; completed instead of failed"));
                            return;
                        }
                    } catch (RuntimeException ex) {
                        RddMonitor.publish("stalled_condition_complete_failed", Map.of(
                                "subtask", stalled.id(), "error", String.valueOf(ex)));
                    }
                }
                // catch/未完成：必须确认“还是原来那个 STALLED 任务”再交给 watcher，避免把已推进的链再判失败。
                Subtask still = chain.currentSubtask();
                if (still != null && still.id().equals(stalled == null ? null : stalled.id())
                        && chain.currentSubtaskStatus() == SubtaskStatus.STALLED) {
                    stallWatcher.handleStalled(ap, rt, still);
                }
                return;
            }
            // FAILED → 先重跑真实资产检测：条件此刻已满足(如 AI 失败后自己攒够) → 直接验收推进
            //（含暂停期，对齐增2"只停主动干预、真实资产检测+推进照常"；FAILED 只表"那次尝试失败"非"目标未达成"）。
            // applyHardCodedResult 要求 RUNNING → 先 FAILED→PENDING(retry)→RUNNING(start) 再走正常完成路径。
            // 仍未满足 → Level 2 局部恢复：预算内自动重跑该二级（AI 换策略再试）。
            if (status == SubtaskStatus.FAILED) {
                Subtask cur = chain.currentSubtask();
                if (cur.detectionMode() == DetectionMode.HARD_CODED
                        && conditionMatches(ap, cur, counts)) {
                    RddMonitor.publish("early_achievement", Map.of(
                            "subtask", cur.id(), "description", cur.description(),
                            "reason", "FAILED but assets now satisfied, completed on real inventory"));
                    rt.chain().retrySubtask(cur.id()); // FAILED→PENDING（仅 FAILED 可 retry）
                    rt.startCurrent();                 // PENDING→RUNNING
                    completeSubtask(ap, rt, cur);      // applyHardCoded(RUNNING)→COMPLETED+推进
                    return;
                }
                handleSubtaskFailure(ap, rt, cur);
                return;
            }
            if (status != SubtaskStatus.RUNNING) {
                return;
            }
            // 2026-11-04（037/T3 第二轮）：成就条件先按四态分诊——INVALID 去修条件、NOT_VERIFIABLE 只诊断，
            // 两者都**不是**"动作失败"。刻意只做旁路（发事件/请求重规划），不改变本 tick 的控制流；
            // 完成判定仍由下面的 conditionMatches（只有 MATCHED 为真）决定。
            applyAdvancementPolicy(ap, rt, chain.currentSubtask());
            // ==== 资产提前触发(EarlyAchievement)：资产已满足的硬编码二级不拍醒/不派身体，直接过，
            // 同 tick 连跳一串已满足的二级；到一级边界自动过依赖门进入下一级。 ====
            int guard = 0;
            while (chain.primaryStatus() == PrimaryGoalStatus.ACTIVE && guard++ < MAX_INSTANT_PASS) {
                if (chain.currentSubtaskStatus() == SubtaskStatus.PENDING) {
                    if (chain.currentSubtask().detectionMode() != DetectionMode.HARD_CODED) {
                        break;
                    }
                    rt.startCurrent();
                    captureStatBaselineAtStart(ap, rt);
                }
                if (chain.currentSubtaskStatus() != SubtaskStatus.RUNNING) {
                    break;
                }
                Subtask cur = chain.currentSubtask();
                if (cur.detectionMode() != DetectionMode.HARD_CODED) {
                    break;
                }
                if (!conditionMatches(ap, cur, counts)) {
                    break;
                }
                RddMonitor.publish("early_achievement", Map.of(
                        "subtask", cur.id(), "description", cur.description(),
                        "reason", "assets already present, skipped AI execution"));
                completeSubtask(ap, rt, cur);
                if (chain.currentSubtask() == cur && chain.primaryStatus() == PrimaryGoalStatus.ACTIVE) return;
                if (chain.primaryStatus() == PrimaryGoalStatus.PENDING) {
                    // 一级全完成被 CONFIRM → 进入下一级：下一级可能未展开(懒边界)→ 只上报驱动器，绝不 activate(会抛)
                    if (chain.currentPrimary().unexpanded()) {
                        RddGoalDriver.needExpansion(ap.getUUID());
                        RddPlugin.reportExpansionNeeded(ap.getUUID(),
                                chain.currentPrimary().id(), chain.currentPrimary().description());
                        break;
                    }
                    if (rt.activateCurrentFromSnapshot(RddPlugin.planningSnapshot(ap.getUUID()))) {
                        captureStatBaselineAtStart(ap, rt);
                        RddMonitor.publish("dependency_met", Map.of("primary", chain.currentPrimary().id()));
                    } else {
                        RddMonitor.publish("primary_waiting", Map.of(
                                "primary", chain.currentPrimary().id(),
                                "reason", "dependency assets not present"));
                        if (shouldPublishGatedSnapshot(ap.getUUID(), chain.currentPrimary().id(), "dependency")) {
                            RddPlugin.publishTaskSnapshot(ap.getUUID(), "primary_dependency_waiting");
                        }
                        break;
                    }
                }
                counts = countInventory(ap);
            }
            if (chain.primaryStatus() != PrimaryGoalStatus.ACTIVE) {
                return;
            }
            // 开工拍醒：一级的可执行二级已经起来了 → 推一句让内置 AI 动手（每个一级一次，失败下 tick 重试）。
            // 放在 EarlyAchievement 连跳之后，拍醒的一定是真正在执行的那个二级。
            RddPlugin.kickoffNudge(ap.getUUID(), chain, RddPlugin.captureObservationToken(ap.getServer()));
            Subtask current = chain.currentSubtask();
            // AI_ASSISTED 二级交还 AI 驱动，不做卡死监督；真实生产进展仍清除旧重复证据。
            if (current.detectionMode() != DetectionMode.HARD_CODED) {
                stallWatcher.observeProgress(ap, rt, current);
                return;
            }
            if (chain.currentSubtaskStatus() != SubtaskStatus.RUNNING) {
                return;
            }
            // 资产 populate：把背包物品写进 AssetRegistry（节流），rdd_status 据此报真实资产。
            // ★ 2026-10-28 实机 bug（本会话抓到）：**验收必须排在卡死监督之前**。
            //   她进了下界、站在传送门边不动 → 位置/背包指纹不变 → stallWatcher.track 返回 true
            //   → 这里提前 return，下面那句 conditionMatches **永远轮不到**；
            //   实测 `block_nearby(nether_portal)` 明明成立（rdd_status 也报 condition_matches=true），
            //   二级却一直是 RUNNING 卡了 14 分钟。顺序改成：先判"做到了没"，再判"是不是卡住了"。
            if (conditionMatches(ap, current, counts)) {
                completeSubtask(ap, rt, current);
                return;
            }
            // 卡死监督：资产指纹（背包+位置）连续未变化 → 判 STALLED 并拍醒将军。
            // 空转止血：暂停监督 → 跳过卡死检测与身体驱动(不 nudge / 不自动重派)，只留资产检测推进。
            if (RddPlugin.supervisionEnabled() && stallWatcher.track(ap, rt, current)) {
                return;
            }
            // assist 协助模式下暂停自动工具提交(防双驾驶):工具执行交还 NUMEN 内置 AI,
            // RDD 只保留资产检测 / 目标完成判定 / 异常提醒。
            if (RddPlugin.supervisionEnabled() && RddPlugin.bodySubmissionEnabled()) {
                RddBodyDispatcher.maybeSubmit(ap, current, chain);
            }
            if (conditionMatches(ap, current, counts)) {
                completeSubtask(ap, rt, current);
                return;
            }
            maybeRetryOrFail(ap, rt, current);
        } catch (RuntimeException e) {
            // 检测失败不能拖垮服务端 tick。
            LOG.warn("[rdd] 检测 tick 异常: {}", e.toString());
        } finally {
            if (assetTick == 0) RddPlugin.publishTaskSnapshot(ap.getUUID(), "periodic_observation");
        }
    }

    /** 运行时开关风险门（默认开）。 */
    public static void setRiskGateEnabled(boolean enabled) {
        RddRiskAdmission.setRiskGateEnabled(enabled);
    }

    /** 风险门当前是否生效（诊断用）。 */
    public static boolean riskGateActive() {
        return RddRiskAdmission.riskGateActive();
    }

    /** 2026-10-31：primary_risk_gated 快照的节流状态（uuid|primaryId → 上次发送毫秒）。 */
    private static final Map<String, Long> LAST_GATED_SNAPSHOT_AT =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long GATED_SNAPSHOT_MIN_INTERVAL_MS = 10_000L;

    /** 同一 primary 的"被门拦住"快照按 10 秒节流；换阶段立即放行一次。 */
    static boolean shouldPublishGatedSnapshot(java.util.UUID uuid, String primaryId) {
        return shouldPublishGatedSnapshot(uuid, primaryId, "riskgate");
    }

    /**
     * 同上，但可按"哪一层门"分开去重 —— 风险门与依赖门是两条不同的原因，
     * 共用一把键会把其中一个的首次记录吞掉。
     */
    static boolean shouldPublishGatedSnapshot(java.util.UUID uuid, String primaryId, String layer) {
        String key = String.valueOf(uuid) + "|" + String.valueOf(layer) + "|" + String.valueOf(primaryId);
        long now = System.currentTimeMillis();
        Long last = LAST_GATED_SNAPSHOT_AT.get(key);
        if (last != null && now - last < GATED_SNAPSHOT_MIN_INTERVAL_MS) {
            return false;
        }
        LAST_GATED_SNAPSHOT_AT.put(key, now);
        return true;
    }

    static boolean riskGateAllows(NumenPlayer ap, String primaryDescription) {
        return RddRiskAdmission.riskGateAllows(ap, primaryDescription);
    }

    /** 同伴是否已绑有效重生点（床/重生锚点）：respawn 非空且非哨兵 ZERO。 */
    static boolean hasBoundRespawn(NumenPlayer ap) {
        try {
            var pos = ap.getRespawnPosition();
            return pos != null && !pos.equals(net.minecraft.core.BlockPos.ZERO);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * 指挥官处理士兵回执（双向协商）：取出待处理 REJECT/COUNTER →
     * 带「原命令 + 士兵理由 + 士兵建议」触发重规划（协商改单）；预算耗尽则停车守望。
     *
     * @return true = 本 tick 已处理（调用方应 return，不再照旧推进）
     */
    /** Planner-visible read-only notice; mismatched/expired identity yields UNKNOWN and no old ids. */
    static String earlyProposalNotice(java.util.UUID id, String currentWorld, String currentSession,
                                       String currentChainId, long currentRevision, long currentStateVersion) {
        return RddEarlyProposalPublisher.notice(id, currentWorld, currentSession, currentChainId, currentRevision, currentStateVersion);
    }

    private boolean tickNegotiation(NumenPlayer ap, RddRuntime rt, TaskChain chain) {
        RddNegotiationInbox.Entry entry = RddNegotiationInbox.peek(ap.getUUID());
        com.dwinovo.numen.rdd.fail.TaskNegotiation n = entry == null ? null : entry.message();
        if (n == null || (!n.needsSupervisorAction() && !n.isPauseRequest())) {
            return false;
        }
        if (chain.primaryStatus() != PrimaryGoalStatus.ACTIVE
                || chain.currentSubtaskStatus() != SubtaskStatus.RUNNING
                && chain.currentSubtaskStatus() != SubtaskStatus.STALLED
                // 2026-09-30 codex 审稿 P2-6：士兵暂停后若找到了替代办法（COUNTER/REJECT），
                // 旧守卫只放行 RUNNING/STALLED → 那条回执会一直挂到自动恢复之后。
                // 「暂停着但想到了别的做法」正是最该被听到的时刻。
                && chain.currentSubtaskStatus() != SubtaskStatus.PAUSED) {
            return false;   // 只在当前二级活跃/卡住/暂停时处理协商
        }
        // Old execution reports remain reviewable but may not stop or replan a new attempt.
        if (!RddNegotiationInbox.applicable(entry, chain)) {
            RddNegotiationInbox.deferStale(ap.getUUID(), entry, chain);
            return false;
        }
        // 2026-09-30 codex 复审 P1-3：**主人暂停是命令，不是建议。**
        // 士兵在主人暂停期间上报的 COUNTER/REJECT 不能把它推翻成"重规划 → 继续跑"，
        // 否则"原地待命"这个明确指令会被执行层的建议悄悄作废。
        // 处理：明确丢弃并留事件，让主人知道"士兵有话想说，但要你先解除暂停"。
        if (chain.currentSubtaskStatus() == SubtaskStatus.PAUSED && isOwnerPause(chain, chain.currentSubtask())) {
            RddNegotiationInbox.takePending(ap.getUUID(), entry);
            RddMonitor.publish("negotiation_deferred_to_owner", Map.of(
                    "companionId", ap.getUUID().toString(),
                    "kind", n.kind().name(), "reason", n.reason(),
                    "suggestion", n.suggestion(),
                    "note", "owner paused this step; only the owner may resume it"));
            return true;   // 已处理（= 拦住），本 tick 不照旧推进
        }
        // 深审 R05：消费前**二次核对 taskId**。
        // 入队时（工具侧）只做可选校验，换目标/重规划后那次回执会滞留；
        // 而二级 id 在重规划里反复复用 `primaryId-r0/r1`（RddPlugin:496），
        // 于是上一轮的 PAUSE 可能作用到新一轮的同 id 二级上。
        // 消费侧再核一次，是最后一道闸：不匹配就丢弃并留事件，不让它改单。
        Subtask currentNow = chain.currentSubtask();
        if (!n.taskId().isBlank() && (currentNow == null || !currentNow.id().equals(n.taskId()))) {
            RddNegotiationInbox.takePending(ap.getUUID(), entry);   // 明确丢弃
            RddMonitor.publish("negotiation_discarded", Map.of(
                    "companionId", ap.getUUID().toString(),
                    "reason", "stale subtask id; the plan moved on",
                    "reportedTaskId", n.taskId(),
                    "currentTaskId", currentNow == null ? "?" : currentNow.id(),
                    "kind", n.kind().name()));
            return false;
        }
        Subtask current = chain.currentSubtask();
        if (!RddNegotiationInbox.takePending(ap.getUUID(), entry)) return false; // never consume a newer entry

        // 「先放着」：不重规划、不换计划，直接把当前二级按住。留在这里等条件变了再开。
        //
        // ★ 开关（2026-09-30 用户要求：怕误伤实验，要能随时关）：
        //   建 config/numen/rdd-pause-disabled.flag 即禁用 PAUSE；
        //   关掉后 PAUSE 回执**落到下面的普通重规划路径**（= 当作 COUNTER 处理），
        //   至少不会把链按住不动。删掉 flag 即恢复。运行时生效，不用重编。
        boolean pauseOff = !RddPlugin.pauseEnabled();
        if (n.isPauseRequest() && pauseOff) {
            RddMonitor.publish("pause_skipped_disabled", Map.of(
                    "companionId", ap.getUUID().toString(),
                    "reason", n.reason(), "suggestion", n.suggestion(),
                    "note", "rdd-pause-disabled.flag exists; PAUSE handled as a plain replan request"));
        }
        if (n.isPauseRequest() && !pauseOff) {
                RddPlugin.clearBody(ap.getUUID());
                // 已经在暂停中（士兵又报了一次 PAUSE）：不是错误，别再走一遍按停逻辑，
                // 更别把它当失败。刷新一下起点，让复评预算从这次上报重新算。
                if (chain.currentSubtaskStatus() == SubtaskStatus.PAUSED) {
                    rt.clearPaused(current.id());
                    rt.markPaused(current.id(), ap.level().getGameTime());
                    return true;
                }
                try {
                    chain.pauseSubtask(current.id(), "soldier PAUSE: " + n.reason()
                            + (n.hasSuggestion() ? " | suggestion: " + n.suggestion() : ""));
                    // 记下暂停起点：没有它，handlePaused 拿不到"已经停多久"，
                    // 复评闸就永远不触发（这正是实机两次卡死的直接原因）。
                    // 时钟必须与读取侧同源：都用 level().getGameTime()（20 tick/s）。
                    // 2026-09-30 深审 R03：我第一版只改了读取侧，漏了这里，还写着
                    // nanoTime/20_000_000（50/s）—— 起点大于游戏时间 → Math.max(0,…) 恒为 0，
                    // 60 秒复评闸照样不触发。codex 审稿抓出，两个时钟必须一起改。
                    rt.markPaused(current.id(), ap.level().getGameTime());
                    RddMonitor.publish("subtask_paused", Map.of(
                        "companionId", ap.getUUID().toString(), "subtask", current.id(),
                        "kind", n.kind().name(), "reason", n.reason(),
                        "suggestion", n.suggestion(),
                        "note", "kept for later; retry budget untouched"));
                return true;
            } catch (RuntimeException pauseFailed) {
                // 按不住不能吞掉：退回重规划路径，让军师换计划（fail-soft，但不留孤儿上报）
                RddMonitor.publish("subtask_pause_rejected", Map.of(
                        "companionId", ap.getUUID().toString(), "subtask", current.id(),
                        "reason", pauseFailed.toString(),
                        "fallback", "replan"));
            }
        }
        String hint = RddNegotiationInbox.negotiationHint(n, current);
        RddMonitor.publish("negotiation_handled", Map.of(
                "companionId", ap.getUUID().toString(),
                "subtask", current.id(), "kind", n.kind().name(),
                "hasSuggestion", n.hasSuggestion(), "reason", n.reason()));
        // 协商改单：优先用士兵建议重规划当前一级（用**独立协商预算**，不被失败预算回绝）。
        // 2026-09-28：把 negotiationHint（士兵的 COUNTER 具体选项）**真正送进规划器输入** ——
        // 旧实现算出来只用于打日志，士兵的建议被整段丢弃，实机表现为"说了好几次指挥官仍照原计划"。
        if (RddPlugin.requestReplan(ap.getUUID(), "soldier " + n.kind().name() + ": " + n.reason(), true, hint)) {
            return true;
        }
        // 重规划预算耗尽 → 停车守望（不无限协商）。
        // 2026-09-30 codex 复审 P1-3：原来直接 markFailed，但那只接受 RUNNING/STALLED ——
        // 在 PAUSED 状态（士兵暂停后又报 COUNTER 的路径）会抛 IllegalStateException，
        // 异常向上冒到 tickRuntime 的 catch，把整条支线变成"检测异常"并静默停摆。
        // 正确做法：PAUSED 就让它**继续暂停**（它本来就不在跑），只发事件。
        if (rt.chain().currentSubtaskStatus() == SubtaskStatus.PAUSED) {
            RddPlugin.clearBody(ap.getUUID());
            RddMonitor.publish("negotiation_parked", Map.of(
                    "companionId", ap.getUUID().toString(), "subtask", current.id(),
                    "kind", n.kind().name(),
                    "reason", "replan budget exhausted; step stays PAUSED (it is not running anyway)",
                    "suggestion", n.suggestion()));
            return true;
        }
        rt.chain().markFailed(current.id(), "negotiation could not replan: " + n.reason());
        RddPlugin.clearBody(ap.getUUID());
        parkedWatcher.watch(ap, rt, current);
        RddMonitor.publish("negotiation_parked", Map.of(
                "companionId", ap.getUUID().toString(), "subtask", current.id(),
                "reason", "replan budget exhausted after soldier " + n.kind().name()));
        return true;
    }

    /**
     * 停车态「条件已达成」裁决（2026-09-28）：REPLANNING 罚站但硬编码条件已满足时，
     * 报出事件；是否真的退回正常流程由开关 {@code rdd.parkedEarlyAccept} 决定。
     *
     * <p><b>为什么默认只报不动手</b>：实机出现口径冲突 —— 链的 condition 没有 {@code type} 字段，
     * 于是 {@link #conditionMatches} 按**背包计数**判（小麦 19>=12，cond=true），
     * 而执行 AI 理解成"地里站着的小麦"（world facts）在种田。两者不一致时自动退回
     * 会顺着链的口径**误判完成**（假阳性比继续罚站更坏：会伪造"任务达成"）。
     * 口径归谁说了算要人拍板，所以这里先只发事件，开关默认关。
     *
     * <p>修的是"能力够不着"而不是"能力缺失"：验收逻辑本来就有，但挂在 ACTIVE 之后，
     * 停车态提前 return 就永远走不到。
     */
    private void tryAcceptSatisfiedWhileParked(NumenPlayer ap, RddRuntime rt, TaskChain chain,
                                                PrimaryGoalStatus ps) {
        try {
            Subtask cur = chain.currentSubtask();
            if (cur == null) {
                return;
            }
            String primaryId = chain.currentPrimary() != null ? chain.currentPrimary().id() : null;
            boolean hardCoded = cur.detectionMode() == DetectionMode.HARD_CODED;
            boolean met = hardCoded && conditionMatches(ap, cur, countInventory(ap));
            boolean resumedBefore = primaryId != null && primaryId.equals(earlyAcceptResumed.get(ap.getUUID()));
            if (!RddParkedAcceptPolicy.shouldResume(ps == PrimaryGoalStatus.REPLANNING, true,
                    hardCoded, met, resumedBefore)) {
                return;
            }
            boolean enabled = Boolean.parseBoolean(System.getProperty("rdd.parkedEarlyAccept", "false"));
            RddMonitor.publish("parked_early_achievement", Map.of(
                    "companionId", ap.getUUID().toString(),
                    "primary", primaryId,
                    "subtask", cur.id(),
                    "description", cur.description(),
                    "action", enabled ? "resume" : "report-only",
                    "reason", "parked in " + ps + " but hard-coded condition already satisfied;"
                            + (enabled ? " resuming normal flow" : " parkedEarlyAccept is off, waiting for owner decision")));
            if (!enabled) {
                return;
            }
            earlyAcceptResumed.put(ap.getUUID(), primaryId);
            chain.resumeFromReplanning();
        } catch (RuntimeException e) { // fail-soft：裁决失败也只是继续罚站，不拖垮 tick
            RddMonitor.publish("parked_early_achievement_error", Map.of(
                    "companionId", ap.getUUID().toString(),
                    "reason", String.valueOf(e)));
        }
    }

    /**
     * 监督扩面（2026-09-27）：非 ACTIVE 的停滞态（WAITING 待料 / REPLANNING / FAILED）也走停泊守望，
     * 长时间无进展就催 AI（复用 RddParkedWatcher），不再静默停。fail-soft，不改变状态机。
     */
    /** 每次进入 REPLANNING 的时刻（uuid → ms）；回到 ACTIVE 后清掉。 */
    private final java.util.Map<UUID, Long> replanEnteredAt = new java.util.concurrent.ConcurrentHashMap<>();


    private void watchParkedIfStuck(NumenPlayer ap, RddRuntime rt, TaskChain chain) {
        try {
            Subtask cur = chain.currentSubtask();
            if (cur != null) parkedWatcher.watch(ap, rt, cur);
        } catch (RuntimeException ignored) { /* fail-soft */ }
    }

    /** Activation has already started the chain-local attempt, never the body/kickoff. Reuse FAILED parking. */
    private void parkRejectedDeathReplan(NumenPlayer ap, RddRuntime rt) {
        failureRecovery.parkRejectedDeathReplan(ap, rt);
    }

    /** FAILED 二级的 Level 2 局部恢复：预算内重置重跑 + 拍醒提示换策略；预算耗尽 → Level 3。 */
    private void handleSubtaskFailure(NumenPlayer ap, RddRuntime rt, Subtask current) {
        failureRecovery.handleSubtaskFailure(ap, rt, current);
    }


    /** 把背包物品写进 AssetRegistry（GLOBAL 作用域，来源=当前二级）。观测证据：inventory_scan。 */
    private void populateAssets(NumenPlayer ap, RddRuntime rt, Subtask current, Map<String, Integer> counts) {
        try {
            String envId = ap.level().dimension().location().toString();
            Map<String, Integer> observed = new HashMap<>(counts);
            // A consumed item is an observed zero, not a permanently held asset.
            for (var entry : rt.assets().snapshot()) {
                if ("inventory_scan".equals(entry.observation().type())) observed.putIfAbsent(entry.assetId(), 0);
            }
            for (Map.Entry<String, Integer> e : observed.entrySet()) {
                Map<String, Object> value = new HashMap<>();
                value.put("count", e.getValue());
                Observation obs = new Observation(
                        "obs-" + e.getKey().hashCode() + "-" + System.nanoTime(),
                        "inventory_scan", "rdd_detector", envId,
                        System.currentTimeMillis(), value);
                rt.assets().apply(obs, e.getKey(), AssetScope.GLOBAL, current == null ? null : current.id());
            }
        } catch (RuntimeException ex) {
            // 资产 populate 失败不影响检测主流程
            LOG.warn("[rdd] 资产 populate 异常: {}", ex.toString());
        }
    }
    /** 世界真身满足条件 → 推进；二级全完成 → 简化 Supervisor CONFIRM。 */
    private boolean completeSubtask(NumenPlayer ap, RddRuntime rt, Subtask current) {
        if (surplus.hold(ap, rt.chain(), current, countInventory(ap))) return false;
        boolean completed = rt.applyHardCoded(current.id(), true);
        RddPlugin.clearBody(ap.getUUID());
        failureRecovery.clearRetries(ap.getUUID());
        stallWatcher.clear(ap.getUUID());
        failureRecovery.clearParked(ap.getUUID());
        parkedWatcher.clear(ap.getUUID());
        furnaceWatch.remove(ap.getUUID());
        if (!completed) {
            return false;
        }
        LOG.info("[rdd] 二级目标完成: {} ({})", current.id(), current.description());
        RddMonitor.publish("subtask_completed", Map.of(
                "subtask", current.id(), "description", current.description(), "condition", current.condition(),
                "companionId", ap.getUUID().toString(), "evidenceSource",
                com.dwinovo.numen.rdd.core.WorldFactConditions.knownType(current.condition().get("type"))
                        ? "server_world_fact" : "server_inventory"));
        RddPlugin.publishTaskSnapshot(ap.getUUID(), "subtask_completed");
        TaskChain chain = rt.chain();
        // P0 辅助留档：二级完成细节（不用于恢复，供后续 Context/Planner 参考）
        RddPlugin.recordSubtaskFact(ap.getUUID(), chain.goal(), chain.currentPrimary().description(), current.description());
        if (chain.primaryStatus() == PrimaryGoalStatus.AWAITING_SUPERVISOR) {
            PrimaryGoal completedPrimary = chain.currentPrimary();
            var completion = RddPlugin.confirmStageCompletion(ap.getUUID(), rt,
                    "all hard-coded conditions met in the real world");
            if (!completion.committed()) {
                LOG.warn("[rdd] primary completion pending {}: {}", ap.getUUID(), completion.error());
                return false;
            }
            LOG.info("[rdd] 一级目标完成: {}", completedPrimary.description());
            RddMonitor.publish("goal_completed", Map.of(
                    "goal", completedPrimary.id(), "description", completedPrimary.description(),
                    "companionId", ap.getUUID().toString()));
            RddPlugin.publishTaskSnapshot(ap.getUUID(), "primary_completed");
            RddPlugin.clearBody(ap.getUUID());
        }
        return true;
    }

    /** 给当前二级派过身体、身体任务已不在位、条件仍未满足 → 重试或判失败。 */
    private void maybeRetryOrFail(NumenPlayer ap, RddRuntime rt, Subtask current) {
        failureRecovery.maybeRetryOrFail(ap, rt, current);
    }

    /** 成就条件修复预算：每个二级一次（见 {@link com.dwinovo.numen.rdd.core.AdvancementRepairBudget}）。 */
    private static final com.dwinovo.numen.rdd.core.AdvancementRepairBudget ADVANCEMENT_REPAIR_BUDGET =
            new com.dwinovo.numen.rdd.core.AdvancementRepairBudget();

    /**
     * 成就条件的四态分诊（037/T3 第二轮 · EV-01）。
     *
     * <p>只做三件事，都**不改变本 tick 的控制流**：
     * <ul>
     *   <li>MATCHED / NOT_MATCHED：不插话，交给正常完成/等待路径；</li>
     *   <li>INVALID（条件可证明是错的）：预算内请求一次重规划（{@code RddPlugin.requestReplan}），
     *       预算用尽后只报一次 {@code advancement_repair_exhausted}，不无限悄悄重试，也不记动作失败；</li>
     *   <li>NOT_VERIFIABLE（观测不可用）：只发限频 {@code advancement_unverifiable} 诊断事件，
     *       不增加尝试次数、不卡死（下一次判定自然重试）。</li>
     * </ul>
     */
    private static void applyAdvancementPolicy(NumenPlayer ap, RddRuntime rt, Subtask cur) {
        if (ap == null || cur == null || cur.detectionMode() != DetectionMode.HARD_CODED) {
            return;
        }
        Map<String, Object> condition = cur.condition();
        if (condition == null || !"advancement".equals(condition.get("type"))) {
            return;
        }
        com.dwinovo.numen.rdd.core.AdvancementVerification.Result state;
        try {
            state = RddWorldFacts.advancementResult(ap, condition);
        } catch (RuntimeException ex) {
            state = com.dwinovo.numen.rdd.core.AdvancementVerification.Result.NOT_VERIFIABLE;
        }
        if (com.dwinovo.numen.rdd.core.AdvancementPolicy.shouldRequestRepair(state)) {
            var decision = ADVANCEMENT_REPAIR_BUDGET.take(cur.id());
            if (decision == com.dwinovo.numen.rdd.core.AdvancementRepairBudget.Decision.REQUEST) {
                try {
                    RddMonitor.publish("advancement_condition_invalid", Map.of(
                            "subtask", cur.id(),
                            "advancement", String.valueOf(condition.get("advancement")),
                            "reason", "advancement condition is provably invalid; requesting replan instead of retrying the action"));
                    RddPlugin.requestReplan(ap.getUUID(),
                            "advancement condition invalid: " + condition.get("advancement"));
                } catch (RuntimeException ignored) {
                    // 请求修复失败只影响能不能立刻改单，不改变完成判定
                }
            } else if (shouldPublishGatedSnapshot(ap.getUUID(), cur.id() + "|exhausted", "advancement_repair")) {
                try {
                    RddMonitor.publish("advancement_repair_exhausted", Map.of(
                            "subtask", cur.id(), "budget", ADVANCEMENT_REPAIR_BUDGET.maxAttempts(),
                            "reason", "repair budget used; reporting instead of silently retrying"));
                } catch (RuntimeException ignored) {
                    // 事件失败不影响判定
                }
            }
        } else if (com.dwinovo.numen.rdd.core.AdvancementPolicy.shouldDiagnoseOnly(state)) {
            if (shouldPublishGatedSnapshot(ap.getUUID(), cur.id() + "|unverifiable", "advancement_repair")) {
                try {
                    RddMonitor.publish("advancement_unverifiable", Map.of(
                            "subtask", cur.id(),
                            "advancement", String.valueOf(condition.get("advancement")),
                            "reason", "registry/progress unavailable; waiting for a fresh observation, not counted as a failed action"));
                } catch (RuntimeException ignored) {
                    // 事件失败不影响判定
                }
            }
        }
    }

    static boolean conditionMatches(NumenPlayer ap, Subtask task, Map<String, Integer> counts) {
        if (!ResourceUse.retainedSatisfied(
                ResourceUse.completionObligations(task.resourceUses(), task.condition()), counts)) return false;
        if (task.condition().containsKey("type") && !"inventory".equals(task.condition().get("type"))) {
            // 2026-11-03 实机：item_crafted bread>=1 用终身统计，历史面包把 r3-3 假完成。
            // 统计类条件必须按「进入本步后的增量」判，而不是终身累计。
            if (isStatCondition(task.condition())) {
                return statDeltaSatisfied(ap, task, counts);
            }
            // 2026-11-03 审计：type=item 是背包形状，WorldFactConditions.valid 不认它，
            // 原路径会让 HardCodedEvaluator 的 type:item 分支永远到不了 → 生产链假卡死。
            if ("item".equals(task.condition().get("type"))) {
                // fail-closed：type:item 只接受缺省/hold + 合法正门槛；acquire/畸形/≤0 一律不判，
                // 不把旧链/外部入口的未知语义当成“持有即完成”（2026-11-03 审计）。
                Object itemMode = task.condition().get("mode");
                boolean itemHold = itemMode == null
                        || com.dwinovo.numen.rdd.core.HardCodedEvaluator.MODE_HOLD.equals(String.valueOf(itemMode));
                int itemMin = com.dwinovo.numen.rdd.core.HardCodedEvaluator.minimumOf(task.condition());
                if (!itemHold || itemMin <= 0) return false;
                return com.dwinovo.numen.rdd.core.HardCodedEvaluator.matches(task.condition(), counts, null);
            }
            return com.dwinovo.numen.rdd.core.WorldFactConditions.valid(task.condition())
                    && RddWorldFacts.matches(ap, task.condition());
        }
        // F5：mode=acquire 的二级按"进入时的基线 + 当前增量"判（hold 走两参重载，行为逐字不变）。
        // 基线缺失 → evaluator 判 false → 不算达成（宁可漏判，不许假完成）。
        Map<String, Integer> baseline = acquireBaselineFor(ap, task);
        // 2026-10-29（#288 实机）：acquire 的"基线 + 增量"口径在**目标本来就已经满足**时会加倍要求 ——
        //   r14-0 要 iron_pickaxe>=1，她进这一级时背包里就有 1 把 ⇒ baseline=1，判定要求 1+1=2 把，
        //   于是 matches 永远是 false，链卡死。目标语义是"拥有"，不是"再赚"。先判"已有即达成"。
        if (HardCodedEvaluator.matches(task.condition(), counts, null)) {
            return true;
        }
        if (HardCodedEvaluator.matches(task.condition(), counts, baseline)) {
            return true;
        }
        // 战略派生等价不是真实背包持有：例如小麦尚未变成面包，不能用于完成判定。
        // 埋点：此声明（condition）与真实背包不符（系统以为有、真身没有）→ asset_mismatch（限流）
        detectAssetMismatch(ap, task, counts);
        return false;
    }

    private static boolean isStatCondition(Map<String, Object> condition) {
        Object type = condition == null ? null : condition.get("type");
        return "item_crafted".equals(type) || "item_used".equals(type)
                || "item_picked_up".equals(type) || "block_mined".equals(type);
    }

    /**
     * 2026-11-25: 真实主/支线 PENDING→RUNNING 入口在身体/工具执行前调用一次。
     * 只锁当前 RUNNING 的统计条件；查询/状态路径绝不调用。
     * UNKNOWN(-1) 不拍，缺基线仍判 false，不把失读折成 0。
     */
    static void captureStatBaselineAtStart(NumenPlayer ap, RddRuntime rt) {
        if (ap == null || rt == null) return;
        TaskChain chain = rt.chain();
        Subtask task = chain.currentSubtask();
        if (task == null || chain.currentSubtaskStatus() != SubtaskStatus.RUNNING) return;
        if (!isStatCondition(task.condition()) || !statDeltaApplies(rt, task)) return;
        int current = RddWorldFacts.statCount(ap, task.condition());
        if (current < 0) return;
        chain.captureStatBaseline(task.id(), current);
    }

    /** 统计条件是否走“进入本步后的增量”；显式 lifetime 优先，显式 delta 不被旧链开关覆盖。 */
    private static boolean statDeltaApplies(RddRuntime rt, Subtask task) {
        Map<String, Object> condition = task.condition();
        boolean explicitLifetime = com.dwinovo.numen.rdd.core.WorldFactConditions.lifetimeScope(condition);
        boolean explicitDelta = "delta".equals(condition.get("scope"));
        return !explicitLifetime && (explicitDelta || rt.chain().statDeltaEnabled());
    }

    /**
     * 统计类条件的增量判定。基线只由启动入口 captureStatBaselineAtStart 锁；
     * 本方法纯判定：status/查询调用不会初始化或改写基线。
     * 基线缺失（含 restore UNKNOWN 未捕获）→ false；不猜 0，不用终身统计兜底。
     */
    private static boolean statDeltaSatisfied(NumenPlayer ap, Subtask task, Map<String, Integer> counts) {
        RddRuntime rt = RddPlugin.runtime(ap.getUUID());
        if (rt == null) return false;
        if (!statDeltaApplies(rt, task)) {
            return com.dwinovo.numen.rdd.core.WorldFactConditions.valid(task.condition())
                    && RddWorldFacts.matches(ap, task.condition());
        }
        int current = RddWorldFacts.statCount(ap, task.condition());
        if (current < 0) return false;
        Map<String, Integer> baseline = rt.chain().statBaseline(task.id());
        if (baseline == null) return false;
        int base = baseline.getOrDefault("value", current);
        Object rawMin = task.condition().get("minimum");
        int minimum = rawMin instanceof Number n && n.intValue() > 0 ? n.intValue() : 1;
        if (current - base >= minimum) return true;
        if ("item_picked_up".equals(task.condition().get("type"))
                && task.condition().get("item") instanceof String item && !item.startsWith("#")
                && counts != null) {
            Map<String, Integer> inventoryBaseline = rt.chain().acquireBaseline(task.id());
            if (inventoryBaseline != null) {
                int gained = counts.getOrDefault(item, 0) - inventoryBaseline.getOrDefault(item, 0);
                if (gained >= minimum) return true;
            }
        }
        return false;
    }

    /**
     * 取该二级的 acquire 基线（F5）。
     *
     * <p>只有 {@code mode=acquire} 才需要基线；{@code hold} 直接返回 null，
     * 让 evaluator 走与改动前完全相同的路径。
     * 链不在内存（未建立/已清理）时也返回 null → 判未达成，不猜。
     */
    private static Map<String, Integer> acquireBaselineFor(NumenPlayer ap, Subtask task) {
        try {
            if (!com.dwinovo.numen.rdd.core.HardCodedEvaluator.MODE_ACQUIRE.equals(
                    com.dwinovo.numen.rdd.core.HardCodedEvaluator.modeOf(task.condition()))) {
                return null;
            }
            RddRuntime rt = RddPlugin.runtime(ap.getUUID());
            return rt == null ? null : rt.chain().acquireBaseline(task.id());
        } catch (RuntimeException ignored) {
            return null;   // 拿不到基线 = 判未达成
        }
    }

    /**
     * 埋点：资产声明与实际背包不符（挂 countInventory 与声明比对处）。
     * 定义：当前子步的 condition 声明 asset_key K（minimum M），注册表最近一次 inventory_scan
     * 仍宣称 K 为 OBSERVED（declared>0），但这一拍真身背包计数低于声明 → mismatch。
     * 只记录，不改判定；噪音型事件已由 RddInstrumentation 限流。
     */
    private static void detectAssetMismatch(NumenPlayer ap, Subtask task, Map<String, Integer> counts) {
        Object key = task.condition().get("asset_key");
        if (!(key instanceof String item) || item.isBlank()) return;
        int live = counts.getOrDefault(item, 0);
        int declared = declaredInventoryCount(ap, item);
        if (declared <= 0 || live >= declared) return;
        RddInstrumentation.publish(RddInstrumentation.ASSET_MISMATCH, Map.of(
                "companionId", ap.getUUID().toString(),
                "task", task.id(),
                "reason", "declared inventory asset does not match live backpack",
                "context", Map.of(
                        "asset", item,
                        "declared", declared,
                        "actual", live,
                        "minimum", task.condition().get("minimum"))));
    }

    /** 注册表里最近一次 inventory_scan 宣称的持有量（OBSERVED 才作数）。 */
    private static int declaredInventoryCount(NumenPlayer ap, String item) {
        try {
            for (var entry : RddPlugin.assets(ap.getUUID()).snapshot()) {
                if (entry.status() == com.dwinovo.numen.rdd.api.AssetStatus.OBSERVED
                        && "inventory_scan".equals(entry.observation().type())
                        && item.equals(entry.assetId())) {
                    Object c = entry.observation().value().get("count");
                    if (c instanceof Number n) return n.intValue();
                }
            }
        } catch (RuntimeException ignore) {
            // 资产表不可读 → 无声明，不误报
        }
        return 0;
    }

    /**
     * Freeze only the inventory cache during a death window. Missing evidence must not write an
     * empty corpse inventory over the last living cache. Returning true also prevents ordinary
     * container/runtime/side-task processing from treating the frozen death fact as live holdings.
     */
    private static boolean freezeDeathInventoryAndSkip(NumenPlayer ap) {
        if (!ap.deathPending()) return false;
        ap.deathInventorySnapshot().ifPresent(snapshot -> {
            if (snapshot.belongsTo(ap)) {
                RddPlugin.cacheInventory(ap.getUUID(), snapshot.inventory());
            }
        });
        return true;
    }

    /** Existing caller boundary; death consumers read the frozen pre-clear fact when present. */
    static Map<String, Integer> countInventory(NumenPlayer ap) {
        DeathInventorySnapshot snapshot = ap.deathInventorySnapshot().orElse(null);
        if (snapshot != null && snapshot.belongsTo(ap)) {
            return snapshot.inventory();
        }
        return RddInventoryProjection.countInventory(ap);
    }

    static int freeMainSlots(NumenPlayer ap) {
        return RddInventoryProjection.freeMainSlots(ap);
    }

    static final int MAIN_INV_SLOTS = RddInventoryProjection.MAIN_INV_SLOTS;

    /** 背包快满提醒的冷却（刻）。10 秒量级：够它去开箱卸货，又不至于整趟只提醒一次。 */
    private static final long BACKPACK_WARN_COOLDOWN_TICKS = 200;

    /**
     * 背包快满就主动提醒它去卸货。
     *
     * <p>用户点名的痛点：它"老是背包满"，满了之后采集/拾取才失败——<b>事前没人说</b>。
     * 这里在每 tick 监督入口做一次判断，靠 {@link com.dwinovo.numen.rdd.policy.BackpackSpace}
     * 去重（首次/更紧/过冷却才发），再走既有的 {@code RddPlugin.nudge} 通道注入提醒。
     */
    /**
     * 039 U3：装备要件的三态判定（只发事实事件）。
     *
     * <p>口径写死：**NOT_VERIFIABLE 绝不能被当成满足**。观测读不到就明说"不可核验"，
     * 而不是悄悄回退成本地扫描（那样又会分出第二套真相）。
     */
    private void evaluateGearCondition(NumenPlayer ap, long gameTime) {
        try {
            final List<String> required = List.of(
                    "minecraft:iron_helmet", "minecraft:iron_chestplate",
                    "minecraft:iron_leggings", "minecraft:iron_boots");
            var result = RddCondition.evaluate(ap.getUUID(),
                    ap.level().dimension().location().toString(), gameTime, required);
            String signature = result.verdict().name();
            String prev = GEAR_VERDICT.put(ap.getUUID(), signature);
            if (signature.equals(prev)) {
                return;   // 只在状态变化时发事件，避免每 30 秒刷屏
            }
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("companionId", ap.getUUID().toString());
            data.put("verdict", result.verdict().name());
            data.put("reason", result.reason());
            data.put("required", required);
            data.put("have", result.have());
            RddMonitor.publish("condition_evaluated", data);
        } catch (RuntimeException ignored) {
            // 判定是增强，不许拖垮 tick
        }
    }

    private static final Map<UUID, String> GEAR_VERDICT = new java.util.concurrent.ConcurrentHashMap<>();

    private void maybeWarnBackpackFull(NumenPlayer ap) {
        if (!RddAlarms.enabled("backpack_full")) {
            return; // 2026-10-08：这条提醒可单独关（config/numen/rdd-alarms.json）
        }
        try {
            int free = freeMainSlots(ap);
            UUID id = ap.getUUID();
            long now = RddInstrumentation.currentGameTimeTicks();
            long lastAt = backpackWarnedAt.getOrDefault(id, 0L);
            int lastFree = backpackWarnedFree.getOrDefault(id, -1);
            if (!com.dwinovo.numen.rdd.policy.BackpackSpace.shouldWarn(
                    free, lastAt, lastFree, now, BACKPACK_WARN_COOLDOWN_TICKS)) {
                return;
            }
            backpackWarnedAt.put(id, now);
            backpackWarnedFree.put(id, free);
            RddPlugin.nudge(id, com.dwinovo.numen.rdd.policy.BackpackSpace.warnMessage(free, MAIN_INV_SLOTS));
            RddMonitor.publish("backpack_near_full", Map.of(
                    "companionId", id.toString(), "freeSlots", free, "mainSlots", MAIN_INV_SLOTS));
        } catch (RuntimeException ex) {
            // 提醒是增益功能，出错绝不能影响主链推进
            LOG.warn("[rdd] 背包提醒失败 {}: {}", ap.getUUID(), ex.toString());
        }
    }
}
