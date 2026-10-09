package com.dwinovo.numen.settlement.core.logic;

import com.dwinovo.numen.settlement.core.model.FacilityKind;
import com.dwinovo.numen.settlement.core.model.FacilityTemplate;
import com.dwinovo.numen.settlement.core.model.FacilityTemplate.MaterialNeed;
import com.dwinovo.numen.settlement.core.model.FacilityTemplate.Maturity;
import com.dwinovo.numen.settlement.core.model.LocalPoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 可放置模板的目录（纯声明，单一真源）。
 *
 * <p>用户点名的第 ② 条：把现有生成器包装成统一模板，声明占地、入口、材料和未完成步骤。
 * 目录<b>如实标注可用程度</b>——"能生成文件"不等于"能完整自动建成"：
 * 核心屋有封闭空间补全问题（{@code PARTIAL}），农田有灌水未自动化（{@code STRUCTURE_ONLY}）。
 *
 * <p>占地按<b>向上取整</b>：5×5→1 格、7×7→2 格、9×9→2 格（每格默认 5）。多出来的是留白/通行空间。
 *
 * <p><b>入口不用地毯</b>：2026-10-09 实机证实"栅栏+地毯"组合对我们的假玩家不可站
 * （TP 上去会落回栅栏层，没有可站面），所以默认入口是<b>栅栏门</b>（可开可关、人畜都按门走）。
 * 这与 {@code SettlementPenTool} 的旧地毯注释冲突，以实测为准。
 */
public final class TemplateCatalog {

    /** 每格默认边长（与 {@code SettlementService.BaseGrid} 默认一致）。 */
    public static final int DEFAULT_CELL_SIZE = 5;

    private static final Map<String, FacilityTemplate> BY_ID = new LinkedHashMap<>();

    static {
        // 牧场：5×5 栅栏圈，北侧中间留栅栏门。占 1 格。
        put(new FacilityTemplate(
                "pen_basic", "基础牧场（羊圈）", FacilityKind.PASTURE_SHEEP,
                5, 2, 5,
                1, 1,
                0,
                LocalPoint.of(2, 0), "north", 1,
                List.of(MaterialNeed.of("minecraft:oak_fence", 15),
                        MaterialNeed.of("minecraft:oak_fence_gate", 1)),
                Maturity.READY,
                List.of(),
                "栅栏围整圈 + 北侧中间一道栅栏门；动物引入另用引羊 AC。实测门可用、地毯门不可用。",
                penMarks()));

        // 核心屋：7×7 火柴盒（地板/墙/平顶/门/床箱台炉）。占 2×2 格。
        put(new FacilityTemplate(
                "core_house", "核心屋", FacilityKind.HOUSE,
                7, 4, 7,
                2, 2,
                0,
                LocalPoint.of(3, 0), "north", 1,
                List.of(MaterialNeed.of("minecraft:oak_planks", 144),
                        MaterialNeed.of("minecraft:oak_door", 1),
                        MaterialNeed.of("minecraft:crafting_table", 1),
                        MaterialNeed.of("minecraft:furnace", 1),
                        MaterialNeed.of("minecraft:chest", 1),
                        MaterialNeed.of("minecraft:white_bed", 1)),
                Maturity.PARTIAL,
                List.of("封闭空间内从里往外建会够不到远侧格（实测远墙 9 格 would not stay put）；需外墙先行或补漏 pass"),
                "地板/墙/平顶/门/床箱台炉一次生成；远侧墙可达性未解决，故标注为可试建与续建。",
                houseMarks()));

        // 农田：9×9 耕地 + 中心水孔。占 2×2 格。
        put(new FacilityTemplate(
                "farm_basic", "基础农田", FacilityKind.FARM,
                9, 2, 9,
                2, 2,
                -1,
                LocalPoint.of(4, 8), "south", 1,
                // 9×9 = 81 支撑泥土 + 80 格耕地；耕地没有自己的物品，core 的
                // BuildStates.overrideItem 把它按泥土记账 → 合计 161 件泥土。
                List.of(MaterialNeed.of("minecraft:dirt", 161)),
                Maturity.STRUCTURE_ONLY,
                List.of("中心水孔要另用桶灌水（蓝图跳过液体格）；未灌水耕地会退化"),
                "9×9 耕地 + 中心水孔；水必须另灌，所以未灌水前不能标成生产就绪。",
                farmMarks()));

        // 平台：整平地皮（填低削高 + 铺顶面）。占 1 格（按 5×5 计）。
        // anchorYOffset=-1：蓝图 y=0 是最深一层填充（floorY-depth），与放置代码给的
        // floor_y = anchor.y+1（depth=1）一致；写错会把平台埋进地里或架到半空。
        put(new FacilityTemplate(
                "platform_basic", "整平平台", FacilityKind.GENERIC,
                5, 6, 5,
                1, 1,
                -1,
                LocalPoint.of(2, 4), "south", 1,
                // 整平是动态的：按地形填低削高，实际用量看现场。这里给的是
                // size×size×depth 的<b>上界</b>（默认 5×5×5），不是确定值。
                List.of(MaterialNeed.of("minecraft:dirt", 125)),
                Maturity.READY,
                List.of("大范围整平受 32k 格与备料限制，大地皮要分块",
                        "料单是上界：整平按地形填低，实际用量看现场"),
                "用于准备地面；动态整平会填低削高，大范围需分块。料单为 depth 上界。",
                List.of()));   // 整平是地形相关的，没有固定的"应该有什么方块"
    }

