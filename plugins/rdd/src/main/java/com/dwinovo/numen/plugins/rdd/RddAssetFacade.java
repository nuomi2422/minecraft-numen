package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.AssetHistory;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 资产门面（职责簇 D）：一个同伴的"生存资产真相"统一入口。
 *
 * <p>把 {@link RddPlugin} 里的资产职责（世界资产注册表 / 最近背包缓存 / 资产历史）
 * 整体搬到这里：字段 + 方法逐字迁移，行为不变（红线 RL-1/RL-2/RL-6 均依赖本类口径）。
 *
 * <p>本类只承担<b>数据持有与口径</b>，不碰链状态、不投 MCP、不驱动游戏。变化点：
 * <ul>
 *   <li>{@link #assets}：世界资产注册表（载盘，uuid→AssetRegistry），由 RddAssetStore 落地。</li>
 *   <li>{@link #lastInventory}/{@link #lastInventoryAtMillis}/{@link #cacheInventory}：最近一次真实背包快照（Detector 每秒写）。</li>
 *   <li>{@link #history}/{@link #recordHistoryCurrent}/{@link #recordHistoryLost}/{@link #recordDeathLostHistory}/{@link #recoverableContext}：资产历史（LOST线索）。</li>
 *   <li>{@link #planningSnapshot}/{@link #planningAssets}/{@link #villageContext}：规划唯一资产口径（A+C 统一入口）。</li>
 *   <li>{@link #saveAssets}/{@link #saveHistory}/{@link #publishAssetSnapshot}：落盘/观测事件。</li>
 * </ul>
 *
 * <p>入口：{@link RddPlugin#setup} 传入 configDir，随后各调用点直接静态调用本类方法。
 */
public final class RddAssetFacade {
    private static final Logger LOG = LoggerFactory.getLogger(RddAssetFacade.class);

    /** Reusable world assets survive task replacement and are attached to the next task runtime. */
    private static final Map<UUID, AssetRegistry> ASSETS = new ConcurrentHashMap<>();
    /** 最近一次真实背包快照（Detector 每秒写，uuid→物品ID→数量）。规划注入用；不清除=背包是女仆属性与链无关。 */
    private static final Map<UUID, Map<String, Integer>> LAST_INVENTORY = new ConcurrentHashMap<>();
    /** 最近一次真实背包扫描时刻（系统毫秒），供规划声明的 verified_at 元字段。 */
    private static final Map<UUID, Long> LAST_INVENTORY_AT = new ConcurrentHashMap<>();
    /** Independent world-asset store; clearing a task must not erase a base or known structure. */
    private static volatile Path assetsDir;
    /** P2.1 资产历史目录 config/numen/rdd-history（每个同伴一个 <uuid>.json）；Lost≠Gone 的长期线索。 */
    private static volatile Path historyDir;
    /** 内存资产历史（uuid→history）；磁盘为真身，清世界状态只清内存。 */
    private static final Map<UUID, AssetHistory> HISTORY = new ConcurrentHashMap<>();

    private RddAssetFacade() {
    }

    /** 初始化目录（由 RddPlugin.setup 调用）。 */
    static void init(Path configDir) {
        assetsDir = configDir.resolve("rdd-assets");
        historyDir = configDir.resolve("rdd-history");
    }

    /** 清世界状态：只清内存缓存，磁盘真身保留；由 RddPlugin ServerStopped 时调用。 */
    static void clearWorldState() {
        ASSETS.clear();
        HISTORY.clear();
        LAST_INVENTORY.clear();
        LAST_INVENTORY_AT.clear();
    }

    /** 清除单个同伴的内存缓存（REMOVE 事件时调用）。 */
    static void remove(UUID companionId) {
        if (companionId == null) return;
        LAST_INVENTORY.remove(companionId);
        LAST_INVENTORY_AT.remove(companionId);
        ASSETS.remove(companionId);
        HISTORY.remove(companionId);
    }

    public static AssetRegistry assets(UUID companionId) {
        if (companionId == null) return new AssetRegistry();
        return ASSETS.computeIfAbsent(companionId, id -> RddAssetStore.load(assetsDir, id));
    }

    /** P2.1 资产历史仓库（磁盘为真身，内存缓存）；Lost≠Gone 的长期恢复线索。 */
    public static AssetHistory history(UUID companionId) {
        if (companionId == null) return new AssetHistory();
        return HISTORY.computeIfAbsent(companionId, id -> RddHistoryStore.load(historyDir, id));
    }

    /** 落盘资产历史（失败只记日志，不影响主流程）。 */
    static void saveHistory(UUID companionId) {
        if (companionId == null || historyDir == null) return;
        try {
            RddHistoryStore.save(historyDir, companionId, history(companionId));
        } catch (IOException ex) {
            LOG.warn("[rdd] 保存资产历史失败 {}: {}", companionId, ex.toString());
        }
    }

    /**
     * P2.1：把当前背包里"值得记住"的条目记为 CURRENT（观测到=还持有）。
     * 只记有恢复价值的（装备/工具/食物），过滤泥土等杂物。
     */
    static void recordHistoryCurrent(UUID companionId, Map<String, Integer> inventory) {
        if (companionId == null || inventory == null || inventory.isEmpty()) return;
        try {
            AssetHistory h = history(companionId);
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Integer> e : inventory.entrySet()) {
                if (!AssetHistory.worthRemembering(e.getKey(), AssetHistory.Purpose.UNKNOWN)) continue;
                if (e.getValue() == null || e.getValue() <= 0) continue;
                h.recordCurrent(e.getKey(), AssetHistory.Purpose.UNKNOWN, e.getValue(),
                        null, 0, 0, 0, now);
            }
            saveHistory(companionId);
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 记录资产历史(CURRENT)失败 {}: {}", companionId, ex.toString());
        }
    }

    /**
     * P2.1：死亡/掉落时把一件有意义资产记为 LOST，保留最后已知位置（供恢复线索）。
     */
    static void recordHistoryLost(UUID companionId, String assetId, Integer lastCount,
                                  String dimension, BlockPos pos) {
        if (companionId == null || assetId == null || !AssetHistory.worthRemembering(assetId, AssetHistory.Purpose.UNKNOWN)) {
            return;
        }
        try {
            history(companionId).recordLost(assetId, AssetHistory.Purpose.UNKNOWN, lastCount,
                    dimension, pos == null ? 0 : pos.getX(), pos == null ? 0 : pos.getY(),
                    pos == null ? 0 : pos.getZ(), System.currentTimeMillis());
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 记录资产历史(LOST)失败 {}: {}", companionId, ex.toString());
        }
    }

    /**
     * P2.1：死亡时把身上值得记住的资产逐条记为 LOST（带最后位置），并落盘一次。
     * 位置取死亡瞬间的同伴坐标（最近一次已知地点）。
     */
    static void recordDeathLostHistory(UUID companionId, NumenPlayer body) {
        // 用"死亡瞬间的真实背包"（body 还在）；缓存可能为空（V3 实测 LOST 未新增的根因）。
        Map<String, Integer> inv = RddDetector.countInventory(body);
        if (inv.isEmpty()) {
            inv = lastInventory(companionId);
        }
        if (inv.isEmpty()) return;
        String dimension = null;
        BlockPos pos = null;
        try {
            if (body.serverLevel() != null) dimension = body.serverLevel().dimension().location().toString();
            pos = body.blockPosition();
        } catch (RuntimeException ignore) {
            // 位置不可得则记 null 位置（仍保留"曾拥有"事实）
        }
        for (Map.Entry<String, Integer> e : inv.entrySet()) {
            recordHistoryLost(companionId, e.getKey(), e.getValue(), dimension, pos);
        }
        saveHistory(companionId);
    }

    /** P2.1：渲染"可恢复线索"块供规划提示词（无则空串）。 */
    static String recoverableContext(UUID companionId) {
        try {
            var rec = history(companionId).recoverable();
            if (rec.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("【可恢复线索（曾拥有、暂不可用；优先判断能否回去取，而不是从零重造）】\n");
            for (var e : rec) sb.append("- ").append(e.render()).append('\n');
            return sb.toString();
        } catch (RuntimeException ex) {
            return "";
        }
    }

    static String planningAssets(UUID companionId) {
        return RddAssetContext.render(assets(companionId), 2000);
    }

    /** P2-D：已观测村庄的事实块（先事实，不含策略）；补给规划提示词用。 */
    static String villageContext(UUID companionId) {
        return com.dwinovo.numen.rdd.core.VillageNode.render(
                com.dwinovo.numen.rdd.core.VillageNode.extract(assets(companionId)));
    }

    /**
     * P2-A【A+C 统一入口】唯一规划资产口径：先<b>同步刷新实时背包</b>再生成快照。
     *
     * <p>修的是首次 /goal 时序：`/goal → beginPlanning → planStages` 早于链建立，Detector 的
     * tickRuntime（要求 rt!=null）还没跑过 → lastInventory 为空 → 规划/判定都读到空背包。
     * 这里若拿得到当前 server，就在规划前用同伴实体直接 countInventory 刷新一次；
     * 拿不到（非服务端线程/无 server）则回落到缓存口径（不劣化）。
     *
     * <p>注意：本方法可能被客户端决策线程调用，故刷新全部包在 try-catch 内，任何异常都不影响主流程。
     */
    public static com.dwinovo.numen.rdd.core.PlanningAssetSnapshot planningSnapshot(UUID companionId) {
        refreshInventoryFromLive(companionId);
        return com.dwinovo.numen.rdd.core.PlanningAssetSnapshot.from(
                lastInventory(companionId), lastInventoryAtMillis(companionId), assets(companionId));
    }

    /** 【A】规划/判定前同步刷新实时背包（拿得到 server 就刷；失败静默回落缓存）。 */
    private static void refreshInventoryFromLive(UUID companionId) {
        if (companionId == null) return;
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return;
            NumenPlayer ap = NumenPlayer.findByUuid(server, companionId);
            if (ap != null) {
                cacheInventory(companionId, RddDetector.countInventory(ap));
            }
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 规划前实时刷新背包失败 {}: {}", companionId, ex.toString());
        }
    }

    /**
     * 【A+C 统一入口·显式 server 版】供已知 server 的调用点（如 beginPlanning）使用：
     * 先按给定 server 同步刷新实时背包，再生成快照。等价于无参重载（其内部自动取当前 server）。
     */
    public static com.dwinovo.numen.rdd.core.PlanningAssetSnapshot planningSnapshot(UUID companionId,
                                                                                   MinecraftServer server) {
        if (companionId != null && server != null) {
            try {
                NumenPlayer ap = NumenPlayer.findByUuid(server, companionId);
                if (ap != null) {
                    cacheInventory(companionId, RddDetector.countInventory(ap));
                }
            } catch (RuntimeException ex) {
                LOG.warn("[rdd] 规划前实时刷新背包失败 {}: {}", companionId, ex.toString());
            }
        }
        return com.dwinovo.numen.rdd.core.PlanningAssetSnapshot.from(
                lastInventory(companionId), lastInventoryAtMillis(companionId), assets(companionId));
    }

    /** 记录某同伴最近一次背包计数（Detector 心跳写）。null/空安全。 */
    public static void cacheInventory(UUID companionId, Map<String, Integer> counts) {
        if (companionId != null) {
            LAST_INVENTORY.put(companionId, counts == null ? Map.of() : Map.copyOf(counts));
            LAST_INVENTORY_AT.put(companionId, System.currentTimeMillis());
        }
    }

    /** 最近一次背包快照（可能为空 = 从未观测到该同伴背包）。不可变。 */
    public static Map<String, Integer> lastInventory(UUID companionId) {
        if (companionId == null) {
            return Map.of();
        }
        return LAST_INVENTORY.getOrDefault(companionId, Map.of());
    }

    /** 最近一次背包扫描时刻（系统毫秒）；从未扫描过 → null。规划声明 verified_at 用。 */
    public static Long lastInventoryAtMillis(UUID companionId) {
        return companionId == null ? null : LAST_INVENTORY_AT.get(companionId);
    }

    static void saveAssets(UUID companionId) {
        if (companionId == null) return;
        try {
            RddAssetStore.save(assetsDir, companionId, assets(companionId));
        } catch (IOException ex) {
            LOG.warn("[rdd] 保存世界资产失败 {}: {}", companionId, ex.toString());
        }
    }

    static void publishAssetSnapshot(UUID companionId, String reason, RddWorldAssetObserver.Result observed) {
        if (companionId == null) return;
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("companionId", companionId.toString());
        data.put("reason", reason == null ? "lazy_world_observation" : reason);
        data.put("assets", RddAssetContext.worldAssets(assets(companionId)));
        data.put("observed", Map.of("bases", observed.bases(), "structures", observed.structures(),
                "machines", observed.machines(), "entityGroups", observed.entityGroups(), "total", observed.total()));
        data.put("dataFlow", Map.of(
                "source", "loaded_world_and_respawn_base",
                "registry", "rdd-assets/<companion>.json",
                "consumers", java.util.List.of("numen_context", "supervisor_context", "monitoring_station"),
                "refresh", "lazy_30s_no_chunk_force_load"));
        RddMonitor.publish("asset_snapshot", data);
    }
}