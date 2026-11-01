package com.dwinovo.numen.rdd.policy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基地建设的可序列化任务节点（167 §B1）。
 *
 * <p>为什么要有它：settlement 已经有 14×14 地皮与 7×7 模块模板，RDD 也有资产/床/备用事实，
 * 但**没有人把它们排成一条带依赖的施工顺序**——模型每次都得自己现编一遍，
 * 于是"建家"和"进下界"从来不是同一条任务图上的先后关系。
 *
 * <p>本类只做**计划**：稳定 id、依赖、物料、验收条件、失败恢复各就各位；
 * 不碰世界、不建账本、不替 settlement 决定坐标（模板 id 直接指向 settlement 的目录）。
 */
public final class HomeBuildPlan {

    /**
     * 一个施工节点。
     *
     * @param id         稳定 id（跨轮、跨重启不变；用于幂等与"已完成事实"）
     * @param templateId settlement 模板 id（为空表示不是模板施工，而是操作类步骤）
     * @param dependsOn  前置节点 id
     * @param materials  该节点需要的物料（实时扫描后再判定够不够，这里只写需求）
     * @param acceptance 验收条件（谁能证明这一步真的成了）
     * @param onFailure  失败时该做什么（不许"原样重试"）
     */
    public record Node(String id, String templateId, List<String> dependsOn,
                       Map<String, Integer> materials, String acceptance, String onFailure) {}

    private HomeBuildPlan() {}

    /**
     * 标准建家顺序。刻意把"登记"与"验收"拆成两个节点：
     * 167 §B1 要求「登记和验收分开，验收失败返回缺失点」。
     */
    public static List<Node> standard() {
        List<Node> out = new ArrayList<>();
        out.add(new Node("home.platform", "platform_cobble14", List.of(),
                Map.of("minecraft:cobblestone", 196),
                "14x14 顶面可落脚；四边落差 >1 时已修台阶",
                "落差过大就先修台阶，再重放地皮；不要在半空里硬建"));
        out.add(new Node("home.house", "core_house", List.of("home.platform"),
                Map.of("minecraft:oak_planks", 144, "minecraft:oak_door", 1,
                        "minecraft:crafting_table", 1, "minecraft:furnace", 1,
                        "minecraft:chest", 1, "minecraft:white_bed", 1),
                "结构完成且入口可通行",
                "缺料就按缺口去补，不要用土块凑墙"));
        out.add(new Node("home.house.register", "", List.of("home.house"),
                Map.of(), "settlement_register(core_house, HOUSE, protect=true) 有回执",
                "登记失败先看返回的缺失点，别重复登记同 id"));
        out.add(new Node("home.house.verify", "", List.of("home.house.register"),
                Map.of(), "settlement_verify 返回 USABLE",
                "不是 USABLE 就按它指出的缺失点补，别自行判定通过"));
        out.add(new Node("home.bind_bed", "", List.of("home.house.verify"),
                Map.of("minecraft:white_bed", 1), "respawn 已绑定且锚点持久化",
                "床不在手上就先做/拿一张；绑不上要报原始拒绝理由"));
        out.add(new Node("home.reserve_box.register", "", List.of("home.house.verify"),
                Map.of("minecraft:chest", 1), "settlement_register(STORAGE, protect_full=true) 有回执",
                "箱子放不下就换位置，别把备用品塞进临时箱子就宣布完成"));
        out.add(new Node("home.reserve_box.stock", "", List.of("home.reserve_box.register"),
                Map.of("minecraft:iron_pickaxe", 1, "minecraft:cooked_beef", 8, "minecraft:torch", 8),
                "settlement_scan_containers 命中 ReserveKit 最低清单",
                "缺什么补什么；补齐后必须重扫，不能沿用旧读数"));
        out.add(new Node("home.lit", "", List.of("home.reserve_box.stock"),
                Map.of("minecraft:torch", 8), "核心结构周边光照判据通过、保护生效",
                "先补火把再谈保护；保护接线未完成时如实报 UNKNOWN"));
        out.add(new Node("home.farm", "farm_basic", List.of("home.lit"),
                Map.of("minecraft:dirt", 97),
                "结构完成**且**已灌水",
                "STRUCTURE_ONLY 不能升成 READY；没水就先取水"));
        out.add(new Node("home.farm.verify", "", List.of("home.farm"),
                Map.of(), "settlement_verify 通过且观察到真实产出",
                "只看结构不算通过；没有产出就报告生产缺口"));
        return List.copyOf(out);
    }

    /** 拓扑序检查：返回第一个"依赖不在前面"的节点 id（无则 null）。 */
    public static String firstDependencyViolation(List<Node> nodes) {
        if (nodes == null) return null;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Node n : nodes) {
            for (String dep : n.dependsOn()) {
                if (!seen.contains(dep)) return n.id() + " -> " + dep;
            }
            seen.add(n.id());
        }
        return null;
    }

    /** 便于事件/日志的一行摘要。 */
    public static String summary(List<Node> nodes) {
        StringBuilder sb = new StringBuilder();
        for (Node n : nodes) {
            if (sb.length() > 0) sb.append(" > ");
            sb.append(n.id());
        }
        return sb.toString();
    }

    /** 按 id 索引（幂等执行时用）。 */
    public static Map<String, Node> byId(List<Node> nodes) {
        Map<String, Node> m = new LinkedHashMap<>();
        for (Node n : nodes) m.put(n.id(), n);
        return Map.copyOf(m);
    }
}
