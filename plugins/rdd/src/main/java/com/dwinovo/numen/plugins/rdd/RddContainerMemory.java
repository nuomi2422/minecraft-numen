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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /** 工具返回行形如 "  slot 0: minecraft:iron_ingot x9"。 */
    private static final Pattern SLOT_LINE =
            Pattern.compile("([a-z0-9_.-]+:[a-z0-9_./-]+)\\s*x\\s*(\\d+)");

    private static final Map<UUID, String> CACHE = new ConcurrentHashMap<>();
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
            CACHE.put(id, scan(body));
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

    static void clear(UUID companionId) {
        if (companionId == null) return;
        CACHE.remove(companionId);
        LAST_REFRESH.remove(companionId);
    }

    /** 真正扫世界（**只在主线程调**）。 */
    private static String scan(NumenPlayer body) {
        ServerLevel level = body.serverLevel();
        List<String> lines = new ArrayList<>();
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
                    String dump;
                    try {
                        dump = new com.dwinovo.numen.core.tools.QueryExtraOps()
                                .inspectBlockStorage(cursor.getX(), cursor.getY(), cursor.getZ(), body);
                    } catch (RuntimeException | LinkageError ex) {
                        LOG.warn("[rdd-storage] 读容器失败 ({},{},{})：{}",
                                cursor.getX(), cursor.getY(), cursor.getZ(), ex.toString());
                        continue;
                    }
                    Map<String, Integer> counts = parseSlotLines(dump);
                    if (counts.isEmpty()) continue;
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
        if (lines.isEmpty()) return "";
        StringBuilder out = new StringBuilder("<storage nearby=\"").append(lines.size()).append("\">")
                .append("你身边的容器里放着：");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out.append("；");
            out.append(lines.get(i));
        }
        out.append("。这些**不在背包里**，要用得先走过去取；别重复去造/挖已有的存货。</storage>");
        return out.toString();
    }

    /** 从工具文本里抽出「物品 id × 数量」，按 id 汇总（同一物品可能占多个 slot）。 */
    static Map<String, Integer> parseSlotLines(String dump) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (dump == null || dump.isBlank()) return counts;
        Matcher m = SLOT_LINE.matcher(dump);
        while (m.find()) {
            int n;
            try {
                n = Integer.parseInt(m.group(2));
            } catch (NumberFormatException ex) {
                continue;
            }
            if (n <= 0) continue;
            counts.merge(m.group(1), n, Integer::sum);
        }
        return counts;
    }
}
