package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.fact.StageKeyNormalizer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.*;

/** Stateful owner of task progress; adapters may only report into this object. */
public final class TaskChain {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    // 非 final：懒展开注入二级时需要重建 Goal（把当前未展开一级替换为已展开版本）。
    private Goal goal;
    private final Map<String, SubtaskStatus> statuses = new LinkedHashMap<>();
    private final Map<String, String> skipReasons = new LinkedHashMap<>();

    /** 暂停原因：与 {@link #skipReasons} 对称，但语义相反（留着可开，不是放弃）。 */
    private final Map<String, String> pauseReasons = new LinkedHashMap<>();
    private final Map<String, Integer> attempts = new LinkedHashMap<>();
    /**
     * {@code mode=acquire} 二级的<b>进入时基线</b>：二级 id → 该时刻各物品的持有量。
     *
     * <p>为什么挂在链上而不是 {@link Subtask} record：{@code Subtask} 是<b>计划规格</b>
     * （由规划器生成、要进 prompt、能被 JSON 序列化）；baseline 是<b>运行时观察事实</b>，
     * 宿主在二级真正激活的那一刻才知道。混进 record 会让"计划"被"执行观察"污染，
     * 并把构造器/JSON/旧存档兼容面一次性放大。
     *
     * <p>为什么必须随链持久化：代次 {@code planRevision} 已经吃过一次"只在宿主内存里 →
     * 重启撞号"的亏（见 {@link #adoptRevisionFromIds}）。baseline 若同样只活在内存，
     * 重启后 {@code acquire} 会拿不到基线 —— 此时 {@link HardCodedEvaluator} 一律判 false，
     * 已完成的二级会在重启后重新变成"没达成"。
     */
    private final Map<String, Map<String, Integer>> acquireBaselines = new LinkedHashMap<>();
    /**
     * G1a（IC-75-r1 IC04）：哪些二级的基线是「观测失败（UNKNOWN）」而不是「确认真空」。
     *
     * <p>★ 为什么要这个侧表：{@link #captureAcquireBaseline(String, Map)} 的历史契约把
     * {@code null} 折成空表（{@code TaskChainAcquireBaselineTest:186-201} 钉死），导致
     * 「读不到背包」与「背包真空」在内部表示上不可区分，{@code acquired = have − 0 = have}
     * —— 75号 §16.2-2 的静态反例（已有 5 + min 3 → 5≥3 假通过）。
     *
     * <p>修复：本侧表记「这条基线是观测失败」。空表条目 + 在本表 = UNKNOWN；
     * 空表条目 + 不在本表 = 确认真空（合法基线，{@code acquired=have} 就是净增益）。
     * 非空条目永远不进本表。不改变 {@code acquireBaselines} 的持久化 JSON 形状
     * （{@code fromJson} 恢复的旧空表条目视作确认真空——历史数据是当时真实观测）。
     */
    private final java.util.Set<String> unobservedBaselines = new java.util.LinkedHashSet<>();
    private int primaryIndex;
    private int subtaskIndex;
    /**
     * 计划代次（2026-09-30 深审 R05/codex P1）。
     *
     * <p>用途：让每次生成的二级 id 唯一。宿主生成 id 时拼上它（{@code primary-r{rev}-N}），
     * 于是**上一轮计划留下的任何东西（迟到的协商回执、指标关联、旧 id 引用）都匹配不上新计划**。
     *
     * <p><b>为什么必须存在链里而不是宿主内存</b>（codex 复审抓出）：代次若只活在宿主的 Map，
     * 停服就被清空，而磁盘上的链里 id 仍带着旧 rev —— 重启后第一次重规划从 rev=1 重新开始，
     * 与磁盘上已有的 {@code primary-r1-0} **直接撞号**，隔离形同虚设。
     * 放进 TaskChain 就随 {@code toJson/fromJson} 一起活过重启。
     *
     * <p>单调递增，永不回退。
     */
    private int planRevision;
    private PrimaryGoalStatus primaryStatus = PrimaryGoalStatus.PENDING;
    /** 当前二级最近一次绑定的宿主执行实例 id（P0-3 执行身份）；无执行绑定时为 null。 */
    private String activeExecutionId;
    /** 当前二级最近一次启动的 tick 时间戳（执行审计/重启恢复判定用）；从未启动为 0。 */
    private long lastStartedAtMillis;
    /**
     * P0 完成事实继承层：历史可靠完成过的一级"阶段键"集合（归一化主题，见
     * {@link StageKeyNormalizer}）。命中的一级视为已达成、在 currentPrimary()/推进时跳过；
     * 它<b>不</b>写进 statuses（懒展开一级本无二级，也绝不伪造二级完成）。
     * 全部阶段命中 → 链直接 COMPLETED。跨重绑继承由 {@code CompletedFactStore} 查询后注入。
     */
    private final Set<String> satisfiedStages = new LinkedHashSet<>();

    /**
     * G3b：安全相关状态提交版本（IC75-r1 §15.2 的 {@code stateVersion} 最小落地）。
     *
     * <p>只在这些「提交点」+1：开跑一次（{@link #beginAttempt}）、节点终态写入
     * （{@link #applyHardCodedResult} / {@link #applyAiAssistedResult}）、
     * 失败/重试/暂停/恢复/计划替换。**读操作（snapshot/toJson/状态查询）绝不 +1** ——
     * 它要能被当成「计划或节点状态变了几次」的判据，不能被遥测刷新稀释
     * （75号 §15.2：「不按普通遥测刷新递增」）。
     */
    private long stateVersion;

    /**
     * G3b：被忽略的陈旧结果次数（只观测，不参与判定）。
     *
     * <p>为什么要有这个计数：陈旧结果静默丢弃会让人以为「判定一直没跑」；
     * 有了它，监测台能区分「没触发」和「触发了但被 CAS 挡掉」。
     */
    private int staleResultsIgnored;

    // ---- G3e：安全点凭据（IC75-r1 §15.8）----

    /**
     * owner 代次：换人设 / 死亡复活重放时 +1，旧凭据一律 {@code STALE_EPOCH}。
     *
     * <p>随链持久化（旧存档读作 0）：代次若只活在内存在，重启后归零，
     * 旧凭据就会「复活」——而凭据代表的是一次尚未完成的结构变更，复活它是危险的。
     */
    private long ownerEpoch;

    /**
     * 当前未消费的凭据；{@code null} = 未冻结。
     *
     * <p><b>刻意不持久化</b>：凭据是「此刻身体真的停着」的断言，重启之后那个断言已经无法证明，
     * 丢弃它才是安全方向（宁可让规划器重新签一张，也不要拿一张来历不明的凭据去改计划）。
     */
    private SafePoint outstandingSafePoint;

    /** 凭据流水号（链内单调递增，只用于生成 value；不持久化）。 */
    private long safePointSeq;


    public TaskChain(Goal goal) {
        this(goal, Set.of());
    }

    /** @param satisfiedStageKeys 历史已完成的阶段键集合（归一键）；命中一级跳过。空集等价旧构造。 */
    public TaskChain(Goal goal, Set<String> satisfiedStageKeys) {
        this.goal = Objects.requireNonNull(goal);
        for (PrimaryGoal primary : goal.primaryGoals()) for (Subtask subtask : primary.subtasks()) statuses.put(subtask.id(), SubtaskStatus.PENDING);
        if (satisfiedStageKeys != null) {
            for (PrimaryGoal primary : goal.primaryGoals()) {
                String key = StageKeyNormalizer.normalize(primary.description());
                if (satisfiedStageKeys.contains(key)) {
                    satisfiedStages.add(key);
                }
            }
        }
        int first = firstUnsatisfiedFrom(0);
        if (first < 0) {
            // 全部阶段都有历史完成事实：链直接终态（一级本无二级，不伪造完成）
            primaryIndex = goal.primaryGoals().size() - 1;
            primaryStatus = PrimaryGoalStatus.COMPLETED;
        } else {
            primaryIndex = first;
            primaryStatus = PrimaryGoalStatus.PENDING;
        }
    }
    public Goal goal() { return goal; }
    public synchronized PrimaryGoalStatus primaryStatus() { return primaryStatus; }

    /** 当前计划代次（只增）。生成二级 id 时拼上它即可保证跨轮次唯一。 */
    public synchronized int planRevision() { return planRevision; }

    /**
     * 主人暂停的原因前缀（2026-09-30 深审/codex P1-2）。
     *
     * <p>约定：凡以它开头的暂停都是<b>主人的明确指令</b>（「原地待命」），
     * 与士兵自己说的 PAUSE（"我做不了，等条件变"）在治理上完全不同：
     * 前者**永不过期、绝不自动恢复**、也不许被执行层的 COUNTER 推翻；
     * 后者到期该催她换办法。
     */
    public static final String OWNER_PAUSE_PREFIX = "owner";

    /** 主人下的暂停（{@link #pauseSubtask} 已校验 reason 非空）。 */
    public static final String OWNER_PAUSE_REASON =
            "owner: paused on request; resume only when the owner says so";

    /** 这次暂停是不是主人下的（供宿主决定要不要被复评/协商打断）。 */
    public synchronized boolean isOwnerPause(String subtaskId) {
        String reason = pauseReasons.get(subtaskId);
        return reason != null && reason.toLowerCase(java.util.Locale.ROOT).startsWith(OWNER_PAUSE_PREFIX);
    }

    /** 推进计划代次，返回新值。宿主在**每次生成/替换二级之前**调它。 */
    public synchronized int nextPlanRevision() { return ++planRevision; }

    /**
     * 从链上<b>实际存在的二级 id</b> 反推代次下限（2026-09-30 深审/codex P1-1）。
     *
     * <p>为什么需要：planRevision 字段是这一轮才加的，**上一版存档里没有**。
     * 而上一版生成的 id 已经是 {@code primary-r1-0} 这样的形状。
     * 若恢复时把代次当成 0，那么重启后第一次重规划又从 rev=1 开始，
     * 生成出与磁盘上<b>一模一样</b>的 id —— 旧回执的隔离就白做了。
     *
     * <p>所以扫一遍现存二级 id，取其中出现过的最大代次作为下限（不存在则保持原值）。
     * 只增不减，永远不会把代次往回拨。
     */
    private void adoptRevisionFromIds() {
        int floor = planRevision;
        for (PrimaryGoal p : goal.primaryGoals()) {
            for (Subtask s : p.subtasks()) {
                int r = revisionOf(s.id());
                if (r > floor) floor = r;
            }
        }
        if (floor != planRevision) {
            planRevision = floor;
        }
    }

