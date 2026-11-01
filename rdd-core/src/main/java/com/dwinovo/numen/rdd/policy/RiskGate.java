package com.dwinovo.numen.rdd.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 风险门（P2.2，纯函数）：进入高风险活动前，用**代码硬规则**判“允许 / 阻止 + 缺什么 + 建议准备任务”。
 *
 * <p>不是规划器：只回答允许与否与缺口。规划/追加任务由宿主据本裁决执行——把“不能违反的生存规则”
 * 落在代码里，不靠 LLM 记规则。（GPT/主人对齐：RiskGate + Requirement 系统一起推进。）
 *
 * <p>依赖 {@link ResourceBudget}（最低储备）与真实可用资产计数（通常来自
 * {@code PlanningAssetSnapshot.availableCounts()}）。纯 JVM、可单测。
 */
public final class RiskGate {

    /**
     * @param allowed   是否允许进入
     * @param level     判定的风险级别
     * @param missing   "key need X have Y" 缺口列表（空=达标）
     * @param prepTasks 建议的准备任务描述（缺口的人类可读转写）
     */
    public record Verdict(boolean allowed, RiskLevel level, List<String> missing, List<String> prepTasks) {}

    private RiskGate() {}

    /** 按风险级别检查最低储备。 */
    public static Verdict check(RiskLevel level, Map<String, Integer> available) {
        RiskLevel lv = level == null ? RiskLevel.NORMAL : level;
        List<String> missing = ResourceBudget.missingFor(lv, available);
        if (missing.isEmpty()) {
            return new Verdict(true, lv, List.of(), List.of());
        }
        // A/166 §69：首次下界放行 —— 只放过抗火药这一项，其余一项都不放松。
        if (lv == RiskLevel.NETHER && onlyPotionMissing(missing)) {
            return new Verdict(true, lv, missing, FIRST_ENTRY_ADVICE);
        }
        return new Verdict(false, lv, missing, prepare(lv, missing));
    }

    /** 缺的项里只有抗火药（其余全齐）。缺任何别的都不算。 */
    static boolean onlyPotionMissing(List<String> missing) {
        if (missing == null || missing.isEmpty()) {
            return false;
        }
        for (String m : missing) {
            if (m == null || !m.startsWith("minecraft:fire_resistance_potion")) {
                return false;
            }
        }
        return true;
    }

    /**
     * 首次下界（无抗火药）时必须照做的一句话。
     *
     * <p>放行是为了破环（抗火药=下界材料，见 §69），不是为了让她在下界长待；
     * 没有这句话，放行就变成随便进。
     */
    private static final List<String> FIRST_ENTRY_ADVICE = List.of(
            "首次下界（背包没有抗火药）：进门后立刻返回主世界，不要在下界久留、不要主动打烈焰人；本次只确认传送门可通行并记住出口坐标。");

    /** 由维度 ID 判定风险级别（如 minecraft:the_nether → NETHER）。 */
    public static RiskLevel levelForDimension(String dimensionId) {
        if (dimensionId == null) {
            return RiskLevel.NORMAL;
        }
        String d = dimensionId.toLowerCase();
        if (d.contains("the_nether") || d.contains("nether")) {
            return RiskLevel.NETHER;
        }
        if (d.contains("the_end") || d.endsWith(":end")) {
            return RiskLevel.END;
        }
        return RiskLevel.NORMAL;
    }

