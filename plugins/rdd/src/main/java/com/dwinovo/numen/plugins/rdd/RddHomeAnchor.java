package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.HomeAnchorPolicy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 家锚点的落盘与取用（167 §9.14 选项 B：第一次绑定即为家）。
 *
 * <p>为什么必须落盘：不落盘就只能靠「当前重生点」推断家，
 * 而它一天换三次床，于是「家」跟着人跑（实机：base 观测散在 6 个坐标上）。
 *
 * <p>文件：`config/numen/home-anchor-&lt;uuid&gt;.json`，格式与其它缓存一致（小、可读、坏文件当没有）。
 */
final class RddHomeAnchor {

    private RddHomeAnchor() {}

    private static final ConcurrentHashMap<UUID, HomeAnchorPolicy.Anchor> CACHE = new ConcurrentHashMap<>();

    /** 取家锚点；没有就按当前复活点定一次（只定一次）。失败返回 null，不编造。 */
    static HomeAnchorPolicy.Anchor current(UUID companionId, String respawnDim,
                                           Integer x, Integer y, Integer z) {
        if (companionId == null) return null;
        HomeAnchorPolicy.Anchor existing = CACHE.get(companionId);
        if (existing == null) existing = load(companionId);
        HomeAnchorPolicy.Anchor decided = HomeAnchorPolicy.decide(
                existing, respawnDim, x, y, z, System.currentTimeMillis());
        if (decided != null && existing == null) {
            CACHE.put(companionId, decided);
            save(companionId, decided);
        }
        return decided;
    }

    /** 一句话陈述「家 vs 复活点」，家未定时为空串。 */
    static String note(UUID companionId, HomeAnchorPolicy.Anchor anchor,
                       Integer rx, Integer ry, Integer rz) {
        if (anchor == null) return "";
        boolean moved = HomeAnchorPolicy.respawnMovedAway(anchor, rx, ry, rz, 8.0);
        return moved
                ? "home_anchor=" + anchor.dimension() + " " + anchor.x() + " " + anchor.y() + " " + anchor.z()
                        + "（复活点已离开家 8 格以上：家不变，别把新床旁边当成基地）"
                : "";
    }

    /** 与其它缓存同目录（由 RddPlugin 在初始化时写入 FACTS_DIR）。 */
    static volatile Path DIR;

    private static Path fileFor(UUID id) {
        Path dir = DIR;
        return dir == null ? null : dir.resolve("home-anchor-" + id + ".json");
    }

    private static HomeAnchorPolicy.Anchor load(UUID id) {
        try {
            Path f = fileFor(id);
            if (f == null || !Files.exists(f)) return null;
            String s = Files.readString(f, StandardCharsets.UTF_8);
            // 极简解析：只认我们自己写的四个字段，坏了就当没有
            String dim = field(s, "dimension");
            Integer x = intField(s, "x");
            Integer y = intField(s, "y");
            Integer z = intField(s, "z");
            if (dim == null || x == null || y == null || z == null) return null;
            return new HomeAnchorPolicy.Anchor(dim, x, y, z, 0L);
        } catch (Exception ex) {
            return null;
        }
    }

    private static void save(UUID id, HomeAnchorPolicy.Anchor a) {
        try {
            Path f = fileFor(id);
            if (f == null) return;
            Files.createDirectories(f.getParent());
            String json = "{\"dimension\":\"" + a.dimension() + "\",\"x\":" + a.x()
                    + ",\"y\":" + a.y() + ",\"z\":" + a.z() + ",\"setAt\":" + a.setAtMillis() + "}";
            Files.writeString(f, json, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 落盘失败不影响上下文
        }
    }

    private static String field(String s, String key) {
        int i = s.indexOf("\"" + key + "\":\"");
        if (i < 0) return null;
        int start = i + key.length() + 4;
        int end = s.indexOf('\"', start);
        return end > start ? s.substring(start, end) : null;
    }

    private static Integer intField(String s, String key) {
        int i = s.indexOf("\"" + key + "\":");
        if (i < 0) return null;
        int start = i + key.length() + 3;
        int end = start;
        while (end < s.length() && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '-')) end++;
        try { return Integer.valueOf(s.substring(start, end)); } catch (NumberFormatException e) { return null; }
    }
}