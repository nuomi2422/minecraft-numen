package com.dwinovo.numen.settlement.core.logic;

import com.dwinovo.numen.settlement.core.TestData;
import com.dwinovo.numen.settlement.core.model.BlockBox;
import com.dwinovo.numen.settlement.core.model.CellKey;
import com.dwinovo.numen.settlement.core.model.DimAnchor;
import com.dwinovo.numen.settlement.core.model.FacilityKind;
import com.dwinovo.numen.settlement.core.model.FacilityRecord;
import com.dwinovo.numen.settlement.core.model.FacilityRegistry;
import com.dwinovo.numen.settlement.core.model.FacilityTemplate;
import com.dwinovo.numen.settlement.core.model.PlatformPlan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 放置坐标数学与校验：用户点名的第 ① 条（坐标映射/旋转/重叠/越界/入口空间）。 */
class PlacementTest {

    private static final String DIM = TestData.DIM;

    /** 原点 (0,64,0)、5×5 格、每格 5、无间隙 → 25×25 平台。 */
    private static PlatformPlan plan() {
        return PlatformPlan.square25("base", DIM, 0, 64, 0);
    }

    private static FacilityTemplate pen() {
        return TemplateCatalog.byId("pen_basic").orElseThrow();
    }

    // ── 坐标映射 ─────────────────────────────────────────────────────

    @Test
    void cellToWorldCoordinatesAreDeterministic() {
        PlacementMath.PlacementPlan p = PlacementMath.resolve(plan(), CellKey.of(1, 1), pen(), 0);
        // B2 = cx=1,cz=1 → x0=5, z0=5；牧场 5×5 占 1 格 → 5..9
        assertEquals(5, p.footprintBox().minX());
        assertEquals(5, p.footprintBox().minZ());
        assertEquals(9, p.footprintBox().maxX());
        assertEquals(9, p.footprintBox().maxZ());
        // 锚点 Y：各生成器约定不同，牧场 y=0 是栅栏、就落在 floorY 这一层（实测 pen_y=68 = base floorY）
        assertEquals(64, p.anchor().y());
        assertEquals(5, p.anchor().x());
        assertEquals(5, p.anchor().z());
    }

    @Test
    void footprintRoundsUpForNonMultiples() {
        // 7×7 核心屋占 2×2 格（10×10），多出的位置是留白
        FacilityTemplate house = TemplateCatalog.byId("core_house").orElseThrow();
        assertTrue(house.footprintFits(5), "7x7 必须能放进 2x2 格（10x10）");
        assertEquals(2, house.footprintCellsX());
        assertEquals(2, house.footprintCellsZ());

        PlacementMath.PlacementPlan p = PlacementMath.resolve(plan(), CellKey.of(0, 0), house, 0);
        assertEquals(0, p.footprintBox().minX());
        assertEquals(9, p.footprintBox().maxX(), "占 2 格 = 10 宽");
        assertEquals(6, p.structureBox().maxX(), "但蓝图只有 7 宽");
    }

    @Test
    void occupiedCellsCoverFootprint() {
        FacilityTemplate house = TemplateCatalog.byId("core_house").orElseThrow();
        List<CellKey> cells = PlacementMath.occupiedCells(plan(), CellKey.of(1, 2), house);
        assertEquals(4, cells.size());
        assertTrue(cells.contains(CellKey.of(1, 2)));
        assertTrue(cells.contains(CellKey.of(2, 3)));
    }

    @Test
    void footprintBoxYFollowsTheTemplateAnchorOffset() {
        // ★ 2026-10-09 实机抓到的缺陷：农田/平台的蓝图 y=0 在 floorY-1（支撑泥土层），
        //   而登记范围原先一律从 floorY 起 → 最底下那层落在保护区与验收之外。
        //   农田在 floorY=64 上应登记 63..64；牧场（偏移 0）应登记 64..65。
        PlacementMath.PlacementPlan farm = PlacementMath.resolve(plan(), CellKey.of(0, 0),
                TemplateCatalog.byId("farm_basic").orElseThrow(), 0);
        assertEquals(63, farm.footprintBox().minY(), "农田支撑层在 floorY-1");
        assertEquals(64, farm.footprintBox().maxY());
        assertEquals(farm.anchor().y(), farm.footprintBox().minY(), "范围底 = 锚点 y");

        PlacementMath.PlacementPlan pen = PlacementMath.resolve(plan(), CellKey.of(0, 0), pen(), 0);
        assertEquals(64, pen.footprintBox().minY(), "牧场栅栏就在 floorY");
        assertEquals(65, pen.footprintBox().maxY());
    }

    // ── 旋转 ─────────────────────────────────────────────────────────

