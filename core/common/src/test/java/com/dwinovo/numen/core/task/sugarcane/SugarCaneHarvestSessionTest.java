package com.dwinovo.numen.core.task.sugarcane;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-07：留根/顶层优先/PROGRESSING/NO_SHOT 不续/取消/未加载/上限终态。 */
class SugarCaneHarvestSessionTest {
    private static final BlockPos BASE = new BlockPos(0, 64, 0);
    private static SugarCaneHarvestSession.World world(int height, boolean loaded) {
        return new SugarCaneHarvestSession.World() {
            public boolean loaded(BlockPos p) { return loaded; }
            public boolean isCane(BlockPos p) { return p.getY() >= BASE.getY() && p.getY() <= BASE.getY() + height; }
        };
    }
    private static SugarCaneHarvestSession session(int height, int count, int maxTargets, long deadline) {
        return new SugarCaneHarvestSession(List.of(BASE), count, maxTargets, world(height, true), deadline);
    }

    @Test void keepsRootAndTakesTopFirst() {
        var s = session(4, 1, 1, 1000);
        assertEquals(new BlockPos(0, 68, 0), s.next(0));
        var s2 = session(4, 2, 1, 1000);
        assertEquals(68, s2.next(0).getY());
        s2.accept(SugarCaneHarvestSession.Outcome.BROKE, 1);
        assertEquals(67, s2.next(1).getY());
    }
    @Test void progressingIsNotFailure() {
        var s = session(3, 1, 1, 1000);
        assertEquals(SugarCaneHarvestSession.State.RUNNING, s.state());
        s.accept(SugarCaneHarvestSession.Outcome.PROGRESSING, 1);
        assertEquals(SugarCaneHarvestSession.State.RUNNING, s.state());
        assertEquals(0, s.destroyed());
    }
    @Test void noShotAndCancelStop() {
        var a = session(3, 2, 1, 1000);
        a.accept(SugarCaneHarvestSession.Outcome.NO_SHOT, 1);
        assertEquals(SugarCaneHarvestSession.State.FAILED, a.state());
        var b = session(3, 2, 1, 1000);
        b.accept(SugarCaneHarvestSession.Outcome.CANCELLED, 1);
        assertEquals(SugarCaneHarvestSession.State.FAILED, b.state());
        assertNull(b.next(2));
    }
    @Test void tooTallTopUnknownSkipped() {
        var s = session(9, 2, 1, 1000);
        assertEquals(SugarCaneHarvestSession.State.SUCCESS, s.state());
        assertEquals("no_harvestable_sugar_cane", s.stopReason());
    }
    @Test void precheckRejectsGrowthAboveAndDisconnect() {
        var s = session(3, 2, 1, 1000);
        var target = s.next(0);
        assertNotNull(target);
        var grown = new SugarCaneHarvestSession.World() {
            public boolean loaded(BlockPos p) { return true; }
            public boolean isCane(BlockPos p) { return p.getY() >= BASE.getY() && p.getY() <= target.getY() + 1; }
        };
        assertEquals("grew_above_since_plan", s.precheck(target, grown));
        var hole = new SugarCaneHarvestSession.World() {
            public boolean loaded(BlockPos p) { return true; }
            public boolean isCane(BlockPos p) { return p.getY() != BASE.getY() + 2 && p.getY() >= BASE.getY() && p.getY() <= BASE.getY() + 3; }
        };
        assertEquals("root_to_target_disconnected", s.precheck(target, hole));
    }
    @Test void unloadedSkippedAndDeadlinePartial() {
        var none = new SugarCaneHarvestSession(List.of(BASE), 2, 1, world(3, false), 1000);
        assertEquals(SugarCaneHarvestSession.State.SUCCESS, none.state());
        assertEquals("no_harvestable_sugar_cane", none.stopReason());
        var s = session(4, 3, 1, 100);
        s.accept(SugarCaneHarvestSession.Outcome.BROKE, 1);
        assertNull(s.next(200));
        assertEquals(SugarCaneHarvestSession.State.TIMEOUT, s.state());
        assertEquals("deadline_reached", s.stopReason());
    }
}
