package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.api.PlanningQuery;
import com.dwinovo.numen.api.observation.ObservationMetadata;
import com.dwinovo.numen.api.observation.WorldObservationService;
import com.dwinovo.numen.plugins.worldstate.focus.FocusPinStore;
import com.dwinovo.numen.plugins.worldstate.focus.FocusTools;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-06: 工具 pin→同一 store→规划投影两轮→unpin 消失；全程零 refresh。 */
class FocusToolWiringTest {

    private static WorldObservationService stub(AtomicInteger reads, AtomicInteger refreshes) {
        return new WorldObservationService() {
            @Override public Response read(Request request) {
                reads.incrementAndGet();
                long now = 1_000L;
                var meta = new ObservationMetadata("world", "session", "minecraft:overworld", "c", "test", request.topic(),
                        1L, now, ObservationMetadata.Freshness.CURRENT, "INTERNAL", now + 60_000L);
                return new Response(Status.OK, request.topic(), "snap", 1L, Coverage.FULL, "",
                        List.of(WorldObservationService.Item.of(Map.of("k", "v"))), 0L, meta);
            }
            @Override public Response refresh(Request request) {
                refreshes.incrementAndGet();
                throw new AssertionError("projection must not refresh");
            }
        };
    }

    @Test void sharedOwnersRemainProjectedUntilLastReleaseWithoutRefresh() {
        UUID c=UUID.randomUUID(); FocusPinStore store=new FocusPinStore(); long now=1000L;
        var legacy=FocusTools.pin(store,c,"world","session","minecraft:overworld","environment",now,60000,0);
        var a=store.acquire(c,"world","session","minecraft:overworld","environment","","package-a",now,60000,0,0,0);
        var b=store.acquire(c,"world","session","minecraft:overworld","environment","","package-b",now,60000,0,0,0);
        assertEquals(legacy.id(),a.pin().id()); assertEquals(a.pin().id(),b.pin().id());
        assertEquals(a.leaseId(),store.acquire(c,"world","session","minecraft:overworld","environment","","package-a",now+1,60000,0,0,0).leaseId());
        AtomicInteger reads=new AtomicInteger(),refreshes=new AtomicInteger(); var obs=stub(reads,refreshes);
        PlanningQuery q=new PlanningQuery(c,"goal","",List.of());
        assertFalse(store.release(c,"package-b",a.leaseId()));
        assertTrue(FocusTools.unpin(store,c,legacy.id()));
        assertTrue(WorldFocusProjector.render(store,obs,q,"world","session",now+100).contains("environment="));
        assertTrue(store.release(c,"package-a",a.leaseId()));
        assertTrue(WorldFocusProjector.render(store,obs,q,"world","session",now+200).contains("environment="));
        assertTrue(store.release(c,"package-b",b.leaseId()));
        assertFalse(WorldFocusProjector.render(store,obs,q,"world","session",now+300).contains("environment="));
        assertEquals(0,refreshes.get());
    }

    @Test void legacyToolHandleRoundTripsAfterSharedReacquire() {
        UUID c=UUID.randomUUID(); FocusPinStore store=new FocusPinStore(); long now=1000L;
        var first=FocusTools.pin(store,c,"world","session","minecraft:overworld","environment",now,60000,0);
        var owner=store.acquire(c,"world","session","minecraft:overworld","environment","","package",now,60000,0,0,0);
        assertTrue(FocusTools.unpin(store,c,first.id()));
        var next=FocusTools.pin(store,c,"world","session","minecraft:overworld","environment",now+1,60000,0);
        assertNotEquals(first.id(),next.id());
        assertFalse(FocusTools.unpin(store,c,first.id()));
        assertTrue(FocusTools.unpin(store,c,next.id()));
        assertEquals(1,store.active(c,"world","session",now+2).size());
        assertTrue(store.release(c,"package",owner.leaseId()));
        assertTrue(store.active(c,"world","session",now+3).isEmpty());
    }

    @Test void pinTwiceProjectTwiceThenUnpin() {
        UUID c = UUID.randomUUID();
        FocusPinStore store = new FocusPinStore();
        long now = 1_000L;
        var p1 = FocusTools.pin(store, c, "world", "session", "minecraft:overworld", "environment", now, 60_000L, 0);
        var p2 = FocusTools.pin(store, c, "world", "session", "minecraft:overworld", "entities", now, 60_000L, 0);
        assertNotNull(p1); assertNotNull(p2);
        AtomicInteger reads = new AtomicInteger(), refreshes = new AtomicInteger();
        var obs = stub(reads, refreshes);
        PlanningQuery q = new PlanningQuery(c, "goal", "", List.of());
        String round1 = WorldFocusProjector.render(store, obs, q, "world", "session", now + 100);
        String round2 = WorldFocusProjector.render(store, obs, q, "world", "session", now + 200);
        assertTrue(round1.contains("environment=") && round1.contains("entities="), round1);
        assertTrue(round2.contains("environment="), round2);
        assertEquals(4, reads.get(), "两轮 × 两个焦点 = 4 次只读");
        assertEquals(0, refreshes.get(), "不得 refresh");
        assertTrue(FocusTools.unpin(store, c, p1.id()));
        String after = WorldFocusProjector.render(store, obs, q, "world", "session", now + 300);
        assertFalse(after.contains("environment="), "unpin 后不再投影：" + after);
        assertTrue(after.contains("entities="), after);
    }
}
