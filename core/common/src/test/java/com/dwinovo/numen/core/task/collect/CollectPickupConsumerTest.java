package com.dwinovo.numen.core.task.collect;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Whole production collect task, typed record, decisions and target set; MC entity/inventory,
 * navigation and task shell are controlled boundaries. Not vanilla collision or server physics. */
class CollectPickupConsumerTest {
    @TempDir static Path temp;
    static URLClassLoader loader;static Class<?> harness;
    @BeforeAll static void compileConsumer() throws Exception {
        Path root=Path.of("").toAbsolutePath();while(root!=null&&!Files.isDirectory(root.resolve("core/common/src/main/java")))root=root.getParent();
        assertNotNull(root,"SETUP: source root missing");
        Path src=temp.resolve("src"),out=temp.resolve("classes");Files.createDirectories(out);
        List<String> args=new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-d",out.toString()));
        for(String file:List.of("task/collect/CollectItemsCompanionTask","task/collect/CollectItemsTaskRecord","task/collect/CollectDecisions","task/base/TargetSet"))
            add(src,args,"com/dwinovo/numen/core/"+file+".java",Files.readString(root.resolve("core/common/src/main/java/com/dwinovo/numen/core/"+file+".java")));
        add(src,args,"com/dwinovo/numen/task/TaskState.java",Files.readString(root.resolve("api/common/src/main/java/com/dwinovo/numen/task/TaskState.java")));
        for(var entry:BOUNDARIES.entrySet())add(src,args,entry.getKey().replace('.','/')+".java",entry.getValue());
        add(src,args,"fixture/CollectHarness.java",HARNESS);
        var compiler=ToolProvider.getSystemJavaCompiler();assertNotNull(compiler,"SETUP: JDK missing");var diagnostics=new ByteArrayOutputStream();
        assertEquals(0,compiler.run(null,diagnostics,diagnostics,args.toArray(String[]::new)),"SETUP not business red: "+diagnostics);
        loader=new URLClassLoader(new URL[]{out.toUri().toURL()},ClassLoader.getPlatformClassLoader());harness=loader.loadClass("fixture.CollectHarness");
    }
    static void add(Path src,List<String> args,String file,String text)throws Exception{Path p=src.resolve(file);Files.createDirectories(p.getParent());Files.writeString(p,text);args.add(p.toString());}
    @AfterAll static void close()throws Exception{if(loader!=null)loader.close();}
    @SuppressWarnings("unchecked") static Map<String,Object> run(String scenario)throws Exception{return (Map<String,Object>)harness.getMethod("run",String.class).invoke(null,scenario);}
    @Test void freshDropRemainsRunningUntilNativePickupAndRealInventoryCredit()throws Exception{
        var r=run("delay");assertEquals("RUNNING",r.get("early"),"a nearby unpickable drop cannot be skipped after two ticks");
        assertEquals("SUCCESS",r.get("state"));assertEquals(1,r.get("collected"));assertEquals(0,r.get("left"));assertEquals(true,r.get("complete"));
    }
    @Test void quickNativePickupIsNotForcedToWaitForTheWholeBudget()throws Exception{
        var r=run("quick");assertEquals("SUCCESS",r.get("state"));assertEquals(1,r.get("collected"));assertTrue((int)r.get("ticks")<15);
    }
    @Test void permanentDelayOrFullInventoryTerminatesBoundedAndTruthfullyPartial()throws Exception{
        for(String mode:List.of("permanent","full")){var r=run(mode);assertEquals("SUCCESS",r.get("state"),mode);
            assertEquals(0,r.get("collected"));assertEquals(1,r.get("left"));assertEquals(false,r.get("complete"));assertTrue((int)r.get("ticks")<250,"wait budget must not reset on each ARRIVED");}
    }
    @Test void someoneElseTakingTheDropIsNotOurInventoryCredit()throws Exception{
        var r=run("stolen");assertEquals("SUCCESS",r.get("state"));assertEquals(0,r.get("collected"));assertEquals(1,r.get("vanished"));assertEquals(false,r.get("complete"));
    }
    @Test void distanceShortcutMustNotPreventNavigationToTheActualPickupCell()throws Exception{
        var r=run("near");assertEquals(1,r.get("collected"));assertTrue((int)r.get("approach")>0,"~1.225-block proximity is not native absorption");
    }
    @Test void deathAndExternalCancellationDoNotClaimACompleteSweep()throws Exception{
        for(String mode:List.of("dead","cancel")){var r=run(mode);assertEquals("CANCELLED",r.get("state"));assertEquals(0,r.get("collected"));assertEquals(false,r.get("complete"));}
    }
    static final Map<String,String> BOUNDARIES=Map.ofEntries(
        Map.entry("com.dwinovo.numen.task.TaskRecord","""
            package com.dwinovo.numen.task; public class TaskRecord {public TaskRecord(String tool,String call,long deadline){} public String describe(){return "collect";}}
            """),
        Map.entry("com.dwinovo.numen.core.task.base.AbstractCompanionTask","""
            package com.dwinovo.numen.core.task.base;
            import com.dwinovo.numen.entity.NumenPlayer;import com.dwinovo.numen.task.*;import com.dwinovo.numen.core.pathing.execute.PlayerNav;import java.util.*;
            public abstract class AbstractCompanionTask<R extends TaskRecord>{protected final NumenPlayer player;protected final R r;protected PlayerNav nav;
                protected AbstractCompanionTask(NumenPlayer p,R record){player=p;r=record;}protected void onStart(){}protected abstract TaskState onTick();
                public void start(NumenPlayer p){onStart();}public TaskState tick(NumenPlayer p){return onTick();}
                protected void stopNav(){if(nav!=null)nav.stop();nav=null;}protected void cleanup(){stopNav();}
                protected Map<String,Object> resultData(){return Map.of();}protected abstract String successMessage();protected String timeoutMessage(){return "timeout";}protected String cancelledMessage(){return "cancel";}
                public void stop(){cleanup();}}
            """),
        Map.entry("com.dwinovo.numen.entity.NumenPlayer","""
            package com.dwinovo.numen.entity;import net.minecraft.world.item.*;import net.minecraft.world.entity.*;import net.minecraft.world.entity.player.Inventory;import net.minecraft.world.entity.item.ItemEntity;import net.minecraft.world.phys.*;import java.util.*;
            public class NumenPlayer {public final World world=new World();public final Inventory inv=new Inventory();public boolean dead;public double distance=0.4;public int approaches;
                public boolean isDeadOrDying(){return dead;}public World level(){return world;}public Inventory getInventory(){return inv;}public AABB getBoundingBox(){return new AABB();}
                public double distanceToSqr(Entity e){return distance*distance;}public Vec3 position(){return new Vec3();}public double getX(){return 0;}public double getY(){return 0;}public double getZ(){return 0;}
                public static class World{public long time;public final List<Entity> entities=new ArrayList<>();public List<Entity> getEntities(NumenPlayer p,AABB b){return entities;}public long getGameTime(){return time;}}
            }
            """),
        Map.entry("com.dwinovo.numen.entity.InputDriver","""
            package com.dwinovo.numen.entity;public class InputDriver {public static void halt(NumenPlayer p){}}
            """),
        Map.entry("com.dwinovo.numen.core.pathing.execute.PlayerNav","""
            package com.dwinovo.numen.core.pathing.execute;import com.dwinovo.numen.entity.NumenPlayer;import net.minecraft.core.BlockPos;import java.util.function.*;
            public class PlayerNav {public enum Status{RUNNING,ARRIVED,FAILED}final NumenPlayer p;final BooleanSupplier reached;
                public PlayerNav(NumenPlayer p,Supplier<BlockPos> target,double speed,BooleanSupplier reached){this.p=p;this.reached=reached;}
                public Status tick(){if(reached.getAsBoolean())return Status.ARRIVED;if(p.distance>0.4){p.approaches++;p.distance=0.4;}return Status.ARRIVED;}public void stop(){}public boolean planningInFlight(){return false;}}
            """),
        Map.entry("net.minecraft.core.BlockPos","""
            package net.minecraft.core;public record BlockPos(int x,int y,int z){}
            """),
        Map.entry("net.minecraft.world.entity.Entity","""
            package net.minecraft.world.entity;import net.minecraft.core.BlockPos;public class Entity {public boolean removed;public int id=1;public boolean isRemoved(){return removed;}public int getId(){return id;}public BlockPos blockPosition(){return new BlockPos(0,0,0);}}
            """),
        Map.entry("net.minecraft.world.entity.item.ItemEntity","""
            package net.minecraft.world.entity.item;import net.minecraft.world.entity.Entity;import net.minecraft.world.item.*;import net.minecraft.world.phys.*;
            public class ItemEntity extends Entity {public int delay;final ItemStack stack;public ItemEntity(Item item){stack=new ItemStack(item,1);}public ItemStack getItem(){return stack;}public boolean hasPickUpDelay(){return delay>0;}public AABB getBoundingBox(){return new AABB();}public Vec3 position(){return new Vec3();}}
            """),
        Map.entry("net.minecraft.world.item.Item","""
            package net.minecraft.world.item;public class Item {}
            """),
        Map.entry("net.minecraft.world.item.ItemStack","""
            package net.minecraft.world.item;public class ItemStack {public final Item item;public int count;public ItemStack(Item item,int count){this.item=item;this.count=count;}public Item getItem(){return item;}public boolean is(Item other){return item==other;}public int getCount(){return count;}public boolean isEmpty(){return count<=0;}}
            """),
        Map.entry("net.minecraft.world.entity.player.Inventory","""
            package net.minecraft.world.entity.player;import net.minecraft.world.item.*;import java.util.*;public class Inventory {public final List<ItemStack> items=new ArrayList<>();public int getContainerSize(){return items.size();}public ItemStack getItem(int i){return items.get(i);}}
            """),
        Map.entry("net.minecraft.world.phys.AABB","""
            package net.minecraft.world.phys;public class AABB {public AABB inflate(double n){return this;}public AABB inflate(double x,double y,double z){return this;}public boolean intersects(AABB b){return true;}}
            """),
        Map.entry("net.minecraft.world.phys.Vec3","""
            package net.minecraft.world.phys;public class Vec3 {}
            """),
        Map.entry("com.dwinovo.numen.event.NumenEvents","""
            package com.dwinovo.numen.event;import com.dwinovo.numen.entity.NumenPlayer;public class NumenEvents {public static void body(NumenPlayer p,String s){}}
            """));
    static final String HARNESS="""
        package fixture;import java.util.*;import com.dwinovo.numen.entity.*;import com.dwinovo.numen.task.*;import com.dwinovo.numen.core.task.collect.*;import net.minecraft.world.entity.item.ItemEntity;import net.minecraft.world.item.*;
        public class CollectHarness {public static Map<String,Object> run(String mode){
            var p=new NumenPlayer();var item=new Item();var stack=new ItemStack(item,0);p.inv.items.add(stack);var drop=new ItemEntity(item);p.world.entities.add(drop);
            drop.delay=mode.equals("delay")?165:mode.equals("permanent")?32767:0;if(mode.equals("near"))p.distance=1.2;
            var record=new CollectItemsTaskRecord("original",1200,Set.of(item),4,"drop");var task=new CollectItemsCompanionTask(p,record);task.start(p);
            TaskState state=TaskState.RUNNING;String early="";int ticks=0;
            while(state==TaskState.RUNNING&&ticks<240){ticks++;p.world.time=ticks;if(drop.delay>0)drop.delay--;
                if(mode.equals("dead")&&ticks==4)p.dead=true;
                if(mode.equals("cancel")&&ticks==4){task.stop();state=TaskState.CANCELLED;break;}
                if(mode.equals("stolen")&&ticks==6)drop.removed=true;
                if(!drop.removed&&ticks>=4&&drop.delay==0&&!mode.equals("full")&&!mode.equals("dead")&&!mode.equals("stolen")&&p.distance<=0.4){drop.removed=true;stack.count++;}
                state=task.tick(p);if(ticks==3)early=state.name();
            }
            Map<String,Object> out=new HashMap<>();out.put("state",state.name());out.put("early",early);out.put("ticks",ticks);out.put("collected",record.getCollected());out.put("left",record.getLeftBehind());out.put("vanished",record.getVanished());out.put("complete",record.isSweepComplete());out.put("approach",p.approaches);return out;
        }}
        """;
}
