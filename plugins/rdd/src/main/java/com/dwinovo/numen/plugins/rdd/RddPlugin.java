package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugin;
import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.AssetHistory;
import com.dwinovo.numen.rdd.core.AssetHistory;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.core.RddChainFactory;
import com.dwinovo.numen.rdd.core.RddDeathLedger;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.dwinovo.numen.rdd.fact.CompletedFactStore;
import com.dwinovo.numen.rdd.fail.FailureEvent;
import com.dwinovo.numen.rdd.fail.FailureKind;
import com.dwinovo.numen.rdd.policy.ResourceBudget;
import com.dwinovo.numen.rdd.policy.RiskGate;
import com.dwinovo.numen.rdd.replan.ReplanContextBuilder;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** NUMEN host adapter for the host-independent RDD task-chain core. */
public final class RddPlugin implements NumenPlugin {
    private static final Logger LOG = LoggerFactory.getLogger(RddPlugin.class);
    private static final Map<UUID, RddRuntime> RUNTIMES = new ConcurrentHashMap<>();
    /** Reusable world assets survive task replacement and are attached to the next task runtime. */
    private static final Map<UUID, AssetRegistry> ASSETS = new ConcurrentHashMap<>();
    private static final Set<UUID> DECOMPOSING = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, BodyState> BODY = new ConcurrentHashMap<>();
    private static final AtomicLong BODY_CALLS = new AtomicLong();
    private static final RddCallbackGuard CALLBACKS = new RddCallbackGuard();
    /** 懒边界上报去重：uuid → 最近一次已上报的未展开一级 id（避免每秒刷 expansion_needed）。 */
    private static final Map<UUID, String> LAST_EXPANSION_REPORT = new ConcurrentHashMap<>();
    /** 开工拍醒去重：uuid → 已经拍过开工的那个二级 id（每个二级都要能开工；换目标时清）。 */
    private static final Map<UUID, String> KICKOFF_SENT = new ConcurrentHashMap<>();
    static {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppedEvent event) -> clearWorldState());
    }
    /**
     * RDD 是否允许"自动提交身体工具"。
     *
     * <p><b>默认 false（2026-09-29 由 true 改回 false）</b>——用户硬要求：「RDD 只负责规划/管理，
     * 绝对不能跟执行方抢方向盘」。此前默认 true 且 {@code setBodySubmissionEnabled} 从未被任何
     * 地方调用，于是"防双驾驶"只是注释没接线：RDD 自动派 mine/goto，AI 模型自己也派，
     * 两个方向盘互抢 → 表现为<b>原地左右横跳</b>。用户原话：「这根本就不是执行层的问题」。
     *
     * <p>要临时开回来：写 {@code config/numen/rdd-bodydispatch.flag}（内容 on/run/1），
     * 与监督那套 flag 一致，无需重编译。
     */
    private static volatile boolean bodySubmissionEnabled = false;
    /** 开关文件 config/numen/rdd-bodydispatch.flag：内容含 "on"/"run"/"1" → 允许自动提交身体工具。 */
    private static volatile Path bodyDispatchFlag;
    /** 空转止血：是否允许"监督拍醒"主动干预（nudge/自动重试/失败升级/重派身体）。
     *  默认 true。暂停时 Detector 退化为纯观察——真实资产检测推进 + EarlyAchievement 照常，
     *  但绝不 nudge 注入 AI / 自动重试 / 升级判失败（LLM 空转止血）。 */
    private static volatile boolean supervisionEnabled = true;
    /** 开关文件 config/numen/rdd-supervision.flag：内容含 "pause"(或 "0") → 暂停监督。 */
    private static volatile Path supervisionFlag;
    /**
     * 同一同伴两次拍醒的最小间隔（毫秒）。2026-10-03 实测：单小时 46 次、同一条话术 15 分钟内
     * 刷 42 次 —— 催工有六七个独立来源，单来源去重挡不住叠加，所以要一个跨来源的总闸。
     * 取 3 分钟：够 AI 读完上一条并真的动手（判据变了自然会放行），又不会把话重复说 40 遍。
     */
    private static final long MIN_NUDGE_INTERVAL_MS = 3L * 60L * 1000L;
    /** 最近一次**真的注入出去**的拍醒时刻，节流判据。键 = 同伴 + 当前二级（见 {@link #nudgeBucket}）：
     *  状态一变（换二级/换一级）就是新桶 → 天然放行，不再让上一阶段的 3 分钟窗口压住下一阶段。 */
    private static final Map<String, Long> LAST_NUDGE_AT = new ConcurrentHashMap<>();
    /** 最近一次真实背包快照（Detector 每秒写，uuid→物品ID→数量）。规划注入用；不清除=背包是女仆属性与链无关。 */
    private static final Map<UUID, Map<String, Integer>> LAST_INVENTORY = new ConcurrentHashMap<>();
    /** 最近一次真实背包扫描时刻（系统毫秒），供规划声明的 verified_at 元字段。 */
    private static final Map<UUID, Long> LAST_INVENTORY_AT = new ConcurrentHashMap<>();
    /** setup 时保存的插件门面，用于监督拍醒（nudge 注入内置 AI）。 */
    private static volatile NumenApi numenApi;
    /** 任务链持久化目录 config/numen/rdd-tasks（每个同伴一个 <uuid>.json）。 */
    private static volatile Path tasksDir;
    /** Independent world-asset store; clearing a task must not erase a base or known structure. */
    private static volatile Path assetsDir;
    /** S1 语义资产目录 config/numen/rdd-semantics（每同伴一个 <uuid>.json；派生自需求清单）。 */
    private static volatile Path semanticsDir;

    static Path semanticsDir() {
        Path p = semanticsDir;
        return p == null ? null : p;
    }
    /** P0 完成事实目录 config/numen/rdd-facts（每个同伴一个 <uuid>.json）；跨重绑/跨重启保留。 */
    private static volatile Path factsDir;
    /** 内存完成事实仓库（uuid→store）；磁盘为真身，清世界状态只清内存。 */
    private static final Map<UUID, CompletedFactStore> FACTS = new ConcurrentHashMap<>();
    /** P4 重规划预算：每（同伴|一级）最多自动重规划次数；超限回落停车，防无限烧 LLM。 */
    private static final int MAX_REPLAN_PER_PRIMARY = 3;
    /** 协商驱动改单的独立预算（与失败驱动分开；士兵的反馈不该被 REPLAN 预算回绝）。 */
    private static final int MAX_NEGOTIATION_REPLANS_PER_PRIMARY = 3;
    private static final Map<String, Integer> REPLAN_COUNTS = new ConcurrentHashMap<>();
    /** 同伴自绑床位复活锚点：companionId → 同伴自己的 respawn 床位（成功 sleep 时由原版写入）。复活(SPAWN)时 TP 到床旁。 */
    private static final Map<UUID, BedAnchor> BED_RESPAWN_PREFERENCE = new ConcurrentHashMap<>();
    /**
     * 床锚点 = 位置 + 所在维度（F4b）。
     *
     * <p>为什么必须带维度：原版 {@code getRespawnPosition()} 只给坐标，
     * 而"在主世界记下的床"与"在下界记下的床"坐标可以完全重合。
     * 旧实现只存 {@code BlockPos}，靠"当前维度 == 床所在维度"当场推断，
     * 推断失败就<b>静默 return</b> —— 于是"床偏好为什么没生效"永远查不出来。
     */
    record BedAnchor(BlockPos pos, String dimension) {}
    /** F2 死亡台账目录 config/numen/rdd-ledger（每个同伴一个 <uuid>.json）。 */
    private static volatile Path ledgerDir;
    /**
     * 台账已从磁盘恢复过的同伴（F2 加载时序护栏）。
     *
     * <p><b>为什么必须先 restore 再 record</b>：反过来会用旧账覆盖本次运行刚记的死亡，
     * 那是比丢账更坏的结果（假"没死过"）。这里用一次性 guard 保证每个同伴只恢复一次。
     */
    private static final Set<UUID> LEDGER_LOADED = ConcurrentHashMap.newKeySet();
    /** P2.1 资产历史目录 config/numen/rdd-history（每个同伴一个 <uuid>.json）；Lost≠Gone 的长期线索。 */
    private static volatile Path historyDir;
    /** 内存资产历史（uuid→history）；磁盘为真身，清世界状态只清内存。 */
    private static final Map<UUID, AssetHistory> HISTORY = new ConcurrentHashMap<>();

    record BodyState(String subtaskId, int submitCount) {}

    /**
     * F4a：判定「同一点连死」的窗口与半径。
     *
     * <p>实机依据（2026-09-30 19:22:13 与 19:23:15，gpt 实例，用户目视确认）：
     * 两次死亡相隔 62 秒、坐标 {@code (700,64,671)} 与 {@code (700,65,671)}，只差 1 格。
     * 那次她复活后没补给没撤离，直接回了同一个危险点，于是又死。
     *
     * <p>取 3 分钟 / 8 格：足够覆盖"死 → 复活 → 走回原处再死"这一整段，
     * 又不至于把"很久之后在同一个矿洞口第二次死"也算成连续送命（那属于正常探索）。
     */
    static final long REPEAT_DEATH_WINDOW_TICKS = 180L * 20L;
    static final double REPEAT_DEATH_RADIUS_BLOCKS = 8.0d;

    @Override
    public void setup(NumenApi numen) {
        numenApi = numen;
        tasksDir = numen.configDir().resolve("rdd-tasks");
        assetsDir = numen.configDir().resolve("rdd-assets");
        factsDir = numen.configDir().resolve("rdd-facts");
        historyDir = numen.configDir().resolve("rdd-history");
        ledgerDir = numen.configDir().resolve("rdd-ledger");
        semanticsDir = numen.configDir().resolve("rdd-semantics");
            supervisionFlag = numen.configDir().resolve("rdd-supervision.flag");
        // 暂停开关：文件存在 = 禁用 PAUSE（2026-09-30 用户要求可随时关，防误伤实验）
        setPauseDisabledFlagPath(numen.configDir().resolve("rdd-pause-disabled.flag"));
            bodyDispatchFlag = numen.configDir().resolve("rdd-bodydispatch.flag");
        // 2026-10-08 正式睡觉支线：通用支线引擎（rdd-core）+ 宿主接线（加载类时注册 tick 监听）。
        SideTaskHost.setup(numen.configDir());
        // 2026-10-08 携带器提醒的"每条约开关"（含换装提醒 gear_upgrade，默认关）。
        RddAlarms.install(numen.configDir());
        // E1 shadow：规划角色的预算策略（与客户端同一份文件，幂等）。
        com.dwinovo.numen.agent.llm.BudgetPolicy.install(numen.configDir());
        numen.registerTool(new RddStatusTool());
        numen.registerTool(new RddSubmitTool());
        // 2026-10-08 只读世界事实探针：逐组验证世界判定用（不改状态）。
        numen.registerTool(new WorldFactProbeTool());
        // 2026-10-08 只读“成熟作物”扫描：种田 AC 的“扫成熟作物”这一步。
        numen.registerTool(new ScanMatureCropsTool());
        // 2026-10-08 原子“收+种”工具（Path B）：一格一次调用，要么全成要么不改。
        numen.registerTool(new FarmCellTool());
        // 2026-10-08 一片田一次收+留苗（避开 ACX for+异步 的续跑限制）。
        numen.registerTool(new FarmNearbyTool());
        numen.registerTool(new RddSkipTool());
        numen.registerTool(new RddAssetsTool());
        // Supervisor ↔ Numen 双向协商：士兵可对命令结构化回执（ACCEPT/REJECT/COUNTER）。
        numen.registerTool(new RddConcernTool());
        // 主人待命控制面：owner 暂停与士兵 PAUSE 治理不同（主人待命永不过期/不被自动恢复），
        // 必须有独立入口 —— 否则"主人暂停"的保护逻辑是死代码（深审 R04/codex P1-2）。
        numen.registerTool(new RddStandbyTool());
        // 验证专用：debug_kill（需 confirm=true）——验证死亡回收闭环（V3）。
        numen.registerTool(new RddDebugKillTool());
        // G2b：死亡台账只读查询面（V-20261020-17 债②：事件有、可回读的查询面没有）
        numen.registerTool(new RddDeathLedgerTool());
        // G2c：验证钩子（用户 2026-10-20 批准）——清掉落物，构造超时分支
        numen.registerTool(new RddDebugClearDropsTool());
        numen.registerTool(new RddDebugRecoveryTool());
        // G3d：计划补丁（增量改计划；只碰当前指针及之后的 PENDING 节点，CAS 提交）
        numen.registerTool(new RddPatchTool());
        // 2026-10-27（主人）：代码层直发 Supervisor（Stage-A）的注册口——实验台跑超长线任务用，
        // 与 /goal 同一条规划真路；source 标 rdd_supervisor_submit，监测台能分清发起方。
        numen.registerTool(new RddSupervisorSubmitTool());
        // 接管 /goal：先同步认领，Stage-A 异步规划；规划期间 NUMEN 原生目标循环让位。
        com.dwinovo.numen.agent.goal.GoalSinks.register((uuid, objective) -> {
            if (uuid == null || objective == null || objective.isBlank()) {
                return false;
            }
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return false;
            RddCallbackGuard.Ticket ticket = CALLBACKS.replace(uuid);
            onServer(server, ticket, () -> beginPlanning(server, ticket, objective));
            return true;
        });
        com.dwinovo.numen.agent.goal.GoalSinks.registerClear((uuid, reason) -> {
            if (uuid == null) {
                return false;
            }
            // ⚠️ 2026-10-03 实机 bug：这里原来对任何非 null uuid 都 return true，于是
            // `/goal clear` 在同伴根本没有链时也回「清掉了同伴正在跑的任务链。」——假成功。
            // 这个 boolean 的契约是「真的清掉了东西吗」（GoalCommand 据它决定回哪句话），
            // 跟 removeCurrent 失败时宁可保留内存也要发 task_clear_failed 是同一个纪律。
            boolean had = hasTaskState(uuid);
            remove(uuid);
            LOG.info("[rdd] 已接收目标清除请求 {} hadState={}", uuid, had);
            return had;
        });
        // /goal 无参要如实报出「RDD 手里正在跑什么」：接管后引擎侧 goal 被置空，
        // 引擎只能看到 null → 原来一律回答「还没有目标」（主人给了目标却说没有）。
        com.dwinovo.numen.agent.goal.GoalSinks.registerStatus(uuid -> {
            RddRuntime rt = uuid == null ? null : RUNTIMES.get(uuid);
            if (rt == null) return null;
            Subtask cur = rt.chain().currentSubtask();
            return rt.chain().goal().description()
                    + "（当前二级: " + (cur == null ? "未展开" : cur.description())
                    + " · 状态 " + rt.chain().currentSubtaskStatus() + "）";
        });
        // P1 资产真相层：同伴死亡/掉装备 → 背包类资产立即失效（不再拿旧装备当"还持有"），
        // 规划输入 LAST_INVENTORY 同步清空；世界资产（基地/结构）不受影响，重新观测会恢复 OBSERVED。
        numen.on(CompanionEvent.DEATH, body -> onCompanionDeath(body));
        numen.on(CompanionEvent.REMOVE, body -> {
            LAST_INVENTORY.remove(body.getUUID());
            LAST_INVENTORY_AT.remove(body.getUUID());
            // 38号v3.6 B24：携带器缓存与 LAST_INVENTORY 同生命周期，不清就是泄漏
            RddCarryHint.invalidate(body.getUUID());
        });
        // 床边复活：死亡时记下同伴"自己绑的"床位（见 onCompanionDeath），SPAWN 时 TP 到床旁安全落点。
        // 只消费一次（take-and-clear）；首建/休眠恢复没有锚点，不会触发。
        numen.on(CompanionEvent.SPAWN, body -> {
            UUID uuid = body.getUUID();
            if (uuid == null) {
                LOG.warn("[rdd] SPAWN event missing companion UUID");
                return;
            }
            // 优先用内存锚点（死亡时记的，带维度）；重启后内存已清 → 回落读同伴**自己的** live respawn 点位
            // （原版持久化在 .dat，重启不丢）——修 V3 实测的"重启后死亡落世界出生点"。
            BedAnchor anchor = BED_RESPAWN_PREFERENCE.remove(uuid);
            BlockPos bedPos = anchor == null ? null : anchor.pos();
            if (bedPos == null || bedPos.equals(BlockPos.ZERO)) {
                try {
                    BlockPos live = body.getRespawnPosition();
                    if (live != null && !live.equals(BlockPos.ZERO)) {
                        bedPos = live;
                    }
                } catch (RuntimeException ignore) {
                    // 读不到就不 TP
                }
            }
            if (bedPos == null) return;
            try {
                ServerLevel level = body.serverLevel();
                if (level == null || !(level.getBlockState(bedPos).getBlock() instanceof BedBlock)) return;
                // F4b：锚点带了维度 → 跨维度时**明确不发 TP**（此前只能靠坐标猜）。
                if (anchor != null && !level.dimension().location().toString().equals(anchor.dimension())) {
                    RddMonitor.publish("bed_respawn_skipped_dimension", Map.of(
                            "companionId", uuid.toString(),
                            "bed", bedPos.toShortString(),
                            "bedDimension", anchor.dimension(),
                            "currentDimension", level.dimension().location().toString(),
                            "note", "the companion respawns with its owner; a bed in another dimension is not used"));
                    return;
                }
                Vec3 stand = bedStandPos(level, bedPos, body);
                body.moveTo(stand.x, stand.y, stand.z, body.getYRot(), body.getXRot());
                LOG.info("[rdd] {} 复活后 TP 到床旁: {}", uuid, bedPos);
            } catch (RuntimeException e) {
                LOG.warn("[rdd] 床边复活 TP 失败 {}: {}", uuid, e.toString());
            }
        });
        numen.contributeState(uuid -> {
            String context = renderStateContext(uuid);
            if (!context.equals(LAST_CONTEXT.put(uuid, context))) {
                Map<String, Object> data = observationData(uuid);
                data.put("source", "rdd_state_contributor");
                data.put("target", "numen");
                data.put("context", context);
                RddMonitor.publish("numen_context", data);
            }
            return context;
        });
    }

    /** Model-requested task correction: only explicitly optional food may be skipped. */
    static String skipOptionalCurrent(UUID companionId, String expectedSubtaskId, String reason) {
        RddRuntime runtime = RUNTIMES.get(companionId);
        if (runtime == null || runtime.chain().currentSubtask() == null) return "no active executable RDD subtask";
        Subtask current = runtime.chain().currentSubtask();
        if (!current.id().equals(expectedSubtaskId)) return "refused: stale subtask id; read rdd_status again";
        if (!RddOptionalFood.canSkip(current, lastInventory(companionId)))
            return "refused: only an optional food step (food item or group=food, not marked required) can be skipped";
        if (RddOptionalFood.foodAlreadyCovered(current, lastInventory(companionId))) {
            // 埋点：重复采集已充足的资源（模型主动跳过前，背包/派生等价已覆盖该食物目标）
            RddInstrumentation.publish(RddInstrumentation.REPEAT_GATHER, Map.of(
                    "companionId", companionId.toString(),
                    "task", current.id(),
                    "reason", "optional food gather already covered by inventory; model requested skip",
                    "context", Map.of("lastObservedInventory", lastInventory(companionId))));
        }
        if (com.dwinovo.numen.task.CompanionTickDispatcher.currentTaskFor(companionId) != null)
            return "refused: body is busy; wait for the current action to stop";
        runtime.chain().skipSubtask(current.id(), reason == null || reason.isBlank() ? "optional food unavailable" : reason);
        clearBody(companionId);
        finishResolvedPrimary(companionId, runtime);
        publishTaskSnapshot(companionId, "model_requested_optional_skip");
        RddMonitor.publish("subtask_skipped", Map.of("companionId", companionId.toString(),
                "subtask", current.id(), "reason", reason == null ? "optional food unavailable" : reason,
                "source", "numen_model"));
        return "optional food subtask skipped; continue with the next RDD step";
    }

    /** A terminal optional step must not leave the chain parked at AWAITING_SUPERVISOR. */
    static void finishResolvedPrimary(UUID companionId, RddRuntime runtime) {
        var chain = runtime.chain();
        if (chain.primaryStatus() != com.dwinovo.numen.rdd.api.PrimaryGoalStatus.AWAITING_SUPERVISOR) return;
        String primary = chain.currentPrimary().id();
        String primaryDesc = chain.currentPrimary().description();
        runtime.applySupervisor(new com.dwinovo.numen.rdd.api.SupervisorDecision(
                com.dwinovo.numen.rdd.api.SupervisorDecisionType.CONFIRM, primary,
                "required conditions verified; optional omissions remain SKIPPED, not world achievements"));
        recordStageFact(companionId, chain.goal(), primaryDesc);
        RddMonitor.publish("primary_resolved", Map.of("companionId", companionId.toString(), "primary", primary,
                "reason", "verified required steps; optional steps may be skipped"));
    }

    private static final Map<UUID, String> LAST_CONTEXT = new java.util.concurrent.ConcurrentHashMap<>();

    /** Disk handoffs survive; in-flight planning and cached world observations do not. */
    private static void clearWorldState() {
        CALLBACKS.clear(() -> {
            DECOMPOSING.clear();
            RddGoalDriver.clearAll();
            BODY.clear();
            RUNTIMES.clear();
            ASSETS.clear();
            FACTS.clear();
            LAST_CONTEXT.clear();
            LAST_INVENTORY.clear();
            RddCarryHint.invalidateAll();
            LAST_EXPANSION_REPORT.clear();
            KICKOFF_SENT.clear();
            // 2026-09-30 深审 R05：世界态必须清干净。
            // 计划代次的真源在 TaskChain 里（随链持久化），这里不用管；
            // 但「上一个世界的回执」一个都不能带到新世界。
            RddNegotiationInbox.clearAll();
            com.dwinovo.numen.rdd.core.RddDeathLedger.clearAll();
            SideTaskHost.onServerStopped(); // 上一个世界的支线实例不带进新世界
        });
    }

    private static void beginPlanning(MinecraftServer server, RddCallbackGuard.Ticket ticket, String objective) {
        beginPlanning(server, ticket, objective, "goal_sink");
    }

    /**
     * 2026-10-27（主人）：代码层直发 Supervisor 的同一入口 —— {@code rdd_supervisor_submit} 走这里。
     * 与 {@code /goal} 的唯一区别是 {@code source} 标签（监测台区分发起方）与可选的引擎目标登记。
     * <b>不旁路任何规划语义</b>：Stage-A、绑定、失败回落全部同一段代码。
     */
    static void beginPlanning(MinecraftServer server, RddCallbackGuard.Ticket ticket,
                              String objective, String source) {
        UUID uuid = ticket.companionId();
        DECOMPOSING.add(uuid);
        BODY.remove(uuid);
        RddGoalDriver.clear(uuid);
        RddMonitor.publish("supervisor_input", Map.of(
                "companionId", uuid.toString(), "objective", objective,
                "source", source, "target", "rdd"));
        RddStagePlanner.planStages(uuid, objective, stages -> onServer(server, ticket, () -> {
            if (!stages.isEmpty()) {
                try {
                    bindCurrent(uuid, RddChainFactory.fromStages(uuid, objective, stages));
                    RddMonitor.publish("goal_staged", Map.of(
                            "companionId", uuid.toString(), "objective", objective, "stages", stages.size()));
                    publishTaskSnapshot(uuid, "goal_staged");
                    // First primary stays unexpanded; Detector reports the boundary to GoalDriver.
                    LOG.info("[rdd] Stage-A 规划 {} 级已绑定 {}:{}", stages.size(), uuid, objective);
                } catch (RuntimeException ex) {
                    LOG.warn("[rdd] Stage-A 装配失败，回落单遍分解: {}", ex.toString());
                    DECOMPOSING.add(uuid);
                    decomposeSinglePass(server, ticket, objective);
                    return;
                }
                DECOMPOSING.remove(uuid);
                return;
            }
            // Fallback belongs to this submission too; a later /goal invalidates both callbacks.
            decomposeSinglePass(server, ticket, objective);
        }));
        LOG.info("[rdd] 接管目标，Stage-A 规划中 {}:{} (source={})", uuid, objective, source);
    }

    /**
     * 2026-10-27：代码层直发 Supervisor 的入口（{@code rdd_supervisor_submit} 工具调它）。
     *
     * <p>与 {@code /goal} 同一条规划真路（{@link #beginPlanning}），只多两件事：
     * <ul>
     *   <li>{@code register=true}（缺省）时把目标写进引擎 {@code GoalState}——
     *       面板与轮末简报要能看到"她在追什么"，否则实验里目标只活在链里，人看不出来；</li>
     *   <li>同伴不在线 / 无服务器 → 显式拒绝（回执给外部脚本，不静默丢）。</li>
     * </ul>
     */
    static String submitSupervisorGoal(UUID companionId, String objective, boolean register, String source) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return "no server: cannot plan while the game is offline";
        }
        if (NumenPlayer.findByUuid(server, companionId) == null) {
            return "companion not online: rdd_supervisor_submit needs an active body "
                    + "(planning and binding both require it)";
        }
        if (register) {
            // 2026-10-27：客户端 EntityAgentLoop 不在本插件的可视范围（rdd 是服务端插件）。
            // 引擎目标登记改走 GoalSinks 的既有注册面——它本来就是「谁来都行」的目标入口，
            // 而且与 /goal 完全同一条路（连带把引擎侧 goal 面板一起写好）。
            // 拿不到（无头/未注册）不拦：链照样建，只是引擎目标面板看不到——如实记事件。
            boolean sinkOk = false;
            try {
                sinkOk = com.dwinovo.numen.agent.goal.GoalSinks.submit(companionId, objective);
            } catch (RuntimeException ex) {
                LOG.warn("[rdd] 引擎目标登记失败（链不受影响） {}: {}", companionId, ex.toString());
            }
            RddMonitor.publish("supervisor_goal_register", Map.of(
                    "companionId", companionId.toString(),
                    "registered", sinkOk, "source", source));
        }
        RddCallbackGuard.Ticket ticket = CALLBACKS.replace(companionId);
        onServer(server, ticket, () -> beginPlanning(server, ticket, objective, source));
        return "objective accepted by the supervisor (Stage-A planning started)";
    }

    /** Exact RDD state block returned to Numen; observation does not own task progress. */
    private static String renderStateContext(UUID uuid) {
            // 2026-10-08 支线接管：支线 active 时当前目标投影为睡觉支线，主线上下文让位。
            String sideContext = SideTaskHost.contextFor(uuid);
            if (sideContext != null) {
                return withAssets(uuid, sideContext);
            }
            if (DECOMPOSING.contains(uuid)) {
                return withAssets(uuid, "<rdd><enabled>true</enabled><active>false</active><decomposing>true</decomposing></rdd>");
            }
            RddRuntime runtime = RUNTIMES.get(uuid);
            if (runtime == null) {
                return withAssets(uuid, "<rdd><enabled>true</enabled><active>false</active></rdd>");
            }
            TaskChain chain = runtime.chain();
            Subtask current = chain.currentSubtask();
            if (current == null) {
                // 当前一级已到达但未展开(懒加载)：诚实报阶段主题，不伪造可执行节点
                return withAssets(uuid, "<rdd><enabled>true</enabled><active>true</active>"
                        + "<primary_status>" + chain.primaryStatus() + "</primary_status>"
                        + "<state>stage_reached_expanding</state>"
                        + "<current_phase>" + escape(chain.currentPrimary().description()) + "</current_phase></rdd>");
            }
            return withAssets(uuid, "<rdd><enabled>true</enabled><active>true</active>"
                    + "<primary_status>" + chain.primaryStatus() + "</primary_status>"
                    + "<subtask>" + escape(current.id()) + "</subtask>"
                    + "<current_task>" + escape(current.description()) + "</current_task>"
                    + "<done_when>" + escape(String.valueOf(current.condition())) + "</done_when>"
                    + "<subtask_status>" + chain.currentSubtaskStatus() + "</subtask_status>"
                    + "<instruction>current_task 是你必须执行的当前目标（优先于自由活动）；"
                    + "若你认为它不合理/不可达/与目标冲突，用 report_task_concern 上报（REJECT/COUNTER+建议），"
                    + "指挥官会据此改单或重规划；不要默默无视。</instruction>"
                    // 38号v3.6 B24：携带器提醒由 withAssets 统一挂（它是全部分支的共同出口）。
                    // ⚠️ 2026-10-01 实机请求体抓到重复：这里加一次、withAssets 又 replace 一次，
                    //    <carry> 在 <rdd> 里出现两遍（白送 ~130 token/请求）。故此处不再加。
                    + "</rdd>");
    }

    /**
     * 携带器提醒（{@code 38} v3.6 B24）。**读缓存，永不读世界** ——
     * 上下文构建不该有副作用；读世界只在 {@link RddCarryHint#refresh} 的主线程路径里做。
     *
     * <p>缺快照时返回空串 → {@code <carry>} 整块不出现（B21：缺失就缺失，不写空标签）。
     */
    private static String carryHint(UUID companionId) {
        try {
            String hint = RddCarryHint.current(companionId);
            return hint.isEmpty() ? "" : hint;
        } catch (Throwable t) {
            // 携带器是锦上添花，绝不能拖崩主链路
            return "";
        }
    }
