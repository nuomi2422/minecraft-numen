package com.dwinovo.numen.link.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static com.dwinovo.numen.link.core.LinkTypes.*;
import static com.dwinovo.numen.link.core.DelegationLedger.*;

/** Offline adapter fixture only: it does not claim actual Minecraft/MSC writers are fenced. */
class MaidLinkCoreContractTest {
    static final Slot CHAT = new Slot(Side.NUMEN, Capability.CHAT);
    static final Slot WORK = new Slot(Side.NUMEN, Capability.EXECUTION);
    static Binding binding(boolean maid) {
        var bodies = new HashMap<Side, Body>();
        bodies.put(Side.NUMEN, new Body(Side.NUMEN, "n"));
        if (maid) bodies.put(Side.MAID, new Body(Side.MAID, "m"));
        return new Binding("pair", "owner", "world", "session", bodies);
    }
    static Provider provider(String id, Side origin, Capability cap, Set<Support> supports,
                             Set<Side> required, Set<String> deps) {
        return new Provider(id, "1", origin, cap, cap == Capability.EXECUTION ? Effect.WRITE : Effect.READ,
                supports, required, deps);
    }
    static CapabilityCatalog catalog() {
        return new CapabilityCatalog(List.of(
            provider("native-chat", Side.NUMEN, Capability.CHAT, Set.of(new Support(Side.NUMEN, Mode.NATIVE)), Set.of(Side.NUMEN), Set.of()),
            provider("work", Side.NUMEN, Capability.EXECUTION, Set.of(new Support(Side.NUMEN, Mode.NATIVE)), Set.of(Side.NUMEN), Set.of()),
            provider("msc-bound", Side.MAID, Capability.CHAT, Set.of(new Support(Side.MAID, Mode.NATIVE)), Set.of(Side.MAID), Set.of()),
            // Fictional tested provider in THIS fixture, not a claim about shipped MSC.
            provider("msc-neutral-fixture", Side.MAID, Capability.CHAT, Set.of(new Support(Side.NUMEN, Mode.OVERRIDE)), Set.of(Side.NUMEN), Set.of("neutral-adapter"))));
    }
    static Choice nativeChat() { return new Choice(true, "native-chat", Mode.NATIVE, "native-profile"); }
    static Choice crossChat() { return new Choice(true, "msc-neutral-fixture", Mode.OVERRIDE, "msc-profile"); }
    static LinkPolicy policy() {
        return new LinkPolicy(binding(false), catalog(), Map.of(CHAT, nativeChat(), WORK,
                new Choice(true, "work", Mode.NATIVE, "worker")), Set.of(), 16);
    }
    static GoalVersion goal(String id, long rev) { return new GoalVersion("w", "s", "n", id, rev, 0); }
    static Key key(String id) { return new Key("binding", id); }

