package com.dwinovo.numen.rdd.side;

import com.dwinovo.numen.rdd.side.sleep.SleepSideTaskTrigger;
import com.dwinovo.numen.rdd.side.sleep.SleepSideTaskType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

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
}