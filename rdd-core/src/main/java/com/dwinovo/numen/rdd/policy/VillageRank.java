package com.dwinovo.numen.rdd.policy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 村庄候选排序（167 §B2）。
 *
 * <p>为什么要有它：村庄观测里已经有村民/箱子/作物/铁傀儡/书架，但**没有任何地方把它们排成候选**，
 * 于是"选哪个村当基地"每次都得模型现场凭印象决定。这里给一个可复算的分数，
 * 让候选顺序稳定、可解释、可测试——模型仍可推翻，但要推翻的是一个**写出来的分数**。
 *
 * <p>打分刻意保守：村民（能交易）> 箱子（有现成物资）> 作物（能种）> 铁傀儡（安全）> 书架（附魔）。
 * 只是排序，不是准入——真正的准入由 {@link HomeGate} 与 settlement 的可用性检查负责。
 */
public final class VillageRank {

    private VillageRank() {}

    /** 一个候选村庄的**事实**快照（都来自 VillageObservation，不新增采集）。 */
    public record Candidate(String id, String dimension, int x, int y, int z,
                            Map<String, Integer> resources, long observedAtMillis) {}

    public static int score(Candidate c) {
        if (c == null) return 0;
        Map<String, Integer> r = c.resources() == null ? Map.of() : c.resources();
        return count(r, "villagers") * 30
                + count(r, "chests") * 10
                + count(r, "crops") / 4
                + count(r, "iron_golem") * 5
                + count(r, "bookshelves") * 2;
    }

    private static int count(Map<String, Integer> m, String k) {
        Integer v = m.get(k);
        return v == null ? 0 : v;
    }

    /** 按分数降序；同分按 id 升序，保证顺序稳定（可复算、可 diff）。 */
    public static List<Candidate> order(List<Candidate> candidates) {
        if (candidates == null) return List.of();
        List<Candidate> out = new ArrayList<>(candidates);
        out.sort(Comparator.comparingInt(VillageRank::score).reversed()
                .thenComparing(c -> c.id() == null ? "" : c.id()));
        return List.copyOf(out);
    }

    /** 第一名（没有候选时 null）。 */
    public static Candidate best(List<Candidate> candidates) {
        List<Candidate> ordered = order(candidates);
        return ordered.isEmpty() ? null : ordered.get(0);
    }
}
