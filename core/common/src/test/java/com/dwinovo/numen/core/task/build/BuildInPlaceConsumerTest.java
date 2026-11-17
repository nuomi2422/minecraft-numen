package com.dwinovo.numen.core.task.build;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test-only source consumer: no copied guard or boolean-helper-as-consumer assertions.
 * Compiles complete production BuildInPlace and verbatim onStart, tickTravel, tickWander,
 * inPlaceAllows and processCell members. onTick ends BEFORE its first setShiftKeyDown:
 * OUTSIDE_TICK_SLICE is a probe sentinel, never a production state or claimed success.
 * Startup collaborators, Minecraft, protection decisions, inventory and navigation are
 * explicit recording boundaries; this does NOT test actual pathfinding, physics, block
 * entity protection implementation, lifecycle delivery, auto-light or onTick's work tail.
 * Record uses real fields, original constructor chain, promotion, Target header/7-arg constructor,
 * materialCount and matches. Tool schema, typed Gson args and in_place record assignment use
 * verbatim production consumers; shape expansion, timeout estimation, auto-light execution,
 * canonical normalization/hints and serialization are NOT tested.
 * Fixtures supply normalized, hint-free states. BuildValidity is a state-identity boundary.
 * All compile/extraction problems are SETUP ERROR, not a business regression red.
 * Parent owns the only Java execution and baseline replay; no invocation/build script here.
 */
class BuildInPlaceConsumerTest {
    @TempDir static Path temp;
    static URLClassLoader loader;
    static Class<?> fixture;