    /** 从二级 id 里读出代次；读不出（旧的 {@code -sN} 命名）返回 -1。 */
    private static int revisionOf(String subtaskId) {
        if (subtaskId == null) return -1;
        int at = subtaskId.lastIndexOf("-r");
        if (at < 0) return -1;
        int end = at + 2;
        int i = end;
        while (i < subtaskId.length() && Character.isDigit(subtaskId.charAt(i))) {
            i++;
        }
        if (i == end || i >= subtaskId.length() || subtaskId.charAt(i) != '-') {
            return -1;   // 不是 "-r<数字>-" 形状
        }
        try {
            return Integer.parseInt(subtaskId.substring(end, i));
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    /** 当前二级的状态；当前一级"已到达但未展开"（无二级可运行）时为 null。 */
    public synchronized SubtaskStatus currentSubtaskStatus() {
        Subtask s = currentSubtask();
        return s == null ? null : statuses.get(s.id());
    }

    /**
     * 当前指针指向的二级目标。当前一级为未展开（懒加载、0 个二级）时返回 null——
     * 链上没有可运行的当前二级，宿主应先把已生成二级注入再激活；null 不是合法可执行节点。
     */
    public synchronized Subtask currentSubtask() {
        PrimaryGoal cur = currentPrimary();
        return cur.unexpanded() ? null : cur.subtasks().get(subtaskIndex);
    }
    public synchronized PrimaryGoal currentPrimary() { return goal.primaryGoals().get(primaryIndex); }

    /**
     * 当前一级的所有前置资产(waitFor)是否都被 counts 满足（无 waitFor → true）。
     *
     * <p>⚠️ 2026-09-27（用户指示）：**依赖门被临时删除**——一律返回 true，不再用 waitFor 拦一级。
     * 理由：waitFor 一直牵制、影响推进，且逻辑不自洽（中间产物被消耗后门恒不满足 → 静默停）。
     * waitFor 数据仍保留在链上（未删字段 / injectWaitFor 仍写），**需要时可恢复本方法原逻辑**
     * （见 git 历史 / 经验库 issue/waiting-gate-silent-stall-no-supervision）。
     */
    public synchronized boolean currentPrimaryReady(Map<String, Integer> counts) {
        if (!dependencyGateEnabled) {
            return true; // gate off (owner decision 2026-09-27): the chain must not self-lock
        }
        return dependencyGateSatisfied(counts);
    }

    /**
     * 依赖门开关（2026-09-27 用户决定：先关掉，别让链自锁）。
     *
     * <p>做成开关而不是删代码：关掉只是**运行时行为**变了，拦截能力本身与它的契约测试
     * 都还在（测试里 {@link #setDependencyGateEnabled(boolean)} 打开后照样验证"缺料就拦"）。
     * 哪天要恢复门，只要把默认值改回 true 或在启动处打开，能力立刻回来。
     */
    private static volatile boolean dependencyGateEnabled = Boolean.getBoolean("rdd.dependencyGate");

    /** 运行时开关依赖门（生产默认关；单测用它验证拦截能力仍在）。 */
    public static void setDependencyGateEnabled(boolean enabled) {
        dependencyGateEnabled = enabled;
    }

    /** 当前依赖门是否生效（诊断用：监测台/日志可读，避免"为什么没拦"变成灵异事件）。 */
    public static boolean dependencyGateActive() {
        return dependencyGateEnabled;
    }

    /** 依赖门本体（原本的判定逻辑；开关打开时才会被调用）。 */
    private synchronized boolean dependencyGateSatisfied(Map<String, Integer> counts) {
        List<AssetRequirement> wf = currentPrimary().waitFor();
        if (wf.isEmpty()) return true;
        if (counts == null) return false;
        for (AssetRequirement r : wf) {
            if (counts.getOrDefault(r.assetKey(), 0) < r.minimum()) return false;
        }
        return true;
    }

    /**
     * 依赖门接入真实注册表（P0-2）：以 {@link AssetRegistry#usableCounts()} 折叠的
     * 可用资产为准判定当前一级是否已满足前置。只算 OBSERVED 且有 inventory_scan 计数的条目，
     * UNKNOWN/INVALID 状态资产天然不满足依赖门。
     */
    public synchronized boolean currentPrimaryReady(AssetRegistry registry) {
        return currentPrimaryReady(registry == null ? null : registry.usableCounts());
    }

    /**
     * 激活"刚推进到、还没开工"的当前一级（PENDING/WAITING → ACTIVE 并启动其首个二级）。
     * 前置资产未到位 → 置 WAITING 并返回 false（监督方此时不该把它派给 AI，等资产到了再调）。
     */
    public synchronized boolean activateCurrent(Map<String, Integer> counts) {
        if (primaryStatus != PrimaryGoalStatus.PENDING && primaryStatus != PrimaryGoalStatus.WAITING) {
            throw new IllegalStateException("only a not-yet-started primary may activate: " + primaryStatus);
        }
        if (currentPrimary().unexpanded()) {
            // 未展开 ≠ 依赖未满足：绝不能把"该展开了"误判成 WAITING(缺资产)，也不得进 ACTIVE。
            throw new IllegalStateException("current primary unexpanded; expandCurrentPrimary(...) before activation: "
                    + currentPrimary().id());
        }
        if (!currentPrimaryReady(counts)) {
            primaryStatus = PrimaryGoalStatus.WAITING;
            return false;
        }
        primaryStatus = PrimaryGoalStatus.ACTIVE;
        if (statuses.get(currentSubtask().id()) == SubtaskStatus.PENDING) {
            // G3a：激活也是「开跑一次」——走统一入口计数（IC03）。基线仍按本路径的既有语义：
            // plain activate 不拍（调用方随后可显式 capture），只有带 registry/snapshot 的两条路拍。
            beginAttempt(currentSubtask().id(), counts, false);
        }
        return true;
    }

    /** 同 {@link #activateCurrent(Map)}，但依赖门以真实注册表为准（P0-2 接线）。 */
    public synchronized boolean activateCurrentWithRegistry(AssetRegistry registry) {
        if (primaryStatus != PrimaryGoalStatus.PENDING && primaryStatus != PrimaryGoalStatus.WAITING) {
            throw new IllegalStateException("only a not-yet-started primary may activate: " + primaryStatus);
        }
        if (currentPrimary().unexpanded()) {
            throw new IllegalStateException("current primary unexpanded; expandCurrentPrimary(...) before activation: "
                    + currentPrimary().id());
        }
        if (!currentPrimaryReady(registry)) {
            primaryStatus = PrimaryGoalStatus.WAITING;
            return false;
        }
        primaryStatus = PrimaryGoalStatus.ACTIVE;
        if (statuses.get(currentSubtask().id()) == SubtaskStatus.PENDING) {
            // G3a：统一入口（计数 + 执行身份作废 + 开工时刻），本路径照旧拍基线
            beginAttempt(currentSubtask().id(),
                    registry == null ? null : registry.usableCounts(), true);   // F5：激活即锁基线
        }
        return true;
    }

    /**
     * 依赖门统一入口（P2-A 修正）：以 {@link PlanningAssetSnapshot} 为准。
     *
     * <p>为什么不是 {@code activateCurrentWithRegistry}：背包资产**不落盘**（P2-A 决策），
     * 注册表里没有 {@code inventory_scan} 条目 → {@code registry.usableCounts()} 恒空 →
     * 任何物品类 wait_for 永不满足（"背包明明有却不判定"）。快照的 {@code availableCounts()}
     * 才是「实时扫描」的持有真相；world 资产仍由注册表另行提供（二级 world 条件走 RddWorldFacts）。
     */
    public synchronized boolean activateCurrentWithSnapshot(PlanningAssetSnapshot snapshot) {
        if (primaryStatus != PrimaryGoalStatus.PENDING && primaryStatus != PrimaryGoalStatus.WAITING) {
            throw new IllegalStateException("only a not-yet-started primary may activate: " + primaryStatus);
        }
        if (currentPrimary().unexpanded()) {
            throw new IllegalStateException("current primary unexpanded; expandCurrentPrimary(...) before activation: "
                    + currentPrimary().id());
        }
        Map<String, Integer> counts = snapshot == null ? null : snapshot.availableCounts();
        if (!currentPrimaryReady(counts)) {
            primaryStatus = PrimaryGoalStatus.WAITING;
            return false;
        }
        primaryStatus = PrimaryGoalStatus.ACTIVE;
        if (statuses.get(currentSubtask().id()) == SubtaskStatus.PENDING) {
            // G3a：统一入口（计数 + 执行身份作废 + 开工时刻），本路径照旧拍基线
            beginAttempt(currentSubtask().id(), counts, true);   // F5：激活即锁基线
        }
        return true;
    }

    public synchronized void startCurrent() {
        if (currentPrimary().unexpanded()) {
            throw new IllegalStateException("current primary unexpanded; expandCurrentPrimary(...) before start: "
                    + currentPrimary().id());
        }
        if (primaryStatus == PrimaryGoalStatus.PENDING) primaryStatus = PrimaryGoalStatus.ACTIVE;
        if (primaryStatus != PrimaryGoalStatus.ACTIVE) throw new IllegalStateException("current subtask cannot start");
        beginAttempt(currentSubtask().id(), null, false);
    }

    /**
     * 唯一 PENDING→RUNNING 迁移入口（G3a，IC75-r1 IC03）。
     *
     * <p><b>为什么必须收口到一处</b>：此前四条启动路径各写各的 ——
     * {@link #startCurrent()} 记 attempts，{@link #activateCurrentWithSnapshot} /
     * {@link #activateCurrentWithRegistry} 不记，{@link #startCurrentWithCounts}
     * 走 startCurrent 再补基线。结果是「同一次开跑」在不同入口下计数不同，
     * 而 attempts 是「这一步做过几次」的唯一计数，不一致就没法用它做重试/审计。
     *
     * <p><b>本次统一的语义（改的是计数口径，不是启动条件）</b>：
     * <ul>
     *   <li>每一次真正的 PENDING→RUNNING 迁移，attempts 恰好 +1；</li>
     *   <li>非 PENDING（已在跑/已完成）→ 直接抛，不静默 +1；读状态/轮询不经过这里；</li>
     *   <li>新的执行轮次作废旧宿主执行身份（{@code activeExecutionId=null}），并记开工时刻；</li>
     *   <li>基线捕获仍按调用方决定（{@code captureBaseline}）——
     *       这是「这次能不能判 acquire」的语义开关，不与计数混在一起。</li>
     * </ul>
     */
    private void beginAttempt(String subtaskId, Map<String, Integer> counts, boolean captureBaseline) {
        // G3e：凭据未消费期间 = 计划正在被改写 → 冻结「新动作」。
        // 只冻新动作、不打断在跑的（打断属执行层）：最坏情况是「这件干完等新计划」，不是卡死。
        requireNoOutstandingSafePoint("start another attempt");
        if (statuses.get(subtaskId) != SubtaskStatus.PENDING) {
            throw new IllegalStateException("attempt must start from PENDING: " + subtaskId
                    + " is " + statuses.get(subtaskId));
        }
        statuses.put(subtaskId, SubtaskStatus.RUNNING);
        attempts.merge(subtaskId, 1, Integer::sum);
        stateVersion++;            // G3b：开跑一次 = 一次安全提交
        activeExecutionId = null; // 新一轮执行，旧的宿主执行实例身份作废
        lastStartedAtMillis = System.currentTimeMillis();
        if (captureBaseline) {
            captureAcquireBaseline(subtaskId, counts);
        }
    }

    /**
     * 同 {@link #startCurrent()}，但在同一次 PENDING→RUNNING 迁移里额外锁一次 {@code acquire} 基线。
     *
     * <p><b>为什么必须有这个重载（2026-10-02 实测事故）。</b>基线原先只在
     * {@link #activateCurrentWithSnapshot}（和 {@link #activateCurrentWithRegistry}）里拍，
     * 而 {@code rdd_submit} 派活走的是 {@link #startCurrent()} 这条路 —— 那边一次都不拍。
     * 后果：{@code rdd_submit} 建出来的链永远没有基线 →
     * {@link HardCodedEvaluator} 的「无基线 = 未达成」分支让 {@code mode=acquire} <b>恒 false</b> →
     * 合成/采集类任务<b>真的把东西做出来了也判不出成功</b> → 被 {@code RddStallWatcher} 当成卡死 →
     * 判失败并逼模型把整件事重做一遍（实测：13:11:41 合成出石镐，13:12:45 判「stalled after nudges」，
     * 13:13:04 又合成一把，背包里最后有 2 把）。
     *
     * <p>「激活」和「开跑」是同一个"派活之前"的时刻，两条路都必须锁基线。
     * {@link #captureAcquireBaseline} 幂等（已有则不覆盖），所以重复调用安全。
     *
     * @param counts 此刻同伴的持有量；{@code null} 记空表（等价于「什么都有」，
     *               后续 acquire 只能靠净增满足，比缺基线的恒 false 宽松）
     */
    public synchronized void startCurrentWithCounts(Map<String, Integer> counts) {
        startCurrent();
        captureAcquireBaseline(currentSubtask().id(), counts);
    }

    // ---- acquire baseline (mode=acquire 的运行时基线；见字段注释) ----

    /**
     * 捕获某二级的 {@code acquire} 基线（宿主在二级激活、开始派活**之前**调）。
     *
     * <p>幂等：已有基线时**不覆盖**（重试同一个二级不该把基线推到"已经做完"的位置，
     * 否则 retry 后 {@code acquire} 永远判未达成）。
     */
    public synchronized void captureAcquireBaseline(String subtaskId, Map<String, Integer> counts) {
        if (subtaskId == null || acquireBaselines.containsKey(subtaskId)) return;
        acquireBaselines.put(subtaskId, counts == null ? Map.of() : Map.copyOf(counts));
        // G1a：null 是「观测失败」，与「确认真空(Map.of())」区分开（见 unobservedBaselines 注释）。
        if (counts == null) unobservedBaselines.add(subtaskId);
    }

    /**
     * 该二级的基线；没有则 null（宿主据此让 acquire 判未达成，而不是假装达成）。
     *
     * <p>★ G1a（IC04）：**观测失败（UNKNOWN）时也返回 null** —— 宿主侧
     * {@code HardCodedEvaluator} 的 null 守卫因此同时挡住「未捕获」和「观测失败」两条路径。
     * 「确认真空」（捕获时真读到了空背包）仍返回空表 —— 空表是合法基线
     * （{@code gained = have − 0 = 净增益}），不能错杀。
     */
    public synchronized Map<String, Integer> acquireBaseline(String subtaskId) {
        if (subtaskId == null) return null;
        Map<String, Integer> baseline = acquireBaselines.get(subtaskId);
        if (baseline == null) return null;
        if (baseline.isEmpty() && unobservedBaselines.contains(subtaskId)) {
            return null;   // UNKNOWN ≠ 确认真空 —— 折回 null 让守卫接住（G1a 核心修复）
        }
        return baseline;
    }

    /** G1a 读数口：该二级的基线是否是「观测失败」。未捕获/确认观测都返回 false。 */
    public synchronized boolean isBaselineUnobserved(String subtaskId) {
        return subtaskId != null && unobservedBaselines.contains(subtaskId);
    }

    /** 二级离开本轮生命周期（换计划/作废）时连同它的基线一起清掉，避免内存与存档堆积。 */
    private void forgetAcquireBaseline(String subtaskId) {
        acquireBaselines.remove(subtaskId);
        unobservedBaselines.remove(subtaskId);
    }

    /** P0-3 执行身份：宿主开始执行后把真实执行实例 id 绑到当前二级；重复绑定会置换旧 id。 */
    public synchronized void bindExecution(String subtaskId, String executionId) {
        if (executionId == null || executionId.isBlank()) throw new IllegalArgumentException("execution id required");
        requireCurrent(subtaskId);
        if (statuses.get(subtaskId) != SubtaskStatus.RUNNING) throw new IllegalStateException("current subtask is not running");
        activeExecutionId = executionId;
    }

    /** P0-3 执行身份：当前二级绑定的宿主执行实例 id；未绑定/未运行时为 null。 */
    public synchronized String activeExecutionId() { return activeExecutionId; }
    public synchronized int attempts(String subtaskId) { return attempts.getOrDefault(subtaskId, 0); }

    /** G3b：安全状态版本（只读）。只在这些提交点 +1：开跑/终态/失败/重试/暂停/恢复/展开/替换。 */
    public synchronized long stateVersion() { return stateVersion; }

    /** G3b：被忽略的陈旧结果次数（只读；区分「判定没跑」与「跑了但被 CAS 挡掉」）。 */
    public synchronized int staleResultsIgnored() { return staleResultsIgnored; }

    // ---- G3e：安全点凭据（签发/校验/消费/作废）----

    /** G3e：owner 代次（只读）。 */
    public synchronized long ownerEpoch() { return ownerEpoch; }

    /**
     * G3e：owner 换代（换人设 / 死亡复活重放）。代次 +1，且<b>当场作废</b>未消费的凭据。
     *
     * @return 新代次
     */
    public synchronized long bumpOwnerEpoch() {
        ownerEpoch++;
        outstandingSafePoint = null;
        return ownerEpoch;
    }

    /**
     * G3e：签发一张安全点凭据（一张一消费，绑当前 owner 代次）。
     *
     * <p><b>签发时机由宿主决定</b>（身体是空的 / 上一动作刚 {@code task_finished}），
     * 链只负责「同 epoch、同用途、未过期、未消费」的校验与一次性消费。
     * 把签发条件也写进这里，迟早会出现第二套「身体是否空闲」的判据——那是本项目反复踩的坑。
     *
     * @throws IllegalStateException 已经有一张未消费的凭据（一次只允许一个结构变更在途）
     */
    public synchronized SafePoint issueSafePoint(SafePoint.Purpose purpose, long ttlMillis) {
        SafePoint existing = outstandingSafePoint();
        if (existing != null) {
            throw new IllegalStateException("safe point already outstanding: " + existing.value());
        }
        long now = System.currentTimeMillis();
        long ttl = ttlMillis <= 0 ? SafePoint.DEFAULT_TTL_MILLIS : ttlMillis;
        SafePoint token = new SafePoint("sp-" + (++safePointSeq) + "-" + ownerEpoch, ownerEpoch,
                Objects.requireNonNull(purpose), now, now + ttl);
        outstandingSafePoint = token;
        return token;
    }

    /**
     * G3e：当前未消费且未过期的凭据；{@code null} = 未冻结。
     *
     * <p><b>过期即视为没有</b>（顺手清掉）：规划器崩了/被取消时不能把身体永久冻住。
     */
    public synchronized SafePoint outstandingSafePoint() {
        if (outstandingSafePoint == null) return null;
        if (outstandingSafePoint.expiredAt(System.currentTimeMillis())) {
            outstandingSafePoint = null;
            return null;
        }
        return outstandingSafePoint;
    }

    /** G3e：校验凭据（纯求值，不消费、不改状态）。 */
    public synchronized SafePoint.Check checkSafePoint(SafePoint token, SafePoint.Purpose purpose) {
        if (token == null) return SafePoint.Check.reject(SafePoint.NO_TOKEN);
        if (token.ownerEpoch() != ownerEpoch) return SafePoint.Check.reject(SafePoint.STALE_EPOCH);
        if (token.purpose() != purpose) return SafePoint.Check.reject(SafePoint.WRONG_PURPOSE);
        if (token.expiredAt(System.currentTimeMillis())) {
            if (outstandingSafePoint != null && outstandingSafePoint.value().equals(token.value())) {
                outstandingSafePoint = null;   // 过期自动解冻
            }
            return SafePoint.Check.reject(SafePoint.EXPIRED);
        }
        if (outstandingSafePoint == null || !outstandingSafePoint.value().equals(token.value())) {
            return SafePoint.Check.reject(SafePoint.ALREADY_CONSUMED);
        }
        return SafePoint.Check.pass();
    }

    /**
     * G3e：消费凭据（一次性）。成功 = 冻结解除。
     *
     * @return true = 这张凭据有效且已用掉；false = 无效（种类见 {@link #checkSafePoint}）
     */
    public synchronized boolean consumeSafePoint(SafePoint token) {
        if (token == null) return false;
        if (!checkSafePoint(token, token.purpose()).ok()) return false;
        outstandingSafePoint = null;
        return true;
    }

    /**
     * G3e：废弃凭据（计划变更放弃时调用）。
     *
     * <p>与消费<b>同一个出口</b>：之后 {@code check} 一律 {@code ALREADY_CONSUMED}——
     * 不给「作废」另造一种失效态，否则调用方要判两种「不能用」。
     */
    public synchronized void revokeSafePoint(SafePoint token) {
        if (token == null) return;
        if (outstandingSafePoint != null && outstandingSafePoint.value().equals(token.value())) {
            outstandingSafePoint = null;
        }
    }

    /** 冻结闸：有未消费凭据时，计划正在被改写 → 不许按旧计划启动新结构变更。 */
    private void requireNoOutstandingSafePoint(String action) {
        SafePoint out = outstandingSafePoint();
        if (out != null) {
            throw new IllegalStateException("SAFE_POINT_PENDING: cannot " + action
                    + " while a safe point is outstanding (" + out.purpose() + ")");
        }
    }

    /** 凭据闸：结构变更必须出示同用途、同 epoch、未过期、未消费的凭据。 */
    private void requireSafePoint(SafePoint token, SafePoint.Purpose purpose) {
        SafePoint.Check check = checkSafePoint(token, purpose);
        if (!check.ok()) {
            throw new IllegalStateException("SAFE_POINT_REJECTED: " + check.code() + " (" + purpose + ")");
        }
    }

    /**
     * 懒加载唯一合法的注入点：宿主目标驱动器把已为"当前一级"生成的二级集合注入该一级。
     * 保持一级的 id/description/waitFor 与 {@link #primaryStatus} 不变，仅 unexpanded→false；
     * 注入后照常走依赖门/激活/硬检测。当前一级未展开之外的调用一律拒绝（不重写其他任何状态）。
     *
     * <p><b>G3e</b>：有未消费的安全点凭据时拒绝（{@code SAFE_POINT_PENDING}）；
     * 要注入请走 {@link #expandCurrentPrimary(List, SafePoint)}。
     */
    public synchronized void expandCurrentPrimary(List<Subtask> generated) {
        requireNoOutstandingSafePoint("expand primary");
        doExpandCurrentPrimary(generated);
    }

    /** G3e：带 `EXPAND_PRIMARY` 凭据的展开注入；成功应用后当场消费。 */
    public synchronized void expandCurrentPrimary(List<Subtask> generated, SafePoint token) {
        requireSafePoint(token, SafePoint.Purpose.EXPAND_PRIMARY);
        doExpandCurrentPrimary(generated);
        consumeSafePoint(token);
    }

    private void doExpandCurrentPrimary(List<Subtask> generated) {
        PrimaryGoal cur = currentPrimary();
        if (isSatisfied(primaryIndex)) {
            // 防御：被完成事实命中的一级必须跳过，绝不能被展开重跑（正常路径 currentPrimary 已跳过）
            throw new IllegalStateException("current primary is fact-satisfied and must be skipped, not expanded: " + cur.id());
        }
        if (!cur.unexpanded()) {
            throw new IllegalStateException("current primary already expanded: " + cur.id());
        }
        if (primaryStatus != PrimaryGoalStatus.PENDING && primaryStatus != PrimaryGoalStatus.WAITING) {
            throw new IllegalStateException("only a not-yet-started primary may be expanded: " + primaryStatus);
        }
        if (generated == null || generated.isEmpty()) {
            throw new IllegalArgumentException("expansion requires at least one legal subtask");
        }
        Set<String> ids = new HashSet<>();
        for (Subtask s : generated) {
            if (s == null) {
                throw new IllegalArgumentException("null subtask in expansion");
            }
            if (!ids.add(s.id())) {
                throw new IllegalArgumentException("duplicate subtask id in expansion: " + s.id());
            }
            if (statuses.containsKey(s.id())) {
                throw new IllegalArgumentException("subtask id already used in chain: " + s.id());
            }
        }
        List<PrimaryGoal> primaries = new ArrayList<>(goal.primaryGoals());
        primaries.set(primaryIndex,
                new PrimaryGoal(cur.id(), cur.description(), List.copyOf(generated), cur.waitFor(), false));
        goal = new Goal(goal.id(), goal.description(), primaries);
        for (Subtask s : generated) {
            statuses.put(s.id(), SubtaskStatus.PENDING);
        }
        subtaskIndex = 0;
        stateVersion++;   // G3b：展开注入 = 计划结构变化（安全提交）
    }

    public synchronized boolean applyHardCodedResult(String subtaskId, boolean satisfied) {
        return applyHardCodedResult(subtaskId, satisfied, attempts(subtaskId));
    }

    /**
     * G3b（IC03 后半句 / IC06 准备）：带<b>尝试号</b>的结果写入。
     *
     * <p><b>为什么需要尝试号</b>：`attempts` 记的是「这一步开跑了几次」；如果一次评估
     * 是在旧尝试里发起的、结果在新尝试开始后才回来，直接套用会把「上一轮的达成」
     * 记到「这一轮」头上——这正是 IC03「旧结果不推进新 attempt」要堵的洞。
     *
     * <p>当前调用链是同一次 tick 内评估并写入，陈旧结果由宿主
     * {@code RddCallbackGuard} 在回调层拦下；本方法是<b>领域层防御</b>：
     * 即使将来评估变成异步，TASK 侧也不会被旧结果推进。
     *
     * @param attemptNo 发起评估时读到的尝试号（{@link #attempts(String)}）
     * @return true = 已写入（COMPLETED）；false = 未满足，或尝试号已过期（忽略）
     */
    public synchronized boolean applyHardCodedResult(String subtaskId, boolean satisfied, int attemptNo) {
        requireCurrent(subtaskId);
        if (currentSubtask().detectionMode() != DetectionMode.HARD_CODED) throw new IllegalStateException("current subtask is not hard-coded");
        if (statuses.get(subtaskId) != SubtaskStatus.RUNNING) throw new IllegalStateException("current subtask is not running");
        if (attemptNo != attempts(subtaskId)) {
            // 旧尝试的迟到结果：只计数、不落状态（历史不会被抹掉，当前节点也不会被推进）
            staleResultsIgnored++;
            return false;
        }
        if (!satisfied) return false;
        statuses.put(subtaskId, SubtaskStatus.COMPLETED);
        stateVersion++;
        advanceOrAwait();
        return true;
    }

    public synchronized boolean applyAiAssistedResult(String subtaskId, String result) {
        requireCurrent(subtaskId);
        if (currentSubtask().detectionMode() != DetectionMode.AI_ASSISTED) throw new IllegalStateException("current subtask is not AI-assisted");
        if (statuses.get(subtaskId) != SubtaskStatus.RUNNING) throw new IllegalStateException("current subtask is not running");
        Objects.requireNonNull(result);
        if (!"CONFIRMED".equals(result)) return false;
        statuses.put(subtaskId, SubtaskStatus.COMPLETED);
        stateVersion++;            // G3b：节点终态提交
        advanceOrAwait();
        return true;
    }

    public synchronized void applySupervisorDecision(SupervisorDecision decision) {
        Objects.requireNonNull(decision);
        if (primaryStatus != PrimaryGoalStatus.AWAITING_SUPERVISOR || !currentPrimary().id().equals(decision.targetNodeId())) throw new IllegalStateException("supervisor decision does not match current primary goal");
        switch (decision.type()) {
            case CONFIRM -> {
                if (primaryIndex + 1 < goal.primaryGoals().size()) {
                    // 推进到下一个"未被完成事实命中"的一级；中间被事实命中的阶段直接跳过。
                    int next = firstUnsatisfiedFrom(primaryIndex + 1);
                    subtaskIndex = 0;
                    if (next < 0) {
                        primaryStatus = PrimaryGoalStatus.COMPLETED;
                    } else {
                        primaryIndex = next;
                        primaryStatus = PrimaryGoalStatus.PENDING;
                    }
                } else {
                    primaryStatus = PrimaryGoalStatus.COMPLETED;
                }
            }
            case REJECT, REPLAN -> primaryStatus = PrimaryGoalStatus.REPLANNING;
            // NEED_MORE_EVIDENCE/DEFER 是"证据不足暂缓"，特意停回 AWAITING_SUPERVISOR：
            // 绝不落到 WAITING——WAITING 是"缺前置资产"语义，二者的退出通道完全不同。
            case NEED_MORE_EVIDENCE, DEFER -> { /* 停在监督等待，等新的 CONFIRM/REJECT/REPLAN */ }
        }
    }

    /** REPLANNING 出口：宿主决定沿用现有二级计划 → 本级二级全量重置 PENDING，回到 PENDING 走依赖门/激活重跑。 */
    public synchronized void resumeFromReplanning() {
        if (primaryStatus != PrimaryGoalStatus.REPLANNING) throw new IllegalStateException("not replanning: " + primaryStatus);
        PrimaryGoal cur = currentPrimary();
        if (cur.unexpanded()) throw new IllegalStateException("unexpanded primary cannot resume replanning: " + cur.id());
        for (Subtask s : cur.subtasks()) {
            statuses.put(s.id(), SubtaskStatus.PENDING);
            skipReasons.remove(s.id());
            pauseReasons.remove(s.id());
            forgetAcquireBaseline(s.id());   // 重跑 = 上一轮的基线作废，否则 acquire 会永远判未达成
        }
        subtaskIndex = 0;
        primaryStatus = PrimaryGoalStatus.PENDING;
        clearExecutionMetadata(); // 重跑 = 旧执行身份/启动时间作废
    }

    /**
     * REPLANNING 缺口 P4：从“卡死”进入 REPLANNING 的显式出口。
     *
     * <p>旧链只有 {@code AWAITING_SUPERVISOR + applySupervisorDecision(REPLAN)} 一个入口，
     * 导致生产里 REPLANNING 长期休眠。本方法允许：链 ACTIVE，且**当前二级 FAILED/STALLED**（确有卡死）
     * 时，由宿主/上层显式进入 REPLANNING；之后照旧走 {@link #replaceCurrentSubtasks(List)}（换新计划）
     * 或 {@link #resumeFromReplanning()}（重跑现有）。
     */
    /**
     * REPLANNING 入口（协商用）：士兵在**干活途中**（当前二级仍 RUNNING）认为计划不对、
     * 主动上报 COUNTER/REJECT 时走这里。
     *
     * <p><b>为什么要有这个方法</b>（2026-09-29 定位到的真断点）：
     * {@link #enterReplanningFromStuck(String)} 要求当前二级是 FAILED/STALLED，
     * 但士兵唯一合理的上报时机恰恰是 <b>RUNNING</b>（还在干、觉得方向不对）。
     * 于是协商请求走到 {@code RddPlugin.requestReplan} 时被
     * {@code enterReplanningFromStuck} 抛异常挡掉，{@code return false}，
     * 而 {@code soldierHint} 是在那之后才拼装的 —— 结果
     * <b>士兵的建议永远送不到规划器</b>，实机表现为「说了好几次指挥官仍照原计划」。
     * 更糟的是回执已被 {@code RddDetector.tickNegotiation} 先行消费，失败后无法恢复。
     *
     * <p><b>语义选择</b>：这里<b>不</b>把当前二级强转 FAILED（那会伪造一个"失败"事实、
     * 污染达成判定与埋点），而是直接进入 REPLANNING。
     * 当前二级状态在 {@link #replaceCurrentSubtasks(List)} / {@link #resumeFromReplanning()}
     * 时按既有逻辑处理，行为与「从卡死进入」保持一致。
     *
     * @param reason 协商原因（原样进埋点与规划上下文）
     * @throws IllegalStateException 链不处于可重规划状态时（与既有入口一致的失败语义）
     */
    public synchronized void enterReplanningFromNegotiation(String reason) {
        if (primaryStatus != PrimaryGoalStatus.ACTIVE) {
            throw new IllegalStateException("only an ACTIVE primary may enter replanning from negotiation: " + primaryStatus);
        }
        if (currentPrimary().unexpanded()) {
            throw new IllegalStateException("unexpanded primary cannot enter replanning from negotiation: " + currentPrimary().id());
        }
        if (currentSubtask() == null) {
            throw new IllegalStateException("no current subtask to replan from negotiation");
        }
        primaryStatus = PrimaryGoalStatus.REPLANNING;
        clearExecutionMetadata();
    }

    public synchronized void enterReplanningFromStuck(String reason) {
        if (primaryStatus != PrimaryGoalStatus.ACTIVE) {
            throw new IllegalStateException("only an ACTIVE primary may enter replanning from stuck: " + primaryStatus);
        }
        if (currentPrimary().unexpanded()) {
            throw new IllegalStateException("unexpanded primary cannot enter replanning from stuck: " + currentPrimary().id());
        }
        Subtask cur = currentSubtask();
        if (cur == null) {
            throw new IllegalStateException("no current subtask to replan");
        }
        SubtaskStatus st = statuses.get(cur.id());
        if (st != SubtaskStatus.FAILED && st != SubtaskStatus.STALLED) {
            throw new IllegalStateException("current subtask is not stuck: " + st);
        }
        primaryStatus = PrimaryGoalStatus.REPLANNING;
        clearExecutionMetadata();
    }

    /**
     * REPLANNING 出口：宿主为当前一级换了新二级计划 → 原位替换成 generated（保留一级
     * id/description/waitFor），旧二级状态作废、新二级全置 PENDING，subtask 指针归零回到 PENDING。
     *
     * <p><b>G3e</b>：存在未消费的安全点凭据时，这条<b>旧签名</b>一律拒绝（{@code SAFE_POINT_PENDING}）——
     * 否则「换计划」就能绕过冻结。要换请走 {@link #replaceCurrentSubtasks(List, SafePoint)}。
     */
    public synchronized void replaceCurrentSubtasks(List<Subtask> generated) {
        requireNoOutstandingSafePoint("replace plan");
        doReplaceCurrentSubtasks(generated);
    }

    /**
     * G3e：带安全点凭据的计划替换——凭据必须是 {@code REPLACE_PLAN} 用途、同 epoch、未过期、未消费，
     * 成功应用后<b>当场消费</b>（一次性）。
     */
    public synchronized void replaceCurrentSubtasks(List<Subtask> generated, SafePoint token) {
        requireSafePoint(token, SafePoint.Purpose.REPLACE_PLAN);
        doReplaceCurrentSubtasks(generated);
        consumeSafePoint(token);
    }

    private void doReplaceCurrentSubtasks(List<Subtask> generated) {
        stateVersion++;            // G3b：计划替换 = 安全提交（结构变化）
        if (primaryStatus != PrimaryGoalStatus.REPLANNING) throw new IllegalStateException("only a REPLANNING primary may have its plan replaced: " + primaryStatus);
        if (generated == null || generated.isEmpty()) throw new IllegalArgumentException("replacement requires at least one legal subtask");
        PrimaryGoal cur = currentPrimary();
        Set<String> ids = new HashSet<>();
        for (Subtask s : generated) {
            if (s == null) throw new IllegalArgumentException("null subtask in replacement");
            if (!ids.add(s.id())) throw new IllegalArgumentException("duplicate subtask id in replacement: " + s.id());
            // 只允许替换当前一级的二级：新 id 不得与其它一级已有的二级冲突。
            if (statuses.containsKey(s.id()) && !wasInPrimary(cur.id(), s.id()))
                throw new IllegalArgumentException("subtask id used outside the replaced primary: " + s.id());
        }
        List<Subtask> old = cur.subtasks();
        Set<String> freed = new HashSet<>();
        for (Subtask s : old) freed.add(s.id());
        List<PrimaryGoal> primaries = new ArrayList<>(goal.primaryGoals());
        primaries.set(primaryIndex, new PrimaryGoal(cur.id(), cur.description(), List.copyOf(generated), cur.waitFor(), false));
        goal = new Goal(goal.id(), goal.description(), primaries);
        for (String freedId : freed) {
            statuses.remove(freedId);
            skipReasons.remove(freedId);
            pauseReasons.remove(freedId);
            attempts.remove(freedId);
            forgetAcquireBaseline(freedId);
        }
        for (Subtask s : generated) {
            statuses.put(s.id(), SubtaskStatus.PENDING);
        }
        subtaskIndex = 0;
        primaryStatus = PrimaryGoalStatus.PENDING;
        clearExecutionMetadata(); // 换计划 = 旧执行身份/启动时间作废
    }

    // ---- G3c：计划补丁（propose 只校验；commit 在 stateVersion CAS 下原子应用） ----

    /** 已提交补丁的短回执（有界；用于观测「补丁为什么被拒/何时生效」，不存正文）。 */
    private final java.util.ArrayDeque<Map.Entry<String, Long>> committedPatches = new java.util.ArrayDeque<>();

    /**
     * 提出补丁：<b>只校验、不改状态</b>（75号 §15.3-5：proposePatch 只存候选，
     * 不立即进入 REPLANNING、不清在途执行）。
     *
     * <p>校验规则（首版刻意窄）：
     * 目标必须是<b>当前一级</b>；CAS 基线必须是当前 {@code stateVersion}；
     * ADD 的 id 全链唯一；REPLACE/DROP 只能碰 <b>PENDING</b> 节点（已完成/在跑/暂停一律拒绝）；
     * 补丁不得让当前一级变空；同一 patchId 已提交过 → {@code ALREADY_COMMITTED}。
     */
    public synchronized PlanPatch.Receipt proposePatch(PlanPatch patch) {
        PatchCheck check = validatePatch(patch, patch == null ? -1L : patch.baseStateVersion());
        return check.receipt();
    }

    /**
     * 提交补丁：CAS（{@code expectedStateVersion} 必须等于当前版本）后原子应用。
     *
     * <p>失败不留半更新：所有校验先做完，再一次性重建当前一级的二级列表；
     * 成功 {@code stateVersion + 1}（后续补丁/判定按新版本判新旧）。
     */
    public synchronized PlanPatch.Receipt commitPatch(PlanPatch patch, long expectedStateVersion) {
        PatchCheck check = validatePatch(patch, expectedStateVersion);
        if (!check.receipt().ok()) {
            return check.receipt();
        }
        List<Subtask> working = check.working();
        PrimaryGoal cur = currentPrimary();
        List<PrimaryGoal> primaries = new ArrayList<>(goal.primaryGoals());
        primaries.set(primaryIndex,
                new PrimaryGoal(cur.id(), cur.description(), List.copyOf(working), cur.waitFor(), false));
        goal = new Goal(goal.id(), goal.description(), primaries);
        // 新增节点的状态表初始化；被删节点的运行时残留清掉（只可能是 PENDING，无基线/计数）
        for (Subtask s : working) {
            statuses.putIfAbsent(s.id(), SubtaskStatus.PENDING);
        }
        for (String id : new ArrayList<>(statuses.keySet())) {
            if (working.stream().noneMatch(s -> s.id().equals(id)) && wasInPrimary(cur.id(), id)) {
                statuses.remove(id);
                skipReasons.remove(id);
                pauseReasons.remove(id);
                attempts.remove(id);
                forgetAcquireBaseline(id);
            }
        }
        stateVersion++;            // G3c：补丁提交 = 安全提交
        committedPatches.addLast(Map.entry(patch.patchId(), stateVersion));
        while (committedPatches.size() > 16) {
            committedPatches.removeFirst();
        }
        return PlanPatch.Receipt.ok(stateVersion);
    }

    /** 最近提交过的补丁（patchId → 版本），有界；只读。 */
    public synchronized Map<String, Long> committedPatchesView() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : committedPatches) {
            out.put(e.getKey(), e.getValue());
        }
        return Map.copyOf(out);
    }

