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

    /** 本地兜底：返回 {@code [hostiles, close]}；读不到返回 null。 */
    private static int[] localHostiles(NumenPlayer body, net.minecraft.server.level.ServerLevel level) {
        try {
            net.minecraft.world.phys.AABB box =
                    body.getBoundingBox().inflate(RddDangerText.SCAN_RADIUS);
            int hostiles = 0, close = 0;
            for (net.minecraft.world.entity.Mob mob
                    : level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, box)) {
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
                if (resp == null
                        || resp.status() == com.dwinovo.numen.api.observation.WorldObservationService.Status.ERROR
                        || resp.status() == com.dwinovo.numen.api.observation.WorldObservationService.Status.PENDING
                        || resp.items().isEmpty()) {
                    return null;
                }
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

    /** 从真实世界取事实；读不到就返回空串（不做假警报，也不编数）。 */
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
                // 观测不可用时**安全反射仍走本地**：039 §6 明确"安全反射保持本地执行，
                // 不等待统一服务返回才救命"。危险块是保命用的，宁可多扫一次也不能变空。
                var local = localHostiles(body, level);
                if (local == null) return "";
                hostiles = local[0];
                close = local[1];
                kinds = localKinds(body, level);
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
            return RddDangerText.block(hostiles, close, pct, kindText.toString());
        } catch (RuntimeException ex) {
            return "";   // 增强块，绝不能拖崩上下文构建
        }
    }
}
