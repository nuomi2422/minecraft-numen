package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「你正在被打」——把敌怪与血量事实**主动推到执行上下文**（2026-10-27，D-01/D-02 的修法）。
 *
 * <h2>为什么要有它（证据，不是猜测）</h2>
 * `exp-session-stats` 扫最近 3 天 52 次死亡，死前一刻在做什么：
 * <pre>
 *   19:44 unknown  ← rdd_get_inventory
 *   19:44 骷髅     ← task_status
 *   19:46 骷髅     ← rdd_whereami
 *   19:43 骷髅     ← eat
 *   19:45 骷髅     ← eat
 * </pre>
 * 也就是说：**它被射的时候在查背包/查任务状态/吃东西**，而不是在打或跑。
 * 这与 #340「1783 次调用里 54% 是 rdd_get_inventory + task_status」互证——
 * 不是它不想跑，是**上下文里没有"你现在有危险"这条事实**，于是轮询比活命优先。
 *
 * <h2>设计纪律（与既有上下文块同口径）</h2>
 * <ul>
 *   <li><b>只读、只陈述</b>：不改判定、不派工、不建议具体动作（动作归执行 AI）；</li>
 *   <li><b>有界</b>：敌怪最多列 3 种、总长 &lt; 260 字；没有危险时返回空串（不占 token）；</li>
 *   <li><b>可测</b>：措辞与阈值在 {@link RddDangerText}（纯函数），本类只负责读世界；</li>
 *   <li><b>不制造假警报</b>：远处一只僵尸、满血 → 不弹块。</li>
 * </ul>
 */
final class RddDangerFacts {

    private RddDangerFacts() {}

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(RddDangerFacts.class);
    /** 观测不可用而走本地兜底的次数（按同伴），只用于诊断。 */
    private static final Map<java.util.UUID, Integer> LOG_LOCAL_AT = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 危险块**只在真的弹出来时**发一条事件（内容变化才发）。
     *
     * <p>为什么要有：这是保命块，但"它到底有没有弹"以前只能靠上下文日志里翻。
     * 有了事件，验 D-018 这种"游戏里到底会不会报警"的问题不用再猜。
     */
    private static void publishIfChanged(NumenPlayer body, String text, int hostiles, int close, int pct) {
        if (text == null || text.isEmpty()) return;
        String signature = hostiles + "/" + close + "/" + pct + "/" + text.length();
        String prev = LAST_PUBLISHED.put(body.getUUID(), signature);
        if (signature.equals(prev)) return;
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("companionId", body.getUUID().toString());
        data.put("hostiles", hostiles);
        data.put("close", close);
        data.put("healthPercent", pct);
        data.put("text", text);
        try {
            RddMonitor.publish("danger_rendered", data);
        } catch (RuntimeException ignored) {
            // 事件发不出去不影响上下文
        }
    }

    /**
     * 这次 entities 响应能不能当当前事实用（042 R2 条件 2）。
     *
     * <p>只有 READY / READY_PARTIAL 算可用；STALE / PENDING / UNKNOWN 一律不可用——
     * 不能因为 `status != ERROR` 就把过期数据当成"当前有几只敌怪"。
     */
    private static com.dwinovo.numen.api.observation.Readiness usability(
            com.dwinovo.numen.api.observation.WorldObservationService.Response resp) {
        var required = java.util.Set.of("count");
        java.util.List<com.dwinovo.numen.api.observation.Readiness> per = new java.util.ArrayList<>();
        for (var item : resp.items()) {
            per.add(com.dwinovo.numen.api.observation.Readiness.assess(
                    resp.status(), resp.coverage(), required,
                    item.values().keySet(), item.unknownFields(), item.staleFields()));
        }
        return com.dwinovo.numen.api.observation.Readiness
                .worstOf(per, resp.status(), resp.coverage());
    }

