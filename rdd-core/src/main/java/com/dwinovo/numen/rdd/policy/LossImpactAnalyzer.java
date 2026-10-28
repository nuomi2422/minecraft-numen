package com.dwinovo.numen.rdd.policy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 损失影响分析（资产语义 V1）：<b>核心不是"丢了重要物品就重规划"，而是"确认损失后，还能不能完成目标"</b>。
 *
 * <p>替代原先"重要丢一个就判、次要丢多个才判"的评分式决策：<b>重要等级决定值不值得关注，
 * 剩余能力决定需不需要行动</b>。本类只产<b>事实与建议</b>，不碰任务链、不自动改目标——
 * 是否插入补充支线/调整主线，留给上层（先分析可靠，再谈接入）。
 *
 * <h2>两条边界（不许违反）</h2>
 * <ol>
 *   <li>传入的 {@code lostItems} 是<b>净损失</b>（已确认仍缺失的量），不是死亡瞬间快照：
 *       掉落可能还在、已被捡回、或被转存——捡回了就不该报永久损失。</li>
 *   <li>{@code lossConfirmed=false} 表示"尚未确认丢失"（回收未结束 / 区块未加载 / 位置未检查）——
 *       此时不下"永久丢失"的结论，只建议先确认库存。</li>
 * </ol>
 *
 * <p>普通吃食物、用材料建造、物品放进箱子都不是损失——调用方不该把它们放进 {@code lostItems}。
 *
 * <p>纯函数、可单测，不含 Minecraft 类型。
 */
public final class LossImpactAnalyzer {

    public enum Recommendation {
        /** 无需动作（无关资产 / 已被捡回 / 正常消耗）。 */
        NONE,
        /** 换装继续（重要资产丢了，但需求仍被备用/替代满足）。 */
        SWAP_BACKUP,
        /** 先确认库存（次要受影响，或尚未确认丢失）。 */
        CONFIRM_STOCK,
        /** 补充（重要能力缺口，需求已不满足）。 */
        SUPPLEMENT
    }

    /**
     * 一处受损失影响的目标需求。
     *
     * @param available 损失后**随身**剩多少（不含容器；容器在 {@code backup}）
     * @param backup    损失后**已知备用**（基地容器观测）里有多少；0 表示"没观测到"，
     *                  <b>不表示"确定没有"</b>——所以 0 不能用来证明"备用不足"，
     *                  只能用来拒绝"备用足够"（P0-A 的诚实方向：宁可多问一句）
     */
    public record GoalImpact(String goalId, String requirementKey, int minimum, int available,
                             boolean satisfied, List<String> coveredBy, int backup) {
        public GoalImpact {
            coveredBy = coveredBy == null ? List.of() : List.copyOf(coveredBy);
        }

        /** 兼容旧调用（不携带备用读数）。 */
        public GoalImpact(String goalId, String requirementKey, int minimum, int available,
                          boolean satisfied, List<String> coveredBy) {
            this(goalId, requirementKey, minimum, available, satisfied, coveredBy, 0);
        }
    }

    /**
     * 分析结论。
     *
     * <p><b>2026-10-25（81 号 §4.1）新增两字段</b>：{@code inventoryKnown} 与 {@code reason}。
     * 没有它们，"背包读不到"会被当成"背包是空的"，而空背包又是 SWAP_BACKUP 的合法输入 ——
     * 于是一次观测失败就能得出"备用足够，不用补"。{@code inventoryKnown=false} 时
     * 一律不下 SWAP_BACKUP 结论，只报"先确认库存"。
     */
    public record LossImpact(boolean importantLoss, boolean backupSufficient, boolean lossConfirmed,
                             boolean inventoryKnown,
                             List<GoalImpact> affectedGoals, Recommendation recommendation,
                             String summary, String reason) {
        public LossImpact {
            affectedGoals = affectedGoals == null ? List.of() : List.copyOf(affectedGoals);
            reason = reason == null ? "" : reason;
        }

        /** 兼容旧调用：默认"库存可读"，避免历史调用点语义被悄悄改写。 */
        public LossImpact(boolean importantLoss, boolean backupSufficient, boolean lossConfirmed,
                          List<GoalImpact> affectedGoals, Recommendation recommendation, String summary) {
            this(importantLoss, backupSufficient, lossConfirmed, true, affectedGoals, recommendation, summary, "");
        }

        public boolean needsAction() {
            return recommendation == Recommendation.SUPPLEMENT;
        }
    }

