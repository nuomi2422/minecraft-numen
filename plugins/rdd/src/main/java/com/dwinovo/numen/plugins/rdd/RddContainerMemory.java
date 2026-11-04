package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「附近容器里有什么」——把**放进箱子里的东西**重新带回 AI 的上下文。
 *
 * <h2>为什么要有它（2026-10-25 主人当场指出）</h2>
 * AI 把铁锭/备用装备放进箱子后**就忘了**：规划口径只认手上持有、世界资产表不记箱内物品、
 * settlement 的容器快照没人喂回上下文。于是"存起来"对 AI 和规划器**等于东西消失了**。
 *
 * <h2>为什么是「tick 里扫、上下文里只读缓存」（这条是踩坑换来的）</h2>
 * 第一版直接在上下文构建（{@code contributeState}）里读世界：{@code getBlockEntity} 恒 null、
 * {@code Services.CAPS.describe} 直接 NPE —— 而同坐标下 MCP 的 {@code inspect_block_storage}
 * 能正常读出箱内物品。差别不在代码，而在**线程**：本插件里早就写着
 * 「contributeState 不保证在主线程，不能在那里读世界」。
 * 所以改成：**服务端 tick 扫（主线程）→ 写缓存 → 上下文只读缓存**，
 * 与背包缓存（{@code cacheInventory}）同一个套路。
 *
 * <p>读容器本身照抄工具那条已被证明能读的路（{@code QueryExtraOps.inspectBlockStorage}）。
 */
final class RddContainerMemory {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(RddContainerMemory.class);

    /** 扫描半径（格）。 */
    private static final int RADIUS = 8;
    /** 一次最多看几处容器。 */
    private static final int MAX_CONTAINERS = 3;
    /** 一处容器最多列几种物品。 */
    private static final int MAX_TYPES = 10;
    /** 两次扫描的最小间隔（tick）——约 2 秒。 */
    private static final long REFRESH_TICKS = 40L;
    private static final Map<UUID, String> CACHE = new ConcurrentHashMap<>();
    /** 同一份扫描的结构化计数（037/T14）：上下文要文字，规划要数字，两边不许走不同的解析路。 */
    private static final Map<UUID, Map<String, Integer>> COUNTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_REFRESH = new ConcurrentHashMap<>();

    private RddContainerMemory() {}

    /** 服务端 tick（主线程）调用：扫描并写缓存。 */
    static void refresh(NumenPlayer body, long gameTime) {
        if (body == null) return;
        UUID id = body.getUUID();
        Long last = LAST_REFRESH.get(id);
        if (last != null && gameTime - last < REFRESH_TICKS) return;
        LAST_REFRESH.put(id, gameTime);
        try {
            Scan scan = scan(body, gameTime);
            CACHE.put(id, scan.text());
            COUNTS.put(id, scan.counts());
        } catch (RuntimeException | LinkageError ex) {
            // 读世界失败不影响主链路；保留上一次缓存（宁可旧一点，也不要突然说"没有"）
            LOG.warn("[rdd-storage] 扫描失败 {}: {}", id, ex.toString());
        }
    }

    /** 上下文构建（可能在非主线程）调用：只读缓存。 */
    static String cached(UUID companionId) {
        if (companionId == null) return "";
        String s = CACHE.get(companionId);
        return s == null ? "" : s;
    }

    /**
     * 上下文/规划共用的结构化计数：物品 id（带 {@code minecraft:} 前缀）→ 数量。
     * 没有缓存时返回空表（调用方按"没看见"处理，不要当"确认没有"）。
     */
    static Map<String, Integer> cachedCounts(UUID companionId) {
        if (companionId == null) return Map.of();
        Map<String, Integer> counts = COUNTS.get(companionId);
        return counts == null ? Map.of() : counts;
    }

    static void clear(UUID companionId) {
        if (companionId == null) return;
        CACHE.remove(companionId);
        COUNTS.remove(companionId);
        LAST_REFRESH.remove(companionId);
    }

    /** 一次扫描的双产物：给人看的文字 + 给规划用的计数。 */
    private record Scan(String text, Map<String, Integer> counts) {
    }

