package com.dwinovo.numen.rdd.side;

import com.dwinovo.numen.rdd.side.sleep.SleepSideTaskTrigger;
import com.dwinovo.numen.rdd.side.sleep.SleepSideTaskType;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-12 live counterexample: mid_operation repeated while skeleton deaths accumulated.
 * Runs real registered sleep type/trigger through Engine, never a replacement policy.
 * The optional risk port is supplied by name so e826 still COMPILES; missing facts do not
 * throw ClassNotFound or manufacture a business red. The assertion is on missing activation.
 * No game, build, or fake sleep/death is required by this test.
 */
class SleepRiskPreemptionTest {
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000ab");
    private static final MainlineResumeToken TOKEN = new MainlineResumeToken(C,"chain","goal","primary","step",3,3);

    @Test void credibleNightRiskReevaluatesBeforeBusyDeadline() {
        Fixture f = new Fixture();
        f.tick();
        assertFalse(f.engine.isActive(C), "safe short busy may defer");
        f.now += 20;
        f.risk = "HOSTILES";
        f.tick();
        assertTrue(f.engine.isActive(C), "credible new night risk must not wait 400 ticks behind mid_operation");
        assertEquals(TOKEN, f.engine.current(C).orElseThrow().mainlineToken(), "preserve original chain and step");
        String context = new SleepSideTaskType().renderContext(f.engine.current(C).orElseThrow(), f.port);
        assertTrue(context.contains("庇护") || context.contains("挖三填一"), "no-bed survival option reaches active context");
    }

    @Test void sustainedBusyRiskDoesNotStarveAndDoesNotRepeatCreation() {
        Fixture f = new Fixture();
        f.risk = "HOSTILES";
        for (int i=0; i<12; i++) { f.tick(); f.now += 20; }
        assertTrue(f.engine.isActive(C), "non-survival busy cannot starve credible night risk indefinitely");
        assertEquals(1, f.events.count("side_task_created"));
    }

    @Test void unknownFactsDoNotAutomaticallyPreemptBusy() {
        Fixture f = new Fixture();
        for (int i=0; i<5; i++) { f.tick(); f.now += 20; }
        assertFalse(f.engine.isActive(C));
        assertEquals(1, f.events.count("side_task_deferred"), "unchanged facts do not spam each tick");
    }

    @Test void explicitNightIntentAndDeathKeepPriority() {
        Fixture f = new Fixture(); f.risk = "HOSTILES"; f.objective = "通宵夜间活动";
        f.tick(); assertFalse(f.engine.isActive(C));
        Fixture death = new Fixture(); death.risk = "HOSTILES";
        death.engine.tick(C, death.port, TOKEN, true);
        assertFalse(death.engine.isActive(C));
    }

    private static final class Fixture {
        long now = 14000;
        String risk = "UNKNOWN", objective = "收集木材";
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = new SideTaskEngine(events, token -> token);
        SideTaskWorldPort port = (SideTaskWorldPort) Proxy.newProxyInstance(
                SideTaskWorldPort.class.getClassLoader(), new Class<?>[]{SideTaskWorldPort.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "gameTime", "dayTime" -> now;
                        case "gameDay" -> 0L;
                        case "hasDayNightCycle", "bodyBusy" -> true;
                        case "worldId" -> "minecraft:overworld";
                        case "mainlineObjective" -> objective;
                        case "pollWake" -> SideTaskWorldPort.Wake.NONE;
                        case "sleepRisk" -> enumValue(method.getReturnType(), risk);
                        default -> method.getReturnType() == boolean.class ? false : method.getReturnType() == int.class ? -1 : null;
                    };
                });
        Fixture() { engine.registerType(new SleepSideTaskType()); engine.registerTrigger(new SleepSideTaskTrigger()); }
        void tick() { engine.tick(C, port, TOKEN, false); }
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> type, String name) { return Enum.valueOf((Class)type, name); }
}
