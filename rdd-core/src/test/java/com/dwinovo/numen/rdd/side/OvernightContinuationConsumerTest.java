package com.dwinovo.numen.rdd.side;

import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.*;
import com.dwinovo.numen.rdd.side.sleep.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Characterizes the existing shared sleep endpoint; GREEN IS NOT full-night acceptance.
 * Real Type/Engine/TaskChain/token checks, controlled world facts only. No SleepOps, MC,
 * wake watcher, event-bus, skill adoption or BodyInstruction execution is simulated as real.
 * Binding is still a legitimate operation; this suite does not change its SUCCESS contract. */
class OvernightContinuationConsumerTest {
    private static final UUID ID=UUID.fromString("00000000-0000-0000-0000-000000000055");
    private record Event(String name,long at,Map<String,Object> data) {}
    private static final class Fixture {
        final FakeWorldPort world=new FakeWorldPort().at(13_000,0).dayTime(13_000).spawnBound(false).asleep(ID,false);
        final TaskChain chain=new TaskChain(new Goal("g","continue gathering",List.of(
            new PrimaryGoal("p","gather",List.of(Subtask.hardCoded("s","original work",Map.of("asset_key","minecraft:bread","minimum",1)))))));
        TaskChain current=chain;
        final MainlineResumeToken token;
        final List<Event> events=new ArrayList<>();
        final SideTaskEngine engine;
        final String chainBefore;
        Fixture(){
            assertTrue(chain.activateCurrentWithSnapshot(PlanningAssetSnapshot.from(Map.of(),new AssetRegistry())));
            token=new MainlineResumeToken(ID,chain.chainInstanceId(),chain.goal().id(),chain.currentPrimary().id(),
                chain.currentSubtask().id(),chain.planRevision(),chain.planRevision());
            chainBefore=chain.toJson();
            engine=new SideTaskEngine((name,data)->events.add(new Event(name,world.gameTime(),Map.copyOf(data))),candidate->{
                if(candidate==null||current==null||!ID.equals(candidate.companionId()))return null;
                Subtask sub=current.currentSubtask();
                return candidate.matches(current.chainInstanceId(),current.goal().id(),current.currentPrimary().id(),
                    sub==null?null:sub.id(),current.planRevision())?candidate:null;
            });
            engine.registerType(new SleepSideTaskType());engine.registerTrigger(new SleepSideTaskTrigger());
        }
        String start(){tick();String id=engine.current(ID).orElseThrow().instanceId();tick();assertTrue(engine.isActive(ID));return id;}
        void tick(){engine.tick(ID,world,token,false);}
        Event last(String name){return events.stream().filter(e->e.name().equals(name)).reduce((a,b)->b).orElseThrow();}
        long count(String name){return events.stream().filter(e->e.name().equals(name)).count();}
        void step(){world.at(world.gameTime()+20,world.gameDay());}
        void unchangedMainline(){assertSame(chain,current);assertEquals(chainBefore,chain.toJson(),
            "a resumed event is not an actual mainline/body execution");}
    }

