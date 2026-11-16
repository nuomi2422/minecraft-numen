package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.NodeType;
import com.dwinovo.numen.rdd.api.PrimarySpec;
import com.dwinovo.numen.rdd.api.RecurrenceSpec;
import com.dwinovo.numen.rdd.api.SubtaskSpec;
import com.dwinovo.numen.rdd.api.SubtaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守门器测试：模型违反硬规则时必须被拦住，而不是照单执行。
 */
class RddPlanGuardTest {

    private static SubtaskSpec spec(String description, String assetKey, int minimum) {
        return new SubtaskSpec(description, Map.of("asset_key", assetKey, "minimum", minimum), null);
    }

    // ---------- 2026-11-02（r25-2 实机）：工具前提不成立 ----------

    @Test
    void 描述要用钻石镐但背包只有石镐_isToolGap() {
        SubtaskSpec s = new SubtaskSpec(
                "用钻石镐去 Y<16 或找熔岩湖与水交界处取 10 块黑曜石",
                Map.of("asset_key", "minecraft:obsidian", "minimum", 10), null);
        assertEquals(List.of("钻石镐"),
                RddPlanGuard.toolGaps(s, Map.of("minecraft:stone_pickaxe", 1)));
    }

    @Test
    void 去下界而背包无甲_isArmorGap() {
        SubtaskSpec s = new SubtaskSpec(
                "借废墟传送门进下界要塞打烈焰人，拿 1 根烈焰棒",
                Map.of("asset_key", "minecraft:blaze_rod", "minimum", 1), null);
        assertTrue(RddPlanGuard.toolGaps(s, Map.of("minecraft:stone_sword", 1))
                .stream().anyMatch(g -> g.contains("护甲")), RddPlanGuard.toolGaps(s, Map.of()).toString());
    }

    @Test
    void 途中不下界的走路步骤不报护甲缺() {
        SubtaskSpec s = new SubtaskSpec("从农田步行前往已知首目标村庄 village_home，途中不下界、不额外囤资源",
                Map.of("asset_key", "minecraft:stone_pickaxe", "minimum", 1), null);
        assertTrue(RddPlanGuard.toolGaps(s, Map.of("minecraft:stone_pickaxe", 1)).isEmpty(),
                RddPlanGuard.toolGaps(s, Map.of()).toString());
    }

    @Test
    void acquire垫脚不归一成不可完成的group条件() {
        SubtaskSpec s = new SubtaskSpec("垫脚：圆石/深板岩都行 8 块",
                Map.of("asset_key", "minecraft:cobblestone", "minimum", 8, "mode", "acquire"), null);
        SubtaskSpec n = RddPlanGuard.normalizeCondition(s);
        assertEquals("minecraft:cobblestone", n.condition().get("asset_key"), n.toString());
        assertEquals("acquire", n.condition().get("mode"));
        assertFalse(n.condition().containsKey("group"));
    }

    @Test
    void 种植步骤用item_used_isGap() {
        SubtaskSpec s = new SubtaskSpec("种植 6 个小麦种子到农田",
                Map.of("type", "item_used", "item", "minecraft:wheat_seeds", "minimum", 6), null);
        assertTrue(RddPlanGuard.predicateGaps(s).stream().anyMatch(g -> g.contains("不证种子实际种下")),
                RddPlanGuard.predicateGaps(s).toString());
    }

    @Test
    void 骨粉使用仍允许item_used() {
        SubtaskSpec s = new SubtaskSpec("使用 6 次骨粉催熟作物",
                Map.of("type", "item_used", "item", "minecraft:bone_meal", "minimum", 6), null);
        assertTrue(RddPlanGuard.predicateGaps(s).isEmpty(), RddPlanGuard.predicateGaps(s).toString());
    }

    @Test
    void 不要进入下界的准备步骤不报护甲缺() {
        SubtaskSpec s = new SubtaskSpec("不要进入下界，先在主世界准备火把与食物",
                Map.of("asset_key", "minecraft:torch", "minimum", 8), null);
        assertTrue(RddPlanGuard.toolGaps(s, Map.of()).isEmpty(), RddPlanGuard.toolGaps(s, Map.of()).toString());
    }

    @Test
    void 同句混合_不下界但打烈焰人_仍报护甲缺() {
        SubtaskSpec s = new SubtaskSpec("途中不下界，打烈焰人取烈焰棒",
                Map.of("asset_key", "minecraft:blaze_rod", "minimum", 1), null);
        assertTrue(RddPlanGuard.toolGaps(s, Map.of()).stream().anyMatch(g -> g.contains("护甲")),
                RddPlanGuard.toolGaps(s, Map.of()).toString());
    }

    @Test
    void 有胸甲时不报护甲缺() {
        SubtaskSpec s = new SubtaskSpec("进下界要塞打烈焰人",
                Map.of("asset_key", "minecraft:blaze_rod", "minimum", 1), null);
        assertTrue(RddPlanGuard.toolGaps(s, Map.of("minecraft:iron_chestplate", 1)).isEmpty());
    }

    @Test
    void 描述是制作工具时不算缺() {
        SubtaskSpec s = new SubtaskSpec("合成一把铁镐",
                Map.of("asset_key", "minecraft:iron_pickaxe", "minimum", 1), null);
        assertTrue(RddPlanGuard.toolGaps(s, Map.of()).isEmpty());
    }

    @Test
    void 过滤时把工具前提不成立的步骤丢弃() {
        SubtaskSpec bad = new SubtaskSpec("用钻石镐取 10 块黑曜石",
                Map.of("asset_key", "minecraft:obsidian", "minimum", 10), null);
        RddPlanGuard.Filtered out = RddPlanGuard.filterSubtasks(List.of(bad), "酿抗火药",
                Map.of("minecraft:stone_pickaxe", 1));
        assertTrue(out.allowed().isEmpty());
        assertTrue(out.dropped().get(0).contains("工具前提不成立"), out.dropped().toString());
    }

