package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.policy.AssetSemantics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「手上这些能改变计划的东西」上下文块（2026-10-28）。
 *
 * <h2>为什么不是又一张清单</h2>
 * 主人说「他不是这些重要的东西不是都有语义吗」——语义层（{@code AssetSemantics}）早就有：
 * 重要度 / 使用性质 / 能力 / 用途 / 可替代条件。问题是它一直只喂给死亡损失分析器，
 * <b>从没进过她做决定时看得到的地方</b>。于是「我有钻石镐，为什么还要做铁镐」这层推理
 * 只能靠她自己从背包列表里猜。
 *
 * <p>本块只做一件事：把<b>已声明的语义</b>连同数量摆出来（不声明就不编），
 * 并明确一句「要不要因此跳过某一级由你判断」。判断权在规划者，这里不给结论。
 *
 * <h2>口径</h2>
 * <ul>
 *   <li>只列 {@code IMPORTANT} 或性质为 {@code EQUIPMENT} 的持有物；普通材料不进来；</li>
 *   <li>上限 {@value #MAX_ENTRIES} 条 / {@value #MAX_CHARS} 字符，超了截断并标注；</li>
 *   <li>只读缓存（{@code RddPlugin.lastInventory} + 语义表），读不到就整块不出现。</li>
 * </ul>
 */
final class RddCapabilityBrief {

    static final int MAX_ENTRIES = 6;
    static final int MAX_CHARS = 420;

    private RddCapabilityBrief() {}

    static String block(UUID companionId) {
        if (companionId == null) {
            return "";
        }
        try {
            Map<String, Integer> inv = RddPlugin.lastInventory(companionId);
            if (inv == null || inv.isEmpty()) {
                return "";
            }
            Map<String, AssetSemantics> sem = RddSemanticsStore.semantics(companionId);
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, Integer> e : inv.entrySet()) {
                String key = e.getKey();
                int count = e.getValue() == null ? 0 : e.getValue();
                if (key == null || key.isBlank() || count <= 0 || lines.size() >= MAX_ENTRIES) {
                    continue;
                }
                AssetSemantics s = sem.get(key.toLowerCase(java.util.Locale.ROOT));
                AssetSemantics.Nature nature = s == null ? AssetSemantics.guessNature(key) : s.nature();
                boolean important = s != null && s.importance() == AssetSemantics.Importance.IMPORTANT;
                if (!important && nature != AssetSemantics.Nature.EQUIPMENT) {
                    continue;   // 普通材料不进这个块（背包列表里已经有了）
                }
                lines.add(render(key, count, s, nature));
            }
            if (lines.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("<capabilities>手上这些可能改变计划（要不要因此跳过某一步，由你判断）：");
            sb.append(String.join("；", lines));
            sb.append("。若某一步的目的已被现有装备覆盖（钻石镐能挖黑曜石 → 石镐/铁镐那一级没有意义），"
                    + "用 rdd_settle_stage 把那一步标掉并写明理由；若后面的路整体不成立，用 rdd_replan_chain。"
                    + "不划算就照旧继续，什么都不用做。");
            sb.append("</capabilities>");
            String out = sb.toString();
            if (out.length() > MAX_CHARS) {
                out = out.substring(0, MAX_CHARS - 12) + "…</capabilities>";
            }
            return out;
        } catch (RuntimeException ex) {
            return "";   // 增强块，绝不拖崩上下文构建
        }
    }

    private static String render(String key, int count, AssetSemantics s, AssetSemantics.Nature nature) {
        StringBuilder sb = new StringBuilder(key).append(" ×").append(count);
        String cap = s == null ? "" : s.requiredCapability();
        if (!cap.isBlank()) {
            sb.append("（能力：").append(cap).append("）");
        } else if (nature == AssetSemantics.Nature.EQUIPMENT) {
            sb.append("（装备）");
        }
        if (s != null && s.importance() == AssetSemantics.Importance.IMPORTANT) {
            sb.append("［重要］");
        }
        if (s != null && !s.substitutes().isEmpty()) {
            sb.append("（可替代：").append(String.join("、", s.substitutes())).append("）");
        }
        return sb.toString();
    }
}
