package com.dwinovo.numen.rdd.side;

import com.dwinovo.numen.rdd.side.sleep.SleepSideTaskType;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Optional API compatibility, not a fabricated trusted producer or live overnight proof. */
class ExplicitOvernightCompatibilityTest {
    private final UUID id=UUID.randomUUID();
    private final MainlineResumeToken token=new MainlineResumeToken(id,"chain","goal","primary","sub",1,1);
    private SideTask create(SleepSideTaskType type){return type.create(new SideTaskType.CreationContext(id,"sleep-fixture",1,0,13000,25000,token,Map.of()));}
    @Test void bareWakeAndSleepingNeverUpgradeTheDefaultUnknownPort(){
        FakeWorldPort world=new FakeWorldPort().at(13000,0).dayTime(13000).spawnBound(true).asleep(id,true).wake(id,SideTaskWorldPort.Wake.DAYBREAK);
        SideTask task=create(new SleepSideTaskType(SleepSideTaskType.Goal.OVERNIGHT));
        assertSame(SleepCycleEvidence.UNKNOWN,world.pollSleepCycle(task));
        var verdict=new SleepSideTaskType().evaluate(task,world);
        assertEquals(SideTaskVerdict.Kind.CONTINUE,verdict.kind());
        assertEquals("false",verdict.data().get("overnight_success"));
        assertEquals(SideTaskWorldPort.Wake.DAYBREAK,world.pollWake(id),"scoped path does not consume old wake or infer its provenance");
    }
    @Test void noArgumentConstructionAndUnmarkedStoredTaskRetainLegacyBinding(){
        SleepSideTaskType legacy=new SleepSideTaskType();SideTask task=create(legacy);
        assertFalse(task.data().containsKey(SleepSideTaskType.KEY_GOAL));
        FakeWorldPort world=new FakeWorldPort().at(13000,0).spawnBound(false);
        var first=legacy.evaluate(task,world);task=task.withData(first.data());
        world.spawnBound(true);assertEquals(SideTaskVerdict.Kind.COMPLETE,legacy.evaluate(task,world).kind());
        assertTrue(legacy.renderContext(task,world).contains("不需要睡满一夜"));
    }
    @Test void explicitGoalPersistsWithoutTurningOldInstancesIntoOvernight(){
        SideTaskEngine source=new SideTaskEngine((name,data)->{},t->t);
        var explicit=new SleepSideTaskType(SleepSideTaskType.Goal.OVERNIGHT);source.registerType(explicit);
        var world=new FakeWorldPort().at(13000,0);assertTrue(source.startNow(explicit,id,world,token,Map.of()));
        SideTaskEngine restored=new SideTaskEngine((name,data)->{},t->t);restored.registerType(new SleepSideTaskType());restored.restore(source.toJson());
        SideTask current=restored.current(id).orElseThrow();assertTrue(SleepSideTaskType.isOvernight(current));
        world.asleep(id,true).wake(id,SideTaskWorldPort.Wake.DAYBREAK);restored.tick(id,world,token,false);
        assertTrue(restored.isActive(id),"persisted goal must still require a fresh scoped producer, not legacy fields");
    }
}
