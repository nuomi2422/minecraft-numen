package com.dwinovo.numen.core.tools;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryExtraOpsTest {

    @Test
    void eyeRayCountsOnlyDistinctSolidBlocksBetweenBothEndpoints() {
        Set<BlockPos> checked = new HashSet<>();
        int count = QueryExtraOps.countSolidBlocks(pos -> {
            checked.add(pos);
            return pos.equals(new BlockPos(2, 64, 0));
        }, new Vec3(0.1, 64.5, 0.5), new Vec3(3.9, 64.5, 0.5));

        assertEquals(1, count);
        assertEquals(Set.of(new BlockPos(1, 64, 0), new BlockPos(2, 64, 0),
                new BlockPos(3, 64, 0)), checked);
    }

    @Test
    void undergroundWallIsCountedOnTheVerticalSegment() {
        assertEquals(1, QueryExtraOps.countSolidBlocks(
                pos -> pos.getY() == 50,
                new Vec3(0.5, 64.0, 0.5), new Vec3(0.5, 40.0, 0.5)));
    }

    @Test
    void solidBlockContainingOurEyesIsNotBlamedAsCover() {
        assertEquals(3, QueryExtraOps.countSolidBlocks(pos -> true,
                new Vec3(0.5, 64.0, 0.5), new Vec3(3.5, 64.0, 0.5)));
    }

    @Test
    void longDiagonalRayStopsAfterThirtyTwoDistinctPositions() {
        Set<BlockPos> checked = new HashSet<>();
        int count = QueryExtraOps.countSolidBlocks(pos -> {
            checked.add(pos);
            return false;
        }, new Vec3(0.1, 64.1, 0.1), new Vec3(63.9, 127.9, 63.9));

        assertEquals(0, count);
        assertEquals(32, checked.size() + 1); // plus the skipped eye block
    }
}
