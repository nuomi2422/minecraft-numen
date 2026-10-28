package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.entity.NumenPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 维度锚点记忆（2026-10-28 实机，#248/#251）。
 *
 * <p><b>为什么要有它</b>：她自己在主世界搭了下界传送门、走进去、在下界一路找堡垒走到 500 格外，
 * 然后计划要她"回主世界"——而她**身边没有门**，于是在她的视角里唯一的路是"再造一座门"
 * （要 10 个黑曜石 + 水桶，两样都已经没有了）。于是整条链在下界空转。
 *
 * <p>根因不是计划错，是**没人记得门在哪**：
 * {@code use_portal} 只在 96 格内找门，而她的门在 520 格外。
 *
 * <p>所以这里在**每次跨维度的那一刻**记下"从哪个维度、在哪个坐标离开的"——
 * 那就是门的位置（人是从门里出来的）。需要回去时把这条坐标当**事实**递给她：
 * 先 {@code goto} 过去，再 {@code use_portal}。只给事实，不替她决定。
 *
 * <p>边界：只记最近一次跳跃；同一维度再次跳跃会覆盖。重启会丢（内存表）——
 * 但"跨维度"这件事本身在下界里通常只发生一次（进来），够用；真要长期保留该落盘（TODO）。
 */
final class RddWaypoints {

    private RddWaypoints() {}

    /** 每个同伴、每个维度：最后一次**在该维度里**看到的坐标 → 即"从那扇门离开"的位置。 */
    private static final Map<UUID, Map<String, String>> LAST_SEEN = new ConcurrentHashMap<>();
    /** 当前所在维度（用来识别"刚刚跳了维度"）。 */
    private static final Map<UUID, String> CURRENT_DIM = new ConcurrentHashMap<>();

    /**
     * 每 tick 调用：记录当前维度里的坐标；一旦发现维度变了，
     * 就把**上一个维度**里最后记下的坐标固化为"门的位置"。
     */
    static void observe(NumenPlayer ap) {
        if (ap == null || ap.level() == null) return;
        UUID id = ap.getUUID();
        String dim = ap.level().dimension().location().toString();
        var pos = ap.blockPosition();
        String here = pos.getX() + "," + pos.getY() + "," + pos.getZ();
        LAST_SEEN.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).put(dim, here);
        String prev = CURRENT_DIM.put(id, dim);
        if (prev != null && !prev.equals(dim)) {
            // 刚跳完维度：上一个维度里最后那格 = 那扇门的门口
            RddMonitor.publish("dimension_anchor", Map.of(
                    "companionId", id.toString(),
                    "from", prev,
                    "fromPos", LAST_SEEN.getOrDefault(id, Map.of()).getOrDefault(prev, ""),
                    "to", dim,
                    "toPos", here));
        }
    }

    /** 某个维度里最后一次留下的坐标（"门在哪"），没有则空串。 */
    static String lastPosIn(UUID id, String dimension) {
        Map<String, String> m = LAST_SEEN.get(id);
        return m == null ? "" : m.getOrDefault(dimension, "");
    }

    /**
     * 回家提示：当前在别的维度、而目标维度有锚点 → 给一句"门在 (x,y,z)，先 goto 再 use_portal"。
     *
     * @param wantedDimension 目标维度 id（例如 minecraft:overworld）；空 = 不提示
     * @return 空串表示没有可用锚点（不硬编一句废话）
     */
    static String returnHint(UUID id, String wantedDimension) {
        if (id == null || wantedDimension == null || wantedDimension.isBlank()) return "";
        String cur = CURRENT_DIM.get(id);
        if (cur == null || cur.equals(wantedDimension)) return "";
        String at = lastPosIn(id, wantedDimension);
        if (at.isEmpty()) return "";
        String[] xyz = at.split(",");
        if (xyz.length != 3) return "";
        return "<way_home>你从 " + wantedDimension + " 进来时，那扇门在 " + at
                + "（约 " + distanceRough(id, cur, xyz) + " 格）。要回去就先把身体挪到那附近"
                + "（goto " + xyz[0] + " " + xyz[1] + " " + xyz[2] + "），再 use_portal {kind:\"nether\"}——"
                + "use_portal 只在约 96 格内找门，隔着几百格它会直接报找不到。"
                + "别在下界现造一座门：那要 10 个黑曜石，而下界没有水。</way_home>";
    }

    private static String distanceRough(UUID id, String curDim, String[] target) {
        try {
            Map<String, String> m = LAST_SEEN.get(id);
            if (m == null) return "?";
            String here = m.get(curDim);
            if (here == null) return "?";
            String[] h = here.split(",");
            if (h.length != 3) return "?";
            double dx = Double.parseDouble(target[0]) - Double.parseDouble(h[0]);
            double dy = Double.parseDouble(target[1]) - Double.parseDouble(h[1]);
            double dz = Double.parseDouble(target[2]) - Double.parseDouble(h[2]);
            // 下界↔主世界 1:8 —— 这里只报个量级，不做换算，避免给出假精度
            return String.valueOf(Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz)));
        } catch (RuntimeException ignored) {
            return "?";
        }
    }

    static void clear(UUID id) {
        LAST_SEEN.remove(id);
        CURRENT_DIM.remove(id);
    }
}
