package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.api.NumenPlugins;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * learner 的 NeoForge 入口：注册插件 + 挂自动入队的服务端 tick。
 *
 * <p>E1（81 号 §14.4）：「该不该学」的判定、队列、质量门都在 —— 但唯一入口是 AI
 * 自觉调 learner_intake，而实测它从不调（当天 6 条 T2 命中 0 入队）。
 * 自动入队把这个缺口接上：每 30 秒扫一次事件流，命中即进队列，
 * 与手动口共用同一判定/同一抑制/同一队列，没有任何放宽。
 * {@link LearnerAutoIntake#onServerTick} 自己节流、自己吞异常（增强旁路，不拖垮 tick）。
 */
@Mod("learner")
public final class LearnerMod {

    public LearnerMod() {
        NumenPlugins.register(new LearnerPlugin());
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
    }

    private void onServerTick(ServerTickEvent.Pre event) {
        if (event.getServer() != null) {
            LearnerAutoIntake.onServerTick(event.getServer().getTickCount());
        }
    }
}