    /**
     * 由阶段主题/文本判定风险级别。
     *
     * <p><b>P2-A 收紧</b>：不再「文本包含某词即高危」——早期据点阶段曾因描述里**提到**高风险词
     * 被误判成 NETHER，`injectWaitFor` 于是给它挂了钻石甲/抗火药硬门 → 永久 WAITING 死锁。
     * 现在只在**明确的行动短语**上判高危（如「进入下界」「击杀末影龙」「下矿采集」），
     * 或**准备类短语**（如「下界准备/备抗火」）——那本就是该备装的阶段。认不出=NORMAL（保守）。
     */
    public static RiskLevel levelForText(String text) {
        if (text == null || text.isBlank()) {
            return RiskLevel.NORMAL;
        }
        String t = text.toLowerCase();
        if (actionFor(t, "末地", "末影龙", "the_end")) {
            return RiskLevel.END;
        }
        if (actionFor(t, "下界", "地狱", "nether")) {
            return RiskLevel.NETHER;
        }
        if (actionFor(t, "下矿", "挖矿", "洞穴", "废弃矿井")) {
            return RiskLevel.MINING;
        }
        return RiskLevel.NORMAL;
    }

    /**
     * 是否出现"行动/准备"短语：目标词出现在**明确的多字行动词**附近（同一短窗口内）。
     *
     * <p><b>2026-09-25 二次收紧</b>：动词表**不得**含单字/歧义词——`备`（备用/设备/背包）、
     * `去`（过去/上去）、`打`（打听/打扮）会把"……备用装备的回退路线"误判成下界行动。
     * 只保留语义明确的多字动词。
     */
    private static boolean actionFor(String text, String... targets) {
        String[] verbs = {"进入", "前往", "击杀", "采集", "探索", "攻略", "挑战", "进军",
                "下矿", "挖矿", "筹备", "准备进入", "预备"};
        for (String target : targets) {
            int idx = text.indexOf(target);
            while (idx >= 0) {
                // 否定句守卫（B3/166）：目标词前一小段出现否定词时，这一处不算「要去」。
                // 依据 2026-09-28 实机回归：「也绝不进入下界」这类**禁止项**曾被判成 NETHER，
                // 于是给"原地待命"挂上下界硬门，任务永远派不下去 —— 风险门当时因此被整个关掉。
                // 现在只跳过被否定的那一处命中，同一文本里其它真正的行动短语仍然照常判定。
                if (!negatedBefore(text, idx)) {
                    int from = Math.max(0, idx - 4);
                    int to = Math.min(text.length(), idx + target.length() + 4);
                    String window = text.substring(from, to);
                    for (String v : verbs) {
                        if (window.contains(v)) return true;
                    }
                }
                idx = text.indexOf(target, idx + 1);
            }
        }
        return false;
    }

    /** 目标词前 8 个字符内是否出现否定词（不做句式理解，只防最明确的禁止项误判）。 */
    private static boolean negatedBefore(String text, int targetStart) {
        int from = Math.max(0, targetStart - 8);
        String before = text.substring(from, targetStart);
        for (String neg : NEGATIONS) {
            if (before.contains(neg)) return true;
        }
        return false;
    }

    /** 明确的否定/禁止措辞。刻意只收多字或语义无歧义的单字，避免把「不错」「差不多」当成否定。 */
    private static final String[] NEGATIONS = {
            "不要", "不用", "不必", "无需", "禁止", "绝不", "不得", "不能", "不可",
            "别", "勿", "莫", "严禁", "避免", "暂不", "先不"};

    /**
     * 把缺口转成准备任务描述。核心策略：缺 N 件 → “获取/制作至 N”；抗火/治疗归“酿造”；
     * 下界额外提醒第二套甲与临时据点。只给方向，具体拆解仍交 Planner。
     */
    private static List<String> prepare(RiskLevel level, List<String> missing) {
        List<String> tasks = new ArrayList<>();
        for (String m : missing) {
            String key = m.split(" ")[0];
            String shortName = key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
            if (shortName.contains("potion")) {
                tasks.add("酿造 " + shortName + "（缺：" + m + "）");
            } else if (shortName.startsWith("diamond_")) {
                tasks.add("制作/获取钻石装备 " + shortName + "（缺：" + m + "，下界需两套）");
            } else {
                tasks.add("获取 " + shortName + "（缺：" + m + "）");
            }
        }
        if (level == RiskLevel.NETHER) {
            tasks.add("下界前：确认临时据点/回退路线与备用装备");
        } else if (level == RiskLevel.END) {
            tasks.add("末地前：确认回退物资、床与末影之眼、要塞传送门");
        }
        return List.copyOf(tasks);
    }