    private record PatchCheck(PlanPatch.Receipt receipt, List<Subtask> working) {}

    private PatchCheck validatePatch(PlanPatch patch, long expectedVersion) {
        if (patch == null || patch.patchId() == null || patch.patchId().isBlank()) {
            return new PatchCheck(PlanPatch.Receipt.rejected("BAD_INPUT", "patch requires an id"), null);
        }
        if (patch.ops().isEmpty()) {
            return new PatchCheck(PlanPatch.Receipt.rejected("BAD_INPUT", "patch has no operations"), null);
        }
        if (!currentPrimary().id().equals(patch.primaryId())) {
            return new PatchCheck(PlanPatch.Receipt.rejected("NOT_CURRENT_PRIMARY",
                    "patch targets " + patch.primaryId() + " but current is " + currentPrimary().id()), null);
        }
        if (expectedVersion != stateVersion) {
            return new PatchCheck(PlanPatch.Receipt.rejected("STALE_VERSION",
                    "expected stateVersion " + expectedVersion + " but chain is at " + stateVersion), null);
        }
        for (Map.Entry<String, Long> e : committedPatches) {
            if (e.getKey().equals(patch.patchId())) {
                return new PatchCheck(PlanPatch.Receipt.rejected("ALREADY_COMMITTED",
                        "patch " + patch.patchId() + " was already committed at version " + e.getValue()), null);
            }
        }
        List<Subtask> working = new ArrayList<>(currentPrimary().subtasks());
        Set<String> allIds = new HashSet<>(statuses.keySet());
        for (PlanPatch.Op op : patch.ops()) {
            if (op == null || op.kind() == null || op.nodeId() == null || op.nodeId().isBlank()) {
                return new PatchCheck(PlanPatch.Receipt.rejected("BAD_INPUT", "operation requires kind and nodeId"), null);
            }
            switch (op.kind()) {
                case ADD_NODE -> {
                    if (op.condition() == null || op.description() == null || op.description().isBlank()) {
                        return new PatchCheck(PlanPatch.Receipt.rejected("BAD_INPUT",
                                "ADD_NODE requires description and condition: " + op.nodeId()), null);
                    }
                    if (allIds.contains(op.nodeId()) || working.stream().anyMatch(s -> s.id().equals(op.nodeId()))) {
                        return new PatchCheck(PlanPatch.Receipt.rejected("DUPLICATE_ID",
                                "node id already used in chain: " + op.nodeId()), null);
                    }
                    working.add(Subtask.hardCoded(op.nodeId(), op.description(), op.condition()));
                }
                case REPLACE_PENDING, DROP_PENDING -> {
                    int idx = -1;
                    for (int i = 0; i < working.size(); i++) {
                        if (working.get(i).id().equals(op.nodeId())) {
                            idx = i;
                            break;
                        }
                    }
                    if (idx < 0) {
                        return new PatchCheck(PlanPatch.Receipt.rejected("NODE_MISSING",
                                "node not found in current primary: " + op.nodeId()), null);
                    }
                    if (statuses.get(op.nodeId()) != SubtaskStatus.PENDING) {
                        return new PatchCheck(PlanPatch.Receipt.rejected("NODE_NOT_PENDING",
                                "only not-yet-started nodes may be patched: " + op.nodeId()
                                        + " is " + statuses.get(op.nodeId())), null);
                    }
                    // G3d 安全边界：只能改「当前节点及其之后」的待执行节点。
                    // 改到当前索引之前的节点会让 subtaskIndex 错位（删一个前面的节点 =
                    // 当前节点被挪到前一位，指针指向别人），那不是补丁，是重排。
                    if (idx < subtaskIndex) {
                        return new PatchCheck(PlanPatch.Receipt.rejected("NODE_BEFORE_CURSOR",
                                "cannot patch a node before the current pointer: " + op.nodeId()), null);
                    }
                    if (op.kind() == PlanPatch.OpKind.DROP_PENDING) {
                        if (working.size() <= 1) {
                            return new PatchCheck(PlanPatch.Receipt.rejected("WOULD_EMPTY",
                                    "patch would leave the current primary with no nodes"), null);
                        }
                        working.remove(idx);
                    } else {
                        if (op.condition() == null || op.description() == null || op.description().isBlank()) {
                            return new PatchCheck(PlanPatch.Receipt.rejected("BAD_INPUT",
                                    "REPLACE_PENDING requires description and condition: " + op.nodeId()), null);
                        }
                        Subtask old = working.get(idx);
                        // 保留原 body（身体指令不属于补丁范围）；只换描述与完成条件
                        working.set(idx, Subtask.hardCoded(op.nodeId(), op.description(), op.condition(), old.body()));
                    }
                }
            }
        }
        if (working.isEmpty()) {
            return new PatchCheck(PlanPatch.Receipt.rejected("WOULD_EMPTY", "patch left no nodes"), null);
        }
        return new PatchCheck(PlanPatch.Receipt.ok(stateVersion), List.copyOf(working));
    }

