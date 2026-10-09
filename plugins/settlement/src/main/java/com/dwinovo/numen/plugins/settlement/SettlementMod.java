package com.dwinovo.numen.plugins.settlement;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.settlement.core.model.FacilityRecord;
import com.dwinovo.numen.settlement.core.protect.GrantLedger;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * NeoForge 入口：注册「基地与设施」插件 + 挂施工授权的服务端清扫。
 *
 * <p>为什么要 tick 清扫（用户点名的第 ④ 条「所有终止路径回收授权」）：
 * 施工授权是<b>进程内、绑定任务</b>的。任务可能以三种方式终止——
 * 正常结束、被别的动作顶替、进程重启。只把授权塞进一个列表的话，后两种没人负责撤销，
 * 授权就一直挂着 = 保护区永久敞开。所以每 tick 对着"该同伴当前还在跑的任务 id"扫一遍，
 * 对不上就撤。
 *
 * <p>清扫从<b>账本里开着的授权</b>出发（不是枚举全服同伴）：只有真开了授权的设施才需要
 * 查它的主人，代价与授权条数成正比，没授权时一步就返回。
 *
 * <p>清扫失败绝不打断服务端 tick，但必须留痕——静默失效是踩过的坑。
 */
@Mod("settlement")
public final class SettlementMod {

    private static final Logger LOG = LoggerFactory.getLogger("settlement");

    /** 服务端插件的 service 引用，供 tick 清扫用（插件 setup 时回填）。 */
    private static volatile SettlementService SERVICE;

    /** 过门自动关门（实测：寻路器开门过路后没人关，敞着的门＝圈没围）。 */
    private final GateWatcher gateWatcher = new GateWatcher();

    public SettlementMod() {
        NumenPlugins.register(new SettlementPlugin());
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
    }

    /** 由插件在 setup 时回填，避免 Mod 构造期去碰 NumenApi。 */
    static void bindService(SettlementService service) {
        SERVICE = service;
    }

    private void onServerTick(ServerTickEvent.Pre event) {
        MinecraftServer server = event.getServer();
        SettlementService service = SERVICE;
        if (server == null || service == null) return;

        // ① 过门自动关门：只在有已登记设施时扫描（没设施就一步返回）。
        if (!service.list().isEmpty()) {
            try {
                gateWatcher.tick(server, service, server.getTickCount());
            } catch (Throwable t) {
                LOG.warn("[settlement] gate watcher failed: {}", t.toString());
            }
        }

        // ② 施工授权回收（所有终止路径）。
        GrantLedger ledger = service.grantLedger();
        if (ledger.size() == 0) return;   // 快路径：没开授权就不做任何枚举

        try {
            for (GrantLedger.OpenGrant open : ledger.openGrants()) {
                String facilityId = open.facilityId();
                FacilityRecord facility = service.byId(facilityId).orElse(null);
                if (facility == null) {
                    // 设施被注销了：授权必须跟着走，否则它永远挂在账上。
                    ledger.release(facilityId);
                    continue;
                }
                NumenPlayer body = bodyOf(server, facility.ownerId());
                if (body == null) {
                    // 身体不在这个世界（下线/换维度/被遣散）：撤权，等它回来再重开。
                    service.sweepGrants(null);
                    continue;
                }
                TaskRecord current = CompanionTickDispatcher.currentTaskFor(body.getUUID());
                List<String> released = service.sweepGrants(
                        current == null ? null : current.publicId());
                if (!released.isEmpty()) {
                    LOG.info("[settlement] reclaimed construction grants for {} (task now {})",
                            released, current == null ? "none" : current.publicId());
                }
            }
        } catch (Throwable t) {
            LOG.warn("[settlement] grant sweep failed: {}", t.toString());
        }
    }

    private static NumenPlayer bodyOf(MinecraftServer server, String ownerId) {
        if (ownerId == null) return null;
        try {
            return NumenPlayer.findByUuid(server, UUID.fromString(ownerId));
        } catch (IllegalArgumentException bad) {
            return null;
        }
    }
}