private static String withAssets(UUID companionId, String rddContext) {
        // 38号v3.6 B24：携带器提醒放在**这里**，而不是 renderStateContext 的主分支。
        // ⚠️ 2026-10-01 实机抓到：renderStateContext 有**三个提前返回分支**
        //    （decomposing / runtime==null / current==null），只有主分支会走到我最初加 carryHint 的位置。
        //    而「没有活动任务」恰恰走提前返回 —— 也就是**最该自己决定要不要先准备的时候看不到携带提醒**。
        //    withAssets 是**全部分支的共同出口**，放这里才真的每轮都带得上。
        String carry = carryHint(companionId);
        if (!carry.isEmpty()) {
            rddContext = rddContext.replace("</rdd>", carry + "</rdd>");
        }
        // 第三批 N1 消费：当前一级还没凑齐的需求（只读缓存；从未扫过背包时整块不出现）。
        // 放在这里的原因与 carry 相同：withAssets 是**全部分支的共同出口**。
        String needs = RddFactContext.executorBlock(companionId);
        if (!needs.isEmpty()) {
            rddContext = rddContext.replace("</rdd>", needs + "</rdd>");
        }
        // 2026-10-25（主人指出）：放进箱子的东西 AI 与规划器都"忘了"——
        // 背包口径只认手持，世界资产不记箱内容。这里把**身边容器里的存货**带回上下文：
        // 只读、只在 8 格内、只在非空时出现（不改任何判定）。
        // 2026-10-25（主人）：安家要"自己选地+自己整平+自己点灯"，这些工具早就有，
        // 缺的是把顺序讲清楚。只在当前目标/阶段谈基地时注入，避免每轮占 token。
        String playbook = "";
        // 2026-10-27：下矿姿势（死亡数据：52 次死亡 mob 41 / fall 1 / suffocate 1 / drown 1，
        // 死前一刻多在 eat / 查背包 / 查状态）。与安家提示同一注入点、同一纪律：只讲顺序。
        String minePlaybook = "";
        try {
            RddRuntime rt0 = RUNTIMES.get(companionId);
            if (rt0 != null && rt0.chain() != null) {
                com.dwinovo.numen.rdd.api.Subtask cur0 = rt0.chain().currentSubtask();
                String stageDesc = rt0.chain().goal() == null ? "" : String.valueOf(rt0.chain().goal().description());
                String curDesc = cur0 == null ? "" : String.valueOf(cur0.description());
                String both = stageDesc + " " + curDesc;
                playbook = RddBasePlaybook.block(both);
                minePlaybook = RddMinePlaybook.block(both);
            }
        } catch (RuntimeException ignored) {
            playbook = "";
            minePlaybook = "";
        }
        // 2026-10-25（主人）：**点一下床就绑好了**——但 AI 不一定会想到去点。
        // 谈基地/床时如果还没绑（spawn 为空或 0,0,0），直接把"去点一下"这句话放到它眼前。
        String bedHint = "";
        try {
            if (!playbook.isEmpty()) {
                net.minecraft.server.level.ServerPlayer body = RddCompanionBody.get(companionId);
                net.minecraft.core.BlockPos spawn = body == null ? null : body.getRespawnPosition();
                boolean unbound = spawn == null || spawn.equals(net.minecraft.core.BlockPos.ZERO);
                if (unbound) {
                    bedHint = "<base_hint>你还没有绑重生点。走到床边用 sleep 点一下就好 —— "
                            + "白天点也生效（只是不跳过时间），不用等天黑、也不用真的睡着。</base_hint>";
                }
            }
        } catch (RuntimeException ignored) {
            bedHint = "";
        }
        // 容器存货：**只读 tick 缓存**（上下文构建不保证在主线程，不能读世界）。
        String storageBlock = RddContainerMemory.cached(companionId);
        // 2026-10-27（D-01/D-02）：**敌怪与血量事实**。52 次死亡里 4 次死时正在查背包/查任务状态、
        // 2 次死时正在吃东西 —— 上下文里此前没有"你现在有危险"这条事实，于是轮询比活命优先。
        // 只读、只陈述、有危险才出现；不改任何判定（动作归执行 AI）。
        String dangerBlock = RddDangerFacts.blockFor(RddCompanionBody.get(companionId));
        String worldAssets = RddAssetContext.render(assets(companionId), 1200);
        // 容器记忆**独立成块**追加：它不依赖 <rdd> 包装是否存在（无链时也要看得见"箱子里有什么"）。
        StringBuilder merged = new StringBuilder(rddContext == null ? "" : rddContext);
        if (!storageBlock.isEmpty()) {
            if (merged.length() > 0) merged.append("\n");
            merged.append(storageBlock);
        }
        if (!playbook.isEmpty()) {
            if (merged.length() > 0) merged.append("\n");
            merged.append(playbook);
        }
        if (!minePlaybook.isEmpty()) {
            if (merged.length() > 0) merged.append("\n");
            merged.append(minePlaybook);
        }
        if (!bedHint.isEmpty()) {
            if (merged.length() > 0) merged.append("\n");
            merged.append(bedHint);
        }
        if (!worldAssets.isBlank()) {
            if (merged.length() > 0) merged.append("\n");
            merged.append(worldAssets);
        }
        // 危险事实放**最后**：离模型说话的位置最近（尾部注意力最强），且它是最有时效的一条。
        if (!dangerBlock.isEmpty()) {
            if (merged.length() > 0) merged.append("\n");
            merged.append(dangerBlock);
        }
        return merged.toString();
    }

    /** Stage-A 退化回落：今天的单遍 decompose -> bind + startCurrent（目标不被吞，行为不劣化）。 */
    private static void decomposeSinglePass(MinecraftServer server, RddCallbackGuard.Ticket ticket, String objective) {
        UUID uuid = ticket.companionId();
        RddDecomposer.decompose(uuid, objective, goal -> onServer(server, ticket, () -> {
            try {
                bindCurrent(uuid, goal);
                RddRuntime runtime = runtime(uuid);
                if (runtime != null) {
                    runtime.startCurrent();
                    publishTaskSnapshot(uuid, "goal_decomposed");
                    kickoffNudge(uuid, runtime.chain());
                }
                LOG.info("[rdd] 目标分解完成并启动 {}:{}", uuid, goal.description());
            } catch (RuntimeException ex) {
                LOG.warn("[rdd] 分解结果启动失败，保留原生回落: {}", ex.toString());
                removeCurrent(uuid);
            } finally {
                DECOMPOSING.remove(uuid);
            }
        }));
    }

    public static void bind(UUID companionId, Goal goal) {
        if (companionId == null || goal == null) throw new IllegalArgumentException("companion and goal required");
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) throw new IllegalStateException("RDD bind requires server thread");
        RddCallbackGuard.Ticket ticket = CALLBACKS.replace(companionId);
        onServer(server, ticket, () -> bindCurrent(companionId, goal));
    }

    /** Apply a planning result without invalidating that result's own goal generation. */
    private static void bindCurrent(UUID companionId, Goal goal) {
        BODY.remove(companionId);
        DECOMPOSING.remove(companionId);
        // 换目标 = 旧支线作废：立即取消，且不恢复旧主线（新链自己重新选择）。
        SideTaskHost.cancel(companionId, "mainline goal replaced");
        // 深审 R05：新目标 = 上一轮的回执全部作废。
        // 否则士兵对旧计划说的 COUNTER/PAUSE 会滞留到新链的下一个 tick 被消费，
        // 去改一个它根本没被派过的任务。深审已在消费侧加了 taskId 二次核对（RddDetector.tickNegotiation），
        // 这里从生产侧断根：换目标/清目标就不再有旧回执可泄漏。
        RddNegotiationInbox.clear(companionId);
        // 2026-09-30 codex 审稿 P2-4：**只清"属于这个目标"的支线追踪，不清死亡事实**。
        // 换目标时，地上的掉落物照样还在，删掉台账会让下一次死亡的时间表漏掉上一个仍可回收的掉落点。
        RddRepairDispatch.remove(companionId);
        // RddDeathLedger 刻意**不清**（死亡是"同伴在这个世界发生了什么"，不是"这个目标的事"）。
        // 换目标 = 换一条新链，它的 planRevision 从 0 开始；
        // 新 id 前缀含"新链自己的 id"，跨目标天然隔离（不必再加代号次）。
        // 新目标在同一同伴上会复用同样的 primary-<uuid8>-N 命名，必须清掉去重记忆，否则首个懒边界漏报。
        LAST_EXPANSION_REPORT.remove(companionId);
        KICKOFF_SENT.remove(companionId);   // 一级 id 跨目标重复（primary-<uuid8>-N），不清就永远不再拍
        clearNudgeThrottle(companionId);    // 新目标 = 新窗口，保证首次开工拍醒一定发得出去
        RddGoalDriver.clear(companionId);
        clearReplanCounts(companionId);   // 新目标 = 新阶段标识，重规划预算清零
        // P0：把该目标血缘下"已可靠完成"的阶段事实注入新链 → 已达成一级直接跳过、不再重复规划
        RUNTIMES.put(companionId, new RddRuntime(
                new TaskChain(goal, facts(companionId).satisfiedStageKeys(goal)), assets(companionId)));
        // 【架构概念 2/4 接线】一级目标生成时同步产出"需求清单"（元件检测的需求侧）。
        // 只产"事实"，不在这里做裁决（裁决归 DetectionArbitration），更不在这里改规划。
        // 2026-10-06：清单不再"算完即丢"——RddFactContext 缓存它，并在检测 tick 里持续对账；
        // goalId 传真 Goal.id（事实库用的就是它，见 RddRequirementDetector 的注释）。
        try {
            RddRuntime justBound = RUNTIMES.get(companionId);
            var primary = justBound == null ? null : justBound.chain().currentPrimary();
            RddFactContext.onPrimaryBound(companionId, primary, goal.id());
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 需求清单生成失败 {}: {}", companionId, ex.toString());
        }
        saveRuntimes();
        publishTaskSnapshot(companionId, "task_bound");
    }

    /**
     * P4：对一个“卡死”的同伴显式发起重规划——进入 REPLANNING，用真实状态（失败事实+资产+完成事实+风险缺口）
     * 重分解当前一级；成功则 {@code replaceCurrentSubtasks} 换新计划，失败/空则 {@code resumeFromReplanning} 回落重跑现有。
     * 这是 REPLANNING 在生产里的**活触发器**（此前只有测试构造 REPLAN）。
     */
    static boolean requestReplan(UUID companionId, String reason) {
        return requestReplan(companionId, reason, false, null);
    }

    static boolean requestReplan(UUID companionId, String reason, boolean fromNegotiation) {
        return requestReplan(companionId, reason, fromNegotiation, null);
    }

    /**
     * 重规划（带来源标记 + **士兵的具体建议**）。
     *
     * <p>{@code fromNegotiation=true} 时用**独立的协商预算**——士兵的反馈不该被"失败驱动的
     * 重规划预算"一口回绝（V1 实测：士兵报 COUNTER 时 REPLAN 预算已耗尽 → 直接停车）。
     *
     * <p>{@code soldierHint} 是 2026-09-28 补的关键一环：士兵（执行 AI）会带着**具体方案**
     * 上报（例如"三选一：按总铁量算 25 / 给我一处干燥矿点 / 让我转去收废弃传送门"），
     * 但旧实现只把 {@code reason}（"soldier COUNTER: …"）送进规划上下文，**建议本身被丢掉了** ——
     * 实机表现就是"干活的说了好几次原因，指挥官照原计划执行，像听不到"。
     * 现在建议会原样进入规划器的输入，规划器才可能"听懂并变通"。
     */
    /** 判断这次重规划是不是"重要东西确认丢了"型（死亡后 / 原因在说缺、丢）。 */
    private static boolean isMissingAssetCause(UUID companionId, String reason) {
        try {
            if (RddInstrumentation.recentDeath(companionId, RddInstrumentation.currentGameTimeTicks())) {
                return true;
            }
        } catch (RuntimeException ignore) {
            // 判定失败就按"不是"处理，走原来的整段重规划
        }
        if (reason == null) return false;
        String r = reason.toLowerCase(java.util.Locale.ROOT);
        return r.contains("缺") || r.contains("丢") || r.contains("missing") || r.contains("lost")
                || r.contains("asset") || r.contains("death");
    }

    /** 描述"丢了什么"（尽量具体；拿不到就退化成一句人话）。 */
    private static String describeMissing(UUID companionId) {
        try {
            var snap = planningSnapshot(companionId);
            var missing = ResourceBudget.missingFor(RiskGate.levelForText("recovery"), snap.availableCounts());
            if (missing != null && !missing.isEmpty()) {
                return String.join("、", missing);
            }
        } catch (RuntimeException ignore) {
            // 拿不到就退化成默认描述
        }
        return "死亡/丢失的装备与材料";
    }

    /**
     * 在当前一级**追加**一个恢复节点（不删除、不改写已有节点）。
     * 用现有的 PlanPatch（ADD_NODE）通道，走 propose → commit 的 CAS，避免绕过冻结/校验。
     */
    static boolean insertRecoveryNode(UUID companionId, String what, String reason) {
        RddRuntime rt = RUNTIMES.get(companionId);
        if (rt == null) return false;
        TaskChain chain = rt.chain();
        String primaryId = chain.currentPrimary().id();
        String nodeId = primaryId + "-recover-" + System.nanoTime() % 1_000_000L;
        String desc = "把丢失的「" + what + "」取回来：走到掉落/死亡地点拾取，取回后回到主线继续"
                + "（支线，不改变主线其它步骤）。";
        long base = chain.stateVersion();
        var patch = new com.dwinovo.numen.rdd.core.PlanPatch(
                "recover-" + nodeId, primaryId, base,
                java.util.List.of(com.dwinovo.numen.rdd.core.PlanPatch.Op.add(nodeId, desc,
                        java.util.Map.of())),
                reason == null ? "missing assets" : reason);
        var proposed = chain.proposePatch(patch);
        if (proposed == null || !proposed.ok()) {
            RddMonitor.publish("recovery_insert_rejected", java.util.Map.of(
                    "companionId", companionId.toString(),
                    "code", proposed == null ? "NULL" : proposed.code(),
                    "why", proposed == null ? "" : proposed.why()));
            return false;
        }
        var committed = chain.commitPatch(patch, base);
        if (committed == null || !committed.ok()) {
            RddMonitor.publish("recovery_insert_rejected", java.util.Map.of(
                    "companionId", companionId.toString(),
                    "code", committed == null ? "NULL" : committed.code(),
                    "why", committed == null ? "" : committed.why()));
            return false;
        }
        RddMonitor.publish("recovery_inserted", java.util.Map.of(
                "companionId", companionId.toString(),
                "primary", primaryId, "node", nodeId, "what", what));
        saveRuntimes();
        publishTaskSnapshot(companionId, "recovery_inserted");
        return true;
    }
    /**
     * 2026-10-23（主人要求）：**"重要东西确认丢了"这类重规划，要插入支线，不要重写整个当前一级。**
     *
     * <p>原行为：重规划 = {@code enterReplanning + replaceCurrentSubtasks}，把当前一级的二级计划整段换掉 ——
     * 对一个只是"丢了装备/材料"的处境来说，这是破坏性的：原本能走的做法被丢弃。
     *
     * <p>新行为：先判断这是不是"缺失型"重规划（最近死过、或原因文本在说缺/丢）；
     * 是的话走 {@link #insertRecoveryNode}：在当前一级**追加一个恢复节点**（PENDING，末尾），
     * 其余节点原样保留；取回后再回到主线。只有插入失败才回落到原来的整段重写。
     */
    static boolean requestReplan(UUID companionId, String reason, boolean fromNegotiation, String soldierHint) {
        if (!fromNegotiation && isMissingAssetCause(companionId, reason)) {
            // 2026-10-23（主人要求）：首选**派恢复支线**——主线由 SideTaskHost 冻结，支线跑完自动恢复，
            // 完全不改编主线计划。只有支线通道不可用（例如没有活跃身体）才退回"插节点"。
            String what = describeMissing(companionId);
            try {
                com.dwinovo.numen.rdd.side.recovery.RecoverySideTaskTrigger.expect(companionId, what, 1);
                LOG.info("[rdd] 缺失型重规划 → 已点名恢复支线（主线冻结，不重写）{}", companionId);
                RddMonitor.publish("recovery_side_task_expected", Map.of(
                        "companionId", companionId.toString(), "what", what, "reason", String.valueOf(reason)));
                return true;
            } catch (RuntimeException ex) {
                LOG.warn("[rdd] 点名恢复支线失败，改插入恢复节点 {}: {}", companionId, ex.toString());
            }
            if (insertRecoveryNode(companionId, what, reason)) {
                LOG.info("[rdd] 缺失型重规划 → 插入恢复节点（不重写当前一级）{}", companionId);
                return true;
            }
            LOG.warn("[rdd] 恢复通道都不可用，回落整段重规划 {}", companionId);
        }
        RddRuntime rt = RUNTIMES.get(companionId);
        if (rt == null) return false;
        TaskChain chain = rt.chain();
        // 预算：失败驱动按 (同伴|一级) 最多 MAX_REPLAN_PER_PRIMARY 次；协商驱动用独立 key+上限。
        String primaryId = chain.currentPrimary().id();
        String key = fromNegotiation
                ? companionId + "|" + primaryId + "|nego"
                : companionId + "|" + primaryId;
        int limit = fromNegotiation ? MAX_NEGOTIATION_REPLANS_PER_PRIMARY : MAX_REPLAN_PER_PRIMARY;
        int used = REPLAN_COUNTS.getOrDefault(key, 0);
        if (used >= limit) {
            long gt = RddInstrumentation.currentGameTimeTicks();
            Map<String, Object> loopData = new LinkedHashMap<>();
            loopData.put("companionId", companionId.toString());
            loopData.put("task", primaryId);
            loopData.put("reason", (fromNegotiation ? "negotiation" : "failure") + " replan budget exhausted (loop)");
            loopData.put("context", Map.of("attempts", used, "threshold", limit, "fromNegotiation", fromNegotiation));
            RddInstrumentation.publishAt(RddInstrumentation.LOOP_DETECTED, loopData, gt);
            if (RddInstrumentation.recentDeath(companionId, gt)) {
                Map<String, Object> recData = new LinkedHashMap<>(loopData);
                recData.put("reason", "recovery attempt after death stalled on replan budget; parking instead");
                RddInstrumentation.publishAt(RddInstrumentation.RECOVERY_FAILED, recData, gt);
            }
            RddMonitor.publish("replan_exhausted", Map.of(
                    "companionId", companionId.toString(), "primary", primaryId,
                    "attempts", used, "reason", "replan budget exhausted; park instead"));
            LOG.warn("[rdd] 重规划预算耗尽，回落停车 {}:{}", companionId, primaryId);
            return false;
        }
        REPLAN_COUNTS.merge(key, 1, Integer::sum);
        try {
            // 2026-09-29 断线修复：协商（士兵在干活途中上报）与卡死是**两种不同来源**，
            // 必须走不同入口。旧代码一律用 enterReplanningFromStuck，它要求当前二级
            // FAILED/STALLED；而士兵唯一合理的上报时机是 RUNNING → 抛异常 → return false
            // → soldierHint（在下方才拼装）永远送不到规划器。实机症状：军师不听士兵。
            if (fromNegotiation) {
                chain.enterReplanningFromNegotiation(reason);
            } else {
                chain.enterReplanningFromStuck(reason);
            }
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 无法进入重规划 {}: {}", companionId, ex.toString());
            return false;
        }
        String theme = chain.currentPrimary().description();
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            chain.resumeFromReplanning();
            return true;
        }
        var snap = planningSnapshot(companionId);
        var level = RiskGate.levelForText(theme);
        var gaps = ResourceBudget.missingFor(level, snap.availableCounts());
        Goal goal = chain.goal();
        var ctx = ReplanContextBuilder.build(goal.description(), primaryId,
                chain.currentSubtask() == null ? null : chain.currentSubtask().id(),
                // 2026-09-29：协商不等于"上一版不可执行"。硬编码 UNKNOWN 会让
                // decomposeSpecsWithHint(attempt>=1) 注入「上一版被判不可执行」的假话，
                // 还会把该假话喂进经验召回查询串 → 召回"改 asset_key 形状"类经验（方向错）。
                FailureEvent.of(null, primaryId,
                        fromNegotiation ? FailureKind.NEGOTIATION : FailureKind.UNKNOWN,
                        reason == null ? "" : reason),
                new java.util.ArrayList<>(chain.satisfiedStages()),
                snap.availableCounts(), level, gaps, java.util.List.of());
        String hint = ReplanContextBuilder.render(ctx);
        if (soldierHint != null && !soldierHint.isBlank()) {
            // 士兵的具体建议（COUNTER 的可选项 / REJECT 的理由）必须进规划器输入，
            // 否则规划器只看到"士兵报了 COUNTER"却拿不到它建议怎么改 → 只能原样重规划。
            hint = hint + "\n\n## 执行层（士兵）明确上报的方案（必须采纳其一或给出不同方案，不要原样重规划）\n"
                    + soldierHint.strip();
        }
        // 2026-09-29「重规划没变化」修复之一：规划器此前**从未看到上一版的完整二级清单**，
        // 它只拿到一个 theme，在同一个信息集上再推一次 → 必然得到同一个结果。
        // 把旧计划显式给它，才能"做出不同的计划"。
        String previous = renderPreviousPlanForReplan(chain);
        if (!previous.isBlank()) {
            hint = hint + "\n\n## 上一版的二级计划（**不要原样重复**，指出它的错处并给出不同做法）\n" + previous;
        }
        // 第三批 N1 消费：把「需求 × 持有对账 + 近期阶段事实」作为只读事实块送进规划器输入。
        // 只陈述事实（缺口数字 ≠「没做」），生成失败绝不影响重规划本身。
        try {
            String factsBlock = RddFactContext.replanBlock(companionId);
            if (!factsBlock.isBlank()) {
                hint = hint + "\n\n" + factsBlock;
            }
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 重规划事实块生成失败 {}: {}", companionId, ex.toString());
        }
        RddCallbackGuard.Ticket ticket = CALLBACKS.replace(companionId);
        // attempt：协商不是"上一版被判不可执行"，故传 0，让 prompt 走非失败分支。
        // completedStages 也不能丢（重规划与懒展开在这一项上曾不一致）。
        RddDecomposer.decomposeSpecsWithHint(companionId, theme, fromNegotiation ? 0 : 1,
                new java.util.ArrayList<>(chain.satisfiedStages()), hint,
                specs -> onServer(server, ticket, () -> {
                    try {
                        if (specs == null || specs.isEmpty()) {
                            chain.resumeFromReplanning();
                            publishTaskSnapshot(companionId, "replan_fallback_resume");
                            return;
                        }
                        java.util.List<Subtask> subs = new java.util.ArrayList<>();
                        // 2026-09-30 深审 R05/codex 两轮：二级 id 原来恒为 `primaryId-rN`，
                        // 每次重规划都复用同一批名字 → 上一轮的 PAUSE/COUNTER 回执
                        // 即使 taskId 相同也能通过消费侧校验，作用到新计划的同 id 二级上。
                        // 计划代次进 id（代次本体在 TaskChain，随链持久化，重启不撞号）。
                        int rev = nextPlanRevision(companionId, chain);
                        for (int i = 0; i < specs.size(); i++) {
                            SubtaskSpec sp = specs.get(i);
                            subs.add(Subtask.hardCoded(primaryId + "-r" + rev + "-" + i,
                                    sp.description(), sp.condition(), sp.body()));
                        }
                        chain.replaceCurrentSubtasks(subs);
                        RddNegotiationInbox.clear(companionId);   // 旧计划的回执一律作废
                        saveRuntimes();
                        publishTaskSnapshot(companionId, "replanned");
                        RddMonitor.publish("replanned", Map.of("companionId", companionId.toString(),
                                "primary", primaryId, "theme", theme,
                                "subtasks", subs.size(), "planRevision", rev));
                    } catch (RuntimeException ex) {
                        LOG.warn("[rdd] 重规划替换失败，回落重跑现有 {}: {}", companionId, ex.toString());
                        try { chain.resumeFromReplanning(); } catch (RuntimeException ignore) { }
                    }
                }));
        return true;
    }

    public static RddRuntime runtime(UUID companionId) {
        return RUNTIMES.get(companionId);
    }

    public static AssetRegistry assets(UUID companionId) {
        if (companionId == null) return new AssetRegistry();
        return ASSETS.computeIfAbsent(companionId, id -> RddAssetStore.load(assetsDir, id));
    }

    /**
     * P1：同伴死亡（含掉装备）→ 立刻让该同伴的背包类资产失效，避免规划/依赖门继续按旧装备放行。
     * 只失效 {@code inventory_scan}，不碰 world_ 基地/结构；下一次背包扫描会把还在身上的重新观测回 OBSERVED。
     *
     * <p>同时记录"床边复活锚点"：死亡瞬间读<b>同伴自己</b>的 respawn 床位（成功 sleep 时由原版写入，
     * 即它自己绑的基地床；同维度且该处确为床），存进 {@link #BED_RESPAWN_PREFERENCE}，
     * 等该同伴 SPAWN（复活）时 TP 到床旁。首建/休眠恢复不走这里，因此不会误触发。
     */
    private static void onCompanionDeath(NumenPlayer body) {
        if (body == null) return;
        UUID companionId = body.getUUID();
        if (companionId == null) return;
        // F2：任何写台账之前先恢复磁盘上的旧账（顺序反了会用旧账覆盖本次记录）
        ensureLedgerLoaded(companionId);
        try {
            int lost = assets(companionId).invalidateByType("inventory_scan");
            // 修正（2026-09-25）：不再 LAST_INVENTORY.remove()。清空缓存会让"死亡后、重扫回 OBSERVED 之前"
            // 触发的重规划读到空背包 → 把已有全套铁装的人重新规划回铁器时代。缓存由 Detector 下一 tick
            // 用复活后的真实背包覆盖（那才是真相）；INVALID 只作为提示（见 PlanningAssetSnapshot）。
            saveAssets(companionId);              // 失效态落盘，跨重启也保持
            BODY.remove(companionId);
            // F6：本次死亡**真正掉了哪些、各多少**。必须读死亡瞬间的真实背包（body 还在），
            // 不能读 LAST_INVENTORY —— 上一轮实机"LOST 没新增"就是读了缓存的锅。
            // 这份 lostItems 是回收判据的批次身份：没有它就只能"任意一件回来即算成功"（假完成）。
            Map<String, Integer> lostItems = deathInstantCounts(body);
            com.dwinovo.numen.rdd.core.RddDeathLedger.Death death = null;
            try {
                death = com.dwinovo.numen.rdd.core.RddDeathLedger.record(
                        companionId, RddInstrumentation.currentGameTimeTicks(),
                        System.currentTimeMillis(), body.blockPosition().toShortString(), lost, lostItems);
            } catch (RuntimeException ledgerFail) {
                LOG.warn("[rdd] 死亡台账写入失败 {}: {}", companionId, ledgerFail.toString());
            }
            if (death != null) {
                saveLedger(companionId);          // F2：写完立刻落盘，死亡记录不留在纯内存里
            }
            Map<String, Object> invalidatedData = new LinkedHashMap<>();
            invalidatedData.put("companionId", companionId.toString());
            invalidatedData.put("reason", "companion_death");
            invalidatedData.put("invalidatedInventoryEntries", lost);
            if (death != null) {
                invalidatedData.put("deathSeq", death.seq());
                invalidatedData.put("deathAt", death.deathAt());
                invalidatedData.put("dropsDespawnInSeconds",
                        death.secondsLeft(RddInstrumentation.currentGameTimeTicks()));
                // 这一条最关键：**东西还没丢**，只是掉在地上。
                invalidatedData.put("dropsStillOnGround", true);
                // 本批次可逐项核对的物品种类数（0 = 旧形状，只能靠 confirmLost/confirmRecovered 显式收口）
                invalidatedData.put("trackedItemKinds", death.lostItems().size());
            }
            RddMonitor.publish("companion_assets_invalidated", invalidatedData);
            LOG.info("[rdd] 同伴死亡：背包资产失效 {} 项", companionId, lost);
            // 埋点：死亡事件（starvation 判据=死亡瞬间食物条为 0，连带 recovery 追踪时间窗）
            long deathTick = RddInstrumentation.currentGameTimeTicks();
            RddInstrumentation.recordDeathTick(companionId, deathTick);
            boolean starving = isStarvingDeath(body);
Map<String, Object> deathData = new LinkedHashMap<>();
            deathData.put("companionId", companionId.toString());
            deathData.put("task", currentTaskId(companionId));
            // ── 死因结构化（2026-10-01 本轮新增）────────────────────────────
            // 老字段 reason 只有 starvation/other 二值，把「掉岩浆里/淹死/摔死/被怪打死/
            // 被玩家打死」全塌缩成 other —— 而"被玩家打死"和"被怪打死"归因方向相反
            // （前者是主人插手，后者是战斗经验不足），混在一起就没法写经验。
            //
            // 现在三样都带上：
            //   deathCauseId     原版 msgId，与语言无关，可直接聚合（本轮的根治点）
            //   deathCauseMsg    渲染后的中文句子，给人/AI 看
            //   deathKind        归类后的短标签，机器可聚合
            //   deathAttacker    凶手名字（玩家名或怪物名），用于区分"谁杀的"
            //
            // 为什么要 id + 归类两样：只有句子时 48% 是「rdd被杀死了」这种无凶手兜底
            // （原版拿不到凶手时的降级文案），拿它归因等于一半死亡天生没法总结经验；
            // NumenPlayer.die() 里现成的 cause.getMsgId() 不受那个清空影响。
            String deathCauseId = safeDeathCauseId(body);
            String deathCauseMsg = safeDeathMessage(body);
            // 2026-10-27（D-04 兜底）：原版拿不到凶手时给的是 genericKill / "RDD was killed"，
            // 8/52 次死亡因此归因 unknown。死亡那一刻实体身上还有**最后一次伤害来源**
            // （msgId + 直接来源实体名），拿它做只降级不编造的兜底；仍判不出就仍是 unknown。
            String lastDamageId = "";
            String lastDamageName = "";
            try {
                var lastDamage = body.getLastDamageSource();
                if (lastDamage != null) {
                    lastDamageId = String.valueOf(lastDamage.getMsgId());
                    var direct = lastDamage.getDirectEntity();
                    var causing = lastDamage.getEntity();
                    var named = direct != null ? direct : causing;
                    lastDamageName = named == null ? "" : named.getName().getString();
                }
            } catch (RuntimeException ignored) {
                // 读不到就是读不到：留空，绝不编一个凶手出来
            }
            String deathKind = com.dwinovo.numen.rdd.core.DeathCauseClassifier
                    .classifyWithFallback(deathCauseId, deathCauseMsg, lastDamageId, lastDamageName);
            deathData.put("deathCauseId", deathCauseId);
            deathData.put("deathCauseMsg", deathCauseMsg);
            deathData.put("deathKind", deathKind);
            if (!lastDamageId.isBlank()) {
                deathData.put("lastDamageId", lastDamageId);
            }
            if (!lastDamageName.isBlank()) {
                deathData.put("lastDamageName", lastDamageName);
            }
            String deathAttacker = safeDeathAttacker(body);
            deathData.put("deathAttacker", deathAttacker);
            // reason 保持老语义（starvation 优先，事件选择逻辑 publishAt 依赖它），
            // 其余情况改用归类结果 —— 这样 reason 自己也变得可聚合了。
            deathData.put("reason", starving ? "starvation" : deathKind);
            // ★ 2026-10-26（V-20261026-07 缺口）：死亡上下文原来只有背包/失效条目，
            //   复盘根本看不到“这是夜里”——于是经验只能写“低血/无甲”，写不出“夜间/没睡”。
            //   这里补时间与环境事实（只读、从死亡瞬间的实体取，不推断）。
            long dayTime = Math.floorMod(body.level().getDayTime(), 24_000L);
            boolean isNight = dayTime >= 13_000L && dayTime < 23_000L;
            var deathPos = body.blockPosition();
            // 床锚点：未绑床时原版可能返回 BlockPos.ZERO 哨兵，必须显式挡掉。
            BlockPos respawnBed = body.getRespawnPosition();
            boolean hasBed = respawnBed != null && !respawnBed.equals(BlockPos.ZERO);
            String bedDimension = "none";
            double bedDistance = -1.0;
            int bedX = -1, bedY = -1, bedZ = -1;
            if (hasBed) {
                var bedDimKey = body.getRespawnDimension();
                bedDimension = bedDimKey == null ? "unknown" : bedDimKey.location().toString();
                bedX = respawnBed.getX();
                bedY = respawnBed.getY();
                bedZ = respawnBed.getZ();
                if (bedDimension.equals(body.level().dimension().location().toString())) {
                    bedDistance = Math.sqrt(deathPos.distSqr(respawnBed));
                }
            }
            ServerLevel deathLevel = body.serverLevel();
            int blockLight = -1;
            int skyLight = -1;
            boolean canSeeSky = false;
            if (deathLevel != null) {
                blockLight = deathLevel.getBrightness(LightLayer.BLOCK, deathPos);
                skyLight = deathLevel.getBrightness(LightLayer.SKY, deathPos);
                canSeeSky = deathLevel.canSeeSky(deathPos);
            }
            deathData.put("context", Map.ofEntries(
                    Map.entry("lastObservedInventory", lastInventory(companionId)),
                    Map.entry("invalidatedEntries", lost),
                    Map.entry("dayTime", dayTime),
                    Map.entry("isNight", isNight),
                    Map.entry("dimension", body.level().dimension().location().toString()),
                    Map.entry("posX", deathPos.getX()),
                    Map.entry("posY", deathPos.getY()),
                    Map.entry("posZ", deathPos.getZ()),
                    Map.entry("hasBed", hasBed),
                    Map.entry("bedDimension", bedDimension),
                    Map.entry("bedX", bedX),
                    Map.entry("bedY", bedY),
                    Map.entry("bedZ", bedZ),
                    Map.entry("bedDistance", bedDistance),
                    Map.entry("blockLight", blockLight),
                    Map.entry("skyLight", skyLight),
                    Map.entry("canSeeSky", canSeeSky),
                    Map.entry("sleeping", body.isSleeping())));
            RddInstrumentation.publishAt(starving ? RddInstrumentation.STARVATION_DEATH : RddInstrumentation.DEATH,
                    deathData, deathTick);
            // P2.1：把死亡瞬间身上"值得记住"的资产记为 LOST（保留最后位置），供重规划判断"能否回去取"。
            recordDeathLostHistory(companionId, body);
            // 【架构概念 1/4 接线】死亡 -> 生成"支线任务"（取备用/捡包），不整链重规划。
            // 存在的理由（用户 2026-09-28 点名）：小问题不该去烧重规划预算（每级只有 3 次，烧完就停车）。
            try {
                RddRuntime rt = RUNTIMES.get(companionId);
                String goalId = (rt != null && rt.chain().goal() != null) ? rt.chain().goal().id() : null;
                // 把死亡点传下去：掉落物约 5 分钟就 despawn，不告诉它坐标它就不知道去哪捡
                // （用户 2026-09-29 实测：只捡回一部分，过 5 分钟有些就没了）。
                String deathAt = body.blockPosition().toShortString();
                // 传整张台账而不是单个坐标：连死两次时两个掉落点的到期时刻不同，
                // 只给最后一个坐标就分不清"哪一次还来得及捡"。
                String dropTimeline = "";
                try {
                    dropTimeline = com.dwinovo.numen.rdd.core.RddDeathLedger.render(
                            companionId, RddInstrumentation.currentGameTimeTicks());
                } catch (RuntimeException ignored) { /* 台账不可用不阻断死亡支线 */ }
                // F4a：短时间同点又死一次 → 死亡支线顺序改成"先撤离/补给再回收"。
                // 5 分钟掉落窗口本身不变（那是原版事实），只改她先去哪。
                // currentSeq 必须传进去排除本次死亡自己（否则刚记的那条与自己距离 0 = 自我匹配）。
                boolean repeat = publishRepeatDeathHint(body, companionId, death == null ? -1 : death.seq());
                RddRepairDispatch.onDeath(companionId, goalId, deathAt, dropTimeline, repeat, death);
            } catch (RuntimeException ex) {
                LOG.warn("[rdd] 支线任务生成失败 {}: {}", companionId, ex.toString());
            }
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 死亡资产失效处理失败 {}: {}", companionId, ex.toString());
        }
        recordSelfBedAnchor(body, companionId);
    }

    /** 当前执行中的二级/任务 id（无链或异常 → 空串），埋点关联任务字段用。 */
    private static String currentTaskId(UUID companionId) {
        try {
            RddRuntime rt = RUNTIMES.get(companionId);
            if (rt != null && rt.chain().currentSubtask() != null) {
                return rt.chain().currentSubtask().id();
            }
        } catch (RuntimeException ignore) {
            // fall through
        }
        return "";
    }

    /** 死亡即饿死代理判据（埋点）：死亡瞬间食物条为 0 → 判 starvation。best-effort，失败回落 false。 */
    /**
     * 读死因的三个 best-effort 取值器（2026-10-01 新增）。
 *
 * <p><b>为什么统一成「失败回空串」而不是抛异常</b>：它们在 {@code onCompanionDeath} 的死亡处理链上，
 * 而那个链一抛异常会连带丢掉 {@code RddRepairDispatch.onDeath} 生成的捡包支线
 * （掉落物只有约 5 分钟存活窗口，捡包支线丢了就是真丢东西）。
 * → <b>死因拿不到不能影响「去把东西捡回来」。</b>
 */
