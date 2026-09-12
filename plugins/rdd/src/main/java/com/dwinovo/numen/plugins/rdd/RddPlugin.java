package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugin;
import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.core.RddChainFactory;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.core.TaskChain;
import net.minecraft.server.level.ServerPlayer;
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
    private static final Set<UUID> DECOMPOSING = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, BodyState> BODY = new ConcurrentHashMap<>();
    private static final AtomicLong BODY_CALLS = new AtomicLong();
    /** assist 协助模式下暂停自动工具提交(防双驾驶);默认 true = RDD 可自动提交。 */
    private static volatile boolean bodySubmissionEnabled = true;
    /** 空转止血：是否允许"监督拍醒"主动干预（nudge/自动重试/失败升级/重派身体）。
     *  默认 true。暂停时 Detector 退化为纯观察——真实资产检测推进 + EarlyAchievement 照常，
     *  但绝不 nudge 注入 AI / 自动重试 / 升级判失败（LLM 空转止血）。 */
    private static volatile boolean supervisionEnabled = true;
    /** 开关文件 config/numen/rdd-supervision.flag：内容含 "pause"(或 "0") → 暂停监督。 */
    private static volatile Path supervisionFlag;
    /** 最近一次真实背包快照（Detector 每秒写，uuid→物品ID→数量）。规划注入用；不清除=背包是女仆属性与链无关。 */
    private static final Map<UUID, Map<String, Integer>> LAST_INVENTORY = new ConcurrentHashMap<>();
    /** setup 时保存的插件门面，用于监督拍醒（nudge 注入内置 AI）。 */
    private static volatile NumenApi numenApi;
    /** 任务链持久化目录 config/numen/rdd-tasks（每个同伴一个 <uuid>.json）。 */
    private static volatile Path tasksDir;

    record BodyState(String subtaskId, int submitCount) {}

    @Override
    public void setup(NumenApi numen) {
        numenApi = numen;
        tasksDir = numen.configDir().resolve("rdd-tasks");
        supervisionFlag = numen.configDir().resolve("rdd-supervision.flag");
        numen.registerTool(new RddStatusTool());
        numen.registerTool(new RddSubmitTool());
        // 接管 /goal：先同步认领，Stage-A 异步规划；规划期间 NUMEN 原生目标循环让位。
        com.dwinovo.numen.agent.goal.GoalSinks.register((uuid, objective) -> {
            if (uuid == null || objective == null || objective.isBlank()) {
                return false;
            }
            DECOMPOSING.add(uuid);
            BODY.remove(uuid);
            RddMonitor.publish("supervisor_input", Map.of(
                    "companionId", uuid.toString(), "objective", objective,
                    "source", "goal_sink", "target", "rdd"));
            // 两段懒展开：Stage-A 先把整条目标规划成 N 个未展开一级(阶段主题)；失败回落单遍分解(行为不劣化)。
            RddStagePlanner.planStages(uuid, objective, stages -> {
                if (!stages.isEmpty()) {
                    try {
                        remove(uuid);
                        bind(uuid, RddChainFactory.fromStages(uuid, objective, stages));
                        RddMonitor.publish("goal_staged", Map.of(
                                "companionId", uuid.toString(), "objective", objective,
                                "stages", stages.size()));
                        RddPlugin.publishTaskSnapshot(uuid, "goal_staged");
                        // 首级未展开：不 startCurrent(会抛)。懒展开由 Detector 上报 + RddGoalDriver 接手。
                        LOG.info("[rdd] Stage-A 规划 {} 级已绑定 {}:{}", stages.size(), uuid, objective);
                    } catch (RuntimeException ex) {
                        LOG.warn("[rdd] Stage-A 装配失败，回落单遍分解: {}", ex.toString());
                        decomposeSinglePass(uuid, objective); // DECOMPOSING 由单遍回调释放
                        return;
                    } finally {
                        DECOMPOSING.remove(uuid);
                    }
                    return;
                }
                // Stage-A 退化(无 key/LLM 失败/空/全不可执行) -> 回落今天的单遍 decompose
                decomposeSinglePass(uuid, objective);
            });
            LOG.info("[rdd] 接管目标，Stage-A 规划中 {}:{}", uuid, objective);
            return true;
        });
        com.dwinovo.numen.agent.goal.GoalSinks.registerClear((uuid, reason) -> {
            if (uuid != null) {
                DECOMPOSING.remove(uuid);
                remove(uuid);
                LOG.info("[rdd] 目标清掉,移除任务链 {}", uuid);
                return true;
            }
            return false;
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

    private static final Map<UUID, String> LAST_CONTEXT = new java.util.concurrent.ConcurrentHashMap<>();

    /** Exact RDD state block returned to Numen; observation does not own task progress. */
    private static String renderStateContext(UUID uuid) {
            if (DECOMPOSING.contains(uuid)) {
                return "<rdd><enabled>true</enabled><active>false</active><decomposing>true</decomposing></rdd>";
            }
            RddRuntime runtime = RUNTIMES.get(uuid);
            if (runtime == null) {
                return "<rdd><enabled>true</enabled><active>false</active></rdd>";
            }
            TaskChain chain = runtime.chain();
            Subtask current = chain.currentSubtask();
            if (current == null) {
                // 当前一级已到达但未展开(懒加载)：诚实报阶段主题，不伪造可执行节点
                return "<rdd><enabled>true</enabled><active>true</active>"
                        + "<primary_status>" + chain.primaryStatus() + "</primary_status>"
                        + "<state>stage_reached_expanding</state>"
                        + "<current_phase>" + escape(chain.currentPrimary().description()) + "</current_phase></rdd>";
            }
            return "<rdd><enabled>true</enabled><active>true</active>"
                    + "<primary_status>" + chain.primaryStatus() + "</primary_status>"
                    + "<subtask>" + escape(current.id()) + "</subtask>"
                    + "<current_task>" + escape(current.description()) + "</current_task>"
                    + "<done_when>" + escape(String.valueOf(current.condition())) + "</done_when>"
                    + "<subtask_status>" + chain.currentSubtaskStatus() + "</subtask_status></rdd>";
    }

    /** Stage-A 退化回落：今天的单遍 decompose -> bind + startCurrent（目标不被吞，行为不劣化）。 */
    private static void decomposeSinglePass(UUID uuid, String objective) {
        RddDecomposer.decompose(uuid, objective, goal -> {
            try {
                remove(uuid);
                bind(uuid, goal);
                RddRuntime runtime = runtime(uuid);
                if (runtime != null) {
                    runtime.startCurrent();
                    publishTaskSnapshot(uuid, "goal_decomposed");
                }
                LOG.info("[rdd] 目标分解完成并启动 {}:{}", uuid, goal.description());
            } catch (RuntimeException ex) {
                LOG.warn("[rdd] 分解结果启动失败，保留原生回落: {}", ex.toString());
                remove(uuid);
            } finally {
                DECOMPOSING.remove(uuid);
            }
        });
    }

    public static void bind(UUID companionId, Goal goal) {
        if (companionId == null || goal == null) throw new IllegalArgumentException("companion and goal required");
        BODY.remove(companionId);
        RUNTIMES.put(companionId, new RddRuntime(new TaskChain(goal), new AssetRegistry()));
        saveRuntimes();
        publishTaskSnapshot(companionId, "task_bound");
    }

    public static RddRuntime runtime(UUID companionId) {
        return RUNTIMES.get(companionId);
    }

    /** 记录某同伴最近一次背包计数（Detector 心跳写）。null/空安全。 */
    public static void cacheInventory(UUID companionId, Map<String, Integer> counts) {
        if (companionId != null) {
            LAST_INVENTORY.put(companionId, counts == null ? Map.of() : Map.copyOf(counts));
        }
    }

    /** 最近一次背包快照（可能为空 = 从未观测到该同伴背包）。不可变。 */
    public static Map<String, Integer> lastInventory(UUID companionId) {
        if (companionId == null) {
            return Map.of();
        }
        return LAST_INVENTORY.getOrDefault(companionId, Map.of());
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

    public static void remove(UUID companionId) {
        if (companionId != null) {
            RddRuntime previous = RUNTIMES.get(companionId);
            if (previous != null) {
                publishTaskSnapshot(companionId, "task_removed");
            }
            RUNTIMES.remove(companionId);
            BODY.remove(companionId);
            LAST_CONTEXT.remove(companionId);
            RddGoalDriver.clear(companionId); // 目标清/重绑 → 丢掉该同伴的懒展开状态
        }
    }

    /** RDD 是否允许自动提交身体工具。assist 协助模式下 false(工具执行交还 NUMEN)。 */
    public static boolean bodySubmissionEnabled() {
        return bodySubmissionEnabled;
    }

    /** 设置 RDD 自动工具提交开关。assist=true 时调用 setBodySubmissionEnabled(false) 防双驾驶。 */
    public static void setBodySubmissionEnabled(boolean on) {
        bodySubmissionEnabled = on;
    }

    /** 监督拍醒是否放行。false = 空转止血：Detector 只观察/推进，不 nudge AI。 */
    public static boolean supervisionEnabled() {
        return supervisionEnabled;
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
                    RUNTIMES.put(uuid, new RddRuntime(chain, new AssetRegistry()));
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

    /**
     * 卡死监督的"拍醒"：把一句话注入内置 AI（效果和主人亲手打字一样）。
     * RDD 不抢方向盘，只在将军发愣时提醒它——缺工具会让它自己调 selfcompile_request。
     */
    public static void nudge(UUID companionId, String message) {
        if (!supervisionEnabled) {
            // 空转止血：监督暂停时绝不注入内置 AI（兜底闸；调用方也各自判了暂停）
            LOG.info("[rdd] 监督暂停,跳过拍醒: {}", message == null ? "" : message);
            return;
        }
        try {
            if (numenApi != null && companionId != null && message != null && !message.isBlank()) {
                numenApi.enqueue(companionId, message);
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
            }
        } catch (RuntimeException e) {
            LOG.warn("[rdd] nudge failed: {}", e.toString());
        }
    }

    /** Emits an observational task-chain snapshot. This never mutates task state. */
    public static void publishTaskSnapshot(UUID companionId, String reason) {
        if (companionId == null) return;
        RddRuntime runtime = RUNTIMES.get(companionId);
        if (runtime == null) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("companionId", companionId.toString());
        data.put("reason", reason == null ? "state_observed" : reason);
        data.put("taskChain", runtime.snapshot());
        data.put("assets", runtime.assets().snapshot());
        data.put("taskId", runtime.chain().goal().id());
        data.put("subtaskId", runtime.snapshot().get("currentSubtaskId"));
        RddMonitor.publish("taskchain_snapshot", data);
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
