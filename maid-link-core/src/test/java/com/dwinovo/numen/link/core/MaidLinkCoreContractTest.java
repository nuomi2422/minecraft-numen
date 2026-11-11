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
        assertEquals(Code.BUSY,p.begin(1,CHAT,Set.of()).check().code());
        assertEquals(Code.BUSY,p.apply(1,Map.of(CHAT,crossChat()),Set.of("neutral-adapter")).code());
        assertTrue(p.finish(ticket));
        assertTrue(p.apply(1,Map.of(CHAT,crossChat()),Set.of("neutral-adapter")).ok());
        assertFalse(p.valid(ticket));
        assertEquals("msc-profile",p.begin(2,CHAT,Set.of("neutral-adapter")).ticket().choice().profile());
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
        assertEquals(Code.UNAVAILABLE,p.apply(1,Map.of(CHAT,crossChat()),Set.of()).code());
        assertTrue(p.resume(1,CHAT).ok());
        assertFalse(p.valid(old));
        assertEquals(nativeChat(),p.begin(2,CHAT,Set.of()).ticket().choice());
    }
    @Test void staleResumeCannotUndoLaterDrain() {
        var p=policy();var work=p.begin(0,WORK,Set.of()).ticket();
        assertTrue(p.drain(0,Set.of(CHAT)).ok());
        assertTrue(p.resume(1,CHAT).ok());
        assertEquals(Code.STALE,p.drain(0,Set.of(CHAT)).code());
        assertTrue(p.drain(2,Set.of(CHAT)).ok());
        assertEquals(Code.STALE,p.resume(1,CHAT).code());
        assertEquals(Code.BUSY,p.begin(3,CHAT,Set.of()).check().code());
        assertTrue(p.valid(work));
        assertEquals(Code.STALE,p.begin(0,WORK,Set.of()).check().code());
        assertTrue(p.resume(3,CHAT).ok());
        assertTrue(p.begin(4,CHAT,Set.of()).check().ok());
    }
    @Test void duplicateDrainAtCurrentRevisionIsIdempotent() {
        var p=policy();p.drain(0,Set.of(CHAT));
        assertTrue(p.drain(1,Set.of(CHAT)).ok());assertEquals(1,p.snapshot().revision());
        p.resume(1,CHAT);assertTrue(p.resume(2,CHAT).ok());assertEquals(2,p.snapshot().revision());
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

    // 052/P1-P4 offline extensions, 2026-11-11. No runtime/game adapter is used.
    static LinkConfiguration.Selection selection(Choice choice) {
        return new LinkConfiguration.Selection(choice, "1");
    }
    static LinkConfiguration.Document configuration() {
        return new LinkConfiguration.Document(1, 7, Map.of(CHAT, selection(nativeChat()),
            WORK, selection(new Choice(false, "missing-disabled-provider", Mode.DELEGATE, "酒狐 档案:\n第二行"))));
    }
    @Test void configurationRoundTripPreservesDisabledChoiceProfileVersionAndRevision() {
        var document=configuration();
        assertEquals(document,LinkConfiguration.decode(LinkConfiguration.encode(document)));
        assertTrue(LinkConfiguration.validate(document,binding(false),catalog(),Set.of()).ok());
        assertThrows(UnsupportedOperationException.class,()->document.selections().clear());
    }
    @Test void configurationRejectsUnknownSchemaAndNegativeRevision() {
        String text=LinkConfiguration.encode(configuration());
        assertThrows(IllegalArgumentException.class,()->LinkConfiguration.decode(text.replace("schema=1","schema=2")));
        assertThrows(IllegalArgumentException.class,()->LinkConfiguration.decode(text.replace("revision=7","revision=-1")));
    }
    @Test void configurationRejectsDuplicateEscapedAndUnknownKeys() {
        String text=LinkConfiguration.encode(configuration());
        for(String extra:List.of("\nschema=1", "\n\\u0073chema=1", "\nadmin.command=run", "\nslot.NUMEN.CHAT.extra=bad"))
            assertThrows(IllegalArgumentException.class,()->LinkConfiguration.decode(text+extra));
    }
    @Test void configurationRejectsPartialSlotAndInvalidEnumsOrBoolean() {
        String text=LinkConfiguration.encode(configuration());
        for(String broken:List.of(text.replace("enabled=true","enabled=yes"),
                text.replace("mode=NATIVE","mode=UNKNOWN"),text.replace("slot.NUMEN.CHAT","slot.ROBOT.CHAT"),
                text.replaceAll("(?m)^slot.NUMEN.CHAT.version=.*\\R?","")))
            assertThrows(IllegalArgumentException.class,()->LinkConfiguration.decode(broken));
    }
    @Test void configurationRejectsOversizedInputAndBlankProvider() {
        assertThrows(IllegalArgumentException.class,()->LinkConfiguration.decode("x".repeat(65_537)));
        assertThrows(IllegalArgumentException.class,()->LinkConfiguration.decode(
            LinkConfiguration.encode(configuration()).replace("provider=native-chat","provider=")));
    }
    @Test void configurationRejectsProviderVersionDriftAndMissingDependencies() {
        var changed=new LinkConfiguration.Document(1,0,Map.of(CHAT,
            new LinkConfiguration.Selection(nativeChat(),"2")));
        assertEquals(Code.STALE,LinkConfiguration.validate(changed,binding(false),catalog(),Set.of()).code());
        var cross=new LinkConfiguration.Document(1,0,Map.of(CHAT,selection(crossChat())));
        assertEquals(Code.UNAVAILABLE,LinkConfiguration.validate(cross,binding(false),catalog(),Set.of()).code());
        assertTrue(LinkConfiguration.validate(cross,binding(false),catalog(),Set.of("neutral-adapter")).ok());
    }
    @Test void configurationBadSaveLeavesExistingFileUntouched(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var file=dir.resolve("link.properties");java.nio.file.Files.writeString(file,"original");
        var bad=new LinkConfiguration.Document(1,0,Map.of(CHAT,
            new LinkConfiguration.Selection(nativeChat(),"wrong-version")));
        assertThrows(IllegalArgumentException.class,()->LinkConfiguration.save(file,bad,binding(false),catalog(),Set.of()));
        assertEquals("original",java.nio.file.Files.readString(file));
        try(var paths=java.nio.file.Files.list(dir)){assertEquals(1,paths.count());}
    }
    @Test void configurationGoodSaveReplacesOnlySelectedFile(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var file=dir.resolve("link.properties");java.nio.file.Files.writeString(file,"original");
        LinkConfiguration.save(file,configuration(),binding(false),catalog(),Set.of());
        assertEquals(configuration(),LinkConfiguration.decode(java.nio.file.Files.readString(file)));
        try(var paths=java.nio.file.Files.list(dir)){assertEquals(1,paths.count());}
    }
    static Map<Slot,LinkConfiguration.Selection> nativeTemplate() {
        return Map.of(CHAT,selection(nativeChat()),
            WORK,selection(new Choice(true,"work",Mode.NATIVE,"worker")),
            new Slot(Side.MAID,Capability.CHAT),selection(new Choice(true,"msc-bound",Mode.NATIVE,"soul")));
    }
    @Test void presetsAllowEitherNativePairWithoutOtherBody() {
        var numen=LinkPresets.create(LinkPresets.Preset.NATIVE_NUMEN,0,binding(false),nativeTemplate(),catalog(),Set.of());
        assertTrue(numen.choices().get(CHAT).enabled());assertTrue(numen.choices().get(WORK).enabled());
        assertFalse(numen.choices().containsKey(new Slot(Side.MAID,Capability.CHAT)));
        var maidBinding=new Binding("b","o","w","s",Map.of(Side.MAID,new Body(Side.MAID,"m")));
        var maid=LinkPresets.create(LinkPresets.Preset.NATIVE_MAID,0,maidBinding,nativeTemplate(),catalog(),Set.of());
        assertEquals(Set.of(new Slot(Side.MAID,Capability.CHAT)),maid.choices().keySet());
    }
    @Test void coexistPresetKeepsBothChatsAndNativeExecution() {
        var doc=LinkPresets.create(LinkPresets.Preset.COEXIST,3,binding(true),nativeTemplate(),catalog(),Set.of());
        assertEquals(3,doc.revision());assertTrue(doc.choices().values().stream().allMatch(Choice::enabled));
        assertTrue(doc.choices().values().stream().allMatch(c->c.mode()==Mode.NATIVE));
    }
    @Test void spokespersonPresetMutesOnlyNumenSpeechAndKeepsProfiles() {
        var doc=LinkPresets.create(LinkPresets.Preset.SPOKESPERSON,0,binding(true),nativeTemplate(),catalog(),Set.of());
        assertFalse(doc.choices().get(CHAT).enabled());
        assertEquals("native-profile",doc.choices().get(CHAT).profile());
        assertTrue(doc.choices().get(WORK).enabled());
        assertTrue(doc.choices().get(new Slot(Side.MAID,Capability.CHAT)).enabled());
    }
    @Test void singleSidePresetOnDualBindingExplicitlyDisablesOtherSide() {
        var doc=LinkPresets.create(LinkPresets.Preset.NATIVE_NUMEN,0,binding(true),nativeTemplate(),catalog(),Set.of());
        var maid=doc.choices().get(new Slot(Side.MAID,Capability.CHAT));
        assertFalse(maid.enabled());assertEquals("soul",maid.profile());
    }
    @Test void presetsRejectUnboundCoexistAndCrossCapabilityTemplates() {
        assertThrows(IllegalArgumentException.class,()->LinkPresets.create(LinkPresets.Preset.COEXIST,
            0,binding(false),nativeTemplate(),catalog(),Set.of()));
        var bad=new HashMap<>(nativeTemplate());bad.put(CHAT,selection(crossChat()));
        assertThrows(IllegalArgumentException.class,()->LinkPresets.create(LinkPresets.Preset.COEXIST,
            0,binding(true),bad,catalog(),Set.of("neutral-adapter")));
    }
    @Test void viewHasUnknownHostStatusRatherThanInferringReadyFromSnapshot() {
        var view=LinkStatusView.render(policy().snapshot(),catalog(),Map.of());
        assertTrue(view.rows().stream().allMatch(r->r.status().code()==Code.UNKNOWN));
        assertEquals("1",view.rows().getFirst().providerVersion());
        assertThrows(UnsupportedOperationException.class,()->view.rows().clear());
    }
    @Test void viewPreservesTrustedBusyAndDisabledState() {
        var p=policy();p.apply(0,Map.of(CHAT,new Choice(false,"native-chat",Mode.NATIVE,"native-profile")),Set.of());
        var view=LinkStatusView.render(p.snapshot(),catalog(),Map.of(WORK,new Check(Code.BUSY,"draining")));
        assertEquals(Code.DENIED,view.rows().stream().filter(r->r.slot().equals(CHAT)).findFirst().orElseThrow().status().code());
        assertEquals(Code.BUSY,view.rows().stream().filter(r->r.slot().equals(WORK)).findFirst().orElseThrow().status().code());
    }
    @Test void uiPatchReturnsBusyAndStaleWithoutDrainingOrResuming() {
        var p=policy();var ticket=p.begin(0,CHAT,Set.of()).ticket();
        var busy=LinkStatusView.submit(p,0,Map.of(CHAT,crossChat()),Set.of("neutral-adapter"));
        assertEquals(Code.BUSY,busy.check().code());assertTrue(p.valid(ticket));assertEquals(0,busy.snapshot().revision());
        p.finish(ticket);
        var accepted=LinkStatusView.submit(p,0,Map.of(CHAT,crossChat()),Set.of("neutral-adapter"));
        assertTrue(accepted.check().ok());assertEquals(1,accepted.snapshot().revision());
        assertEquals(Code.STALE,LinkStatusView.submit(p,0,Map.of(CHAT,nativeChat()),Set.of()).check().code());
    }
    @Test void documentSubmissionPinsVersionAndRejectsStaleRevisionWithoutChangingPolicy() {
        var p=policy();var before=p.snapshot();
        var bad=new LinkConfiguration.Document(1,0,Map.of(CHAT,new LinkConfiguration.Selection(nativeChat(),"2")));
        assertEquals(Code.STALE,LinkStatusView.submitDocument(p,bad,binding(false),catalog(),Set.of()).check().code());
        assertEquals(before,p.snapshot());
        var stale=new LinkConfiguration.Document(1,7,Map.of(CHAT,selection(nativeChat())));
        assertEquals(Code.STALE,LinkStatusView.submitDocument(p,stale,binding(false),catalog(),Set.of()).check().code());
        assertEquals(before,p.snapshot());
        var ticket=p.begin(0,CHAT,Set.of()).ticket();
        var valid=new LinkConfiguration.Document(1,0,Map.of(CHAT,selection(crossChat())));
        assertEquals(Code.BUSY,LinkStatusView.submitDocument(p,valid,binding(false),catalog(),Set.of("neutral-adapter")).check().code());
        p.finish(ticket);
        assertTrue(LinkStatusView.submitDocument(p,valid,binding(false),catalog(),Set.of("neutral-adapter")).check().ok());
        assertEquals(crossChat(),p.snapshot().choices().get(CHAT));
    }
    @Test void mutedSpokespersonTemplateStillChecksProviderAndVersion() {
        var bad=new HashMap<>(nativeTemplate());
        bad.put(CHAT,new LinkConfiguration.Selection(nativeChat(),"wrong"));
        assertThrows(IllegalArgumentException.class,()->LinkPresets.create(LinkPresets.Preset.SPOKESPERSON,
            0,binding(true),bad,catalog(),Set.of()));
    }
    @Test void nativeSinglePairIgnoresUnavailableProviderOfDisabledOtherPair() {
        var template=new HashMap<>(nativeTemplate());
        template.put(new Slot(Side.MAID,Capability.CHAT),selection(new Choice(true,"not-installed",Mode.NATIVE,"soul")));
        var doc=LinkPresets.create(LinkPresets.Preset.NATIVE_NUMEN,0,binding(true),template,catalog(),Set.of());
        assertFalse(doc.choices().get(new Slot(Side.MAID,Capability.CHAT)).enabled());
        assertTrue(doc.choices().get(CHAT).enabled());
    }
    static com.dwinovo.numen.worldstate.core.observation.ObservationKey observationKey() {
        return new com.dwinovo.numen.worldstate.core.observation.ObservationKey("world","minecraft:overworld",
            com.dwinovo.numen.worldstate.core.model.Topic.CONTAINERS,"block:1,2,3","");
    }
    static com.dwinovo.numen.worldstate.core.query.ReadResult observed(
            String session,com.dwinovo.numen.worldstate.core.model.Freshness freshness,
            com.dwinovo.numen.worldstate.core.model.Coverage coverage) {
        var key=observationKey();
        var payload=new com.dwinovo.numen.worldstate.core.observation.payload.ContainerPayload(
            key.dimension(),key.target(),27,Map.of());
        var observation=new com.dwinovo.numen.worldstate.core.observation.Observation(key,payload,"cache-fixture",
            com.dwinovo.numen.worldstate.core.model.Origin.DIRECT,10,100,coverage,freshness,1,1,session);
        return com.dwinovo.numen.worldstate.core.query.ReadResult.present(observation,freshness,10,coverage,"cached sample");
    }
    @Test void observationProjectionPreservesExactSourceAndDoesNotClaimPossession() {
        var source=observed("session",com.dwinovo.numen.worldstate.core.model.Freshness.CURRENT,
            com.dwinovo.numen.worldstate.core.model.Coverage.COMPLETE);
        var view=LinkObservationView.project(binding(false),observationKey(),Check.yes(),source,110,20);
        assertTrue(view.check().ok());assertSame(source,view.source());assertTrue(view.currentComplete());
        assertEquals("cached-observation-only",view.check().reason());
    }
    @Test void observationProjectionRejectsWrongWorldSessionAndUnauthorizedAudience() {
        var source=observed("other-session",com.dwinovo.numen.worldstate.core.model.Freshness.CURRENT,
            com.dwinovo.numen.worldstate.core.model.Coverage.COMPLETE);
        var view=LinkObservationView.project(binding(false),observationKey(),Check.yes(),source,110,20);
        assertEquals(Code.STALE,view.check().code());assertNull(view.source());
        var denied=LinkObservationView.project(binding(false),observationKey(),new Check(Code.DENIED,"owner-mismatch"),source,110,20);
        assertEquals(Code.DENIED,denied.check().code());assertNull(denied.source());
        var other=new Binding("b","o","another-world","session",binding(false).bodies());
        assertEquals(Code.DENIED,LinkObservationView.project(other,observationKey(),Check.yes(),source,110,20).check().code());
    }
    @Test void observationProjectionNeverTurnsMissingOrUnavailableIntoEmpty() {
        var missing=com.dwinovo.numen.worldstate.core.query.ReadResult.missing(observationKey());
        assertEquals(Code.UNKNOWN,LinkObservationView.project(binding(false),observationKey(),Check.yes(),missing,110,20).check().code());
        assertEquals(Code.UNAVAILABLE,LinkObservationView.project(binding(false),observationKey(),Check.yes(),null,110,20).check().code());
    }
    @Test void observationProjectionKeepsStalePartialHistoricalUnknown() {
        for(var freshness:com.dwinovo.numen.worldstate.core.model.Freshness.values())
            for(var coverage:com.dwinovo.numen.worldstate.core.model.Coverage.values()) {
                var source=observed("session",freshness,coverage);
                var view=LinkObservationView.project(binding(false),observationKey(),Check.yes(),source,110,20);
                boolean expected=freshness==com.dwinovo.numen.worldstate.core.model.Freshness.CURRENT
                    && coverage==com.dwinovo.numen.worldstate.core.model.Coverage.COMPLETE;
                assertEquals(expected,view.currentComplete());assertEquals(expected,view.check().ok());
                assertSame(source,view.source());
            }
    }
    @Test void observationProjectionRechecksAgeAndRejectsFutureClock() {
        var source=observed("session",com.dwinovo.numen.worldstate.core.model.Freshness.CURRENT,
            com.dwinovo.numen.worldstate.core.model.Coverage.COMPLETE);
        assertFalse(LinkObservationView.project(binding(false),observationKey(),Check.yes(),source,121,20).currentComplete());
        assertFalse(LinkObservationView.project(binding(false),observationKey(),Check.yes(),source,99,20).currentComplete());
        assertTrue(LinkObservationView.project(binding(false),observationKey(),Check.yes(),source,120,20).currentComplete());
        assertThrows(IllegalArgumentException.class,()->LinkObservationView.project(binding(false),observationKey(),Check.yes(),source,110,0));
    }
}