    private TemplateCatalog() {}

    // ── 结构标记（世界侧核对用） ─────────────────────────────────────
    //
    // 采样而不是全量图纸：目的是判"这道围栏立起来了没 / 墙在不在"，不是重放整张蓝图。
    // 每个模板都取四角 + 门（最容易漏的地方），因为这些正是"建了一半"的典型缺口。

    private static final String FENCE = "minecraft:oak_fence";
    private static final String GATE = "minecraft:oak_fence_gate";

    /** 5×5 牧场：四角栅栏 + 门 + 每边中点栅栏。 */
    private static List<CompletionChecker.Mark> penMarks() {
        List<CompletionChecker.Mark> marks = new ArrayList<>();
        // 四角
        for (int[] c : new int[][]{{0, 0}, {4, 0}, {0, 4}, {4, 4}}) {
            marks.add(new CompletionChecker.Mark(c[0], 0, c[1], FENCE));
        }
        // 每边中点（门在北边中点）
        marks.add(new CompletionChecker.Mark(2, 0, 0, GATE));
        marks.add(new CompletionChecker.Mark(2, 0, 4, FENCE));
        marks.add(new CompletionChecker.Mark(0, 0, 2, FENCE));
        marks.add(new CompletionChecker.Mark(4, 0, 2, FENCE));
        return marks;
    }

    /** 7×7 核心屋：四角墙 + 门 + 屋顶中心 + 地板中心 + <b>四件家具</b>。 */
    private static List<CompletionChecker.Mark> houseMarks() {
        String planks = "minecraft:oak_planks";
        List<CompletionChecker.Mark> marks = new ArrayList<>();
        for (int[] c : new int[][]{{0, 0}, {6, 0}, {0, 6}, {6, 6}}) {
            marks.add(new CompletionChecker.Mark(c[0], 1, c[1], planks));
            marks.add(new CompletionChecker.Mark(c[0], 2, c[1], planks));
        }
        marks.add(new CompletionChecker.Mark(3, 1, 0, "minecraft:oak_door"));   // 南中门
        marks.add(new CompletionChecker.Mark(3, 3, 3, planks));                 // 平顶中心
        marks.add(new CompletionChecker.Mark(3, 0, 3, planks));                 // 地板中心
        // ★ 家具必须进标记：2026-10-09 实测 D3 核心屋报了「世界核对 11/11 COMPLETE」，
        //   而箱子其实<b>根本没落地</b>（背包里那件料也消耗了）——因为标记只采了结构，
        //   家具不在采样里，缺失就判不出来。用户要的是「还差什么」，家具漏了就是谎报完成。
        marks.add(new CompletionChecker.Mark(1, 1, 1, "minecraft:crafting_table"));
        marks.add(new CompletionChecker.Mark(5, 1, 1, "minecraft:furnace"));
        marks.add(new CompletionChecker.Mark(1, 1, 5, "minecraft:chest"));
        marks.add(new CompletionChecker.Mark(5, 1, 5, "minecraft:white_bed"));
        return marks;
    }

    /** 9×9 农田：中心水孔那格是空气（要另灌水），四角与四边中点应是泥土。 */
    private static List<CompletionChecker.Mark> farmMarks() {
        List<CompletionChecker.Mark> marks = new ArrayList<>();
        for (int[] c : new int[][]{{0, 0}, {8, 0}, {0, 8}, {8, 8}, {4, 0}, {0, 4}, {8, 4}, {4, 8}}) {
            marks.add(new CompletionChecker.Mark(c[0], 0, c[1], "minecraft:dirt"));
        }
        return marks;
    }

    private static void put(FacilityTemplate t) {
        BY_ID.put(t.id(), t);
    }

    public static List<FacilityTemplate> all() {
        return List.copyOf(BY_ID.values());
    }

    public static Optional<FacilityTemplate> byId(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(BY_ID.get(id.toLowerCase(java.util.Locale.ROOT).trim()));
    }

    public static boolean has(String id) {
        return byId(id).isPresent();
    }

    /**
     * 按占地格数筛出"能放进这些格"的模板（用户第一版只做相邻独立摆放，
     * 所以这里只是尺寸筛选，不做合并）。
     */
    public static List<FacilityTemplate> fitting(int availableCellsX, int availableCellsZ) {
        List<FacilityTemplate> out = new ArrayList<>();
        for (FacilityTemplate t : BY_ID.values()) {
            if (t.footprintCellsX() <= availableCellsX && t.footprintCellsZ() <= availableCellsZ) {
                out.add(t);
            }
        }
        return out;
    }

    /** 模板材料的缺口（对照给定库存），用于放置前的料单预检。 */
    public static Map<String, Integer> materialGap(FacilityTemplate template, Map<String, Integer> stock) {
        Map<String, Integer> gap = new LinkedHashMap<>();
        Map<String, Integer> have = stock == null ? Map.of() : stock;
        for (MaterialNeed need : template.materials()) {
            int owned = have.getOrDefault(need.itemId(), 0);
            if (owned < need.count()) {
                gap.put(need.itemId(), need.count() - owned);
            }
        }
        return gap;
    }
}
