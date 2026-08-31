package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugin;
import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.core.TaskChain;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
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

    record BodyState(String subtaskId, int submitCount) {}

    @Override
    public void setup(NumenApi numen) {
        numen.registerTool(new RddStatusTool());
        numen.registerTool(new RddSubmitTool());
        // 接管 /goal：先同步认领，异步分解；分解期间 NUMEN 原生目标循环让位。
        com.dwinovo.numen.agent.goal.GoalSinks.register((uuid, objective) -> {
            if (uuid == null || objective == null || objective.isBlank()) {
                return false;
            }
            DECOMPOSING.add(uuid);
            BODY.remove(uuid);
            RddDecomposer.decompose(uuid, objective, goal -> {
                try {
                    remove(uuid);
                    bind(uuid, goal);
                    RddRuntime runtime = runtime(uuid);
                    if (runtime != null) {
                        runtime.startCurrent();
                    }
                    LOG.info("[rdd] 目标分解完成并启动 {}:{}", uuid, goal.description());
                } catch (RuntimeException ex) {
                    LOG.warn("[rdd] 分解结果启动失败，保留原生回落: {}", ex.toString());
                    remove(uuid);
                } finally {
                    DECOMPOSING.remove(uuid);
                }
            });
            LOG.info("[rdd] 接管目标，正在异步分解 {}:{}", uuid, objective);
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
            if (DECOMPOSING.contains(uuid)) {
                return "<rdd><enabled>true</enabled><active>false</active><decomposing>true</decomposing></rdd>";
            }
            RddRuntime runtime = RUNTIMES.get(uuid);
            if (runtime == null) {
                return "<rdd><enabled>true</enabled><active>false</active></rdd>";
            }
            TaskChain chain = runtime.chain();
            Subtask current = chain.currentSubtask();
            return "<rdd><enabled>true</enabled><active>true</active>"
                    + "<primary_status>" + chain.primaryStatus() + "</primary_status>"
                    + "<subtask>" + escape(current.id()) + "</subtask>"
                    + "<current_task>" + escape(current.description()) + "</current_task>"
                    + "<done_when>" + escape(String.valueOf(current.condition())) + "</done_when>"
                    + "<subtask_status>" + chain.currentSubtaskStatus() + "</subtask_status></rdd>";
        });
    }

    public static void bind(UUID companionId, Goal goal) {
        if (companionId == null || goal == null) throw new IllegalArgumentException("companion and goal required");
        BODY.remove(companionId);
        RUNTIMES.put(companionId, new RddRuntime(new TaskChain(goal), new AssetRegistry()));
    }

    public static RddRuntime runtime(UUID companionId) {
        return RUNTIMES.get(companionId);
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
            RUNTIMES.remove(companionId);
            BODY.remove(companionId);
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

    /** XML 转义：描述/条件可能含玩家可输入的 < > & ". */
    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