    @Test
    void rotationSwapsSizesAndMovesEntrance() {
        FacilityTemplate pen = pen();   // 5×5，入口在北侧中间 (2,0)
        PlacementMath.PlacementPlan r0 = PlacementMath.resolve(plan(), CellKey.of(0, 0), pen, 0);
        // rotation 的单位是「顺时针四分之一圈」，不是角度（宿主把 90° 换算成 1）
        PlacementMath.PlacementPlan r90 = PlacementMath.resolve(plan(), CellKey.of(0, 0), pen, 1);

        // 正方形旋转后尺寸不变，但入口必须挪到东侧：局部 (2,0) → (4,2)
        assertEquals(r0.footprintBox().minX(), r90.footprintBox().minX());
        assertEquals(0 + 4, r90.entranceInside().x(), "顺时针 90° 后门在东侧");
        assertEquals(0 + 2, r90.entranceInside().z());
        assertEquals(0 + 2, r0.entranceInside().x(), "未旋转时门在北侧中间");
        assertEquals(0 + 0, r0.entranceInside().z());
    }

    @Test
    void rotationNormalizesAndNonSquareSwapsFootprint() {
        assertEquals(0, RotationMath.normalizeQuarters(4));
        assertEquals(1, RotationMath.normalizeQuarters(5));
        assertEquals(3, RotationMath.normalizeQuarters(-1));

        // 7×4×7 的屋旋转 90° → 外廓变 7(x=z) × 7 不变；用 3×2 的假模板验证互换
        assertEquals(2, RotationMath.rotatedSizeX(3, 2, 1));
        assertEquals(3, RotationMath.rotatedSizeZ(3, 2, 1));
        assertEquals(3, RotationMath.rotatedSizeX(3, 2, 2));
    }

    @Test
    void rotationIsClockwiseConsistentForAllQuarters() {
        // 局部 (0,0) 在 4×6 盒里：顺时针 90° → (5,0)，180° → (3,5)，270° → (0,3)
        assertEquals(5, RotationMath.rotateLocal(0, 0, 4, 6, 1).x());
        assertEquals(0, RotationMath.rotateLocal(0, 0, 4, 6, 1).z());
        assertEquals(3, RotationMath.rotateLocal(0, 0, 4, 6, 2).x());
        assertEquals(5, RotationMath.rotateLocal(0, 0, 4, 6, 2).z());
        assertEquals(0, RotationMath.rotateLocal(0, 0, 4, 6, 3).x());
        assertEquals(3, RotationMath.rotateLocal(0, 0, 4, 6, 3).z());
    }

    // ── 校验：越界 / 重叠 / 入口 ──────────────────────────────────────

    @Test
    void acceptsAFreeCell() {
        PlacementValidator.Result r = PlacementValidator.validate(plan(), CellKey.of(1, 1),
                pen(), 0, new FacilityRegistry(), DIM);
        assertTrue(r.ok(), "空格必须放行: " + r.rejects());
    }

    @Test
    void rejectsOutOfBoundsBeforeTouchingWorld() {
        // 2×2 格的屋放在最后一格 → 越界
        FacilityTemplate house = TemplateCatalog.byId("core_house").orElseThrow();
        PlacementValidator.Result r = PlacementValidator.validate(plan(), CellKey.of(4, 4),
                house, 0, new FacilityRegistry(), DIM);
        assertFalse(r.ok());
        assertTrue(r.rejects().stream().anyMatch(s -> s.startsWith("OUT_OF_BOUNDS")), r.rejects().toString());
    }

    @Test
    void rejectsOverlapWithExistingFacility() {
        FacilityRegistry reg = new FacilityRegistry().with(TestData.facility(
                "pen_a", FacilityKind.PASTURE_SHEEP, BlockBox.of(5, 64, 5, 9, 69, 9)));
        PlacementValidator.Result r = PlacementValidator.validate(plan(), CellKey.of(1, 1),
                pen(), 0, reg, DIM);
        assertFalse(r.ok(), "重叠必须拒绝");
        assertTrue(r.rejects().stream().anyMatch(s -> s.startsWith("OVERLAP")), r.rejects().toString());
    }

    @Test
    void adjacentButNotOverlappingIsAccepted() {
        // 用户点名的第一版规则：相邻独立摆放要允许（a b 各自有边界和 ID）
        FacilityRegistry reg = new FacilityRegistry().with(TestData.facility(
                "pen_a", FacilityKind.PASTURE_SHEEP, BlockBox.of(0, 64, 0, 4, 69, 4)));
        PlacementValidator.Result r = PlacementValidator.validate(plan(), CellKey.of(1, 0),
                pen(), 0, reg, DIM);
        assertTrue(r.ok(), "相邻不重叠必须放行: " + r.rejects());
    }

