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
 * Test-only source consumer: no copied admission logic. Compiles the real production
 * BuildClearSafety (verbatim) plus the real ChunkLoadedTest seam, and drives it with a
 * stub world. BlockHelper is a state-identity boundary here: the falling-block and hazard
 * predicates are fixtures, NOT re-proven; what this proves is the production decision
 * itself — footprint intersection, standing surface, pending-clear awareness, loaded /
 * unknown, safe-fall 3 vs >3, liquid/hazard landing, and that a non-contacting shape
 * cannot stop the downward scan or pass as a landing.
 *
 * <p>SETUP failures (locate tree, javac, class load) are setup errors, not business reds.
 * Parent owns Java execution; this file is source only until the 3rd seat runs it.
 */
class BuildClearSafetyConsumerTest {

    @TempDir static Path temp;
    static ClassLoader loader;
    static Method runCase;

    @BeforeAll static void compileConsumers() throws Exception {
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
        add(src, args, "com/dwinovo/numen/core/task/build/BuildClearSafety.java",
                Files.readString(main.resolve("task/build/BuildClearSafety.java")));
        add(src, args, "com/dwinovo/numen/core/pathing/moves/ChunkLoadedTest.java",
                Files.readString(main.resolve("pathing/moves/ChunkLoadedTest.java")));

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
        add(src, args, "net/minecraft/world/level/block/Block.java", """
                package net.minecraft.world.level.block;
                public class Block { }
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
                }
                """);
        add(src, args, "com/dwinovo/numen/core/pathing/util/BlockHelper.java", """
                package com.dwinovo.numen.core.pathing.util;
                import net.minecraft.core.BlockPos;
                import net.minecraft.world.level.BlockGetter;
                import net.minecraft.world.level.block.Block;
                import net.minecraft.world.level.block.FallingBlock;
                /** Fixture boundary: only the state-identity of the two predicates, not their internals. */
                public final class BlockHelper {
                    public static final Block HAZARD = new Block();
                    public static boolean breakReleasesFallingBlock(BlockGetter level, BlockPos pos){
                        return level.getBlockState(pos.above()).getBlock() instanceof FallingBlock;}
                    public static boolean isHazard(BlockGetter level, BlockPos pos){
                        return level.getBlockState(pos).getBlock() == HAZARD;}
                }
                """);
        add(src, args, "com/dwinovo/numen/core/task/build/Fixture.java", FIXTURE);
        if (ToolProvider.getSystemJavaCompiler() == null) {
            throw new IllegalStateException("SETUP ERROR: no system java compiler");
        }
        int code = ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new));
        assertEquals(0, code, "SETUP ERROR: production consumer compile failed");
        loader = new URLClassLoader(new URL[]{out.toUri().toURL()},
                BuildClearSafetyConsumerTest.class.getClassLoader());
        Class<?> fixture = loader.loadClass("com.dwinovo.numen.core.task.build.Fixture");
        Object instance = fixture.getDeclaredConstructor().newInstance();
        runCase = fixture.getMethod("run", String.class);
        runCase.setAccessible(true);
    }

    @AfterAll static void closeLoader() throws Exception {
        if (loader instanceof URLClassLoader closable) closable.close();
    }

    private static void add(Path src, List<String> args, String rel, String body) throws Exception {
        Path f = src.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, body);
        args.add(f.toString());
    }

    private static String verdict(String name) {
        try {
            return (String) runCase.invoke(null, name);   // fixture exposes a static run(String)
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SETUP ERROR: fixture invocation failed", e);
        }
    }

    @Test void flatGroundDigOneIsAllowed() {
        assertTrue(verdict("flatDig1").startsWith("ALLOW"), verdict("flatDig1"));
    }

    @Test void exactlyThreeBlockDropIsAllowed() {
        assertTrue(verdict("drop3").startsWith("ALLOW"), verdict("drop3"));
    }

    @Test void threeAndAHalfBlockDropIsRefused() {
        String v = verdict("drop3half");
        assertTrue(v.startsWith("REFUSE"), v);
        assertTrue(v.contains("3.5") || v.contains("> 3"), v);
    }

    @Test void fourBlockDropIsRefused() {
        assertTrue(verdict("drop4").startsWith("REFUSE"), verdict("drop4"));
    }

    @Test void noFloorUnderTheBodyIsRefused() {
        String v = verdict("noFloor");
        assertTrue(v.startsWith("REFUSE") && v.contains("no floor"), v);
    }

    @Test void fallingBlockAboveIsRefusedEvenForSideDig() {
        String v = verdict("sandAbove");
        assertTrue(v.startsWith("REFUSE") && v.contains("falling block"), v);
    }

    @Test void liquidLandingIsRefused() {
        String v = verdict("liquid");
        assertTrue(v.startsWith("REFUSE") && v.contains("liquid"), v);
    }

    @Test void hazardLandingIsRefused() {
        String v = verdict("hazard");
        assertTrue(v.startsWith("REFUSE") && v.contains("hazard"), v);
    }

    @Test void pendingClearRemovesTheWouldBeFloor() {
        assertTrue(verdict("batchKept").startsWith("ALLOW"), verdict("batchKept"));
        String v = verdict("batchRemoved");
        assertTrue(v.startsWith("REFUSE"), v);
    }

    @Test void unloadedDropPathIsUnknownNotAir() {
        String v = verdict("unloaded");
        assertTrue(v.startsWith("UNKNOWN"), v);
    }

    @Test void sideDigOutsideFootprintIsAllowed() {
        assertTrue(verdict("sideDig").startsWith("ALLOW"), verdict("sideDig"));
    }

    @Test void airborneBodyDoesNotAutoAllow() {
        String v = verdict("airborne");
        assertTrue(v.startsWith("REFUSE") || v.startsWith("UNKNOWN"), v);
    }

    @Test void shapeOutsideFootprintCannotBeTheLanding() {
        String v = verdict("shapeOutside");
        assertTrue(v.startsWith("REFUSE") && v.contains("no floor"), v);
    }

    @Test void secondColumnKeepsTheBodyUp() {
        assertTrue(verdict("twoColumn").startsWith("ALLOW"), verdict("twoColumn"));
    }

    private static final String FIXTURE = """
            package com.dwinovo.numen.core.task.build;
            import com.dwinovo.numen.core.pathing.moves.ChunkLoadedTest;
            import com.dwinovo.numen.core.pathing.util.BlockHelper;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.block.FallingBlock;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.phys.AABB;
            import net.minecraft.world.phys.shapes.VoxelShape;
            import java.util.HashMap;
            import java.util.HashSet;
            import java.util.Map;
            import java.util.Set;
            import java.util.function.LongPredicate;

            public final class Fixture {
                private static final BlockGetter EMPTY_VIEW = pos -> BlockState.AIR;
                private final Map<Long, BlockState> world = new HashMap<>();
                private final Set<String> unloaded = new HashSet<>();
                private final BlockGetter view = pos -> world.getOrDefault(pos.asLong(), BlockState.AIR);

                public static String run(String name) {
                    return new Fixture().caseFor(name);
                }

                private String caseFor(String name) {
                    world.clear();
                    unloaded.clear();
                    AABB body;
                    BlockPos pos;
                    LongPredicate willClear = key -> false;
                    switch (name) {
                        case "flatDig1" -> {
                            body = new AABB(0.0,5.0,0.0,0.6,6.8,0.6);
                            solid(0,4,0); solid(0,3,0);
                            pos = new BlockPos(0,4,0);
                        }
                        case "drop3" -> {
                            body = new AABB(0.0,5.0,0.0,0.6,6.8,0.6);
                            solid(0,1,0);
                            pos = new BlockPos(0,4,0);
                        }
                        case "drop3half" -> {
                            // 站在下半砖上(脚底 y=5.5),拆掉这块砖后落 3.5 格
                            body = new AABB(0.0,5.5,0.0,0.6,7.3,0.6);
                            put(0,5,0, new BlockState(new net.minecraft.world.level.block.Block(), false, false,
                                    VoxelShape.of(new AABB(0,0,0,1,0.5,1))));
                            solid(0,1,0);
                            pos = new BlockPos(0,5,0);
                        }
                        case "drop4", "noFloor" -> {
                            body = new AABB(0.0,5.0,0.0,0.6,6.8,0.6);
                            pos = new BlockPos(0,4,0);
                        }
                        case "sandAbove" -> {
                            body = new AABB(0.0,5.0,0.0,0.6,6.8,0.6);
                            put(3,4,0, new BlockState(new FallingBlock(), false, false,
                                    VoxelShape.of(new AABB(0,0,0,1,1,1))));
                            pos = new BlockPos(3,3,0);
                        }
                        case "liquid" -> {
                            body = new AABB(0.0,5.0,0.0,0.6,6.8,0.6);
                            solid(0,4,0);
                            put(0,3,0, new BlockState(new net.minecraft.world.level.block.Block(), false, true,
                                    VoxelShape.EMPTY));
                            pos = new BlockPos(0,4,0);
                        }
                        case "hazard" -> {
                            body = new AABB(0.0,5.0,0.0,0.6,6.8,0.6);
                            solid(0,4,0);
                            put(0,3,0, new BlockState(BlockHelper.HAZARD, false, false,
                                    VoxelShape.of(new AABB(0,0,0,1,1,1))));
                            pos = new BlockPos(0,4,0);
                        }
                        case "batchKept", "batchRemoved" -> {
                            body = new AABB(0.0,5.0,0.0,0.6,6.8,0.6);
                            solid(0,4,0); solid(0,3,0); solid(0,2,0);
                            pos = new BlockPos(0,4,0);
                            if (name.endsWith("Removed")) {
                                long first = new BlockPos(0,4,0).asLong();
                                long second = new BlockPos(0,3,0).asLong();
                                long third = new BlockPos(0,2,0).asLong();
                                willClear = key -> key == second || key == third || key == first;
                            }
                        }
                        case "unloaded" -> {
                            body = new AABB(0.0,5.0,0.0,0.6,6.8,0.6);
                            solid(0,4,0); solid(0,3,0);
                            unloaded.add("0,5");
                            pos = new BlockPos(0,4,0);
                        }
                        case "sideDig" -> {
                            body = new AABB(0.0,5.0,0.0,0.6,6.8,0.6);
                            pos = new BlockPos(3,3,0);
                        }
                        case "airborne" -> {
                            // 身体悬在 y=6,脚下没有任何承重面:不能因“没贴着”自动放行
                            body = new AABB(0.0,6.0,0.0,0.6,7.8,0.6);
                            pos = new BlockPos(0,4,0);
                        }
                        case "shapeOutside" -> {
                            // 形状只有脚外那一截(x 0.7..1.0),不能当落脚面,也不能截断向下扫描
                            body = new AABB(0.0,5.0,0.0,0.6,6.8,0.6);
                            put(0,4,0, new BlockState(new net.minecraft.world.level.block.Block(), false, false,
                                    VoxelShape.of(new AABB(0.7,0,0,1.0,1,1))));
                            pos = new BlockPos(0,4,0);
                        }
                        case "twoColumn" -> {
                            // 0.6 宽身体跨 0/1 两列,拆掉一列后另一列仍托着
                            body = new AABB(0.7,5.0,0.0,1.3,6.8,0.6);
                            solid(0,4,0); solid(1,4,0);
                            pos = new BlockPos(0,4,0);
                        }
                        default -> throw new IllegalArgumentException("unknown case " + name);
                    }
                    ChunkLoadedTest loaded = (x, z) -> !unloaded.contains(x + "," + z);
                    BuildClearSafety.Verdict verdict =
                            BuildClearSafety.check(view, loaded, pos, body, willClear);
                    return verdict.admission().name() + "|" + verdict.reason();
                }

                private void put(int x, int y, int z, BlockState state) {
                    world.put(new BlockPos(x, y, z).asLong(), state);
                }

                private void solid(int x, int y, int z) {
                    put(x, y, z, new BlockState(new net.minecraft.world.level.block.Block(), false, false,
                            VoxelShape.of(new AABB(0,0,0,1,1,1))));
                }
            }
            """;
}
