package com.dwinovo.numen.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** R-06：旧 Boolean 入口与非命令/拒绝路径保持兼容（不需要 Minecraft 实例即可判定）。 */
class EnqueueReceiptCompatTest {

    @Test
    void oldBooleanEntryStaysFalseForInvalidInput() throws Exception {
        assertFalse(NumenActuator.enqueue(null, "x").get(2, TimeUnit.SECONDS));
        assertFalse(NumenActuator.enqueue(java.util.UUID.randomUUID(), " ").get(2, TimeUnit.SECONDS));
    }

    @Test
    void receiptEntryRejectsInvalidInputWithoutIdentity() throws Exception {
        var r = NumenActuator.enqueueReceipt(null, "x").get(2, TimeUnit.SECONDS);
        assertEquals(Delivery.REJECTED, r.delivery());
        assertNull(r.commandId());
        assertEquals(NumenGateway.EnqueueReceipt.IdentityStatus.NOT_APPLICABLE, r.identityStatus());
    }
}
