package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.PrimarySpec;
import com.dwinovo.numen.rdd.api.SubtaskSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                new PrimarySpec("村民交易所", List.of()));

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
}