private static String safeDeathCauseId(NumenPlayer body) {
    try {
        String v = (body == null) ? null : body.deathCauseId();
        return v == null ? "" : v;
    } catch (RuntimeException ignore) {
        return "";
    }
}

private static String safeDeathMessage(NumenPlayer body) {
    try {
        String v = (body == null) ? null : body.deathMessage();
        return v == null ? "" : v;
    } catch (RuntimeException ignore) {
        return "";
    }
}

private static String safeDeathAttacker(NumenPlayer body) {
    try {
        String v = (body == null) ? null : body.deathCauseAttacker();
        return v == null ? "" : v;
    } catch (RuntimeException ignore) {
        return "";
    }
}

/** 死亡即饿死代理判据（埋点）：死亡瞬间食物条为 0 → starvation。best-effort，失败回 false */
    private static boolean isStarvingDeath(NumenPlayer body) {
        try {
            var food = body.getFoodData();
            return food != null && food.getFoodLevel() == 0;
        } catch (RuntimeException ignore) {
            return false;
        }
    }

    /**
     * 死亡瞬间的真实背包计数（F6 批次身份的数据源）。
     *
     * <p><b>2026-09-30 实机修正：光读 body 拿不到</b>。第一次实现只读
     * {@code RddDetector.countInventory(body)}，实机事件里 {@code trackedItemKinds=0}
     * —— 死亡事件触发时身体的物品栏已经被原版处理掉了，逐项核对因此完全失效
     * （判据退化成"不可核对"，F6 等于没修）。
     *
     * <p>所以改成**先读 body，读空则回落到最后一次真实扫描的缓存**
     * （{@code LAST_INVENTORY} 由 Detector 每次 tick 写入，死亡那一刻仍然有效）。
     * 上一轮笔记担心的"REMOVE 会清空缓存"针对的是稍后的 {@code CompanionEvent.REMOVE}，
     * 而这里跑在更早的 {@code DEATH} 上，实测缓存仍在。
     *
     * <p>两者都空才返回空表 —— 那一批不可逐项核对，判据只会更保守，不会更松。
     */
    private static Map<String, Integer> deathInstantCounts(NumenPlayer body) {
        try {
            Map<String, Integer> live = RddDetector.countInventory(body);
            if (live != null && !live.isEmpty()) return Map.copyOf(live);
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 读死亡瞬间背包失败，回落到缓存: {}", ex.toString());
        }
        try {
            Map<String, Integer> cached = lastInventory(body.getUUID());
            if (!cached.isEmpty()) {
                LOG.info("[rdd] 死亡瞬间背包用缓存兜底：{} 项（cachedAt={}）",
                        cached.size(), lastInventoryAtMillis(body.getUUID()));
                return cached;
            }
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 读死亡背包缓存失败 {}: {}", ex.toString());
        }
        return Map.of();
    }

    /**
     * F4a：短时间、同一点附近<b>上一次</b>又死一次 → 本次死亡支线改成"先撤离/补给再回收"。
     *
     * <p>实机依据（2026-09-30 19:22 / 19:23）：#3 与 #4 只隔 62 秒、坐标差 1 格，
     * 复活后没补给没撤离就又回了同一个危险点。
     *
     * <p>判据本身在 rdd-core（纯函数、可单测），这里只负责取坐标 + 发事件。
     * <b>不改</b> 5 分钟掉落窗口 —— 那是原版事实，不是决策。
     *
     * @param currentSeq 本次死亡自己的 seq（必须传给判据排除，见 {@code repeatDeathWithin} 的说明）
     */
    private static boolean publishRepeatDeathHint(NumenPlayer body, UUID companionId, int currentSeq) {
        try {
            BlockPos at = body.blockPosition();
            com.dwinovo.numen.rdd.core.RddDeathLedger.Death previous =
                    com.dwinovo.numen.rdd.core.RddDeathLedger.repeatDeathWithin(
                            companionId, RddInstrumentation.currentGameTimeTicks(),
                            at.getX(), at.getY(), at.getZ(),
                            REPEAT_DEATH_WINDOW_TICKS, REPEAT_DEATH_RADIUS_BLOCKS,
                            currentSeq);
            if (previous == null) return false;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("companionId", companionId.toString());
            data.put("previousDeathSeq", previous.seq());
            data.put("previousDeathAt", previous.deathAt());
            data.put("nowAt", at.toShortString());
            data.put("secondsSincePrevious",
                    (RddInstrumentation.currentGameTimeTicks() - previous.gameTime()) / 20L);
            data.put("windowSeconds", REPEAT_DEATH_WINDOW_TICKS / 20L);
            data.put("radiusBlocks", REPEAT_DEATH_RADIUS_BLOCKS);
            data.put("action", "recover_self_first_then_collect_drops");
            data.put("note", "same spot, twice in a row: resupply and get clear BEFORE going back for drops");
            RddMonitor.publish("repeat_death_nearby", data);
            LOG.warn("[rdd] 重复死亡 {}（上次 {}，{} 秒前）→ 恢复顺序改为先撤离/补给",
                    companionId, previous.deathAt(),
                    (RddInstrumentation.currentGameTimeTicks() - previous.gameTime()) / 20L);
            return true;
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 重复死亡判定失败 {}: {}", companionId, ex.toString());
            return false;
        }
    }

    /**
     * 记录同伴<b>自己绑的</b>床（不是主人的）：读同伴自己的 respawn 床位——成功 sleep 时原版
     * {@code ServerPlayer.startSleepInBed} 会把它设成自己的重生点，即"自由绑定自己的基地"。
     *
     * <p>F4b：维度不再靠"当场推断后静默 return"。
     * <b>语义不变</b>（仍只在同维度使用床锚点，真跨维度复活不在本轮），
     * 改的是可诊断性：床在别的维度 → 发 {@code bed_anchor_dimension_mismatch}，
     * 而不是让"床偏好为什么没生效"永远查不出来。
     */
    private static void recordSelfBedAnchor(NumenPlayer body, UUID companionId) {
        try {
            BlockPos bed = body.getRespawnPosition();
            if (bed == null || bed.equals(BlockPos.ZERO)) return;
            var bedDimension = body.getRespawnDimension();
            ServerLevel level = body.serverLevel();
            if (bedDimension == null || level == null) return;
            String bedDimensionId = bedDimension.location().toString();
            if (!level.dimension().equals(bedDimension)) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("companionId", companionId.toString());
                data.put("bed", bed.toShortString());
                data.put("bedDimension", bedDimensionId);
                data.put("currentDimension", level.dimension().location().toString());
                data.put("note", "bed anchor kept but not used while the companion is in another dimension; "
                        + "cross-dimension respawn is not implemented (by design: the companion respawns with its owner)");
                RddMonitor.publish("bed_anchor_dimension_mismatch", data);
                LOG.info("[rdd] {} 床锚点在 {}，同伴当前在 {} → 仅记录不启用（跨维度复活不在本轮范围）",
                        companionId, bedDimensionId, level.dimension().location().toString());
                return;
            }
            if (!(level.getBlockState(bed).getBlock() instanceof BedBlock)) return;
            BED_RESPAWN_PREFERENCE.put(companionId, new BedAnchor(bed.immutable(), bedDimensionId));
            LOG.info("[rdd] 记录 {} 自绑床位: {} @ {}", companionId, bed, bedDimensionId);
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 记录自绑床位失败 {}: {}", companionId, ex.toString());
        }
    }

    /** 床边安全落点：优先 BedBlock 标准站立位，异常/无解时回落床心（X/Z 偏移 0.5）。 */
    private static Vec3 bedStandPos(ServerLevel level, BlockPos bed, NumenPlayer body) {
        try {
            BlockState state = level.getBlockState(bed);
            if (state.getBlock() instanceof BedBlock) {
                var facing = state.getValue(BedBlock.FACING);
                var stand = BedBlock.findStandUpPosition(body.getType(), level, bed, facing, body.getRespawnAngle());
                if (stand.isPresent()) return stand.get();
            }
        } catch (RuntimeException ignore) {
            // 落回下面的兜底坐标
        }
        return new Vec3(bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5);
    }

    /** P0 完成事实仓库（磁盘为真身，内存缓存）。 */
    public static CompletedFactStore facts(UUID companionId) {
        if (companionId == null) return new CompletedFactStore();
        return FACTS.computeIfAbsent(companionId, id -> RddFactStore.load(factsDir, id));
    }

    /** P2.1 资产历史仓库（磁盘为真身，内存缓存）；Lost≠Gone 的长期恢复线索。 */
    public static AssetHistory history(UUID companionId) {
        if (companionId == null) return new AssetHistory();
        return HISTORY.computeIfAbsent(companionId, id -> RddHistoryStore.load(historyDir, id));
    }

    /** 落盘资产历史（失败只记日志，不影响主流程）。 */
    static void saveHistory(UUID companionId) {
        if (companionId == null || historyDir == null) return;
        try {
            RddHistoryStore.save(historyDir, companionId, history(companionId));
        } catch (IOException ex) {
            LOG.warn("[rdd] 保存资产历史失败 {}: {}", companionId, ex.toString());
        }
    }

    // ---- F2 死亡台账落盘（磁盘为真身） ----

    /**
     * 确保某同伴的死亡台账已从磁盘恢复，<b>只恢复一次</b>。
     *
     * <p>调用时机：任何接触台账的入口之前（死亡回调、判据查询、渲染）。
     * 必须"先恢复后使用" —— 反过来会用旧账覆盖本次运行刚记的死亡，
     * 那是比丢账更坏的假"没死过"。
     */
    static void ensureLedgerLoaded(UUID companionId) {
        if (companionId == null) return;
        if (!LEDGER_LOADED.add(companionId)) return;   // 已恢复过：绝不二次覆盖
        try {
            RddDeathLedger.LedgerSnapshot loaded = RddDeathLedgerStore.load(ledgerDir, companionId);
            RddDeathLedger.restore(companionId, loaded);
            if (!loaded.deaths().isEmpty()) {
                LOG.info("[rdd] 恢复 {} 的死亡台账 {} 条", companionId, loaded.deaths().size());
            }
        } catch (RuntimeException ex) {
            // 恢复失败 = 按"没死过"处理，但必须留痕（静默丢账正是本项要消灭的缺陷）
            warnLedgerStore("ledger_load_failed", Map.of(
                    "companionId", companionId.toString(), "error", String.valueOf(ex)));
        }
    }

    /** 台账变更后落盘（死亡记录写入 / 判为已回收 / 已确认丢失后各调一次）。 */
    static void saveLedger(UUID companionId) {
        if (companionId == null || ledgerDir == null) return;
        try {
            RddDeathLedgerStore.save(ledgerDir, companionId, RddDeathLedger.snapshot(companionId));
        } catch (IOException | RuntimeException ex) {
            warnLedgerStore("ledger_save_failed", Map.of(
                    "companionId", companionId.toString(), "error", String.valueOf(ex)));
        }
    }

    /**
     * 台账落盘异常的<b>唯一</b>出口：日志 + 监测台事件（fail-soft 但绝不无声）。
     *
     * <p>注意这里 catch 的是 {@link Throwable}，不是 {@code RuntimeException}：
     * 单测/裁剪 classpath 下 {@code RddMonitor} 会抛 {@link NoClassDefFoundError}，
     * 那是 {@code Error} —— 用 {@code RuntimeException} 的话"fail-soft 的兜底自己会炸"，
     * 正好把最该保住的死亡路径带崩（单测 {@code RddDeathLedgerStoreTest} 已实测到这个形状）。
     */
    static void warnLedgerStore(String event, Map<String, ?> data) {
        try {
            LOG.warn("[rdd] 死亡台账落盘异常 {}: {}", event, data);
        } catch (Throwable ignored) {
            // 日志都不可用就只剩静默：仍不得把异常抛给调用方
        }
        try {
            RddMonitor.publish(event, new java.util.LinkedHashMap<>(data));
        } catch (Throwable ignored) {
            // 监测台不可用（无 MC 运行时/类路径裁剪）时只留日志，绝不让埋点把主流程带崩
        }
    }

    /**
     * P2.1：把当前背包里"值得记住"的条目记为 CURRENT（观测到=还持有）。
     * 只记有恢复价值的（装备/工具/食物），过滤泥土等杂物。
     */
    static void recordHistoryCurrent(UUID companionId, Map<String, Integer> inventory) {
        if (companionId == null || inventory == null || inventory.isEmpty()) return;
        try {
            AssetHistory h = history(companionId);
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Integer> e : inventory.entrySet()) {
                if (!AssetHistory.worthRemembering(e.getKey(), AssetHistory.Purpose.UNKNOWN)) continue;
                if (e.getValue() == null || e.getValue() <= 0) continue;
                h.recordCurrent(e.getKey(), AssetHistory.Purpose.UNKNOWN, e.getValue(),
                        null, 0, 0, 0, now);
            }
            saveHistory(companionId);
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 记录资产历史(CURRENT)失败 {}: {}", companionId, ex.toString());
        }
    }

    /**
     * P2.1：死亡/掉落时把一件有意义资产记为 LOST，保留最后已知位置（供恢复线索）。
     */
    static void recordHistoryLost(UUID companionId, String assetId, Integer lastCount,
                                  String dimension, BlockPos pos) {
        if (companionId == null || assetId == null || !AssetHistory.worthRemembering(assetId, AssetHistory.Purpose.UNKNOWN)) {
            return;
        }
        try {
            history(companionId).recordLost(assetId, AssetHistory.Purpose.UNKNOWN, lastCount,
                    dimension, pos == null ? 0 : pos.getX(), pos == null ? 0 : pos.getY(),
                    pos == null ? 0 : pos.getZ(), System.currentTimeMillis());
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 记录资产历史(LOST)失败 {}: {}", companionId, ex.toString());
        }
    }

    /**
     * P2.1：死亡时把身上值得记住的资产逐条记为 LOST（带最后位置），并落盘一次。
     * 位置取死亡瞬间的同伴坐标（最近一次已知地点）。
     */
    private static void recordDeathLostHistory(UUID companionId, NumenPlayer body) {
        // 用"死亡瞬间的真实背包"（body 还在）；缓存可能为空（V3 实测 LOST 未新增的根因）。
        Map<String, Integer> inv = RddDetector.countInventory(body);
        if (inv.isEmpty()) {
            inv = lastInventory(companionId);
        }
        if (inv.isEmpty()) return;
        String dimension = null;
        BlockPos pos = null;
        try {
            if (body.serverLevel() != null) dimension = body.serverLevel().dimension().location().toString();
            pos = body.blockPosition();
        } catch (RuntimeException ignore) {
            // 位置不可得则记 null 位置（仍保留"曾拥有"事实）
        }
        for (Map.Entry<String, Integer> e : inv.entrySet()) {
            recordHistoryLost(companionId, e.getKey(), e.getValue(), dimension, pos);
        }
        saveHistory(companionId);
    }

    /** P2.1：渲染"可恢复线索"块供规划提示词（无则空串）。 */
    static String recoverableContext(UUID companionId) {
        try {
            var rec = history(companionId).recoverable();
            if (rec.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("【可恢复线索（曾拥有、暂不可用；优先判断能否回去取，而不是从零重造）】\n");
            for (var e : rec) sb.append("- ").append(e.render()).append('\n');
            return sb.toString();
        } catch (RuntimeException ex) {
            return "";
        }
    }

    /** 记录"某战略阶段已被可靠完成"并落盘（P0）。失败只记日志，不影响主流程。 */
    static void recordStageFact(UUID companionId, Goal goal, String primaryDescription) {
        if (companionId == null || goal == null || primaryDescription == null) return;
        try {
            CompletedFactStore store = facts(companionId);
            store.recordStage(goal, primaryDescription, System.currentTimeMillis(), "primary confirmed");
            RddFactStore.save(factsDir, companionId, store);
        } catch (IOException ex) {
            LOG.warn("[rdd] 保存完成事实失败 {}: {}", companionId, ex.toString());
        }
    }

    /** 留档一条二级完成细节（辅助，不用于恢复）；随阶段落盘一起持久化。 */
    static void recordSubtaskFact(UUID companionId, Goal goal, String primaryDescription, String subtaskDescription) {
        if (companionId == null || goal == null) return;
        facts(companionId).recordSubtask(goal, primaryDescription, subtaskDescription, System.currentTimeMillis());
    }

    static String planningAssets(UUID companionId) {
        return RddAssetContext.render(assets(companionId), 2000);
    }

    /** P2-D：已观测村庄的事实块（先事实，不含策略）；补给规划提示词用。 */
    static String villageContext(UUID companionId) {
        return com.dwinovo.numen.rdd.core.VillageNode.render(
                com.dwinovo.numen.rdd.core.VillageNode.extract(assets(companionId)));
    }

    /**
     * P2-A【A+C 统一入口】唯一规划资产口径：先<b>同步刷新实时背包</b>再生成快照。
     *
     * <p>修的是首次 /goal 时序：`/goal → beginPlanning → planStages` 早于链建立，Detector 的
     * tickRuntime（要求 rt!=null）还没跑过 → lastInventory 为空 → 规划/判定都读到空背包。
     * 这里若拿得到当前 server，就在规划前用同伴实体直接 countInventory 刷新一次；
     * 拿不到（非服务端线程/无 server）则回落到缓存口径（不劣化）。
     *
     * <p>注意：本方法可能被客户端决策线程调用，故刷新全部包在 try-catch 内，任何异常都不影响主流程。
     */
    public static com.dwinovo.numen.rdd.core.PlanningAssetSnapshot planningSnapshot(UUID companionId) {
        refreshInventoryFromLive(companionId);
        return com.dwinovo.numen.rdd.core.PlanningAssetSnapshot.from(
                lastInventory(companionId), lastInventoryAtMillis(companionId), assets(companionId));
    }

    /** 【A】规划/判定前同步刷新实时背包（拿得到 server 就刷；失败静默回落缓存）。 */
    private static void refreshInventoryFromLive(UUID companionId) {
        if (companionId == null) return;
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return;
            NumenPlayer ap = NumenPlayer.findByUuid(server, companionId);
            if (ap != null) {
                cacheInventory(companionId, RddDetector.countInventory(ap));
            }
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 规划前实时刷新背包失败 {}: {}", companionId, ex.toString());
        }
    }

    /**
     * 【A+C 统一入口】带 server 的规划快照：先<b>同步刷新实时背包</b>再生成快照。
     *
     * <p>修的是首次 /goal 时序：`/goal → beginPlanning → planStages` 早于链建立，Detector 的
     * tickRuntime（要求 rt!=null）还没跑过 → lastInventory 为空 → 规划/判定都读到空背包。
     * 这里在规划前用同伴实体直接 countInventory 刷新，保证「规划此刻」的持有真相可用。
     * 找不到同伴/缺少 server 时回落到缓存口径（不劣化）。
     */
    /**
     * 【A+C 统一入口·显式 server 版】供已知 server 的调用点（如 beginPlanning）使用：
     * 先按给定 server 同步刷新实时背包，再生成快照。等价于无参重载（其内部自动取当前 server）。
     */
    public static com.dwinovo.numen.rdd.core.PlanningAssetSnapshot planningSnapshot(UUID companionId,
                                                                                   MinecraftServer server) {
        if (companionId != null && server != null) {
            try {
                NumenPlayer ap = NumenPlayer.findByUuid(server, companionId);
                if (ap != null) {
                    cacheInventory(companionId, RddDetector.countInventory(ap));
                }
            } catch (RuntimeException ex) {
                LOG.warn("[rdd] 规划前实时刷新背包失败 {}: {}", companionId, ex.toString());
            }
        }
        return com.dwinovo.numen.rdd.core.PlanningAssetSnapshot.from(
                lastInventory(companionId), lastInventoryAtMillis(companionId), assets(companionId));
    }

    /** 清某同伴的重规划预算计数（重绑/清任务时调用）。 */
    private static void clearReplanCounts(UUID companionId) {
        if (companionId == null) return;
        String prefix = companionId + "|";
        REPLAN_COUNTS.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /**
     * 每个同伴的<b>计划代次</b>的唯一真源是 {@link TaskChain#nextPlanRevision()}（随链持久化）。
     *
     * <p>2026-09-30 深审 R05/codex 两轮抓出：宿主内存里另存一份计数是错的 ——
     * 停服即清空，而磁盘链里的 id 仍带旧 rev，重启后第一次重规划会**撞号**。
     * 所以这里只保留一个取数口，代次本体在 TaskChain 里。
     */
    private static int nextPlanRevision(UUID companionId, TaskChain chain) {
        return chain == null ? 1 : chain.nextPlanRevision();
    }

    /**
     * 设置某同伴的床边复活锚点（同伴自绑床位；一般由死亡路径自动写入，也可手动设）。
     *
     * <p>F4b：锚点必须带维度。手动设置时用同伴<b>当前所在</b>的维度；
     * 维度不匹配的锚点在 SPAWN 时会被显式跳过并埋 {@code bed_respawn_skipped_dimension}。
     */
    public static void setBedRespawnPreference(UUID companionId, BlockPos bedPos) {
        if (companionId == null || bedPos == null) {
            return;
        }
        String dimensionId = "unknown";
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                NumenPlayer body = NumenPlayer.findByUuid(server, companionId);
                if (body != null && body.serverLevel() != null) {
                    dimensionId = body.serverLevel().dimension().location().toString();
                }
            }
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 设置床锚点时读维度失败 {}: {}", companionId, ex.toString());
        }
        BED_RESPAWN_PREFERENCE.put(companionId, new BedAnchor(bedPos.immutable(), dimensionId));
        LOG.info("[rdd] 设置 {} 的床边复活偏好: {} @ {}", companionId, bedPos, dimensionId);
    }

    /** 清除某同伴的床边复活偏好。 */
    public static void clearBedRespawnPreference(UUID companionId) {
        if (companionId == null) return;
        BED_RESPAWN_PREFERENCE.remove(companionId);
        LOG.info("[rdd] 清除 {} 的床边复活偏好", companionId);
    }

    /** 记录某同伴最近一次背包计数（Detector 心跳写）。null/空安全。 */
    public static void cacheInventory(UUID companionId, Map<String, Integer> counts) {
        if (companionId != null) {
            LAST_INVENTORY.put(companionId, counts == null ? Map.of() : Map.copyOf(counts));
            LAST_INVENTORY_AT.put(companionId, System.currentTimeMillis());
        }
    }

    /** 最近一次背包快照（可能为空 = 从未观测到该同伴背包）。不可变。 */
    public static Map<String, Integer> lastInventory(UUID companionId) {
        if (companionId == null) {
            return Map.of();
        }
        return LAST_INVENTORY.getOrDefault(companionId, Map.of());
    }

    /** 最近一次背包扫描时刻（系统毫秒）；从未扫描过 → null。规划声明 verified_at 用。 */
    public static Long lastInventoryAtMillis(UUID companionId) {
        return companionId == null ? null : LAST_INVENTORY_AT.get(companionId);
    }

    public static boolean decomposing(UUID companionId) {
        return companionId != null && DECOMPOSING.contains(companionId);
    }

    public static BodyState bodyState(UUID companionId) {
        return companionId == null ? null : BODY.get(companionId);
    }

    public static void rememberBody(UUID companionId, String subtaskId, int submitCount) {
        if (companionId != null && subtaskId != null) {
            BODY.put(companionId, new BodyState(subtaskId, submitCount));
        }
    }

    public static void clearBody(UUID companionId) {
        if (companionId != null) BODY.remove(companionId);
    }

    public static String nextBodyCallId() {
        return "rdd-" + BODY_CALLS.incrementAndGet();
    }

    /** 这个同伴身上还有没有 RDD 的任务状态（内存里的任何一样）。/goal clear 据它如实回报。 */
    static boolean hasTaskState(UUID companionId) {
        return companionId != null && (RUNTIMES.containsKey(companionId)
                || DECOMPOSING.contains(companionId)
                || BODY.containsKey(companionId));
    }

    public static void remove(UUID companionId) {
        if (companionId == null) return;
        RddCallbackGuard.Ticket ticket = CALLBACKS.replace(companionId);
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) onServer(server, ticket, () -> removeCurrent(companionId));
    }

    private static void removeCurrent(UUID companionId) {
        if (companionId == null) return;
        try {
            RddTaskHandoff.remove(tasksDir, companionId, () -> {
                DECOMPOSING.remove(companionId);
                RddRuntime previous = RUNTIMES.get(companionId);
                if (previous != null) {
                    publishTaskSnapshot(companionId, "task_removed");
                }
                RUNTIMES.remove(companionId);
                BODY.remove(companionId);
                LAST_CONTEXT.remove(companionId);
                LAST_EXPANSION_REPORT.remove(companionId);
                KICKOFF_SENT.remove(companionId);
                SideTaskHost.cancel(companionId, "task cleared"); // 清目标 = 支线作废
                clearNudgeThrottle(companionId); // 链都收了，节流记录跟着走（否则新目标开局就被节流）
                RddGoalDriver.clear(companionId); // 目标清/重绑 → 丢掉该同伴的懒展开状态
                clearReplanCounts(companionId);
                RddFactContext.forget(companionId); // 任务清空 → 事实接线缓存一并清（防缓慢增长）
                LOG.info("[rdd] 已清除任务及磁盘交接 {}", companionId);
            });
        } catch (IOException ex) {
            // Do not report a successful removal while restoreRuntimes can still reload the file.
            LOG.error("[rdd] 清除磁盘任务失败，内存任务保留，需处理文件后重试清除 {}: {}", companionId, ex.toString());
            RddMonitor.publish("task_clear_failed", Map.of(
                    "companionId", companionId.toString(), "reason", ex.toString(),
                    "memoryRetained", true));
        }
    }

    static RddCallbackGuard.Ticket planningTicket(UUID companionId) {
        return CALLBACKS.current(companionId);
    }

    /**
     * 把「上一版的二级清单」渲染给规划器（2026-09-29「重规划没变化」修复）。
     *
     * <p>为什么需要：重规划时规划器只拿到一级 theme，**从未看到旧计划长什么样**。
     * 在同一个信息集上再推一次，必然得到同一个结果 —— 实机就是「重规划和之前
     * 基本没什么区别」。显式给出旧计划后，它才有对照物可说「这里不对，换个做法」。
     *
     * <p>有界：最多 {@value #REPLAN_PLAN_ECHO_MAX} 条、每条描述截断，避免把规划
     * prompt 撑爆（这本身也是「上下文瘦身」要管的量）。
     */
    private static final int REPLAN_PLAN_ECHO_MAX = 12;
    private static final int REPLAN_PLAN_DESC_MAX = 120;

    private static String renderPreviousPlanForReplan(TaskChain chain) {
        try {
            PrimaryGoal primary = chain.currentPrimary();
            if (primary == null || primary.subtasks() == null || primary.subtasks().isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (Subtask s : primary.subtasks()) {
                if (s == null || shown >= REPLAN_PLAN_ECHO_MAX) {
                    break;
                }
                String desc = s.description() == null ? "" : s.description().trim();
                if (desc.length() > REPLAN_PLAN_DESC_MAX) {
                    desc = desc.substring(0, REPLAN_PLAN_DESC_MAX) + "…";
                }
                sb.append("  ").append(shown + 1).append(". ").append(desc);
                java.util.Map<String, Object> cond = s.condition();
                if (cond != null && !cond.isEmpty()) {
                    sb.append("  [条件 ").append(cond.keySet()).append("]");
                }
                sb.append('\n');
                shown++;
            }
            if (shown == 0) {
                return "";
            }
            if (primary.subtasks().size() > shown) {
                sb.append("  …（另有 ").append(primary.subtasks().size() - shown).append(" 条未列）\n");
            }
            return sb.toString().strip();
        } catch (RuntimeException e) {
            // 渲染旧计划失败不该阻断重规划：没有对照物只是质量下降，不是功能中断
            LOG.warn("[rdd] 渲染上一版计划失败: {}", e.toString());
            return "";
        }
    }

    /** Revalidate on the original server: an old world's completion never enters a new world. */
    static void onServer(MinecraftServer server, RddCallbackGuard.Ticket ticket, Runnable callback) {
        CALLBACKS.dispatch(ticket, action -> {
            if (server.isSameThread()) action.run();
            else server.execute(action);
        }, () -> {
            if (ServerLifecycleHooks.getCurrentServer() == server) callback.run();
        });
    }

    /** RDD 是否允许自动提交身体工具。assist 协助模式下 false(工具执行交还 NUMEN)。 */
    public static boolean bodySubmissionEnabled() {
        return bodySubmissionEnabled;
    }

    /** 设置 RDD 自动工具提交开关。assist=true 时调用 setBodySubmissionEnabled(false) 防双驾驶。 */
    public static void setBodySubmissionEnabled(boolean on) {
        bodySubmissionEnabled = on;
    }

    /**
     * 每次检测心跳刷新"自动提交身体工具"开关，与监督 flag 同一套机制。
     *
     * <p>为什么必须有这个（2026-09-29）：此前只有 {@link #setBodySubmissionEnabled} 这个 setter，
     * **而且从没被任何地方调用过** —— 于是"防双驾驶"永远停在默认值。用户明确要求把这个自主权
     * 关掉：RDD 只做规划/管理，执行权完全归 AI。给个文件开关是为了不重编译也能临时开回来，
     * 但**默认必须是关的**。
     */
    public static void refreshBodyDispatchFlag() {
        if (bodyDispatchFlag == null) {
            return;
        }
        boolean on;
        try {
            String content = Files.exists(bodyDispatchFlag)
                    ? Files.readString(bodyDispatchFlag, StandardCharsets.UTF_8).trim() : "";
            on = content.equalsIgnoreCase("on") || content.equalsIgnoreCase("run") || content.equals("1");
        } catch (IOException ex) {
            on = bodySubmissionEnabled; // 读失败保持当前
        }
        if (on != bodySubmissionEnabled) {
            bodySubmissionEnabled = on;
            LOG.info("[rdd] 自动提交身体工具{}: flag={}", on ? "开启" : "关闭", bodyDispatchFlag);
            RddMonitor.publish("body_dispatch_state", Map.of("bodySubmissionEnabled", on));
        }
    }

    /** 监督拍醒是否放行。false = 空转止血：Detector 只观察/推进，不 nudge AI。 */
    public static boolean supervisionEnabled() {
        return supervisionEnabled;
    }

    /**
     * 暂停(PAUSE)功能开关（2026-09-30 用户要求：怕误伤实验，要能随时关掉）。
     *
     * <p>做法照抄 {@code rdd-supervision.flag} 的形态 —— <b>运行时文件开关</b>，
     * 不用重新编译就能切：建出 <code>config/numen/rdd-pause-disabled.flag</code> 即禁用，
     * 删掉即恢复。
     *
     * <p>为什么需要独立于 supervision：supervision 关掉会连带停掉卡死检测/拍醒/重试，
     * 那是"整套监督都停"；而用户只想<b>单独停暂停</b>，其余照常。
     */
    private static volatile Path pauseDisabledFlag;
    private static volatile boolean pauseEnabled = true;

    public static void setPauseDisabledFlagPath(Path p) { pauseDisabledFlag = p; }

    public static boolean pauseEnabled() { return pauseEnabled; }

    /** 每次检测心跳刷新：文件存在 = 禁用暂停。 */
    public static void refreshPauseFlag() {
        if (pauseDisabledFlag == null) return;
        boolean disabled;
        try {
            disabled = Files.exists(pauseDisabledFlag);
        } catch (RuntimeException ex) {
            disabled = !pauseEnabled;
        }
        if (disabled != !pauseEnabled) {
            pauseEnabled = !disabled;
            LOG.warn("[rdd] 暂停(PAUSE)功能已{}（{}）", pauseEnabled ? "启用" : "禁用",
                    pauseDisabledFlag);
        }
    }

    /** 每次检测心跳(~1s)刷新监督开关：外部(监测台/人)写 config/numen/rdd-supervision.flag=pause 即暂停。 */
    public static void refreshSupervisionFlag() {
        if (supervisionFlag == null) {
            return;
        }
        boolean paused;
        try {
            String content = Files.exists(supervisionFlag)
                    ? Files.readString(supervisionFlag, StandardCharsets.UTF_8).trim() : "";
            // 文件内容 "pause"/"0"/任意非空非 run → 暂停；删文件或写 "run" → 恢复
            paused = !content.isEmpty() && !content.equalsIgnoreCase("run");
        } catch (IOException ex) {
            paused = !supervisionEnabled; // flag 读取失败保持当前状态
        }
        if (paused != !supervisionEnabled) {
            supervisionEnabled = !paused;
            LOG.info("[rdd] 监督{}: flag={}", supervisionEnabled ? "恢复" : "暂停", supervisionFlag);
            RddMonitor.publish("supervision_state", Map.of("supervisionEnabled", supervisionEnabled));
        }
    }

    /** 保存所有活跃任务链到 config/numen/rdd-tasks（原子写 tmp+move）。 */
    public static void saveRuntimes() {
        if (tasksDir == null || RUNTIMES.isEmpty()) return;
        try {
            Files.createDirectories(tasksDir);
            for (Map.Entry<UUID, RddRuntime> e : RUNTIMES.entrySet()) {
                try {
                    Path tmp = tasksDir.resolve(e.getKey() + ".json.tmp");
                    Files.writeString(tmp, e.getValue().chain().toJson(), StandardCharsets.UTF_8);
                    Files.move(tmp, tasksDir.resolve(e.getKey() + ".json"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ex) {
                    LOG.warn("[rdd] 保存任务失败 {}: {}", e.getKey(), ex.toString());
                }
            }
        } catch (IOException ex) {
            LOG.warn("[rdd] 创建任务目录失败: {}", ex.toString());
        }
    }

    /** 游戏重启恢复：磁盘有任务但内存没有 → 加载为 RddRuntime（幂等）。 */
    public static void restoreRuntimes() {
        if (tasksDir == null || !Files.isDirectory(tasksDir)) return;
        try (var stream = Files.list(tasksDir)) {
            stream.filter(f -> f.getFileName().toString().endsWith(".json")).forEach(f -> {
                try {
                    UUID uuid = UUID.fromString(f.getFileName().toString().replace(".json", ""));
                    if (RUNTIMES.containsKey(uuid)) return;
                    String json = Files.readString(f, StandardCharsets.UTF_8);
                    TaskChain chain = TaskChain.fromJson(json);
                    RUNTIMES.put(uuid, new RddRuntime(chain, assets(uuid)));
                    // 懒链可能停靠在未展开一级：currentSubtask()=null，报阶段而非 NPE
                    Subtask restored = chain.currentSubtask();
                    String curLabel = restored != null
                            ? restored.id() : ("unexpanded:" + chain.currentPrimary().id());
                    LOG.info("[rdd] 恢复任务链 {}（当前二级 {}）", uuid, curLabel);
                } catch (Exception ex) {
                    LOG.warn("[rdd] 恢复任务失败 {}: {}", f.getFileName(), ex.toString());
                }
            });
        } catch (IOException ex) {
            LOG.warn("[rdd] 扫描任务目录失败: {}", ex.toString());
        }
    }

    static void saveAssets(UUID companionId) {
        if (companionId == null) return;
        try {
            RddAssetStore.save(assetsDir, companionId, assets(companionId));
        } catch (IOException ex) {
            LOG.warn("[rdd] 保存世界资产失败 {}: {}", companionId, ex.toString());
        }
    }

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
        if (!supervisionEnabled) {
            // 空转止血：监督暂停时绝不注入内置 AI（兜底闸；调用方也各自判了暂停）
            LOG.info("[rdd] 监督暂停,跳过拍醒: {}", message == null ? "" : message);
            return false;
        }
        if (numenApi == null || companionId == null || message == null || message.isBlank()) {
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
            numenApi.enqueueSystem(companionId, message);
            LAST_NUDGE_AT.put(nudgeBucket(companionId), now);
            Map<String, Object> data = observationData(companionId);
            data.put("outputId", UUID.randomUUID().toString());
            data.put("companionId", companionId.toString());
            data.put("message", message);
            data.put("source", "supervisor");
            data.put("target", "numen");
            RddRuntime runtime = RUNTIMES.get(companionId);
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
        return supervisionEnabled && numenApi != null && companionId != null
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
        RddRuntime rt = companionId == null ? null : RUNTIMES.get(companionId);
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
        if (companionId == null || chain == null) return false;
        if (!supervisionEnabled || numenApi == null) return false;   // 监督关着/没接宿主：不吵
        if (bodySubmissionEnabled) return false;                     // 身体驱动模式 RDD 自己干活，拍醒=双驾驶
        if (chain.primaryStatus() != PrimaryGoalStatus.ACTIVE) return false;
        Subtask cur = chain.currentSubtask();
        if (cur == null || chain.currentSubtaskStatus() != SubtaskStatus.RUNNING) return false;
        if (cur.id().equals(KICKOFF_SENT.get(companionId))) return false; // 这个二级已拍过
        if (!nudgeAllowed(companionId)) {
            // 新阶段的"开工"不能被上一阶段催工的 3 分钟总闸吞掉（每个二级都要能开工）
            clearNudgeThrottle(companionId);
        }
        String msg = "【开工】现在动手做这一步：「" + cur.description() + "」。"
                + "先调用工具真正执行，不要只回文字；做完把结果报回来，我核对真实资产后再推进下一步。"
                + "如果这一步现在做不了，用 report_task_concern 上报卡点，不要默默无视。";
        if (!nudge(companionId, msg)) return false;                  // 发失败不记账 → 下个 tick 重试
        KICKOFF_SENT.put(companionId, cur.id());
        Map<String, Object> data = observationData(companionId);
        data.put("primary", chain.currentPrimary().id());
        data.put("subtask", cur.id());
        data.put("reason", "kickoff: a subtask just entered RUNNING");
        RddMonitor.publish("goal_kickoff", data);
        LOG.info("[rdd] 开工拍醒 {} 二级 {}", companionId, cur.id());
        return true;
    }

    /** rdd_submit 用：AI 自己刚提交并启动的单，不需要再"开工拍醒"一次。 */
    static void markKickoffHandled(UUID companionId, String subtaskId) {
        if (companionId != null && subtaskId != null) {
            KICKOFF_SENT.put(companionId, subtaskId);
        }
    }

    /**
     * Emits an observational task-chain snapshot. This never mutates task state.
     *
     * <p>刻意<b>不</b>内嵌全量资产表：单条快照曾因此达 69 KB，其中 88% 是每 5 秒重复的同一批
     * 资产条目（每件还带完整 Observation 对象），实测把 rdd.jsonl 推到 288 MB。资产本体由
     * 30 秒一次的 {@link #publishAssetSnapshot}（{@code RddAssetContext.worldAssets} 渲染形状）
     * 提供，监测台优先读它；这里只留一个计数供页面显示规模。
     */
    public static void publishTaskSnapshot(UUID companionId, String reason) {
        if (companionId == null) return;
        RddRuntime runtime = RUNTIMES.get(companionId);
        if (runtime == null) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("companionId", companionId.toString());
        data.put("reason", reason == null ? "state_observed" : reason);
        data.put("taskChain", runtime.snapshot());
        data.put("assetCount", runtime.assets().snapshot().size());
        data.put("taskId", runtime.chain().goal().id());
        data.put("subtaskId", runtime.snapshot().get("currentSubtaskId"));
        RddMonitor.publish("taskchain_snapshot", data);
    }

    /**
     * 懒边界上报（已去重）：当前一级到达但未展开时，Detector 每秒都会走到这个分支，
     * 若不去重就会每秒写一条 expansion_needed + 一份任务链快照。
     * 同一个一级只上报一次；换到新一级才再报。
     */
    static void reportExpansionNeeded(UUID companionId, String primaryId, String theme) {
        if (companionId == null || primaryId == null) {
            return;
        }
        if (primaryId.equals(LAST_EXPANSION_REPORT.put(companionId, primaryId))) {
            return; // 同一一级已上报过
        }
        RddMonitor.publish("expansion_needed", Map.of(
                "primary", primaryId,
                "theme", theme == null ? "" : theme));
        publishTaskSnapshot(companionId, "primary_reached_unexpanded");
    }

    static void publishAssetSnapshot(UUID companionId, String reason, RddWorldAssetObserver.Result observed) {
        if (companionId == null) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("companionId", companionId.toString());
        data.put("reason", reason == null ? "lazy_world_observation" : reason);
        data.put("assets", RddAssetContext.worldAssets(assets(companionId)));
        data.put("observed", Map.of("bases", observed.bases(), "structures", observed.structures(),
                "machines", observed.machines(), "entityGroups", observed.entityGroups(), "total", observed.total()));
        data.put("dataFlow", Map.of(
                "source", "loaded_world_and_respawn_base",
                "registry", "rdd-assets/<companion>.json",
                "consumers", java.util.List.of("numen_context", "supervisor_context", "monitoring_station"),
                "refresh", "lazy_30s_no_chunk_force_load"));
        RddMonitor.publish("asset_snapshot", data);
    }

    /** Correlation only; no secondary task state and no reconstructed prompt. */
    static Map<String, Object> observationData(UUID companionId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("companionId", companionId.toString());
        RddRuntime rt = runtime(companionId);
        if (rt != null) {
            Map<String, Object> snapshot = rt.snapshot();
            data.put("taskId", snapshot.get("goalId"));
            data.put("primaryId", snapshot.get("currentPrimaryId"));
            data.put("subtaskId", snapshot.get("currentSubtaskId"));
        }
        return data;
    }

    static void publishPlanningContext(UUID companionId, String stage, String user, String system,
                                       com.dwinovo.numen.agent.provider.IToolSpec tool) {
        Map<String, Object> data = observationData(companionId);
        data.put("inputId", UUID.randomUUID().toString());
        data.put("source", "rdd_" + stage);
        data.put("target", "supervisor_planner");
        data.put("context", Map.of("system", system, "user", user,
                "tool", tool.name(), "parameters", tool.parameterSchema()));
        RddMonitor.publish("supervisor_context", data);
        // 逐字落盘：把「规划器实际收到的 user 正文」单独写一份，供监测台/人一眼核对
        // 「背包块/世界资产/经验」是否真的到了规划器（数据链断点定位用；只观测不改行为）。
        RddMonitor.publish("planning_input_verbatim", Map.of(
                "companionId", companionId == null ? "" : companionId.toString(),
                "stage", stage == null ? "" : stage,
                "system", system == null ? "" : system,
                "user", user == null ? "" : user,
                "tool", tool == null ? "" : tool.name()));
    }

    /** XML 转义：描述/条件可能含玩家可输入的 < > & ". */
    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
