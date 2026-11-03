package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.PrimarySpec;
import com.dwinovo.numen.rdd.api.SubtaskSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 规划守门器（纯逻辑）：模型输出<b>明显违反硬规则</b>时安全丢弃，而不是照单执行。
 *
 * <p>提示词只是请求，模型会不听。硬规则必须由代码执行，这一层就是执行的地方：
 * <ul>
 *   <li><b>未要求就规划可选升级</b>（书架 / 满级附魔台 / 铁砧 / 信标 / 生电部件）→ 丢弃。</li>
 *   <li><b>已持有的资产还要再去获取</b> → 从计划里去掉，记为复用了现有资产。</li>
 *   <li><b>可选工程主题</b>（生电 / 交易所 / 农场）在主人没要求时 → 整条阶段丢弃。</li>
 * </ul>
 *
 * <h2>清空的规则（边界写死，别靠感觉）</h2>
 * <ul>
 *   <li><b>违规项永不复活</b>：未被主人要求的可选升级/可选阶段被丢弃后，任何情况下都不会
 *       因为「结果空了」而被放回执行。宁可返回空表让上游安全降级，也不要照单去建刷铁机。</li>
 *   <li><b>全违规 → 空表</b>：返回空表即降级信号，交给上游走失败恢复。</li>
 *   <li><b>全已持有 → 保留</b>：这一条只适用于子步骤里「已持有」那一类——它们合法、只是多余，
 *       会被资产检测立刻验收；清空反而会把「本来就做完的一级」变成一次升级上报。
 *       阶段过滤没有这一类，所以阶段全被过滤时就是空表。</li>
 * </ul>
 *
 * <p>不改变既有解析行为：{@code parse} 仍是纯解析，守门是解析之后单独一步，便于单测与回滚。
 */
final class RddPlanGuard {

    /**
     * 子步骤过滤结果。
     *
     * @param allowed 允许进入计划的部分
     * @param dropped 被丢弃项的说明（可审计，不含隐藏推理）
     * @param reused  因已持有而被去掉的资产键（审计「复用了什么」）
     */
    record Filtered(List<SubtaskSpec> allowed, List<String> dropped, List<String> reused) {

        boolean degradedToEmpty() {
            return allowed.isEmpty();
        }
    }

    /**
     * 阶段过滤结果。
     *
     * @param allowed 允许进入计划的阶段
     * @param dropped 被丢弃的阶段说明
     */
    record Stages(List<PrimarySpec> allowed, List<String> dropped) {}

    private RddPlanGuard() {}

