package com.dwinovo.numen.plugins.worldstate.focus;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-06 显式短期焦点生命周期：两轮存活/到期/换世界/换会话/换主体/上限/去重。 */
class FocusPinStoreTest {

    private static final String W = "world-1";
    private static final String S = "session-1";
    private static final String D = "minecraft:overworld";

    @Test void independentOwnersShareThenOnlyLastReleaseRemovesPhysicalPin() {
        FocusPinStore st = new FocusPinStore(); UUID c = UUID.randomUUID();
        var a = st.acquire(c,W,S,D,"environment","topic@v1","package-a",1000,60000,0,0,0);
        var b = st.acquire(c,W,S,D,"environment","topic@v1","package-b",1100,60000,0,0,0);
        assertNotNull(a); assertNotNull(b); assertEquals(a.pin().id(), b.pin().id());
        assertEquals(a.leaseId(), st.acquire(c,W,S,D,"environment","topic@v1","package-a",1200,60000,0,0,0).leaseId());
        assertEquals(a.pin().expiresAtMs(), b.pin().expiresAtMs(), "later owner inherits remaining TTL without renewal");
        assertEquals(1, st.active(c,W,S,1300).size());
        assertFalse(st.release(c,"package-b",a.leaseId()));
        assertTrue(st.release(c,"package-a",a.leaseId()));
        assertEquals(1, st.active(c,W,S,1400).size());
        assertTrue(st.release(c,"package-b",b.leaseId()));
        assertEquals(0, st.active(c,W,S,1500).size());
    }

    @Test void legacyAndOwnersCannotRemoveEachOthersLease() {
        FocusPinStore st = new FocusPinStore(); UUID c = UUID.randomUUID();
        var legacy = st.pin(c,W,S,D,"environment",1000,60000,0,0,0);
        var owner = st.acquire(c,W,S,D,"environment","","package",1000,60000,0,0,0);
        assertEquals(legacy.id(), owner.pin().id());
        assertTrue(st.unpin(c,legacy.id()));
        assertEquals(1,st.active(c,W,S,1200).size());
        assertFalse(st.unpin(c,legacy.id()));
        assertTrue(st.release(c,"package",owner.leaseId()));
        assertTrue(st.active(c,W,S,1300).isEmpty());
    }

    @Test void exactVersionAndObservationParametersDoNotShareSlots() {
        FocusPinStore st = new FocusPinStore(); UUID c = UUID.randomUUID();
        var a = st.acquire(c,W,S,D,"environment","topic@v1","a",1000,60000,10,3,4);
        var b = st.acquire(c,W,S,D,"environment","topic@v2","b",1000,60000,10,3,4);
        var d = st.acquire(c,W,S,D,"environment","topic@v1","c",1000,60000,11,3,4);
        assertNotEquals(a.pin().id(), b.pin().id()); assertNotEquals(a.pin().id(), d.pin().id());
        assertEquals(3,st.active(c,W,S,1200).size());
    }

    @Test void ownerLimitExpiryAndStaleLeaseAreBounded() {
        FocusPinStore st=new FocusPinStore(); UUID c=UUID.randomUUID();
        var first=st.acquire(c,W,S,D,"environment","v1","owner0",1000,1000,0,0,0);
        for(int i=1;i<FocusPinStore.MAX_OWNERS_PER_PIN;i++)
            assertNotNull(st.acquire(c,W,S,D,"environment","v1","owner"+i,1000,1000,0,0,0));
        assertNull(st.acquire(c,W,S,D,"environment","v1","overflow",1000,1000,0,0,0));
        assertEquals(first.leaseId(),st.acquire(c,W,S,D,"environment","v1","owner0",1500,60000,0,0,0).leaseId());
        st.sweepExpired(2000); assertTrue(st.active(c,W,S,2000).isEmpty());
        var next=st.acquire(c,W,S,D,"environment","v1","owner0",2000,1000,0,0,0);
        assertNotEquals(first.leaseId(),next.leaseId()); assertFalse(st.release(c,"owner0",first.leaseId()));
    }