    /** 真正扫世界（**只在主线程调**）。 */
    private static Scan scan(NumenPlayer body, long gameTime) {
        ServerLevel level = body.serverLevel();
        List<String> lines = new ArrayList<>();
        Map<String, Integer> totals = new LinkedHashMap<>();
        BlockPos origin = body.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int hits = 0;
        for (int dx = -RADIUS; dx <= RADIUS && lines.size() < MAX_CONTAINERS; dx++) {
            for (int dy = -RADIUS; dy <= RADIUS && lines.size() < MAX_CONTAINERS; dy++) {
                for (int dz = -RADIUS; dz <= RADIUS && lines.size() < MAX_CONTAINERS; dz++) {
                    if (dx * dx + dy * dy + dz * dz > RADIUS * RADIUS) continue;
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!level.hasChunkAt(cursor)) continue;
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) continue;
                    if (!state.getBlock().getDescriptionId().contains("chest")) continue;
                    hits++;
                    // 039 U3：内容优先走统一观测的 containers 主题；观测缺席/过期就显式 refresh，
                    // 两条路都不通才回退直读。发现（往哪看）仍然靠这里的本地扫描——观测不负责"找"。
                    Map<String, Integer> counts = readContainer(body, level, cursor.immutable(), gameTime);
                    if (counts == null || counts.isEmpty()) continue;
                    counts.forEach((item, n) -> totals.merge(item, n, Integer::sum));
                    StringBuilder sb = new StringBuilder();
                    int listed = 0;
                    for (Map.Entry<String, Integer> e : counts.entrySet()) {
                        if (listed >= MAX_TYPES) { sb.append("…"); break; }
                        if (listed > 0) sb.append('、');
                        sb.append(e.getKey().replace("minecraft:", "")).append('×').append(e.getValue());
                        listed++;
                    }
                    lines.add("(" + cursor.getX() + "," + cursor.getY() + "," + cursor.getZ() + ") " + sb);
                }
            }
        }
        LOG.info("[rdd-storage] 扫描 原点={} 箱子命中={} 成行={}", origin, hits, lines.size());
        if (lines.isEmpty()) return new Scan("", Map.of());
        StringBuilder out = new StringBuilder("<storage nearby=\"").append(lines.size()).append("\">")
                .append("你身边的容器里放着：");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out.append("；");
            out.append(lines.get(i));
        }
        out.append("。这些**不在背包里**，要用得先走过去取；别重复去造/挖已有的存货。</storage>");
        return new Scan(out.toString(), Map.copyOf(totals));
    }

    /** 同一容器两次 refresh 的最小间隔（tick）：观测写入会落盘，别每 2 秒刷一次盘。 */
    private static final long OBS_REFRESH_TICKS = 200L;
    private static final Map<String, Long> OBS_AT = new ConcurrentHashMap<>();

    /**
     * 读一个容器的内容：**观测是唯一真源**，直读只在观测不可用（能力缺席/ERROR/PENDING）时兜底。
     *
     * <p>2026-11-05 收口：shadow 阶段结束（实机多轮 0 差异 + D-017 修复后刷新已可靠），
     * 不再每次都做两份读取——两份都读就永远有两个可能不一致的答案。
     *
     * @return 物品→数量（空表 = 已知为空）；两条路都读不到返回 null（**不是**空表）
     */
    private static Map<String, Integer> readContainer(NumenPlayer body, ServerLevel level,
                                                      BlockPos pos, long gameTime) {
        Map<String, Integer> observed = readViaObservation(body, pos, gameTime);
        if (observed != null) return observed;
        return readDirect(level, pos);
    }

    private static Map<String, Integer> readViaObservation(NumenPlayer body, BlockPos pos, long gameTime) {
        try {
            com.dwinovo.numen.api.observation.WorldObservationService svc =
                    com.dwinovo.numen.api.NumenPlugins.capability(
                            com.dwinovo.numen.api.observation.WorldObservationService.class);
            if (svc == null) return null;
            String target = "block:" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
            String dim = body.level().dimension().location().toString();
            var req = new com.dwinovo.numen.api.observation.WorldObservationService.Request(
                    "rdd-storage", dim, "containers", Map.of("container", target), 0L, 64, 0L);
            var resp = svc.read(req);
            if (resp == null) return null;
            // 容器内容**随时会变**（放进去/拿出来），所以不能只靠 TTL：按本地节奏定期 refresh，
            // 否则会拿旧快照当真（2026-11-05 02:41 实机：箱里已有 5 铁锭，观测里还是空）。
            Long last = OBS_AT.get(target);
            if (last == null || gameTime - last >= OBS_REFRESH_TICKS) {
                OBS_AT.put(target, gameTime);
                var refreshed = svc.refresh(req);
                if (refreshed != null) resp = refreshed;
            }
            if (resp == null
                    || resp.status() == com.dwinovo.numen.api.observation.WorldObservationService.Status.ERROR
                    || resp.status() == com.dwinovo.numen.api.observation.WorldObservationService.Status.PENDING) {
                return null;
            }
            Map<String, Integer> out = new LinkedHashMap<>();
            for (var item : resp.items()) {
                Object idObj = item.values().get("item");
                Object countObj = item.values().get("count");
                if (idObj instanceof String id && countObj instanceof Number n && n.intValue() > 0) {
                    out.merge(id, n.intValue(), Integer::sum);
                }
            }
            return out;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static Map<String, Integer> readDirect(ServerLevel level, BlockPos pos) {
        try {
            com.dwinovo.numen.api.storage.ContainerReader.Result read =
                    com.dwinovo.numen.platform.Services.CONTAINERS.read(level, pos);
            if (read == null || !read.known()) return null;
            return read.items();
        } catch (RuntimeException | LinkageError ex) {
            LOG.debug("[rdd-storage] 直读容器失败 ({},{},{})：{}",
                    pos.getX(), pos.getY(), pos.getZ(), ex.toString());
            return null;
        }
    }

    // 2026-11-04（037/T14）：删掉 dedupeSections / parseSlotLines。
    // 它们是"从人读文本里抠数字"的产物，而渲染用的是 '×'（U+00D7），正则要的却是 ASCII 'x'
    // —— 所以 RddFutureNeeds 这条消费链一直解析出空表，"存起来的东西对规划不可见"。
    // 现在扫描同时产出结构化计数（Scan.counts），消费者直接读数字，不再有文本可解析错。
}
