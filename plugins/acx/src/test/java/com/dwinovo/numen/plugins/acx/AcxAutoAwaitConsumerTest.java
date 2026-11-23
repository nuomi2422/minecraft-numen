package com.dwinovo.numen.plugins.acx;

import com.dwinovo.numen.acx.api.*;
import com.dwinovo.numen.acx.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real registry/bridge/adapter/runner/session; host actions are explicit controlled ports.
 * No MC world, body movement or network effect is inferred from these tests. */
class AcxAutoAwaitConsumerTest {
    private static final class Port implements AcxToolPort {
        final String name; final java.util.function.Function<AcxTool.AcxCallContext,Result> action;
        final AtomicInteger calls=new AtomicInteger();
        Port(String name,java.util.function.Function<AcxTool.AcxCallContext,Result> action){this.name=name;this.action=action;}
        public String name(){return name;}
        public AcxPortSchema schema(){return AcxPortSchema.builder().build();}
        public Result invoke(Map<String,Object> params){throw new AssertionError("original context required");}
        public Result invoke(Map<String,Object> params,AcxTool.AcxCallContext ctx){calls.incrementAndGet();return action.apply(ctx);}
    }
    private static void hosted(AcxRegistry registry,List<AcxToolPort> ports,AcxCompletionProbe probe) throws Exception {
        // Before the implementation this uses the exact production registration path and exposes
        // the missing autonomous continuation as a business assertion, not compilation failure.
        try { AcxHostBridge.class.getMethod("registerAll",AcxToolRegistry.class,List.class,AcxCompletionProbe.class,boolean.class)
                .invoke(null,registry,ports,probe,true); }
        catch(NoSuchMethodException beforeFix){AcxHostBridge.registerAll(registry,ports,probe);}
    }
    private static AcxDefinition plan(String name,List<AcxStep> steps){return new AcxDefinition(name,"1","",List.of(),steps,"","");}
    private static AcxTool marker(AtomicInteger count){return new AcxTool(){
        public String name(){return "tail";} public AcxToolSchema schema(){return AcxToolSchema.builder().build();}
        public AcxStepOutcome execute(Map<String,Object> p,AcxCallContext c){count.incrementAndGet();return AcxStepOutcome.success(Map.of("verified",true));}};}
    private static AcxStep call(String id,String tool){return new AcxStep(id,tool,Map.of());}

    @Test void oneExecuteCrossesGotoLateInteractReplyAndCollectWithoutResending() throws Exception {
        var tail=new AtomicInteger();var polls=new AtomicInteger();var probes=new ConcurrentHashMap<String,AtomicInteger>();
        var move=new Port("goto",c->AcxToolPort.Result.accepted("t-move",Map.of(),"accepted"));
        var dig=new Port("interact_at",c->{throw new AcxToolPort.ReplyPendingAfterDispatchException("already dispatched",()->
                polls.incrementAndGet()<3?Optional.empty():Optional.of(AcxToolPort.Result.completed(Map.of("changed",true))));});
        var pickup=new Port("collect_items",c->AcxToolPort.Result.accepted("t-pickup",Map.of(),"accepted"));
        AcxCompletionProbe probe=task->probes.computeIfAbsent(task,k->new AtomicInteger()).incrementAndGet()<2
                ?AcxCompletionProbe.Snapshot.running(task):AcxCompletionProbe.Snapshot.done(Map.of("collected",1),task);
        var registry=new AcxRegistry();hosted(registry,List.of(move,dig,pickup),probe);registry.register(marker(tail));
        var manager=new AcxSessionManager(AcxRunner.builder().tools(registry).build(),Runnable::run);
        String run=manager.start(plan("one-execute",List.of(call("move","goto"),call("dig","interact_at"),call("pickup","collect_items"),call("done","tail"))),Map.of());
        var result=manager.session(run);
        assertEquals(AcxStatus.SUCCESS,result.record().status(),"one execute must await original receipts, not require external resume");
        assertEquals(0,result.attemptId(),"automatic waiting must not create another resume attempt or run budget");
        assertEquals(1,move.calls.get());assertEquals(1,dig.calls.get());assertEquals(1,pickup.calls.get());assertEquals(1,tail.get());
        assertTrue(polls.get()>=3,"late reply must actually be read");
    }

