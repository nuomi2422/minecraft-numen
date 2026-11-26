package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Source fixture: compiles the unmodified production method against fake world/MC boundaries.
 * This is not a real provider, Minecraft integration, or respawn lifecycle test. */
class RddBaseFactsInspectionTest {
    @TempDir static Path output;
    static URLClassLoader loader;
    static Class<?> host;

    @BeforeAll static void compileActualMethod() throws Exception {
        String source = Files.readString(RddBaseFactsConsumerTest.plugin().resolve("RddBaseFacts.java"));
        String actual = RddBaseFactsConsumerTest.method(source, "static BaseInspection inspectBaseResult(");
        Path file = output.resolve("BaseInspectionHost.java");
        Files.writeString(file, HEAD + actual + TAIL);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "SETUP ERROR: JDK required");
        assertEquals(0, compiler.run(null, null, null, "-encoding", "UTF-8", "-proc:none",
                "-d", output.toString(), file.toString()), "SETUP ERROR: production method must compile unchanged");
        loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, getClassLoader());
        host = loader.loadClass("BaseInspectionHost");
        assertEquals(output.toUri().toURL(), host.getProtectionDomain().getCodeSource().getLocation());
    }
    static ClassLoader getClassLoader() { return RddBaseFactsInspectionTest.class.getClassLoader(); }
    @AfterAll static void close() throws Exception { if (loader != null) loader.close(); }
    @SuppressWarnings("unchecked") static Map<String,Object> run(String scenario) throws Exception {
        return (Map<String,Object>) host.getMethod("run", String.class).invoke(null, scenario);
    }
    @Test void unboundSpawnIsUnknownWithoutWorldReads() throws Exception {
        for (String scenario : new String[]{"null-spawn", "zero-spawn"}) {
            var r=run(scenario); assertEquals("UNKNOWN",r.get("status"));
            assertEquals(false,r.get("snapshot")); assertEquals(0,r.get("reads")); assertEquals(0,r.get("chunkChecks"));
        }
    }
    @Test void unavailableOrUnsuitableDimensionIsUnknown() throws Exception {
        for (String scenario : new String[]{"no-level", "wrong-dimension", "no-spawn"}) {
            var r=run(scenario); assertEquals("UNKNOWN",r.get("status"));
            assertEquals(false,r.get("snapshot")); assertEquals(0,r.get("reads")); assertEquals(0,r.get("chunkChecks"));
        }
    }
    @Test void anyUnloadedChunkIsUnknownBeforeBlockReads() throws Exception {
        var r=run("unloaded"); assertEquals("UNKNOWN",r.get("status"));
        assertEquals(false,r.get("snapshot")); assertEquals(0,r.get("reads")); assertEquals(4,r.get("chunkChecks"));
    }
    @Test void fullyLoadedNonBedIsAbsent() throws Exception {
        var r=run("non-bed"); assertEquals("ABSENT",r.get("status"));
        assertEquals(false,r.get("snapshot")); assertEquals(4,r.get("chunkChecks")); assertEquals(1,r.get("reads"));
    }
    @Test void incompleteOrInconsistentBedIsUnknown() throws Exception {
        for (String scenario : new String[]{"half-bed", "same-part", "wrong-facing", "different-bed"}) {
            var r=run(scenario); assertEquals("UNKNOWN",r.get("status"),scenario);
            assertEquals(false,r.get("snapshot")); assertEquals(2,r.get("reads"));
        }
    }
    @Test void completeBedIsObservedFromEitherHalf() throws Exception {
        for (String scenario : new String[]{"complete-head", "complete-foot"}) {
            var r=run(scenario); assertEquals("OBSERVED",r.get("status"),scenario);
            assertEquals(true,r.get("snapshot")); assertEquals(true,r.get("complete"));
            assertEquals("overworld",r.get("dimension")); assertEquals("16,64,16",r.get("spawn"));
            assertEquals(4,r.get("chunkChecks")); assertTrue((int)r.get("reads") > 2);
        }
    }
    private static final String HEAD = """
            import java.util.*;
            public class BaseInspectionHost {
            enum BaseStatus { OBSERVED, UNKNOWN, ABSENT }
            record BaseInspection(BaseStatus status, BaseSnapshot snapshot, String dimension, BlockPos spawn) {}
            record BaseSnapshot(String dimension, BlockPos spawn, boolean complete, List<String> facilities, Map<String,Integer> storedItems) {}
            """;
    private static final String TAIL = """
            static class Log { void info(String s,Object... args) {} }
            static final Log BASELOG = new Log();
            static boolean dimension(ServerLevel l,Map<String,Object> condition){return l.dimensionOk;}
            record Dim(String value) { String location(){return value;} }
            enum Direction { NORTH, SOUTH; Direction getOpposite(){return this==NORTH?SOUTH:NORTH;} }
            enum BedPart { HEAD, FOOT }
            record BlockPos(int x,int y,int z) {
                static final BlockPos ZERO=new BlockPos(0,0,0);
                int getX(){return x;} int getY(){return y;} int getZ(){return z;}
                BlockPos relative(Direction d){return offset(0,0,d==Direction.NORTH?-1:1);}
                BlockPos offset(int dx,int dy,int dz){return new BlockPos(x+dx,y+dy,z+dz);}
                BlockPos immutable(){return this;}
                static Iterable<BlockPos> betweenClosed(BlockPos a,BlockPos b){
                    List<BlockPos> all=new ArrayList<>();
                    for(int x=a.x;x<=b.x;x++) for(int y=a.y;y<=b.y;y++) for(int z=a.z;z<=b.z;z++) all.add(new BlockPos(x,y,z));
                    return all;
                }
                public String toString(){return x+","+y+","+z;}
            }
            static class Block {}
            static class Property<T> {}
            static class BedBlock extends Block {
                static final Property<Direction> FACING=new Property<>(); static final Property<BedPart> PART=new Property<>();
                static boolean canSetSpawn(ServerLevel l){return l.spawnOk;}
            }
            static class Blocks { static final Block CHEST=new Block(),TRAPPED_CHEST=new Block(),FURNACE=new Block(); }
            static class State {
                Block block; BedPart part; Direction facing;
                State(Block b,BedPart p,Direction d){block=b;part=p;facing=d;}
                Block getBlock(){return block;} boolean is(Block b){return block==b;}
                @SuppressWarnings("unchecked") <T> T getValue(Property<T> p){return (T)(p==BedBlock.FACING?facing:part);}
            }
            static class ItemStack { boolean isEmpty(){return true;} Object getItem(){return null;} int getCount(){return 0;} }
            interface Container { int getContainerSize(); ItemStack getItem(int slot); }
            static class Registry { String getKey(Object o){return "fixture:block";} }
            static class BuiltInRegistries { static final Registry BLOCK=new Registry(), ITEM=new Registry(); }
            static class FunctionalBlockTypes { static boolean isTracked(String s){return false;} }
            static class ChunkSource {
                int checks; boolean unloaded;
                Object getChunkNow(int x,int z){checks++;return unloaded && x==1 && z==1 ? null : this;}
            }
            static class ServerLevel {
                boolean dimensionOk=true,spawnOk=true; int reads; ChunkSource chunks=new ChunkSource();
                Map<BlockPos,State> states=new HashMap<>(); State air=new State(new Block(),null,null);
                Dim dimension(){return new Dim("overworld");} ChunkSource getChunkSource(){return chunks;}
                State getBlockState(BlockPos p){reads++;return states.getOrDefault(p,air);}
                Object getBlockEntity(BlockPos p){return null;}
            }
            static class Server { ServerLevel target; ServerLevel getLevel(Dim d){return target;} }
            static class NumenPlayer {
                BlockPos spawn=new BlockPos(16,64,16); ServerLevel current=new ServerLevel(); Server server=new Server();
                BlockPos getRespawnPosition(){return spawn;} Dim getRespawnDimension(){return new Dim("overworld");}
                ServerLevel level(){return current;} Server getServer(){return server;}
            }
            public static Map<String,Object> run(String scenario) {
                NumenPlayer ap=new NumenPlayer(); ServerLevel level=ap.current; ap.server.target=level;
                BlockPos spawn=ap.spawn; BedBlock bed=new BedBlock(); Direction facing=Direction.NORTH;
                switch(scenario) {
                    case "null-spawn" -> ap.spawn=null;
                    case "zero-spawn" -> ap.spawn=BlockPos.ZERO;
                    case "no-level" -> ap.server.target=null;
                    case "wrong-dimension" -> level.dimensionOk=false;
                    case "no-spawn" -> level.spawnOk=false;
                    case "unloaded" -> level.chunks.unloaded=true;
                    case "non-bed" -> {}
                    default -> {
                        boolean foot=scenario.equals("complete-foot"); BedPart part=foot?BedPart.FOOT:BedPart.HEAD;
                        level.states.put(spawn,new State(bed,part,facing));
                        BlockPos other=spawn.relative(foot?facing:facing.getOpposite());
                        if(!scenario.equals("half-bed")) level.states.put(other,new State(
                            scenario.equals("different-bed")?new BedBlock():bed,
                            scenario.equals("same-part")?part:(foot?BedPart.HEAD:BedPart.FOOT),
                            scenario.equals("wrong-facing")?Direction.SOUTH:facing));
                    }
                }
                BaseInspection r=inspectBaseResult(ap,Map.of());
                Map<String,Object> result=new HashMap<>(); result.put("status",r.status().name());
                result.put("snapshot",r.snapshot()!=null); result.put("reads",level.reads); result.put("chunkChecks",level.chunks.checks);
                result.put("dimension",r.dimension()); result.put("spawn",String.valueOf(r.spawn()));
                if(r.snapshot()!=null)result.put("complete",r.snapshot().complete());
                return result;
            }
            }
            """;
}
