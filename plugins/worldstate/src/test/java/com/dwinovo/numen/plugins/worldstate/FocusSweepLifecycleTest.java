package com.dwinovo.numen.plugins.worldstate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic service lifecycle; not NeoForge event delivery or game evidence. */
class FocusSweepLifecycleTest {
    @TempDir Path dir;
    @Test void serviceTicksAtBoundedCadenceAndStopClearsOnlyItsFocus() {
        var service = new WorldStateService(dir.resolve("one"));
        var other = new WorldStateService(dir.resolve("two"));
        for (int i=0;i<20;i++) assertNotNull(service.focusPins().pin(new UUID(0,i+1),"w","s","d","environment",1000,1000,0,0,0));
        UUID survivor=UUID.randomUUID();
        assertNotNull(service.focusPins().pin(survivor,"w","s","d","environment",1000,60000,0,0,0));
        UUID otherId=UUID.randomUUID();
        assertNotNull(other.focusPins().pin(otherId,"w","s","d","environment",1000,60000,0,0,0));
        assertEquals(16,service.tickFocusSweep(2000,100));
        assertEquals(0,service.tickFocusSweep(2000,999_999_999L));
        assertEquals(5,service.tickFocusSweep(2000,1_000_000_100L));
        assertEquals(1,service.focusPins().active(survivor,"w","s",2000).size());
        service.stopFocusSweep();
        assertTrue(service.focusPins().active(survivor,"w","s",2000).isEmpty());
        assertEquals(1,other.focusPins().active(otherId,"w","s",2000).size());
        assertEquals(0,service.tickFocusSweep(2000,1_000_000_101L));
    }
    @Test void clockDiscontinuityDoesNotBreakMonotonicCadence() {
        var service=new WorldStateService(dir); UUID c=UUID.randomUUID();
        assertNotNull(service.focusPins().pin(c,"w","s","d","environment",1000,1000,0,0,0));
        assertEquals(1,service.tickFocusSweep(1500,10));
        assertEquals(0,service.tickFocusSweep(3000,20));
        assertEquals(1,service.tickFocusSweep(1000,1_000_000_010L));
        assertEquals(1,service.focusPins().active(c,"w","s",1000).size());
        assertEquals(1,service.tickFocusSweep(3000,2_000_000_010L));
        assertTrue(service.focusPins().active(c,"w","s",3000).isEmpty());
    }
}
