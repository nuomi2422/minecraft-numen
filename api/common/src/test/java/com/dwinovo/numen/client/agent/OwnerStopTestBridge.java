package com.dwinovo.numen.client.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Test-only package bridge; keep the production pause enum package-private. */
public final class OwnerStopTestBridge {
    private OwnerStopTestBridge() {}
    public static void assertOwnerResumeClearsLatch() {
        assertEquals(AgentTurnPause.NONE, OwnerStopLatch.afterOwnerResume(AgentTurnPause.OWNER_INTERRUPT));
    }
}
