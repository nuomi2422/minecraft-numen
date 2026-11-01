package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 167 10.26: busy-but-no-output is neither waiting nor dead-task. */
class RddStallPolicyBusyTest {

    @Test
    void firesOnlyWhenBusyAndStale() {
        assertTrue(RddStallPolicy.isBusyNoProgress(false, false, RddStallPolicy.BUSY_NO_PROGRESS_CHECKS));
        assertFalse(RddStallPolicy.isBusyNoProgress(false, false, RddStallPolicy.BUSY_NO_PROGRESS_CHECKS - 1));
    }

    @Test
    void waitingOrDeadAreOtherPeoplesJob() {
        assertFalse(RddStallPolicy.isBusyNoProgress(true, false, 999));
        assertFalse(RddStallPolicy.isBusyNoProgress(false, true, 999));
    }
}