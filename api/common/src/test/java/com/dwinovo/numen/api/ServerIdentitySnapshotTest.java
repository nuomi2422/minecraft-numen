package com.dwinovo.numen.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerIdentitySnapshotTest {
    private final ServerIdentityProvider.Snapshot A = new ServerIdentityProvider.Snapshot("world-A", "session-A");
    private final ServerIdentityProvider.Snapshot B = new ServerIdentityProvider.Snapshot("world-B", "session-B");

    @AfterEach void clear() { WorldContextStamp.clear(); WorldContextStamp.resetForTest(); }

    @Test void mapsConcreteSnapshotNotGlobalCurrent() {
        var a = WorldContextStamp.tokenFrom(A);
        WorldContextStamp.set("world-B", "session-B");
        var b = WorldContextStamp.tokenFrom(B);
        assertEquals("world-A", a.identity().get("world_id"));
        assertEquals("world-B", b.identity().get("world_id"));
    }

    @Test void injectedTokenIsReturnedWithoutServer() {
        var token = WorldContextStamp.tokenFrom(A);
        WorldContextStamp.installForTest(token);
        assertSame(token, WorldContextStamp.captureForTest());
    }

    @Test void injectedTokenWinsOverCurrentStampConflict() {
        var old = WorldContextStamp.tokenFrom(A);
        WorldContextStamp.set("world-B", "session-B");
        WorldContextStamp.installForTest(old);
        assertEquals("world-A", WorldContextStamp.captureForTest().identity().get("world_id"));
        WorldContextStamp.resetForTest();
        assertNull(WorldContextStamp.captureForTest());
    }

    @Test void nullAndBlankAreUnknown() {
        assertNull(WorldContextStamp.tokenFrom(null).identity());
        assertNull(WorldContextStamp.tokenFrom(new ServerIdentityProvider.Snapshot("", "s")).identity());
        assertNull(WorldContextStamp.tokenFrom(new ServerIdentityProvider.Snapshot("w", "")).identity());
    }

    @Test void sameWorldDifferentSessionStaysDistinct() {
        var one = WorldContextStamp.tokenFrom(new ServerIdentityProvider.Snapshot("same", "s1"));
        var two = WorldContextStamp.tokenFrom(new ServerIdentityProvider.Snapshot("same", "s2"));
        assertNotEquals(one.identity().get("server_session"), two.identity().get("server_session"));
    }

    @Test void aToBSwitchDoesNotLeakOldTokenAfterClear() {
        var oldA = WorldContextStamp.tokenFrom(A);
        WorldContextStamp.installForTest(oldA);
        WorldContextStamp.set("world-B", "session-B");
        assertEquals("world-A", WorldContextStamp.captureForTest().identity().get("world_id"),
                "in-flight request keeps its producer token across A→B");
        WorldContextStamp.resetForTest();
        var currentB = WorldContextStamp.snapshot();
        assertNotNull(currentB, "process stamp keeps B");
        assertEquals("world-B", currentB.get("world_id"), "new request captures B");
        assertNotEquals("world-A", currentB.get("world_id"));
    }

    @Test void clearMeansNoCurrentServerNotOldToken() {
        WorldContextStamp.installForTest(WorldContextStamp.tokenFrom(A));
        WorldContextStamp.set("world-A", "session-A");
        WorldContextStamp.clear();
        assertNull(WorldContextStamp.snapshot());
    }

    @Test void stopAndNewServerDoesNotReuseOldEpoch() {
        WorldContextStamp.set("world-A", "session-A");
        var old = WorldContextStamp.capture();
        assertEquals("world-A", old.identity().get("world_id"));
        WorldContextStamp.clear();
        assertNull(WorldContextStamp.snapshot(), "stop must withdraw the stamp");
        WorldContextStamp.set("world-B", "session-B");
        var next = WorldContextStamp.capture();
        assertEquals("world-B", next.identity().get("world_id"));
        assertTrue(next.epoch() > old.epoch(), "new server session must not reuse old epoch");
    }

    @Test void multipleServersDoNotShare() {
        var one = WorldContextStamp.tokenFrom(A);
        var two = WorldContextStamp.tokenFrom(B);
        assertNotEquals(one.identity().get("world_id"), two.identity().get("world_id"));
    }
}