    private LossImpactAnalyzer() {
    }

    /** 旧签名：库存可读、无备用读数——历史调用点的语义原样保留。 */
    public static LossImpact analyze(Map<String, Integer> lostItems,
                                     Map<String, Integer> heldAfterLoss,
                                     List<RequirementManifest.Manifest> manifests,
                                     Map<String, AssetSemantics> semantics,
                                     boolean lossConfirmed) {
        return analyze(lostItems, heldAfterLoss, Map.of(), manifests, semantics, lossConfirmed, true);
    }

    /**
     * 完整口径：随身 / 已知备用 / 库存是否可读，三件事分开。
     *
     * <h2>为什么要拆成三个输入（2026-10-25，81 号 §4.1）</h2>
     * <ol>
     *   <li><b>随身（held）</b>只放"这个人身上真有几件"。容器里的备用不并进来 ——
     *       并进来就会把"家里有"当"身上有"，而任务判据读的是身上。</li>
     *   <li><b>备用（backup）</b>单独一路：只有在基地容器被真实观测过、且条目还在新鲜期内，
     *       调用方才递进来。它是"可以换装继续"的证据，不是"已经拿到手"的事实。</li>
     *   <li><b>库存可读（inventoryKnown）</b>：读不到 ≠ 空。死亡瞬间背包必然清空是常识，
     *       但"清空"和"没读到"在类型上必须分开，否则一次读取失败就能推出"备用足够"。</li>
     * </ol>
     *
     * <p><b>一条硬规矩</b>：把需求打穿（原本满足、损失后不满足）<b>且</b>备用也补不上，
     * 才叫 {@code importantUnsatisfied}。备用补得上时，它是"受影响但可换装继续"——
     * 这正好是用户要的「有备用装备就不用再做一遍」。
     *
     * <p><b>另一条硬规矩</b>：这次丢的键如果一条需求都对不上（换过目标 / 清单为空），
     * 我们不知道损失压在哪，<b>不能</b>用"没有受影响项"推出"备用足够"——
     * 那种情况给 {@code CONFIRM_STOCK} 并写明原因。
     */
    public static LossImpact analyze(Map<String, Integer> lostItems,
                                     Map<String, Integer> heldAfterLoss,
                                     Map<String, Integer> backupAvailable,
                                     List<RequirementManifest.Manifest> manifests,
                                     Map<String, AssetSemantics> semantics,
                                     boolean lossConfirmed,
                                     boolean inventoryKnown) {
        Map<String, Integer> lost = clean(lostItems);
        Map<String, Integer> held = clean(heldAfterLoss);
        Map<String, Integer> backup = clean(backupAvailable);
        // 损失前 ≈ 现在持有 + 净损失（净损失是已确认不再持有的部分）。
        Map<String, Integer> before = new LinkedHashMap<>(held);
        for (Map.Entry<String, Integer> e : lost.entrySet()) {
            before.merge(e.getKey(), e.getValue(), Integer::sum);
        }

        boolean importantLoss = false;
        for (String key : lost.keySet()) {
            if (importanceOf(key, semantics) == AssetSemantics.Importance.IMPORTANT) {
                importantLoss = true;
            }
        }

        List<GoalImpact> affected = new ArrayList<>();
        boolean importantUnsatisfied = false;
        // 库存不可读时不算"打穿"：空表是未知，不是零（IC04 同款语义）。
        if (inventoryKnown && manifests != null) {
            for (RequirementManifest.Manifest manifest : manifests) {
                if (manifest == null) continue;
                for (RequirementManifest.Requirement r : manifest.requirements()) {
                    int beforeAvail = available(r, before);
                    int afterAvail = available(r, held);
                    int backupAvail = available(r, backup);
                    // 只有"这次损失把原本满足的需求打穿"才算受影响（原本就缺的缺口不算）。
                    if (!(beforeAvail >= r.minimum() && afterAvail < r.minimum())) continue;
                    boolean important = importanceOf(r.key(), semantics) == AssetSemantics.Importance.IMPORTANT;
                    if (important) {
                        importantLoss = true;
                        // 备用也补不上 → 真的缺；补得上 → 只是"受影响，可换装继续"。
                        if (backupAvail < r.minimum()) importantUnsatisfied = true;
                    }
                    affected.add(new GoalImpact(manifest.goalId(), r.key(), r.minimum(), afterAvail,
                            false, coveredBy(r, held), backupAvail));
                }
            }
        }

        // 清单证据：这次丢的键至少对得上一条需求（键或替代）。
        boolean requirementEvidence = false;
        if (manifests != null) {
            for (RequirementManifest.Manifest manifest : manifests) {
                if (manifest == null) continue;
                for (RequirementManifest.Requirement r : manifest.requirements()) {
                    for (String key : lost.keySet()) {
                        if (key.equals(r.key()) || r.alternatives().contains(key)) {
                            requirementEvidence = true;
                        }
                    }
                }
            }
        }

        // 库存不可读时永远不宣称"备用足够"：那是用"没看见缺口"冒充"没有缺口"。
        boolean backupSufficient = inventoryKnown && importantLoss && !importantUnsatisfied && requirementEvidence;
        Recommendation recommendation = decide(affected, importantLoss, importantUnsatisfied,
                lossConfirmed, inventoryKnown, requirementEvidence);
        String reason = reasonFor(recommendation, inventoryKnown, requirementEvidence, affected);
        String summary = summarize(lost, affected, importantLoss, importantUnsatisfied, lossConfirmed,
                inventoryKnown, recommendation);
        return new LossImpact(importantLoss, backupSufficient, lossConfirmed, inventoryKnown,
                affected, recommendation, summary, reason);
    }

