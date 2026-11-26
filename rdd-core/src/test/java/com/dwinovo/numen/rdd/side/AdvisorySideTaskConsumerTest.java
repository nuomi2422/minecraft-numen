package com.dwinovo.numen.rdd.side;

import com.dwinovo.numen.rdd.side.sleep.SleepSideTaskTrigger;
import com.dwinovo.numen.rdd.side.sleep.SleepSideTaskType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B 型普通支线是可见建议，不是当前身体 owner。 */
class AdvisorySideTaskConsumerTest {
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final long WINDOW = 11_800L;

    @Test
    void sleepAdvisoryDoesNotActivateOrFreezeMainline() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = new SideTaskEngine(events, token -> token);
        engine.registerType(new SleepSideTaskType());
        engine.registerTrigger(new SleepSideTaskTrigger());
        FakeWorldPort port = new FakeWorldPort().at(WINDOW, 0L);

        engine.tick(C, port, null, false);

        assertFalse(engine.isActive(C), "ordinary sleep advice must not become a side owner");
        assertTrue(events.saw("side_task_advisory"), "the same advice must remain visible as an advisory");
    }

    @Test
    void legacyUnmarkedOvernightReleasesSlotWithoutResume() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = new SideTaskEngine(events, token -> token);
        engine.registerType(new SleepSideTaskType(SleepSideTaskType.Goal.OVERNIGHT, true));
        SideTask legacy = new SideTask(C, "legacy-sleep", SleepSideTaskType.TYPE_ID, 1, 0, 100, 20_000,
                SideTaskState.ACTIVE, "SEEKING_BED",
                Map.of("sleep_goal", "OVERNIGHT", "kept", "yes"), null);
        assertTrue(engine.runtime().activate(legacy));

        engine.tick(C, new FakeWorldPort().at(WINDOW, 0L), null, false);

        assertFalse(engine.isActive(C), "old auto Host sleep must release the only runtime slot");
        assertTrue(events.saw("side_task_cancelled"));
        assertFalse(events.saw("side_task_resumed_mainline"), "migration must not resume an owner-paused mainline");
        Map<?, ?> payload = events.payloads.get(events.events.indexOf("side_task_cancelled"));
        assertEquals("yes", ((Map<?, ?>) payload.get("preservedData")).get("kept"));
    }

    @Test
    void newExplicitOvernightStaysHardAcrossAdvisoryRegistration() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = new SideTaskEngine(events, token -> token);
        SleepSideTaskType explicit = new SleepSideTaskType(SleepSideTaskType.Goal.OVERNIGHT, false);
        engine.registerType(explicit);
        assertTrue(engine.startNow(explicit, C, new FakeWorldPort().at(13_000L, 0L), null, Map.of()));
        SideTask created = engine.current(C).orElseThrow();
        assertEquals("false", created.data().get("advisory_source"));
        assertFalse(explicit.advisoryOnly(created));

        String json = engine.toJson();
        SideTaskEngine restored = new SideTaskEngine(events, token -> token);
        restored.registerType(new SleepSideTaskType(SleepSideTaskType.Goal.OVERNIGHT, true));
        restored.restore(json);
        SideTask saved = restored.current(C).orElseThrow();
        assertFalse(new SleepSideTaskType(SleepSideTaskType.Goal.OVERNIGHT, true).advisoryOnly(saved));
        restored.tick(C, new FakeWorldPort().at(13_100L, 0L), null, false);
        assertTrue(restored.isActive(C), "a saved explicit task must not be downgraded by the current registration");
    }
}