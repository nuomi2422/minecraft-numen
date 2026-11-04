package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.observation.WorldObservationService;
import com.dwinovo.numen.entity.NumenPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 让统一观测的 {@code environment} 主题保持新鲜（039 U1 收尾）。
 *
 * <p>为什么由 RDD 来触发：读口是<b>只读缓存</b>，没人刷新就永远 PENDING；而 039 §6 又明令
 * 禁止在上下文组装线程同步扫世界。折中办法是**在 RDD 自己的服务端 tick 里**按节奏显式
 * refresh——显式、有上限、不藏在读路径里。
 *
 * <p>节奏 5 秒：environment 只读 dayTime/天气，采集极廉价；比 TTL 短得多，读侧不会看到过期值。
 */
final class RddObservation {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(RddObservation.class);

    /** 两次刷新之间的最小 tick 数（20 tick = 1 秒）。 */
    private static final long CADENCE_TICKS = 100L;

    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();

    /** 身边关心的方块：与 RddEnvBlock 的"身边"行同一份清单。 */
    static final String NEARBY_IDS = "minecraft:chest,minecraft:barrel,minecraft:trapped_chest,"
            + "minecraft:lava,minecraft:nether_portal,minecraft:obsidian,minecraft:crying_obsidian";

    private RddObservation() {}

    /** 服务端 tick 调用；能力缺席或宿主出错都不影响主链路。 */
    static void refreshIfDue(NumenPlayer ap, long gameTime) {
        if (ap == null) return;
        try {
            WorldObservationService svc = NumenPlugins.capability(WorldObservationService.class);
            if (svc == null) return;
            Long last = LAST.get(ap.getUUID());
            if (last != null && gameTime - last < CADENCE_TICKS) return;
            LAST.put(ap.getUUID(), gameTime);
            String dim = ap.level().dimension().location().toString();
            WorldObservationService.Response resp = svc.refresh(
                    new WorldObservationService.Request("rdd-env", dim, "environment", Map.of(), 0L, 1, 0L));
            // blocks 主题：身边有没有箱子/岩浆/门（039 U2b 的消费者）。
            // 盒子与 RddEnvBlock 旧扫描同口径（±8 水平、±12 垂直）。
            var pos = ap.blockPosition();
            String box = (pos.getX() - 8) + "," + (pos.getY() - 12) + "," + (pos.getZ() - 8) + ","
                    + (pos.getX() + 8) + "," + (pos.getY() + 12) + "," + (pos.getZ() + 8);
            svc.refresh(new WorldObservationService.Request("rdd-env", dim, "blocks",
                    Map.of("box", box, "blocks", NEARBY_IDS), 0L, 32, 0L));
            // entities 主题（039 U5#7）：危险实体 —— 两个盒子：扫描半径 / 贴身半径。
            svc.refresh(new WorldObservationService.Request("rdd-env", dim, "entities",
                    Map.of("box", dangerBox(pos, RddDangerText.SCAN_RADIUS), "category", "hostile"), 0L, 64, 0L));
            svc.refresh(new WorldObservationService.Request("rdd-env", dim, "entities",
                    Map.of("box", dangerBox(pos, RddDangerText.CLOSE_RADIUS), "category", "hostile"), 0L, 64, 0L));
            if (resp != null && resp.status() == WorldObservationService.Status.ERROR) {
                // 限频：只在状态变化时打一条，避免每 5 秒刷屏
                Long prevErr = ERR_AT.get(ap.getUUID());
                if (prevErr == null || gameTime - prevErr > 200L) {
                    ERR_AT.put(ap.getUUID(), gameTime);
                    LOG.warn("[rdd-obs] environment refresh failed: {}", resp.reason());
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 观测不该影响判定；能力缺席是正常情况
        }
    }

    /** 以方块位置为中心、半径 r 的整数盒（与实体扫描同口径）。 */
    static String dangerBox(net.minecraft.core.BlockPos pos, int r) {
        return (pos.getX() - r) + "," + (pos.getY() - r) + "," + (pos.getZ() - r) + ","
                + (pos.getX() + r) + "," + (pos.getY() + r) + "," + (pos.getZ() + r);
    }

    private static final Map<UUID, Long> ERR_AT = new ConcurrentHashMap<>();

    static void clear(UUID companionId) {
        if (companionId == null) return;
        LAST.remove(companionId);
        ERR_AT.remove(companionId);
    }
}
