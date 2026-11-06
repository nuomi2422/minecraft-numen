package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugin;
import com.dwinovo.numen.api.observation.WorldObservationService;
import com.dwinovo.numen.plugins.worldstate.probe.ProbeService;
import com.dwinovo.numen.plugins.worldstate.tool.WorldProbeTool;
import com.dwinovo.numen.plugins.worldstate.tool.WorldStateTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 世界观测插件：装配 {@link WorldStateService}，注册 {@code world_state} 工具入口。
 *
 * <p>V1 只把「只读查询、显式采集、带时间与覆盖信息的基础存储」这条链跑通，
 * 其余主题（blocks/containers/facilities）按同一契约逐个接入，旧扫描器保持现状。
 */
public final class WorldStatePlugin implements NumenPlugin {

    private static final Logger LOG = LoggerFactory.getLogger("worldstate");

    @Override
    public void setup(NumenApi numen) {
        WorldStateService service = new WorldStateService(numen.configDir());
        WorldStateMod.bindService(service);
        // 2026-11-06：常驻环境块靠周期刷新保持 CURRENT（详见 EnvironmentRefresher 的取值理由）。
        WorldStateMod.bindRefresher(new EnvironmentRefresher(service));
        // 039/B1: tool and plugins share this one adapter, not parallel policy instances.
        ObservationCapability observations = new ObservationCapability(service, () -> {
                    try {
                        return net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
                    } catch (RuntimeException | LinkageError e) {
                        return null;
                    }
                });
        numen.registerTool(new WorldStateTool(service, observations));
        numen.registerTool(new WorldProbeTool(new ProbeService()));
        // 2026-11-06：身体每 10 tick 采好一帧就映射进原 store（只映射，不扫库存——帧已由
        // CompanionBodySample 在同一个 tick 备好）。这条链由 CompanionTickDispatcher 直接驱动，
        // 不依赖 RDD 主链，也不新增总线。
        numen.on(CompanionEvent.SELF_SAMPLE_READY, body -> SelfSampleBridge.ready(service, body));
        // 身体没了（死亡/移除）：这个主体任何维度的 SELF 旧帧都不再代表当前，全部失效。
        numen.on(CompanionEvent.DEATH, body -> SelfSampleBridge.gone(service, body));
        numen.on(CompanionEvent.REMOVE, body -> SelfSampleBridge.gone(service, body));
        WorldObservationService previous = numen.capability(WorldObservationService.class, observations);
        if (previous != null) {
            LOG.warn("[worldstate] replaced an existing WorldObservationService: {}", previous.getClass().getName());
        }
        LOG.info("[worldstate] registered; store={} topics={} + world_probe catalog + observation capability",
                service.file(), service.topics());
    }
}