    @Test
    void rejectsWhenEntranceClearanceIsBlocked() {
        // 牧场入口在北侧；北边一格被别的设施占住 → 门被堵
        FacilityRegistry reg = new FacilityRegistry().with(TestData.facility(
                "blocker", FacilityKind.HOUSE, BlockBox.of(5, 64, 0, 9, 69, 4)));
        PlacementValidator.Result r = PlacementValidator.validate(plan(), CellKey.of(1, 1),
                pen(), 0, reg, DIM);
        assertFalse(r.ok(), "入口被堵必须拒绝");
        assertTrue(r.rejects().stream().anyMatch(s -> s.startsWith("ENTRANCE_BLOCKED")), r.rejects().toString());
    }

    @Test
    void resumeIgnoresItsOwnOverlap() {
        FacilityRegistry reg = new FacilityRegistry().with(TestData.facility(
                "pen_b_basic_a2", FacilityKind.PASTURE_SHEEP, BlockBox.of(5, 64, 5, 9, 69, 9)));
        // 不忽略 → 拒绝
        assertFalse(PlacementValidator.validate(plan(), CellKey.of(1, 1), pen(), 0, reg, DIM).ok());
        // 忽略自己 → 放行（续建）
        assertTrue(PlacementValidator.validate(plan(), CellKey.of(1, 1), pen(), 0, reg, DIM,
                "pen_b_basic_a2").ok());
    }

    @Test
    void rejectsCellAlreadyClaimedByRegistryCells() {
        FacilityRecord claiming = new FacilityRecord(
                "claimer", "owner", "base", DIM, FacilityKind.GENERIC,
                BlockBox.of(20, 64, 20, 24, 69, 24), 0, null, null, null,
                List.of(), List.of(), null, null, List.of(), List.of(),
                List.of(CellKey.of(1, 1)), java.util.Map.of(),
                null, null, null, 0L, null);
        PlacementValidator.Result r = PlacementValidator.validate(plan(), CellKey.of(1, 1),
                pen(), 0, new FacilityRegistry().with(claiming), DIM);
        assertFalse(r.ok(), "格已被登记占用必须拒绝");
        assertTrue(r.rejects().stream().anyMatch(s -> s.startsWith("CELL_TAKEN")), r.rejects().toString());
    }

    // ── 入口站位 ─────────────────────────────────────────────────────

    @Test
    void entranceOutsideIsOneCellBeyondInside() {
        PlacementMath.PlacementPlan p = PlacementMath.resolve(plan(), CellKey.of(1, 1), pen(), 0);
        DimAnchor in = p.entranceInside();
        DimAnchor out = p.entranceOutside();
        assertEquals(in.x(), out.x());
        assertEquals(in.z() - 1, out.z(), "门朝北，门外站位在北边一格");
    }

    @Test
    void clearanceBoxExtendsAwayFromEntrance() {
        PlacementMath.PlacementPlan p = PlacementMath.resolve(plan(), CellKey.of(1, 1), pen(), 0);
        BlockBox c = PlacementMath.clearanceBox(p, pen());
        assertEquals(p.entranceOutside().z(), c.minZ());
        assertEquals(1, c.sizeZ(), "clearanceOutside=1 → 一格");
    }

    @Test
    void clearanceBoxFollowsTheRotatedFacingNotTheDeclaredOne() {
        // ★ 2026-10-09 发现的缺陷：净空条原先拿模板声明的 entranceFacing（旋转前）去算，
        //   而 entranceOutside 已经旋转过 → 旋转 90° 后门在东侧、净空条却仍朝北延伸，
        //   于是"入口被堵"判在错的一侧（漏报真堵 / 误报假堵）。
        FacilityTemplate pen = pen();   // 声明朝北
        PlacementMath.PlacementPlan r0 = PlacementMath.resolve(plan(), CellKey.of(0, 0), pen, 0);
        PlacementMath.PlacementPlan r90 = PlacementMath.resolve(plan(), CellKey.of(0, 0), pen, 1);

        BlockBox c0 = PlacementMath.clearanceBox(r0, pen);
        assertEquals(1, c0.sizeZ(), "未旋转：净空沿 Z 方向一格");
        assertEquals(1, c0.sizeX());
        // 门朝北 → 净空条在北侧（z 更小）
        assertEquals(r0.entranceOutside().z(), c0.minZ());
        assertTrue(c0.minZ() < r0.entranceInside().z(), "净空必须在门的朝外一侧");

        BlockBox c90 = PlacementMath.clearanceBox(r90, pen);
        // 顺时针 90°：北 → 东。净空条必须沿 +X 延伸，而不是继续沿 Z。
        assertEquals(1, c90.sizeZ(), "旋转后净空不该还在 Z 方向铺开");
        assertEquals(1, c90.sizeX(), "clearanceOutside=1 仍是单格");
        assertEquals(r90.entranceOutside().x(), c90.minX());
        assertTrue(c90.minX() > r90.entranceInside().x(), "旋转后净空必须在东侧（+X）");
    }
}
