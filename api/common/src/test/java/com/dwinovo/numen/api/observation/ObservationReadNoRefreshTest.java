package com.dwinovo.numen.api.observation;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-07: read must stay cache-only; provider collection count must not change. */
class ObservationReadNoRefreshTest {
    @Test void readDoesNotTriggerRefreshOrCollection() {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger refreshes = new AtomicInteger();
        WorldObservationService svc = new WorldObservationService() {
            @Override public Response read(Request r) {
                reads.incrementAndGet();
                return Response.ok("environment", "s1", 100, Coverage.UNKNOWN, List.of());
            }
            @Override public Response refresh(Request r) {
                refreshes.incrementAndGet();
                return Response.error("environment", "must not refresh");
            }
        };
        svc.read(new WorldObservationService.Request("env","minecraft:overworld","environment",
                java.util.Map.of(), 500, 10, 50));
        svc.read(new WorldObservationService.Request("env","minecraft:overworld","environment",
                java.util.Map.of(), 500, 10, 50));
        assertEquals(2, reads.get());
        assertEquals(0, refreshes.get(), "read 不得隐式刷新/采集");
    }
}
