package com.dwinovo.numen.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * R-06: enqueue receipt shape and boundaries.
 *
 * <p>accepted is not effective: the receipt only proves local acceptance;
 * whether the stop took effect still waits for the owner_stop receipt.
 */
class EnqueueReceiptTest {

    @Test
    void rejectedCarriesNoCommandIdOrIdentity() {
        UUID c = UUID.randomUUID();
        var r = NumenGateway.EnqueueReceipt.rejected(c);
        assertEquals(Delivery.REJECTED, r.delivery());
        assertNull(r.commandId());
        assertEquals(c, r.companionUuid());
        assertEquals(NumenGateway.EnqueueReceipt.IdentityStatus.NOT_APPLICABLE, r.identityStatus());
        assertNull(r.worldId());
        assertNull(r.serverSession());
        assertEquals(0L, r.epoch());
    }

    @Test
    void handedOffIsAcceptedNotEffective() {
        UUID c = UUID.randomUUID();
        var r = NumenGateway.EnqueueReceipt.handedOff(c);
        assertEquals(Delivery.HANDED_OFF, r.delivery());
        assertNull(r.commandId());
    }

    @Test
    void okReceiptKeepsCommandIdAndIdentityTogether() {
        UUID c = UUID.randomUUID();
        var r = new NumenGateway.EnqueueReceipt(
                Delivery.SEEN, "cmd-1", c,
                NumenGateway.EnqueueReceipt.IdentityStatus.OK,
                "world-A", "session-A", 7L);
        assertEquals("cmd-1", r.commandId());
        assertEquals("world-A", r.worldId());
        assertEquals("session-A", r.serverSession());
        assertEquals(7L, r.epoch());
    }

    @Test
    void instanceAndTargetChangesAreDistinctStatuses() {
        UUID c = UUID.randomUUID();
        var changed = new NumenGateway.EnqueueReceipt(
                Delivery.SEEN, "cmd-1", c,
                NumenGateway.EnqueueReceipt.IdentityStatus.CONNECTION_CHANGED,
                "world-A", "session-A", 8L);
        assertEquals(NumenGateway.EnqueueReceipt.IdentityStatus.CONNECTION_CHANGED, changed.identityStatus());
        var retargeted = new NumenGateway.EnqueueReceipt(
                Delivery.SEEN, "cmd-1", c,
                NumenGateway.EnqueueReceipt.IdentityStatus.TARGET_CHANGED,
                "world-A", "session-A", 7L);
        assertEquals(NumenGateway.EnqueueReceipt.IdentityStatus.TARGET_CHANGED, retargeted.identityStatus());
    }

    @Test
    void noIdentityIsExplicitNotGuessed() {
        UUID c = UUID.randomUUID();
        var r = new NumenGateway.EnqueueReceipt(
                Delivery.SEEN, null, c,
                NumenGateway.EnqueueReceipt.IdentityStatus.NO_IDENTITY,
                null, null, 3L);
        assertEquals(NumenGateway.EnqueueReceipt.IdentityStatus.NO_IDENTITY, r.identityStatus());
        assertNull(r.worldId());
    }
}
