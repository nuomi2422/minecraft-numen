package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 2026-11-17 早完成建议职责拆分后的最少真实消费控：
 * Detector 的 facade 仍走同一发布/通知所有者；未知同伴无通知；空身体发布按异常语义静默不抛。
 */
class RddEarlyProposalPublisherTest {

    @Test
    void unknownCompanionHasNoNotice() {
        UUID id = UUID.randomUUID();
        assertEquals("", RddEarlyProposalPublisher.notice(id, "w", "s", "c", 1, 1));
        assertEquals("", RddEarlyProposalPublisher.notice(null, "w", "s", "c", 1, 1));
    }

    @Test
    void detectorFacadeDelegatesToPublisher() {
        UUID id = UUID.randomUUID();
        assertEquals(
                RddEarlyProposalPublisher.notice(id, "w", "s", "c", 1, 1),
                RddDetector.earlyProposalNotice(id, "w", "s", "c", 1, 1));
    }

    @Test
    void publishWithNullBodyDoesNotThrow() {
        RddEarlyProposalPublisher.publish(null, null, Map.of());
    }
}