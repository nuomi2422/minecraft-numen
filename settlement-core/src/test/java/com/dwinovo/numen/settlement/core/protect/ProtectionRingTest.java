package com.dwinovo.numen.settlement.core.protect;

import com.dwinovo.numen.settlement.core.TestData;
import com.dwinovo.numen.settlement.core.model.BlockBox;
import com.dwinovo.numen.settlement.core.model.FacilityKind;
import com.dwinovo.numen.settlement.core.model.FacilityRecord;
import com.dwinovo.numen.settlement.core.model.FacilityRegistry;
import com.dwinovo.numen.settlement.core.model.ProtectionZone;
import com.dwinovo.numen.settlement.core.model.ZoneKind;
import com.dwinovo.numen.settlement.core.protect.ProtectionRing.Side;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 037/T4 第一轮 · ZONE-01：保护环只有一个生成器，且"门能拆、墙不能拆"要按判决断言，
 * 不是只数 zone 个数。
 *
 * <p>占地统一用 5x5：x/z = 0..4，墙高 y = 64..65。
 */
class ProtectionRingTest {

    private static final BlockBox AREA = BlockBox.of(0, 64, 0, 4, 65, 4);
    private static final int Y0 = 64;
    private static final int Y1 = 65;

    private static FacilityRegistry registryOf(List<ProtectionZone> zones) {
        FacilityRecord f = TestData.facility("home", FacilityKind.HOUSE, AREA, zones, List.of(), null, null,
                com.dwinovo.numen.settlement.core.model.ConstructionProgress.unknown());
        return FacilityRegistry.of(List.of(f));
    }

    private static Decision breakAt(FacilityRegistry reg, int x, int y, int z) {
        return ProtectionRules.decideBreak(reg, TestData.DIM, x, y, z, List.of(), 0L);
    }

    @Test
    void allFourSidesLeaveTheEntranceBreakableAndWallsProtected() {
        record Case(Side side, int gate, int gx, int gz, int wallX, int wallZ) {
        }
        List<Case> cases = List.of(
                new Case(Side.SOUTH, 2, 2, 4, 0, 4),
                new Case(Side.NORTH, 2, 2, 0, 0, 0),
                new Case(Side.EAST, 2, 4, 2, 4, 0),
                new Case(Side.WEST, 2, 0, 2, 0, 0));
        for (Case c : cases) {
            ProtectionRing.Result r = ProtectionRing.ring(AREA, Y0, Y1, c.side(), c.gate());
            assertTrue(r.ok(), c.side() + " 不该被拒绝: " + r.rejection());
            FacilityRegistry reg = registryOf(r.zones());
            assertEquals(Decision.ALLOW, breakAt(reg, c.gx(), Y0, c.gz()),
                    c.side() + " 的门格必须能拆");
            assertEquals(Decision.DENY, breakAt(reg, c.wallX() == c.gx() ? c.gx() : c.wallX(), Y0, c.wallZ()),
                    c.side() + " 的墙体必须仍在保护内");
        }
    }

    @Test
    void gateCellIsOnlySpaceNeverStructure() {
        for (Side side : Side.values()) {
            ProtectionRing.Result r = ProtectionRing.ring(AREA, Y0, Y1, side, 2);
            assertTrue(r.ok(), side + ": " + r.rejection());
            long structureHits = r.zones().stream()
                    .filter(z -> z.kind() == ZoneKind.STRUCTURE)
                    .filter(z -> z.box().contains(gateX(side), Y0, gateZ(side)))
                    .count();
            assertEquals(0, structureHits, side + " 的门格被 STRUCTURE 覆盖了 = 通道永远打不开");
            assertEquals(1, r.zones().stream().filter(z -> z.kind() == ZoneKind.SPACE).count());
        }
    }

    @Test
    void cornerGateIsStillBreakable() {
        // 门压在南墙左上角 (0,*,4)：西墙在 z=4 的那一格必须让开
        ProtectionRing.Result r = ProtectionRing.ring(AREA, Y0, Y1, Side.SOUTH, 0);
        assertTrue(r.ok(), String.valueOf(r.rejection()));
        FacilityRegistry reg = registryOf(r.zones());
        assertEquals(Decision.ALLOW, breakAt(reg, 0, Y0, 4), "角上的门格必须能拆");
        assertEquals(Decision.DENY, breakAt(reg, 0, Y0, 3), "角门旁边的墙还得管");
        assertEquals(Decision.DENY, breakAt(reg, 1, Y0, 4), "门旁边那格墙也得管");
    }

    @Test
    void oneBlockWideFootprintIsRejectedInsteadOfNegativeBoxes() {
        BlockBox thin = BlockBox.of(0, 64, 0, 0, 65, 4);
        ProtectionRing.Result r = ProtectionRing.ring(thin, Y0, Y1, Side.SOUTH, 0);
        assertFalse(r.ok(), "1 格宽的占地做不出环，必须明确拒绝");
        assertNotNull(r.rejection());
        assertTrue(r.zones().isEmpty());
    }

    @Test
    void entranceOutsideTheSpanIsRejected() {
        ProtectionRing.Result r = ProtectionRing.ring(AREA, Y0, Y1, Side.SOUTH, 9);
        assertFalse(r.ok());
        assertTrue(r.rejection().contains("outside"), r.rejection());
        assertTrue(ProtectionRing.ring(AREA, 65, 64, Side.SOUTH, 2).rejection() != null, "高度区间反了也要拒绝");
        assertTrue(ProtectionRing.ring(null, Y0, Y1, Side.SOUTH, 2).rejection() != null);
    }