    @Test
    void 顺序产出工具时后续步骤不误丢() {
        // 2026-11-02 实机：Stage-B 首两步行文「搓木棍做木镐」→「用木镐挖石头做石镐」，
        // 旧实现按当前空背包逐条判，两条全丢，第一步就没人干活。
        SubtaskSpec make = new SubtaskSpec("用 2 块木板竖放搓 4 根木棍做木镐",
                Map.of("asset_key", "minecraft:wooden_pickaxe", "minimum", 1), null);
        SubtaskSpec use = new SubtaskSpec("用木镐挖约 6 个石头做石镐与石斧",
                Map.of("asset_key", "minecraft:stone_pickaxe", "minimum", 1), null);
        RddPlanGuard.Filtered out = RddPlanGuard.filterSubtasks(List.of(make, use), "石器开局", Map.of());
        assertEquals(2, out.allowed().size(), "前一步产出的工具应视为后续可用：" + out.dropped());
    }

    // ---------- 2026-11-02（169 T2 最小版）：判据不覆盖描述 ----------

    @Test
    void 描述要三十二块方块而判据只认铁剑_isGap() {
        SubtaskSpec r25 = new SubtaskSpec(
                "备齐武器与垫脚方块：一把铁剑加至少 32 块圆石/石质方块用于垫桥",
                Map.of("asset_key", "minecraft:iron_sword", "minimum", 1), null);
        assertEquals(List.of("至少 32 块方块"), RddPlanGuard.predicateGaps(r25));
    }

    @Test
    void 背包意图配世界方块判据_isGap() {
        SubtaskSpec r27 = new SubtaskSpec("清点背包：blaze_rod/nether_wart/玻璃瓶/圆石",
                Map.of("type", "block_nearby", "block", "minecraft:nether_wart", "radius", 1), null);
        assertTrue(RddPlanGuard.predicateGaps(r27).stream().anyMatch(g -> g.contains("背包物品")),
                RddPlanGuard.predicateGaps(r27).toString());
    }

    @Test
    void 判据是方块组时不算Gap() {
        SubtaskSpec ok = new SubtaskSpec("在脚下铺 3x3 落脚台，先备 8 块方块",
                Map.of("group", "blocks", "minimum", 8), null);
        assertTrue(RddPlanGuard.predicateGaps(ok).isEmpty());
    }

    @Test
    void 过滤时把判据不覆盖的步骤丢弃并给出理由() {
        SubtaskSpec bad = new SubtaskSpec(
                "备齐武器与垫脚：一把铁剑加至少 32 块圆石/石质方块",
                Map.of("asset_key", "minecraft:iron_sword", "minimum", 1), null);
        RddPlanGuard.Filtered out = RddPlanGuard.filterSubtasks(List.of(bad), "酿抗火药", Map.of());
        assertTrue(out.allowed().isEmpty());
        assertEquals(1, out.dropped().size());
        assertTrue(out.dropped().get(0).contains("判据不覆盖描述"), out.dropped().toString());
    }

    @Test
    void 没有方块数量要求时不报Gap() {
        SubtaskSpec s = spec("取 4 块黑曜石", "minecraft:obsidian", 4);
        assertTrue(RddPlanGuard.predicateGaps(s).isEmpty());
    }

    // ---------- 2026-11-02：垫脚类「都行」条件归一成 group:blocks（r22-0 实机形状） ----------

    @Test
    void 垫脚多选材料条件归一为方块组() {
        SubtaskSpec r22 = spec("用已有的方块（圆石/深板岩/下界岩都行）在脚下铺 3x3 落脚台",
                "minecraft:cobblestone", 8);
        SubtaskSpec normalized = RddPlanGuard.normalizeCondition(r22);
        assertEquals("blocks", normalized.condition().get("group"), normalized.condition().toString());
        assertEquals(8, normalized.condition().get("minimum"));
        assertFalse(normalized.condition().containsKey("asset_key"));
    }

    @Test
    void 单一材料步骤不被改写() {
        SubtaskSpec single = spec("取 4 块黑曜石", "minecraft:obsidian", 4);
        assertEquals("minecraft:obsidian",
                RddPlanGuard.normalizeCondition(single).condition().get("asset_key"));
    }

    // ---------- 1 & 3：未要求的可选升级被丢弃，明确要求时放行 ----------

    @Test
    void 未要求时书架与满级相关步骤被丢弃() {
        List<SubtaskSpec> plan = List.of(
                spec("开采黑曜石", "minecraft:obsidian", 4),
                spec("做15个书架", "minecraft:bookshelf", 15),
                spec("合成铁砧", "minecraft:anvil", 1));

        RddPlanGuard.Filtered out = RddPlanGuard.filterSubtasks(plan, "通关MC", Map.of());

        assertEquals(1, out.allowed().size(), "只剩必要的黑曜石步骤");
        assertEquals("minecraft:obsidian",
                out.allowed().get(0).condition().get("asset_key"));
        assertEquals(2, out.dropped().size());
        assertTrue(out.dropped().stream().allMatch(d -> d.contains("可选升级未获要求")));
    }

    @Test
    void 主人明确要求时可选升级被放行() {
        List<SubtaskSpec> plan = List.of(
                spec("做15个书架", "minecraft:bookshelf", 15),
                spec("开采黑曜石", "minecraft:obsidian", 4));

        RddPlanGuard.Filtered out = RddPlanGuard.filterSubtasks(plan, "帮我做满级附魔台和15个书架", Map.of());

        assertEquals(2, out.allowed().size(), "已明确要求就不该再拦");
        assertTrue(out.dropped().isEmpty());
    }

    // ---------- 2：已持有资产不重复规划 ----------

    @Test
    void 已持有的资产步骤被去掉并记为复用() {
        List<SubtaskSpec> plan = List.of(
                spec("再挖6颗钻石", "minecraft:diamond", 6),
                spec("挖铁", "minecraft:raw_iron", 4));
        Map<String, Integer> held = Map.of("minecraft:diamond", 6, "minecraft:raw_iron", 1);

        RddPlanGuard.Filtered out = RddPlanGuard.filterSubtasks(plan, "深钻求钻", held);

        assertEquals(1, out.allowed().size(), "已持够的钻石步骤不该再规划");
        assertEquals("minecraft:raw_iron", out.allowed().get(0).condition().get("asset_key"));
        assertTrue(out.reused().contains("minecraft:diamond"));
    }

    @Test
    void 持有数量不足时仍然保留该步骤() {
        List<SubtaskSpec> plan = List.of(spec("挖煤", "minecraft:coal", 8));
        Map<String, Integer> held = Map.of("minecraft:coal", 3);

        RddPlanGuard.Filtered out = RddPlanGuard.filterSubtasks(plan, "采集燃料", held);

        assertEquals(1, out.allowed().size(), "不够就得继续采");
        assertTrue(out.reused().isEmpty());
    }

