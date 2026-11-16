package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.fact.CompletedFactStore;
import com.dwinovo.numen.rdd.fact.FactSnapshot;
import com.dwinovo.numen.rdd.fact.StageFact;
import com.dwinovo.numen.rdd.policy.RequirementManifest;
import com.dwinovo.numen.rdd.policy.RequirementView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 需求视图的纯文本投影（2026-11-17 从 RddFactContext 内聚拆出）。
 *
 * <p>只吃调用方传入的已投影值（Need / Manifest / FactSnapshot / 事实库），不读世界、不持有状态、
 * 不决定策略；UNKNOWN 持有、已满足但已消耗、sticky、转化备注、声明序与 3/6/8/5 上限都按原语义渲染。
 * 状态与生命周期仍归 RddFactContext。
 */
final class RddNeedsRender {

    /** 监测事件里最多列几条缺口。 */
    static final int MAX_EVENT_GAPS = 6;
    /** 执行 AI 上下文里最多列几条未凑齐需求。 */
    static final int MAX_EXECUTOR_GAPS = 3;
    /** 重规划事实块最多列几条需求（按关注度排序取前 N，其余只报条数）。 */
    static final int MAX_REPLAN_ROWS = 8;
    /** 重规划事实块最多列几条近期阶段事实。 */
    static final int MAX_RECENT_FACTS = 5;

    private RddNeedsRender() {}

    static int have(RddFactContext.Need need, Map<String, Integer> held) {
        return RddFactContext.have(need, held);
    }
    /** 事件里的缺口短文本（有界；只在事件帧用，不用于逐轮上下文）。 */
    static String unmetText(List<RddFactContext.Need> needs, Map<String, Integer> held) {
        return unmetText(needs, held, Map.of());
    }

    /**
     * 带转化归因的缺口文本（G1c-3）。
     *
     * <p>{@code transformNotes}：资源键 → 「近期被转化成了什么」（如 {@code log→planks×8}）。
     * 缺口行追加「（近期转化：…）」——把「数量减少」与「东西没了」分开（75号 §3.2）。
     */
    static String unmetText(List<RddFactContext.Need> needs, Map<String, Integer> held,
                            Map<String, String> transformNotes) {
        Map<String, String> notes = transformNotes == null ? Map.of() : transformNotes;
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (RddFactContext.Need need : needs) {
            int now = have(need, held);
            if (now >= need.minimum()) {
                continue;
            }
            if (n == MAX_EVENT_GAPS) {
                sb.append("…");
                break;
            }
            if (n > 0) {
                sb.append("；");
            }
            sb.append(need.display()).append(" 需").append(need.minimum()).append(" 有").append(now);
            String note = notes.get(need.display());
            if (note != null && !note.isBlank()) {
                sb.append("（近期转化 ").append(note).append("）");
            }
            n++;
        }
        return sb.toString();
    }

    /** 执行 AI 上下文块：只列未凑齐的需求（≤3 条），全凑齐就整块不出现。
     *
     * <p>★ G1b：CRITICAL 需求带 {@code [关键]} 前缀（到达模型请求正文 = 消费者接通的判据，
     * S44）。<b>排序不变</b>——仍按声明序，不按重要度排：排序归检索层（75号 §4.1），
     * 执行侧上下文块只做可见性，不做调度裁决（D09 执行局部应变自由）。
     *
     * <p>★ G1c：{@code reservedByKey} 非空时给对应行追加「（已预留 N）」——让执行 AI
     * 看见「这批里有多少件已经被许给别的需求」，而不是把全部现有量都当自己可用。
     */
    static String stickyKey(String primaryId, int planRevision, int attempt, RddFactContext.Need need) {
        return primaryId + "|r" + planRevision + "|a" + attempt + "|" + need.display();
    }

    static String displayKey(RddFactContext.Need need) { return need.display(); }

