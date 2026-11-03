package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.AssetRequirement;
import com.dwinovo.numen.rdd.api.PrimarySpec;
import com.dwinovo.numen.rdd.policy.ResourceBudget;
import com.dwinovo.numen.rdd.policy.RiskGate;
import com.dwinovo.numen.rdd.policy.RiskLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规划期风险前置（P2.2 接线，主人定：**在规划时就意识到危险、提前备装**，不是等到进门才拦）。
 *
 * <p>两件事：
 * <ul>
 *   <li>{@link #prepHint}：把该目标/阶段的风险级别与最低储备写进规划提示词，逼规划师**提前**安排准备步骤。</li>
 *   <li>{@link #injectWaitFor}：给 Stage-A 的**高风险阶段**自动补 wait_for 硬门（来自 ResourceBudget），
 *       让“没备齐就不放行该阶段”由代码执行，而不是口头提醒。</li>
 * </ul>
 * 纯逻辑（只用 rdd-core 的 RiskGate/ResourceBudget），可单测；不碰 Minecraft。
 */
final class RddRiskPlanning {

    private RddRiskPlanning() {}

    /** 生成“风险前置”提示词块；NORMAL 返回空串（不打扰普通规划）。 */
    static String prepHint(String text, Map<String, Integer> available) {
        RiskLevel level = RiskGate.levelForText(text);
        if (level == RiskLevel.NORMAL) {
            return "";
        }
        Map<String, Integer> required = ResourceBudget.requiredWithBackup(level);
        if (required.isEmpty()) {
            return "";
        }
        List<String> missing = ResourceBudget.missingWithBackup(level, available);
        StringBuilder sb = new StringBuilder();
        sb.append("【风险前置｜").append(levelLabel(level)).append("】本目标/阶段属高风险。进入前必须备齐（真实资产，含备用套）：\n");
        for (Map.Entry<String, Integer> e : required.entrySet()) {
            sb.append("- ").append(e.getKey()).append(" ×").append(e.getValue()).append("\n");
        }
        if (missing.isEmpty()) {
            sb.append("当前缺口：无（已达标，保持不重复采集）。\n");
        } else {
            sb.append("当前缺口：").append(String.join("; ", missing)).append("\n");
        }
        sb.append("请把这些准备安排在该风险阶段**之前**的独立阶段（或该阶段开头）**提前完成**，"
                + "不要等到了现场才临时准备；下界/末地还需临时据点/回退路线与相应药水。\n");
        if (level == RiskLevel.NETHER || level == RiskLevel.END) {
            sb.append("【恢复点】进下界/末地前必须已有可用床并绑定为重生点（interact_at 右键床即绑）——"
                    + "没有恢复点就进高风险维度，死亡即从零。\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    /**
     * 给高风险阶段自动补 wait_for 硬门：合并 ResourceBudget 的要求到已有 waitFor（同 key 取较大 minimum）。
     * NORMAL 阶段原样返回。
     */
    static List<PrimarySpec> injectWaitFor(List<PrimarySpec> stages, Map<String, Integer> available) {
        if (stages == null || stages.isEmpty()) {
            return stages == null ? List.of() : stages;
        }
        List<PrimarySpec> out = new ArrayList<>(stages.size());
        int index = 0;
        for (PrimarySpec stage : stages) {
            // 第一个一级目标从零起步，绝不给它加跨级前置硬门——否则"就地挖石头/采集木头"
            // 这类描述被判 MINING 后会要求 torch/bread，而该阶段本身正是产出它们的阶段 → 自锁死等。
            // （真机验证抓到：primary-0 waitFor=bread4+torch16 → 永久 WAITING。）
            boolean isFirstStage = (index == 0);
            index++;
            if (stage == null) {
                continue;
            }
            if (isFirstStage) {
                out.add(pruneSelfLocked(stage));
                continue;
            }
            // 2026-11-02 实机：验收/收口阶段只是核对已有成果，不是新的高风险行动；
            // 给它注入下界/末地硬门会把「最终验收」变成永远等两套钻石甲的硬锁。
            String d = stage.description() == null ? "" : stage.description();
            boolean acceptance = d.contains("验收") || d.contains("最终确认") || d.contains("最终")
                    || d.contains("收口") || d.contains("确认");
            if (acceptance) {
                out.add(pruneSelfLocked(stage));
                continue;
            }
            RiskLevel level = RiskGate.levelForText(stage.description());
            // A2（94 号）：高风险阶段的门里带上「备用套」（ResourceBudget.backupFor），
            // 不是只够打一次就进门。NORMAL/MINING 与旧行为完全一致（backup 为空）。
            Map<String, Integer> required = ResourceBudget.requiredWithBackup(level);
            if (level == RiskLevel.NORMAL || required.isEmpty()) {
                out.add(pruneSelfLocked(stage));
                continue;
            }
            Map<String, Integer> merged = new LinkedHashMap<>();
            for (AssetRequirement r : stage.waitFor()) {
                merged.put(r.assetKey(), r.minimum());
            }
            for (Map.Entry<String, Integer> e : required.entrySet()) {
                merged.merge(e.getKey(), e.getValue(), Math::max);
            }
            // §6③ 恢复点：下界/末地阶段追加"床"作为硬门（携带即视为有恢复点能力的保守代理）。
            if (level == RiskLevel.NETHER || level == RiskLevel.END) {
                merged.merge("minecraft:white_bed", 1, Math::max);
            }
            List<AssetRequirement> wf = new ArrayList<>();
            for (Map.Entry<String, Integer> e : merged.entrySet()) {
                // 2026-11-03 实机：`minecraft:bed` 不是真实物品 ID（床是 *_bed），
                // 且床通常已放在基地、不在背包；当硬门会永久 WAITING。规划期直接剥掉。
                if ("minecraft:bed".equals(e.getKey())) continue;
                // 2026-11-02 实机：阶段自己就会产出 bread/emerald/bed，却把它们写成进入本阶段的
                // wait_for → 永远等不到（自我依赖死锁）。只剥掉「本阶段明确会产出」的同类项。
                if (producesInStage(stage.description(), e.getKey())) continue;
                wf.add(new AssetRequirement(e.getKey(), e.getValue()));
            }
            out.add(new PrimarySpec(stage.description(), wf));
        }
        return List.copyOf(out);
    }

    /** 去掉「本阶段自己会产出、却被写成进入本阶段前置」的 wait_for（不改其它要求）。 */
    private static PrimarySpec pruneSelfLocked(PrimarySpec stage) {
        if (stage == null || stage.waitFor() == null || stage.waitFor().isEmpty()) return stage;
        List<AssetRequirement> kept = new ArrayList<>();
        for (AssetRequirement r : stage.waitFor()) {
            if (!producesInStage(stage.description(), r.assetKey())) kept.add(r);
        }
        if (kept.size() == stage.waitFor().size()) return stage;
        return new PrimarySpec(stage.description(), kept);
    }

    /** 本阶段描述是否明确会产出该资产（只覆盖最容易自锁的几类）。 */
    private static boolean producesInStage(String description, String assetKey) {
        if (description == null || assetKey == null) return false;
        // 泛化床 ID 不是真实物品，任何阶段都不该当 wait_for 硬门（床可能已放置）。
        if ("minecraft:bed".equals(assetKey)) return true;
        String d = description;
        if (assetKey.endsWith(":bread")) return d.contains("面包");
        if (assetKey.endsWith(":emerald")) return d.contains("绿宝石") && d.contains("交易");
        if (assetKey.endsWith("_bed")) return d.contains("床");
        if (assetKey.endsWith(":wheat")) return d.contains("小麦") && (d.contains("拆") || d.contains("收") || d.contains("合成"));
        // 2026-11-03 实机：`wait_for cooked_beef×4` 落在「在村庄安家：拆干草块做面包」阶段上；
        // 该阶段本来就是产食物，靠作弊给了 16 牛肉才通过。食物类前置只在明确产食物的阶段剥离。
        if (isFoodAsset(assetKey) && isFoodProducingStage(d)) return true;
        return false;
    }

    private static boolean isFoodAsset(String assetKey) {
        return assetKey.endsWith(":bread") || assetKey.endsWith(":cooked_beef")
                || assetKey.endsWith(":beef") || assetKey.endsWith(":cooked_porkchop")
                || assetKey.endsWith(":porkchop") || assetKey.endsWith(":cooked_chicken")
                || assetKey.endsWith(":chicken") || assetKey.endsWith(":cooked_mutton")
                || assetKey.endsWith(":mutton") || assetKey.endsWith(":baked_potato")
                || assetKey.endsWith(":potato") || assetKey.endsWith(":carrot")
                || assetKey.endsWith(":apple") || assetKey.endsWith(":melon_slice")
                || assetKey.endsWith(":sweet_berries");
    }

    private static boolean isFoodProducingStage(String d) {
        // 只认"生产/处理食物"的上下文；`杀`/`猎` 会让「击杀末影龙」被误判成食物阶段，
        // 从而剥掉它应有的熟牛肉备用门（RddRiskPlanningTest.highRiskStageCarriesBackupSet 回归）。
        return d.contains("干草") || d.contains("面包") || d.contains("小麦")
                || d.contains("农田") || d.contains("收") || d.contains("种")
                || d.contains("烤") || d.contains("食物") || d.contains("吃");
    }

    private static String levelLabel(RiskLevel level) {
        return switch (level) {
            case NORMAL -> "常规";
            case MINING -> "下矿/洞穴";
            case NETHER -> "下界";
            case END -> "末地";
        };
    }

    // ------------------------------------------------------------ A2（94 号）：准备节点 ----

    /** 规划师已经写出这类阶段时，不再重复注入。 */
    private static final List<String> PREP_WORDS =
            List.of("准备", "补给", "前哨", "备用", "备齐", "营地", "仓库");

    static final String PREP_STAGE_DESC =
            "准备与补给：建前哨/补齐食物与工具/备一套备用/定回撤路线（进入高风险区域前完成）";

    /**
     * A2：高风险目标必须有一个「准备与补给」阶段，排在**第一个高风险阶段之前**。
     *
     * <p>为什么不能只靠提示词：88 §7.2 的实据是「末地链直线、没有前哨/备用/补给」，
     * 而规划师是 LLM，提示词可能被忽略。这里做**解析后的注入**，保证任务图里真有这个节点，
     * 且 `goal_staged` 事件能机读出来（不是文档里的一句口号）。
     *
     * <p>保守边界：
     * <ul>
     *   <li>没有高风险阶段 → 不插（低风险目标不该被加一步准备）；</li>
     *   <li>第一个阶段就是高风险（index 0）→ 不插：插进去会把原第一阶段顶到 index 1，
     *       让「就地采石」这类产出型阶段拿到 torch/bread 硬门而自锁（P2.2 真机回归）；</li>
     *   <li>已达阶段上限 → 不插（不硬塞）。</li>
     * </ul>
     */
    /**
     * 2026-11-03 实机 r10-2：Stage-A 直接给「把铁矿石全部烧成铁锭」并要 iron_ingot≥11，
     * 但背包/箱子没有铁矿石，描述把"有矿"当既成事实 → 空转 20 分钟。
     * 这里只做最小前置：烧/熔炼铁且前面没有挖铁步、当前也没有铁源时，插一个"先挖铁"准备级。
     * 纯函数、可单测；不碰已绑定链。
     */
    static List<PrimarySpec> ensureSmeltPrepStage(List<PrimarySpec> stages, Map<String, Integer> available) {
        if (stages == null || stages.isEmpty()) return stages;
        java.util.Map<String, Integer> held = available == null ? java.util.Map.of() : available;
        List<PrimarySpec> out = new ArrayList<>();
        boolean minedIron = false;
        for (int i = 0; i < stages.size(); i++) {
            PrimarySpec stage = stages.get(i);
            String d = stage.description() == null ? "" : stage.description();
            boolean smeltIron = (d.contains("烧") || d.contains("熔炼") || d.contains("冶炼"))
                    && d.contains("铁");
            boolean ironSource = held.getOrDefault("minecraft:iron_ore", 0) > 0
                    || held.getOrDefault("minecraft:deepslate_iron_ore", 0) > 0
                    || held.getOrDefault("minecraft:raw_iron", 0) > 0
                    || held.getOrDefault("minecraft:iron_ingot", 0) > 0;
            int remainingIncludingCurrent = stages.size() - i;
            if (smeltIron && !minedIron && !ironSource
                    && out.size() + 1 + remainingIncludingCurrent
                    <= com.dwinovo.numen.rdd.core.RddChainFactory.MAX_STAGES) {
                out.add(new PrimarySpec(
                        "先下矿挖铁矿石：在已知矿区/浅层找到铁矿石并采够本轮烧炼所需（先取原料，再烧锭）",
                        List.of()));
            }
            out.add(stage);
            if (d.contains("挖铁") || d.contains("铁矿石") || d.contains("采铁")
                    || d.contains("挖矿") || d.contains("采矿")) {
                minedIron = true;
            }
        }
        return out;
    }

    static List<PrimarySpec> ensurePrepStage(List<PrimarySpec> stages, int maxStages) {
        if (stages == null || stages.isEmpty()) {
            return stages == null ? List.of() : stages;
        }
        int firstRisky = -1;
        for (int i = 0; i < stages.size(); i++) {
            PrimarySpec s = stages.get(i);
            if (s == null) continue;
            if (RiskGate.levelForText(s.description()) != RiskLevel.NORMAL) {
                firstRisky = i;
                break;
            }
        }
        if (firstRisky <= 0) {
            return stages;
        }
        for (int i = 0; i < firstRisky; i++) {
            String d = stages.get(i).description();
            for (String w : PREP_WORDS) {
                if (d.contains(w)) return stages;
            }
        }
        if (stages.size() >= maxStages) {
            return stages;
        }
        List<PrimarySpec> out = new ArrayList<>(stages);
        out.add(firstRisky, new PrimarySpec(PREP_STAGE_DESC, List.of()));
        return List.copyOf(out);
    }
}