    @Test void versionedOwnerIsNotReusedByLegacyAndFourSlotsRemainShared() {
        FocusPinStore st=new FocusPinStore(); UUID c=UUID.randomUUID();
        var v=st.acquire(c,W,S,D,"environment","v1","package",1000,60000,0,3,0);
        var legacy=st.pin(c,W,S,D,"environment",1000,60000,0,8,0);
        assertNotEquals(v.pin().id(),legacy.id());
        assertTrue(st.unpin(c,legacy.id())); assertEquals(1,st.active(c,W,S,1100).size());
        for(int i=0;i<3;i++) assertNotNull(st.pin(c,W,S,"other"+i,"entities",1000,60000,0,0,0));
        assertNull(st.acquire(c,W,S,D,"environment","v2","second",1000,60000,0,3,0));
        assertTrue(st.release(c,"package",v.leaseId()));
        assertNotNull(st.acquire(c,W,S,D,"environment","v2","second",1000,60000,0,3,0));
    }

    @Test void serializedWorldSwitchOldLeaseCannotReleaseNewPin() {
        FocusPinStore st=new FocusPinStore(); UUID c=UUID.randomUUID();
        var old=st.acquire(c,W,S,D,"environment","v1","owner",1000,60000,0,0,0);
        var next=st.acquire(c,"world-2","session-2",D,"environment","v1","owner",1100,60000,0,0,0);
        assertNotEquals(old.leaseId(),next.leaseId());
        assertFalse(st.release(c,"owner",old.leaseId()));
        assertEquals(1,st.active(c,"world-2","session-2",1200).size());
        assertTrue(st.release(c,"owner",next.leaseId()));
    }

    @Test void pinSurvivesTwoRoundsThenExpires() {
        FocusPinStore st = new FocusPinStore();
        UUID c = UUID.randomUUID();
        FocusPin p = st.pin(c, W, S, D, "entities", 1000L, 5000L, 0, 8, 0);
        assertNotNull(p);
        assertEquals(1, st.active(c, W, S, 2000L).size(), "第一轮有效");
        assertEquals(1, st.active(c, W, S, 5900L).size(), "第二轮仍有效");
        assertEquals(0, st.active(c, W, S, 6001L).size(), "到期即失效");
    }

    @Test void pinsAreScopedByWorldSessionAndCompanion() {
        FocusPinStore st = new FocusPinStore();
        UUID c = UUID.randomUUID();
        st.pin(c, W, S, D, "environment", 1000L, 60_000L, 0, 0, 0);
        assertEquals(0, st.active(c, "other-world", S, 2000L).size());
        assertEquals(0, st.active(c, W, "other-session", 2000L).size());
        assertEquals(0, st.active(UUID.randomUUID(), W, S, 2000L).size());
        assertEquals(1, st.active(c, W, S, 2000L).size());
    }

    @Test void unpinAndLimitAndDuplicate() {
        FocusPinStore st = new FocusPinStore();
        UUID c = UUID.randomUUID();
        FocusPin a = st.pin(c, W, S, D, "entities", 1000L, 60_000L, 0, 0, 0);
        FocusPin again = st.pin(c, W, S, D, "entities", 1500L, 60_000L, 0, 0, 0);
        assertEquals(a.id(), again.id(), "同 topic+dimension 复用现有 pin");
        assertEquals(a.expiresAtMs(), again.expiresAtMs(), "复用不续期：expiresAt 不变");
        assertTrue(st.unpin(c, a.id()));
        assertFalse(st.unpin(c, a.id()));
        for (int i = 0; i < FocusPinStore.MAX_PINS_PER_COMPANION; i++) {
            assertNotNull(st.pin(c, W, S, "dim-" + i, "environment", 2000L + i, 60_000L, 0, 0, 0));
        }
        assertNull(st.pin(c, W, S, "dim-x", "environment", 3000L, 60_000L, 0, 0, 0), "超过上限应拒绝");
    }
}