    @Test void boundOnlyCompletesLegacySideBeforeAnySleepOrWake(){
        Fixture f=new Fixture();String instance=f.start();f.step();f.world.spawnBound(true);f.tick();
        assertFalse(f.world.isSleeping(ID));assertEquals(SideTaskWorldPort.Wake.NONE,f.world.pollWake(ID));
        assertFalse(f.engine.isActive(ID));assertEquals(1,f.count("side_task_completed"));
        assertEquals(instance,f.last("side_task_completed").data().get("instanceId"));
        assertTrue(f.last("side_task_completed").data().get("reason").toString().contains("spawn point bound"));
        assertEquals(f.world.gameTime(),f.last("side_task_resumed_mainline").at());
        assertEquals(Boolean.TRUE,f.last("side_task_resumed_mainline").data().get("mainlineResumed"));f.unchangedMainline();
    }
    @Test void vanillaRefusalHasNoSeparateInputAfterTheBindingFactReachedTheSideTask(){
        Fixture f=new Fixture();f.start();f.step();
        // Controlled values corresponding to a binding-success/sleep-refused reply, NOT an executed SleepOps.
        Map<String,Object> reply=Map.of("success",true,"bound",true,"sleeping",false,"vanilla_rejection","NOT_POSSIBLE_NOW");
        f.world.spawnBound((Boolean)reply.get("bound")).asleep(ID,(Boolean)reply.get("sleeping"));f.tick();
        assertEquals(Boolean.TRUE,reply.get("success"),"legitimate binding result must not be relabelled FAIL");
        assertEquals(1,f.count("side_task_completed"));assertFalse(f.world.isSleeping(ID));
        assertFalse(f.last("side_task_completed").data().toString().contains("NOT_POSSIBLE_NOW"),
            "the existing endpoint sees bound/sleep facts, not this separate tool refusal");f.unchangedMainline();
    }
    @Test void justEnteringSleepCompletesLegacyOwnerBeforeTheMatchingDaybreakCanBeConsumed(){
        Fixture f=new Fixture();String instance=f.start();f.step();f.world.asleep(ID,true);f.tick();
        long completedAt=f.last("side_task_completed").at();
        assertFalse(f.engine.isActive(ID));assertEquals("observed companion sleeping during this side task",
            f.last("side_task_completed").data().get("reason"));
        assertEquals(completedAt,f.last("side_task_resumed_mainline").at(),"current resume is at entry, not at wake");
        f.world.at(24_000,1).dayTime(0).day(true).asleep(ID,false).wake(ID,SideTaskWorldPort.Wake.DAYBREAK);f.tick();
        assertEquals(1,f.count("side_task_completed"));assertEquals(instance,f.last("side_task_completed").data().get("instanceId"));
        assertTrue(completedAt<24_000);assertEquals(SideTaskWorldPort.Wake.DAYBREAK,f.world.pollWake(ID),
            "same owner's later wake was not consumed: that sleep owner was already terminal");f.unchangedMainline();
    }
    @Test void suppliedDaybreakCanCompleteAnActiveOwnerWithoutAnyRecordedSleep(){
        Fixture f=new Fixture();f.start();f.step();f.world.wake(ID,SideTaskWorldPort.Wake.DAYBREAK);f.tick();
        assertFalse(f.world.isSleeping(ID));assertEquals(1,f.count("side_task_completed"));
        assertEquals("slept through the night and woke at daybreak",f.last("side_task_completed").data().get("reason"));
        assertEquals(Boolean.TRUE,f.last("side_task_resumed_mainline").data().get("mainlineResumed"));
        // This is an uncorrelated enum supplied by a test port, not a matched live sleep/wake proof.
        f.unchangedMainline();
    }
    @Test void foreignWorldOrForeignProducerWakeCannotBeDistinguishedByTheExistingEnum(){
        for(String change:List.of("world","producer")){
            Fixture f=new Fixture();f.start();f.step();
            if(change.equals("world"))f.world.world("another-world-with-same-day-cycle");
            // A foreign producer's asserted DAYBREAK has the exact same WorldPort enum payload.
            f.world.wake(ID,SideTaskWorldPort.Wake.DAYBREAK);f.tick();
            assertEquals(1,f.count("side_task_completed"),change);
            assertEquals(Boolean.TRUE,f.last("side_task_resumed_mainline").data().get("mainlineResumed"),change);
            assertFalse(f.last("side_task_completed").data().containsKey("world_id"));
            assertFalse(f.last("side_task_completed").data().containsKey("server_session"));
        }
    }
    @Test void interruptedWakeWithoutBindingDoesNotCompleteAndChangedChainDoesNotGetResumePermission(){
        Fixture f=new Fixture();f.start();f.step();f.world.wake(ID,SideTaskWorldPort.Wake.INTERRUPTED);f.tick();
        assertTrue(f.engine.isActive(ID));assertEquals(0,f.count("side_task_completed"));
        f.current=new TaskChain(new Goal("g","replacement",List.of(new PrimaryGoal("p","replacement",List.of(
            Subtask.hardCoded("s","replacement work",Map.of("minimum",1)))))));
        f.world.wake(ID,SideTaskWorldPort.Wake.DAYBREAK);f.tick();
        assertEquals(1,f.count("side_task_completed"));
        assertEquals(Boolean.FALSE,f.last("side_task_resumed_mainline").data().get("mainlineResumed"),
            "real chain-instance comparison rejects a same-name replacement, not token->token");
    }
    @Test void currentCreationDoesNotOfferBindingVersusOvernightGoalSelection(){
        Fixture f=new Fixture();SleepSideTaskType type=new SleepSideTaskType();
        var created=type.create(new SideTaskType.CreationContext(ID,"explicit",7,0,13_000,25_000,f.token,
            Map.of("requested_goal","overnight","world_id","fixture-world","server_session","fixture-session")));
        assertTrue(created.data().isEmpty(),"current Type ignores requested data; no supported goal/mode is invented by this test");
        assertEquals("sleep",created.typeId());
        assertTrue(type.renderContext(created,f.world).contains("不需要睡满一夜"),"legacy binding/entry contract is explicit");
    }
}
