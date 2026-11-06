package com.dwinovo.numen.core.task.light;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LightDeficitPlannerTest {
    private static LightDeficitPlanner.World w(int light, boolean torch, boolean support, boolean replaceable, boolean loaded) {
        return new LightDeficitPlanner.World() {
            public boolean loaded(BlockPos p) { return loaded; }
            public int maxLocalRawBrightness(BlockPos p) { return light; }
            public boolean replaceable(BlockPos p) { return replaceable; }
            public boolean canSupportStandingTorch(BlockPos p) { return support; }
            public boolean alreadyTorch(BlockPos p) { return torch; }
        };
    }
    @Test void darkWithSupportPlanned() {
        var r = LightDeficitPlanner.plan(List.of(new BlockPos(1, 64, 1)), 4, w(4, false, true, true, true));
        assertEquals(1, r.lights().size());
    }
    @Test void brightOrExistingTorchSkipped() {
        assertTrue(LightDeficitPlanner.plan(List.of(new BlockPos(1,64,1)), 4, w(9,false,true,true,true)).lights().isEmpty());
        assertTrue(LightDeficitPlanner.plan(List.of(new BlockPos(1,64,1)), 4, w(4,true,true,true,true)).lights().isEmpty());
    }
    @Test void invalidSupportReplaceableLoadedSkipped() {
        assertTrue(LightDeficitPlanner.plan(List.of(new BlockPos(1,64,1)), 4, w(4,false,false,true,true)).lights().isEmpty());
        assertTrue(LightDeficitPlanner.plan(List.of(new BlockPos(1,64,1)), 4, w(4,false,true,false,true)).lights().isEmpty());
        assertTrue(LightDeficitPlanner.plan(List.of(new BlockPos(1,64,1)), 4, w(4,false,true,true,false)).lights().isEmpty());
    }
    @Test void budgetCaps() {
        var list = List.of(new BlockPos(0,64,0), new BlockPos(1,64,0), new BlockPos(2,64,0));
        var r = LightDeficitPlanner.plan(list, 2, w(3,false,true,true,true));
        assertEquals(2, r.lights().size());
        assertEquals("reached_max_lights", r.stopReason());
    }
}
