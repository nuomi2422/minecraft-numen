package com.dwinovo.numen.core.task.build;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dynamic consumer of the REAL clearing call site and the REAL precheck, not a
 * source-contains check. Extracts verbatim from BuildCompanionTask: clear(BlockPos),
 * the occupied-cell block of processCell, checkClearSafety(), computeWillClear() and
 * loadedTest(), then runs them against a recording stub world together with the verbatim
 * BuildClearSafety + real ChunkLoadedTest.
 *
 * <p>Each case performs exactly ONE dispatch: the call-site block is called once and it
 * calls clear() once. The report keeps guard/precheck and the actual write result in
 * separate fields (result=written|refused|none, refusal=reason), so a refused write is
 * never reported as ALLOW. BlockHelper stays a state-identity fixture boundary.
 * SETUP failures (extraction, javac, load) are setup errors, not business reds.
 */
class BuildClearCallSiteConsumerTest {

    @TempDir static Path temp;
    static URLClassLoader loader;
    static Method runCase;

    @BeforeAll static void compileCallSite() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("core/common/src/main/java"))) {
            root = root.getParent();
        }
        if (root == null) throw new IllegalStateException("SETUP ERROR: cannot locate candidate tree");
        Path main = root.resolve("core/common/src/main/java/com/dwinovo/numen/core");
        Path src = temp.resolve("src");
        Path out = temp.resolve("classes");
        Files.createDirectories(out);
        List<String> args = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none",
                "-d", out.toString()));
        String task = Files.readString(main.resolve("task/build/BuildCompanionTask.java"))
                .replace("\r\n", "\n");
        String clear = member(task, "    private BuildClearSafety.Verdict clear(BlockPos pos)");
        String precheck = member(task, "    private Precondition.Failure checkClearSafety()");
        String willClear = member(task, "    private LongOpenHashSet computeWillClear()");
        String loaded = member(task, "    private ChunkLoadedTest loadedTest()");
        String processCell = member(task, "    private BlockState processCell(");
        String callSite = member(processCell, "        if (occupied) {");
        String probe = PROBE.replace("<CLEAR>", clear)
                .replace("<CALLSITE>", callSite)
                .replace("<CHECK>", precheck)
                .replace("<WILLCLEAR>", willClear)
                .replace("<LOADED>", loaded);

        add(src, args, "com/dwinovo/numen/core/task/build/BuildClearSafety.java",
                Files.readString(main.resolve("task/build/BuildClearSafety.java")));
        add(src, args, "com/dwinovo/numen/core/pathing/moves/ChunkLoadedTest.java",
                Files.readString(main.resolve("pathing/moves/ChunkLoadedTest.java")));
        add(src, args, "com/dwinovo/numen/core/pathing/util/BlockHelper.java", STUB_BLOCK_HELPER);
        add(src, args, "com/dwinovo/numen/core/Constants.java", """
                package com.dwinovo.numen.core;
                public final class Constants {
                    public static final Log LOG = new Log();
                    public static final class Log { public void warn(String s, Object... a) { } }
                }
                """);
        add(src, args, "com/dwinovo/numen/core/FailureType.java", """
                package com.dwinovo.numen.core;
                public enum FailureType { NO_MATERIAL, NO_PATH, TARGET_LOST }
                """);
        add(src, args, "com/dwinovo/numen/core/task/base/Precondition.java", """
                package com.dwinovo.numen.core.task.base;
                import com.dwinovo.numen.core.FailureType;
                public final class Precondition {
                    public record Failure(String reason, FailureType type) { }
                }
                """);
        add(src, args, "it/unimi/dsi/fastutil/longs/LongOpenHashSet.java", """
                package it.unimi.dsi.fastutil.longs;
                import java.util.*;
                public class LongOpenHashSet implements Iterable<Long> {
                    private final List<Long> keys = new ArrayList<>();
                    public boolean add(long k){ if (keys.contains(k)) return false; keys.add(k); return true; }
                    public boolean contains(long k){ return keys.contains(k); }
                    public boolean isEmpty(){ return keys.isEmpty(); }
                    public int size(){ return keys.size(); }
                    public Iterator<Long> iterator(){ return keys.iterator(); }
                }
                """);
        add(src, args, "net/minecraft/core/BlockPos.java", """
                package net.minecraft.core;
                public final class BlockPos {
                    private final int x, y, z;
                    public BlockPos(int x, int y, int z) { this.x=x; this.y=y; this.z=z; }
                    public int getX(){return x;} public int getY(){return y;} public int getZ(){return z;}
                    public BlockPos above(){return new BlockPos(x,y+1,z);}
                    public long asLong(){return ((long)x & 0x3FFFFFFL) << 38 | ((long)z & 0x3FFFFFFL) << 12 | (y & 0xFFFL);}
                    public static BlockPos of(long packed){
                        int x=(int)(packed >> 38); int y=(int)(packed << 52 >> 52); int z=(int)(packed << 26 >> 38);
                        return new BlockPos(x,y,z);}
                    public String toShortString(){return x+","+y+","+z;}
                }
                """);
        add(src, args, "net/minecraft/server/level/ServerLevel.java", """
                package net.minecraft.server.level;
                public class ServerLevel { }
                """);
        add(src, args, "net/minecraft/world/level/BlockGetter.java", """
                package net.minecraft.world.level;
                import net.minecraft.core.BlockPos;
                import net.minecraft.world.level.block.state.BlockState;
                @FunctionalInterface
                public interface BlockGetter { BlockState getBlockState(BlockPos pos); }
                """);
        add(src, args, "net/minecraft/world/level/material/FluidState.java", """
                package net.minecraft.world.level.material;
                public final class FluidState {
                    public static final FluidState EMPTY = new FluidState(true), WATER = new FluidState(false);
                    private final boolean empty;
                    private FluidState(boolean empty){this.empty=empty;}
                    public boolean isEmpty(){return empty;}
                }
                """);
        add(src, args, "net/minecraft/world/phys/AABB.java", """
                package net.minecraft.world.phys;
                public final class AABB {
                    public final double minX,minY,minZ,maxX,maxY,maxZ;
                    public AABB(double minX,double minY,double minZ,double maxX,double maxY,double maxZ){
                        this.minX=minX;this.minY=minY;this.minZ=minZ;this.maxX=maxX;this.maxY=maxY;this.maxZ=maxZ;}
                }
                """);
        add(src, args, "net/minecraft/world/phys/shapes/VoxelShape.java", """
                package net.minecraft.world.phys.shapes;
                import net.minecraft.world.phys.AABB;
                import java.util.List;
                public final class VoxelShape {
                    public static final VoxelShape EMPTY = new VoxelShape(List.of());
                    private final List<AABB> parts;
                    public VoxelShape(List<AABB> parts){this.parts=parts;}
                    public static VoxelShape of(AABB... parts){return new VoxelShape(List.of(parts));}
                    public boolean isEmpty(){return parts.isEmpty();}
                    public List<AABB> toAabbs(){return parts;}
                }
                """);
        add(src, args, "net/minecraft/world/item/ItemStack.java", """
                package net.minecraft.world.item;
                public final class ItemStack { }
                """);
        add(src, args, "net/minecraft/world/level/block/Block.java", """
                package net.minecraft.world.level.block;
                import net.minecraft.core.BlockPos;
                import net.minecraft.world.item.ItemStack;
                import net.minecraft.world.level.block.state.BlockState;
                import java.util.*;
                public class Block {
                    public static final List<String> CALLS = new ArrayList<>();
                    public static int DROPS, DROPPED;
                    public BlockState defaultBlockState(){return BlockState.AIR;}
                    public static List<ItemStack> getDrops(BlockState s, Object level, BlockPos p,
                            Object be, Object entity, ItemStack tool){
                        CALLS.add("drops"); DROPS++;
                        List<ItemStack> out = new ArrayList<>(); out.add(new ItemStack()); return out;
                    }
                    public static void popResource(Object level, BlockPos p, ItemStack stack){
                        CALLS.add("spawn"); DROPPED++;
                    }
                    public static int getId(BlockState s){return 1;}
                }
                """);
        add(src, args, "net/minecraft/world/level/block/Blocks.java", """
                package net.minecraft.world.level.block;
                public final class Blocks { public static final Block AIR = new Block(); }
                """);
        add(src, args, "net/minecraft/world/level/block/FallingBlock.java", """
                package net.minecraft.world.level.block;
                public class FallingBlock extends Block { }
                """);
        add(src, args, "net/minecraft/world/level/block/LiquidBlock.java", """
                package net.minecraft.world.level.block;
                public class LiquidBlock extends Block { }
                """);
        add(src, args, "net/minecraft/world/level/block/state/BlockState.java", """
                package net.minecraft.world.level.block.state;
                import net.minecraft.core.BlockPos;
                import net.minecraft.world.level.BlockGetter;
                import net.minecraft.world.level.block.Block;
                import net.minecraft.world.level.material.FluidState;
                import net.minecraft.world.phys.shapes.VoxelShape;
                public final class BlockState {
                    public static final BlockState AIR = new BlockState(new Block(), true, false, VoxelShape.EMPTY);
                    public final Block block; public final boolean air; public final boolean liquid;
                    public final VoxelShape shape;
                    public BlockState(Block block, boolean air, boolean liquid, VoxelShape shape){
                        this.block=block;this.air=air;this.liquid=liquid;this.shape=shape;}
                    public Block getBlock(){return block;}
                    public boolean isAir(){return air;}
                    public FluidState getFluidState(){return liquid?FluidState.WATER:FluidState.EMPTY;}
                    public VoxelShape getCollisionShape(BlockGetter level, BlockPos pos){return shape;}
                    public void spawnAfterBreak(Object server, BlockPos pos, net.minecraft.world.item.ItemStack tool,
                            boolean dropXp){ Block.CALLS.add("afterBreak"); }
                }
                """);
        add(src, args, "com/dwinovo/numen/core/task/build/Probe.java", probe);
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("SETUP ERROR: no system java compiler");
        if (compiler.run(null, null, null, args.toArray(String[]::new)) != 0) {
            throw new IllegalStateException("SETUP ERROR: call-site consumers did not compile");
        }
        loader = new URLClassLoader(new URL[]{out.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
        Class<?> probeClass = loader.loadClass("com.dwinovo.numen.core.task.build.Probe");
        runCase = probeClass.getMethod("run", String.class);
    }

    @AfterAll static void closeLoader() throws Exception {
        if (loader != null) loader.close();
    }

    static void add(Path src, List<String> args, String rel, String body) throws Exception {
        Path f = src.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, body);
        args.add(f.toString());
    }

    static String member(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) throw new IllegalStateException("SETUP ERROR: missing " + signature);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return source.substring(start, i + 1);
            }
        }
        throw new IllegalStateException("SETUP ERROR: unbalanced " + signature);
    }

    static String run(String name) {
        try {
            return (String) runCase.invoke(null, name);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SETUP ERROR: probe invocation failed", e);
        }
    }

    static int field(String out, String key) {
        for (String part : out.split("\\|")) {
            if (part.startsWith(key + "=")) return Integer.parseInt(part.substring(key.length() + 1));
        }
        throw new AssertionError("missing " + key + " in " + out);
    }

    static String text(String out, String key) {
        for (String part : out.split("\\|")) {
            if (part.startsWith(key + "=")) return part.substring(key.length() + 1);
        }
        throw new AssertionError("missing " + key + " in " + out);
    }

    @Test void safetyRefusalDoesOneDispatchAndNoSideEffect() {
        String r = run("refused");
        assertEquals("refused", text(r, "result"), r);
        assertTrue(text(r, "refusal").contains("falling block"), r);
        assertEquals(0, field(r, "set"));
        assertEquals(0, field(r, "drops"));
        assertEquals(0, field(r, "spawned"));
        assertEquals(0, field(r, "broke"));
        assertEquals(0, field(r, "marked"));
        assertTrue(r.endsWith("produced=false"), r);
    }

    @Test void allowedClearWritesOnceWithDropsAfterWrite() {
        String r = run("allowed");
        assertEquals("written", text(r, "result"), r);
        assertEquals(1, field(r, "set"));
        assertEquals(1, field(r, "drops"));
        assertEquals(1, field(r, "spawned"));
        assertEquals(1, field(r, "broke"));
        assertEquals(1, field(r, "marked"));
        assertEquals(1, field(r, "setInCalls"), r);
        assertTrue(r.endsWith("produced=true"), r);
        assertTrue(r.contains("calls=drops>set>spawn"), r);
    }

    @Test void worldRefusedWriteIsOneAttemptWithoutSpawnOrBroke() {
        String r = run("writeRefused");
        assertEquals("refused", text(r, "result"), r);
        assertTrue(text(r, "refusal").contains("refused the clear"), r);
        assertEquals(1, field(r, "set"));
        assertEquals(1, field(r, "drops"));
        assertEquals(0, field(r, "spawned"));
        assertEquals(0, field(r, "broke"));
        assertEquals(0, field(r, "marked"));
        assertTrue(r.endsWith("produced=false"), r);
    }

    @Test void unknownPrecheckRejectsInsteadOfEmptySuccess() {
        String r = run("precheckUnknown");
        assertTrue(r.startsWith("precheck=fail"), r);
        assertTrue(r.contains("not loaded yet"), r);
    }

    @Test void safePrecheckPasses() {
        String r = run("precheckSafe");
        assertTrue(r.startsWith("precheck=pass"), r);
    }

    @Test void sandAbovePrecheckRejects() {
        String r = run("precheckSand");
        assertTrue(r.startsWith("precheck=fail"), r);
        assertTrue(r.contains("falling block"), r);
    }

    private static final String STUB_BLOCK_HELPER = """
            package com.dwinovo.numen.core.pathing.util;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.FallingBlock;
            /** Fixture boundary: state identity of the two predicates only. */
            public final class BlockHelper {
                public static final Block HAZARD = new Block();
                public static boolean breakReleasesFallingBlock(BlockGetter level, BlockPos pos){
                    return level.getBlockState(pos.above()).getBlock() instanceof FallingBlock;}
                public static boolean isHazard(BlockGetter level, BlockPos pos){
                    return level.getBlockState(pos).getBlock() == HAZARD;}
            }
            """;

    private static final String PROBE = """
            package com.dwinovo.numen.core.task.build;
            import com.dwinovo.numen.core.FailureType;
            import com.dwinovo.numen.core.pathing.moves.ChunkLoadedTest;
            import com.dwinovo.numen.core.task.base.Precondition;
            import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
            import net.minecraft.core.BlockPos;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.Blocks;
            import net.minecraft.world.level.block.FallingBlock;
            import net.minecraft.world.level.block.LiquidBlock;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.phys.AABB;
            import net.minecraft.world.phys.shapes.VoxelShape;
            import java.util.*;

            public final class Probe {
                static final int PLACE_FLAGS = 0;
                static final BlockState SOLID = new BlockState(new Block(), false, false,
                        VoxelShape.of(new AABB(0,0,0,1,1,1)));
                static final BlockState SAND = new BlockState(new FallingBlock(), false, false,
                        VoxelShape.of(new AABB(0,0,0,1,1,1)));
                static final BlockState TARGET = new BlockState(new Block(), false, false,
                        VoxelShape.of(new AABB(0,0,0,1,1,1)));

                final World world = new World();
                final Player player = new Player(world);
                final Rec r = new Rec();
                final Rules rules = new Rules();
                final Map<Long, String> clearRefusals = new LinkedHashMap<>();
                final ClearSet willClear = new ClearSet();
                int marked, broke;

                public static String run(String name) {
                    return new Probe().caseFor(name);
                }

                private String caseFor(String name) {
                    Block.CALLS.clear(); Block.DROPS = 0; Block.DROPPED = 0;
                    BlockPos pos = new BlockPos(0, 4, 0);
                    switch (name) {
                        case "refused" -> {
                            world.blocks.put(pos.asLong(), SOLID);
                            world.blocks.put(new BlockPos(0,5,0).asLong(), SAND);
                        }
                        case "allowed", "writeRefused" -> {
                            world.blocks.put(pos.asLong(), SOLID);
                            world.blocks.put(new BlockPos(0,3,0).asLong(), SOLID);
                            if (name.equals("writeRefused")) world.writable = false;
                        }
                        case "precheckUnknown" -> {
                            r.targets.add(new Target(pos, TARGET));
                            world.blocks.put(pos.asLong(), SOLID);
                            world.unloaded.add(pos.asLong());
                        }
                        case "precheckSafe" -> {
                            r.targets.add(new Target(pos, TARGET));
                            world.blocks.put(pos.asLong(), SOLID);
                            world.blocks.put(new BlockPos(0,3,0).asLong(), SOLID);
                        }
                        case "precheckSand" -> {
                            r.targets.add(new Target(pos, TARGET));
                            world.blocks.put(pos.asLong(), SOLID);
                            world.blocks.put(new BlockPos(0,5,0).asLong(), SAND);
                        }
                        default -> throw new IllegalArgumentException("unknown case " + name);
                    }
                    if (name.startsWith("precheck")) {
                        Precondition.Failure failure = checkClearSafety();
                        return failure == null ? "precheck=pass"
                                : "precheck=fail|reason=" + failure.reason();
                    }
                    BlockState produced = callSite(pos, true, BlockState.AIR, new Target(pos, BlockState.AIR));
                    String result = !clearRefusals.isEmpty() ? "refused"
                            : (world.setBlockCalls == 1 && world.blocks.getOrDefault(pos.asLong(), SOLID).isAir()
                                    ? "written" : "none");
                    String refusal = clearRefusals.isEmpty() ? "none" : clearRefusals.values().iterator().next();
                    int setInCalls = 0;
                    for (String c : Block.CALLS) if (c.equals("set")) setInCalls++;
                    return "result=" + result
                            + "|refusal=" + refusal
                            + "|set=" + world.setBlockCalls
                            + "|drops=" + Block.DROPS
                            + "|spawned=" + Block.DROPPED
                            + "|broke=" + broke
                            + "|marked=" + marked
                            + "|setInCalls=" + setInCalls
                            + "|calls=" + String.join(">", Block.CALLS)
                            + "|produced=" + (produced != null);
                }

                void markObserved(Target target, boolean done) {
                    marked++;
                }

                final class Rec {
                    boolean consumeMaterials = true;
                    final List<Target> targets = new ArrayList<>();
                    void brokeOne() { broke++; }
                }

                final class Rules {
                    boolean mode, hopeless;
                    BlockState peek(BlockPos pos) {
                        return world.blocks.getOrDefault(pos.asLong(), BlockState.AIR);
                    }
                    boolean blockedByMode(Target target) { return mode; }
                    boolean hopeless(Target target) { return hopeless; }
                }

                static final class ClearSet {
                    final Set<Long> keys = new HashSet<>();
                    boolean contains(long key) { return keys.contains(key); }
                }

                static final class Target {
                    final BlockPos pos;
                    final BlockState desired;
                    Target(BlockPos pos, BlockState desired) { this.pos = pos; this.desired = desired; }
                    BlockPos pos() { return pos; }
                    BlockState desiredState() { return desired; }
                    boolean matches(BlockState state) {
                        return state == desired || (state.isAir() && desired.isAir());
                    }
                }

                static final class BuildCellRules {
                    static boolean isAirTarget(Target target) { return target.desiredState().isAir(); }
                }

                static final class World extends ServerLevel implements BlockGetter {
                    final Map<Long, BlockState> blocks = new HashMap<>();
                    final Set<Long> unloaded = new HashSet<>();
                    boolean writable = true;
                    int setBlockCalls, levelEvents;
                    public BlockState getBlockState(BlockPos pos) {
                        return blocks.getOrDefault(pos.asLong(), BlockState.AIR);
                    }
                    boolean isLoaded(BlockPos pos) { return !unloaded.contains(pos.asLong()); }
                    boolean hasChunk(int chunkX, int chunkZ) { return true; }
                    Object getBlockEntity(BlockPos pos) { return null; }
                    boolean setBlock(BlockPos pos, BlockState state, int flags) {
                        setBlockCalls++;
                        Block.CALLS.add("set");
                        if (!writable) return false;
                        blocks.put(pos.asLong(), state);
                        return true;
                    }
                    void levelEvent(int id, BlockPos pos, int data) { levelEvents++; }
                }

                static final class Player {
                    final World world;
                    Player(World world) { this.world = world; }
                    World level() { return world; }
                    AABB getBoundingBox() { return new AABB(0.0, 5.0, 0.0, 0.6, 6.8, 0.6); }
                    ItemStack getMainHandItem() { return new ItemStack(); }
                }

                // ==== verbatim from BuildCompanionTask (production) ====
                <CLEAR>

                // ==== verbatim occupied-cell block from processCell (production) ====
                BlockState callSite(BlockPos pos, boolean occupied, BlockState desired, Target target) {
            <CALLSITE>
                    return null;
                }

                // ==== verbatim precheck / batch / loaded seam (production) ====
            <CHECK>

            <WILLCLEAR>

            <LOADED>
            }
            """;
}