    @Test void unknownStandingBusyAndGuardNeverAutomaticallyAdvance() throws Exception {
        for(String mode:List.of("unknown","standing","busy","guard")){
            var tail=new AtomicInteger();var registry=new AcxRegistry();
            var port=new Port("action",c->mode.equals("standing")?AcxToolPort.Result.acceptedStanding("t",Map.of(),"standing")
                    :mode.equals("busy")?AcxToolPort.Result.bodyBusy("foreign","busy")
                    :AcxToolPort.Result.accepted("t",Map.of(),"accepted"));
            hosted(registry,List.of(port),task->AcxCompletionProbe.Snapshot.unknown("no matching terminal"));registry.register(marker(tail));
            AcxStep first=mode.equals("guard")?new AcxStep("gate","guard",Map.of("condition",Map.of("field",1,"op",">","value",2))):call("action","action");
            var record=AcxRunner.builder().tools(registry).build().run(plan(mode,List.of(first,call("done","tail"))),Map.of());
            assertEquals(AcxStatus.PAUSED,record.status(),mode);assertEquals(0,tail.get(),mode);assertTrue(port.calls.get()<=1,mode);
        }
    }

    @Test void cancellationAndBodyRetirementStopOnlyTheirOriginalWaitingRun() throws Exception {
        for(boolean retire:List.of(false,true)){
            var invoked=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);var tail=new AtomicInteger();
            try {
                var registry=new AcxRegistry();var port=new Port("action",c->{invoked.countDown();return AcxToolPort.Result.accepted("t",Map.of(),"accepted");});
                hosted(registry,List.of(port),task->AcxCompletionProbe.Snapshot.running("in flight"));registry.register(marker(tail));
                var manager=new AcxSessionManager(AcxRunner.builder().tools(registry).build(),executor);Object body=new Object();
                String run=manager.startBound(plan("cancel",List.of(call("a","action"),call("done","tail"))),Map.of(),new AcxRunBinding("owner",body,null));
                assertTrue(invoked.await(2,TimeUnit.SECONDS));
                if(retire)manager.invalidateBody(body);else assertTrue(manager.cancel(run),"the waiting run must still be cancellable");
                executor.shutdown();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));
                assertNotEquals(AcxStatus.SUCCESS,manager.session(run).record().status());assertEquals(0,tail.get());assertEquals(1,port.calls.get());
            } finally {executor.shutdownNow();}
        }
    }

    @Test void otherRunsCanComputeWhileOriginalBodyWaits() throws Exception {
        var invoked=new CountDownLatch(1);var release=new AtomicBoolean();var executor=Executors.newFixedThreadPool(2);
        try {
            var registry=new AcxRegistry();var port=new Port("action",c->{invoked.countDown();return AcxToolPort.Result.accepted("t",Map.of(),"accepted");});
            hosted(registry,List.of(port),task->release.get()?AcxCompletionProbe.Snapshot.done(Map.of(),"done"):AcxCompletionProbe.Snapshot.running("busy"));
            var computed=new CountDownLatch(1);registry.register(marker(new AtomicInteger()));
            registry.register(new AcxTool(){public String name(){return "compute";}public AcxToolSchema schema(){return AcxToolSchema.builder().build();}
                public AcxStepOutcome execute(Map<String,Object> p,AcxCallContext c){computed.countDown();return AcxStepOutcome.success(Map.of());}});
            var manager=new AcxSessionManager(AcxRunner.builder().tools(registry).build(),executor);
            String waiting=manager.start(plan("waiting",List.of(call("a","action"),call("done","tail"))),Map.of());
            assertTrue(invoked.await(2,TimeUnit.SECONDS));
            manager.start(plan("independent",List.of(call("c","compute"))),Map.of());assertTrue(computed.await(2,TimeUnit.SECONDS));
            release.set(true);executor.shutdown();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));
            assertEquals(AcxStatus.SUCCESS,manager.session(waiting).record().status());assertEquals(1,port.calls.get());
        } finally {executor.shutdownNow();}
    }

    @Test void matchingFailedBodyRequiresReobservationNotAnAutomaticIgnoredFailure() throws Exception {
        var tail=new AtomicInteger();var registry=new AcxRegistry();
        var ref=new AcxBodyAction.Ref("t","call","action","owner","world","server",1);
        var port=new Port("action",c->AcxToolPort.Result.accepted("t",Map.of(),"accepted").withBodyAction(ref));
        AcxCompletionProbe probe=new AcxCompletionProbe(){public Snapshot probe(String t){throw new AssertionError("tagged calls must not use id-only probe");}
            public AcxBodyAction.Snapshot bodyAction(AcxBodyAction.Ref r,AcxTool.AcxCallContext c){return new AcxBodyAction.Snapshot(ref,AcxBodyAction.State.FAILED,AcxBodyAction.Progress.UNKNOWN,AcxBodyAction.Route.UNKNOWN);}};
        hosted(registry,List.of(port),probe);registry.register(marker(tail));
        var result=AcxRunner.builder().tools(registry).build().run(plan("failed",List.of(
                new AcxStep("a","action",Map.of("ignore_failure",true)),call("done","tail"))),Map.of());
        assertEquals(AcxStatus.PAUSED,result.status());assertEquals(0,tail.get());assertEquals(1,port.calls.get());
    }
}