    // ---------- 6：违反硬规则时安全降级 ----------

    @Test
    void 整条计划全是违规时安全降级为空表() {
        List<SubtaskSpec> plan = List.of(
                spec("做15个书架", "minecraft:bookshelf", 15),
                spec("合成铁砧", "minecraft:anvil", 1));

        RddPlanGuard.Filtered out = RddPlanGuard.filterSubtasks(plan, "通关MC", Map.of());

        assertTrue(out.degradedToEmpty(), "全部违规必须降级而不是照单执行");
        assertTrue(out.allowed().isEmpty());
        assertEquals(2, out.dropped().size());
    }

    @Test
    void 全部已持有时保留原计划_不制造空计划() {
        // 保护性让步：全已满足时若清空，会把「本来就做完的一级」变成一次升级上报
        List<SubtaskSpec> plan = List.of(spec("挖钻石", "minecraft:diamond", 6));
        Map<String, Integer> held = Map.of("minecraft:diamond", 6);

        RddPlanGuard.Filtered out = RddPlanGuard.filterSubtasks(plan, "深钻求钻", held);

        assertEquals(1, out.allowed().size(), "不得清空");
        assertTrue(out.reused().contains("minecraft:diamond"));
    }

    @Test
    void 违规可选与已持有混合时_违规项绝不复活() {
        List<SubtaskSpec> plan = List.of(
                spec("做15个书架", "minecraft:bookshelf", 15),
                spec("挖钻石", "minecraft:diamond", 6));
        Map<String, Integer> held = Map.of("minecraft:diamond", 6);

        RddPlanGuard.Filtered out = RddPlanGuard.filterSubtasks(plan, "通关MC", held);

        assertEquals(1, out.allowed().size());
        assertTrue(out.allowed().stream()
                        .noneMatch(s -> "minecraft:bookshelf".equals(s.condition().get("asset_key"))),
                "书架是违规项，不得因为「其余步骤全已持有」而被放回执行");
    }

