package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.llm.BudgetPolicy;
import com.dwinovo.numen.agent.llm.LlmEndpoint;
import com.dwinovo.numen.agent.llm.LlmObservation;
import com.dwinovo.numen.agent.llm.NumenLlmClient;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.IToolSpec;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.api.NumenGateway;
import com.dwinovo.numen.api.WorldContextStamp;
import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.SubtaskSpec;
import com.dwinovo.numen.rdd.core.PlanningAssetSnapshot;
import com.dwinovo.numen.rdd.core.RddChainFactory;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.google.gson.Gson;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 客户端侧的 LLM 目标分解器：把 {@code /goal} 的自然语言目标拆成多个带真实资产条件
 * （+可选身体指令）的 HARD_CODED 二级目标。
 *
 * <p>宿主在线程内采集世界事实；仅端点解析与请求发送转到客户端，HTTP 在传输线程跑，结果经
 * {@code Minecraft.getInstance().execute} 弹回主线程。任何失败/无 key/坏 JSON
 * 都回落 {@link RddChainFactory#fromObjective} 占位链——目标绝不被吞。
 *
 * <p>{@link #parse} 是纯静态容错解析，可独立单测。
 */
final class RddDecomposer {
    private static final Logger LOG = LoggerFactory.getLogger(RddDecomposer.class);
    private static final Gson GSON = new Gson();

    private RddDecomposer() {}

    /**
     * 共享 LLM 单轮 + 单合成工具 传输（客户端主线程）。成功把首个工具调用的 arguments JSON
     * 交回调；任何失败（无 key/LLM 错/未调工具）交 onFail。两段都在 {@code Minecraft.getInstance().execute}
     * 弹回主线程——调用方据此决定回落或走 §9 失败恢复，绝不在传输层伪造结果。
     */
    /**
     * 「现场事实」前缀（2026-10-28 实机教训）：
     * 重规划之后给出的仍是同一个步骤（obsidian≥10），因为规划器不知道她**人在下界**——
     * 黑曜石在下界几乎拿不到（要水桶浇岩浆），在主世界才有现成的废墟传送门框。
     * 所以每次规划都先用一句话把"现在在哪个维度、那个维度的硬约束"递到它眼前。
     * 纯文本、极短，不占多少 token。
     */
    static String situationBlock(UUID companionId) {
        try {
            var body = com.dwinovo.numen.plugins.rdd.RddCompanionBody.get(companionId);
            if (body == null) return "";
            String dim = body.level().dimension().location().toString();
            String hint;
            if (dim.contains("the_nether")) {
                hint = "下界：不能放床（放了会炸）、没有水和树、熔岩遍地、出行靠自己建的传送门；"
                        + "黑曜石/木头这类东西在下界很难拿到，需要的话回主世界取。";
            } else if (dim.contains("the_end")) {
                hint = "末地：没有水、不能睡、掉下去就回不来；补给必须事先带够。";
            } else {
                hint = "主世界：水和树都有，怪物夜间出没。";
            }
            return "【现场事实】当前所在维度：" + dim + "（" + hint + "）\n\n";
        } catch (RuntimeException | LinkageError ignored) {
            return "";
        }
    }

    static void llmAsk(UUID companionId, String stage, String userContent, String system, IToolSpec tool,
                       Consumer<String> onArguments, Runnable onFail) {
        // Capture world facts on the caller's thread before waiting for the owner client.
        String requestContent = situationBlock(companionId) + userContent;
        withPlannerEndpoint(companionId, stage, ep -> {
            RddPlugin.publishPlanningContext(companionId, stage, requestContent, system, tool);
            return NumenLlmClient.forEndpoint(ep)
                    .chatStreaming(List.of(new ConvoState.Msg.User(requestContent)), List.of(tool), system, null,
                            new LlmObservation("supervisor", companionId.toString(), stage, RddMonitor::publish));
        }, result -> {
            AssistantTurn turn = result == null ? null : result.turn();
            if (turn == null || !turn.hasToolCalls()) { onFail.run(); return; }
            onArguments.accept(turn.toolCalls().get(0).arguments());
        }, onFail);
    }

    /** Only normal owned Stage-B may request one exact asset detail, then one final plan. */
    static void llmAskOwnedStageB(UUID companionId, TaskChain owner, long ownerRevision, String userContent,
                                 Consumer<String> onArguments, Runnable onFail) {
        if (owner == null) {
            llmAsk(companionId, "stage_b", userContent, RddDecomposerPrompt.SYSTEM_PROMPT,
                    RddDecomposerPrompt.DECOMPOSE_TOOL, onArguments, onFail);
            return;
        }
        var scope = RddStageBAssetHost.capture(companionId, owner, ownerRevision);
        if (scope == null) { onFail.run(); return; }
        var settled = new java.util.concurrent.atomic.AtomicBoolean();
        Consumer<String> complete = args -> { if (settled.compareAndSet(false, true)) onArguments.accept(args); };
        Runnable fail = () -> RddStageBAssetHost.dispatch(scope, () -> {
            if (settled.compareAndSet(false, true)) onFail.run();
        });
        String content = situationBlock(companionId) + userContent + "\n\n" + scope.catalog()
                + "\nYou may call rdd_assets once with action=detail and one exact asset_id from the catalog. "
                + "Historical detail is not current availability. After its tool result, call decompose_goal only. "
                + "Use exactly one tool call per response. No refresh, catalog scan or repeated detail request.";
        stageBAssetRound(companionId, scope, List.of(new ConvoState.Msg.User(content)), "", complete, fail);
    }

    private static void stageBAssetRound(UUID companionId, RddStageBAssetHost.Context scope,
                                        List<ConvoState.Msg> messages, String detailCallId,
                                        Consumer<String> complete, Runnable fail) {
        var plan = RddDecomposerPrompt.DECOMPOSE_TOOL;
        List<IToolSpec> tools = detailCallId.isEmpty() ? List.of(plan, new RddAssetsTool()) : List.of(plan);
        String system = detailCallId.isEmpty()
                ? RddDecomposerPrompt.SYSTEM_PROMPT.replace("只输出 decompose_goal 工具调用，不要写多余文字。",
                        "可先调用一次 rdd_assets 精确detail；收到工具结果后只输出 decompose_goal。每轮恰一个调用，不写多余文字。")
                : RddDecomposerPrompt.SYSTEM_PROMPT;
        withPlannerEndpoint(companionId, "stage_b", endpoint -> RddStageBAssetHost.send(scope, () -> {
            RddPlugin.publishPlanningContext(companionId, "stage_b", GSON.toJson(messages), system, plan);
            return NumenLlmClient.forEndpoint(endpoint).chatStreaming(messages, tools, system, null,
                    new LlmObservation("supervisor", companionId.toString(), "stage_b", RddMonitor::publish));
        }), result -> RddStageBAssetHost.dispatch(scope, () -> {
            AssistantTurn turn = result == null ? null : result.turn();
            if (turn == null || !turn.hasToolCalls() || turn.toolCalls().size() != 1) { fail.run(); return; }
            LlmToolCall call = turn.toolCalls().getFirst();
            if (call.id() == null || call.id().isBlank() || call.id().length() > 256
                    || call.id().equals(detailCallId)) { fail.run(); return; }
            if (plan.name().equals(call.name())) { complete.accept(call.arguments()); return; }
            if (!detailCallId.isEmpty() || !"rdd_assets".equals(call.name())) { fail.run(); return; }
            com.google.gson.JsonObject args;
            try {
                args = com.google.gson.JsonParser.parseString(call.arguments()).getAsJsonObject();
                if (!args.keySet().equals(java.util.Set.of("action", "asset_id"))
                        || !args.get("action").isJsonPrimitive() || !args.getAsJsonPrimitive("action").isString()
                        || !"detail".equals(args.get("action").getAsString())
                        || !args.get("asset_id").isJsonPrimitive() || !args.getAsJsonPrimitive("asset_id").isString()
                        || args.get("asset_id").getAsString().isBlank()
                        || args.get("asset_id").getAsString().length() > 1024) { fail.run(); return; }
            } catch (RuntimeException invalid) { fail.run(); return; }
            String reply = RddStageBAssetHost.read(scope, args);
            if (reply == null) return; // Original scope lost: do not fail or read on behalf of a replacement.
            RddMonitor.publish("stage_b_asset_detail", Map.of("companionId", companionId.toString(),
                    "toolCallId", call.id(), "assetId", args.get("asset_id").getAsString(), "replyChars", reply.length()));
            var followup = new ArrayList<ConvoState.Msg>(messages);
            followup.add(new ConvoState.Msg.Assistant(turn));
            followup.add(new ConvoState.Msg.Tool(call.id(), reply));
            stageBAssetRound(companionId, scope, List.copyOf(followup), call.id(), complete, fail);
        }), fail);
    }

    /** Concern replies are correlated host requests, never generic first-tool arguments. */
    static void llmAskConcern(UUID companionId, String userContent,
                              Consumer<String> onArguments, Runnable onFail) {
        String content = situationBlock(companionId) + userContent;
        var tool = RddDecomposerPrompt.DECOMPOSE_TOOL;
        withPlannerEndpoint(companionId, "stage_b", endpoint -> {
            RddPlugin.publishPlanningContext(companionId, "stage_b", content, RddDecomposerPrompt.SYSTEM_PROMPT, tool);
            return NumenLlmClient.forEndpoint(endpoint).chatStreaming(List.of(new ConvoState.Msg.User(content)),
                    List.of(tool), RddDecomposerPrompt.SYSTEM_PROMPT, null,
                    new LlmObservation("supervisor", companionId.toString(), "stage_b", RddMonitor::publish));
        }, result -> {
            AssistantTurn turn = result == null ? null : result.turn();
            if (turn == null || !turn.hasToolCalls() || turn.toolCalls().size() != 1
                    || !tool.name().equals(turn.toolCalls().get(0).name())) { onFail.run(); return; }
            onArguments.accept(turn.toolCalls().get(0).arguments());
        }, onFail);
    }

    private static <T> void withPlannerEndpoint(UUID companionId, String stage,
            java.util.function.Function<LlmEndpoint, java.util.concurrent.CompletableFuture<T>> send,
            Consumer<T> onResult, Runnable onFail) {
        withPlannerEndpoint(companionId, stage, NumenGateway::plannerEndpoint,
                task -> Minecraft.getInstance().execute(task), WorldContextStamp::epoch,
                (phase, estimate) -> {
                    BudgetPolicy.Advice block = BudgetPolicy.checkBlock(phase, estimate);
                    if (block == null) return true;
                    notifyPlanningBlock(companionId, phase, block);
                    return false;
                }, send, onResult, onFail);
    }

    /** Both real sends use this seam; transport setup and caller callback failures stay distinct. */
    static <T> void withPlannerEndpoint(UUID companionId, String stage,
            java.util.function.Function<UUID, java.util.concurrent.CompletableFuture<LlmEndpoint>> lookup,
            java.util.concurrent.Executor client, java.util.function.LongSupplier generation,
            java.util.function.BiPredicate<String, Long> budget,
            java.util.function.Function<LlmEndpoint, java.util.concurrent.CompletableFuture<T>> send,
            Consumer<T> onResult, Runnable onFail) {
        RddPlannerTransport.withPlannerEndpoint(companionId, stage, lookup, client, generation, budget, send, onResult, onFail);
    }


    /**
     * enforce 预算拦截的通知（限频，同角色冷却内只发一次）：日志 + rdd.jsonl 的
     * {@code budget_block} 事件。拦截本身由调用点执行，这里只负责「不静默」。
     */
    private static void notifyPlanningBlock(UUID companionId, String stage, BudgetPolicy.Advice block) {
        // G4c S103：**先记原因，再做限频通知**。占位链要解释「为什么降级」这件事不能依赖
        // 通知限频（notifyBlock 冷却期内会直接 return，那样原因就丢了）。
        if (companionId != null) {
            RECENT_PLANNING_BLOCK.put(companionId, new RecentBlock(
                    "planning 预算用尽（" + String.join("+", block.exceeded()) + "）",
                    System.currentTimeMillis()));
        }
        if (!BudgetPolicy.notifyBlock(block)) return;
        LOG.warn("[rdd] 预算拦截: stage={} role={} exceeded={} usedTokens={} limitTokens={}",
                stage, block.role(), block.exceeded(), block.used().tokens(), block.limit().tokensPerWindow());
        RddMonitor.publish("budget_block", Map.ofEntries(
                Map.entry("actor", "supervisor"),
                Map.entry("stage", stage),
                Map.entry("status", "blocked"),
                Map.entry("role", block.role()),
                Map.entry("exceeded", String.join("+", block.exceeded())),
                Map.entry("usedTokens", block.used().tokens()),
                Map.entry("usedCalls", block.used().calls()),
                Map.entry("usedMillis", block.used().millis()),
                Map.entry("projectedTokens", block.projectedTokens()),
                Map.entry("limitTokens", block.limit().tokensPerWindow()),
                Map.entry("limitCalls", block.limit().callsPerWindow()),
                Map.entry("limitMinutes", block.limit().minutesPerWindow()),
                Map.entry("mode", block.mode())));
    }

    /** 最近一次「planning 被预算拦下」的原因（G4c S103；15 秒内有效，取一次就清）。 */
    private record RecentBlock(String reason, long atMs) {}

    private static final java.util.Map<UUID, RecentBlock> RECENT_PLANNING_BLOCK =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 所有「回落占位链」都必须走这里（G4c S103）。
     *
     * <p>为什么需要统一入口：`RddDecomposer` 里有 <b>5 处</b>回落（无 key / LLM 失败 / 没调工具 /
     * 规格不可用 / 预算拦下），只给其中一处加原因等于没加（实测：拦在 `stage_b` 走的是另一个回调）。
     * 这里按「<b>刚被预算拦过（15 秒内）</b>」决定要不要把原因写进目标正文，然后清掉 ——
     * 免得一次预算拦截的解释粘到十分钟后一个完全无关的失败上。
     */
    static Goal fallbackGoal(UUID companionId, String objective) {
        if (companionId != null) {
            RecentBlock rb = RECENT_PLANNING_BLOCK.remove(companionId);
            if (rb != null && System.currentTimeMillis() - rb.atMs() <= 15_000L) {
                return RddChainFactory.fromObjective(companionId, objective, rb.reason());
            }
        }
        return RddChainFactory.fromObjective(companionId, objective);
    }

    /** 生存闸：需要先吃时产出首任务链（只做进食，其余目标等它完成后再规划）。 */
    private static Goal survivalFirst(UUID companionId, String objective) {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        var body = com.dwinovo.numen.entity.NumenPlayer.findByUuid(server, companionId);
        if (body == null) return null;
        int hunger = body.getFoodData().getFoodLevel();
        double hp = body.getHealth();
        int free = 0;
        int size = Math.min(36, body.getInventory().getContainerSize());
        for (int i = 0; i < size; i++) {
            if (body.getInventory().getItem(i).isEmpty()) free++;
        }
        // 2026-11-06 21:44：包里没有能吃的东西时，“去吃”永远做不完（实测 hunger 6 连刷 4 次），
        // 必须改成“先弄到吃的”。
        boolean hasFood = RddSurvivalGate.hasEdibleFood(RddDetector.countInventory(body));
        var decision = RddSurvivalGate.evaluate(hunger, hp, free, false, hasFood);
        if (decision == RddSurvivalGate.Decision.NONE) return null;
        String head = switch (decision) {
            case EAT_FIRST -> "先活下来：背包满就先丢掉/存好一组大宗材料腾出格子，再取或合成食物并吃掉，hunger 回到 15 以上。";
            case SEEK_FOOD_FIRST -> "先活下来：背包里没有能直接吃的东西——先去弄吃的（收割成熟作物；有 3 个小麦就找台子合成面包；"+
                    "都没有就就近打猎/钓鱼并烤熟），吃到 hunger 15 以上再干别的。";
            case MAKE_ROOM_FIRST -> "背包满且有东西要捡/要合成：先丢掉或存好一组大宗材料腾出一格。";
            case NONE -> "";   // 上面已提前 return，这里只为编译完备
        };
        LOG.info("[rdd] 生存闸命中 {} hunger={} hp={} free={}", decision, hunger, hp, free);
        return RddChainFactory.fromObjective(companionId, head + "完成后再继续原目标：" + objective);
    }

    /** 目标里是否在建家/安家（决定要不要贴建家顺序参考）。 */
    private static boolean mentionsHome(String objective) {
        if (objective == null) return false;
        String s = objective.toLowerCase(java.util.Locale.ROOT);
        return s.contains("家") || s.contains("基地") || s.contains("村")
                || s.contains("home") || s.contains("base");
    }

    /** 主入口：异步分解目标，回调收到一个可用的 Goal（成功=分解链，失败=占位链）。 */
    static void decompose(UUID companionId, String objective, Consumer<Goal> done) {
        decompose(companionId, objective, null, done);
    }

    static void decompose(UUID companionId, String objective,
                          com.dwinovo.numen.api.RationaleContext.Entry why, Consumer<Goal> done) {
        // 2026-11-06 生存闸（内容层唯一判据）：hunger<10 / hp<8 时跳过常规分解，直出进食首任务。
        try {
            Goal survival = survivalFirst(companionId, objective);
            if (survival != null) {
                Minecraft.getInstance().execute(() -> done.accept(survival));
                return;
            }
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 生存闸判定失败，按原流程分解: {}", ex.toString());
        }
        // 仍在原宿主线程采集事实；等待客户端端点时不重跑生存闸、资产或身体读取。
        // 经验知识贴进最终请求正文（无知识时与原来逐字相同）
        PlanningAssetSnapshot snapshot = RddPlugin.planningSnapshot(companionId);
        var miningContext = RddMiningCapabilityCheck.capture(companionId);
        String userContent = fallbackRequestContent(companionId, objective, why, snapshot, RddPlanningKnowledge.HOST);
        Runnable onFail = () -> done.accept(fallbackGoal(companionId, objective));
        withPlannerEndpoint(companionId, "fallback", ep -> {
            RddPlugin.publishPlanningContext(companionId, "fallback", userContent, RddDecomposerPrompt.SYSTEM_PROMPT, RddDecomposerPrompt.DECOMPOSE_TOOL);
            return NumenLlmClient.forEndpoint(ep)
                    .chatStreaming(List.of(new ConvoState.Msg.User(userContent)),
                            List.of(RddDecomposerPrompt.DECOMPOSE_TOOL), RddDecomposerPrompt.SYSTEM_PROMPT, null,
                            new LlmObservation("supervisor", companionId.toString(), "fallback", RddMonitor::publish));
        }, result -> {
            if (result == null) {
                LOG.warn("[rdd] 分解 LLM 调用失败，回落占位链");
                onFail.run();
                return;
            }
            AssistantTurn turn = result.turn();
            if (turn == null || !turn.hasToolCalls()) {
                LOG.warn("[rdd] LLM 未调用分解工具，回落占位链");
                done.accept(fallbackGoal(companionId, objective));
                return;
            }
            LlmToolCall call = turn.toolCalls().get(0);
            // 守门：硬规则由代码执行，模型不听也要拦住（未要求的可选升级、已持有的资产）
            var parsed = RddStageBSpecParser.parseDetailed(call.arguments());
            RddMiningCapabilityCheck.filter(miningContext, parsed.specs(), objective,
                    snapshot.availableCounts(), guarded -> {
                        RddPlanningKnowledge.publishPolicy(companionId, "fallback",
                                RddPlanningPolicy.appliedRules(objective, "fallback"),
                                guarded.dropped(), guarded.reused());
                        List<SubtaskSpec> specs = stageBPublished(parsed, guarded, objective);
                        if (specs.isEmpty()) {
                            LOG.warn("[rdd] 分解结果不可用，回落占位链");
                            done.accept(fallbackGoal(companionId, objective));
                            return;
                        }
                        // 167 §19 第 4 条：输入里已经写明的「实测做不到」，不许再被排进计划。
                        try {
                            var facts = com.dwinovo.numen.rdd.policy.MeasuredFacts.extract(userContent);
                            java.util.List<com.dwinovo.numen.rdd.policy.PlanConsistency.Subtask> view2 =
                                    new java.util.ArrayList<>();
                            for (SubtaskSpec spec : specs) {
                                view2.add(new com.dwinovo.numen.rdd.policy.PlanConsistency.Subtask(
                                        spec.description(), spec.condition()));
                            }
                            var violations = com.dwinovo.numen.rdd.policy.MeasuredFacts.violations(facts, view2);
                            if (!violations.isEmpty()) {
                                RddMonitor.publish("plan_violates_measured_fact", java.util.Map.of(
                                        "companionId", companionId.toString(),
                                        "violations", String.join(" | ", violations),
                                        "note", "输入里已写明做不到，计划却又排了同样的步骤（167 §19）"));
                            }
                        } catch (RuntimeException ignored) {
                            // 检查失败不影响分解
                        }
                        // 167 §17：计划自洽检查——只发事件、不拦、不改判据。
                        try {
                            java.util.List<com.dwinovo.numen.rdd.policy.PlanConsistency.Subtask> view = new java.util.ArrayList<>();
                            for (SubtaskSpec spec : specs) {
                                view.add(new com.dwinovo.numen.rdd.policy.PlanConsistency.Subtask(
                                        spec.description(), spec.condition()));
                            }
                            var warnings = com.dwinovo.numen.rdd.policy.PlanConsistency.check(objective, view);
                            if (!warnings.isEmpty()) {
                                RddMonitor.publish("plan_inconsistent", java.util.Map.of(
                                        "companionId", companionId.toString(),
                                        "objective", objective.length() > 120 ? objective.substring(0, 120) : objective,
                                        "warnings", String.join(" | ", warnings),
                                        "note", "正文写的数量大于子任务要求：照它执行会「造一半就算完成」（167 §17）"));
                            }
                        } catch (RuntimeException ignored) {
                            // 检查失败不影响分解
                        }
                        Goal goal;
                        try {
                            goal = RddChainFactory.fromSpec(companionId, objective, specs);
                        } catch (RuntimeException ex) {
                            LOG.warn("[rdd] 分解规格非法，回落占位链");
                            onFail.run();
                            return;
                        }
                        done.accept(goal); // Caller errors are not an LLM failure and must not trigger done twice.
                    });
        }, onFail);
    }

    /** Actual single-pass final content; no host reads, so failed Stage-A handoff is directly testable. */
    static String fallbackRequestContent(UUID companionId, String objective,
                                         com.dwinovo.numen.api.RationaleContext.Entry why,
                                         PlanningAssetSnapshot snapshot, RddPlanningKnowledge.Provider knowledge) {
        String homeHint = mentionsHome(objective)
                ? System.lineSeparator() + System.lineSeparator()
                  + com.dwinovo.numen.rdd.policy.HomeBuildPlan.hint() + System.lineSeparator()
                : "";
        return RddStagePlanner.withRationale(RddPlanningKnowledge.withKnowledge(knowledge, companionId,
                RddRiskPlanning.prepHint(objective, snapshot.availableCounts())
                        + homeHint + decompositionPrompt(objective, snapshot, List.of())
                        + RddMiningPrerequisite.prompt(objective),
                objective, "fallback", List.of()), com.dwinovo.numen.api.RationaleContext.renderEntry(why));
    }

    /**
     * Stage-B：把"当前一级主题"当迷你目标懒展开成二级规格列表（spec §9：进入一级前必须生成
     * ≥1 个合法可执行二级）。任何失败（无 key/LLM 错/坏 JSON/解析为空）回调<b>空表</b>——
     * 成功与否由宿主目标驱动器判定并走 §9 恢复，绝不在此回落占位假二级（asset_key="goal"
     * 这类不可执行节点不得冒充"展开成功"）。
     */
    static void decomposeSpecs(UUID companionId, TaskChain owner, long ownerRevision,
                               String themeObjective, int attempt,
                               List<String> completedStages, Consumer<List<SubtaskSpec>> done) {
        decomposeSpecsWithHint(companionId, owner, ownerRevision, themeObjective, attempt, completedStages, "", done);
    }

    /**
     * Stage-B 带额外提示重载（P4 重规划用）：把重规划上下文（失败原因/真实资产/完成事实/风险缺口）
     * 追加进提示词，让本次重分解站在真实状态上换计划。
     */
    static void decomposeSpecsWithHint(UUID companionId, String themeObjective, int attempt,
                                       List<String> completedStages, String extraHint,
                                       Consumer<List<SubtaskSpec>> done) {
        // Independent recovery objectives have no chain-theme owner. Keep planning, but
        // omit chain rationale rather than attach the unrelated current mainline's reason.
        decomposeSpecsWithHint(companionId, null, -1L, themeObjective, attempt, completedStages, extraHint, done);
    }

    /**
     * 2026-11-17 V02：Stage-B 发布裁决。必需步骤被 schema/guard 静默丢弃时返回空表，
     * 走既有有界空计划恢复；不能把可完成的木材前缀当整级成功安装。
     * 可选升级裁剪、已持有复用、DAILY 跳过仍按原 allowed 发布。
     */
    static List<SubtaskSpec> stageBPublished(RddStageBSpecParser.Parsed parsed, RddPlanGuard.Filtered guarded,
                                             String objective) {
        boolean optionalAllowed = RddPlanningPolicy.optionalAllowed(objective);
        for (var skipped : parsed.skippedRequired()) {
            boolean unrequestedOptional = !optionalAllowed
                    && RddPlanningPolicy.optionalAsset(skipped.assetKey());
            if (skipped.nodeType() == com.dwinovo.numen.rdd.api.NodeType.MAIN
                    && !unrequestedOptional
                    && !RddPlanGuard.looksMaintenance(skipped.description())) {
                return List.of();
            }
        }
        return guarded.allowed();
    }

    /** The caller already owns the theme: carry that exact chain/revision, never infer it later. */
    static void decomposeSpecsWithHint(UUID companionId, TaskChain owner, long ownerRevision,
                                       String themeObjective, int attempt, List<String> completedStages,
                                       String extraHint, Consumer<List<SubtaskSpec>> done) {
        // 2026-11-02：死亡原因/坐标也进 knownFailures（否则规划侧永远召不回死亡教训）。
        // 上一次被判定不可执行 → 把该失败事实一并交给知识侧，召回「这类失败该怎么办」的经验
        List<String> knownFailures = new java.util.ArrayList<>(RddDeathSpotFacts.knownFailuresFor(companionId));
        if (attempt >= 1) {
            knownFailures.add("上一次生成的子步骤被判定不可执行");
        }
        PlanningAssetSnapshot snapshot = RddPlugin.planningSnapshot(companionId);
        var miningContext = RddMiningCapabilityCheck.capture(companionId);
        dispatchStageB(companionId, themeObjective, owner, ownerRevision, attempt, completedStages, extraHint,
                snapshot, knownFailures, RddPlanningKnowledge.HOST,
                () -> {
                    var runtime = RddPlugin.runtime(companionId);
                    return runtime == null ? null : runtime.chain();
                }, userContent -> RddDecomposer.llmAskOwnedStageB(companionId, owner, ownerRevision, userContent,
                args -> {
                    var parsed = RddStageBSpecParser.parseDetailed(args);
                    RddMiningCapabilityCheck.filter(miningContext, RddPlanGuard.normalizeConditions(parsed.specs()),
                            themeObjective, snapshot.availableCounts(), guarded -> {
                        RddPlanningKnowledge.publishPolicy(companionId, "stage_b",
                                RddPlanningPolicy.appliedRules(themeObjective, "stage_b"),
                                guarded.dropped(), guarded.reused());
                        done.accept(stageBPublished(parsed, guarded, themeObjective));
                    });
                },
                () -> done.accept(List.of())));
    }

    record ConcernPlan(List<SubtaskSpec> specs, RddStageBSpecParser.ConcernProposal proposal) {}

    /** Same Stage-B prompt, knowledge, endpoint and publication guards; old list callers stay unchanged. */
    static void decomposeConcernSpecs(UUID companionId, TaskChain owner, long ownerRevision,
                                     String theme, int attempt, List<String> completed, String hint,
                                     String concernRef, Consumer<ConcernPlan> done) {
        PlanningAssetSnapshot snapshot = RddPlugin.planningSnapshot(companionId);
        var miningContext = RddMiningCapabilityCheck.capture(companionId);
        String reviewHint = hint + "\n\nConcern review ref=" + concernRef
                + "\nReturn concern_review with this exact ref, a proposal, reason and basis. "
                + "A proposal is not host verification. If evidence is missing use DEFERRED and an actionable resume_condition.";
        dispatchStageB(companionId, theme, owner, ownerRevision, attempt, completed, reviewHint,
                snapshot, new java.util.ArrayList<>(RddDeathSpotFacts.knownFailuresFor(companionId)), RddPlanningKnowledge.HOST,
                () -> { var runtime = RddPlugin.runtime(companionId); return runtime == null ? null : runtime.chain(); },
                content -> llmAskConcern(companionId, content, arguments -> {
                    var parsed = RddStageBSpecParser.parseDetailed(arguments);
                    var proposal = RddStageBSpecParser.parseConcernProposal(arguments, concernRef);
                    RddMiningCapabilityCheck.filter(miningContext, RddPlanGuard.normalizeConditions(parsed.specs()),
                            theme, snapshot.availableCounts(), guarded -> {
                        RddPlanningKnowledge.publishPolicy(companionId, "stage_b",
                                RddPlanningPolicy.appliedRules(theme, "stage_b"), guarded.dropped(), guarded.reused());
                        done.accept(new ConcernPlan(stageBPublished(parsed, guarded, theme), proposal));
                    });
                }, () -> done.accept(new ConcernPlan(List.of(), null))));
    }

    /**
     * The real Stage-B assembly and dispatch seam. Only host reads and transport are supplied;
     * prompt, knowledge consumption and the final request are the production path, not a mirror.
     * Caller identity is included so a controlled owner change can be tested without Minecraft.
     */
    static void dispatchStageB(UUID companionId, String themeObjective,
                               TaskChain owner, long ownerRevision, int attempt,
                               List<String> completedStages, String extraHint, PlanningAssetSnapshot snapshot,
                               List<String> knownFailures, RddPlanningKnowledge.Provider knowledge,
                               java.util.function.Supplier<TaskChain> currentChain,
                               Consumer<String> send) {
        String hint = attempt >= 1
                ? "\n注意：上一次生成的子步骤被判定不可执行——每个 asset_key 必须是完整的小写命名空间 ID"
                + "（如 minecraft:oak_log），condition 必须有真实可检测物品，minimum 取「拿到即算」的小值"
                + "（采集类不要写「必须凑够 N 个」）。"
                + "不要裸键/占位符/大写，不要用 \"goal\" 冒充物品。宁可少拆，不可拆出跑不动的步骤。"
                : "";
        String base = RddPlanningKnowledge.attach(
                RddRiskPlanning.prepHint(themeObjective, snapshot.availableCounts())
                        + decompositionPrompt(themeObjective, snapshot,
                        completedStages == null ? List.of() : completedStages) + hint
                        + (extraHint == null || extraHint.isBlank() ? "" : "\n\n" + extraHint),
                RddPlanningPolicy.block(themeObjective, "stage_b"));
        String userContent = RddPlanningKnowledge.withKnowledge(knowledge, companionId,
                base, themeObjective, "stage_b", knownFailures);
        // 2026-11-02（r25-2→r26-0 实机：counter 说「门没有路」，新计划仍写「借同一扇门」）：
        // 先证明失败原因到底有没有进 prompt——只记长度与关键词，不存正文。
        LOG.info("[rdd] stage_b 请求 {} 字 | 失败上下文={} | 含不可达关键词={} | 含 3x3={}",
                userContent.length(),
                extraHint != null && !extraHint.isBlank(),
                userContent.contains("no path") || userContent.contains("不可达")
                        || userContent.contains("没有路"),
                userContent.contains("3×3") || userContent.contains("3x3"));
        // Optional knowledge may replace the runtime or advance its plan. The theme still
        // belongs to the caller: never borrow a replacement's reason, even at the same revision.
        var chain = currentChain.get();
        boolean applicableOwner = owner != null && chain == owner && owner.planRevision() == ownerRevision;
        String rationale = applicableOwner
                ? com.dwinovo.numen.api.RationaleContext.renderApplicable(
                        companionId, ownerRevision, owner.chainInstanceId())
                : "";
        Map<String, String> equipmentTarget = applicableOwner ? owner.currentPrimary().equipmentTarget() : null;
        if (equipmentTarget != null) {
            userContent += "\n\n## 当前一级的结构化装备目标\nequipment_target=" + GSON.toJson(equipmentTarget)
                    + "\n必须包含一个非 optional 的 MAIN 子步骤：condition.type=equipment，condition.slots 与 equipment_target 四槽键值完全一致"
                    + "（物品 ID / #tag 原样保留），在单次检测中同时覆盖 feet/legs/chest/head。"
                    + "四个先后单槽检查、背包持有、仅 head、optional 或 DAILY 步骤均不能代替该整组条件；如有 dimension 约束须保留。";
        }
        send.accept(stageBRequestContent(userContent, rationale));
    }

    /**
     * Stage-B final-content seam, called by the real request path above.
     * The existing applicable rationale must reach Stage-B just as it reaches Stage-A,
     * so decomposition retains the reasons behind the current chain.
     * No output schema, invented reason, API change or persistence is introduced here.
     */
    static String stageBRequestContent(String knowledgeContent, String applicableRationale) {
        return RddStagePlanner.withRationale(knowledgeContent, applicableRationale);
    }

    /**
     * 容错解析 LLM 的 {@code decompose_goal} 参数 JSON → 规格列表。
     * 坏 JSON / 缺 subtasks 返回空；字段缺失 / 资产键不可执行丢弃对应条目；显式 slots 条目非法或第 9 个合法条目使整批返回空列表
     * （调用方据此回落占位链或走 §9 失败恢复）。不对称错误代价：宁可失败，不可拿残缺链冒充真分解。
     */
    static List<SubtaskSpec> parse(String argumentsJson) {
        return RddStageBSpecParser.parse(argumentsJson);
    }



    // ---------- prompt 与合成工具：渲染已拆到 RddDecomposerPrompt；原入口保持不变 ----------

    /** 兼容旧测试/调用方的常量 API；真实归属 {@link RddDecomposerPrompt}。 */
    static final int ID_LIST_MAX_IDS = RddDecomposerPrompt.ID_LIST_MAX_IDS;
    static final int ID_LIST_MAX_CHARS = RddDecomposerPrompt.ID_LIST_MAX_CHARS;

    static String systemPrompt() { return RddDecomposerPrompt.systemPrompt(); }

    static java.util.Map<String, Object> decomposeSchema() { return RddDecomposerPrompt.decomposeSchema(); }

    static String decompositionPrompt(String objective, Map<String, Integer> held,
                                      List<String> completedStages) {
        return RddDecomposerPrompt.decompositionPrompt(objective, held, completedStages);
    }

    static String decompositionPrompt(String objective, Map<String, Integer> held,
                                      List<String> completedStages, String worldAssets) {
        return RddDecomposerPrompt.decompositionPrompt(objective, held, completedStages, worldAssets);
    }

    static String decompositionPrompt(String objective, PlanningAssetSnapshot snapshot,
                                      List<String> completedStages) {
        return RddDecomposerPrompt.decompositionPrompt(objective, snapshot, completedStages);
    }

    static String decompositionPrompt(String objective, PlanningAssetSnapshot snapshot,
                                      List<String> completedStages, String worldAssets) {
        return RddDecomposerPrompt.decompositionPrompt(objective, snapshot, completedStages, worldAssets);
    }

    static String decompositionPrompt(String objective, PlanningAssetSnapshot snapshot,
                                      List<String> completedStages, String worldAssets, String villageBlock) {
        return RddDecomposerPrompt.decompositionPrompt(objective, snapshot, completedStages, worldAssets, villageBlock);
    }

    static String boundedIdList(List<String> ids) { return RddDecomposerPrompt.boundedIdList(ids); }

    static String boundedIdList(List<String> ids, int maxChars, int reservedAfter) {
        return RddDecomposerPrompt.boundedIdList(ids, maxChars, reservedAfter);
    }

    static String marker(int n) { return RddDecomposerPrompt.marker(n); }
}
