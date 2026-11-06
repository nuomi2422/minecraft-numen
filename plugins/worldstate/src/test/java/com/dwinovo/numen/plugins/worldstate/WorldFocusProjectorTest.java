package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.api.PlanningQuery;
import com.dwinovo.numen.api.observation.ObservationMetadata;
import com.dwinovo.numen.api.observation.WorldObservationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-06: 焦点投影只读缓存、不 refresh；未知/过期不出旧值；预算有界。 */
class WorldFocusProjectorTest {

    @TempDir Path dir;

    private static WorldObservationService stub(Map<String, WorldObservationService.Response> byTopic,
                                               AtomicInteger reads, AtomicInteger refreshes) {
        return new WorldObservationService() {
            @Override public Response read(Request request) {
                reads.incrementAndGet();
                Response r = byTopic.get(request.topic());
                return r != null ? r : new Response(Status.ERROR, request.topic(), "", 0L,
                        Coverage.UNKNOWN, "no data", List.of(), 0L);
            }
            @Override public Response refresh(Request request) {
                refreshes.incrementAndGet();
                throw new AssertionError("focus projection must not refresh");
            }
        };
    }

    private static WorldObservationService.Response ok(String topic, long now, String key, Object val) {
        var meta = new ObservationMetadata("w", "s", "minecraft:overworld", "c", "test", "self", 1L, now,
                ObservationMetadata.Freshness.CURRENT, "INTERNAL", now + 60_000L);
        return new WorldObservationService.Response(WorldObservationService.Status.OK, topic, "snap", 1L,
                WorldObservationService.Coverage.FULL, "", List.of(WorldObservationService.Item.of(Map.of(key, val))),
                0L, meta);
    }

    @Test void projectsOnlyReadsAndNeverRefreshes() {
        var svc = new com.dwinovo.numen.plugins.worldstate.focus.FocusPinStore();
        UUID c = UUID.randomUUID();
        long now = 1_000_000L;
        svc.pin(c, "world", "session", "minecraft:overworld", "environment", now, 60_000L, 0, 0, 0);
        svc.pin(c, "world", "session", "minecraft:overworld", "entities", now, 60_000L, 0, 0, 0);
        AtomicInteger reads = new AtomicInteger(), refreshes = new AtomicInteger();
        var obs = stub(Map.of("environment", ok("environment", now, "time", "day"),
                "entities", ok("entities", now, "hostile", 1)), reads, refreshes);
        String text = WorldFocusProjector.render(svc, obs, new PlanningQuery(c, "goal", "", List.of()),
                "world", "session", now + 1000);
        assertTrue(text.contains("environment=") && text.contains("entities="), text);
        assertEquals(2, reads.get(), "只读缓存两次");
        assertEquals(0, refreshes.get(), "不得 refresh");
        assertTrue(text.length() <= WorldFocusProjector.MAX_CHARS, "预算包含省略标记");
    }

    @Test void missingOrExpiredIsUnknownNotStale() {
        var svc = new com.dwinovo.numen.plugins.worldstate.focus.FocusPinStore();
        UUID c = UUID.randomUUID();
        long now = 2_000_000L;
        svc.pin(c, "world", "session", "minecraft:overworld", "environment", now, 1_000L, 0, 0, 0);
        AtomicInteger reads = new AtomicInteger(), refreshes = new AtomicInteger();
        var obs = stub(Map.of(), reads, refreshes);
        String text = WorldFocusProjector.render(svc, obs, new PlanningQuery(c, "goal", "", List.of()),
                "world", "session", now + 100);
        assertTrue(text.contains("UNKNOWN"), text);
        String expired = WorldFocusProjector.render(svc, obs, new PlanningQuery(c, "goal", "", List.of()),
                "world", "session", now + 2_000L);
        assertEquals("", expired, "过期后焦点应消失");
    }

    @Test void worldOrSessionSwitchClearsPins() {
        var svc = new com.dwinovo.numen.plugins.worldstate.focus.FocusPinStore();
        UUID c = UUID.randomUUID();
        svc.pin(c, "world-a", "s1", "minecraft:overworld", "environment", 1_000L, 60_000L, 0, 0, 0);
        svc.pin(c, "world-b", "s2", "minecraft:overworld", "entities", 1_000L, 60_000L, 0, 0, 0);
        assertEquals(1, svc.active(c, "world-b", "s2", 2_000L).size(), "切换世界时旧焦点被清理");
    }
}