    @Test
    void heightIsTakenFromTheCallerNotSilentlyUnified() {
        ProtectionRing.Result low = ProtectionRing.ring(AREA, 60, 61, Side.SOUTH, 2);
        assertTrue(low.ok());
        assertTrue(low.zones().stream().allMatch(z -> z.box().minY() == 60 && z.box().maxY() == 61),
                "墙高必须照传入值，不能悄悄换成别的口径");

        ProtectionRing.Result tall = ProtectionRing.ring(AREA, 64, 80, Side.SOUTH, 2);
        assertTrue(tall.ok());
        assertTrue(tall.zones().stream().allMatch(z -> z.box().minY() == 64 && z.box().maxY() == 80));
    }

    @Test
    void overlappingFacilityKeepsItsOwnStructureProtection() {
        ProtectionRing.Result a = ProtectionRing.ring(BlockBox.of(0, 64, 0, 4, 65, 4), Y0, Y1, Side.SOUTH, 2);
        ProtectionRing.Result b = ProtectionRing.ring(BlockBox.of(0, 64, 8, 4, 65, 12), Y0, Y1, Side.NORTH, 2);
        FacilityRecord fa = TestData.facility("a", FacilityKind.HOUSE, BlockBox.of(0, 64, 0, 4, 65, 4),
                a.zones(), List.of(), null, null, com.dwinovo.numen.settlement.core.model.ConstructionProgress.unknown());
        FacilityRecord fb = TestData.facility("b", FacilityKind.HOUSE, BlockBox.of(0, 64, 8, 4, 65, 12),
                b.zones(), List.of(), null, null, com.dwinovo.numen.settlement.core.model.ConstructionProgress.unknown());
        FacilityRegistry reg = FacilityRegistry.of(List.of(fa, fb));
        // B 的北墙门在 x=2，所以 (0,*,8) 是墙、(2,*,8) 是门：别人的结构保护不该被"A 也在场"影响
        assertEquals(Decision.DENY, breakAt(reg, 0, Y0, 8), "B 的墙不能因为 A 也在场就放开");
        assertEquals(Decision.ALLOW, breakAt(reg, 2, Y0, 8), "B 自己的门仍可拆");
        assertEquals(Decision.ALLOW, breakAt(reg, 2, Y0, 4), "A 自己的门仍可拆");
    }

    @Test
    void grantLetsTheBuildWorkAndExpiresBackToDeny() {
        ProtectionRing.Result r = ProtectionRing.ring(AREA, Y0, Y1, Side.SOUTH, 2);
        FacilityRegistry reg = registryOf(r.zones());
        BlockBox work = BlockBox.of(0, Y0, 4, 4, Y1, 4);
        Grant live = Grant.expiring("home", TestData.DIM, work, 10_000L);
        Grant expired = Grant.expiring("home", TestData.DIM, work, 1_000L);

        assertEquals(Decision.ALLOW,
                ProtectionRules.decideBreak(reg, TestData.DIM, 1, Y0, 4, List.of(live), 5_000L),
                "授权有效期内可以改自己的墙");
        assertEquals(Decision.DENY,
                ProtectionRules.decideBreak(reg, TestData.DIM, 1, Y0, 4, List.of(expired), 5_000L),
                "授权过期后持久保护必须回来");
        assertEquals(Decision.DENY,
                ProtectionRules.decideBreak(reg, TestData.DIM, 1, Y0, 4, List.of(live), 20_000L),
                "过了过期时刻同样要拒");
    }

    @Test
    void placingBlocksIntoTheGateIsStillRefused() {
        ProtectionRing.Result r = ProtectionRing.ring(AREA, Y0, Y1, Side.SOUTH, 2);
        FacilityRegistry reg = registryOf(r.zones());
        assertEquals(Decision.DENY,
                ProtectionRules.decidePlaceObstruction(reg, TestData.DIM, 2, Y0, 4, true, List.of(), 0L),
                "通道可以拆开，但不许堵");
        assertEquals(Decision.ALLOW,
                ProtectionRules.decidePlaceObstruction(reg, TestData.DIM, 2, Y0, 4, false, List.of(), 0L),
                "不挡路的东西（火把）放进去可以");
    }

    @Test
    void noZoneIsInvertedOrEmpty() {
        for (Side side : Side.values()) {
            ProtectionRing.Result r = ProtectionRing.ring(AREA, Y0, Y1, side, 2);
            assertTrue(r.ok());
            assertTrue(r.zones().stream().allMatch(z ->
                    z.box().sizeX() >= 1 && z.box().sizeY() >= 1 && z.box().sizeZ() >= 1), side + " 生成了空盒");
        }
        assertNull(ProtectionRing.ring(AREA, Y0, Y1, Side.SOUTH, 2).rejection());
    }

    private static int gateX(Side side) {
        return switch (side) {
            case EAST -> 4;
            case WEST -> 0;
            default -> 2;
        };
    }

    private static int gateZ(Side side) {
        return switch (side) {
            case NORTH -> 0;
            case SOUTH -> 4;
            default -> 2;
        };
    }
}