    @Test
    void realVillageSupplyAndTradingSurviveActualStageAPostProcessing() throws Exception {
        String json;
        try (var in = getClass().getResourceAsStream("/real-win-stage-a-20261111.json")) {
            assertNotNull(in);
            json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var raw = RddStagePlanner.parse(json);
        assertEquals(9, raw.size());
        var guarded = RddPlanGuard.filterStages(raw, "通关MC");
        assertTrue(guarded.dropped().isEmpty());
        assertEquals(raw, guarded.allowed());
        var prepared = RddRiskPlanning.ensurePrepStage(
                RddRiskPlanning.ensureSmeltPrepStage(guarded.allowed(), Map.of()), 12);
        var planned = RddRiskPlanning.injectWaitFor(prepared, Map.of());
        int home = -1, trading = -1, iron = -1;
        for (int i = 0; i < planned.size(); i++) {
            String d = planned.get(i).description();
            if (d.startsWith("安家：")) home = i;
            if (d.startsWith("基地收口与交易雏形")) trading = i;
            if (d.startsWith("铁装：")) iron = i;
        }
        assertTrue(home >= 0 && iron > home && trading > iron);
        assertTrue(home < java.util.stream.IntStream.range(0, planned.size())
                .filter(i -> planned.get(i).description().equals(RddRiskPlanning.PREP_STAGE_DESC))
                .findFirst().orElse(planned.size()), "generic safety prep must not replace or precede preserved home supply");
        var chain = com.dwinovo.numen.rdd.core.RddChainFactory.fromStages(java.util.UUID.randomUUID(), "通关MC", planned);
        assertEquals(planned.stream().map(PrimarySpec::description).toList(),
                chain.primaryGoals().stream().map(p -> p.description()).toList());
    }

    @Test
    void basicVillagersAndEquipmentDoNotUnlockLargeAutomation() {
        String prompt = RddPlanningPolicy.block("通关MC", "stage_a");
        assertTrue(prompt.contains("基本村庄供食、安家、交易与装备准备"));
        assertFalse(RddPlanningPolicy.deferredOptional("通关MC").contains("村民交易体系"));
        var stages = List.of(new PrimarySpec("收村民农田、全套铁甲、基本交易所", List.of()),
                new PrimarySpec("大型红石自动化刷铁机", List.of()));
        var guarded = RddPlanGuard.filterStages(stages, "通关MC");
        assertEquals(List.of(stages.get(0)), guarded.allowed());
        assertEquals(1, guarded.dropped().size());
    }

    @Test
    void genericBedAcquisitionUsesExistingFamilyWithoutBindingRespawn() {
        var raw = new SubtaskSpec("在村里或周边弄到一张床（村里房子直接拿现成床最好；没有就用 3 羊毛 + 3 木板合成白色床，羊毛来自羊或村庄羊毛）",
                Map.of("asset_key", "minecraft:white_bed", "minimum", 1), null, NodeType.MAIN, null);
        // Exercise the production JSON parser before normalization and evaluator.
        var jsonStep = new com.google.gson.JsonObject();
        jsonStep.addProperty("description", raw.description());
        var jsonCondition = new com.google.gson.JsonObject();
        jsonCondition.addProperty("asset_key", "minecraft:white_bed");
        jsonCondition.addProperty("minimum", 1);
        jsonStep.add("condition", jsonCondition);
        var jsonSteps = new com.google.gson.JsonArray();
        jsonSteps.add(jsonStep);
        var jsonPlan = new com.google.gson.JsonObject();
        jsonPlan.add("subtasks", jsonSteps);
        var parsed = RddDecomposer.parse(jsonPlan.toString());
        assertEquals(1, parsed.size());
        var normalized = RddPlanGuard.normalizeConditions(parsed).get(0);
        assertEquals("minecraft:bed", normalized.condition().get("asset_key"));
        assertTrue(com.dwinovo.numen.rdd.core.HardCodedEvaluator.matches(
                normalized.condition(), Map.of("minecraft:blue_bed", 1)));
        assertFalse(com.dwinovo.numen.rdd.core.HardCodedEvaluator.matches(normalized.condition(), Map.of()));
        var bind = new SubtaskSpec("放床并真正睡过，绑定重生点", Map.of("type", "base"), null);
        assertEquals(bind, RddPlanGuard.normalizeCondition(bind));
        assertFalse(com.dwinovo.numen.rdd.core.HardCodedEvaluator.matches(
                bind.condition(), Map.of("minecraft:blue_bed", 1)), "holding a bed does not prove bound respawn");
        var white = spec("获取一张白色床供染色", "minecraft:white_bed", 1);
        assertEquals(white, RddPlanGuard.normalizeCondition(white));
        var yellow = spec("获取黄色的一张床", "minecraft:yellow_bed", 1);
        assertEquals(yellow, RddPlanGuard.normalizeCondition(yellow));
        var black = spec("获取黑色的一张床", "minecraft:black_bed", 1);
        assertEquals(black, RddPlanGuard.normalizeCondition(black));
        var craft = spec("合成一张床", "minecraft:white_bed", 1);
        assertEquals(craft, RddPlanGuard.normalizeCondition(craft));
    }

    // ---------- Stage-A 主题过滤 ----------

    @Test
    void 未要求时可选工程阶段被整条丢弃() {
        List<PrimarySpec> stages = List.of(
                new PrimarySpec("铁器时代：两把铁镐与盾牌", List.of()),
                new PrimarySpec("生电：刷铁机与村民交易所", List.of()),
                new PrimarySpec("下界之门：黑曜石与打火石", List.of()));

        RddPlanGuard.Stages out = RddPlanGuard.filterStages(stages, "通关MC");

        assertEquals(2, out.allowed().size());
        assertEquals(1, out.dropped().size());
        assertTrue(out.dropped().get(0).contains("生电"));
    }

    @Test
    void 明确要求生电时阶段放行() {
        List<PrimarySpec> stages = List.of(new PrimarySpec("生电：刷铁机与村民交易所", List.of()));

        RddPlanGuard.Stages out = RddPlanGuard.filterStages(stages, "帮我建生电和村民交易所");

        assertEquals(1, out.allowed().size());
        assertTrue(out.dropped().isEmpty());
    }

    @Test
    void 未要求时全部可选分支必须返回空阶段表用于安全降级() {
        // 回归：模型只给了可选分支，绝不能被「别清空」的保护放回执行
        List<PrimarySpec> stages = List.of(
                new PrimarySpec("生电：刷铁机", List.of()),
                new PrimarySpec("大型自动化交易所", List.of()));

        RddPlanGuard.Stages out = RddPlanGuard.filterStages(stages, "通关MC");

        assertTrue(out.allowed().isEmpty(), "全为违规可选分支时必须返回空表，交给上游降级");
        assertEquals(2, out.dropped().size(), "丢弃原因要如实记录");
        assertTrue(out.dropped().stream().allMatch(d -> d.contains("可选分支未获要求")));
    }

    @Test
    void 可选分支与必要阶段混合时只丢可选() {
        List<PrimarySpec> stages = List.of(
                new PrimarySpec("生电：刷铁机", List.of()),
                new PrimarySpec("下界之门：黑曜石与打火石", List.of()));

        RddPlanGuard.Stages out = RddPlanGuard.filterStages(stages, "通关MC");

        assertEquals(1, out.allowed().size());
        assertEquals("下界之门：黑曜石与打火石", out.allowed().get(0).description());
    }

    // ---------- 7：原有路径不被破坏 ----------

    @Test
    void 正常计划原样通过() {
        List<SubtaskSpec> plan = List.of(
                spec("开采黑曜石", "minecraft:obsidian", 4),
                spec("合成附魔台", "minecraft:enchanting_table", 1));

        RddPlanGuard.Filtered out = RddPlanGuard.filterSubtasks(plan, "通关MC", Map.of());

        assertEquals(2, out.allowed().size(), "普通附魔台是必要前置，不该被当成可选升级丢掉");
        assertTrue(out.dropped().isEmpty());
        assertTrue(out.reused().isEmpty());
    }

    @Test
    void 空输入不炸() {
        assertTrue(RddPlanGuard.filterSubtasks(null, "通关MC", null).allowed().isEmpty());
        assertTrue(RddPlanGuard.filterSubtasks(List.of(), "通关MC", Map.of()).allowed().isEmpty());
        assertTrue(RddPlanGuard.filterStages(null, "通关MC").allowed().isEmpty());
        assertTrue(RddPlanGuard.filterStages(List.of(), "通关MC").allowed().isEmpty());
        assertTrue(RddPlanGuard.filterStages(List.of(), "通关MC").dropped().isEmpty());
    }

    @Test
    void filterPreservesNodeTypeAndRecurrence() {
        SubtaskSpec daily = new SubtaskSpec("每日收麦补种",
                Map.of("asset_key", "minecraft:wheat", "minimum", 1), null,
                NodeType.DAILY, RecurrenceSpec.daily(true));
        var filtered = RddPlanGuard.filterSubtasks(List.of(daily), "维护农田", Map.of("minecraft:wheat", 5));
        assertEquals(1, filtered.allowed().size(), filtered.dropped().toString());
        assertEquals(NodeType.DAILY, filtered.allowed().get(0).nodeType(), "守门器不得把 DAILY 归一成 MAIN");
        assertNotNull(filtered.allowed().get(0).recurrence());
    }

    @Test void harvestWaitNodeDowngradesToDaily() {
        SubtaskSpec harvest = new SubtaskSpec("收割小麦到 9",
                Map.of("asset_key", "minecraft:wheat", "minimum", 9), null, NodeType.MAIN, null);
        var filtered = RddPlanGuard.filterSubtasks(List.of(harvest), "维护农田", Map.of());
        assertEquals(1, filtered.allowed().size(), filtered.dropped().toString());
        assertEquals(NodeType.DAILY, filtered.allowed().get(0).nodeType(), "等待/收获类不得留在主线");
        assertNotNull(filtered.allowed().get(0).recurrence());
        assertTrue(filtered.allowed().get(0).recurrence().requiresFarmBuilt());
    }


    // V22 original request 9f04f1d9-5d82-4f1f-88e9-1c3fce9eb43d, response
    // call_b510429a428c47dea6d26514 / new-rdd L58; exact dropped step in L60.
    // This isolated consumer counterexample does not replay the live plan or change the parser's eight-step cap.
    private static final String V22_FOOD_PREPARATION = "食物回补：就地打猎（牛/猪/鸡）或捡掉落，烤熟后随身带 ≥4 个熟肉。背包空着进下界等于送死，这条不补满不进烈焰人阶段。";

    @Test void v22FoodPreparationWhyMustNotLoseItsRealPredicateBeforeTheLegalTail() {
        var food = parsePreparationStep(V22_FOOD_PREPARATION, Map.of("type", "inventory", "group", "food", "minimum", 4));
        var tail = parsePreparationStep("补足火把供地表照明", Map.of("asset_key", "minecraft:torch", "minimum", 8));
        assertTrue(RddPlanGuard.predicateGaps(food).isEmpty(), "the original food predicate is valid, not the reason for the drop");
        var guarded = RddPlanGuard.filterSubtasks(List.of(food, tail), "下界取烈焰棒与末影珍珠", Map.of());
        var chain = installPreparationPlan(guarded.allowed());
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(List.of(food, tail), guarded.allowed(), "preparation WHY is not a present dangerous action: " + guarded.dropped()),
                () -> assertTrue(guarded.dropped().isEmpty(), guarded.dropped().toString()),
                () -> assertEquals(V22_FOOD_PREPARATION, chain.currentSubtask().description(), "do not install the dependent tail without its food preparation"),
                () -> assertEquals(food.condition(), chain.currentSubtask().condition()),
                () -> assertFalse(com.dwinovo.numen.rdd.core.HardCodedEvaluator.matches(food.condition(), Map.of("minecraft:cooked_beef", 3))),
                () -> assertTrue(com.dwinovo.numen.rdd.core.HardCodedEvaluator.matches(food.condition(), Map.of("minecraft:cooked_beef", 4))),
                () -> assertTrue(chain.satisfiedStages().isEmpty(), "publishing preparation is not completing it"));
    }

