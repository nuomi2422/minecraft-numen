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
    /** 上次**扫描成功**的墙钟毫秒：缓存可能是旧的，旧到什么程度要说得出来（R2：旧数据只能标历史）。 */
    private static final Map<UUID, Long> LAST_OK = new ConcurrentHashMap<>();
    /** 标记规则在纯类里（R2）：本类一加载就碰 Minecraft 类型，纯 JVM 单测测不了。 */
    static final long STALE_AFTER_MS = RddStorageStaleness.STALE_AFTER_MS;
    /** 每只同伴上次看到的 streamEpoch（v2 游标必须与 epoch 成对，缺了服务端会判 reset）。 */
    private static final Map<UUID, String> CHANGE_EPOCH = new ConcurrentHashMap<>();

    /** 041 U4c：每只同伴一个增量轮询器（内含游标）。 */
    private static final Map<UUID, ContainerChangePoller> POLLERS = new ConcurrentHashMap<>();

    private RddContainerMemory() {}

    /** 服务端 tick（主线程）调用：扫描并写缓存。 */
    static void refresh(NumenPlayer body, long tick) {
        if (body == null) return;
        UUID id = body.getUUID();
        Long last = LAST_REFRESH.get(id);
        boolean due = last == null || tick - last >= REFRESH_TICKS;
        // 041 U4c：只要容器观测**有过变化**就立刻重扫，等于把"别人刷新过"这件事当成信号；
        // 没有变化时仍按 2 秒节奏扫（世界变化只能靠扫发现，变化日志只知道"观测被写过"）。
        boolean changed = pollContainerChanges(id);
        if (!due && !changed) return;
        LAST_REFRESH.put(id, tick);
        try {
            Scan scan = scan(body, tick);
            CACHE.put(id, scan.text());
            COUNTS.put(id, scan.counts());
            LAST_OK.put(id, System.currentTimeMillis());
        } catch (RuntimeException | LinkageError ex) {
            // 读世界失败不影响主链路；保留上一次缓存（宁可旧一点，也不要突然说"没有"）
            LOG.warn("[rdd-storage] 扫描失败 {}: {}", id, ex.toString());
        }
    }

    /**
     * 读一次 containers 主题的增量（041 U4c）。
     *
     * @return true=有新变化（含历史断档，断档时更要重扫）；false=没变化或增量不可用
     */
    private static boolean pollContainerChanges(UUID id) {
        try {
            var svc = com.dwinovo.numen.api.NumenPlugins.capability(
                    com.dwinovo.numen.api.observation.WorldObservationService.class);
            if (svc == null) {
                LOG_CHANGE.maybe(id, "capability = null（观测能力没拿到）");
                return false;
            }
            ContainerChangePoller poller = POLLERS.computeIfAbsent(id, k -> new ContainerChangePoller());
            // 决策交给 ContainerChangePoller（同口径、离线可测）；这里只负责"怎么问能力面"
            return poller.poll(cursor -> {
                // R3 第 4 步：**显式声明 v2**。v1 的老入口仍保留给老消费者，但本消费者不再二分猜测。
                var q = com.dwinovo.numen.api.observation.ChangesQuery.v2(
                        cursor, CHANGE_EPOCH.getOrDefault(id, ""), java.util.List.of("containers"), 64);
                var v2 = svc.changes(q);
                if (v2 == null || v2.status() == com.dwinovo.numen.api.observation.WorldObservationService.Status.ERROR) {
                    String why = v2 == null ? "null" : v2.status() + " reason=" + v2.reason();
                    // 老宿主不认 v2（过渡期）→ 明确回退一次老入口，并记一条，不静默
                    if (v2 != null && v2.reason() != null && v2.reason().contains("not supported")) {
                        // 回退必须**把游标归零**：此刻 poller 里的 cursor 可能已经是 v2 的 streamSeq，
                        // 而 v1 的 after 是 observation revision——两个命名空间，混用会静默返回错误切片。
                        LOG_CHANGE.maybe(id, "v2 增量不受支持，回退 v1 且游标重置（" + why + "）");
                        LOG.info("[rdd-storage] containers 回退 v1 导致游标重置 → 全量重扫一次 companion={}", id);
                        var legacy = svc.changes(0L, java.util.List.of("containers"), 64);
                        if (legacy == null
                                || legacy.status() == com.dwinovo.numen.api.observation.WorldObservationService.Status.ERROR) {
                            LOG_CHANGE.maybe(id, "v1 增量也不可用");
                            return null;
                        }
                        return new ContainerChangePoller.Page(true, 0L, legacy.changes().size());
                    }
                    LOG_CHANGE.maybe(id, "增量不可用 " + why);
                    return null;      // 拿不到增量 → 调用方退回节奏扫描
                }
                String prevEpoch = CHANGE_EPOCH.get(id);
                CHANGE_EPOCH.put(id, v2.streamEpoch());
                LOG_CHANGE.maybe(id, "增量(v2) cursor=" + cursor + " next=" + v2.nextCursor()
                        + " reset=" + v2.resetRequired() + " n=" + v2.events().size()
                        + " epoch=" + v2.streamEpoch());
                if (v2.resetRequired()) {
                    // 三种 reset 是三种处置，必须分开记（审计上轮的死要求）
                    String whyReset;
                    if (prevEpoch == null || prevEpoch.isBlank()) {
                        // 本进程第一次问增量：没有游标可言。注意这**不是**"换流"——
                        // 换流要求先有旧 epoch 再变；新进程的旧 epoch 只存在于持久化里（尚未实现）。
                        whyReset = "新进程（无游标）";
                    } else if (!prevEpoch.equals(v2.streamEpoch())) {
                        whyReset = "换流（epoch " + prevEpoch + " → " + v2.streamEpoch() + "）";
                    } else {
                        whyReset = "真断档（保留期淘汰）";
                    }
                    LOG.info("[rdd-storage] containers 游标重置[{}] companion={} → 重扫并强制刷新", whyReset, id);
                    // 只清本同伴当前维度的节流键，别让一只同伴换流触发全场刷新风暴
                    int cleared = clearThrottleFor(id);
                    LOG_CHANGE.maybe(id, "节流清理 cleared=" + cleared + "（仅本同伴维度）");
                    if (cleared == 0) {
                        LOG_CHANGE.maybe(id, "未匹配到本同伴节流键：节流表无维度前缀，请检查 containerKey");
                    }
                } else if (!v2.events().isEmpty()) {
                    LOG.info("[rdd-storage] change-signal(v2) n={} cursor={} next={}",
                            v2.events().size(), cursor, v2.nextCursor());
                }
                return new ContainerChangePoller.Page(v2.resetRequired(), v2.nextCursor(),
                        v2.events().size());
            });
        } catch (RuntimeException | LinkageError e) {
            LOG_CHANGE.maybe(id, "poll 失败: " + e);
            return false;
        }
    }

    /** 增量轮询的诊断（每 30 秒最多一条，避免刷屏）。 */
    private static final class ChangeDiag {
        private final Map<UUID, Long> last = new ConcurrentHashMap<>();

        void maybe(UUID id, String msg) {
            long now = System.currentTimeMillis();
            Long prev = last.put(id, now);
            if (prev != null && now - prev < 30_000L) return;
            LOG.info("[rdd-storage] {}", msg);
        }
    }

    private static final ChangeDiag LOG_CHANGE = new ChangeDiag();

    /** 上下文构建（可能在非主线程）调用：只读缓存。 */
    static String cached(UUID companionId) {
        if (companionId == null) return "";
        String s = CACHE.get(companionId);
        if (s == null || s.isEmpty()) return "";
        Long ok = LAST_OK.get(companionId);
        if (ok == null) return s;
        return RddStorageStaleness.markStale(s, System.currentTimeMillis() - ok, STALE_AFTER_MS);
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
        LAST_OK.remove(companionId);
        POLLERS.remove(companionId);
    }

    /** 一次扫描的产物：给人看的文字 + 给规划用的计数 + **这批数字有没有来自直读兜底**。 */
    private record Scan(String text, Map<String, Integer> counts, boolean anyDirect) {
    }

    /** 一个容器的读结果：计数 + 出处（观测 / 直读兜底）。 */
    private record ContainerRead(Map<String, Integer> counts, boolean direct) {
    }

    /** 真正扫世界（**只在主线程调**）。 */
    private static Scan scan(NumenPlayer body, long tick) {
        ServerLevel level = body.serverLevel();
        List<String> lines = new ArrayList<>();
        Map<String, Integer> totals = new LinkedHashMap<>();
        boolean anyDirect = false;
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
                    ContainerRead read = readContainer(body, level, cursor.immutable(), tick);
                    if (read == null || read.counts().isEmpty()) continue;
                    if (read.direct()) anyDirect = true;      // 042 R2：直读兜底要留出处
                    Map<String, Integer> counts = read.counts();
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
        if (lines.isEmpty()) return new Scan("", Map.of(), anyDirect);
        StringBuilder out = new StringBuilder("<storage nearby=\"").append(lines.size()).append('"')
                .append(scanDirectAttr(anyDirect)).append('>')
                .append("你身边的容器里放着：");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out.append("；");
            out.append(lines.get(i));
        }
        out.append("。这些**不在背包里**，要用得先走过去取；别重复去造/挖已有的存货。</storage>");
        return new Scan(out.toString(), Map.copyOf(totals), anyDirect);
    }

    /** 出处属性：直读兜底时在 <storage> 上标出来源，让上下文知道"这不是观测数据"。 */
    private static String scanDirectAttr(boolean anyDirect) {
        return anyDirect ? " source=\"direct\"" : "";
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
    private static ContainerRead readContainer(NumenPlayer body, ServerLevel level,
                                               BlockPos pos, long tick) {
        Map<String, Integer> observed = readViaObservation(body, pos, tick);
        if (observed != null) return new ContainerRead(observed, false);
        Map<String, Integer> direct = readDirect(level, pos);
        // 直读是**当前值**（比旧观测新），不能降级成"历史"；但必须留出处，让调用方知道它不是观测来的。
        return direct == null ? null : new ContainerRead(direct, true);
    }

    /**
     * 节流键：世界 + 维度 + 目标（042 R2 第 5 条）。
     *
     * <p>只按坐标做键时，换世界/换维度会命中别的维度同坐标容器的节流记录——
     * 轻则漏刷新，重则把 A 世界的刷新时间当成 B 世界的。
     */
    private static String containerKey(ServerLevel level, String target) {
        return worldPrefix(level) + target;
    }

    /** 世界 + 维度前缀：节流键与"按同伴清理"共用同一套口径。 */
    private static String worldPrefix(ServerLevel level) {
        String world = "?";
        try {
            world = level.getServer().getWorldData().getLevelName();
        } catch (RuntimeException ignored) {
            // 名字拿不到就退化成"?"，仍然比只按坐标好
        }
        return world + "|" + level.dimension().location() + "|";
    }

    /**
     * 只清**该同伴当前世界/维度**的节流键。
     *
     * <p>为什么不能 OBS_AT.clear()：键是跨同伴的，一只同伴换流会把所有同伴、所有容器的节流清零 →
     * 全场强制 refresh、每个容器一次落盘。一只同伴换流触发全场风暴，这在现场会表现成"莫名其妙卡一下"。
     */
    private static int clearThrottleFor(java.util.UUID id) {
        try {
            var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) return 0;
            var body = com.dwinovo.numen.entity.NumenPlayer.findByUuid(server, id);
            if (body == null || body.level() == null) return 0;
            String prefix = worldPrefix((ServerLevel) body.level());
            int n = 0;
            for (var it = OBS_AT.keySet().iterator(); it.hasNext(); ) {
                if (it.next().startsWith(prefix)) {
                    it.remove();
                    n++;
                }
            }
            return n;
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    private static Map<String, Integer> readViaObservation(NumenPlayer body, BlockPos pos, long tick) {
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
            // 节流表要防长跑膨胀：换世界/走远都会留下旧键，超上限就整体清一次（简单但不会泄漏）
            if (OBS_AT.size() > 1024) OBS_AT.clear();
            String key = containerKey(body.serverLevel(), target);
            Long last = OBS_AT.get(key);
            if (last == null || tick - last >= OBS_REFRESH_TICKS) {
                OBS_AT.put(key, tick);
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