    /** RECOVERING 出口：停机恢复后宿主核实当前二级可继续 → 回到 ACTIVE，照常跑监督/完成判定。 */
    public synchronized void resumeFromRecovering() {
        if (primaryStatus != PrimaryGoalStatus.RECOVERING) throw new IllegalStateException("not recovering: " + primaryStatus);
        primaryStatus = PrimaryGoalStatus.ACTIVE;
        stateVersion++;            // G3b：恢复裁决是一次安全提交（改变了在途语义）
        clearExecutionMetadata(); // 恢复续跑不是"同一执行"：旧执行身份/启动时间作废
    }

    /** 执行元数据作废（离开在途执行的统一清点）：二级身份与启动时间只属于一个在途执行。 */
    private void clearExecutionMetadata() {
        activeExecutionId = null;
        lastStartedAtMillis = 0;
    }

    /** 查询某一级包含某二级 id（replaceCurrentSubtasks 校验用，避免误删其它一级）。 */
    private boolean wasInPrimary(String primaryId, String subtaskId) {
        for (PrimaryGoal primary : goal.primaryGoals()) {
            if (primary.id().equals(primaryId)) {
                for (Subtask s : primary.subtasks()) if (s.id().equals(subtaskId)) return true;
            }
        }
        return false;
    }