    /** Sticky keys that are recorded satisfied but the inventory is now below minimum (debug only). */
    static java.util.List<String> satisfiedButSpent(List<RddFactContext.Need> needs, Map<String, Integer> held,
                                                    Set<String> satisfiedKeys) {
        if (satisfiedKeys == null || satisfiedKeys.isEmpty()) return List.of();
        java.util.List<String> out = new ArrayList<>();
        for (RddFactContext.Need need : needs) {
            if (satisfiedKeys.contains(displayKey(need)) && have(need, held) < need.minimum()) {
                out.add(displayKey(need));
            }
        }
        return out;
    }

    static String renderNeedsBlock(List<RddFactContext.Need> needs, Map<String, Integer> held) {
        return renderNeedsBlock(needs, held, Map.of(), Map.of());
    }

    static String renderNeedsBlock(List<RddFactContext.Need> needs, Map<String, Integer> held,
                                   Map<String, Integer> reservedByKey) {
        return renderNeedsBlock(needs, held, reservedByKey, Map.of());
    }

    /**
     * G1c-3：带「近期转化」归因的渲染。{@code transformNotes} = 资源键 → 转化说明
     * （如 {@code log→planks×8}）；只给命中的未满足行追加，不改变任何数量判定。
     */
    static String renderNeedsBlock(List<RddFactContext.Need> needs, Map<String, Integer> held,
                                   Map<String, Integer> reservedByKey,
                                   Map<String, String> transformNotes) {
        return renderNeedsBlock(needs, held, reservedByKey, transformNotes, Set.of());
    }

    static String renderNeedsBlock(List<RddFactContext.Need> needs, Map<String, Integer> held,
                                   Map<String, Integer> reservedByKey,
                                   Map<String, String> transformNotes,
                                   Set<String> satisfiedKeys) {
        List<RddFactContext.Need> unmet = new ArrayList<>();
        for (RddFactContext.Need need : needs) {
            if (have(need, held) < need.minimum()
                    && (satisfiedKeys == null || !satisfiedKeys.contains(displayKey(need)))) {
                unmet.add(need);
            }
        }
        if (unmet.isEmpty()) {
            return "";
        }
        Map<String, Integer> reserved = reservedByKey == null ? Map.of() : reservedByKey;
        Map<String, String> notes = transformNotes == null ? Map.of() : transformNotes;
        StringBuilder sb = new StringBuilder("<needs>一级目标需求还差：");
        int n = 0;
        for (RddFactContext.Need need : unmet) {
            if (n == MAX_EXECUTOR_GAPS) {
                sb.append("…");
                break;
            }
            if (n > 0) {
                sb.append("；");
            }
            sb.append(need.importancePrefix()).append(need.display()).append(" ×").append(need.minimum())
                    .append("（现有 ").append(have(need, held)).append("）");
            int r = reserved.getOrDefault(need.display(), 0);
            if (r > 0) {
                sb.append("（已预留 ").append(r).append("）");
            }
            String note = notes.get(need.display());
            if (note != null && !note.isBlank()) {
                sb.append("（近期转化 ").append(note).append("）");
            }
            n++;
        }
        sb.append("。凑齐即消失；这只有数量，不是指令。</needs>");
        return sb.toString();
    }

