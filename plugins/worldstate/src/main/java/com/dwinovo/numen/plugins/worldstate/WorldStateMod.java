package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.api.NumenPlugins;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * 世界观测模块的模组入口。构造期把 {@link WorldStatePlugin} 挂进 Numen 引擎。
 *
 * <p>{@link #service()} 是给别的模块（settlement / rdd / 后续牧场交易）的<b>只读</b>入口，
 * 沿用 settlement 的先例，避免为了查一次动物数量而依赖整个实现。
 */
@Mod("worldstate")
public final class WorldStateMod {

    private static volatile WorldStateService SERVICE;
    private static volatile EnvironmentRefresher REFRESHER;

    public WorldStateMod() {
        NumenPlugins.register(new WorldStatePlugin());
        // 2026-11-06：SELF 只活在内存里，停服时把本进程留下的 SELF 缓存一并作废
        // （服务恢复后它们已经不可信），其它主题一条不动——它们要留着跟磁盘历史对账。
        // 注册方式沿用 SettlementMod / SideTaskHost 的现有写法：构造期挂 NeoForge.EVENT_BUS，
        // 不新造 hook；Fabric 侧本仓没有 worldstate 入口，故不声明双加载器。
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            WorldStateService service = SERVICE;
            if (service != null) service.invalidateAllSelf();
        });
        // 常驻环境块的周期刷新：每 tick 只做一次时间比较，到点才去刷（不每 tick 刷）。
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) -> {
            EnvironmentRefresher refresher = REFRESHER;
            if (refresher != null) refresher.tick(event.getServer(), System.currentTimeMillis());
        });
    }

    static void bindService(WorldStateService service) {
        SERVICE = service;
    }

    static void bindRefresher(EnvironmentRefresher refresher) {
        REFRESHER = refresher;
    }

    public static WorldStateService service() {
        return SERVICE;
    }
}
