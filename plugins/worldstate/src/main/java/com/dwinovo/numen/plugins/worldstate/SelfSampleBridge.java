package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.worldstate.core.refresh.RefreshResult;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CompanionEvent → {@link WorldStateService} 的唯一映射层（2026-11-06，SELF 自动采样）。
 *
 * <p><b>为什么单独一个类</b>：这两条映射（身体 → 维度/键 → refresh，或 → invalidateSelf）
 * 是这条链上唯一需要被离线测试盯住的一段。抽出来之后，测试编译的就是生产代码本身，
 * 而不是在测试里复制一份同样的 lambda。
 *
 * <p><b>为什么不在这里扫库存</b>：帧已经由 {@code CompanionStateWatch} 在同一个 tick 采好，
 * {@code CompanionBodySample} 按（身体, 服务器, 维度, tick）复用——refresh 里的
 * {@code SelfWorldProvider} 只会拿现成帧去装箱，不重复读 41 格。
 *
 * <p><b>为什么不在这里判 owner</b>：观测属于世界，不属于某个客户端。主人离线时采样照走，
 * 只是不给他发包（那条在 {@code CompanionStateWatch} 的推送段）。
 */
final class SelfSampleBridge {

    private static final Logger LOG = LoggerFactory.getLogger("worldstate");

    private SelfSampleBridge() {}

    /** {@code SELF_SAMPLE_READY}：映射成一次 SELF refresh；epoch/tick 沿用采样帧，不重读身体。 */
    static void ready(WorldStateService service, NumenPlayer companion) {
        if (service == null || companion == null) return;
        MinecraftServer server = companion.getServer();
        if (server == null) return;   // 已经不在世界里：没有服务端线程可写的帧，跳过这一拍
        String dimension = companion.serverLevel().dimension().location().toString();
        RefreshResult result = service.refresh(server, ObservationRequest.self(dimension, companion.getUUID()));
        if (!result.success()) {
            // 不刷屏（每 10 tick 一次），但必须留痕：静默失败会让"采样在跑"变成假象。
            LOG.debug("[worldstate] SELF refresh skipped: {}", result.message());
        }
    }

    /** {@code DEATH}/{@code REMOVE}：这个主体任何维度的旧帧都不再代表当前。 */
    static void gone(WorldStateService service, NumenPlayer companion) {
        if (service == null || companion == null) return;
        MinecraftServer server = companion.getServer();
        if (server == null) return;   // 关服那一路由 ServerStoppedEvent → invalidateAllSelf 兜底
        service.invalidateSelf(server, companion.getUUID());
    }
}
