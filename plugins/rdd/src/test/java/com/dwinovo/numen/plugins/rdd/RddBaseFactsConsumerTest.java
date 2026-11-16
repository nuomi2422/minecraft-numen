package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.WorldFactConditions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Actual D-group sampling bodies with a narrow in-memory world boundary, not Minecraft.
 * Compiles the current methods, never a hand-copied sampler. Fixture geometry/blocks/containers
 * do not prove native registries, respawn lifecycle or the full inspectBase observation path.
 * Compiler/classpath errors are SETUP ERROR, not a business red.
 */
class RddBaseFactsConsumerTest {
    @TempDir static Path output;
    static URLClassLoader loader;
    static Class<?> host;

    @BeforeAll static void compileActualSampling() throws Exception {
        String source = Files.readString(plugin().resolve("RddBaseFacts.java"));
        StringBuilder actual = new StringBuilder();
        for (String signature : List.of("private static BlockPos bedAnchor(", "private static int baseRadius(",
                "private static void scanAround(", "static boolean shelterAround(", "private static boolean isWallLike(",
                "static boolean stockedAround(", "static boolean litAround(")) {
            actual.append(method(source, signature)).append('\n');
        }
        // Only qualify the fixture DoorBlock differently; no predicate or body rewrite.
        String generated = HEAD + actual.toString().replace("net.minecraft.world.level.block.DoorBlock", "DoorBlock") + TAIL;
        Path file = output.resolve("BaseSamplerHost.java");
        Files.writeString(file, generated);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "SETUP ERROR: JDK required");
        String core = Path.of(WorldFactConditions.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        assertEquals(0, compiler.run(null, null, null, "-encoding", "UTF-8", "-proc:none", "-classpath",
                core + File.pathSeparator + System.getProperty("java.class.path"), "-d", output.toString(), file.toString()),
                "SETUP ERROR: current production sampler must compile");
        loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, RddBaseFactsConsumerTest.class.getClassLoader());
        host = loader.loadClass("BaseSamplerHost");
        assertEquals(output.toUri().toURL(), host.getProtectionDomain().getCodeSource().getLocation());
    }

    @AfterAll static void close() throws Exception { if (loader != null) loader.close(); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> run(String scenario) throws Exception {
        return (Map<String, Object>) host.getMethod("run", String.class).invoke(null, scenario);
    }

    @Test void circularScanKeepsDxDzDyOrderAndInclusiveHeight() throws Exception {
        assertEquals(List.of("9,20,30", "9,21,30", "10,20,29", "10,21,29", "10,20,30", "10,21,30",
                "10,20,31", "10,21,31", "11,20,30", "11,21,30"), run("order").get("visited"));
    }
    @Test void unloadedPointsAreSkippedBeforeCallback() throws Exception {
        assertEquals(List.of("9,20,30", "10,20,30", "10,20,31", "11,20,30"), run("unloaded").get("visited"));
    }
    @Test void trueCallbackStopsWithoutReadingTheTail() throws Exception {
        assertEquals(List.of("9,20,30", "10,20,29"), run("early").get("visited"));
        assertEquals(2, run("early").get("checks"));
    }
    @Test void callbackFailureIsNotSilentlyConvertedIntoAWorldFact() throws Exception {
        assertEquals("fixture-read-failed", run("exception").get("error"));
    }
    @Test void absentBedUsesSelfOnlyForShelterAndDoesNotInspectStockOrLight() throws Exception {
        var result = run("no-bed");
        assertEquals(true, result.get("shelter"));
        assertEquals(false, result.get("stocked"));
        assertEquals(false, result.get("lit"));
        assertEquals(true, result.get("unchangedReads"));
    }
    @Test void stockedConsumesAllSlotsOfCurrentContainerThenStopsScanning() throws Exception {
        var result = run("stocked");
        assertEquals(true, result.get("matched"));
        assertEquals(2, result.get("slots"));
        assertEquals(1, result.get("containerReads"));
    }
    @Test void lightPredicateRetainsAllEightExplicitKinds() throws Exception {
        assertEquals(8, run("lights").get("matched"));
        assertEquals(false, run("dark").get("matched"));
        assertEquals(false, run("insufficient-light").get("matched"));
    }
    @Test void worldFacadeAndAssetObserverStillUseOneSnapshotEntry() throws Exception {
        String world = Files.readString(plugin().resolve("RddWorldFacts.java"));
        String owner = Files.readString(plugin().resolve("RddBaseFacts.java"));
        String observer = Files.readString(plugin().resolve("RddWorldAssetObserver.java"));
        assertTrue(method(world, "static BaseSnapshot inspectBase(").contains("return RddBaseFacts.inspectBase(ap, condition);"));
        assertTrue(observer.contains("RddWorldFacts.inspectBase(companion, Map.of())"));
        assertTrue(world.contains("record BaseSnapshot(String dimension, BlockPos spawn, boolean complete,"));
        for (String name : List.of("shelterAround", "stockedAround", "litAround")) {
            assertTrue(method(world, "private static boolean " + name + "(").contains("return RddBaseFacts." + name + "(ap, condition);"));
        }
        String inspect = method(owner, "static BaseSnapshot inspectBase(");
        int dimension = inspect.indexOf("getLevel(ap.getRespawnDimension())");
        int preflight = inspect.indexOf("getChunkNow(x, z)");
        int bed = inspect.indexOf("getBlockState(spawn)");
        assertTrue(dimension >= 0 && preflight > dimension && bed > preflight);
        String executable = inspect.lines().filter(line -> !line.stripLeading().startsWith("//"))
                .collect(java.util.stream.Collectors.joining("\n"));
        assertFalse(executable.contains("findStandUpPosition("), "no restored stand-up gate");
    }

    static Path plugin() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null) {
            Path candidate = root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd");
            if (Files.isRegularFile(candidate.resolve("RddWorldFacts.java"))) return candidate;
            root = root.getParent();
        }
        throw new AssertionError("SETUP ERROR: production source root missing");
    }
    static String method(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) throw new AssertionError("SETUP ERROR: missing " + signature);
        int brace = source.indexOf('{', start), depth = 0;
        for (int i = brace; i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            if (source.charAt(i) == '}' && --depth == 0) return source.substring(start, i + 1);
        }
        throw new AssertionError("SETUP ERROR: unclosed " + signature);
    }

    private static final String HEAD = """
            import java.util.*;
            import java.util.function.Predicate;
            import com.dwinovo.numen.rdd.core.WorldFactConditions;
            public class BaseSamplerHost {
            """;
    private static final String TAIL = """
            static class Block {}
            static class BedBlock extends Block {}
            static class DoorBlock extends Block {}
            static class Blocks {
                static final Block CHEST=new Block(), TRAPPED_CHEST=new Block(), FURNACE=new Block(), CRAFTING_TABLE=new Block();
                static final Block TORCH=new Block(), WALL_TORCH=new Block(), LANTERN=new Block(), SOUL_LANTERN=new Block();
                static final Block GLOWSTONE=new Block(), SEA_LANTERN=new Block(), JACK_O_LANTERN=new Block(), SHROOMLIGHT=new Block();
            }
            static class BlockState {
                final Block block; final boolean solid;
                BlockState(Block b,boolean s){block=b;solid=s;}
                Block getBlock(){return block;} boolean is(Block b){return block==b;} boolean isSolid(){return solid;}
            }
            static class BlockPos {
                int x,y,z; static final BlockPos ZERO=new BlockPos(0,0,0);
                BlockPos(int x,int y,int z){this.x=x;this.y=y;this.z=z;}
                int getX(){return x;} int getY(){return y;} int getZ(){return z;}
                BlockPos immutable(){return new BlockPos(x,y,z);} BlockPos above(int dy){return new BlockPos(x,y+dy,z);}
                public boolean equals(Object o){return o instanceof BlockPos p && x==p.x&&y==p.y&&z==p.z;}
                public int hashCode(){return Objects.hash(x,y,z);} public String toString(){return x+","+y+","+z;}
                static class MutableBlockPos extends BlockPos { MutableBlockPos(){super(0,0,0);} void set(int x,int y,int z){this.x=x;this.y=y;this.z=z;} }
            }
            static class ItemStack { int count; ItemStack(int n){count=n;} boolean isEmpty(){return count==0;} int getCount(){return count;} }
            interface Container { int getContainerSize(); ItemStack getItem(int slot); }
            static class ServerLevel {
                int checks, reads, slots, containerReads; Set<String> unloaded=new HashSet<>();
                BlockState state=new BlockState(new Block(),true); Container container;
                boolean hasChunkAt(BlockPos p){checks++;return !unloaded.contains(p.toString());}
                BlockState getBlockState(BlockPos p){reads++;return state;}
                Object getBlockEntity(BlockPos p){containerReads++;return container;}
            }
            static class NumenPlayer {
                final ServerLevel level; BlockPos spawn; final BlockPos self=new BlockPos(10,20,30);
                NumenPlayer(ServerLevel l){level=l;} BlockPos getRespawnPosition(){return spawn;}
                Object level(){return level;} ServerLevel serverLevel(){return level;} BlockPos blockPosition(){return self;}
            }
            public static Map<String,Object> run(String scenario) {
                ServerLevel level=new ServerLevel(); NumenPlayer ap=new NumenPlayer(level);
                List<String> visited=new ArrayList<>();
                switch(scenario) {
                    case "order" -> scanAround(level,ap.self,1,0,1,p->{visited.add(p.toString());return false;});
                    case "unloaded" -> {level.unloaded.add("10,20,29");scanAround(level,ap.self,1,0,0,p->{visited.add(p.toString());return false;});}
                    case "early" -> scanAround(level,ap.self,1,0,0,p->{visited.add(p.toString());return visited.size()==2;});
                    case "exception" -> {try {scanAround(level,ap.self,1,0,0,p->{throw new IllegalStateException("fixture-read-failed");});}
                        catch(IllegalStateException e){return Map.of("error",e.getMessage());}}
                    case "no-bed" -> {
                        boolean shelter=shelterAround(ap,Map.of("radius",1,"minimum",1));int reads=level.reads;
                        boolean stocked=stockedAround(ap,Map.of()),lit=litAround(ap,Map.of());
                        return Map.of("shelter",shelter,"stocked",stocked,"lit",lit,"unchangedReads",reads==level.reads);
                    }
                    case "stocked" -> {
                        ap.spawn=ap.self;level.state=new BlockState(new BedBlock(),false);
                        level.container=new Container(){public int getContainerSize(){return 2;} public ItemStack getItem(int slot){level.slots++;return new ItemStack(slot == 0 ? 10 : 6);}};
                        boolean matched=stockedAround(ap,Map.of("radius",1,"minimum",10));
                        return Map.of("matched",matched,"slots",level.slots,"containerReads",level.containerReads);
                    }
                    case "dark", "insufficient-light" -> {
                        ServerLevel limited=new ServerLevel(){BlockState getBlockState(BlockPos p){
                            reads++; if(reads==1)return new BlockState(new BedBlock(),false);
                            return new BlockState(scenario.equals("insufficient-light") && reads==2 ? Blocks.TORCH : new Block(),false);
                        }};
                        NumenPlayer limitedAp=new NumenPlayer(limited);limitedAp.spawn=ap.self;
                        return Map.of("matched",litAround(limitedAp,Map.of("radius",1,"minimum",2)));
                    }
                    case "lights" -> {
                        int matched=0;ap.spawn=ap.self;
                        for(Block light:List.of(Blocks.TORCH,Blocks.WALL_TORCH,Blocks.LANTERN,Blocks.SOUL_LANTERN,Blocks.GLOWSTONE,Blocks.SEA_LANTERN,Blocks.JACK_O_LANTERN,Blocks.SHROOMLIGHT)) {
                            ServerLevel lightLevel=new ServerLevel(){BlockState getBlockState(BlockPos p){reads++;return reads==1?new BlockState(new BedBlock(),false):new BlockState(light,false);}};
                            NumenPlayer lightAp=new NumenPlayer(lightLevel);lightAp.spawn=ap.self;
                            if(litAround(lightAp,Map.of("radius",1,"minimum",1)))matched++;
                        }
                        return Map.of("matched",matched);
                    }
                }
                return Map.of("visited",visited,"checks",level.checks);
            }
            }
            """;
}
