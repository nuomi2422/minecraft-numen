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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** NUMEN host adapter for the host-independent RDD task-chain core. */
public final class RddPlugin implements NumenPlugin {
    private static final Logger LOG = LoggerFactory.getLogger(RddPlugin.class);
    private static final Map<UUID, RddRuntime> RUNTIMES = new ConcurrentHashMap<>();

    @Override
    public void setup(NumenApi numen) {
        numen.registerTool(new RddStatusTool());
        numen.registerTool(new RddSubmitTool());
        // 接管 /goal：目标到达 → 建 RDD 任务链。返回 true = 已接管，引擎让位。
        com.dwinovo.numen.agent.goal.GoalSinks.register((uuid, objective) -> {
            if (uuid == null || objective == null || objective.isBlank()) {
                return false;
            }
            bind(uuid, RddChainFactory.fromObjective(uuid, objective));
            RddRuntime runtime = runtime(uuid);
            if (runtime != null) {
                runtime.startCurrent();
            }
            LOG.info("[rdd] 接管目标 {}:{}", uuid, objective);
            return true;
        });
        com.dwinovo.numen.agent.goal.GoalSinks.registerClear((uuid, reason) -> {
            if (uuid != null) {
                remove(uuid);
                LOG.info("[rdd] 目标清掉,移除任务链 {}", uuid);
                return true;
            }
            return false;
        });
        numen.contributeState(uuid -> {
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
        RUNTIMES.put(companionId, new RddRuntime(new TaskChain(goal), new AssetRegistry()));
    }

    public static RddRuntime runtime(UUID companionId) {
        return RUNTIMES.get(companionId);
    }

    public static void remove(UUID companionId) {
        if (companionId != null) RUNTIMES.remove(companionId);
    }

    /** XML 转义：描述/条件可能含玩家可输入的 < > & "。 */
    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
