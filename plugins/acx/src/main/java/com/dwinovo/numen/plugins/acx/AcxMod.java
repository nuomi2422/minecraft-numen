package com.dwinovo.numen.plugins.acx;

import com.dwinovo.numen.api.NumenPlugins;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** NeoForge 入口：把 ACX 插件挂到 NUMEN 宿主。 */
@Mod("acx")
public final class AcxMod {

    public AcxMod() {
        AcxPlugin plugin = new AcxPlugin();
        NumenPlugins.register(plugin);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> plugin.onServerStopped());
    }
}
