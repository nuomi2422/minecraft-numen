package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-07 B2: empty-harvest hint only after 3 empties/20min with no growth progress. */
class RddFarmScanGuardTest {
    private static final UUID ID = UUID.randomUUID();
    private static final long T0 = 1_000_000L;
    private static final long MIN = 60_000L;

    @Test void threeEmptyScansOverTwentyMinutesTriggerHint() {
        Map<UUID, RddFarmScanGuard.State> m = new HashMap<>();
        assertNull(RddFarmScanGuard.observe(m, ID, T0, 0, 7L));
        assertNull(RddFarmScanGuard.observe(m, ID, T0 + 10 * MIN, 0, 7L));
        String hint = RddFarmScanGuard.observe(m, ID, T0 + 20 * MIN, 0, 7L);
        assertNotNull(hint);
        assertTrue(hint.contains("20") && hint.contains("扩田"), hint);
    }

    @Test void growthProgressResetsCounter() {
        Map<UUID, RddFarmScanGuard.State> m = new HashMap<>();
        RddFarmScanGuard.observe(m, ID, T0, 0, 7L);
        RddFarmScanGuard.observe(m, ID, T0 + 10 * MIN, 0, 7L);
        assertNull(RddFarmScanGuard.observe(m, ID, T0 + 20 * MIN, 0, 8L), "age/fingerprint changed = progress");
        assertNull(RddFarmScanGuard.observe(m, ID, T0 + 21 * MIN, 0, 8L));
    }

    @Test void harvestOrShortWindowDoesNotTrigger() {
        Map<UUID, RddFarmScanGuard.State> m = new HashMap<>();
        RddFarmScanGuard.observe(m, ID, T0, 0, 7L);
        RddFarmScanGuard.observe(m, ID, T0 + MIN, 1, 7L);
        assertNull(RddFarmScanGuard.observe(m, ID, T0 + 2 * MIN, 0, 7L));
        assertNull(RddFarmScanGuard.observe(m, ID, T0 + 3 * MIN, 0, 7L), "window <20min must not trigger");
    }
}