    /** 该一级是否被完成事实命中（归一主题键在 satisfiedStages 中）。 */
    private boolean isSatisfied(int index) {
        return satisfiedStages.contains(StageKeyNormalizer.normalize(goal.primaryGoals().get(index).description()));
    }

    /** 从 start 起找第一个未被完成事实命中的一级下标；全部命中返回 -1。 */
    private int firstUnsatisfiedFrom(int start) {
        for (int i = Math.max(0, start); i < goal.primaryGoals().size(); i++) {
            if (!isSatisfied(i)) {
                return i;
            }
        }
        return -1;
    }

    /** 只读：当前链继承的已完成阶段键集合。 */
    public synchronized Set<String> satisfiedStages() {
        return Set.copyOf(satisfiedStages);
    }

    public synchronized void markFailed(String subtaskId, String reason) {
        requireCurrent(subtaskId);
        SubtaskStatus st = statuses.get(subtaskId);
        if (st != SubtaskStatus.RUNNING && st != SubtaskStatus.STALLED) throw new IllegalStateException("current subtask is not running/stalled");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("failure reason required");
        statuses.put(subtaskId, SubtaskStatus.FAILED);
        stateVersion++;            // G3b：失败也是安全提交（会触发重试/重规划）
        activeExecutionId = null; // 二级离开 RUNNING：宿主执行身份作废
    }