    @Test void nativeNumenDoesNotRequireMaid() {
        assertTrue(policy().begin(0, CHAT, Set.of()).check().ok());
    }
    @Test void nativeMaidDoesNotRequireWorker() {
        Binding b = new Binding("b", "o", "w", "s", Map.of(Side.MAID, new Body(Side.MAID, "m")));
        var slot = new Slot(Side.MAID, Capability.CHAT);
        var p = new LinkPolicy(b, catalog(), Map.of(slot, new Choice(true, "msc-bound", Mode.NATIVE, "soul")), Set.of(), 2);
        assertTrue(p.begin(0, slot, Set.of()).check().ok());
    }
    @Test void nativeCoexistenceKeepsSeparateOutputs() {
        var m = new Slot(Side.MAID, Capability.CHAT);
        var p = new LinkPolicy(binding(true), catalog(), Map.of(CHAT, nativeChat(), m,
            new Choice(true, "msc-bound", Mode.NATIVE, "soul")), Set.of(), 4);
        assertTrue(p.begin(0, CHAT, Set.of()).check().ok());
        assertTrue(p.begin(0, m, Set.of()).check().ok());
    }
    @Test void noImplicitCrossEntitySupport() {
        assertEquals(Code.UNSUPPORTED, catalog().validate(binding(true), CHAT,
            new Choice(true, "msc-bound", Mode.OVERRIDE, "soul"), Set.of()).code());
    }
    @Test void crossProviderRequiresAdvertisedDependency() {
        assertEquals(Code.UNAVAILABLE, catalog().validate(binding(false), CHAT, crossChat(), Set.of()).code());
        assertTrue(catalog().validate(binding(false), CHAT, crossChat(), Set.of("neutral-adapter")).ok());
    }
    @Test void rejectAliasedBodiesAndInvalidManifests() {
        assertThrows(IllegalArgumentException.class, () -> new Binding("b","o","w","s",Map.of(
            Side.NUMEN,new Body(Side.NUMEN,"same"), Side.MAID,new Body(Side.MAID,"same"))));
        assertThrows(IllegalArgumentException.class, () -> new Provider("p","1",Side.NUMEN,Capability.EXECUTION,
            Effect.WRITE,Set.of(new Support(Side.NUMEN,Mode.READ)),Set.of(),Set.of()));
        var provider = catalog().provider("work");
        assertThrows(IllegalArgumentException.class, () -> new CapabilityCatalog(List.of(provider,provider)));
    }
    @Test void unsupportedPatchIsAtomic() {
        var p = policy(); var before = p.snapshot();
        assertEquals(Code.UNSUPPORTED, p.apply(0, Map.of(CHAT,crossChat(),WORK,
            new Choice(true,"missing",Mode.OVERRIDE,"x")),Set.of("neutral-adapter")).code());
        assertEquals(before,p.snapshot());
    }
    @Test void busySwitchMustWaitForRealDrain() {
        var p = policy(); var ticket = p.begin(0,CHAT,Set.of()).ticket();
        assertEquals(Code.BUSY,p.apply(0,Map.of(CHAT,crossChat()),Set.of("neutral-adapter")).code());
        assertTrue(p.drain(0,Set.of(CHAT)).ok());
        assertFalse(p.valid(ticket));
        assertEquals(Code.BUSY,p.begin(0,CHAT,Set.of()).check().code());
        assertEquals(Code.BUSY,p.apply(0,Map.of(CHAT,crossChat()),Set.of("neutral-adapter")).code());
        assertTrue(p.finish(ticket));
        assertTrue(p.apply(0,Map.of(CHAT,crossChat()),Set.of("neutral-adapter")).ok());
        assertFalse(p.valid(ticket));
        assertEquals("msc-profile",p.begin(1,CHAT,Set.of("neutral-adapter")).ticket().choice().profile());
    }
    @Test void switchingChatDoesNotCancelWork() {
        var p = policy(); var work = p.begin(0,WORK,Set.of()).ticket();
        assertTrue(p.apply(0,Map.of(CHAT,crossChat()),Set.of("neutral-adapter")).ok());
        assertTrue(p.valid(work));
        assertEquals(Code.STALE,p.begin(0,WORK,Set.of()).check().code());
        assertTrue(p.begin(1,WORK,Set.of()).check().ok());
    }
    @Test void failedSwitchCanResumeOldChoiceWithoutOldCallbacks() {
        var p = policy(); var old = p.begin(0,CHAT,Set.of()).ticket();
        p.drain(0,Set.of(CHAT)); p.finish(old);
        assertEquals(Code.UNAVAILABLE,p.apply(0,Map.of(CHAT,crossChat()),Set.of()).code());
        assertTrue(p.resume(0,CHAT).ok());
        assertFalse(p.valid(old));
        assertEquals(nativeChat(),p.begin(0,CHAT,Set.of()).ticket().choice());
    }
    @Test void disabledChoiceCannotRunAndDoesNotClearProfile() {
        var p=policy(); var disabled = new Choice(false,"native-chat",Mode.NATIVE,"native-profile");
        assertTrue(p.apply(0,Map.of(CHAT,disabled),Set.of()).ok());
        assertEquals(Code.DENIED,p.begin(1,CHAT,Set.of()).check().code());
        assertEquals("native-profile",p.snapshot().choices().get(CHAT).profile());
        assertTrue(p.begin(1,WORK,Set.of()).check().ok());
    }
    @Test void policyCloseAndForeignTicketsAreRejected() {
        var p=policy(); var q=policy(); var t=p.begin(0,CHAT,Set.of()).ticket();
        assertFalse(q.valid(t)); assertFalse(q.finish(t));
        p.close(); assertFalse(p.valid(t));
        assertEquals(Code.STALE,p.apply(0,Map.of(),Set.of()).code());
    }
    @Test void policyCapacityAndSnapshotsAreBoundedImmutable() {
        var p=new LinkPolicy(binding(false),catalog(),Map.of(CHAT,nativeChat()),Set.of(),1);
        p.begin(0,CHAT,Set.of()); assertEquals(Code.CAPACITY,p.begin(0,CHAT,Set.of()).check().code());
        assertThrows(UnsupportedOperationException.class,()->p.snapshot().choices().clear());
    }
    @Test void resourceAcquisitionAllOrNothing() {
        var a=new ControlArbiter(()->0L,10);var first=a.acquire("one",Set.of("body:n"),10).lease();
        assertEquals(Code.BUSY,a.acquire("two",Set.of("body:n","box:x"),10).check().code());
        assertTrue(a.acquire("three",Set.of("box:x"),10).check().ok());
        assertTrue(a.allows(first,"one","body:n"));
        assertFalse(a.allows(first,"two","body:n"));
    }
    @Test void expirationDoesNotMeanStopped() {
        var time=new AtomicLong();var a=new ControlArbiter(time::get,10);
        var first=a.acquire("one",Set.of("n"),10).lease();time.set(10);
        assertFalse(a.allows(first,"one","n"));
        assertEquals(Code.BUSY,a.acquire("two",Set.of("n"),10).check().code());
        assertTrue(a.acknowledgeStopped(first));
        var next=a.acquire("two",Set.of("n"),10).lease();
        assertTrue(next.epoch()>first.epoch());assertFalse(a.allows(first,"one","n"));
        assertFalse(a.acknowledgeStopped(first));assertTrue(a.allows(next,"two","n"));
    }
    @Test void revokeKeepsOccupancyAndForeignAuthorityCannotRelease() {
        var a=new ControlArbiter(()->0L,10);var b=new ControlArbiter(()->0L,10);
        var lease=a.acquire("one",Set.of("n"),10).lease();a.revoke(lease);
        assertFalse(a.allows(lease,"one","n"));assertFalse(b.acknowledgeStopped(lease));
        assertEquals(Code.BUSY,a.acquire("two",Set.of("n"),10).check().code());
        a.close();assertEquals(Code.STALE,a.acquire("two",Set.of("m"),10).check().code());
    }
    @Test void authorityFailClosesOnClockRegression() {
        var time=new AtomicLong(20);var a=new ControlArbiter(time::get,2);
        var lease=a.acquire("one",Set.of("n"),10).lease();time.set(19);
        assertThrows(IllegalStateException.class,()->a.allows(lease,"one","n"));
        assertEquals(Code.STALE,a.acquire("two",Set.of("m"),10).check().code());
    }
    @Test void concurrentResourceClaimHasOneWinner() throws Exception {
        var a=new ControlArbiter(()->0L,2);var pool=Executors.newFixedThreadPool(8);
        try {
            var tasks=new ArrayList<Callable<Boolean>>();
            for(int i=0;i<32;i++){String owner="o"+i;tasks.add(()->a.acquire(owner,Set.of("n"),10).check().ok());}
            int winners=0;for(var f:pool.invokeAll(tasks))if(f.get())winners++;
            assertEquals(1,winners);
        } finally { pool.shutdownNow(); }
    }
    @Test void delegationDoesNotReplaceBusyOrNewGoal() {
        var d=new DelegationLedger(5);var a=goal("A",1);var b=goal("B",2);
        assertEquals(Code.BUSY,d.accept(key("x"),"collect",a,a,true).check().code());
        assertEquals(Code.STALE,d.accept(key("x"),"collect",a,b,false).check().code());
        var h=d.accept(key("x"),"collect",a,a,false).handle();
        assertEquals(Code.STALE,d.cancel(h,b).code());
        assertEquals(State.ACCEPTED,d.state(h));
        assertEquals(Code.STALE,d.start(h,b).code());
    }
    @Test void idempotencyReplaysReceiptNotExecution() {
        var d=new DelegationLedger(5);var a=goal("A",1);
        var h=d.accept(key("x"),"collect",a,a,false).handle();d.start(h,a);
        var replay=d.accept(key("x"),"collect",a,a,true);
        assertSame(h,replay.handle());assertEquals(State.RUNNING,replay.state());
        assertEquals(Code.DENIED,d.accept(key("x"),"different",a,a,false).check().code());
        assertEquals(Code.BUSY,d.accept(key("y"),"collect",a,a,false).check().code());
    }
    @Test void cancelIsNotTerminalAndUnknownIsNotFree() {
        var d=new DelegationLedger(5);var a=goal("A",1);
        var h=d.accept(key("x"),"collect",a,a,false).handle();d.cancel(h,a);
        assertEquals(State.CANCEL_REQUESTED,d.state(h));
        assertEquals(Code.DENIED,d.start(h,a).code());d.uncertain(h);
        assertEquals(Code.BUSY,d.accept(key("y"),"collect",a,a,false).check().code());
        d.settle(h,a,State.CANCELLED,"stop:123");
        assertTrue(d.accept(key("y"),"collect",a,a,false).check().ok());
    }
    @Test void lateEvidenceStaysWithOriginalRequest() {
        var d=new DelegationLedger(5);var a=goal("A",1);var b=goal("B",2);
        var h=d.accept(key("x"),"collect",a,a,false).handle();d.close();
        assertEquals(Code.STALE,d.settle(h,b,State.SUCCEEDED,"wrong").code());
        assertTrue(d.settle(h,a,State.SUCCEEDED,"world-fact:A").ok());
        assertEquals("world-fact:A",d.evidence(h));
        assertEquals(Code.STALE,d.settle(h,a,State.FAILED,"late").code());
        assertEquals(Code.STALE,d.accept(key("y"),"collect",b,b,false).check().code());
    }
    @Test void ledgerDoesNotEvictReceiptsToMakeReplaysLookNew() {
        var d=new DelegationLedger(1);var a=goal("A",1);
        var h=d.accept(key("x"),"collect",a,a,false).handle();d.settle(h,a,State.FAILED,"failed");
        assertEquals(Code.CAPACITY,d.accept(key("y"),"collect",a,a,false).check().code());
        assertSame(h,d.accept(key("x"),"collect",a,a,false).handle());
    }
    @Test void combinedFixtureSwitchesVoiceWithoutStealingExecution() {
        var p=policy();var time=new AtomicLong();var a=new ControlArbiter(time::get,4);
        var d=new DelegationLedger(4);var goal=goal("delegated",1);
        var request=d.accept(key("collect"),"wood",goal,goal,false).handle();
        var work=p.begin(0,WORK,Set.of()).ticket();var lease=a.acquire("rdd",Set.of("body:n"),100).lease();
        assertTrue(d.start(request,goal).ok());
        assertTrue(p.apply(0,Map.of(CHAT,crossChat()),Set.of("neutral-adapter")).ok());
        assertTrue(p.valid(work));assertTrue(a.allows(lease,"rdd","body:n"));
        assertEquals(Code.BUSY,a.acquire("maid",Set.of("body:n"),100).check().code());
        assertTrue(d.settle(request,goal,State.SUCCEEDED,"fixture-world:wood-in-numen").ok());
        a.acknowledgeStopped(lease);p.finish(work);
        var output=p.begin(1,CHAT,Set.of("neutral-adapter")).ticket();
        assertTrue(p.valid(output));assertEquals(State.SUCCEEDED,d.state(request));
        assertEquals("msc-profile",output.choice().profile());
    }
}
