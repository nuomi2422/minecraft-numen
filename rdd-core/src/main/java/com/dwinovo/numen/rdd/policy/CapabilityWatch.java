package com.dwinovo.numen.rdd.policy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「她刚拿到了一件能改变计划的东西」——<b>只产事实，不做决定</b>（2026-10-28）。
 *
 * <h2>主人这一条的原话</h2>
 * 「提前收口这种东西是规划者决定的……如果说我最终的目标就是拿到钻石镐，那我已经拿到钻石镐了，
 * 前面的东西就没有意义了……因为有些东西比较核心：我拿到了钻石镐，我直接可以去挖黑曜石了，
 * 那我挖铁镐就没有意义了。……也有，我挖了铁镐不一定就是挖铁镐：我有钻石镐了，但是我没有铁装备，
 * 那么我可以规划一下，用钻石镐来挖铁，而不是继续去挖到石镐之后再来挖铁，就更加灵活。」
 *
 * <p>→ 所以他说的不是"给核心物品建一张表然后自动跳过"，而是<b>一次轻量的、由现场状态触发的规划机会</b>：
 * 代码负责在"状态真的变了"的那一刻把人叫醒，并把"这是什么、能干什么、可替代什么"摆出来；
 * <b>要不要收口、要不要换道，规划者说了算</b>。
 *
 * <h2>它怎么判"值得叫醒"（全部保守）</h2>
 * <ul>
 *   <li>只认<b>从无到有</b>（{@code before = 0 → now > 0}）：数量增多不算新能力；</li>
 *   <li>语义表里标了 {@code IRRELEVANT} 的<b>不叫</b>（那是她/规划者已经声明"与当前目标无关"的东西）；</li>
 *   <li>没语义条目的，只有 {@link AssetSemantics#guessNature} 判为 {@code EQUIPMENT} 才叫
 *       —— 工具/装备是"能力"本身，这与项目里既有的保守分类是同一套口径；</li>
 *   <li>输出**有界**且稳定排序（重要的在前），绝不把整包东西倒进上下文。</li>
 * </ul>
 *
 * <p>纯 JVM，无 MC 依赖，可单测。
 */
public final class CapabilityWatch {

    /** 一次最多报几件：够规划者看一眼"值不值得现在改计划"，多了就是噪音。 */
    public static final int MAX_UPGRADES = 4;

    /**
     * 一件"新到手的、可能改变计划的东西"。
     *
     * @param assetKey   物品 id
     * @param now        当前持有量
     * @param nature     使用性质（装备/材料/消耗品/任务物品）
     * @param capability 语义表里声明的能力（可空串 —— 没声明就不编）
     * @param purpose    语义表里声明的用途/服务目标（可空串）
     * @param substitutes 语义表里声明的替代条件（空 = 没声明，不代表不能替代）
     */
    public record Upgrade(String assetKey, int now, AssetSemantics.Nature nature,
                          String capability, String purpose, List<String> substitutes) {
        public Upgrade {
            assetKey = assetKey == null ? "" : assetKey;
            capability = capability == null ? "" : capability;
            purpose = purpose == null ? "" : purpose;
            substitutes = substitutes == null ? List.of() : List.copyOf(substitutes);
        }

        /** 一行渲染（事件与提示词共用，保证两处口径一致）。 */
        public String render() {
            StringBuilder sb = new StringBuilder(assetKey).append(" ×").append(now);
            if (!capability.isBlank()) sb.append("（能力：").append(capability).append("）");
            else if (nature == AssetSemantics.Nature.EQUIPMENT) sb.append("（装备）");
            if (!purpose.isBlank()) sb.append("（用途：").append(purpose).append("）");
            if (!substitutes.isEmpty()) sb.append("（可替代：").append(String.join("、", substitutes)).append("）");
            return sb.toString();
        }
    }

    private CapabilityWatch() {}

    /**
     * 找出"这次从无到有、且可能改写计划"的资产。
     *
     * @param before    上一次的真实背包计数（首次观测请传空表 —— 那会把它整包当"新到手"，调用方自己决定要不要叫醒）
     * @param now       本次的真实背包计数
     * @param semantics 语义表（键小写；可为空 = 只有装备兜底）
     * @return 有序、去重、有界的 {@link Upgrade} 列表（可能为空）
     */
    public static List<Upgrade> newlyObtained(Map<String, Integer> before, Map<String, Integer> now,
                                              Map<String, AssetSemantics> semantics) {
        if (now == null || now.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> prev = before == null ? Map.of() : before;
        Map<String, AssetSemantics> sem = semantics == null ? Map.of() : semantics;
        List<Upgrade> out = new ArrayList<>();
        // 排序只影响"谁先被看见"，不影响是否入选：语义表里有条目的优先，其次按 key 稳定排序。
        List<String> keys = new ArrayList<>(now.keySet());
        keys.sort(Comparator.naturalOrder());
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            int nowCount = now.get(key) == null ? 0 : Math.max(0, now.get(key));
            int beforeCount = prev.get(key) == null ? 0 : Math.max(0, prev.get(key));
            if (nowCount <= 0 || beforeCount > 0) {
                continue;   // 只认"从无到有"
            }
            AssetSemantics s = sem.get(key.toLowerCase(java.util.Locale.ROOT));
            if (s != null && s.importance() == AssetSemantics.Importance.IRRELEVANT) {
                continue;   // 已声明与当前目标无关 → 不叫
            }
            AssetSemantics.Nature nature = s == null ? AssetSemantics.guessNature(key) : s.nature();
            boolean declared = s != null;
            if (!declared && nature != AssetSemantics.Nature.EQUIPMENT) {
                continue;   // 没声明、也不是装备/工具 → 不值得打断
            }
            out.add(new Upgrade(key, nowCount, nature,
                    s == null ? "" : s.requiredCapability(),
                    s == null ? "" : s.servesGoal(),
                    s == null ? List.of() : s.substitutes()));
            if (out.size() >= MAX_UPGRADES) {
                break;
            }
        }
        return List.copyOf(out);
    }

    /** 语义表里"重要"的那些键（规划者看"我手上真正要紧的东西"用；只读、保守）。 */
    public static Set<String> importantKeys(Map<String, AssetSemantics> semantics) {
        if (semantics == null || semantics.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, AssetSemantics> e : new LinkedHashMap<>(semantics).entrySet()) {
            if (e.getValue() != null && e.getValue().importance() == AssetSemantics.Importance.IMPORTANT) {
                out.add(e.getKey());
            }
        }
        return Set.copyOf(out);
    }
}
