package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RddStallPolicyTest {
    private static RddStallPolicy.Observation observation(String work, boolean busy, String source) {
        return new RddStallPolicy.Observation("iron=0|position=1,64,1", work, busy, source);
    }

    private static RddStallPolicy.Check next(RddStallPolicy.Check previous, RddStallPolicy.Observation observation) {
        return RddStallPolicy.check(previous == null ? null : previous.fingerprint(),
                previous == null ? 0 : previous.unchanged(), observation);
    }

    @Test void parkedBucketAbsorbsJitterButSeparatesRealTravel() {
        // 回归（2026-09-27 实机）：AI 在两点之间横跳时，原精确坐标指纹每 tick 变化，
        // "无进展"窗口被无限重置 -> 催工永不触发（表现为重复走且任务永不完成）。
        // 修法是停车守望改用 8 格粗桶：同区域抖动同桶（窗口不被重置），跨区域才算新进展。
        String a = RddStallPolicy.parkedBucket(100, 64, 100);
        assertEquals(a, RddStallPolicy.parkedBucket(100, 64, 101));
        assertEquals(a, RddStallPolicy.parkedBucket(103, 64, 97));
        assertEquals(a, RddStallPolicy.parkedBucket(103, 64, 103));
        assertNotEquals(a, RddStallPolicy.parkedBucket(108, 64, 100));
        assertNotEquals(a, RddStallPolicy.parkedBucket(100, 72, 100));
        // 负坐标也要正确分桶（Math.floorDiv 而非截断）
        assertEquals("-1,0,-1", RddStallPolicy.parkedBucket(-1, 0, -1));
        assertEquals("-2,0,-2", RddStallPolicy.parkedBucket(-9, 0, -9));
    }

    @Test void idleStillEscalatesAfterFifteenUnchangedChecks() {
        var frame = observation("", false, "idle");
        var check = next(null, frame);
        for (int i = 1; i < 15; i++) {
            check = next(check, frame);
            assertFalse(check.stalled());
        }
        assertTrue(next(check, frame).stalled());
    }

    @Test void activeBodyGetsBoundedGraceRatherThanPermanentExemption() {
        var frame = observation("fishing:0/3", true, "body_task:t1");
        var check = next(null, frame);
        for (int i = 1; i < RddStallPolicy.WORK_GRACE_CHECKS; i++) {
            check = next(check, frame);
            assertFalse(check.stalled(), "premature nudge at " + i);
        }
        check = next(check, frame);
        assertTrue(check.stalled());
        assertEquals(0, check.remaining());
    }

    @Test void changingTaskIdentityWithoutWorkCannotRefreshGrace() {
        var check = next(null, observation("fishing:0/3", true, "body_task:t0"));
        for (int i = 1; i <= RddStallPolicy.WORK_GRACE_CHECKS; i++) {
            check = next(check, observation("fishing:0/3", true, "body_task:t" + i));
        }
        assertTrue(check.stalled());
    }

    @Test void furnaceCanProduceForTenMinutesWithoutInventoryChanging() {
        RddStallPolicy.Check check = null;
        for (int second = 0; second < 600; second++) {
            // Material remains in the furnace; completed batches and cooking change, inventory does not.
            check = next(check, observation("output=" + second / 10 + "|cooking=" + second % 10,
                    true, "furnace_production"));
            assertFalse(check.stalled());
        }
    }

    @Test void litButBlockedFurnaceStillEscalatesWhenNothingIsProduced() {
        var frame = observation("input=1|output=64|cooking=0", true, "furnace_production");
        var check = next(null, frame);
        // Fuel countdown is deliberately not part of workProgress; lit alone is not success.
        for (int i = 0; i < RddStallPolicy.WORK_GRACE_CHECKS; i++) check = next(check, frame);
        assertTrue(check.stalled());
    }

    @Test void realContainerOutputOrBodyProgressRestartsObservationWindow() {
        var check = next(null, observation("output=0|body=building:0/20", true, "body_task:t1"));
        for (int i = 0; i < 100; i++) {
            check = next(check, observation("output=0|body=building:0/20", true, "body_task:t1"));
        }
        check = next(check, observation("output=1|body=building:1/20", true, "body_task:t1"));
        assertTrue(check.changed());
        assertEquals(0, check.unchanged());
        assertEquals(RddStallPolicy.WORK_GRACE_CHECKS, check.remaining());
    }

    @Test void endedWorkCannotKeepTheBusyGrace() {
        var check = next(null, observation("output=0", true, "furnace_production"));
        for (int i = 0; i < 20; i++) check = next(check, observation("output=0", true, "furnace_production"));
        check = next(check, observation("output=0", false, "idle"));
        assertTrue(check.stalled());
        assertEquals(RddStallPolicy.IDLE_GRACE_CHECKS, check.limit());
    }
}
