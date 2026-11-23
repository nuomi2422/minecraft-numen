package com.dwinovo.numen.core.task.build;

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
 * Dynamic consumer of the REAL clearing call site, not a source-contains check.
 * Extracts verbatim (a) BuildCompanionTask.clear(BlockPos) and (b) the occupied-cell
 * block of processCell, then runs them against a recording stub world together with the
 * verbatim BuildClearSafety + real ChunkLoadedTest. Proves: a safety refusal causes zero
 * setBlock, zero drops, zero broke and no markObserved; a world-refused setBlock is not
 * counted as cleared and still does not mark the cell done; drops are computed before the
 * write and spawned only after it. BlockHelper stays a state-identity fixture boundary.
 * SETUP failures (extraction, javac, load) are setup errors, not business reds.
 */
class BuildClearCallSiteConsumerTest {

    @TempDir static Path temp;
    static ClassLoader loader;
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
        int site = task.indexOf("        if (occupied) {");
        int after = task.indexOf("        if (BuildCellRules.isAirTarget(target)) {\n"
                + "            markObserved(target, true);\n            return null;", site);
        if (site < 0 || after < 0) throw new IllegalStateException("SETUP ERROR: call site not found");
        String callSite = task.substring(site, after);
        String probe = PROBE.replace("<CLEAR>", clear).replace("<CALLSITE>", callSite);

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
        add(src, args, "net/minecraft/core/BlockPos.java", """
                package net.minecraft.core;
                public final class BlockPos {
                    private final int x, y, z;
                    public BlockPos(int x, int y, int z) { this.x=x; this.y=y; this.z=z; }
                    public int getX(){return x;} public int getY(){return y;} public int getZ(){return z;}
                    public BlockPos above(){return new BlockPos(x,y+1,z);}
                    public long asLong(){return ((long)x & 0x3FFFFFFL) << 38 | ((long)z & 0x3FFFFFFL) << 12 | (y & 0xFFFL);}
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
                BuildClearCallSiteConsumerTest.class.getClassLoader());
        Class<?> probeClass = loader.loadClass("com.dwinovo.numen.core.task.build.Probe");
        runCase = probeClass.getMethod("run", String.class);
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

    @Test void safetyRefusalDoesNothingAndDoesNotMark() {
        String r = run("refused");
        assertTrue(r.startsWith("REFUSE"), r);
        assertEquals(0, field(r, "set"));
        assertEquals(0, field(r, "drops"));
        assertEquals(0, field(r, "spawned"));
        assertEquals(0, field(r, "broke"));
        assertEquals(0, field(r, "marked"));
        assertTrue(r.endsWith("produced=false"), r);
    }

    @Test void allowedClearWritesThenDropsAndMarks() {
        String r = run("allowed");
        assertTrue(r.startsWith("ALLOW"), r);
        assertEquals(1, field(r, "set"));
        assertEquals(1, field(r, "drops"));
        assertEquals(1, field(r, "spawned"));
        assertEquals(1, field(r, "broke"));
        assertEquals(1, field(r, "marked"));
        assertTrue(r.endsWith("produced=true"), r);
        assertTrue(r.contains("calls=drops>set>spawn"), r);
    }

    @Test void worldRefusedWriteIsNotCountedClearedAndDoesNotMark() {
        String r = run("writeRefused");
        assertTrue(r.startsWith("ALLOW"), r);
        assertEquals(0, field(r, "spawned"));
        assertEquals(0, field(r, "broke"));
        assertEquals(0, field(r, "marked"));
        assertEquals(1, field(r, "refused"));
        assertTrue(r.endsWith("produced=false"), r);
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
            import com.dwinovo.numen.core.pathing.moves.ChunkLoadedTest;
            import net.minecraft.core.BlockPos;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.Blocks;
            import net.minecraft.world.level.block.FallingBlock;
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

                final World world = new World();
                final Player player = new Player(world);
                final Rec r = new Rec();
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
                        default -> throw new IllegalArgumentException("unknown case " + name);
                    }
                    BuildClearSafety.Verdict verdict = clear(pos);
                    BlockState produced = callSite(pos, true, BlockState.AIR, new Target(BlockState.AIR));
                    return verdict.admission().name()
                            + "|set=" + world.setBlockCalls
                            + "|drops=" + Block.DROPS
                            + "|spawned=" + Block.DROPPED
                            + "|broke=" + broke
                            + "|marked=" + marked
                            + "|refused=" + clearRefusals.size()
                            + "|calls=" + String.join(">", Block.CALLS)
                            + "|produced=" + (produced != null);
                }

                private ChunkLoadedTest loadedTest() {
                    return (x, z) -> world.hasChunk(x >> 4, z >> 4);
                }

                void markObserved(Target target, boolean done) {
                    marked++;
                }

                final class Rec {
                    boolean consumeMaterials = true;
                    void brokeOne() { broke++; }
                }

                static final class ClearSet {
                    final Set<Long> keys = new HashSet<>();
                    boolean contains(long key) { return keys.contains(key); }
                }

                static final class Target {
                    final BlockState desired;
                    Target(BlockState desired) { this.desired = desired; }
                    BlockState desiredState() { return desired; }
                }

                static final class BuildCellRules {
                    static boolean isAirTarget(Target target) { return target.desiredState().isAir(); }
                }

                static final class World extends ServerLevel implements BlockGetter {
                    final Map<Long, BlockState> blocks = new HashMap<>();
                    boolean writable = true;
                    int setBlockCalls, levelEvents;
                    public BlockState getBlockState(BlockPos pos) {
                        return blocks.getOrDefault(pos.asLong(), BlockState.AIR);
                    }
                    boolean isLoaded(BlockPos pos) { return true; }
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
            }
            """;
}
