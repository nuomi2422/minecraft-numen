package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Characterizes the actual Host's automatic registration and same-token resume permission.
 * No automatic production migration and no BodyInstruction execution are claimed. */
class AutomaticSleepGoalConsumerTest {
    public interface Driver extends ScopedSleepCycleConsumerTest.Driver {
        void autoTick();void busy(boolean busy,String risk);boolean overnight();void selectExplicitForNewTasks();
        void restoreCurrentWithExplicitRegistration();void replaceMainline();int interrupted();
    }
    @TempDir static Path temp;
    static Class<?> host;
    @BeforeAll static void compile()throws Exception{
        Path root=Path.of("").toAbsolutePath();while(root!=null&&!Files.isDirectory(root.resolve("plugins/rdd/src/main/java")))root=root.getParent();assertNotNull(root);
        String production=Files.readString(root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/SideTaskHost.java"));
        int declaration=production.indexOf("    private static final SleepSideTaskType SLEEP_TYPE =");
        assertTrue(declaration>=0);String sleepType=production.substring(declaration,production.indexOf(';',declaration)+1);
        String registration="";
        for(String line:production.split("\n"))if(line.contains("ENGINE.registerType(SLEEP_TYPE);")||line.contains("ENGINE.registerTrigger(new SleepSideTaskTrigger());"))registration+=line+"\n";
        assertTrue(registration.contains("registerType")&&registration.contains("registerTrigger"));
        String validator=ScopedSleepCycleConsumerTest.method(production,"    private static MainlineResumeToken validateMainline(");
        // Nested methods use eight-space closing indentation, unlike top-level helper extraction.
        String interrupt=nested(production,"        static boolean mayInterrupt(");
        String tick=nested(production,"        static void tick(SideTaskEngine engine,");
        String fixture=Files.readString(root.resolve("plugins/rdd/src/test/resources/sleep-cycle/SleepCycleHost.java.in"));
        fixture=fixture.replace("implements ScopedSleepCycleConsumerTest.Driver","implements AutomaticSleepGoalConsumerTest.Driver")
            .replace("int resumed;boolean bound;","int resumed;boolean bound,busy;int interrupts;SideTaskWorldPort.SleepRisk risk=SideTaskWorldPort.SleepRisk.CLEAR;")
            .replace("final TaskChain chain=", "TaskChain chain=")
            .replace("t->t!=null&&t.matches(chain.chainInstanceId(),\"g\",\"p\",\"s\",chain.planRevision())?t:null", "SideTaskHost::validateMainline")
            .replace("engine.registerTrigger(new SleepSideTaskTrigger());", "SideTaskHost.registerAutomatic(engine);")
            .replace("case \"bodyBusy\",\"inCombat\"->false;", "case \"bodyBusy\"->busy;case \"inCombat\"->false;")
            .replace("case \"sleepRisk\"->SideTaskWorldPort.SleepRisk.CLEAR;", "case \"sleepRisk\"->risk;");

        String methods="""
            public void autoTick(){SideTaskHost.SleepSafety.tick(engine,id,world,token,false,()->{interrupts++;busy=false;return true;});}
            public void busy(boolean value,String risk){busy=value;this.risk=SideTaskWorldPort.SleepRisk.valueOf(risk);}
            public boolean overnight(){return engine.current(id).map(SleepSideTaskType::isOvernight).orElse(false);}
            public void selectExplicitForNewTasks(){engine.registerType(new SleepSideTaskType(SleepSideTaskType.Goal.OVERNIGHT));}
            public void restoreCurrentWithExplicitRegistration(){String saved=engine.toJson();engine.onServerStopped();engine.registerType(new SleepSideTaskType(SleepSideTaskType.Goal.OVERNIGHT));engine.restore(saved);}
            public void replaceMainline(){chain=new TaskChain(new Goal("g","replacement",List.of(new PrimaryGoal("p","replacement",List.of(Subtask.hardCoded("s","new work",Map.of("minimum",1)))))));}
            public int interrupted(){return interrupts;}
            """;
        fixture=fixture.replace("    public void start(boolean overnight)",methods+"\n    public void start(boolean overnight)");
        String hostExtras=sleepType+"\nstatic void registerAutomatic(SideTaskEngine ENGINE){"+registration+"}\n"
            +validator.replace("private static","static")+"\nstatic class SleepSafety {"+interrupt+tick+"}\n";
        fixture=fixture.replace("class SideTaskHost {","class SideTaskHost {"+hostExtras)
            +"\nclass RddPlugin {static RddRuntime runtime(UUID id){return new RddRuntime(SleepCycleHost.live.chain,new AssetRegistry());}}"
            +"\nclass RddReplanTransition {static boolean deferPlannerDaily(UUID id,MainlineResumeToken token){return false;}}";
        ScopedSleepCycleConsumerTest.tmp=temp;ScopedSleepCycleConsumerTest.compileFixture(fixture);host=ScopedSleepCycleConsumerTest.host;
    }
    private static String nested(String source,String prefix){int a=source.indexOf(prefix),b=source.indexOf("\n        }",source.indexOf('{',a));assertTrue(a>=0&&b>a);return source.substring(a,b+10);}
    Driver fixture()throws Exception{return (Driver)host.getConstructor().newInstance();}
    @Test void actualAutomaticRegistrationCreatesLegacyAndBindingResumesBeforeAnyWake()throws Exception{
        Driver f=fixture();f.autoTick();assertTrue(f.active());assertFalse(f.overnight());
        f.evaluate();f.bind();f.autoTick();assertEquals(1,f.completed());assertEquals(1,f.resumed());
        f.observe(13001,24000,false);f.autoTick();assertEquals(1,f.completed());assertEquals(1,f.resumed());
    }
    @Test void changingRegistrationAffectsOnlyNewCreationNotAnExistingOrRestoredLegacyTask()throws Exception{
        Driver f=fixture();f.autoTick();f.restoreCurrentWithExplicitRegistration();assertFalse(f.overnight());f.bind();f.evaluate();
        // First evaluation snapshots bound=true; no artificial old->new binding is inferred after restore.
        assertTrue(f.active());f.observe(13001,13001,true);f.evaluate();assertEquals(1,f.completed());
        Driver fresh=fixture();fresh.selectExplicitForNewTasks();fresh.autoTick();assertTrue(fresh.overnight());
        fresh.observe(13000,13000,false);fresh.evaluate();fresh.bind();fresh.autoTick();assertEquals(0,fresh.completed());
        fresh.observe(13001,13001,true);fresh.autoTick();assertEquals(0,fresh.resumed());
        fresh.observe(13002,24000,false);fresh.autoTick();assertEquals(1,fresh.completed());assertEquals(1,fresh.resumed());
        fresh.autoTick();assertEquals(1,fresh.resumed());
    }
    @Test void normalBusyAcrossRetryWindowDoesNotAutomaticallyInterrupt()throws Exception{
        for(String risk:new String[]{"CLEAR","UNKNOWN"}){
            Driver f=fixture();f.busy(true,risk);
            for(long tick=13000;tick<=14200;tick+=200){f.observe(tick,tick,false);f.autoTick();}
            assertFalse(f.active(),risk);assertEquals(0,f.interrupted(),risk);assertEquals(0,f.completed(),risk);
        }
    }
    @Test void matchedCycleCompletionDoesNotGiveAReplacementMainlineResumePermission()throws Exception{
        Driver f=fixture();f.selectExplicitForNewTasks();f.autoTick();f.observe(13000,13000,false);f.observe(13001,13001,true);
        f.replaceMainline();f.observe(13002,24000,false);f.autoTick();assertEquals(1,f.completed());assertEquals(0,f.resumed());
    }
}
