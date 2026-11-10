package com.dwinovo.numen.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * R-06锛氬叆闃熷洖鎵х殑褰㈢姸涓庤竟鐣屻€? *
 * <p>accepted 涓嶇瓑浜庣敓鏁堬細鍥炴墽鍙瘉鏄庢湰鍦板彈鐞嗭紱stop 鏄惁鐢熸晥瑕佺瓑 owner_stop 鍥炴墽銆? */
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

