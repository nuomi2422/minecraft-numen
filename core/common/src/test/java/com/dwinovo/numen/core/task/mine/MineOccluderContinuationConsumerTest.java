package com.dwinovo.numen.core.task.mine;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exact production onTick/mineProgress with an effective-position digger boundary.
 * The boundary mimics current() exposing an occluder while digStep still expects the ore intent.
 * Does not prove vanilla raycast/interaction or unrestricted terrain permission. */
class MineOccluderContinuationConsumerTest {
    @TempDir static Path temp;static URLClassLoader loader;static Class<?> fixture;
    @BeforeAll static void compile()throws Exception{
        Path root=Path.of("").toAbsolutePath();while(root!=null&&!Files.isDirectory(root.resolve("core/common/src/main/java")))root=root.getParent();assertNotNull(root);
        Path main=root.resolve("core/common/src/main/java/com/dwinovo/numen/core/task/mine");
        String source=Files.readString(main.resolve("MineCompanionTask.java")),methods="";
        for(String signature:List.of("    protected TaskState onTick() {","    private void mineProgress(BlockPos pos) {","    private void clearNoShot() {","    private int inventoryMatch() {","    private List<BlockPos> droppedItems() {","    private boolean nearKnownOre(BlockPos p) {","    private void noteProgress() {","    private TaskState stalledOut() {","    protected Map<String, Object> resultData() {"))methods+=MineHarvestRecoveryConsumerTest.method(source,signature)+"\n";
        String boundaries=MineEffectsConsumerTest.BOUNDARIES.replace("PRODUCTION_METHODS",methods+SCENARIO)
            .replace("static class Level {", "static class Level { BlockPos removed,nonOre;")
            .replace("return new BlockState();", "return new BlockState(){ boolean isAir(){return p.equals(removed);} Object getBlock(){return p.equals(nonOre)?\"stone\":\"ore\";} };")
            .replace("BlockPos current; Result result=Result.PROGRESSING; int calls;", "BlockPos current,ore=new BlockPos(8,64,0),occluder=new BlockPos(7,64,0); Result result=Result.PROGRESSING; int calls; List<BlockPos> intents=new ArrayList<>();")
            .replace("Result digStep(BlockPos p){calls++;return result;}","Result digStep(BlockPos p){calls++;intents.add(p);if(p.x()==9){current=p;return Result.PROGRESSING;}if(calls==1){current=occluder;return Result.PROGRESSING;}current=null;return p.equals(ore)?Result.BROKE_OCCLUDER:Result.BROKE_TARGET;}");
        Path src=temp.resolve("src"),out=temp.resolve("classes");Files.createDirectories(out);
        List<String> args=new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-d",out.toString()));
        MineHarvestRecoveryConsumerTest.add(src,args,"fixture/Effects.java",boundaries);
        MineHarvestRecoveryConsumerTest.add(src,args,"fixture/PartialGather.java",Files.readString(main.resolve("PartialGather.java")).replace("package com.dwinovo.numen.core.task.mine;","package fixture;"));
        MineHarvestRecoveryConsumerTest.add(src,args,"com/dwinovo/numen/core/Constants.java","package com.dwinovo.numen.core;public class Constants{public static final Log LOG=new Log();public static class Log{public void debug(String s,Object...a){}public void info(String s,Object...a){}}}");
        ByteArrayOutputStream errors=new ByteArrayOutputStream();assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,errors,errors,args.toArray(String[]::new)),"SETUP: "+errors);
        loader=new URLClassLoader(new URL[]{out.toUri().toURL()},ClassLoader.getPlatformClassLoader());fixture=loader.loadClass("fixture.Effects");
    }
    @AfterAll static void close()throws Exception{if(loader!=null)loader.close();}
    @Test void continuingAnEffectiveOccluderRetainsOriginalOreIntentAndDoesNotCountItAsOre()throws Exception{
        @SuppressWarnings("unchecked") var r=(Map<String,Object>)fixture.getMethod("occluderCase").invoke(null);
        assertEquals(2,r.get("calls"));assertEquals(true,r.get("sameOreIntent"));
        assertEquals(0,r.get("broken"));assertEquals(0,r.get("anticipated"));assertEquals(0,r.get("gathered"));
    }
    @SuppressWarnings("unchecked") static Map<String,Object> lifecycle(String s)throws Exception{return (Map<String,Object>)fixture.getMethod("lifecycle",String.class).invoke(null,s);}
    @Test void vanishedOriginalOreCancelsEffectiveOccluderWithoutAnotherDig()throws Exception{var r=lifecycle("removed");assertEquals(1,r.get("calls"));assertEquals(true,r.get("cleared"));}
    @Test void changedOriginalOreTypeCancelsEffectiveOccluder()throws Exception{var r=lifecycle("changed");assertEquals(1,r.get("calls"));assertEquals(true,r.get("cleared"));}
    @Test void choosingANewOreReplacesTheOldIntent()throws Exception{var r=lifecycle("replace");assertEquals(true,r.get("newIntent"));}
    static final String SCENARIO="""
        public static Map<String,Object> lifecycle(String scenario){
            Effects f=new Effects();BlockPos ore=f.digger.ore;f.knownOres.add(ore);f.reachable=ore;f.player.world.time=1;f.onTick();
            f.reachable=null;f.player.world.time=2;
            if(scenario.equals("removed"))f.player.world.removed=ore;
            if(scenario.equals("changed"))f.player.world.nonOre=ore;
            BlockPos replacement=new BlockPos(9,64,0);
            if(scenario.equals("replace"))f.mineProgress(replacement);else f.onTick();
            return Map.of("calls",f.digger.calls,"cleared",f.latchedMineTarget==null&&f.digger.current()==null,
                "newIntent",replacement.equals(f.latchedMineTarget)&&f.digger.intents.get(f.digger.intents.size()-1).equals(replacement));
        }
        public static Map<String,Object> occluderCase(){
            Effects f=new Effects();BlockPos ore=f.digger.ore;f.knownOres.add(ore);f.reachable=ore;
            f.player.world.time=1;f.onTick();f.player.world.time=2;f.onTick();
            return Map.of("calls",f.digger.calls,"sameOreIntent",f.digger.intents.equals(List.of(ore,ore)),
                "broken",f.brokenTargets,"anticipated",f.anticipatedDrops.size(),"gathered",f.r.getMined());
        }
        """;
}
