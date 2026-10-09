package com.dwinovo.numen.settlement.core.logic;

import com.dwinovo.numen.settlement.core.model.CellKey;
import com.dwinovo.numen.settlement.core.model.ConstructionProgress;
import com.dwinovo.numen.settlement.core.model.ConstructionStatus;
import com.dwinovo.numen.settlement.core.model.DimAnchor;
import com.dwinovo.numen.settlement.core.model.FacilityKind;
import com.dwinovo.numen.settlement.core.model.FacilityRecord;
import com.dwinovo.numen.settlement.core.model.FacilityRegistry;
import com.dwinovo.numen.settlement.core.model.FacilityTemplate;
import com.dwinovo.numen.settlement.core.model.PlatformPlan;
import com.dwinovo.numen.settlement.core.model.ProductionState;
import com.dwinovo.numen.settlement.core.model.Verdict;
import com.dwinovo.numen.settlement.core.TestData;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 模板目录（第 ② 条）与放置账本/续建（第 ④ 条）。 */
class TemplateCatalogAndLedgerTest {

    private static final String DIM = TestData.DIM;

    // ── 目录 ─────────────────────────────────────────────────────────

    @Test
    void catalogHasTheFourShippedTemplates() {
        assertEquals(4, TemplateCatalog.all().size());
        assertTrue(TemplateCatalog.has("pen_basic"));
        assertTrue(TemplateCatalog.has("core_house"));
        assertTrue(TemplateCatalog.has("farm_basic"));
        assertTrue(TemplateCatalog.has("platform_basic"));
    }

    @Test
    void footprintIsRoundedUpNotEqualToBlueprintSize() {
        // 用户那张表：牧场 5×5→1 格、核心屋 7×7→2 格、农田 9×9→2 格
        assertEquals(1, TemplateCatalog.byId("pen_basic").orElseThrow().footprintCellsX());
        assertEquals(2, TemplateCatalog.byId("core_house").orElseThrow().footprintCellsX());
        assertEquals(2, TemplateCatalog.byId("farm_basic").orElseThrow().footprintCellsX());
        for (FacilityTemplate t : TemplateCatalog.all()) {
            assertTrue(t.footprintFits(TemplateCatalog.DEFAULT_CELL_SIZE),
                    t.id() + " 的占地必须容得下自己的蓝图尺寸");
        }
    }

    @Test
    void maturitySeparatesFileFromFullyAutomated() {
        // 用户要求：不要把"能生成文件"和"能完整自动建成"混为一谈
        assertEquals(FacilityTemplate.Maturity.READY,
                TemplateCatalog.byId("pen_basic").orElseThrow().maturity());
        assertEquals(FacilityTemplate.Maturity.PARTIAL,
                TemplateCatalog.byId("core_house").orElseThrow().maturity());
        assertEquals(FacilityTemplate.Maturity.STRUCTURE_ONLY,
                TemplateCatalog.byId("farm_basic").orElseThrow().maturity());

        FacilityTemplate farm = TemplateCatalog.byId("farm_basic").orElseThrow();
        assertTrue(farm.needsWater(), "农田必须标注灌水未自动化");
        assertFalse(farm.unfinishedSteps().isEmpty());
    }

    @Test
    void penEntranceIsAFenceGateNotCarpet() {
        // 2026-10-09 实机：地毯门对我们的假玩家不可站，默认入口改栅栏门
        FacilityTemplate pen = TemplateCatalog.byId("pen_basic").orElseThrow();
        assertTrue(pen.materials().stream()
                        .anyMatch(m -> m.itemId().contains("fence_gate")),
                "牧场必须带一道栅栏门");
        assertFalse(pen.materials().stream()
                        .anyMatch(m -> m.itemId().contains("carpet")),
                "不应再用地毯当门");
    }

    @Test
    void anchorYOffsetDiffersPerTemplateBecauseGeneratorsDiffer() {
        // 实测三个生成器约定不同，必须显式声明
        assertEquals(0, TemplateCatalog.byId("pen_basic").orElseThrow().anchorYOffset());
        assertEquals(0, TemplateCatalog.byId("core_house").orElseThrow().anchorYOffset());
        assertEquals(-1, TemplateCatalog.byId("farm_basic").orElseThrow().anchorYOffset());
        // 平台 y=0 是最深填充层（floorY-depth）
        assertEquals(-1, TemplateCatalog.byId("platform_basic").orElseThrow().anchorYOffset());
        // 平台蓝图高 = depth 1 + clear 4 + 1
        assertEquals(6, TemplateCatalog.byId("platform_basic").orElseThrow().sizeY());
    }

