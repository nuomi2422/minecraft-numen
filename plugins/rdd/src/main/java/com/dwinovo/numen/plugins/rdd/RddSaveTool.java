package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 2026-10-31（V-20261031-08）：停机前落盘钩子。
 *
 * <p><b>为什么需要它</b>：V-20261031-07 实测发现同伴背包只在自动保存时落盘，
 * `exp-runner` 的收尾是 `task_stop → 宽限 60s → Stop-Process -Force`，没有优雅 `stop`，
 * 因此最后几分钟的物品会丢（B2 结束 2 原木，B3 起始 0）。世界副本的 playerdata mtime
 * 停在 autosave（04:44:51 &lt; 结束 04:48:31）就是证据。
 *
 * <p><b>它做什么</b>：只调 `MinecraftServer.saveEverything(..., flush=true)` +
 * `PlayerList.saveAll()`，把区块与玩家数据刷到磁盘；不改世界、不改背包、不执行任何指令。
 * harness 在 task_stop 之后、force-kill 之前调用它，并记录回执。
 *
 * <p><b>不是作弊通道</b>：不产生 `admin_command_used`，不改变任务结果；它只是把「已经发生的
 * 世界状态」落盘。验证卡里按 setup/收尾动作标注，不计入自然行为。
 */
final class RddSaveTool implements NumenTool {

    @Override public String name() { return "rdd_save"; }

    @Override public String description() {
        return "First-party flush: save all chunks and player data to disk now and reply when the save returns. "
                + "It does not change the world or the inventory; the harness calls it after task_stop and before "
                + "a forced process stop so the last minutes of progress are not lost.";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalString("reason", "why the flush is requested (audit only; not used to change behavior)")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            MinecraftServer server = companion.getServer();
            if (server == null) {
                reply.accept(TaskResult.fail("rdd_save: companion has no server").toJson());
                return;
            }
            long t0 = System.currentTimeMillis();
            // suppressLog=true, flush=true, forced=true：强制把区块/实体刷盘。
            boolean saved = server.saveEverything(true, true, true);
            // 玩家数据单独再刷一次：同伴是 list-resident 的 ServerPlayer，saveAll 覆盖它。
            server.getPlayerList().saveAll();
            long ms = System.currentTimeMillis() - t0;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ok", saved);
            data.put("ms", ms);
            data.put("players", server.getPlayerList().getPlayerCount());
            reply.accept(TaskResult.ok("save-all flushed", data).toJson());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("rdd_save failed: " + ex.getMessage()).toJson());
        }
    }
}
