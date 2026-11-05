package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-05：复盘 429 之后，learner_note 应拒收一段时间。 */
class ReviewQuotaGateTest {

    @BeforeEach
    void clear() { ReviewQuotaGate.reset(); }

    @Test
    void blockedRightAfter429() {
        long now = 1_000_000L;
        ReviewQuotaGate.blockQuota(now);
        assertTrue(ReviewQuotaGate.isBlocked(now));
        assertTrue(ReviewQuotaGate.isBlocked(now + ReviewQuotaGate.BLOCK_MS - 1));
        assertFalse(ReviewQuotaGate.isBlocked(now + ReviewQuotaGate.BLOCK_MS));
    }

    @Test
    void laterBlockExtendsButNeverShortens() {
        long now = 5_000_000L;
        ReviewQuotaGate.blockQuota(now);
        long until = now + ReviewQuotaGate.BLOCK_MS;
        ReviewQuotaGate.blockQuota(now + 1000L);
        assertTrue(ReviewQuotaGate.isBlocked(until + 1), "第二次 429 应把窗口往后推");
        ReviewQuotaGate.blockQuota(now - 10_000L);
        assertTrue(ReviewQuotaGate.isBlocked(until + 1), "旧时间戳不能缩短窗口");
    }
}