    /**
     * 2026-11-02（169 T2 最小版）：正文要求「N 块方块」而 condition 根本不验方块 → 判据不覆盖描述。
     * 实机形状：r22-0「圆石/深板岩/下界岩都行（8 块）」、r25-1「铁剑 + 32 块圆石/石质方块」。
     * 判据只验单一 asset_key 时，方块要求永远不参与判定——步骤会在「只拿到剑」时假过。
     *
     * @return 未覆盖的要求说明（空 = 覆盖得住）
     */
    static List<String> predicateGaps(com.dwinovo.numen.rdd.api.SubtaskSpec s) {
        List<String> gaps = new ArrayList<>();
        if (s == null) return gaps;
        String d = s.description() == null ? "" : s.description();
        Map<String, Object> c = s.condition();
        boolean blockCondition = c.containsKey("group") && String.valueOf(c.get("group")).contains("blocks");
        boolean assetIsBlock = c.get("asset_key") instanceof String k && PLACE_MATERIALS.contains(k);
        // 2026-11-02（实机 r27-0）：描述是「清点背包（烈焰棒/下界疣/瓶子）」，判据却是
        // {type:block_nearby, block:nether_wart} —— 用「身边 1 格有疣方块」冒充「背包里有疣」，
        // 主世界基地永远不满足。背包意图 + 世界方块判据 = 不覆盖。
        boolean inventoryIntent = d.contains("背包") || d.contains("身上") || d.contains("随身")
                || d.contains("手上") || d.contains("清点");
        boolean worldCondition = c.containsKey("block")
                || (c.get("type") instanceof String t && t.contains("block"));
        if (inventoryIntent && worldCondition) {
            gaps.add("背包物品（判据却在验世界方块）");
        }
        java.util.regex.Matcher m = BLOCK_COUNT.matcher(d);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n <= 0) continue;
            if (!blockCondition && !assetIsBlock) {
                gaps.add("至少 " + n + " 块方块");
            }
        }
        // 2026-11-03 实机 r7-1：item_used wheat_seeds 把 3 次 "nothing visibly changed" 的点击
        // 也算进去，种子仍 15 却判完成。种/播种类步骤不接受 item_used，要求世界作物或收获物证据。
        if ("item_used".equals(String.valueOf(c.get("type")))
                && c.get("item") instanceof String item
                && isPlantableSeed(item)
                && (d.contains("种") || d.contains("播种"))) {
            gaps.add("item_used 只证右键使用次数，不证种子实际种下；改用 block_nearby 作物或 container_at 收获物");
        }
        return gaps;
    }

    private static boolean isPlantableSeed(String itemId) {
        return switch (itemId) {
            case "minecraft:wheat_seeds", "minecraft:beetroot_seeds", "minecraft:melon_seeds",
                    "minecraft:pumpkin_seeds", "minecraft:carrot", "minecraft:potato" -> true;
            default -> false;
        };
    }

    /** 「32 块圆石/石质方块」「N 方块」这类数量要求。 */
    private static final java.util.regex.Pattern BLOCK_COUNT =
            java.util.regex.Pattern.compile("(\\d{1,3})\\s*(?:块|个)\\s*(?:圆石|石块|方块|石质|垫脚)")
            ;

    /** 工具/材料名 → 物品 id（用于「工具前提不成立」检查）。 */
    private static final java.util.Map<String, String> TOOL_WORDS = java.util.Map.of(
            "钻石镐", "minecraft:diamond_pickaxe",
            "铁镐", "minecraft:iron_pickaxe",
            "石镐", "minecraft:stone_pickaxe",
            "木镐", "minecraft:wooden_pickaxe",
            "打火石", "minecraft:flint_and_steel",
            "熔炉", "minecraft:furnace");

    /** 分句判「去下界/要塞/烈焰」：本分句目标词之前有「不」就只否定本分句，不波及下一句。 */
    private static boolean clauseNetherBound(String description) {
        if (description == null || description.isBlank()) return false;
        String[] clauses = description.split("[，。；,;、\n]");
        for (String clause : clauses) {
            if (clause == null || clause.isBlank()) continue;
            int target = Integer.MAX_VALUE;
            for (String w : new String[]{"下界", "要塞", "烈焰", "废墟传送门", "传送门"}) {
                int i = clause.indexOf(w);
                if (i >= 0 && i < target) target = i;
            }
            if (target == Integer.MAX_VALUE) continue;
            if (clause.substring(0, target).contains("不")) continue;   // 不下界 / 不要进入下界
            return true;
        }
        return false;
    }

    /**
     * 2026-11-02（实机 r25-2）：描述写「用钻石镐去取黑曜石」，背包里却只有石镐——
     * 石镐挖黑曜石不掉落，这一步从出生就是死的。判据只认 obsidian，永远等不到。
     *
     * <p>只做「用到工具但手上没有」的判定；描述本身是「制作/合成」该工具时不算缺。
     *
     * @return 缺失的工具说明（空 = 前提成立或不需要工具）
     */
    static List<String> toolGaps(com.dwinovo.numen.rdd.api.SubtaskSpec s, Map<String, Integer> held) {
        List<String> gaps = new ArrayList<>();
        if (s == null || held == null) return gaps;
        String d = s.description() == null ? "" : s.description();
        // 2026-11-02 实机（恢复安家目标）：Stage-B 首步写「用 2 块木板竖放搓 4 根木棍…做木镐」，
        // 旧 producing 只认「做一/造一/合成/制作」，于是把「做木镐」误判成「用木镐」并丢弃整步。
        // 这里补「做/造/搓」等真实制作动词；同时下面只把「用X」当使用证据（提到名字不算用）。
        boolean producing = d.contains("合成") || d.contains("做") || d.contains("造")
                || d.contains("制作") || d.contains("搓") || d.contains("备一把");
        // 2026-11-02（实机 r28-3/death49–58）：往「下界/要塞/烈焰/传送门」去的步骤，背包没甲就不该发布。
        // 2026-11-03 实机：整段 negatedNether 会误伤「途中不下界，打烈焰人」这类混合句，
        // 而「不要进入下界」又不在固定否定串里。改为**按分句**判：否定只作用于本分句。
        com.dwinovo.numen.rdd.policy.RiskLevel risk = com.dwinovo.numen.rdd.policy.RiskGate.levelForText(d);
        boolean netherBound = clauseNetherBound(d)
                || risk == com.dwinovo.numen.rdd.policy.RiskLevel.NETHER
                || risk == com.dwinovo.numen.rdd.policy.RiskLevel.END;
        boolean hasArmor = held.getOrDefault("minecraft:iron_chestplate", 0) > 0
                || held.getOrDefault("minecraft:diamond_chestplate", 0) > 0
                || held.getOrDefault("minecraft:leather_chestplate", 0) > 0
                || held.getOrDefault("minecraft:chainmail_chestplate", 0) > 0
                || held.getOrDefault("minecraft:golden_chestplate", 0) > 0;
        if (netherBound && !hasArmor && !producing) {
            gaps.add("护甲（去下界前至少一件胸甲）");
        }
        for (Map.Entry<String, String> e : TOOL_WORDS.entrySet()) {
            // 只把「用<工具>」当作"这一步真的要用它"；只是提到名字（例如"做木镐"、"石镐 3 个"）不算。
            if (!d.contains("用" + e.getKey())) continue;
            if (producing) continue;
            int have = held.getOrDefault(e.getValue(), 0);
            if (have <= 0) gaps.add(e.getKey());
        }
        return gaps;
    }

    /**
     * 同一批 Stage-B 步骤里，前面某步刚做出来的工具应视为"后续步骤可用"。
     * 否则一串「做木镐 → 用木镐挖石头 → 做石镐」会在规划时按当前空背包全部判缺工具而整串丢弃。
     * 只认明确的制作动词；纯提到工具名不算产出。
     */
    static java.util.Set<String> producedToolIds(String description) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (description == null || description.isBlank()) return out;
        boolean producing = description.contains("做") || description.contains("制作")
                || description.contains("合成") || description.contains("造")
                || description.contains("搓") || description.contains("备一把");
        if (!producing) return out;
        for (Map.Entry<String, String> e : TOOL_WORDS.entrySet()) {
            if (description.contains(e.getKey())) out.add(e.getValue());
        }
        return out;
    }

    /** 可作为垫脚/铺路的方块材料（判据写单一 asset_key 时用来归一成 group:blocks）。 */
    private static final java.util.Set<String> PLACE_MATERIALS = java.util.Set.of(
            "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:deepslate",
            "minecraft:netherrack", "minecraft:blackstone", "minecraft:basalt",
            "minecraft:dirt", "minecraft:stone", "minecraft:andesite",
            "minecraft:granite", "minecraft:diorite", "minecraft:polished_blackstone");

    /**
     * 2026-11-02（实机 r22-0）：描述写「圆石/深板岩/下界岩都行」，判据却是
     * {@code asset_key=minecraft:cobblestone, minimum=8} —— 在玄武岩三角洲、0 工具时
     * 这条**永远不可满足**，把撤退步骤卡成死结。
     *
     * <p>归一（不改别的）：描述出现「都行/任意/任选/或」且是垫脚/铺路类，且 asset_key 是
     * 方块材料 → 改写成 {@code {group:blocks, minimum:N}}（检测器已支持 group）。
     * 纯函数、不做取舍判断，只是让判据与描述一致。
     */
    static List<SubtaskSpec> normalizeConditions(List<SubtaskSpec> specs) {
        if (specs == null || specs.isEmpty()) return List.of();
        List<SubtaskSpec> out = new ArrayList<>(specs.size());
        for (SubtaskSpec s : specs) {
            out.add(normalizeCondition(s));
        }
        return List.copyOf(out);
    }

    static SubtaskSpec normalizeCondition(SubtaskSpec s) {
        if (s == null) return null;
        String d = s.description() == null ? "" : s.description();
        Object key = s.condition().get("asset_key");
        boolean multi = d.contains("都行") || d.contains("任意") || d.contains("任选") || d.contains("或");
        boolean placing = d.contains("垫") || d.contains("铺") || d.contains("落脚台") || d.contains("搭") || d.contains("放");
        if (key instanceof String k && PLACE_MATERIALS.contains(k) && multi && placing) {
            // acquire/未知 mode 不能归一成 group（group 无增量实现，会造出生即不可完成的步）；
            // 保留原 asset_key，让 evaluator 按基线增量判。
            Object rawMode = s.condition().get("mode");
            if (rawMode != null
                    && !com.dwinovo.numen.rdd.core.HardCodedEvaluator.MODE_HOLD.equals(String.valueOf(rawMode))) {
                return s;
            }
            Map<String, Object> cond = new java.util.LinkedHashMap<>();
            cond.put("group", "blocks");
            cond.put("minimum", s.condition().getOrDefault("minimum", 1));
            // 规范化不能静默吞掉 mode/optional；group+acquire 在 evaluator 会 fail-close，
            // 但至少要让语义原样到达判定层，而不是悄悄降成 hold。
            if (s.condition().containsKey("mode")) cond.put("mode", s.condition().get("mode"));
            if (s.condition().containsKey("optional")) cond.put("optional", s.condition().get("optional"));
            return new SubtaskSpec(s.description(), cond, s.body());
        }
        return s;
    }

    /**
     * 过滤 Stage-B 的子步骤。
     *
     * @param specs     解析后的子步骤（可为空）
     * @param objective 当前一级主题（判定主人是否要求了可选升级）
     * @param held      当前真实背包（判定哪些已经持有）
     */
    static Filtered filterSubtasks(List<SubtaskSpec> specs, String objective, Map<String, Integer> held) {
        if (specs == null || specs.isEmpty()) {
            return new Filtered(List.of(), List.of(), List.of());
        }
        boolean optionalAllowed = RddPlanningPolicy.optionalAllowed(objective);
        // 规划期按"当前背包 + 前面步骤已产出的工具"顺序求值；不是所有步骤都用同一份空背包。
        Map<String, Integer> effectiveHeld = new java.util.LinkedHashMap<>();
        if (held != null) effectiveHeld.putAll(held);

        List<SubtaskSpec> allowed = new ArrayList<>();
        List<SubtaskSpec> heldOnes = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        List<String> reused = new ArrayList<>();
        for (SubtaskSpec spec : specs) {
            // 2026-11-02（169 T2 最小版）：判据不覆盖描述 → 丢弃该步（宁可不执行，也不要假过）。
            // 实机：r25-1「铁剑 + 32 块方块」condition 只验铁剑，方块凑没凑齐都不影响判定。
            List<String> toolMissing = toolGaps(spec, effectiveHeld);
            if (!toolMissing.isEmpty()) {
                dropped.add("工具前提不成立（缺 " + String.join("/", toolMissing)
                        + "）→ 先补工具或换一条走法："
                        + (spec.description() == null ? "" : spec.description()));
                continue;
            }
            List<String> gaps = predicateGaps(spec);
            if (!gaps.isEmpty()) {
                dropped.add("判据不覆盖描述（" + String.join("；", gaps)
                        + "）→ 请拆成两步：先备方块、再备武器："
                        + (spec.description() == null ? "" : spec.description()));
                continue;
            }
            String assetKey = assetKeyOf(spec);
            if (!optionalAllowed && RddPlanningPolicy.optionalAsset(assetKey)) {
                dropped.add("可选升级未获要求：" + assetKey);
                continue;
            }
            if (alreadyHeld(spec, held)) {
                reused.add(assetKey);
                heldOnes.add(spec);
                producedToolIds(spec.description()).forEach(id -> effectiveHeld.merge(id, 1, Math::max));
                continue;
            }
            allowed.add(spec);
            producedToolIds(spec.description()).forEach(id -> effectiveHeld.merge(id, 1, Math::max));
        }

        if (allowed.isEmpty()) {
            // 保护性让步只适用于「已持有」这一类：它们合法、只是多余，会被资产检测立刻验收，
            // 清空反而会把「本来就做完的一级」变成一次升级上报。
            // 违规的可选升级（dropped 那批）绝不因此复活。
            return new Filtered(List.copyOf(heldOnes), dropped, reused);
        }
        return new Filtered(List.copyOf(allowed), dropped, reused);
    }

    /**
     * 过滤 Stage-A 的阶段清单：主人没要求时，可选工程（生电/交易所/农场/满级）整条不要。
     *
     * <p><b>全部都是未获要求的可选分支时返回空阶段表</b>——交给上游安全降级/走失败恢复。
     * 绝不把原始违规阶段表放回执行：宁可这一级展开失败重来，也不要照单去建刷铁机。
     * （子步骤那边对「已持有」有不清空的保护，但那条保护不适用于违规的可选阶段。）
     */
    static Stages filterStages(List<PrimarySpec> stages, String objective) {
        if (stages == null || stages.isEmpty()) {
            return new Stages(List.of(), List.of());
        }
        if (RddPlanningPolicy.optionalAllowed(objective)) {
            return new Stages(List.copyOf(stages), List.of());
        }
        List<PrimarySpec> allowed = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (PrimarySpec stage : stages) {
            String theme = stage == null ? "" : String.valueOf(stage.description());
            if (RddPlanningPolicy.optionalTheme(theme)) {
                dropped.add("可选分支未获要求：" + theme);
                continue;
            }
            allowed.add(stage);
        }
        return new Stages(List.copyOf(allowed), dropped);
    }

    // ---------- helpers ----------

    private static String assetKeyOf(SubtaskSpec spec) {
        if (spec == null || spec.condition() == null) {
            return "";
        }
        Object key = spec.condition().get("asset_key");
        return key == null ? "" : String.valueOf(key).trim();
    }

    private static int minimumOf(SubtaskSpec spec) {
        if (spec == null || spec.condition() == null) {
            return 1;
        }
        Object min = spec.condition().get("minimum");
        if (min instanceof Number n) {
            return n.intValue();
        }
        if (min instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignore) {
                return 1;
            }
        }
        return 1;
    }

    private static boolean alreadyHeld(SubtaskSpec spec, Map<String, Integer> held) {
        if (spec == null) return false;
        Object mode = spec.condition().get("mode");
        // acquire/未知 mode 不能因“当前已有”被当成冗余删掉；只有 hold 才能省略。
        if (mode != null && !com.dwinovo.numen.rdd.core.HardCodedEvaluator.MODE_HOLD
                .equals(String.valueOf(mode))) {
            return false;
        }
        if (spec.condition().containsKey("group"))
            return com.dwinovo.numen.rdd.core.HardCodedEvaluator.matches(spec.condition(), held);
        String key = assetKeyOf(spec);
        if (key.isEmpty() || held == null || held.isEmpty()) {
            return false;
        }
        Integer have = held.get(key);
        return have != null && have >= minimumOf(spec);
    }
}