    /**
     * 重规划事实块：需求 × 持有对账（{@link RequirementView}）+ 近期阶段事实。
     *
     * <p>只读陈述：缺口数字 ≠「没做」；「持有未知」= 没扫过登记表，也不代表 0。
     * 无需求清单时返回空串（不加噪音）。
     */
    static String renderReplanBlock(RequirementManifest.Manifest manifest, CompletedFactStore facts,
                                    AssetRegistry assets, FactSnapshot snapshot, long now) {
        if (manifest == null || manifest.requirements().isEmpty()) {
            return "";
        }
        RequirementView.View view = RequirementView.build(manifest, facts, assets, null);
        if (view.rows().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【需求 × 持有对账（只读；共同事实快照 ").append(ageText(now, snapshot.capturedAt()))
                .append("采样，维度 ").append(snapshot.dimensionId()).append("）】\n");
        int n = 0;
        for (RequirementView.Row row : view.rows()) {
            if (n == MAX_REPLAN_ROWS) {
                sb.append("- ……其余 ").append(view.rows().size() - MAX_REPLAN_ROWS).append(" 条略（关注项已在前）\n");
                break;
            }
            n++;
            // G1b：CRITICAL 需求行带 [关键] 前缀（与 executorBlock 的 needs 块同文案口径）；
            // 排序仍按 attention（可疑度），不按重要度——本块是只读对账，不是调度裁决。
            String impPrefix = row.importance() == com.dwinovo.numen.rdd.policy.RequirementManifest.Importance.CRITICAL
                    ? "[关键] " : "";
            sb.append("- ").append(impPrefix).append(row.requirementKey()).append(" 需要 ").append(row.minimum());
            if (row.held() == null) {
                sb.append("，持有未知（登记表没这个键，不等于 0）");
            } else {
                sb.append("，现有 ").append(row.held());
            }
            if (row.heldGap() > 0) {
                sb.append(" → 缺口 ").append(row.heldGap());
            }
            if (row.factCount() > 0) {
                sb.append("｜事实记录 ").append(row.factCount()).append(" 条");
            }
            // ★ 持有未知时**不能**再渲染 attention 文案：RequirementView 对 UNKNOWN_HELD
            //   刻意给 NONE（"判断不了 ≠ 不用看"），而 attentionText(NONE) 是"持有已达标" ——
            //   实机抓到的错标：同一行同时写「持有未知」与「持有已达标」。
            //   未知行只保留上面那句"持有未知（登记表没这个键，不等于 0）"。
            if (row.held() != null) {
                String attention = attentionText(row.attention());
                if (!attention.isEmpty()) {
                    sb.append("｜").append(attention);
                }
            }
            sb.append('\n');
        }
        String recent = renderRecentFacts(facts, manifest.goalId(), now);
        if (!recent.isEmpty()) {
            sb.append("近期已完成的阶段（共同事实）：\n").append(recent);
        }
        if (view.notes().stream().anyMatch(t -> t != null && t.startsWith("REGISTRY_EMPTY"))) {
            sb.append("（注：资产登记表为空 = 还没扫过背包；上列「持有未知」不代表 0）\n");
        }
        sb.append("（只读陈述：缺口数字 ≠「没做」；对账列不改变任何一侧。）");
        return sb.toString();
    }

    private static String attentionText(RequirementView.Attention attention) {
        return switch (attention) {
            case DONE_BUT_NOT_HELD -> "关注：事实有完成记录但持有不足（疑似消耗/采集口径）";
            case NOT_DONE_AND_NOT_HELD -> "持有不足";
            case HELD_BUT_NO_FACT -> "持有已达标";
            case NONE -> "持有已达标";
        };
    }

    /** 近期阶段事实（按 manifest.goalId = 真 Goal.id 过滤，时间倒序，≤5 条）。 */
    private static String renderRecentFacts(CompletedFactStore facts, String goalId, long now) {
        if (facts == null || goalId == null || goalId.isBlank()) {
            return "";
        }
        List<StageFact> matched = new ArrayList<>();
        for (StageFact f : facts.stageFacts()) {
            if (f != null && goalId.equals(f.goalId()) && f.rawStage() != null && !f.rawStage().isBlank()) {
                matched.add(f);
            }
        }
        if (matched.isEmpty()) {
            return "";
        }
        matched.sort(Comparator.comparingLong(StageFact::completedAtMillis).reversed());
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (StageFact f : matched) {
            if (n == MAX_RECENT_FACTS) {
                sb.append("- ……\n");
                break;
            }
            sb.append("- 《").append(f.rawStage()).append("》 ").append(ageText(now, f.completedAtMillis())).append('\n');
            n++;
        }
        return sb.toString();
    }

    private static String ageText(long now, long at) {
        long ms = Math.max(0L, now - at);
        if (ms < 60_000L) {
            return "刚刚";
        }
        if (ms < 60L * 60_000L) {
            return (ms / 60_000L) + " 分钟前";
        }
        return (ms / (60L * 60_000L)) + " 小时前";
    }
}