    @BeforeAll static void compileProductionConsumers() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("core/common/src/main/java"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("SETUP ERROR: cannot locate candidate tree");
        Path main = root.resolve("core/common/src/main/java/com/dwinovo/numen/core");
        String task = Files.readString(main.resolve("task/build/BuildCompanionTask.java"));
        String record = Files.readString(main.resolve("task/build/BuildTaskRecord.java"));
        Path src = temp.resolve("src"), out = temp.resolve("classes");
        Files.createDirectories(out);
        URL gsonSource = com.google.gson.Gson.class.getProtectionDomain().getCodeSource().getLocation();
        List<String> args = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none", "-d", out.toString(),
                "-classpath", Path.of(gsonSource.toURI()).toString()));
        addToolConsumer(root, main, src, args);
        add(src, args, "com/dwinovo/numen/task/TaskState.java",
                Files.readString(root.resolve("api/common/src/main/java/com/dwinovo/numen/task/TaskState.java")));
        add(src, args, "com/dwinovo/numen/core/task/build/BuildInPlace.java",
                Files.readString(main.resolve("task/build/BuildInPlace.java")));
        add(src, args, "com/dwinovo/numen/task/TaskRecord.java", """
                package com.dwinovo.numen.task;
                public abstract class TaskRecord {
                    protected TaskRecord(String tool, String call, long deadline) { }
                }
                """);
        add(src, args, "net/minecraft/core/BlockPos.java", """
                package net.minecraft.core;
                public record BlockPos(int x, int y, int z) {
                    public static final BlockPos ZERO=new BlockPos(0,0,0);
                    public int getX(){return x;} public int getY(){return y;} public int getZ(){return z;}
                    public BlockPos immutable(){return this;}
                    public long asLong(){return ((long)x<<38)^((long)z<<12)^(y&4095);}
                    public String toShortString(){return x+", "+y+", "+z;}
                    public static BlockPos containing(net.minecraft.world.phys.Vec3 p){
                        return new BlockPos((int)Math.floor(p.x()),(int)Math.floor(p.y()),(int)Math.floor(p.z()));
                    }
                }
                """);
        add(src, args, "net/minecraft/core/Direction.java", """
                package net.minecraft.core;
                public enum Direction { UP; public enum Axis { X,Y,Z } }
                """);
        add(src, args, "net/minecraft/world/phys/Vec3.java", """
                package net.minecraft.world.phys;
                public record Vec3(double x,double y,double z) { }
                """);
        add(src, args, "net/minecraft/nbt/CompoundTag.java", "package net.minecraft.nbt; public class CompoundTag { }");
        add(src, args, "net/minecraft/world/item/Item.java", "package net.minecraft.world.item; public class Item { }");
        add(src, args, "net/minecraft/world/item/ItemStack.java", """
                package net.minecraft.world.item;
                public record ItemStack(Item item) {
                    public Item getItem(){return item;} public ItemStack copy(){return this;}
                    public boolean is(Item i){return item==i;}
                    public static boolean isSameItemSameComponents(ItemStack a,ItemStack b){return a.item==b.item;}
                }
                """);
        add(src, args, "net/minecraft/world/item/Items.java", """
                package net.minecraft.world.item;
                public class Items { public static final Item TORCH=new Item(); }
                """);
        add(src, args, "net/minecraft/world/item/BlockItem.java", """
                package net.minecraft.world.item;
                public class BlockItem extends Item {
                    public net.minecraft.world.level.block.Block getBlock(){return null;}
                }
                """);
        add(src, args, "net/minecraft/world/level/block/Block.java", """
                package net.minecraft.world.level.block;
                public class Block {
                    public String getDescriptionId(){return getClass().getName();}
                    public void setPlacedBy(Object w,Object p,Object s,Object player,Object stack){ }
                }
                """);
        for (String name : List.of("LiquidBlock", "SnowLayerBlock", "TurtleEggBlock", "SeaPickleBlock")) {
            add(src, args, "net/minecraft/world/level/block/"+name+".java",
                    "package net.minecraft.world.level.block; public class "+name+" extends Block { }");
        }
        add(src, args, "net/minecraft/world/level/block/Blocks.java", """
                package net.minecraft.world.level.block;
                public class Blocks { public static final Block VINE=new Block(), GLOW_LICHEN=new Block(); }
                """);
        add(src, args, "net/minecraft/world/level/block/state/properties/Property.java", """
                package net.minecraft.world.level.block.state.properties;
                public class Property<T> { }
                """);
        add(src, args, "net/minecraft/world/level/block/state/properties/BooleanProperty.java", """
                package net.minecraft.world.level.block.state.properties;
                public class BooleanProperty extends Property<Boolean> { }
                """);
        for (String name : List.of("DoubleBlockHalf", "BedPart", "SlabType")) {
            String values = switch (name) { case "DoubleBlockHalf" -> "LOWER,UPPER"; case "BedPart" -> "FOOT,HEAD"; default -> "BOTTOM,TOP,DOUBLE"; };
            add(src, args, "net/minecraft/world/level/block/state/properties/"+name+".java",
                    "package net.minecraft.world.level.block.state.properties; public enum "+name+" { "+values+" }");
        }
        add(src, args, "net/minecraft/world/level/block/state/properties/BlockStateProperties.java", """
                package net.minecraft.world.level.block.state.properties;
                public class BlockStateProperties {
                    public static final Property<DoubleBlockHalf> DOUBLE_BLOCK_HALF=new Property<>();
                    public static final Property<BedPart> BED_PART=new Property<>();
                    public static final Property<SlabType> SLAB_TYPE=new Property<>();
                    public static final Property<Integer> LAYERS=new Property<>(),EGGS=new Property<>(),PICKLES=new Property<>(),CANDLES=new Property<>();
                    public static final BooleanProperty NORTH=new BooleanProperty(),EAST=new BooleanProperty(),SOUTH=new BooleanProperty(),
                        WEST=new BooleanProperty(),UP=new BooleanProperty(),DOWN=new BooleanProperty();
                }
                """);
        add(src, args, "net/minecraft/world/level/block/state/BlockState.java", """
                package net.minecraft.world.level.block.state;
                import net.minecraft.world.level.block.*;
                import net.minecraft.world.level.block.state.properties.*;
                public record BlockState(Block block,boolean air) {
                    public Block getBlock(){return block;} public boolean isAir(){return air;}
                    public boolean is(Block b){return block==b;}
                    public boolean canBeReplaced(){return air;}
                    public boolean isCollisionShapeFullBlock(Object w,Object p){return !air;}
                    public boolean hasProperty(Property<?> p){return false;}
                    public <T> T getValue(Property<T> p){throw new UnsupportedOperationException("property outside fixture");}
                    public java.util.List<Object> getProperties(){return java.util.List.of();}
                }
                """);
        add(src, args, "net/minecraft/world/level/EmptyBlockGetter.java", """
                package net.minecraft.world.level;
                public enum EmptyBlockGetter { INSTANCE }
                """);
        add(src, args, "com/dwinovo/numen/core/build/BuildValidity.java", """
                package com.dwinovo.numen.core.build;
                public class BuildValidity {
                    public static boolean valid(Object a,Object b,boolean ignored){return a==b;}
                }
                """);
        // Narrow real record, with no rewritten guard, parser, constructor assignment or cost algorithm.
        StringBuilder rec = new StringBuilder("""
                package com.dwinovo.numen.core.task.build;
                import java.util.*;
                import com.dwinovo.numen.task.TaskRecord;
                import com.dwinovo.numen.core.build.BuildValidity;
                import net.minecraft.core.*;
                import net.minecraft.nbt.CompoundTag;
                import net.minecraft.world.item.Item;
                import net.minecraft.world.level.block.state.BlockState;
                import net.minecraft.world.level.block.state.properties.BlockStateProperties;
                public final class BuildTaskRecord extends TaskRecord {
                    public record EntitySpawn() { } // unused payload type only
                """);
        for (String field : List.of("public static final String TOOL_NAME", "public final List<Target> targets",
                "public com.dwinovo.numen.core.task.light.AutoLightRequest autoLight", "public final ReplaceMode replaceMode",
                "public final boolean replaceBlockEntities", "public BuildInPlace inPlace", "public final boolean replaceExisting",
                "public final boolean consumeMaterials", "public final boolean allowPartial",
                "public final Map<Long, CompoundTag> blockEntityData", "public final List<EntitySpawn> entities", "private int completed", "private int placed")) {
            rec.append(field(record, field)).append('\n');
        }
        // All original constructor overloads, including the tool's five-argument entry.
        rec.append(between(record, "    public BuildTaskRecord(String toolCallId, long deadlineGameTime,\n                           List<Target> targets, boolean replaceExisting)",
                "    private static List<Target> promotePlainCells("));
        rec.append(member(record, "    public static BuildTaskRecord autoLight("));
        rec.append(member(record, "    private static List<Target> promotePlainCells("));
        rec.append(member(record, "    public int completed()"));
        rec.append(member(record, "    public int placed()"));
        rec.append(member(record, "    public void placedOne()"));
        rec.append(member(record, "    public record CellNeed("));
        rec.append(between(record, "    public record Target(", "        public Target(BlockState desiredState,"));
        rec.append(nestedMember(record, "        public Target(BlockState desiredState,"));
        rec.append(nestedMember(record, "        public int materialCount()"));
        rec.append(nestedMember(record, "        public boolean matches("));
        rec.append("}\n}\n");
        add(src, args, "com/dwinovo/numen/core/task/build/BuildTaskRecord.java", rec.toString());
        add(src, args, "com/dwinovo/numen/core/task/build/ReplaceMode.java",
                Files.readString(main.resolve("task/build/ReplaceMode.java")));
        add(src, args, "com/dwinovo/numen/core/FailureType.java", """
                package com.dwinovo.numen.core; public enum FailureType { NO_PATH }
                """);
        add(src, args, "com/dwinovo/numen/core/Constants.java", """
                package com.dwinovo.numen.core;
                public class Constants { public static final Log LOG=new Log();
                    public static class Log { public void debug(String s,Object...args){ } }
                }
                """);
        add(src, args, "com/dwinovo/numen/core/task/light/AutoLightRequest.java", """
                package com.dwinovo.numen.core.task.light;
                public class AutoLightRequest {
                    public int maxTicks(){throw new UnsupportedOperationException("auto-light outside scope");}
                    public static AutoLightRequest parse(com.google.gson.JsonObject args){
                        throw new UnsupportedOperationException("auto-light outside scope");
                    }
                }
                """);
        add(src, args, "com/dwinovo/numen/core/task/light/AutoLightSession.java", """
                package com.dwinovo.numen.core.task.light;
                public class AutoLightSession {
                    public AutoLightSession(Object r,Object w){ }
                    public void stop(String why){throw new UnsupportedOperationException("auto-light outside scope");}
                }
                """);
        StringBuilder consumer = new StringBuilder(boundary());
        for (String signature : List.of("    protected void onStart()", "    private TaskState tickTravel()",
                "    private void tickWander()", "    private boolean inPlaceAllows(", "    private BlockState processCell(",
                "    public void stop(NumenPlayer companion, StopReason why)", "    protected void cleanup()",
                "    protected String cancelledMessage()")) {
            consumer.append(member(task, signature)).append('\n');
        }
        // Do not copy the onTick dispatch or invent its success behavior.
        consumer.append(between(task, "    protected TaskState onTick() {", "        player.setShiftKeyDown(phase == Phase.WORK && show.crouching());"));
        consumer.append("        throw new OutsideTickSlice();\n    }\n}\n");
        add(src, args, "com/dwinovo/numen/core/task/build/BuildCompanionTask.java", consumer.toString());
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("SETUP ERROR: JDK compiler required");
        var diagnostics = new ByteArrayOutputStream();
        if (compiler.run(null, diagnostics, diagnostics, args.toArray(String[]::new)) != 0) {
            throw new IllegalStateException("SETUP ERROR: source consumers did not compile; NOT business red:\n"
                    + diagnostics.toString(StandardCharsets.UTF_8));
        }
        loader = new URLClassLoader(new URL[]{out.toUri().toURL(), gsonSource}, ClassLoader.getPlatformClassLoader());
        fixture = loader.loadClass("com.dwinovo.numen.core.task.build.BuildCompanionTask");
    }

    static void addToolConsumer(Path root, Path main, Path src, List<String> args) throws Exception {
        Path api = root.resolve("api/common/src/main/java/com/dwinovo/numen");
        String tool = Files.readString(main.resolve("tools/work/BuildTool.java"));
        String toolArgs = Files.readString(api.resolve("agent/tool/ToolArgs.java"));
        // The entire pure-Gson typed-args tail, including its closing class brace, is verbatim.
        add(src, args, "com/dwinovo/numen/agent/tool/ToolArgs.java", """
                package com.dwinovo.numen.agent.tool;
                import com.google.gson.*;
                import java.lang.reflect.RecordComponent;
                import java.util.Collection;
                import java.util.regex.*;
                public final class ToolArgs {
                """ + toolArgs.substring(unique(toolArgs, "    private static final Gson GSON")));
        add(src, args, "com/dwinovo/numen/agent/tool/api/ToolContext.java",
                Files.readString(api.resolve("agent/tool/api/ToolContext.java")));
        add(src, args, "com/dwinovo/numen/agent/tool/NumenTool.java", """
                package com.dwinovo.numen.agent.tool;
                public interface NumenTool {
                    java.util.Map<String,Object> parameterSchema();
                    void onServerCall(String call,com.google.gson.JsonObject args,
                        com.dwinovo.numen.entity.NumenPlayer player,java.util.function.Consumer<String> reply);
                }
                """);
        add(src, args, "com/dwinovo/numen/entity/NumenPlayer.java",
                "package com.dwinovo.numen.entity; public class NumenPlayer { }");
        add(src, args, "com/dwinovo/numen/core/WorkProfile.java", """
                package com.dwinovo.numen.core;
                public class WorkProfile {
                    public static WorkProfile of(com.dwinovo.numen.entity.NumenPlayer p){return new WorkProfile();}
                    public boolean freeMaterials(){return false;}
                }
                """);
        add(src, args, "com/dwinovo/numen/core/build/BuildShapes.java",
                "package com.dwinovo.numen.core.build; public class BuildShapes { public static final int MAX_TOTAL_CELLS=16384; }");
        add(src, args, "com/dwinovo/numen/task/TaskDispatch.java", """
                package com.dwinovo.numen.task;
                import com.dwinovo.numen.entity.NumenPlayer;
                import com.dwinovo.numen.agent.tool.api.ToolContext;
                public class TaskDispatch {
                    public static int ctxCalls,setTaskCalls;
                    public static TaskRecord captured;
                    public static ToolContext ctx(String call,NumenPlayer player){ctxCalls++;return new ToolContext(call,100);}
                    public static void setTask(NumenPlayer p,TaskRecord record,com.google.gson.JsonObject args,
                            java.util.function.Consumer<String> reply){setTaskCalls++;captured=record;}
                    public static void reset(){ctxCalls=0;setTaskCalls=0;captured=null;}
                }
                """);
        StringBuilder shell = new StringBuilder("""
                package com.dwinovo.numen.core.tools.work;
                import static com.dwinovo.numen.task.TaskDispatch.*;
                import com.dwinovo.numen.agent.tool.*;
                import com.dwinovo.numen.core.build.BuildShapes;
                import com.dwinovo.numen.core.task.build.*;
                import com.dwinovo.numen.entity.NumenPlayer;
                import com.google.gson.*;
                import java.util.*;
                import java.util.function.Consumer;
                public final class BuildTool implements NumenTool {
                    // Explicit boundaries: no shape expansion or timeout-estimation coverage.
                    private static List<BuildTaskRecord.Target> expandOp(OpSpec ignored){return List.of();}
                    public static long timeoutTicksFor(int cells,boolean consume){return 1200;}
                """);
        shell.append(emptyRecord(tool, "    private record Args("));
        shell.append(emptyRecord(tool, "    private record OpSpec("));
        for (String signature : List.of("    public Map<String, Object> parameterSchema()",
                "    private static Map<String, Object> enumSchema(", "    private static List<Object> valuesWithNull(",
                "    public void onServerCall(",
                "    public static com.dwinovo.numen.core.task.light.AutoLightRequest parseAutoLight(")) {
            shell.append(member(tool, signature)).append('\n');
        }
        // Public bridge: reflection must not cross package-private test helper access.
        shell.append("""
                    public static Map<String,Object> schemaProbe(){return new BuildTool().parameterSchema();}
                    public static Map<String,Object> callProbe(String json){
                        com.dwinovo.numen.task.TaskDispatch.reset();
                        Map<String,Object> out=new HashMap<>();
                        try {
                            new BuildTool().onServerCall("tool-call",JsonParser.parseString(json).getAsJsonObject(),
                                    new NumenPlayer(),ignored->{});
                        } catch(IllegalArgumentException ex){out.put("error",ex);}
                        out.put("ctx",ctxCalls);out.put("setTask",setTaskCalls);
                        if(captured!=null){
                            BuildTaskRecord r=(BuildTaskRecord)captured;
                            out.put("inPlaceNull",r.inPlace==null);
                            if(r.inPlace!=null)out.put("anchor",List.of(r.inPlace.x(),r.inPlace.z(),r.inPlace.feetY(),r.inPlace.headY()));
                            out.put("replaceExisting",r.replaceExisting);out.put("consumeMaterials",r.consumeMaterials);
                            out.put("allowPartial",r.allowPartial);out.put("targets",r.targets.size());
                        }
                        return out;
                    }
                }
                """);
        add(src, args, "com/dwinovo/numen/core/tools/work/BuildTool.java", shell.toString());
    }

    static String boundary() {
        return """
                package com.dwinovo.numen.core.task.build;
                import java.util.*;
                import java.util.function.*;
                import net.minecraft.core.BlockPos;
                import net.minecraft.world.phys.Vec3;
                import net.minecraft.world.item.*;
                import net.minecraft.world.level.block.*;
                import net.minecraft.world.level.block.state.BlockState;
                import com.dwinovo.numen.task.TaskState;
                import com.dwinovo.numen.core.FailureType;
                import com.dwinovo.numen.entity.NumenPlayer;
                abstract class LifecycleBoundary {
                    abstract void hit(String key);
                    enum StopReason { PREEMPTED,REPLACED,BODY_GONE }
                    public void stop(NumenPlayer companion,StopReason why){hit("superStop");}
                    protected void cleanup(){hit("superCleanup");}
                }
                public final class BuildCompanionTask extends LifecycleBoundary {
                    enum Phase { TRAVEL,WORK }
                    static final double WALK_SPEED=1;
                    static final int TRAVEL_BUDGET_TICKS=600,WANDER_INTERVAL_TICKS=80,WANDER_WALK_TICKS=40,LAYER_PAUSE_TICKS=18,PLACE_FLAGS=18;
                    final Player player=new Player(); final BuildTaskRecord r; final Rules rules=new Rules();
                    final Inventory inv=new Inventory(); final Ledger ledger=new Ledger();
                    final Map<String,Integer> counts=new LinkedHashMap<>();
                    Phase phase=Phase.TRAVEL; BlockPos inPlaceViolation,siteMin=new BlockPos(10,60,20);
                    int passStartCompleted,lightMaterialStart,travelTicks,wanderTicks,workPause,placedBaseline;
                    List<Vec3> wanderPoints=List.of(new Vec3(13,60,20)); Vec3 wanderTarget;
                    LongOpenHashSet observedCompleted,skippedPos;
                    PlayerNav nav; String failure="",reason="";
                    com.dwinovo.numen.core.task.light.AutoLightSession lighting;
                    static final BlockState AIR=new BlockState(new Block(),true),SOLID=new BlockState(new Block(),false),
                        DESIRED=new BlockState(new Block(),false),WATER=new BlockState(new LiquidBlock(),false);
                    BuildCompanionTask(boolean anchored){
                        r=new BuildTaskRecord("consumer",0,List.of(),ReplaceMode.REPLACE_EMPTY,true,true,false,Map.of(),List.of(),false);
                        if(anchored) r.inPlace=new BuildInPlace(10,20,60,61);
                    }
                    void hit(String key){counts.merge(key,1,Integer::sum);}
                    static class LongOpenHashSet { }
                    class World {
                        final Map<BlockPos,BlockState> blocks=new HashMap<>(); boolean loaded=true,writable=true;
                        boolean isLoaded(BlockPos p){hit("loaded");return loaded;}
                        BlockState getBlockState(BlockPos p){hit("worldRead");return blocks.getOrDefault(p,AIR);}
                        boolean setBlock(BlockPos p,BlockState s,int flags){hit("place");if(writable)blocks.put(p,s);return writable;}
                    }
                    class Player {
                        void halt(){hit("halt");}
                        void setShiftKeyDown(boolean down){hit("shift");if(down)hit("shiftTrue");}
                        double x=10.5,y=60,z=20.5; final World world=new World();
                        World level(){hit("level");return world;}
                        double getX(){hit("bodyX");return x;} double getY(){hit("bodyY");return y;} double getZ(){hit("bodyZ");return z;}
                        BlockPos blockPosition(){return new BlockPos((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z));}
                    }
                    class Rules {
                        boolean entity,mode,hopeless;
                        BlockState peek(BlockPos p){hit("peek");return player.world.blocks.getOrDefault(p,AIR);}
                        boolean blockedByEntity(BlockPos p,BlockState s){hit("entity");return entity;}
                        boolean blockedByMode(BuildTaskRecord.Target t){hit("mode");return mode;}
                        boolean hopeless(BuildTaskRecord.Target t){hit("hopeless");return hopeless;}
                        boolean costsMaterial(BuildTaskRecord.Target t){hit("cost");return true;}
                    }
                    class Inventory {
                        boolean available=true;
                        int mainInventoryCount(Item i){hit("materials");return 1;}
                        boolean hasItems(Item i,int n,boolean strict){hit("materials");return available;}
                        int countMatching(BuildTaskRecord.CellNeed n){hit("materials");return available?1:0;}
                        void consumeMatching(BuildTaskRecord.CellNeed n){hit("consume");}
                        void consumeOne(Item i){hit("consume");}
                    }
                    class Ledger {
                        List<BuildTaskRecord.CellNeed> needs=List.of();
                        List<BuildTaskRecord.CellNeed> needsFor(BuildTaskRecord.Target t){hit("needs");return needs;}
                    }
                    static class BuildCellRules { static boolean isAirTarget(BuildTaskRecord.Target t){return t.desiredState().isAir();} }
                    record NavGoal() { static NavGoal nearGround(BlockPos p,double d){return new NavGoal();} }
                    static class GoalCompiler { record Compiled(NavGoal goal,Object protectedCells){ } }
                    static class PlayerNav {
                        enum State { ARRIVED,FAILED,RUNNING }
                        final BuildCompanionTask owner;
                        PlayerNav(BuildCompanionTask t){owner=t;}
                        static PlayerNav to(Player p,Supplier<GoalCompiler.Compiled> goal,double speed,BooleanSupplier b,BuildCompanionTask t){
                            t.hit("navCreate");goal.get();return new PlayerNav(t);
                        }
                        State tick(){owner.hit("navTick");return State.RUNNING;}
                        boolean planningInFlight(){return false;}
                    }
                    static class InputDriver { static void halt(Player p){p.halt();} }
                    NavGoal siteApproachGoal(){hit("approach");return new NavGoal();}
                    Object protectedCells(){hit("protected");return Set.of();}
                    Vec3 nextWanderPoint(){hit("wanderPoint");return wanderPoints.get(0);}
                    void beginWork(){hit("beginWork");phase=Phase.WORK;}
                    void stopNav(){hit("stopNav");nav=null;}
                    void computeSite(){hit("site");} void computeWanderPoints(){hit("wanderSetup");}
                    void registerProvider(){hit("register");} void rescanAll(){hit("rescan");}
                    void unregisterProvider(){hit("unregister");}
                    void rebuildOrder(){hit("order");} void computePace(){hit("pace");}
                    void clear(BlockPos p){hit("clear");player.world.blocks.put(p,AIR);}
                    void placeWithItem(BuildTaskRecord.Target t,BlockPos p){hit("itemPlace");player.world.blocks.put(p,t.desiredState());}
                    void applyBlockEntityData(BlockPos p,BlockState s){hit("blockEntityData");}
                    void markObserved(BuildTaskRecord.Target t,boolean b){hit("observed");}
                    void noteShortage(Item i,int n){hit("shortage");}
                    void fail(String why,FailureType type){hit("fail");failure=type.name();reason=why;}
                    TaskState tickLighting(){throw new UnsupportedOperationException("lighting outside scope");}
                    static class OutsideTickSlice extends RuntimeException { }
                    String earlyTick(){try{return onTick().name();}catch(OutsideTickSlice e){return "OUTSIDE_TICK_SLICE";}}
                    BuildTaskRecord.Target target(int y,boolean item){return new BuildTaskRecord.Target(DESIRED,new Item(),new BlockPos(10,y,20),"target",null,null,null,item);}
                    Map<String,Object> snapshot(BlockState result){
                        Map<String,Object> out=new HashMap<>();out.putAll(counts);out.put("produced",result!=null);
                        out.put("placedDelta",r.placed()-placedBaseline);
                        out.put("phase",phase.name());out.put("violation",inPlaceViolation!=null);out.put("failure",failure);out.put("reason",reason);
                        return out;
                    }
                    public static Map<String,Object> startup(boolean anchored){
                        BuildCompanionTask t=new BuildCompanionTask(anchored);t.onStart();
                        t.hit("startupComplete");String prefix=t.earlyTick();
                        // Test driver reads actual production-selected phase; it does not claim to execute onTick tail.
                        if(t.phase==Phase.TRAVEL)t.tickTravel();
                        t.wanderTicks=80;t.tickWander();t.tickWander();
                        Map<String,Object> out=t.snapshot(null);out.put("tick",prefix);return out;
                    }
                    public static Map<String,Object> cell(String scenario,boolean anchored){
                        BuildCompanionTask t=new BuildCompanionTask(anchored);t.onStart();t.counts.clear();
                        int y=62;boolean item=false;
                        switch(scenario){
                            case "x" -> t.player.x=11.5; case "z" -> t.player.z=21.5;
                            case "low" -> t.player.y=59.79;case "high" -> t.player.y=60.21;
                            case "feetBlocked" -> t.player.world.blocks.put(new BlockPos(10,60,20),SOLID);
                            case "headBlocked" -> t.player.world.blocks.put(new BlockPos(10,61,20),SOLID);
                            case "feetTarget" -> y=60;case "headTarget" -> y=61;
                            case "entity" -> t.rules.entity=true;case "mode" -> t.rules.mode=true;
                            case "hopeless" -> t.rules.hopeless=true;case "missing" -> t.inv.available=false;
                            case "exactMissing" -> {t.inv.available=false;t.ledger.needs=List.of(new BuildTaskRecord.CellNeed(new ItemStack(new Item()),true));}
                            case "unloaded" -> t.player.world.loaded=false;case "writeRefused" -> t.player.world.writable=false;
                            case "item" -> item=true;case "lowerEdge" -> t.player.y=59.8;case "upperEdge" -> t.player.y=60.2;
                            default -> { }
                        }
                        if(List.of("missing","exactMissing","entity","mode","hopeless","occupied").contains(scenario))
                            t.player.world.blocks.put(new BlockPos(10,y,20),SOLID);
                        BuildTaskRecord.Target target=t.target(y,item);
                        if(scenario.equals("matched"))t.player.world.blocks.put(target.pos(),DESIRED);
                        if(scenario.equals("liquid"))target=new BuildTaskRecord.Target(WATER,new Item(),target.pos(),"water",null,null,null);
                        BlockState result=t.processCell(target);String state=t.earlyTick();
                        Map<String,Object> out=t.snapshot(result);out.put("tick",state);return out;
                    }
                    public static Map<String,Object> lifecycle(String action){
                        BuildCompanionTask t=new BuildCompanionTask(true);t.onStart();t.counts.clear();
                        switch(action){
                            case "cleanup" -> t.cleanup();
                            case "preempt" -> t.stop(new NumenPlayer(),StopReason.PREEMPTED);
                            default -> t.stop(new NumenPlayer(),StopReason.REPLACED);
                        }
                        Map<String,Object> out=t.snapshot(null);out.put("cancelled",t.cancelledMessage());return out;
                    }
                    public static Map<String,Object> recheck(String change,boolean resume){
                        BuildCompanionTask t=new BuildCompanionTask(true);t.onStart();
                        BlockState first=t.processCell(t.target(62,false));
                        if(resume)t.onStart(); // onStart re-entry only, NOT actual lifecycle resume or persisted restart
                        t.placedBaseline=t.r.placed();t.counts.clear();
                        switch(change){case "body" -> t.player.x=12;case "head" -> t.player.world.blocks.put(new BlockPos(10,61,20),SOLID);
                            default -> t.player.world.blocks.put(new BlockPos(10,60,20),SOLID);}
                        BlockState second=t.processCell(t.target(63,false));String tick=t.earlyTick();
                        Map<String,Object> out=t.snapshot(second);out.put("firstProduced",first!=null);out.put("tick",tick);return out;
                    }
                """;
    }

    @AfterAll static void closeLoader() throws Exception { if (loader != null) loader.close(); }

    @Test void realStartupSelectsWorkAndRealWanderDoesNotNavigateInPlace() throws Exception {
        var anchored = probe("startup", new Class<?>[]{boolean.class}, true);
        assertEquals("WORK", anchored.get("phase"));
        assertEquals("OUTSIDE_TICK_SLICE", anchored.get("tick"), "normal onTick tail is deliberately not tested");
        for (String collaborator : List.of("site", "wanderSetup", "register", "rescan", "order", "pace", "startupComplete")) {
            assertEquals(1, count(anchored, collaborator), collaborator);
        }
        assertEquals(0, count(anchored, "navCreate"));assertEquals(0, count(anchored, "navTick"));
        assertEquals(0, count(anchored, "wanderPoint"));
        var legacy = probe("startup", new Class<?>[]{boolean.class}, false);
        assertEquals("TRAVEL", legacy.get("phase"));
        assertEquals(1, count(legacy, "approach"));assertEquals(1, count(legacy, "navCreate"));
        assertTrue(count(legacy, "navTick") >= 1, "verbatim tickTravel creates and ticks real-consumer nav boundary");
        assertEquals(1, count(legacy, "wanderPoint"), "legacy wander still advances after its interval");
    }

    @Test void everyAnchorRejectionReturnsNullThenRealEarlyTickFailsWithoutActionsOrMaterials() throws Exception {
        for (String scenario : List.of("x", "z", "low", "high", "feetBlocked", "headBlocked", "feetTarget", "headTarget")) {
            var r = cell(scenario, true);
            assertRejected(r, scenario);
            assertEquals(1, count(r, "bodyX"), scenario);assertEquals(1, count(r, "bodyY"), scenario);assertEquals(1, count(r, "bodyZ"), scenario);
            int peeks = scenario.equals("feetBlocked") ? 1 : scenario.equals("headBlocked") ? 2 : 0;
            assertEquals(peeks, count(r, "peek"), "guard reads only necessary body cells: " + scenario);
        }
    }

    @Test void allowedAnchorReachesRealPlacementAndMaterialConsumptionIncludingToleranceEdges() throws Exception {
        for (String scenario : List.of("allowed", "lowerEdge", "upperEdge", "occupied", "item")) {
            var r = cell(scenario, true);
            assertEquals(true, r.get("produced"), scenario);assertEquals(false, r.get("violation"), scenario);
            assertEquals(2, count(r, "peek"));assertEquals(1, count(r, "materials"));
            assertEquals(1, count(r, "entity"));assertEquals(1, count(r, "mode"));assertEquals(1, count(r, "hopeless"));
            assertEquals("OUTSIDE_TICK_SLICE", r.get("tick"));assertEquals(0, count(r, "fail"));
            assertEquals(scenario.equals("occupied") ? 1 : 0, count(r, "clear"));
            assertEquals(scenario.equals("item") ? 1 : 0, count(r, "itemPlace"));
            assertEquals(scenario.equals("item") ? 0 : 1, count(r, "place"));
            assertEquals(scenario.equals("item") ? 0 : 1, count(r, "consume"), "item use owns its own consumption boundary");
        }
    }

    @Test void originalProtectionAndMissingMaterialBranchesRemainAheadOfDestruction() throws Exception {
        for (boolean anchored : List.of(true, false)) {
            for (String scenario : List.of("entity", "mode", "hopeless", "missing", "exactMissing")) {
                var r = cell(scenario, anchored);
                assertEquals(false, r.get("produced"), scenario);assertEquals(false, r.get("violation"), scenario);
                assertEquals(anchored ? 2 : 0, count(r, "peek"));
                assertEquals(anchored ? 1 : 0, count(r, "bodyX"), "null inPlace skips guard entirely");
                assertEquals(0, count(r, "clear"));assertEquals(0, count(r, "place"));assertEquals(0, count(r, "itemPlace"));assertEquals(0, count(r, "consume"));
                boolean shortage = scenario.equals("missing") || scenario.equals("exactMissing");
                assertEquals(shortage ? 1 : 0, count(r, "materials"));assertEquals(shortage ? 1 : 0, count(r, "shortage"));
                assertEquals(scenario.equals("entity") ? 0 : 1, count(r, "mode"));
                assertEquals(scenario.equals("entity") || scenario.equals("mode") ? 0 : 1, count(r, "hopeless"));
                assertEquals("OUTSIDE_TICK_SLICE", r.get("tick"));
            }
        }
    }

    @Test void legacyNullInPlaceDoesNotReadBodyEvenAtWrongColumnOrBlockedHead() throws Exception {
        for (String scenario : List.of("x", "z", "high", "low", "feetBlocked", "headBlocked", "feetTarget", "headTarget")) {
            var r = cell(scenario, false);
            assertEquals(true, r.get("produced"), scenario);assertEquals(false, r.get("violation"));
            for (String key : List.of("bodyX", "bodyY", "bodyZ", "peek", "fail")) assertEquals(0, count(r, key), key);
            assertEquals(1, count(r, "materials"));assertEquals(1, count(r, "place"));
        }
    }

    @Test void eachCellRechecksLiveBodyAndHeadroomAfterPriorSuccessAndOnStartReentry() throws Exception {
        for (boolean resume : List.of(false, true)) {
            for (String change : List.of("body", "feet", "head")) {
                var r = probe("recheck", new Class<?>[]{String.class, boolean.class}, change, resume);
                assertEquals(true, r.get("firstProduced"), "first real processCell must reach placement");
                assertRejected(r, change + " resume=" + resume);
            }
        }
    }

    @Test void realExistingShortCircuitsAndFailedWorldWriteDoNotInventProduction() throws Exception {
        for (String scenario : List.of("liquid", "unloaded", "matched", "writeRefused")) {
            var r = cell(scenario, true);
            assertEquals(false, r.get("produced"));assertEquals(false, r.get("violation"));
            assertEquals(0, count(r, "consume"));assertEquals(0, count(r, "clear"));
            assertEquals(scenario.equals("writeRefused") ? 1 : 0, count(r, "place"));
            assertEquals(scenario.equals("matched") ? 1 : 0, count(r, "observed"));
            assertEquals(scenario.equals("writeRefused") ? 1 : 0, count(r, "materials"));
        }
    }

    @Test void realStopAndCleanupReleaseBoundariesWithoutImmediateNavigationOrPlacement() throws Exception {
        // Only these real members are executed, not base lifecycle delivery or a future tick gate.
        for(String action:List.of("stop","preempt","cleanup")) {
            var r=probe("lifecycle",new Class<?>[]{String.class},action);
            assertEquals(action.equals("cleanup")?0:1,count(r,"superStop"));
            assertEquals(action.equals("cleanup")?1:0,count(r,"superCleanup"));
            assertEquals(1,count(r,"unregister"));assertEquals(1,count(r,"halt"));
            assertEquals(action.equals("cleanup")?1:0,count(r,"shift"));assertEquals(0,count(r,"shiftTrue"));
            assertEquals("build interrupted after 0/0 block(s)",r.get("cancelled"));
            for(String key:List.of("navCreate","navTick","place","itemPlace","consume","clear"))assertEquals(0,count(r,key),key);
        }
    }

    @Test void realSchemaMakesInPlaceOptionalButRequiresAllFourAnchorCoordinates() throws Exception {
        var schema = toolProbe("schemaProbe", new Class<?>[]{});
        assertEquals(List.of("ops"), schema.get("required"));
        Map<?,?> properties = (Map<?,?>) schema.get("properties");
        Map<?,?> anchor = (Map<?,?>) properties.get("in_place");
        assertEquals(List.of("object", "null"), anchor.get("type"));
        assertEquals(List.of("x", "z", "feet_y", "head_y"), anchor.get("required"));
        Map<?,?> coordinates = (Map<?,?>) anchor.get("properties");
        for (String key : List.of("x", "z", "feet_y", "head_y")) {
            assertEquals("integer", ((Map<?,?>) coordinates.get(key)).get("type"), key);
        }
    }

    @Test void realToolAssignsParsedAnchorAndPreservesLegacyOmissionAndNull() throws Exception {
        for (String suffix : List.of("", ",\"in_place\":null")) {
            var r = toolCall(suffix);
            assertFalse(r.containsKey("error"));assertEquals(1, count(r,"setTask"));
            assertEquals(true,r.get("inPlaceNull"));assertEquals(1,count(r,"ctx"));
            assertEquals(true,r.get("replaceExisting"));assertEquals(true,r.get("consumeMaterials"));
            assertEquals(false,r.get("allowPartial"));assertEquals(0,r.get("targets"),"shape expansion is an empty boundary");
        }
        for (String json : List.of("{\"x\":10,\"z\":20,\"feet_y\":60,\"head_y\":61}",
                "{\"x\":10,\"z\":20,\"feet_y\":-2147483648,\"head_y\":-2147483647}")) {
            var r = toolCall(",\"in_place\":"+json);
            assertFalse(r.containsKey("error"));assertEquals(1,count(r,"setTask"));
            assertEquals(false,r.get("inPlaceNull"));
            assertEquals(json.contains("-2147483648") ? List.of(10,20,Integer.MIN_VALUE,Integer.MIN_VALUE+1)
                    : List.of(10,20,60,61),r.get("anchor"));
        }
    }

    @Test void realToolRejectsInvalidAnchorBeforeDispatchIncludingOverflowPair() throws Exception {
        for (String anchor : List.of(
                "{\"x\":10,\"z\":20,\"feet_y\":60,\"head_y\":61.5}",
                "{\"x\":\"NaN\",\"z\":20,\"feet_y\":60,\"head_y\":61}",
                "{\"x\":2147483648,\"z\":20,\"feet_y\":60,\"head_y\":61}",
                "{\"x\":10,\"z\":20,\"feet_y\":60}",
                "{\"x\":10,\"z\":20,\"feet_y\":60,\"head_y\":62}",
                "{\"x\":10,\"z\":20,\"feet_y\":2147483647,\"head_y\":-2147483648}")) {
            var r = toolCall(",\"in_place\":"+anchor);
            assertInstanceOf(IllegalArgumentException.class,r.get("error"),anchor);
            assertTrue(((IllegalArgumentException)r.get("error")).getMessage().contains("in_place"),anchor);
            assertEquals(0,count(r,"setTask"),anchor);
            // ctx has already been called to construct the record before BuildInPlace.parse rejects.
        }
    }

    static Map<String,Object> toolCall(String suffix) throws Exception {
        return toolProbe("callProbe",new Class<?>[]{String.class},"{\"ops\":[{\"op\":\"set\"}]"+suffix+"}");
    }
    @SuppressWarnings("unchecked") static Map<String,Object> toolProbe(String method, Class<?>[] types, Object...args) throws Exception {
        return (Map<String,Object>) invoke(loader.loadClass("com.dwinovo.numen.core.tools.work.BuildTool"),method,types,args);
    }

    static void assertRejected(Map<String,Object> r, String context) {
        assertEquals(false, r.get("produced"), context);assertEquals(true, r.get("violation"), context);
        assertEquals("FAILED", r.get("tick"), context);assertEquals("NO_PATH", r.get("failure"), context);
        assertTrue(((String)r.get("reason")).contains("in_place anchor broken before placing"), context);
        assertEquals(1, count(r, "fail"), context);
        assertEquals(0, count(r, "placedDelta"), context + ": rejected guard must not advance real record placement count");
        for (String key : List.of("level", "loaded", "worldRead", "entity", "mode", "hopeless", "cost", "needs", "materials",
                "clear", "place", "itemPlace", "consume", "shortage", "observed", "blockEntityData", "navCreate", "navTick")) {
            assertEquals(0, count(r, key), context + ": rejected guard must precede " + key);
        }
    }

    static int count(Map<String,Object> r, String key) { return ((Number)r.getOrDefault(key, 0)).intValue(); }
    static Map<String,Object> cell(String scenario, boolean anchored) throws Exception {
        return probe("cell", new Class<?>[]{String.class, boolean.class}, scenario, anchored);
    }
    @SuppressWarnings("unchecked") static Map<String,Object> probe(String method, Class<?>[] types, Object...args) throws Exception {
        return (Map<String,Object>) invoke(fixture,method,types,args);
    }
    static Object invoke(Class<?> owner,String method,Class<?>[] types,Object...args) throws Exception {
        try { return owner.getMethod(method,types).invoke(null,args); }
        catch(java.lang.reflect.InvocationTargetException wrapped) {
            Throwable cause=wrapped.getCause();
            if(cause instanceof Exception ex)throw ex;
            if(cause instanceof Error error)throw error;
            throw new IllegalStateException("SETUP ERROR: unexpected reflection cause",cause);
        }
    }
    // Args/OpSpec have empty bodies; ordinary member extraction would consume the next method.
    static String emptyRecord(String source,String signature) {
        int start=unique(source,signature),end=source.indexOf("{}",start);
        if(end<0)throw new IllegalStateException("SETUP ERROR: incomplete empty record "+signature);
        return source.substring(start,end+2)+'\n';
    }
    static void add(Path src, List<String> args, String relative, String source) throws Exception {
        Path p=src.resolve(relative);Files.createDirectories(p.getParent());Files.writeString(p,source,StandardCharsets.UTF_8);args.add(p.toString());
    }
    static String field(String source, String signature) {
        int start=unique(source,signature), end=source.indexOf(';',start);
        if(end<0)throw new IllegalStateException("SETUP ERROR: incomplete field "+signature);
        return source.substring(start,end+1);
    }
    static String member(String source, String signature) { return indentedMember(source,signature,"\n    }"); }
    static String nestedMember(String source, String signature) { return indentedMember(source,signature,"\n        }"); }
    static String indentedMember(String source, String signature, String close) {
        int start=unique(source,signature),end=source.indexOf(close,start);
        if(end<0)throw new IllegalStateException("SETUP ERROR: incomplete production member "+signature);
        return source.substring(start,end+close.length());
    }
    static String between(String source, String start, String end) {
        int a=unique(source,start),b=unique(source,end);
        if(b<=a)throw new IllegalStateException("SETUP ERROR: invalid slice "+start);
        return source.substring(a,b);
    }
    static int unique(String source, String token) {
        int at=source.indexOf(token);
        if(at<0 || source.indexOf(token,at+1)>=0)throw new IllegalStateException("SETUP ERROR: unique source marker "+token);
        return at;
    }
}