    @Test void immediateNetherCombatStillCannotInstallWithoutArmor() {
        var step = parsePreparationStep("现在进入下界要塞击杀烈焰人取得烈焰棒", Map.of("asset_key", "minecraft:blaze_rod", "minimum", 1));
        var guarded = RddPlanGuard.filterSubtasks(List.of(step), "通关MC", Map.of());
        var chain = installPreparationPlan(guarded.allowed());
        assertTrue(guarded.allowed().isEmpty());
        assertTrue(guarded.dropped().stream().anyMatch(reason -> reason.contains("护甲")));
        assertTrue(chain.currentPrimary().unexpanded());
        assertTrue(chain.satisfiedStages().isEmpty());
    }

    @Test void foodWordAndFoodPredicateDoNotDisguiseAnImmediateDangerousAction() {
        var step = parsePreparationStep("食物回补：现在进入下界要塞击杀烈焰人，顺手捡食物", Map.of("type", "inventory", "group", "food", "minimum", 4));
        var guarded = RddPlanGuard.filterSubtasks(List.of(step), "通关MC", Map.of());
        assertTrue(guarded.allowed().isEmpty(), "neither a food keyword nor its predicate grants unarmored combat");
        assertTrue(guarded.dropped().stream().anyMatch(reason -> reason.contains("护甲")));
        assertTrue(installPreparationPlan(guarded.allowed()).currentPrimary().unexpanded());
    }

    @Test void explicitSafeFoodPreparationCanInstallBeforeOwningArmor() {
        var step = parsePreparationStep("不要进入下界，先在主世界地表取食物补给", Map.of("type", "inventory", "group", "food", "minimum", 4));
        var guarded = RddPlanGuard.filterSubtasks(List.of(step), "通关MC", Map.of());
        assertEquals(List.of(step), guarded.allowed(), guarded.dropped().toString());
        var chain = installPreparationPlan(guarded.allowed());
        assertEquals(step.description(), chain.currentSubtask().description());
        assertEquals(step.condition(), chain.currentSubtask().condition());
        assertTrue(chain.satisfiedStages().isEmpty());
    }


    @Test void riskExplanationMustNotHideActualDangerOrInstallOnlyItsTail() {
        for (String description : List.of(
                "先在安全处补充粮食。空手进入下界意味着死亡。现在进入下界要塞击杀烈焰人，顺手捡食物",
                "食物回补：现在进入下界要塞击杀烈焰人等于送死")) {
            var mixed = parsePreparationStep(description, Map.of("type", "inventory", "group", "food", "minimum", 4));
            var tail = parsePreparationStep("补足火把供地表照明", Map.of("asset_key", "minecraft:torch", "minimum", 8));
            var guarded = RddPlanGuard.filterSubtasks(List.of(mixed, tail), "通关MC", Map.of());
            assertTrue(guarded.allowed().isEmpty(), "ambiguous dangerous preparation must return the batch, not only drop food: " + guarded.dropped());
            assertTrue(guarded.dropped().stream().anyMatch(reason -> reason.contains("护甲")));
            assertTrue(installPreparationPlan(guarded.allowed()).currentPrimary().unexpanded());
        }
    }

    @Test void explicitRiskReasonCannotReenterThroughTheSharedRiskClassifier() {
        var safe = parsePreparationStep("先在主世界安全处补充粮食。空手进入下界意味着死亡。补齐前不要进入下界。",
                Map.of("type", "inventory", "group", "food", "minimum", 4));
        var guarded = RddPlanGuard.filterSubtasks(List.of(safe), "通关MC", Map.of());
        assertEquals(List.of(safe), guarded.allowed(), guarded.dropped().toString());
        var chain = installPreparationPlan(guarded.allowed());
        assertEquals(safe.description(), chain.currentSubtask().description());
        assertEquals(safe.condition(), chain.currentSubtask().condition());
        assertTrue(chain.satisfiedStages().isEmpty());
    }


    @Test void movementBeforeMissingArmorReasonCannotBeRemovedAsPreparationWhy() {
        var dangerous = parsePreparationStep("食物回补：先前往下界要塞取食物但没有护甲等于送死",
                Map.of("type", "inventory", "group", "food", "minimum", 4));
        var tail = parsePreparationStep("补足火把供地表照明", Map.of("asset_key", "minecraft:torch", "minimum", 8));
        var guarded = RddPlanGuard.filterSubtasks(List.of(dangerous, tail), "通关MC", Map.of());
        assertTrue(guarded.allowed().isEmpty(), "the explicit travel action must remain, and its food step cannot be dropped alone");
        assertTrue(guarded.dropped().stream().anyMatch(reason -> reason.contains("护甲")));
        assertTrue(installPreparationPlan(guarded.allowed()).currentPrimary().unexpanded());
    }

