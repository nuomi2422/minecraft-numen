package com.dwinovo.numen.api.observation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldTimeTest {
    @Test void wrapsGameTicksWithoutChangingNegativeSemantics() {
        assertEquals(0L, WorldTime.inDay(0));
        assertEquals(13000L, WorldTime.inDay(13000));
        assertEquals(13000L, WorldTime.inDay(37000));
        assertEquals(23999L, WorldTime.inDay(-1));
    }
}