    /** 监督检测到卡死（资产/工具长时间无变化）→ 置 STALLED，等待外部拍醒或升级。 */
    public synchronized void markStalled(String subtaskId, String reason) {
        requireCurrent(subtaskId);
        if (statuses.get(subtaskId) != SubtaskStatus.RUNNING) throw new IllegalStateException("current subtask is not running");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("stall reason required");
        statuses.put(subtaskId, SubtaskStatus.STALLED);
    }

    /** 监督拍醒后，检测到行为恢复 → 从 STALLED 回到 RUNNING（任务继续）。 */
    public synchronized void resumeFromStalled(String subtaskId) {
        requireCurrent(subtaskId);
        if (statuses.get(subtaskId) != SubtaskStatus.STALLED) throw new IllegalStateException("current subtask is not stalled");
        statuses.put(subtaskId, SubtaskStatus.RUNNING);
    }

    /** Level 2 局部恢复：失败的当前二级重置为 PENDING，可重新 startCurrent（AI 换策略再试）。 */
    public synchronized void retrySubtask(String subtaskId) {
        stateVersion++;            // G3b：重试 = 安全提交（状态从 FAILED 回到可开跑）
        requireCurrent(subtaskId);
        if (statuses.get(subtaskId) != SubtaskStatus.FAILED) throw new IllegalStateException("only a FAILED subtask may retry");
        statuses.put(subtaskId, SubtaskStatus.PENDING);
    }

    /**
     * Mark an explicitly optional current step as intentionally skipped and advance.
     * Skipping is different from failure: it is a deliberate replanning outcome and
     * must not trigger the retry/capability-gap loop.
     */
    public synchronized void skipSubtask(String subtaskId, String reason) {
        requireCurrent(subtaskId);
        if (primaryStatus != PrimaryGoalStatus.ACTIVE) throw new IllegalStateException("primary not active");
        SubtaskStatus st = statuses.get(subtaskId);
        if (st != SubtaskStatus.RUNNING && st != SubtaskStatus.FAILED && st != SubtaskStatus.STALLED) {
            throw new IllegalStateException("current subtask cannot be skipped from " + st);
        }
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("skip reason required");
        statuses.put(subtaskId, SubtaskStatus.SKIPPED);
        skipReasons.put(subtaskId, reason);
        advanceOrAwait();
    }

    public synchronized Map<String, SubtaskStatus> subtaskStatuses() {
        return Map.copyOf(statuses);
    }

