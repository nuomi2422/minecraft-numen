package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.entity.NumenPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A3（95 号）：环境/能力/停滞的「四层事实」第一版。
 *
 * <p>只做两层，且都只读实体/仪表、不改规划：
 * <ul>
 *   <li><b>fact</b>：{@code in_water} / {@code in_lava} / {@code low_hp} / {@code no_food}；</li>
 *   <li><b>progress</b>：{@code stalled}（同一坐标连续 60s 不变）。</li>
 * </ul>
 *
 * <p>capability（攻击对飞行/水晶无效）与 tool（重复查询）两层需要工具回执钩子，
 * 本版**未接**——卡 V-20261029-04 判据②④仍记 NOT_EXERCISED，不假装做了。
 *
 * <p>去抖：同一 {@code (layer, kind, subject)} 在 {@link #DEBOUNCE_MS} 内只发一次，
 * 被抑制的次数累加进下一条的 {@code suppressed}（不静默丢）。
 *
 * <p>纯分类逻辑 {@link #classify(Snapshot)} 可单测，不依赖 Minecraft。
 */
final class RddSituationFacts {

    // 2026-11-04（037/T8b 收尾）：原来借 core:common 的 Constants.LOG，等于为了打日志
    // 也要依赖别人的内部类；插件用自己的 logger，依赖面才能真的收掉。
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(RddSituationFacts.class);

    static final long DEBOUNCE_MS = 60_000L;
    static final int LOW_HP = 6;
    /** 饥饿值 <= 6 视为「没食物」（原版 20 满，6 以下不能疾跑）。 */
    static final int NO_FOOD = 6;
    static final long STALL_TICKS = 60L * 20L;

    private RddSituationFacts() {}

    /** 一次采样的纯数据，供分类与单测。 */
    record Snapshot(boolean inWater, boolean inLava, float hp, int food, long posUnchangedTicks) {}

    /** 一次采样应报的层:类型（有序，同一拍可能多条）。 */
    static List<String> classify(Snapshot s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        if (s.inLava()) out.add("fact:in_lava");
        else if (s.inWater()) out.add("fact:in_water");
        if (s.hp() <= LOW_HP) out.add("fact:low_hp");
        if (s.food() <= NO_FOOD) out.add("fact:no_food");
        if (s.posUnchangedTicks() >= STALL_TICKS) out.add("progress:stalled");
        return out;
    }

    private static final Map<String, long[]> LAST = new ConcurrentHashMap<>(); // key -> {lastAt, suppressed}
    private static final Map<UUID, long[]> POS = new ConcurrentHashMap<>();    // uuid -> {x,y,z,lastMoveTick}

    /** 每 tick 调一次（放在 tickRuntime 的早退之前）。 */
    static void tick(NumenPlayer ap) {
        if (ap == null || ap.level() == null) return;
        try {
            UUID id = ap.getUUID();
            var pos = ap.blockPosition();
            long now = RddInstrumentation.currentGameTimeTicks();
            long[] p = POS.computeIfAbsent(id, k -> new long[] { pos.getX(), pos.getY(), pos.getZ(), now });
            if (p[0] != pos.getX() || p[1] != pos.getY() || p[2] != pos.getZ()) {
                p[0] = pos.getX();
                p[1] = pos.getY();
                p[2] = pos.getZ();
                p[3] = now;
            }
            long unchanged = Math.max(0, now - p[3]);
            Snapshot s = new Snapshot(ap.isInWater(), ap.isInLava(),
                    ap.getHealth(), ap.getFoodData().getFoodLevel(), unchanged);
            long wall = System.currentTimeMillis();
            for (String kind : classify(s)) {
                String[] parts = kind.split(":", 2);
                emit(parts[0], parts[1], id, now, wall, ap);
            }
        } catch (RuntimeException ex) {
            // 观测失败不能拖垮 tick；但也别静默到查无此人
            LOG.info("[rdd-situation] tick FAILED: {}", ex.toString());
        }
    }

    private static void emit(String layer, String kind, UUID id, long gameTime, long wall, NumenPlayer ap) {
        String key = layer + "|" + kind + "|" + id;
        long[] e = LAST.computeIfAbsent(key, k -> new long[] { 0L, 0L });
        long suppressed = 0;
        if (wall - e[0] < DEBOUNCE_MS) {
            e[1] += 1;
            return;
        }
        suppressed = e[1];
        e[0] = wall;
        e[1] = 0;
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("layer", layer);
        data.put("kind", kind);
        data.put("subject", id.toString());
        data.put("suppressed", suppressed);
        data.put("gameTime", gameTime);
        data.put("coverage", "COMPLETE");
        var pos = ap.blockPosition();
        data.put("pos", pos.getX() + "," + pos.getY() + "," + pos.getZ());
        if ("fact".equals(layer)) {
            data.put("hp", ap.getHealth());
            data.put("food", ap.getFoodData().getFoodLevel());
        }
        RddMonitor.publish("situation_event", data);
    }

    /** 单测用：清掉去抖/坐标状态。 */
    static void resetForTest() {
        LAST.clear();
        POS.clear();
    }
}
