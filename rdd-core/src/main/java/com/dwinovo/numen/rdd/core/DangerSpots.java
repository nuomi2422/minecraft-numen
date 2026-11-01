package com.dwinovo.numen.rdd.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 危险点（2026-11-01 实机，167 §9.7）：同一个坐标反复死，但现有「重复死亡」提示只覆盖 3 分钟窗口。
 *
 * <p>实机证据：seq37 与 seq39 都死在 15,48,106，相隔 93 分钟——
 * repeat_death_nearby（窗口 180 秒）按设计不会触发，于是「这地方会摔死人」这条事实没人记得。
 *
 * <p>本类只做长期聚类：把同片区域内死过 >=2 次的地点找出来；不判断该不该去。
 */
public final class DangerSpots {

    private DangerSpots() {}

    /** 聚类半径（格）。 */
    public static final double DEFAULT_RADIUS = 8.0;

    public record Spot(String id, int x, int y, int z, int deaths, List<Integer> seqs, long spanSeconds) {}

    public record DeathAt(int seq, int x, int y, int z, long gameTime) {}

    /** 找出死亡次数 >= minDeaths 的地点，按次数降序、坐标升序（稳定）。 */
    public static List<Spot> find(List<DeathAt> deaths, double radius, int minDeaths) {
        if (deaths == null || deaths.isEmpty() || minDeaths < 2) return List.of();
        List<DeathAt> sorted = new ArrayList<>(deaths);
        sorted.sort((a, b) -> Long.compare(a.gameTime(), b.gameTime()));

        List<List<DeathAt>> clusters = new ArrayList<>();
        for (DeathAt d : sorted) {
            List<DeathAt> hit = null;
            for (List<DeathAt> c : clusters) {
                if (dist(c.get(0), d) <= radius) { hit = c; break; }
            }
            if (hit == null) {
                List<DeathAt> created = new ArrayList<>();
                created.add(d);
                clusters.add(created);
            } else {
                hit.add(d);
            }
        }

        List<Spot> out = new ArrayList<>();
        for (List<DeathAt> c : clusters) {
            if (c.size() < minDeaths) continue;
            DeathAt rep = c.get(0);
            List<Integer> seqs = new ArrayList<>();
            for (DeathAt d : c) seqs.add(d.seq());
            long span = (c.get(c.size() - 1).gameTime() - rep.gameTime()) / 20L;
            out.add(new Spot(rep.x() + "," + rep.y() + "," + rep.z(),
                    rep.x(), rep.y(), rep.z(), c.size(), List.copyOf(seqs), span));
        }
        out.sort((a, b) -> a.deaths() != b.deaths()
                ? Integer.compare(b.deaths(), a.deaths())
                : a.id().compareTo(b.id()));
        return List.copyOf(out);
    }

    private static double dist(DeathAt a, DeathAt b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 上下文里的一行；没有危险点返回空串。 */
    public static String describe(List<Spot> spots) {
        if (spots == null || spots.isEmpty()) return "";
        Spot s = spots.get(0);
        return "danger_spot=" + s.id() + " deaths=" + s.deaths() + " seq=" + s.seqs()
                + " span=" + (s.spanSeconds() / 60) + "m";
    }
}