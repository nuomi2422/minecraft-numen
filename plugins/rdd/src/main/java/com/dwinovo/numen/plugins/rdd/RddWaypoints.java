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
    /** 每个维度里"门在这一侧"的坐标（= 从别处跳进来时的落点）。回家要走向**当前维度这一侧**的门。 */
    private static final Map<UUID, Map<String, String>> PORTAL_IN_DIM = new ConcurrentHashMap<>();

    static void observe(NumenPlayer ap) {
        if (ap == null || ap.level() == null) return;
        UUID id = ap.getUUID();
        String dim = ap.level().dimension().location().toString();
        var pos = ap.blockPosition();
        String here = pos.getX() + "," + pos.getY() + "," + pos.getZ();
        LAST_SEEN.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).put(dim, here);
        String prev = CURRENT_DIM.put(id, dim);
        if (prev != null && !prev.equals(dim)) {
            // 落点 = 这一侧的门（人是从门里出来的）——回家时走向它，而不是目标维度那侧的坐标
            PORTAL_IN_DIM.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).put(dim, here);
            PORTAL_IN_DIM.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).put(prev, LAST_SEEN.getOrDefault(id, Map.of()).getOrDefault(prev, ""));
            save();   // 落盘：重启后"回家"仍然有效
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
        loadIfNeeded();   // 重启后锚点在磁盘上，不加载等于没有
        if (id == null || wantedDimension == null || wantedDimension.isBlank()) return "";
        String cur = CURRENT_DIM.get(id);
        if (cur == null || cur.equals(wantedDimension)) return "";
        // ★ 要走向**当前维度这一侧**的门（她进来的那个落点），不是目标维度那侧的坐标。
        Map<String, String> m = PORTAL_IN_DIM.get(id);
        String at = m == null ? "" : m.getOrDefault(cur, "");
        if (at.isEmpty()) return "";
        String[] xyz = at.split(",");
        if (xyz.length != 3) return "";
        String dist = distanceRough(id, cur, xyz);
        return "<way_home>【下一步动作】先把身体派到门口：goto " + xyz[0] + " " + xyz[1] + " " + xyz[2]
                + "（从你当前位置约 " + dist + " 格，一次派到底，中途不用反复查状态；" + "**要站进门框里那一格**，不是站在门框底下）。"
                + "站进去之后**立刻 use_portal {kind:\"nether\"}** —— 那一步才是真正过门。"
                + "use_portal 只在约 96 格内找门，隔着几百格它会直接报找不到；"
                + "别在这个维度现造一座门——那要 10 个黑曜石，而下界没有水。"
                + "（这一侧的门在 " + at + "，属于 " + wantedDimension + " 的出入口）</way_home>";
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

    // ── 落盘（2026-10-28，#252 的下一步）：锚点原来只在内存，重启即空 ——
    //    而"先走远、再重启、再想回家"恰恰是最常见的处境。这里把它存成一个小 JSON：
    //    { "<uuid>": { "<dim>": "x,y,z", ... }, ... }（只记"门在这一侧"的坐标）
    private static java.nio.file.Path storeFile() {
        // 放在 config\numen 下（语义目录的兄弟），跟其它 RDD 状态同一处
        return RddPlugin.semanticsDir().resolveSibling("rdd-waypoints.json");
    }

    /** 启动/首次使用时读一次（失败就按空处理，不炸）。 */
    static void loadIfNeeded() {
        if (LOADED) return;
        LOADED = true;
        try {
            java.nio.file.Path p = storeFile();
            if (p == null || !java.nio.file.Files.isRegularFile(p)) return;
            String raw = java.nio.file.Files.readString(p, java.nio.charset.StandardCharsets.UTF_8);
            var root = com.google.gson.JsonParser.parseString(raw);
            if (!root.isJsonObject()) return;
            for (var e : root.getAsJsonObject().entrySet()) {
                try {
                    UUID id = UUID.fromString(e.getKey());
                    if (!e.getValue().isJsonObject()) continue;
                    Map<String, String> m = PORTAL_IN_DIM.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
                    for (var d : e.getValue().getAsJsonObject().entrySet()) {
                        m.put(d.getKey(), d.getValue().getAsString());
                    }
                } catch (RuntimeException ignored) {
                    // 单条坏数据不影响其它同伴
                }
            }
        } catch (RuntimeException | java.io.IOException ignored) {
            // fail-soft：读不到就当没有锚点
        }
    }

    private static volatile boolean LOADED = false;

    /** 落盘（每次维度切换后调一次，量很小）。 */
    private static void save() {
        try {
            java.nio.file.Path p = storeFile();
            if (p == null) return;
            var root = new com.google.gson.JsonObject();
            for (var e : PORTAL_IN_DIM.entrySet()) {
                var dims = new com.google.gson.JsonObject();
                for (var d : e.getValue().entrySet()) {
                    dims.addProperty(d.getKey(), d.getValue());
                }
                root.add(e.getKey().toString(), dims);
            }
            java.nio.file.Files.createDirectories(p.getParent());
            java.nio.file.Files.writeString(p, root.toString(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (RuntimeException | java.io.IOException ignored) {
            // fail-soft：存不下不影响本次提示
        }
    }
    static void clear(UUID id) {
        LAST_SEEN.remove(id);
        CURRENT_DIM.remove(id);
    }
}