    @Test
    void fittingFiltersByAvailableCells() {
        // 1×1 格能放：牧场、平台（各占 1 格）；核心屋/农田要 2×2
        assertEquals(2, TemplateCatalog.fitting(1, 1).size());
        assertEquals(4, TemplateCatalog.fitting(2, 2).size());
        assertEquals(0, TemplateCatalog.fitting(0, 0).size());
    }

    @Test
    void materialGapReportsShortfallOnly() {
        FacilityTemplate pen = TemplateCatalog.byId("pen_basic").orElseThrow();
        // 5×5 栅栏圈周长 16 格：中间一格换栅栏门 → 15 栅栏 + 1 门
        Map<String, Integer> gap = TemplateCatalog.materialGap(pen, Map.of("minecraft:oak_fence", 10));
        assertEquals(5, gap.get("minecraft:oak_fence"));
        assertEquals(1, gap.get("minecraft:oak_fence_gate"));
        assertTrue(TemplateCatalog.materialGap(pen,
                Map.of("minecraft:oak_fence", 15, "minecraft:oak_fence_gate", 1)).isEmpty());
    }

    @Test
    void materialListsMatchTheRealGenerators() {
        // 料单必须与生成器实际产出的格数一致，否则玩家照单备料却建不完
        FacilityTemplate pen = TemplateCatalog.byId("pen_basic").orElseThrow();
        assertEquals(15, pen.materials().stream()
                .filter(m -> m.itemId().endsWith("oak_fence")).findFirst().orElseThrow().count());
        assertEquals(16, pen.totalMaterialCount(), "周长 16 格 = 15 栅栏 + 1 门");

        // 7×7 屋：地板 49 + 顶 49 + 墙 46（南中留门）= 144 木板
        FacilityTemplate house = TemplateCatalog.byId("core_house").orElseThrow();
        assertEquals(144, house.materials().stream()
                .filter(m -> m.itemId().endsWith("oak_planks")).findFirst().orElseThrow().count());

        // 9×9 农田：81 支撑 + 80 耕地（耕地按泥土记账）= 161 泥土
        FacilityTemplate farm = TemplateCatalog.byId("farm_basic").orElseThrow();
        assertEquals(161, farm.materials().stream()
                .filter(m -> m.itemId().endsWith("dirt")).findFirst().orElseThrow().count());
    }

    @Test
    void unknownTemplateIsEmpty() {
        assertTrue(TemplateCatalog.byId("nope").isEmpty());
        assertTrue(TemplateCatalog.byId(null).isEmpty());
    }

    // ── 去重 / 续建 ──────────────────────────────────────────────────

    private static FacilityRecord tracked(String id, ConstructionStatus status,
                                          int completed, int total, String template,
                                          DimAnchor anchor, int rotation) {
        ConstructionProgress c = new ConstructionProgress(completed, 0, 0, total, 1L, status,
                template, anchor, "t-1", Map.of(), null);
        return new FacilityRecord(id, "owner", "base", DIM, FacilityKind.PASTURE_SHEEP,
                com.dwinovo.numen.settlement.core.model.BlockBox.of(5, 64, 5, 9, 69, 9),
                rotation, null, null, null, List.of(), List.of(), null, null,
                List.of(), List.of(), List.of(), Map.of(),
                c, Verdict.UNKNOWN, ProductionState.UNKNOWN, 1L, null);
    }

    @Test
    void sameIdTwiceDoesNotCreateASecondFacility() {
        // 用户点名：同一个放置请求不能生成两座同名建筑
        String id = PlacementLedger.facilityId("base", "pen_basic", "A2");
        FacilityRegistry reg = new FacilityRegistry().with(tracked(id, ConstructionStatus.BUILDING,
                3, 20, "pen_basic", DimAnchor.of(DIM, 5, 65, 5), 0));

        PlacementLedger.Decision d = PlacementLedger.decide(reg, id);
        assertEquals(PlacementLedger.Disposition.RESUME, d.disposition());
        assertNotNull(d.existing());
        assertTrue(d.disposition().canStart());
    }