    @Test void travelAfterSafePreparationStillCannotBorrowItsMissingArmorReason() {
        var dangerous = parsePreparationStep("先在主世界补充食物。前往下界要塞取食物但没有护甲意味着死亡",
                Map.of("type", "inventory", "group", "food", "minimum", 4));
        var guarded = RddPlanGuard.filterSubtasks(List.of(dangerous), "通关MC", Map.of());
        assertTrue(guarded.allowed().isEmpty(), "a later explicit travel action is not a lack-subject risk explanation");
        assertTrue(guarded.dropped().stream().anyMatch(reason -> reason.contains("护甲")));
        assertTrue(installPreparationPlan(guarded.allowed()).currentPrimary().unexpanded());
    }

    // V25 new-rdd:144/146, request 99c0911a-1730-401a-899a-0ad58bb541b8: food for a later trip, not travel now.
    @Test void v25RestockingForALaterNetherTripMustNotDropFoodTwelveBeforeTheLegalTail() {
        String description = "把食物补到能支撑一整个下界/找要塞来回的量：至少 12 份直接可食用的东西（生肉也计，后面可以在基地熔炉烤熟再吃；小麦/种子不算）。来源按优先级：基地与村庄箱子里的存货 → 夜里或水边打猎（牛/羊/猪/鸡/鲑鱼）→ 现成农田收获后补种。起步攒到 12 份就走下一步，别在野外无限狩猎。";
        var food = parsePreparationStep(description, Map.of("group", "food", "minimum", 12));
        var tail = parsePreparationStep("补足火把供地表照明", Map.of("asset_key", "minecraft:torch", "minimum", 8));
        assertTrue(RddPlanGuard.predicateGaps(food).isEmpty(), "the recorded food predicate is valid, not a missing tool or uncovered action");
        var guarded = RddPlanGuard.filterSubtasks(List.of(food, tail), "末地准备与定位要塞", Map.of("minecraft:salmon", 2));
        var chain = installPreparationPlan(guarded.allowed());
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(List.of(food, tail), guarded.allowed(), "food restocking for a later trip must not require armor: " + guarded.dropped()),
                () -> assertTrue(guarded.dropped().isEmpty(), guarded.dropped().toString()),
                () -> assertEquals(description, chain.currentSubtask().description(), "do not advance to the legal tail by silently deleting food12"),
                () -> assertEquals(food.condition(), chain.currentSubtask().condition(), "retain the original group-food minimum12 predicate"),
                () -> assertEquals(NodeType.MAIN, chain.currentSubtask().nodeType()),
                () -> assertFalse(com.dwinovo.numen.rdd.core.HardCodedEvaluator.matches(food.condition(), Map.of("minecraft:salmon", 11))),
                () -> assertTrue(com.dwinovo.numen.rdd.core.HardCodedEvaluator.matches(food.condition(), Map.of("minecraft:salmon", 12))),
                () -> assertTrue(chain.satisfiedStages().isEmpty(), "admitting preparation is not verifying food or completing its theme"));
    }

    @Test void futureTripQuantityCannotHideCurrentTravelInTheSameOrFollowingClause() {
        for (String description : List.of(
                "把食物补到能支撑下界/找要塞来回的量：至少12份，现在进入下界要塞击杀烈焰人",
                "把食物补到能支撑下界/找要塞来回的量：至少12份然后出发进入下界",
                "把食物补到能支撑立即进入下界来回的量：至少12份")) {
            var mixed = parsePreparationStep(description, Map.of("group", "food", "minimum", 12));
            var tail = parsePreparationStep("补足火把供地表照明", Map.of("asset_key", "minecraft:torch", "minimum", 8));
            var guarded = RddPlanGuard.filterSubtasks(List.of(mixed, tail), "通关MC", Map.of());
            assertTrue(guarded.allowed().isEmpty(), "future-purpose wording cannot authorize unarmored current travel: " + description);
            assertTrue(guarded.dropped().stream().anyMatch(reason -> reason.contains("护甲")));
            assertTrue(installPreparationPlan(guarded.allowed()).currentPrimary().unexpanded());
        }
    }

    @Test void oneTripStockQuotaWithAHarvestSourceStaysMainButRepeatedCareRemainsDaily() {
        var quota = parsePreparationStep("把食物补到本趟出行所需的量：至少 8 份。来源优先箱子存货，也可在现成农田收获后补种。起步攒到 8 份就走下一步。",
                Map.of("group", "food", "minimum", 8));
        var prepared = RddPlanGuard.filterSubtasks(List.of(quota), "通关MC", Map.of());
        assertEquals(List.of(quota), prepared.allowed(), "an alternative farm source does not change the one-trip inventory gate");
        var recurring = parsePreparationStep("把食物补到每天出行所需的量：至少 8 份，每日收割成熟作物后补种。攒到 8 份就走下一步。",
                Map.of("group", "food", "minimum", 8));
        var daily = RddPlanGuard.filterSubtasks(List.of(recurring), "维护农田", Map.of()).allowed();
        assertEquals(1, daily.size());
        assertEquals(NodeType.DAILY, daily.getFirst().nodeType(), "a quota must not erase explicit recurring care");
        assertEquals(RecurrenceSpec.daily(true), daily.getFirst().recurrence());
    }

    @Test void boundedVillageBreadQuotaWithMatureWheatSourceMustStayMainAndBlockItsTail() {
        String description = "补给到村级交易所需的量：把村里干草块/农田收获物拆合成面包，手上+基地箱里的面包凑到 9 个（3 只村民各 3 个用于促繁殖）。上一轮 4 个面包已吃/已计，不再纠缠旧计数；这里用「当前持有的面包」口径，缺 8 个，靠村内干草块与收成熟小麦补，不够时到村内其他房屋箱子找面包/小麦。不要在野外另开食物工程。";
        var food = parsePreparationStep(description, Map.of("group", "food", "minimum", 9));
        var tail = parsePreparationStep("补足火把供地表照明", Map.of("asset_key", "minecraft:torch", "minimum", 8));
        assertEquals(NodeType.MAIN, food.nodeType(), "the actual parser receives an explicit MAIN node");
        var guarded = RddPlanGuard.filterSubtasks(List.of(food, tail), "村级交易所", Map.of());
        assertEquals(2, guarded.allowed().size(), guarded.dropped().toString());
        var chain = installPreparationPlan(guarded.allowed());
        var installed = chain.currentPrimary().subtasks();
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertTrue(guarded.dropped().isEmpty(), guarded.dropped().toString()),
                () -> assertTrue(guarded.reused().isEmpty(), "this counterexample does not pretend the food quota is already held"),
                () -> assertEquals(List.of(food, tail), guarded.allowed(), "a finite bread quota must retain its original description, condition and MAIN type"),
                () -> assertEquals(NodeType.MAIN, guarded.allowed().getFirst().nodeType(), "harvesting is only one source for this bounded supply step"),
                () -> assertNull(guarded.allowed().getFirst().recurrence(), "do not invent recurring work from a mature-wheat source"),
                () -> assertEquals(description, chain.currentSubtask().description(), "the legal tail must wait for the original bread step"),
                () -> assertEquals(food.condition(), installed.getFirst().condition(), "preserve group-food minimum9 without rewriting the predicate"),
                () -> assertEquals(NodeType.MAIN, installed.getFirst().nodeType()),
                () -> assertEquals(SubtaskStatus.RUNNING, chain.subtaskStatuses().get(installed.getFirst().id()), "bread preparation must not become DAILY/SKIPPED"),
                () -> assertEquals(SubtaskStatus.PENDING, chain.subtaskStatuses().get(installed.get(1).id()), "do not start the tail before the food gate"),
                () -> assertTrue(chain.satisfiedStages().isEmpty(), "installing a quota is not satisfying it"));
    }

    @Test void boundedVillageBreadQuotaDoesNotOverrideExplicitDailyOrRepeatedMaintenance() {
        for (String description : List.of(
                "补给到村级交易所需的量：把面包凑到 9 个，每日收成熟小麦后补种。",
                "补给到村级交易所需的量：把面包凑到 9 个，反复收成熟小麦后补种。")) {
            var food = parsePreparationStep(description, Map.of("group", "food", "minimum", 9));
            var guarded = RddPlanGuard.filterSubtasks(List.of(food), "维护农田", Map.of());
            assertTrue(guarded.dropped().isEmpty(), guarded.dropped().toString());
            assertEquals(1, guarded.allowed().size(), description);
            var daily = guarded.allowed().getFirst();
            assertEquals(NodeType.DAILY, daily.nodeType(), "a finite quota must not erase explicit recurring care: " + description);
            assertEquals(RecurrenceSpec.daily(true), daily.recurrence(), description);
            assertEquals(description, daily.description());
            assertEquals(food.condition(), daily.condition());
        }
    }

    @Test void boundedWheatQuotaForBreedingAlsoRetainsItsOriginalMainPredicate() {
        var quota = parsePreparationStep("补给到羊圈繁殖所需的量：把小麦凑到 18 个。靠现有农田收成熟小麦补齐，不另开农田。",
                Map.of("asset_key", "minecraft:wheat", "minimum", 18));
        var guarded = RddPlanGuard.filterSubtasks(List.of(quota), "繁殖准备", Map.of());
        assertTrue(guarded.dropped().isEmpty(), guarded.dropped().toString());
        assertEquals(List.of(quota), guarded.allowed(), "finite supply classification must not depend on bread or a particular quantity");
        assertEquals(NodeType.MAIN, guarded.allowed().getFirst().nodeType());
        assertNull(guarded.allowed().getFirst().recurrence());
    }

    @Test void supplyPurposeWithoutAnExplicitQuotaOrWithEverydayCareStillBecomesDaily() {
        for (String description : List.of(
                "补给到羊圈繁殖所需的量：把小麦凑到够用。收成熟小麦后补种。",
                "补给到羊圈繁殖所需的量：把小麦凑到 18 个，每天收成熟小麦后补种。")) {
            var step = parsePreparationStep(description, Map.of("asset_key", "minecraft:wheat", "minimum", 18));
            var guarded = RddPlanGuard.filterSubtasks(List.of(step), "维护农田", Map.of());
            assertTrue(guarded.dropped().isEmpty(), guarded.dropped().toString());
            assertEquals(1, guarded.allowed().size(), description);
            var daily = guarded.allowed().getFirst();
            assertEquals(NodeType.DAILY, daily.nodeType(), "supply purpose alone must not bypass recurring harvest classification: " + description);
            assertEquals(RecurrenceSpec.daily(true), daily.recurrence(), description);
            assertEquals(description, daily.description());
            assertEquals(step.condition(), daily.condition());
        }
    }

    private static SubtaskSpec parsePreparationStep(String description, Map<String, Object> condition) {
        String json = new com.google.gson.Gson().toJson(Map.of("subtasks", List.of(Map.of(
                "description", description, "condition", condition, "node_type", "MAIN"))));
        var parsed = RddDecomposer.parse(json);
        assertEquals(1, parsed.size(), "use the actual decomposer parser, not an ideal SubtaskSpec");
        return parsed.getFirst();
    }

    private static com.dwinovo.numen.rdd.core.TaskChain installPreparationPlan(List<SubtaskSpec> accepted) {
        var primary = com.dwinovo.numen.rdd.api.PrimaryGoal.unexpanded("food-prepare-p", "下界取烈焰棒与末影珍珠", List.of());
        var chain = new com.dwinovo.numen.rdd.core.TaskChain(new com.dwinovo.numen.rdd.api.Goal("food-prepare-g", "通关MC", List.of(primary)));
        if (!accepted.isEmpty()) {
            var injection = RddGoalDriver.buildInjection(primary.id(), chain.nextPlanRevision(), accepted);
            RddGoalDriver.acceptPlan(injection, ignored -> {}, () -> chain.expandCurrentPrimary(injection));
            chain.activateCurrent(Map.of());
        }
        return chain;
    }

    @Test void initialFarmingConstructionStaysMain() {
        SubtaskSpec construction = new SubtaskSpec("开垦并初始播种",
                Map.of("asset_key", "minecraft:wheat_seeds", "minimum", 1), null, NodeType.MAIN, null);
        var filtered = RddPlanGuard.filterSubtasks(List.of(construction), "建农田", Map.of());
        assertEquals(1, filtered.allowed().size(), filtered.dropped().toString());
        assertEquals(NodeType.MAIN, filtered.allowed().get(0).nodeType(), "一次性建设仍为主线");
    }

    // ---------- 2026-11-17 V02：六步计划被 schema/guard 缩成木材前缀 ----------

    /** 冻结的 V02 Stage-B 原始六步 JSON：四个 item_crafted 用 asset_key 会被 schema 拒，采石步会被 guard 拒。 */
    private static final String V02_SIX_STEP_JSON = "{\"subtasks\": [{\"description\": \"徒手砍针叶林附近的树，攒够原木（起步 3 个即可推进；看到就多砍几棵，够做木镐+石镐+石斧共约 5~6 个原木就停）\", \"condition\": {\"type\": \"inventory\", \"group\": \"wood\", \"minimum\": 6}, \"node_type\": \"MAIN\"}, {\"description\": \"把原木在 2x2 手搓成木板，再把木板竖排 2 个搓木棍（需工作台时才放工作台）\", \"condition\": {\"type\": \"item_crafted\", \"asset_key\": \"minecraft:stick\", \"minimum\": 4}, \"node_type\": \"MAIN\"}, {\"description\": \"用木板 3 + 木棍 2 在工作台 3x3 里合成木镐\", \"condition\": {\"type\": \"item_crafted\", \"asset_key\": \"minecraft:wooden_pickaxe\", \"minimum\": 1}, \"node_type\": \"MAIN\"}, {\"description\": \"用木镐在附近地表/裸露岩面挖 6 个圆石（正好够石镐+石斧，不多挖）\", \"condition\": {\"type\": \"block_mined\", \"block\": \"minecraft:stone\", \"minimum\": 6}, \"node_type\": \"MAIN\"}, {\"description\": \"圆石 3 + 木棍 2 合成石镐\", \"condition\": {\"type\": \"item_crafted\", \"asset_key\": \"minecraft:stone_pickaxe\", \"minimum\": 1}, \"node_type\": \"MAIN\"}, {\"description\": \"圆石 3 + 木棍 2 合成石斧；石镐石斧一到手即停手，不再砍树挖石，下一步立刻出发找村庄\", \"condition\": {\"type\": \"item_crafted\", \"asset_key\": \"minecraft:stone_axe\", \"minimum\": 1}, \"node_type\": \"MAIN\"}]}";

    @Test
    void requiredStepsMustNotVanishIntoWoodOnlyPrefix() {
        var parsed = RddStageBSpecParser.parseDetailed(V02_SIX_STEP_JSON);
        assertEquals(2, parsed.specs().size(), "schema 保留木材与采石两步");
        assertEquals(4, parsed.skippedRequired().size(), "四个 item_crafted 用 asset_key 的 MAIN 步是必需丢弃");
        var normalized = RddPlanGuard.normalizeConditions(parsed.specs());
        var filtered = RddPlanGuard.filterSubtasks(normalized,
                "立足开局：徒手砍树取原木→木板→木棍→木镐；用木镐在附近地表挖6个石头，做出石镐+石斧即停手",
                Map.of());
        assertTrue(RddDecomposer.stageBPublished(parsed, filtered).isEmpty(),
                "必需步骤被丢弃后不能把木材前缀当整级发布");
    }

    // ---------- 2026-11-17 V02 正控：合法计划/可选裁剪/DAILY/已持有复用 ----------

    @Test
    void fullyLegalPlanStillPublishes() {
        var parsed = RddStageBSpecParser.parseDetailed("{\"subtasks\":[{\"description\":\"砍树取原木\",\"condition\":{\"type\":\"inventory\",\"group\":\"wood\",\"minimum\":6},\"node_type\":\"MAIN\"},{\"description\":\"合成木棍\",\"condition\":{\"type\":\"item_crafted\",\"item\":\"minecraft:stick\",\"minimum\":4},\"node_type\":\"MAIN\"}]}");
        assertTrue(parsed.skippedRequired().isEmpty());
        var filtered = RddPlanGuard.filterSubtasks(RddPlanGuard.normalizeConditions(parsed.specs()), "石器开局", Map.of());
        assertEquals(2, RddDecomposer.stageBPublished(parsed, filtered).size());
    }

    @Test
    void optionalUpgradeTrimIsStillLegal() {
        var parsed = RddStageBSpecParser.parseDetailed("{\"subtasks\":[{\"description\":\"砍树取原木\",\"condition\":{\"type\":\"inventory\",\"group\":\"wood\",\"minimum\":6},\"node_type\":\"MAIN\"},{\"description\":\"合成钻石镐\",\"condition\":{\"type\":\"item_crafted\",\"item\":\"minecraft:diamond_pickaxe\",\"minimum\":1},\"node_type\":\"MAIN\"}]}");
        var filtered = RddPlanGuard.filterSubtasks(RddPlanGuard.normalizeConditions(parsed.specs()), "石器开局", Map.of());
        assertEquals(1, RddDecomposer.stageBPublished(parsed, filtered).size(), "未获要求的可选升级仍可裁剪");
    }

    @Test
    void dailySkipIsNotARequiredDrop() {
        var parsed = RddStageBSpecParser.parseDetailed("{\"subtasks\":[{\"description\":\"砍树取原木\",\"condition\":{\"type\":\"inventory\",\"group\":\"wood\",\"minimum\":6},\"node_type\":\"MAIN\"},{\"description\":\"每日巡检农田\",\"condition\":{\"type\":\"item_crafted\",\"asset_key\":\"minecraft:wheat\",\"minimum\":1},\"node_type\":\"DAILY\"}]}");
        assertTrue(parsed.skippedRequired().isEmpty(), "DAILY 的 schema 跳过不是必需丢弃");
        var filtered = RddPlanGuard.filterSubtasks(RddPlanGuard.normalizeConditions(parsed.specs()), "石器开局", Map.of());
        assertEquals(1, RddDecomposer.stageBPublished(parsed, filtered).size());
    }

    @Test
    void heldAssetsAreReusedNotRequiredDrops() {
        var parsed = RddStageBSpecParser.parseDetailed("{\"subtasks\":[{\"description\":\"合成木棍\",\"condition\":{\"type\":\"inventory\",\"asset_key\":\"minecraft:stick\",\"minimum\":4},\"node_type\":\"MAIN\"}]}");
        var filtered = RddPlanGuard.filterSubtasks(RddPlanGuard.normalizeConditions(parsed.specs()), "石器开局", Map.of("minecraft:stick", 4));
        assertEquals(1, RddDecomposer.stageBPublished(parsed, filtered).size(), "已持有复用仍发布");
    }
}