    /**
     * 暂停当前二级：<b>不开始、留着、以后还能开</b>。
     *
     * <p>与 {@link #skipSubtask} 的关键差别是<b>不推进</b>——skip 立刻 {@code advanceOrAwait()} 走到下一个，
     * 而暂停原地不动：既不消耗重试预算，也不进 FAILED 的能力缺口/失败升级循环。
     * 规划器改主意时用 {@link #resumeFromPaused} 原地开回来。
     *
     * <p>之所以值得单列一个状态而不是复用 STALLED/FAILED：
     * STALLED 会被监督判成卡死并最终 markFailed（记忆库 {@code issue/waiting-gate-silent-stall-no-supervision}：
     * "没进展"与"故意不做"混在一起就会催工、逼它去干本来就不该干的事）；
     * FAILED 会走重试与能力缺口。两者都会破坏"先放着"这个意图。
     * <p>允许从 <b>STALLED</b> 暂停（2026-09-29 实机修正）：士兵察觉"我做不到"的那一刻，
     * 二级通常<b>已经是 STALLED</b>（做不到 → 资产无变化 → 15 秒 grace → 判卡死）。
     * 最初只放行 RUNNING/PENDING，结果实机第一跑就落到
     * {@code subtask_pause_rejected: cannot be paused from STALLED} → 白白退回重规划。
     * 「卡住 + 承认做不到」= 正该被按住，而不是被判失败后继续烧重试预算。
     */
    public synchronized void pauseSubtask(String subtaskId, String reason) {
        stateVersion++;            // G3b：暂停 = 安全提交
        requireCurrent(subtaskId);
        if (primaryStatus != PrimaryGoalStatus.ACTIVE) throw new IllegalStateException("primary not active");
        SubtaskStatus st = statuses.get(subtaskId);
        if (st != SubtaskStatus.RUNNING && st != SubtaskStatus.PENDING && st != SubtaskStatus.STALLED) {
            throw new IllegalStateException("current subtask cannot be paused from " + st);
        }
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("pause reason required");
        statuses.put(subtaskId, SubtaskStatus.PAUSED);
        pauseReasons.put(subtaskId, reason);
        clearExecutionMetadata(); // 暂停 = 不再有在途执行，旧执行身份/启动时间作废
    }

    /** 规划器改主意 → 把暂停的当前二级原地开回来（PAUSED → RUNNING，可直接续跑）。 */
    public synchronized void resumeFromPaused(String subtaskId) {
        stateVersion++;            // G3b：恢复 = 安全提交
        requireCurrent(subtaskId);
        if (statuses.get(subtaskId) != SubtaskStatus.PAUSED) throw new IllegalStateException("current subtask is not paused");
        statuses.put(subtaskId, SubtaskStatus.RUNNING);
        pauseReasons.remove(subtaskId);
    }

    /** 只读：某二级是否处于暂停（监测台/规划器快照用，不改状态）。 */
    public synchronized boolean isPaused(String subtaskId) {
        return statuses.get(subtaskId) == SubtaskStatus.PAUSED;
    }

    public synchronized Map<String, String> pauseReasonsView() {
        return Map.copyOf(pauseReasons);
    }