    @Test
    void completedFacilityRejectsRepeatPlacement() {
        String id = PlacementLedger.facilityId("base", "pen_basic", "A2");
        FacilityRegistry reg = new FacilityRegistry().with(tracked(id, ConstructionStatus.COMPLETE,
                20, 20, "pen_basic", DimAnchor.of(DIM, 5, 65, 5), 0));
        PlacementLedger.Decision d = PlacementLedger.decide(reg, id);
        assertEquals(PlacementLedger.Disposition.ALREADY_COMPLETE, d.disposition());
        assertFalse(d.disposition().canStart(), "已收口不许重复建造");
    }

    @Test
    void failedNeedsExplicitResume() {
        String id = PlacementLedger.facilityId("base", "pen_basic", "A2");
        FacilityRegistry reg = new FacilityRegistry().with(tracked(id, ConstructionStatus.FAILED,
                5, 20, "pen_basic", DimAnchor.of(DIM, 5, 65, 5), 0));
        assertEquals(PlacementLedger.Disposition.FAILED_NEEDS_RESUME,
                PlacementLedger.decide(reg, id).disposition());
    }

    @Test
    void untrackedManualRegistrationIsNotSilentlyOverwritten() {
        FacilityRegistry reg = new FacilityRegistry().with(
                TestData.facility("base_pen_basic_a2", FacilityKind.PASTURE_SHEEP,
                        com.dwinovo.numen.settlement.core.model.BlockBox.of(5, 64, 5, 9, 69, 9)));
        PlacementLedger.Decision d = PlacementLedger.decide(reg, "base_pen_basic_a2");
        assertEquals(PlacementLedger.Disposition.ALREADY_COMPLETE, d.disposition());
        assertFalse(d.disposition().canStart());
    }

    @Test
    void facilityIdIsDeterministicAndStable() {
        String a = PlacementLedger.facilityId("base", "pen_basic", "A2");
        String b = PlacementLedger.facilityId("base", "pen_basic", "A2");
        String c = PlacementLedger.facilityId("base", "pen_basic", "A3");
        assertEquals(a, b, "同一(基地,模板,格)必须永远同一个 id");
        assertFalse(a.equals(c));
        assertTrue(a.matches("[a-z0-9_-]{1,64}"), "id 必须合法: " + a);
    }

    @Test
    void resumeMismatchDetectsChangedAnchor() {
        // 续建必须沿用原锚点；基地基准变了要拒绝而不是错位建
        FacilityTemplate pen = TemplateCatalog.byId("pen_basic").orElseThrow();
        PlatformPlan plan = PlatformPlan.square25("base", DIM, 0, 64, 0);
        PlacementMath.PlacementPlan resolved = PlacementMath.resolve(plan, CellKey.of(1, 1), pen, 0);

        FacilityRecord ok = tracked("x", ConstructionStatus.BUILDING, 3, 20, "pen_basic",
                resolved.anchor(), 0);
        assertTrue(PlacementLedger.resumeMismatches(ok, pen, resolved).isEmpty());

        FacilityRecord moved = tracked("x", ConstructionStatus.BUILDING, 3, 20, "pen_basic",
                DimAnchor.of(DIM, 100, 65, 100), 0);
        assertFalse(PlacementLedger.resumeMismatches(moved, pen, resolved).isEmpty(),
                "锚点变了必须拒绝续建");

        FacilityRecord rotated = tracked("x", ConstructionStatus.BUILDING, 3, 20, "pen_basic",
                resolved.anchor(), 1);
        assertFalse(PlacementLedger.resumeMismatches(rotated, pen, resolved).isEmpty(),
                "朝向变了必须拒绝续建");
    }

    @Test
    void openLedgerIsImmediatelyOpenSoRestartShowsBuildingNotEmpty() {
        // 用户点名：开工前就登记为施工中，否则重启后半成品被当地图上的空地
        FacilityTemplate pen = TemplateCatalog.byId("pen_basic").orElseThrow();
        PlatformPlan plan = PlatformPlan.square25("base", DIM, 0, 64, 0);
        PlacementMath.PlacementPlan resolved = PlacementMath.resolve(plan, CellKey.of(1, 1), pen, 0);
        ConstructionProgress opened = PlacementLedger.openLedger(pen, resolved, "t-9", 20, 5L);
        assertTrue(opened.isOpen(), "登记即开账");
        assertEquals(ConstructionStatus.PLANNED, opened.status());
        assertEquals("pen_basic", opened.template());
        assertEquals(resolved.anchor(), opened.anchor());
        assertEquals(20, opened.total());
    }
}