    private static Recommendation decide(List<GoalImpact> affected, boolean importantLoss,
                                         boolean importantUnsatisfied, boolean lossConfirmed,
                                         boolean inventoryKnown, boolean requirementEvidence) {
        if (!inventoryKnown) {
            // 读不到库存：既不能说"够"，也不能说"不够"——先确认，别用观测失败当结论。
            return Recommendation.CONFIRM_STOCK;
        }
        if (!importantLoss && affected.isEmpty()) {
            return Recommendation.NONE;                 // 无关 / 已捡回 / 正常消耗
        }
        if (importantUnsatisfied) {
            return lossConfirmed ? Recommendation.SUPPLEMENT : Recommendation.CONFIRM_STOCK;
        }
        if (importantLoss) {
            // "重要东西丢了 + 需求没被打穿"只有在**能把这次损失对到某条需求上**时
            // 才算"备用/替代仍够"。对不上（换过目标/清单缺失）→ 只敢说"先确认"。
            return requirementEvidence ? Recommendation.SWAP_BACKUP : Recommendation.CONFIRM_STOCK;
        }
        return Recommendation.CONFIRM_STOCK;            // 只有次要受影响：按批次复核
    }

    private static String reasonFor(Recommendation rec, boolean inventoryKnown,
                                    boolean requirementEvidence, List<GoalImpact> affected) {
        if (!inventoryKnown) return "inventory_unreadable";
        return switch (rec) {
            case NONE -> "";
            case SUPPLEMENT -> "important_requirement_unsatisfied_after_confirmed_loss";
            case SWAP_BACKUP -> "backup_or_alternative_covers_requirement";
            case CONFIRM_STOCK -> requirementEvidence
                    ? "loss_not_confirmed_or_secondary"
                    : "no_requirement_evidence_for_this_loss";
        };
    }

