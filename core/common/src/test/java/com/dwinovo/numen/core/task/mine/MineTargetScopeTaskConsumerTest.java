package com.dwinovo.numen.core.task.mine;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Entire production record -> exact task runQuery/merge/prune/reachableTarget/mineProgress.
 * Index query, harvest/reach and dig are boundary ports; no actual A-star, MC or drop provenance claim.
 * The index port applies only the predicate supplied by the production task, never test scope logic. */
class MineTargetScopeTaskConsumerTest {
    @TempDir static Path temp;static URLClassLoader loader;static Class<?> fixture;
    @BeforeAll static void compile()throws Exception{
        Path root=Path.of("").toAbsolutePath();while(root!=null&&!Files.isDirectory(root.resolve("core/common/src/main/java")))root=root.getParent();assertNotNull(root);
        Path main=root.resolve("core/common/src/main/java/com/dwinovo/numen/core/task/mine");
        String source=Files.readString(main.resolve("MineCompanionTask.java")),methods="";
        for(String signature:List.of("    private void runQuery() {","    private void mergeHits(List<BlockPos> hits) {","    private void prune() {","    private BlockPos reachableTarget() {","    private void mineProgress(BlockPos pos) {","    private void clearNoShot() {","    private void noteProgress() {"))methods+=MineHarvestRecoveryConsumerTest.method(source,signature)+"\n";
        String record=Files.readString(main.resolve("MineBlockTaskRecord.java"))
            .replace("package com.dwinovo.numen.core.task.mine;","package fixture;")
            .replace("import com.dwinovo.numen.task.TaskRecord;", "")
            .replace("import net.minecraft.world.level.block.Block;", "")
            .replace("import net.minecraft.core.BlockPos;", "");
        Path src=temp.resolve("src"),out=temp.resolve("classes");Files.createDirectories(out);
        List<String> args=new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-d",out.toString()));
        add(src,args,"fixture/MineBlockTaskRecord.java",record);add(src,args,"fixture/TaskConsumer.java",BOUNDARIES.replace("TASK_METHODS",methods));
        add(src,args,"com/dwinovo/numen/core/Constants.java","package com.dwinovo.numen.core;public class Constants{public static final Log LOG=new Log();public static class Log{public void debug(String s,Object...a){}}}");
        add(src,args,"com/dwinovo/numen/core/pathing/moves/TerrainPermit.java","package com.dwinovo.numen.core.pathing.moves;public enum TerrainPermit{TERRAFORM}");
        ByteArrayOutputStream errors=new ByteArrayOutputStream();assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,errors,errors,args.toArray(String[]::new)),"SETUP: "+errors);
        loader=new URLClassLoader(new URL[]{out.toUri().toURL()},ClassLoader.getPlatformClassLoader());fixture=loader.loadClass("fixture.TaskConsumer");
    }
    static void add(Path p,List<String>a,String n,String s)throws Exception{MineHarvestRecoveryConsumerTest.add(p,a,n,s);}
    @AfterAll static void close()throws Exception{if(loader!=null)loader.close();}
    @SuppressWarnings("unchecked") static Map<String,Object> run(String s)throws Exception{
        assertTrue(Arrays.stream(loader.loadClass("fixture.MineBlockTaskRecord").getConstructors()).anyMatch(c->c.getParameterCount()==7),"target scope capability absent, not an unscoped API violation");
        return (Map<String,Object>)fixture.getMethod("run",String.class).invoke(null,s);
    }
    @Test void recordScopeReachesQueryBeforeQuotaAndOutsideNearOreDoesNotWin()throws Exception{var r=run("query");assertEquals(List.of("2,66,0"),r.get("known"));assertEquals(true,r.get("scopedQuery"));}
    @Test void mergeAndPruneRejectOutOfScopeEvenIfAnIndexPortReturnsIt()throws Exception{var r=run("merge");assertEquals(List.of("2,66,0"),r.get("known"));}
    @Test void directPickRejectsStaleOutsideCandidateWithoutRelyingOnPrune()throws Exception{var r=run("pick");assertEquals("2,66,0",r.get("picked"));}
    @Test void directDigRejectsOutsideTargetBeforeBoundarySideEffect()throws Exception{var r=run("digOutside");assertEquals(0,r.get("digCalls"));}
    @Test void insideOreWithBodyTwoBlocksLowerCanStillBePickedAndDug()throws Exception{var r=run("digInside");assertEquals("2,66,0",r.get("picked"));assertEquals(1,r.get("digCalls"));}
    @Test void unknownQueryCoverageRemainsUnknownAfterScopeFiltering()throws Exception{var r=run("unknown");assertEquals(false,r.get("complete"));assertEquals(List.of(),r.get("known"));}
    @Test void legacyUnboundedTaskKeepsOutsideCandidate()throws Exception{var r=run("legacy");assertEquals(2,((List<?>)r.get("known")).size());}
    static final String BOUNDARIES="""
        package fixture;
        import java.util.*;import java.util.function.Predicate;
        record BlockPos(int x,int y,int z){int getX(){return x;}int getY(){return y;}int getZ(){return z;}BlockPos immutable(){return this;}BlockPos above(){return new BlockPos(x,y+1,z);}BlockPos below(){return new BlockPos(x,y-1,z);}double distSqr(BlockPos p){return (x-p.x)*(x-p.x)+(y-p.y)*(y-p.y)+(z-p.z)*(z-p.z);}public String toString(){return x+","+y+","+z;}String toShortString(){return toString();}}
        class Block {}
        class TaskRecord{TaskRecord(String t,String c,long d){}public String describe(){return "";}}
        record BlockState(Block block){boolean isAir(){return false;}Block getBlock(){return block;}}
        class Level{Block ore;long getGameTime(){return 1;}BlockState getBlockState(BlockPos p){return new BlockState(ore);}}
        class ServerLevel extends Level{}
        class Player{ServerLevel level;BlockPos feet=new BlockPos(2,64,0);Level level(){return level;}BlockPos blockPosition(){return feet;}boolean onGround(){return true;}Object getInventory(){return this;}}
        class ChunkPos{static long asLong(BlockPos p){return 0;}}
        class CalculationContext{}
        class ContextFactory{static CalculationContext forExecution(Player p,Object permit){return new CalculationContext();}}
        class WorkProfile{static WorkProfile of(Player p){return new WorkProfile();}boolean instaBreak(){return false;}boolean dropsLoot(){return true;}}
        class BlockHelper{static boolean canHarvest(Object inv,BlockState state){return true;}}
        class TargetIndex{
            record Result(List<BlockPos> hits,boolean complete){}
            static List<BlockPos> candidates;static boolean complete=true,scoped;
            static Result query(ServerLevel l,BlockPos c,Collection<Block> t,int want,int radius,int budget){scoped=false;return new Result(candidates,complete);}
            static Result query(ServerLevel l,BlockPos c,Collection<Block> t,int want,int radius,int budget,Predicate<BlockPos> accepted){scoped=true;return new Result(candidates.stream().filter(accepted).limit(want).toList(),complete);}
        }
        public class TaskConsumer{
            static final int MAX_ORES=64,QUERY_MAX_CHUNK_RADIUS=32,QUERY_BUILD_BUDGET=48,QUERY_HEARTBEAT_TICKS=100,QUERY_MIN_GAP_TICKS=20,MAX_NO_SHOT_TICKS=20,DROP_LOITER_TICKS=5;
            static final double IN_PLACE_FILTER_SQR=49;
            Player player=new Player();MineBlockTaskRecord r;List<BlockPos> knownOres=new ArrayList<>();Set<BlockPos> unworkable=new HashSet<>(),unharvestable=new HashSet<>();Map<BlockPos,Long> anticipatedDrops=new HashMap<>();
            long lastQueryChunk,lastProgressTick;BlockPos lastProgressPos,noShotPos;int heartbeatTimer,queryCooldown,coldMapFails,brokenTargets,noShotTicks;boolean lastQueryComplete;Object navigationFailure;
            static class Digger{enum Result{BROKE_TARGET,NO_SHOT,BROKE_OCCLUDER,PROGRESSING}int calls;Result digStep(BlockPos p){calls++;return Result.PROGRESSING;}void cancel(){}}
            Digger digger=new Digger();boolean reachable(BlockPos p){return true;}boolean plausibleToBreak(CalculationContext c,BlockPos p,BlockState s){return true;}
        TASK_METHODS
            public static Map<String,Object> run(String scenario)throws Exception{
                TaskConsumer t=new TaskConsumer();Block ore=new Block();t.player.level=new ServerLevel();t.player.level.ore=ore;
                BlockPos outside=new BlockPos(2,65,0),inside=new BlockPos(2,66,0);
                if(scenario.equals("legacy"))t.r=new MineBlockTaskRecord("call",1000,Set.of(ore),4,"ore");
                else t.r=MineBlockTaskRecord.class.getConstructor(String.class,long.class,Set.class,int.class,String.class,BlockPos.class,BlockPos.class).newInstance("call",1000L,Set.of(ore),4,"ore",inside,inside);
                TargetIndex.candidates=List.of(outside,inside);TargetIndex.complete=!scenario.equals("unknown");TargetIndex.scoped=false;
                if(scenario.equals("unknown"))TargetIndex.candidates=List.of(outside);
                BlockPos picked=null;
                switch(scenario){
                    case "query","unknown","legacy" -> t.runQuery();
                    case "merge" -> t.mergeHits(List.of(outside,inside));
                    case "pick" -> {t.knownOres.addAll(List.of(outside,inside));picked=t.reachableTarget();}
                    case "digOutside" -> t.mineProgress(outside);
                    case "digInside" -> {t.knownOres.add(inside);picked=t.reachableTarget();t.mineProgress(inside);}
                    default -> throw new IllegalArgumentException(scenario);
                }
                return Map.of("known",t.knownOres.stream().map(Object::toString).toList(),"picked",picked==null?"none":picked.toString(),"digCalls",t.digger.calls,"complete",t.lastQueryComplete,"scopedQuery",TargetIndex.scoped);
            }
        }
        """;
}