    /** 便捷：该级别是否达标。 */
    public static boolean allows(RiskLevel level, Map<String, Integer> available) {
        return check(level, available).allowed();
    }

    /**
     * P2.2+ 高风险准入（§6③）：在最低储备之上，额外要求"恢复点"证据——即已绑定的床/重生锚点。
     *
     * <p>依据我们自己的发现：床边复活锚点（床）是高风险活动（下界/末地/深矿）的**恢复能力**核心。
     * 没有恢复点就进高风险维度，一旦死亡即"从零"，正是要避免的。
     *
     * @param hasRecoveryPoint 是否已绑定床/重生锚点（由宿主查同伴 respawn 判定）
     * @return 与 {@link #check} 同构的裁决；未绑恢复点则 allowed=false 并追加准备任务
     */
    public static Verdict checkWithRecovery(RiskLevel level, Map<String, Integer> available,
                                            boolean hasRecoveryPoint) {
        RiskLevel lv = level == null ? RiskLevel.NORMAL : level;
        Verdict base = check(lv, available);
        // 仅对高风险级别（下界/末地）要求恢复点；NORMAL/MINING 不强制。
        boolean risky = lv == RiskLevel.NETHER || lv == RiskLevel.END;
        if (!risky || hasRecoveryPoint) {
            return base;
        }
        List<String> missing = new ArrayList<>(base.missing());
        missing.add("recovery_point need 1 have 0（未绑定床/重生锚点）");
        List<String> prep = new ArrayList<>(base.prepTasks());
        prep.add("先找床绑定重生点（interact_at 右键床即绑），再进" + lv.name().toLowerCase() + "；否则死亡即从零");
        return new Verdict(false, lv, List.copyOf(missing), List.copyOf(prep));
    }

    /**
     * 167 §B6：在「物资 + 恢复点」之上，再要求**基地备用**达标。
     *
     * <p>只收紧、不放松：{@code baseShortfall} 非空时往 missing 里追加条目并返回 allowed=false；
     * 达标时**完全等于** {@link #checkWithRecovery} 的结果——所以 {@code ESTABLISHED} 永远不会变成放行理由。
     *
     * @param baseShortfall 备用缺口（形如 {@code cooked_beef 0/8}）；null/空 表示已达标或未知
     * @param baseKnown     是否真的观测过基地备用箱；false 时不追加（未知不等于没有）
     */
    public static Verdict checkWithBase(RiskLevel level, Map<String, Integer> available,
                                        boolean hasRecoveryPoint,
                                        boolean baseKnown, List<String> baseShortfall) {
        Verdict base = checkWithRecovery(level, available, hasRecoveryPoint);
        RiskLevel lv = base.level();
        boolean risky = lv == RiskLevel.NETHER || lv == RiskLevel.END;
        if (!risky || !baseKnown || baseShortfall == null || baseShortfall.isEmpty()) {
            return base;   // 不额外收紧；也不因为不知道就放宽
        }
        List<String> missing = new ArrayList<>(base.missing());
        for (String s : baseShortfall) {
            missing.add("base_reserve need " + s);
        }
        List<String> prep = new ArrayList<>(base.prepTasks());
        prep.add("先把基地备用箱补到最低清单（" + String.join(", ", baseShortfall)
                + "），再进" + lv.name().toLowerCase() + "；否则死一次就没有第二套");
        return new Verdict(false, lv, List.copyOf(missing), List.copyOf(prep));
    }

    /** 供测试/宿主使用的已知风险关键词集合（保持与 {@link #levelForText} 一致）。 */
    public static Set<String> knownRiskKeywords() {
        return Set.of("下界", "地狱", "nether", "末地", "末影龙", "the_end", "下矿", "挖矿", "洞穴", "废弃矿井");
    }
}
