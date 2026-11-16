package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.provider.IToolSpec;
import com.dwinovo.numen.rdd.api.PrimarySpec;
import com.dwinovo.numen.rdd.core.PlanningAssetSnapshot;
import com.dwinovo.numen.rdd.core.RddChainFactory;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Stage-A：把整条目标（{@code /goal 通关MC}）先规划成 N 个按序推进的一级发展阶段（主题 + 可选跨级资产门）。
 *
 * <p>只产一级清单，<b>绝不展开二级</b>——一级到达后由宿主目标驱动器调 Stage-B 懒展开。
 * 任何失败（无 key/LLM 错/坏 JSON/空/不可执行）都回调空表；上层据此回落单遍 decompose
 * （§10：目标不被吞），或在懒边界进 §9 恢复。{@link #parse} 纯静态可单测。
 */
final class RddStagePlanner {
    private RddStagePlanner() {}

    /** 主入口：异步规划一级清单，回调收到可用的 PrimarySpec 列表（失败=空表）。 */
    static void planStages(UUID companionId, String objective, Consumer<List<PrimarySpec>> done) {
        planStages(companionId, objective, null, done);
    }

    /**
     * @param why 发起方随目标带来的结构化理由；建链前还没有链，所以不查 RationaleContext 的链作用域。
     */
    static void planStages(UUID companionId, String objective,
                           com.dwinovo.numen.api.RationaleContext.Entry why,
                           Consumer<List<PrimarySpec>> done) {
        // 注入"当前真实背包"：规划师要站在已有资产上推进，不倒退重规划已持有的装备/设施。
        // 再贴上经验知识（无知识时与原来逐字相同）。
        PlanningAssetSnapshot snapshot = RddPlugin.planningSnapshot(companionId);
        // 2026-10-25（主人指出）：规划师缺"我有啥/我在哪/世界里有啥"。补一块紧凑事实包，
        // 只给事实不给策略；整块 ≤420 字符，读不到写"未观测"。
        String facts = "";
        try {
            var srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            var self = srv == null ? null : srv.getPlayerList().getPlayer(companionId);
            facts = self == null ? RddPlannerFacts.block(snapshot.availableCounts())
                    : RddPlannerFacts.block(self, companionId);
        } catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(RddStagePlanner.class)
                    .warn("[rdd-facts] 事实包生成失败：{}", ex.toString());
            facts = "";
        }
        org.slf4j.LoggerFactory.getLogger(RddStagePlanner.class)
                .info("[rdd-facts] 事实包长度={} 片段={}", facts.length(),
                        facts.length() > 120 ? facts.substring(0, 120).replace("\n", " ") : facts.replace("\n", " "));
        dispatchStages(companionId, objective, why, snapshot, facts, RddPlanningKnowledge.HOST,
                () -> freshRationale(companionId), userContent ->
                RddDecomposer.llmAsk(companionId, "stage_a", userContent, PLAN_SYSTEM, PLAN_TOOL,
                args -> {
                    RddPlanGuard.Stages guarded = RddPlanGuard.filterStages(parse(args), objective);
                    List<PrimarySpec> withSmeltPrep = RddRiskPlanning.ensureSmeltPrepStage(
                            guarded.allowed(), snapshot.availableCounts());
                    List<PrimarySpec> prepared = RddRiskPlanning.ensurePrepStage(withSmeltPrep, RddChainFactory.MAX_STAGES);
                    List<PrimarySpec> planned = RddRiskPlanning.injectWaitFor(prepared, snapshot.availableCounts());
                    RddPlanningKnowledge.publishPolicy(companionId, "stage_a",
                            RddPlanningPolicy.appliedRules(objective, "stage_a"),
                            guarded.dropped(), List.of());
                    done.accept(planned);
                },
                () -> done.accept(List.of())));
    }

    /** The real Stage-A prompt/provider/final-send path, with only host facts and transport supplied. */
    static void dispatchStages(UUID companionId, String objective,
                               com.dwinovo.numen.api.RationaleContext.Entry why, PlanningAssetSnapshot snapshot,
                               String facts, RddPlanningKnowledge.Provider knowledge,
                               java.util.function.Supplier<String> currentRationale, Consumer<String> send) {
        String base = RddPlanningKnowledge.attach(
                RddRiskPlanning.prepHint(objective, snapshot.availableCounts())
                        + facts + "\n" + planningPrompt(objective, snapshot),
                RddPlanningPolicy.block(objective, "stage_a"));
        // 044 §0.7: the planning request must also carry the structured why. Execution already
        // renders this section; planning used to omit it, so the planner never saw the reason,
        // risks, provenance or applicability that the executor got.
        // 建链前的规划：用发起方带进来的理由（no chain yet），没有就退回到链作用域判定。
        String whyText = why != null
                ? com.dwinovo.numen.api.RationaleContext.renderEntry(why)
                : currentRationale.get();
        RddMonitor.publish("plan_final_request", java.util.Map.of(
                "companionId", companionId.toString(),
                "requestKind", "plan",
                "rationalePresent", !whyText.isBlank(),
                "rationaleFrom", why != null ? "submitter" : "chain_scope"));
        String userContent = withRationale(RddPlanningKnowledge.withKnowledge(
                knowledge, companionId, base, objective, "stage_a", List.of()),
                whyText);
        send.accept(userContent);
    }

    /**
     * 容错解析 plan_stages 参数 JSON → PrimarySpec 列表。
     * 阶段缺主题丢弃；wait_for 资产键不可执行（裸键/占位/大写）或 minimum 为负 → 整条门丢弃
     * （阶段保留、无门也合法，跨级资产门是增强不是必须）。显式 equipment_target 非法则整批拒绝，
     * 不丢字段/阶段或返回合法前缀；缺字段保留旧语义。解析彻底失败返回空表。
     */
    static List<PrimarySpec> parse(String argumentsJson) {
        return RddStagePlanParser.parse(argumentsJson);
    }

    // ---------- prompt 与合成工具 ----------

    private static final String PLAN_SYSTEM =
            "你是 MC 女仆的目标规划师。把主人的整条目标规划成 2~12 个按顺序推进、串起来能完成整条目标的一级发展阶段（阶段/phase）。"
                    + "每个阶段 = 一条该阶段要达成的发展主线（如\"石器时代：做石斧石镐、备好基础材料\"），"
                    + "不是单个物品，也不是整条战役。阶段按依赖顺序排列，前面的阶段达成后才能进入下一个。"
                    + "若某阶段开工前背包必须先持有某物品，在 wait_for 给出真实 minecraft 命名空间 ID"
                    + "（如 minecraft:stone_pickaxe）和 minimum；无法确定的跨级前置不要硬写。"
                    + "阶段若要求完成时四槽实穿装备，用 equipment_target 明确 feet/legs/chest/head 各槽 ID 或 #tag；"
                    + "这是阶段输出义务，不是 wait_for 背包前置，单头盔/库存不能替代四槽实穿。"
                    + "缺字段沿用旧语义，不从 theme 猜出装备义务。"
                    + "只输出 plan_stages 工具调用，不要写多余文字。";

    private static final String PLAN_BODY_TAIL =
            "请用 plan_stages 工具给出发展阶段清单。每个阶段包含：\n"
                    + "- theme：该阶段要达成的发展主线（可读、自含，能被据此拆出可执行的子步骤）\n"
                    + "- wait_for（可选）：进入该阶段前必须已持有的前置资产，{asset_key: 真实命名空间ID, minimum: 数量}\n"
                    + "- equipment_target（可选）：本阶段完成时必须四槽同时实穿的输出义务，"
                    + "{feet: ID/#tag, legs: ID/#tag, chest: ID/#tag, head: ID/#tag}；"
                    + "四个 canonical 键必须全齐且无多余键，值为真实命名空间 ID 或 #tag。"
                    + "单头盔、背包库存或 wait_for 都不能替代；未声明字段保持旧语义，不按 theme 猜义务。\n"
                    // 2026-10-28（主人口径）：数量分两类——
                    // ① 配方/规则能算死的（钻石镐 3 钻、下界门 10 黑曜石、末影之眼 12），照写实数量；
                    // ② 只有到现场才知道的（矿脉储量、林子木头、附近动物），别写大数，写小值起步，
                    //    到了地方再按实际情况补。
                    + "wait_for 的 minimum：能由配方/规则算死的写准确数（如传送门 10 个黑曜石）；"
                    + "只有到现场才知道多少的（矿脉、木材、附近动物）写小值（1~3）起步，"
                    + "剩余靠她到地方看清后自己补，不要预先写一个大目标。\n"
                    + "阶段间不要遗漏：从现状一路推到主人目标完成。"
                    // 2026-10-28（主人）：一级清单也不许"为了走完时代顺序"硬排中间档。
                    //   注意这是规划期的先验；运行期还能用 rdd_settle_stage 把已经没意义的阶段收口。
                    + "**能力优先于流程**：若现有的东西已经能完成某个阶段的目的（已有钻石镐 → 石镐/铁镐阶段没有意义；"
                    + "已有铁镐 → 不必再排石镐阶段），就不要排那个阶段；目标是「到终点」，不是「把时代列表走一遍」。\n";

    /** Same rule the execution prompt applies: an entry from another plan revision is stale, not "current". */
    static boolean rationaleApplicable(boolean hasEntry, long rationaleGeneration, long chainPlanRevision) {
        return hasEntry && RddRationale.fresh(rationaleGeneration, chainPlanRevision);
    }

    /**
     * Rationale for a planning request. The entry is keyed by companion and stamped with the
     * planRevision it was written for, so anything else (no chain yet, newer plan, restart with
     * a different revision) is treated as stale and omitted rather than presented as current.
     */
    static String freshRationale(java.util.UUID companionId) {
        if (companionId == null) return "";
        var runtime = RddPlugin.runtime(companionId);
        if (runtime == null) return "";
        var chain = runtime.chain();
        // Atomic check-and-render: one snapshot decides both, so a concurrent write cannot
        // pass the check with new state and then render a different entry.
        return com.dwinovo.numen.api.RationaleContext.renderApplicable(
                companionId, chain.planRevision(), chain.chainInstanceId());
    }

    /** Append the rationale section verbatim when present; blank input leaves the prompt unchanged. */
    static String withRationale(String content, String rationale) {
        if (content == null) return "";
        if (rationale == null || rationale.isBlank()) return content;
        return content + "\n" + rationale;
    }

    static String planningPrompt(String objective, Map<String, Integer> held) {
        return planningPrompt(objective, held, "");
    }

    static String planningPrompt(String objective, Map<String, Integer> held, String worldAssets) {
        return composePlanningPrompt(objective, renderHeldAssets(held), worldAssets);
    }

    /** P1.5：Planner 直接吃统一资产快照（含"已失去/不确定"显式告知）。 */
    static String planningPrompt(String objective, PlanningAssetSnapshot snapshot, String worldAssets) {
        return composePlanningPrompt(objective, renderHeldAssets(snapshot), worldAssets);
    }

    /** 规划主路径（2026-10-07 起）：目标 + 当前持有 + 策略，不再贴世界资产/村庄/时间成本。 */
    static String planningPrompt(String objective, PlanningAssetSnapshot snapshot) {
        return composePlanningPrompt(objective, renderHeldAssets(snapshot), "", "");
    }

    /** P2-D：附带已观测村庄事实块（先事实，策略留给规划师评价）。 */
    static String planningPrompt(String objective, PlanningAssetSnapshot snapshot,
                                 String worldAssets, String villageBlock) {
        return composePlanningPrompt(objective, renderHeldAssets(snapshot), worldAssets, villageBlock);
    }

    private static String composePlanningPrompt(String objective, String heldBlock, String worldAssets) {
        return composePlanningPrompt(objective, heldBlock, worldAssets, "");
    }

    private static String composePlanningPrompt(String objective, String heldBlock,
                                                String worldAssets, String villageBlock) {
        // 2026-10-07 用户裁决：世界资产块 / 村庄事实列表 / 可恢复线索 / P2-E 时间成本刻度 不再注入
        // 规划 prompt —— 固定旧债，白烧 token（烧钱），还会盖过主人写进经验库的路线。
        // 参数保留只为不动一串签名（调用点已不再传真值）。
        // 2026-10-27（主人要的「写题型注入规划 AI」）：长线终局目标补一段**阶段骨架**
        // ——它填的是"这类目标长什么样"的先验，不是具体步数；普通目标不注入（空串）。
        String patterns = RddStagePatterns.block(objective);
        return "主人的目标：" + objective + "\n\n"
                + heldBlock
                + (patterns.isEmpty() ? "" : patterns + "\n\n")
                + PLAN_BODY_TAIL;
    }

    /** 背包上下文块：空背包返回空串（不写"空"误导 LLM），非空按 key 排序保证输出稳定。 */
    private static String renderHeldAssets(Map<String, Integer> held) {
        if (held == null || held.isEmpty()) {
            return "";
        }
        String items = new TreeMap<>(held).entrySet().stream()
                .map(e -> "- " + e.getKey() + " ×" + e.getValue())
                .collect(Collectors.joining("\n"));
        return "【你当前已真实持有（背包扫描）：】\n" + items + "\n\n"
                + "【规划铁律】站在\"已经拥有上面这些\"继续推进：已持有的装备/工具/设施（如 wooden_pickaxe、crafting_table）"
                + "不要在任何阶段重复规划重新获取；theme 从现状往下一时代推进，不倒退。"
                + "只有后续会被消耗掉的东西（食物、合成/烧炼原料）才按需要规划补量。\n\n";
    }

    /** P1.5：来自统一快照的背包块——可持有 + 显式告知"已失去/不确定"。 */
    private static String renderHeldAssets(PlanningAssetSnapshot snap) {
        StringBuilder sb = new StringBuilder();
        if (snap != null && !snap.availableCounts().isEmpty()) {
            String items = snap.availableCounts().entrySet().stream()
                    .map(e -> "- " + e.getKey() + " ×" + e.getValue())
                    .collect(Collectors.joining("\n"));
            sb.append("【你当前已真实持有（背包扫描）：】\n").append(items).append("\n\n")
                    .append("【规划铁律】站在\"已经拥有上面这些\"继续推进：已持有的装备/工具/设施（如 wooden_pickaxe、crafting_table）")
                    .append("不要在任何阶段重复规划重新获取；theme 从现状往下一时代推进，不倒退。")
                    .append("只有后续会被消耗掉的东西（食物、合成/烧炼原料）才按需要规划补量。\n\n");
        } else {
            // 【C-补】空背包也要显式告知，避免"没块"与"空背包"无法区分（正是难查的根因）。
            sb.append("【你当前已真实持有（背包扫描）：】（背包为空或尚未观测到——按空背包规划）\n\n");
        }
        if (snap != null && !snap.lostIds().isEmpty()) {
            sb.append("【已失去（死亡/掉落判定失效，绝不要假设还持有，需要就重新获取）：】")
                    .append(String.join("、", snap.lostIds())).append("\n\n");
        }
        if (snap != null && !snap.unknownIds().isEmpty()) {
            sb.append("【状态不确定（先核实再依赖）：】")
                    .append(String.join("、", snap.unknownIds())).append("\n\n");
        }
        return sb.toString();
    }

    /** 合成工具：LLM 直接以结构化 JSON 返回一级清单（引擎无 response_format:json_object）。 */
    private static final IToolSpec PLAN_TOOL = new IToolSpec() {
        @Override public String name() { return "plan_stages"; }
        @Override public String description() {
            return "把主人的整条目标规划成按顺序推进的多个一级发展阶段（主题，不含具体子步骤）。"
                    + "每个阶段的 theme 描述该阶段要达成什么；可选的 wait_for 给进入该阶段前必须已持有的资产"
                    + "（asset_key 必须是真实 minecraft 命名空间 ID）；equipment_target 是完成时四槽同时实穿的输出义务，"
                    + "不是背包前置；缺字段保留旧语义。";
        }
        @Override public Map<String, Object> parameterSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "stages", Map.of(
                                    "type", "array",
                                    "items", Map.of(
                                            "type", "object",
                                            "properties", Map.of(
                                                    "theme", Map.of("type", "string",
                                                            "description", "该阶段要达成的发展主线（可读、自含）"),
                                                    "wait_for", Map.of(
                                                            "type", "array",
                                                            "description", "进入该阶段前必须已持有的前置资产（可选）",
                                                            "items", Map.of(
                                                                    "type", "object",
                                                                    "properties", Map.of(
                                                                            "asset_key", Map.of("type", "string",
                                                                                    "description", "真实 minecraft 命名空间 ID，如 minecraft:stone_pickaxe"),
                                                                            "minimum", Map.of("type", "integer",
                                                                                    "description", "最少持有数量，缺省 1")),
                                                                    "required", List.of("asset_key"))),
                                                    "equipment_target", Map.of(
                                                            "type", "object",
                                                            "description", "阶段完成时四槽同时实穿的输出义务（可选），不是背包前置；ID 或 #tag，四键全齐",
                                                            "properties", Map.of(
                                                                    "feet", Map.of("type", "string"),
                                                                    "legs", Map.of("type", "string"),
                                                                    "chest", Map.of("type", "string"),
                                                                    "head", Map.of("type", "string")),
                                                            "required", List.of("feet", "legs", "chest", "head"),
                                                            "additionalProperties", false)),
                                            "required", List.of("theme")))),
                    "required", List.of("stages"));
        }
    };
}
