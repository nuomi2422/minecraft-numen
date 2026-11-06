package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.observation.ObservationMetadata;
import com.dwinovo.numen.api.observation.WorldObservationService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 2026-11-06: 只接 hp；CURRENT/身份/时效任一不合格都必须 UNKNOWN。 */
class RddPlanningHpFactTest {

    private static final long NOW = 5_000_000L;

    private static ObservationMetadata meta(String subject, ObservationMetadata.Freshness f, long observedAt, long validUntil) {
        return new ObservationMetadata("world-1", "session-1", "minecraft:overworld", subject,
                "body", "self", 1L, observedAt, f, "INTERNAL", validUntil);
    }

    private static WorldObservationService.Response ok(UUID subject, ObservationMetadata m, Object hp) {
        java.util.Map<String, Object> values = hp == null ? Map.of() : Map.of("hp", hp);
        var item = new WorldObservationService.Item(values, Set.of(), Set.of());
        return new WorldObservationService.Response(WorldObservationService.Status.OK, "self", "s1", 1L,
                WorldObservationService.Coverage.FULL, "", List.of(item), 0L, m);
    }

    @Test void freshCurrentHpIsAccepted() {
        UUID c = UUID.randomUUID();
        var r = ok(c, meta(c.toString(), ObservationMetadata.Freshness.CURRENT, NOW - 1000, NOW + 60_000), 4);
        assertEquals(4.0, RddPlanningKnowledge.validHp(c, r, NOW));
    }

    @Test void keepsFractionalHpPrecision() {
        UUID c = UUID.randomUUID();
        var r = ok(c, meta(c.toString(), ObservationMetadata.Freshness.CURRENT, NOW - 1000, NOW + 60_000), 6.4);
        assertEquals(6.4, RddPlanningKnowledge.validHp(c, r, NOW), 0.0001);
    }

    @Test void rejectsZeroValidUntil() {
        UUID c = UUID.randomUUID();
        var r = ok(c, meta(c.toString(), ObservationMetadata.Freshness.CURRENT, NOW - 1000, 0L), 4);
        assertNull(RddPlanningKnowledge.validHp(c, r, NOW));
    }

    @Test void rejectsWrongSubjectStaleExpiredAndFuture() {
        UUID c = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        assertNull(RddPlanningKnowledge.validHp(c, ok(other, meta(other.toString(), ObservationMetadata.Freshness.CURRENT, NOW - 1, NOW + 1000), 4), NOW));
        assertNull(RddPlanningKnowledge.validHp(c, ok(c, meta(c.toString(), ObservationMetadata.Freshness.STALE, NOW - 1, NOW + 1000), 4), NOW));
        assertNull(RddPlanningKnowledge.validHp(c, ok(c, meta(c.toString(), ObservationMetadata.Freshness.CURRENT, NOW - 1, NOW - 1), 4), NOW));
        assertNull(RddPlanningKnowledge.validHp(c, ok(c, meta(c.toString(), ObservationMetadata.Freshness.CURRENT, NOW + 1000, NOW + 10_000), 4), NOW));
    }

    @Test void rejectsUnknownHpOrBadMetadata() {
        UUID c = UUID.randomUUID();
        assertNull(RddPlanningKnowledge.validHp(c, ok(c, meta(c.toString(), ObservationMetadata.Freshness.CURRENT, NOW - 1, NOW + 1000), null), NOW));
        assertNull(RddPlanningKnowledge.validHp(c, ok(c, meta("", ObservationMetadata.Freshness.CURRENT, NOW - 1, NOW + 1000), 4), NOW));
    }
}