    private static final Map<java.util.UUID, String> LAST_PUBLISHED =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 本地兜底：返回 {@code [hostiles, close]}；读不到返回 null。 */
    private static int[] localHostiles(NumenPlayer body, net.minecraft.server.level.ServerLevel level) {
        try {
            net.minecraft.world.phys.AABB box =
                    body.getBoundingBox().inflate(RddDangerText.SCAN_RADIUS);
            int hostiles = 0, close = 0;
            int seen = 0;
            for (net.minecraft.world.entity.Mob mob
                    : level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, box)) {
                if (seen++ >= MAX_SAMPLED_ENTITIES) break;      // 单次实体数上限（042 R2 条件 1）
                if (!(mob instanceof net.minecraft.world.entity.monster.Monster) || !mob.isAlive()) continue;
                hostiles++;
                if (mob.distanceTo(body) <= RddDangerText.CLOSE_RADIUS) close++;
            }
            return new int[]{hostiles, close};
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 本地兜底的按类型明细。 */
    private static Map<String, Integer> localKinds(NumenPlayer body, net.minecraft.server.level.ServerLevel level) {
        Map<String, Integer> kinds = new LinkedHashMap<>();
        try {
            net.minecraft.world.phys.AABB box =
                    body.getBoundingBox().inflate(RddDangerText.SCAN_RADIUS);
            for (net.minecraft.world.entity.Mob mob
                    : level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, box)) {
                if (!(mob instanceof net.minecraft.world.entity.monster.Monster) || !mob.isAlive()) continue;
                String id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                        .getKey(mob.getType()).getPath();
                kinds.merge(id, 1, Integer::sum);
            }
        } catch (RuntimeException ignored) {
            // 明细拿不到不影响主判据
        }
        return kinds;
    }