    private static int available(RequirementManifest.Requirement r, Map<String, Integer> counts) {
        int best = AssetDerivation.equivalentCount(r.key(), counts);
        for (String alt : r.alternatives()) {
            best = Math.max(best, AssetDerivation.equivalentCount(alt, counts));
        }
        return best;
    }

    private static List<String> coveredBy(RequirementManifest.Requirement r, Map<String, Integer> counts) {
        List<String> out = new ArrayList<>();
        if (AssetDerivation.equivalentCount(r.key(), counts) > 0) out.add(r.key());
        for (String alt : r.alternatives()) {
            if (AssetDerivation.equivalentCount(alt, counts) > 0) out.add(alt);
        }
        return out;
    }

    private static AssetSemantics.Importance importanceOf(String assetKey, Map<String, AssetSemantics> semantics) {
        if (assetKey == null) return AssetSemantics.Importance.SECONDARY;
        AssetSemantics s = semantics == null ? null : semantics.get(assetKey);
        if (s == null && semantics != null) s = semantics.get(assetKey.toLowerCase(Locale.ROOT));
        if (s != null) return s.importance();
        // ★ 2026-10-28 实机（#245）：语义表里**没有条目**就一律 SECONDARY —— 结果她丢了整套钻石甲，
        //   分析器报 importantLoss=false / recommendation=NONE / affectedGoals=0，看起来"没什么大事"。
        //   语义表本来只覆盖"规划时提到过的那几件"（当时只有 obsidian/diamond_pickaxe/bucket 三条），
        //   所以**没有条目 ≠ 不重要**。这里补按品级的兜底（只用于"没有语义"的资产）：
        //   diamond_/netherite_ → IMPORTANT；iron_/chainmail_ → SECONDARY；其余 SECONDARY。
        String k = assetKey.toLowerCase(Locale.ROOT);
        if (k.startsWith("netherite_") || k.startsWith("diamond_")) {
            return AssetSemantics.Importance.IMPORTANT;
        }
        return AssetSemantics.Importance.SECONDARY;
    }

    private static Map<String, Integer> clean(Map<String, Integer> counts) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (counts != null) {
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && e.getValue() > 0) {
                    out.put(e.getKey(), e.getValue());
                }
            }
        }
        return out;
    }

    private static String summarize(Map<String, Integer> lost, List<GoalImpact> affected,
                                    boolean importantLoss, boolean importantUnsatisfied,
                                    boolean lossConfirmed, boolean inventoryKnown,
                                    Recommendation recommendation) {
        StringBuilder sb = new StringBuilder();
        sb.append("损失：").append(lost.isEmpty() ? "无" : lost.toString());
        if (!inventoryKnown) {
            sb.append("；库存未确认（读不到不等于没有）——先确认背包再决定补不补，不要按\"0 件\"处理");
        }
        switch (recommendation) {
            case NONE -> sb.append("；无重要影响，无需动作");
            case SWAP_BACKUP -> sb.append("；重要资产缺失但需求仍有备用/替代满足，换装继续");
            case CONFIRM_STOCK -> sb.append(lossConfirmed
                    ? "；次要资产受影响，按批次复核即可"
                    : "；尚未确认丢失（回收未结束/区块未加载），先确认库存再决定");
            case SUPPLEMENT -> sb.append("；重要能力缺口，需要补充");
        }
        if (!affected.isEmpty()) {
            sb.append("；受影响目标=");
            for (int i = 0; i < affected.size(); i++) {
                GoalImpact g = affected.get(i);
                if (i > 0) sb.append(',');
                sb.append(g.goalId()).append(':').append(g.requirementKey())
                        .append("(需").append(g.minimum()).append("剩").append(g.available());
                if (g.backup() > 0) sb.append(" 备用").append(g.backup());
                sb.append(')');
            }
        }
        if (importantLoss && importantUnsatisfied && !lossConfirmed) {
            sb.append("；注意：即使目标已被阻塞，也请说明是'暂不可用、尚未确认丢失'");
        }
        return sb.toString();
    }
}