    /** Read-only structured view for monitoring; it is derived from the state owner. */
    public synchronized Map<String, Object> snapshot() {
        List<Map<String, Object>> primaries = new ArrayList<>();
        for (int p = 0; p < goal.primaryGoals().size(); p++) {
            PrimaryGoal primary = goal.primaryGoals().get(p);
            List<Map<String, Object>> subtasks = new ArrayList<>();
            for (int s = 0; s < primary.subtasks().size(); s++) {
                Subtask subtask = primary.subtasks().get(s);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", subtask.id());
                item.put("description", subtask.description());
                item.put("status", statuses.get(subtask.id()).name());
                if (skipReasons.containsKey(subtask.id())) item.put("skipReason", skipReasons.get(subtask.id()));
                if (pauseReasons.containsKey(subtask.id())) item.put("pauseReason", pauseReasons.get(subtask.id()));
                item.put("detectionMode", subtask.detectionMode().name());
                item.put("condition", subtask.condition());
                item.put("current", p == primaryIndex && s == subtaskIndex);
                // G3b：尝试次数进快照（G3a 的债：此前只能读持久化文件核对 IC03）
                item.put("attempts", attempts.getOrDefault(subtask.id(), 0));
                // ★ D20-C2（75号 15.4 基线可见性）：把 acquire 基线的存在性与取值暴露进快照。
                //   现在监测台只能看到 condition 里的 mode=acquire，但无法区分
                //   「基线还没建立(null)」和「基线=空表(当时真空背包)」——两者语义完全不同
                //   (75号15.4: UNKNOWN ≠ 真空)，从外面一眼看不出来，调试 acquire 永不满足时无从下手。
                //   判定语义一字不改（HardCodedEvaluator 的 null 守卫照旧），这只是观测面增量。
                if (acquireBaselines.containsKey(subtask.id())) {
                    item.put("acquireBaselineCaptured", true);
                    Map<String, Integer> baseline = acquireBaselines.get(subtask.id());
                    // 空表是合法值(死亡瞬间真空背包)，但要在观测面区分"捕获过且为空"与"从未捕获"——
                    // 所以为空表加显式标记，不为空时给 keys 便于核对。
                    if (baseline != null && !baseline.isEmpty()) {
                        item.put("acquireBaselineKeys", List.copyOf(baseline.keySet()));
                    } else {
                        item.put("acquireBaselineEmpty", true);
                    }
                } else {
                    item.put("acquireBaselineCaptured", false);
                }
                subtasks.add(item);
            }
            Map<String, Object> primaryView = new LinkedHashMap<>();
            primaryView.put("id", primary.id());
            primaryView.put("description", primary.description());
            primaryView.put("current", p == primaryIndex);
            primaryView.put("unexpanded", primary.unexpanded());
            if (!primary.waitFor().isEmpty()) {
                List<Map<String, Object>> wf = new ArrayList<>();
                for (AssetRequirement r : primary.waitFor()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("asset_key", r.assetKey());
                    m.put("minimum", r.minimum());
                    wf.add(m);
                }
                primaryView.put("waitFor", wf);
            }
            primaryView.put("subtasks", subtasks);
            primaries.add(primaryView);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("goalId", goal.id());
        view.put("description", goal.description());
        view.put("satisfiedStages", List.copyOf(satisfiedStages));
        view.put("primaryStatus", primaryStatus.name());
        view.put("primaryIndex", primaryIndex);
        view.put("subtaskIndex", subtaskIndex);
        // G3b：把「这一步开跑过几次」「安全状态变过几次」「挡掉过几次陈旧结果」带进快照 ——
        // 监测台据此能核对 IC03/IC06，不用去翻持久化文件（V-20261020-21 的债）。
        view.put("stateVersion", stateVersion);
        view.put("staleResultsIgnored", staleResultsIgnored);
        // G3e：安全点凭据的可见性——「为什么现在不能换计划」必须能一句话说清，
        // 而不是只能靠「调用抛了 SAFE_POINT_PENDING」去猜。
        view.put("ownerEpoch", ownerEpoch);
        SafePoint outstanding = outstandingSafePoint();
        Map<String, Object> safePoint = new LinkedHashMap<>();
        safePoint.put("outstanding", outstanding != null);
        if (outstanding != null) {
            safePoint.put("purpose", outstanding.purpose().name());
            safePoint.put("expiresInMs", outstanding.remainingMillis(System.currentTimeMillis()));
            safePoint.put("epoch", outstanding.ownerEpoch());
        }
        view.put("safePoint", safePoint);
        PrimaryGoal current = currentPrimary();
        view.put("currentPrimaryId", current.id());
        // 当前一级未展开时诚实报"无当前二级"，而不是伪造占位节点。
        view.put("currentSubtaskId", current.unexpanded() ? null : currentSubtask().id());
        if (primaryStatus == PrimaryGoalStatus.WAITING && !current.waitFor().isEmpty()) {
            view.put("waitingFor", currentPrimary().waitFor().stream().map(AssetRequirement::assetKey).toList());
        }
        view.put("primaries", primaries);
        return view;
    }

    /** 导出任务链状态为 JSON（持久化用：goal 结构 + 全部状态 + 当前指针 + 执行元数据）。 */
    public synchronized String toJson() {
        JsonObject o = new JsonObject();
        o.add("goal", GSON.toJsonTree(goal));
        JsonObject st = new JsonObject();
        for (Map.Entry<String, SubtaskStatus> e : statuses.entrySet()) {
            st.addProperty(e.getKey(), e.getValue().name());
        }
        o.add("statuses", st);
        o.add("skipReasons", GSON.toJsonTree(skipReasons));
        o.add("pauseReasons", GSON.toJsonTree(pauseReasons));
        o.add("attempts", GSON.toJsonTree(attempts));
        o.add("acquireBaselines", GSON.toJsonTree(acquireBaselines));
        JsonArray sat = new JsonArray();
        for (String k : satisfiedStages) {
            sat.add(k);
        }
        o.add("satisfiedStages", sat);
        o.addProperty("primaryIndex", primaryIndex);
        o.addProperty("subtaskIndex", subtaskIndex);
        o.addProperty("planRevision", planRevision);
        o.addProperty("primaryStatus", primaryStatus.name());
        o.addProperty("lastStartedAtMillis", lastStartedAtMillis);
        // G3b：安全状态版本与陈旧结果计数随链持久化（判据要活得过重启）
        o.addProperty("stateVersion", stateVersion);
        o.addProperty("staleResultsIgnored", staleResultsIgnored);
        // G3e：owner 代次随链持久化（旧凭据靠它作废；凭据本身不落盘，见字段注释）
        o.addProperty("ownerEpoch", ownerEpoch);
        if (activeExecutionId != null) o.addProperty("activeExecutionId", activeExecutionId);
        return GSON.toJson(o);
    }

    /**
     * 从 JSON 恢复任务链状态（游戏重启后 RECOVERING）。
     *
     * <p>恢复规则（P0-4）：磁盘上正 ACTIVE/RUNNING 的链不等于重启后真的能不告而续——
     * 停机期间的执行身份、身体任务全部作废。这里把「ACTIVE + 当前二级 RUNNING/STALLED」
     * 归一为 RECOVERING，由宿主核实当前二级可继续后再 {@link #resumeFromRecovering()}。
     * 其余状态原样恢复（未开始的 PENDING/WAITING、已停的 AWAITING_SUPERVISOR 等都不算在途执行）。
     */
    public static TaskChain fromJson(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        JsonObject goalObj = o.getAsJsonObject("goal");
        // 旧链兼容（A-1 前无 unexpanded 字段）：解析树里给缺该字段的一级补 false=已展开，
        // 不依赖 Gson 对缺失原始类型组件的默认行为。waitFor 等引用组件缺失 → 构造器已置空。
        JsonArray primaries = goalObj.getAsJsonArray("primaryGoals");
        for (JsonElement el : primaries) {
            JsonObject pg = el.getAsJsonObject();
            if (!pg.has("unexpanded")) {
                pg.addProperty("unexpanded", false);
            }
        }
        Goal goal = GSON.fromJson(goalObj, Goal.class);
        TaskChain chain = new TaskChain(goal);
        JsonObject st = o.getAsJsonObject("statuses");
        if (st == null) {
            throw new IllegalArgumentException("restored status table missing");
        }
        chain.statuses.clear();
        for (String k : st.keySet()) {
            chain.statuses.put(k, SubtaskStatus.valueOf(st.get(k).getAsString()));
        }
        chain.primaryIndex = o.get("primaryIndex").getAsInt();
        chain.subtaskIndex = o.get("subtaskIndex").getAsInt();
        // 旧链（无 planRevision 字段）= 0。**但不能真的当 0**：
        // 上一版存档的二级 id 已经是 `primary-r1-0` 这种带代次的形状，
        // 重启后第一次重规划又从 rev=1 开始 → 直接撞号，旧回执又能混进来。
        // 所以从磁盘上**实际存在的二级 id 反推一个下限**（见 adoptRevisionFromIds）。
        chain.planRevision = o.has("planRevision") ? o.get("planRevision").getAsInt() : 0;
        chain.adoptRevisionFromIds();
        chain.primaryStatus = PrimaryGoalStatus.valueOf(o.get("primaryStatus").getAsString());
        if (o.has("skipReasons") && o.get("skipReasons").isJsonObject()) {
            // 全量载入（不做加载期过滤）：skipReason 指向非 SKIPPED/未知二级属于损坏数据，
            // 交由 validateRestoredState 大声拒绝，而不是悄悄丢进 /dev/null。
            for (var entry : o.getAsJsonObject("skipReasons").entrySet()) {
                chain.skipReasons.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        // 旧存档（PAUSED 引入前写出的 JSON）没有这个键 —— 缺失即空，不算损坏。
        if (o.has("pauseReasons") && o.get("pauseReasons").isJsonObject()) {
            for (var entry : o.getAsJsonObject("pauseReasons").entrySet()) {
                chain.pauseReasons.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        if (o.has("attempts") && o.get("attempts").isJsonObject()) {
            JsonObject att = o.getAsJsonObject("attempts");            for (String k : att.keySet()) {
                chain.attempts.put(k, att.get(k).getAsInt());
            }
        }
        // acquire 基线（2026-09-30）：旧存档没有这个键 = 从没捕获过基线 → 空。
        // 语义后果是"acquire 判未达成"，**不是**损坏：重启后由宿主重新捕获，
        // 而不是拿一个错的基线判出假的完成。
        if (o.has("acquireBaselines") && o.get("acquireBaselines").isJsonObject()) {
            for (var entry : o.getAsJsonObject("acquireBaselines").entrySet()) {
                JsonObject counts = entry.getValue().isJsonObject() ? entry.getValue().getAsJsonObject() : null;
                if (counts == null) continue;
                Map<String, Integer> baseline = new LinkedHashMap<>();
                for (var c : counts.entrySet()) {
                    try {
                        baseline.put(c.getKey(), c.getValue().getAsInt());
                    } catch (RuntimeException malformed) {
                        throw new IllegalArgumentException("malformed acquireBaseline for " + entry.getKey(), malformed);
                    }
                }
                chain.acquireBaselines.put(entry.getKey(), Map.copyOf(baseline));
            }
        }
        if (o.has("lastStartedAtMillis")) {
            chain.lastStartedAtMillis = o.get("lastStartedAtMillis").getAsLong();
        }
        // G3b：旧存档无这两个字段 → 0（向后兼容；不因为升级就伪造"提交过"）
        if (o.has("stateVersion")) {
            chain.stateVersion = o.get("stateVersion").getAsLong();
        }
        if (o.has("staleResultsIgnored")) {
            chain.staleResultsIgnored = o.get("staleResultsIgnored").getAsInt();
        }
        // G3e：owner 代次向后兼容读作 0；凭据本身不落盘（重启后必须重签，见字段注释）
        if (o.has("ownerEpoch")) {
            chain.ownerEpoch = o.get("ownerEpoch").getAsLong();
        }
        // 完成事实继承层（P0）：旧链无此字段 → 空集（向后兼容）
        if (o.has("satisfiedStages") && o.get("satisfiedStages").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("satisfiedStages")) {
                chain.satisfiedStages.add(el.getAsString());
            }
        }
        if (o.has("activeExecutionId") && !o.get("activeExecutionId").isJsonNull()) {
            chain.activeExecutionId = o.get("activeExecutionId").getAsString();
        }
        // 在途执行归一（P0-4）：必须基于恢复后的状态表判，不能基于未恢复的链。
        if (chain.primaryStatus == PrimaryGoalStatus.ACTIVE) {
            Subtask cur = chain.currentSubtask();
            SubtaskStatus curStatus = cur == null ? null : chain.statuses.get(cur.id());
            if (curStatus == SubtaskStatus.RUNNING || curStatus == SubtaskStatus.STALLED) {
                chain.primaryStatus = PrimaryGoalStatus.RECOVERING;
                chain.activeExecutionId = null; // 停机作废：宿主执行实例身份不跨重启
            }
        }
        // 幂等清：已是 RECOVERING（二次重启读到自己刚存的 RECOVERING）也一样作废执行身份，
        // 防"休眠期间宿主编入过 bindExecution 的残留 id"被第三次原样恢复。
        if (chain.primaryStatus == PrimaryGoalStatus.RECOVERING) {
            chain.activeExecutionId = null;
        }
        chain.validateRestoredState();
        return chain;
    }

    private void advanceOrAwait() {
        activeExecutionId = null; // 二级离开 RUNNING：宿主执行身份作废
        if (subtaskIndex + 1 < currentPrimary().subtasks().size()) { subtaskIndex++; return; }
        primaryStatus = PrimaryGoalStatus.AWAITING_SUPERVISOR;
    }
    private void requireCurrent(String id) { if (id == null || currentSubtask() == null || !currentSubtask().id().equals(id)) throw new IllegalArgumentException("stale subtask: " + id); }

    /** Reject corrupt handoff state before it can reach a live executor or monitor. */
    private void validateRestoredState() {
        if (primaryIndex < 0 || primaryIndex >= goal.primaryGoals().size()) {
            throw new IllegalArgumentException("primary index out of range: " + primaryIndex);
        }
        Set<String> expected = new LinkedHashSet<>();
        for (PrimaryGoal primary : goal.primaryGoals()) {
            for (Subtask subtask : primary.subtasks()) {
                if (!expected.add(subtask.id())) {
                    throw new IllegalArgumentException("duplicate subtask id in restored goal: " + subtask.id());
                }
            }
        }
        if (!statuses.keySet().equals(expected)) {
            Set<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(statuses.keySet());
            Set<String> unknown = new LinkedHashSet<>(statuses.keySet());
            unknown.removeAll(expected);
            throw new IllegalArgumentException("restored status table mismatch; missing=" + missing + ", unknown=" + unknown);
        }
        for (String id : attempts.keySet()) {
            if (!expected.contains(id)) {
                throw new IllegalArgumentException("restored attempts table references unknown subtask: " + id);
            }
        }
        for (String id : skipReasons.keySet()) {
            if (!expected.contains(id)) {
                throw new IllegalArgumentException("restored skipReason references unknown subtask: " + id);
            }
            if (statuses.get(id) != SubtaskStatus.SKIPPED) {
                throw new IllegalArgumentException("restored skipReason on non-skipped subtask: " + id);
            }
        }
        for (String id : pauseReasons.keySet()) {
            if (!expected.contains(id)) {
                throw new IllegalArgumentException("restored pauseReason references unknown subtask: " + id);
            }
            if (statuses.get(id) != SubtaskStatus.PAUSED) {
                throw new IllegalArgumentException("restored pauseReason on non-paused subtask: " + id);
            }
        }
        // 基线表只做"引用完整性"校验：不许指向不存在的二级（那会让基线永远查不到、acquire 卡死）。
        // 注意**不**校验"该二级必须正在跑"——重试/暂停期间基线继续存在是合法的。
        for (String id : acquireBaselines.keySet()) {
            if (!expected.contains(id)) {
                throw new IllegalArgumentException("restored acquireBaseline references unknown subtask: " + id);
            }
        }
        PrimaryGoal current = goal.primaryGoals().get(primaryIndex);
        // 完成事实层自洽：非终态的当前一级不得是被事实命中的阶段（否则永远停在已达成阶段）
        if (primaryStatus != PrimaryGoalStatus.COMPLETED && isSatisfied(primaryIndex)) {
            throw new IllegalArgumentException("current primary is fact-satisfied but chain is not COMPLETED: " + current.id());
        }
        if (current.unexpanded()) {
            if (subtaskIndex != 0) {
                throw new IllegalArgumentException("unexpanded primary must have subtask index 0: " + subtaskIndex);
            }
            if (primaryStatus == PrimaryGoalStatus.ACTIVE) {
                // ACTIVE+未展开 = 永远 return 的静默卡死（当前二级为 null，走状态分支全部落空）。
                throw new IllegalArgumentException("ACTIVE primary cannot be unexpanded; must be PENDING/WAITING until expansion");
            }
        } else if (subtaskIndex < 0 || subtaskIndex >= current.subtasks().size()) {
            throw new IllegalArgumentException("subtask index out of range: " + subtaskIndex);
        }
    }
}