    /** 最近一次从观测读到的按类型明细（只在读取线程内使用，键=同伴）。 */
    private static final Map<java.util.UUID, Map<String, Integer>> observedKinds =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 读统一观测回答"附近有几只敌怪、贴身几只、都是什么"。
     *
     * <p>两个盒子各读一次（扫描半径 / 贴身半径）——只读缓存，不扫世界；由
     * {@code RddObservation} 在服务端 tick 里按 5 秒节奏刷新。
     *
     * @return {@code [hostiles, close]}；任一观测不可用返回 {@code null}（调用方据此**不报警**）
     */
    private static int[] observedHostiles(NumenPlayer body) {
        try {
            var svc = com.dwinovo.numen.api.NumenPlugins.capability(
                    com.dwinovo.numen.api.observation.WorldObservationService.class);
            if (svc == null) return null;
            String dim = body.level().dimension().location().toString();
            var pos = body.blockPosition();
            int[] out = new int[2];
            Map<String, Integer> kinds = new LinkedHashMap<>();
            for (int i = 0; i < 2; i++) {
                int r = i == 0 ? RddDangerText.SCAN_RADIUS : RddDangerText.CLOSE_RADIUS;
                var resp = svc.read(new com.dwinovo.numen.api.observation.WorldObservationService.Request(
                        "rdd-danger", dim, "entities",
                        Map.of("box", RddObservation.dangerBox(pos, r), "category", "hostile"), 0L, 64, 0L));
                if (resp == null) return null;
                // 042 R2 条件 2：**过期观测不能用**。status 不是 ERROR/PENDING 也不代表新鲜——
                // ObservationMap 把 FULL&&!stale 之外的一律标 Status.PARTIAL，所以必须看 Readiness。
                var readable = usability(resp);
                if (!readable.canReportPresence()) return null;
                if (resp.items().isEmpty()) return null;
                Object c = resp.items().get(0).values().get("count");
                if (!(c instanceof Number n)) return null;
                out[i] = n.intValue();
                if (i == 0) {
                    Object tc = resp.items().get(0).values().get("type_counts");
                    if (tc instanceof Map<?, ?> m) {
                        for (var e : m.entrySet()) {
                            if (e.getKey() instanceof String k && e.getValue() instanceof Number v) {
                                kinds.merge(k.replace("minecraft:", ""), v.intValue(), Integer::sum);
                            }
                        }
                    }
                }
            }
            observedKinds.put(body.getUUID(), kinds);
            return out;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * tick 采样（**只在服务端线程**）：读世界 → 写不可变快照。
     *
     * <p>为什么必须在这里读：上下文组装不保证在主线程，`blockFor` 里读世界就是 042 R2 说的线程违规。
     * 但"挪到 tick" ≠ "改成等观测服务"——039 §6 要求保命反射保持本地，本地方案就是本方法。
     */
    /** 采样间隔与实体数上限：可调，不写死（042 R2 条件 1）。 */
    static int SAMPLE_INTERVAL_TICKS = 5;
    static int MAX_SAMPLED_ENTITIES = 128;
    private static final Map<java.util.UUID, Long> LAST_SAMPLE_TICK =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * **读写的唯一时钟源**。
     *
     * <p>审计实测过：写入用 `server.getTickCount()`、读取用 `level.getGameTime` 是两个计数器
     * （前者每次开服从 0 起，后者跟着存档走）→ 重启后 age 变成巨大值 → 快照永远不新鲜 →
     * 观测一不可用就永久"未知"。两次都调这里，才谈得上"同源"。
     */
    static long currentTick(ServerLevel level) {
        return RddClock.current(level);
    }

    /** 每只同伴各自的耗时统计（混在一起会失真：两只时 n 是两只之和，间隔算出半速）。 */
    private static final class Perf {
        long samples;
        long skipped;
        long totalNanos;
        long maxNanos;
        long firstTick = -1L;
    }

    private static final int PERF_LOG_EVERY = 200;
    private static final Map<java.util.UUID, Perf> PERF =
            new java.util.concurrent.ConcurrentHashMap<>();

    static void sampleOnTick(NumenPlayer body) {
        try {
            if (body == null || !(body.level() instanceof ServerLevel level)) return;
            java.util.UUID id = body.getUUID();
            long tick = currentTick(level);
            Long last = LAST_SAMPLE_TICK.get(id);
            if (last != null && tick - last < SAMPLE_INTERVAL_TICKS) return;   // 有界采样，不是每 tick 扫
            LAST_SAMPLE_TICK.put(id, tick);
            Perf perf = PERF.computeIfAbsent(id, k -> new Perf());
            if (perf.firstTick < 0) perf.firstTick = tick;
            long t0 = System.nanoTime();
            var local = localHostiles(body, level);
            if (local == null) {
                recordSkipped(perf);     // 失败的采样也要计数，否则平均值是"幸存者平均"
                return;
            }
            Map<String, Integer> kinds = localKinds(body, level);
            long cost = System.nanoTime() - t0;
            recordPerf(perf, cost, tick);
            DANGER_SNAPSHOT.put(id, new DangerSnapshot(local[0], local[1], Map.copyOf(kinds), tick));
        } catch (RuntimeException | LinkageError ignored) {
            // 采样失败不影响 tick
        }
    }

    private static void recordSkipped(Perf perf) {
        synchronized (perf) {
            perf.skipped++;
        }
    }

    /**
     * 记录一次**成功**采样的耗时；每 200 次输出平均值、最大值、两种间隔与跳过次数。
     *
     * <p>间隔必须由 tick 算，且**按同伴分别统计**：
     * <ul>
     *   <li>混在一起时，两只同伴的 n 是两只之和、spanTicks 却是同一个轴 →
     *       算出的"间隔"会是单只的一半，看起来像采样频率翻倍；</li>
     *   <li>间隔除的是成功次数，而窗口覆盖的是全部尝试（含 skipped）→ 所以要分开报
     *       "成功间隔"和"尝试间隔"，否则一个名字两种含义。</li>
     * </ul>
     * 墙钟也推不出 tick 间隔（窗口失焦时集成服务器暂停：游戏时间停、墙钟走）。
     */
    private static void recordPerf(Perf perf, long costNanos, long tick) {
        synchronized (perf) {
            perf.samples++;
            perf.totalNanos += costNanos;
            if (costNanos > perf.maxNanos) perf.maxNanos = costNanos;
            if (perf.samples % PERF_LOG_EVERY == 0) {
                long avgUs = perf.totalNanos / perf.samples / 1000L;
                long maxUs = perf.maxNanos / 1000L;
                long spanTicks = perf.firstTick < 0 ? 0 : (tick - perf.firstTick);
                long attempts = perf.samples + perf.skipped;
                long okInterval = perf.samples > 0 ? Math.max(1, spanTicks / perf.samples) : 0;
                long tryInterval = attempts > 0 ? Math.max(1, spanTicks / attempts) : 0;
                LOG.info("[rdd-danger] tick采样 n={} skipped={} 平均={}us 最大={}us 成功间隔≈{}tick 尝试间隔≈{}tick 窗口={}tick",
                        perf.samples, perf.skipped, avgUs, maxUs, okInterval, tryInterval, spanTicks);
                perf.totalNanos = 0L;
                perf.maxNanos = 0L;
                perf.samples = 0L;
                perf.skipped = 0L;
                perf.firstTick = -1L;
            }
        }
    }

    /** 一只同伴的危险快照（不可变）。 */
    record DangerSnapshot(int hostiles, int close, Map<String, Integer> kinds, long tick) {}

    private static final Map<java.util.UUID, DangerSnapshot> DANGER_SNAPSHOT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 清掉某只同伴的快照（换身体/退场时用）。 */
    static void forget(java.util.UUID companionId) {
        if (companionId != null) DANGER_SNAPSHOT.remove(companionId);
    }

    /**
     * 从缓存/观测取事实；**绝不在这里读世界**，读不到就给明确的"未知"。
     */
    static String blockFor(NumenPlayer body) {
        try {
            if (body == null || !(body.level() instanceof ServerLevel level)) {
                return "";
            }
            // 039 U5#7：先读统一观测（entities/hostile，两个盒子：扫描半径 / 贴身半径）。
            int[] observed = observedHostiles(body);
            int hostiles;
            int close;
            Map<String, Integer> kinds = new LinkedHashMap<>();
            if (observed != null) {
                hostiles = observed[0];
                close = observed[1];
                kinds.putAll(observedKinds.getOrDefault(body.getUUID(), Map.of()));
            } else {
                // 观测不可用 → 用 tick 采样写下的快照（**不读世界**）。
                DangerSnapshot snap = DANGER_SNAPSHOT.get(body.getUUID());
                long nowTick = currentTick(level);   // 与写入侧同源（见 currentTick 注释）
                if (snap == null
                        || !RddDangerText.isFresh(nowTick, snap.tick(), RddDangerText.SNAPSHOT_TTL_TICKS)) {
                    // 既没观测、也没新鲜快照 → 明确说"未知"，不许用空串冒充"安全"
                    return RddDangerText.unknownMarker();
                }
                hostiles = snap.hostiles();
                close = snap.close();
                kinds = new LinkedHashMap<>(snap.kinds());
                LOG_LOCAL_AT.merge(body.getUUID(), 1, Integer::sum);
            }
            if (hostiles == 0) {
                return "";
            }
            StringBuilder kindText = new StringBuilder();
            int shown = 0;
            for (Map.Entry<String, Integer> e : kinds.entrySet()) {
                if (shown == 3) break;
                if (kindText.length() > 0) kindText.append('、');
                kindText.append(e.getKey());
                if (e.getValue() > 1) kindText.append('×').append(e.getValue());
                shown++;
            }
            int pct = Math.round(body.getHealth() / Math.max(1f, body.getMaxHealth()) * 100f);
            String text = RddDangerText.block(hostiles, close, pct, kindText.toString());
            publishIfChanged(body, text, hostiles, close, pct);
            return text;
        } catch (RuntimeException ex) {
            return "";   // 增强块，绝不能拖崩上下文构建
        }
    }
}
