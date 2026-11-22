package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.CleanupMode;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Extracted production refresh, scan, snapshot and future-needs methods, not a Minecraft test.
 * Server, clock, world blocks, observation reads and event transport are controlled boundaries.
 * In particular readContainer is a double: observation metadata validation itself is not tested.
 */
class RddFutureNeedFreshnessSourceTest {
    @TempDir(cleanup = CleanupMode.NEVER) Path temp;
    Class<?> fixture;

    @BeforeEach void compile() throws Exception {
        Path root = root();
        Path plugin = root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd");
        String memory = Files.readString(plugin.resolve("RddContainerMemory.java"));
        String future = Files.readString(plugin.resolve("RddFutureNeeds.java"));
        List<Path> sources = new ArrayList<>();
        source(sources, "com/dwinovo/numen/rdd/api/AssetRequirement.java", "package com.dwinovo.numen.rdd.api; public record AssetRequirement(String assetKey,int minimum) {} ");
        source(sources, "com/dwinovo/numen/rdd/api/PrimaryGoal.java", "package com.dwinovo.numen.rdd.api; import java.util.*; public record PrimaryGoal(String id,String description,List<AssetRequirement> waitFor) {} ");
        source(sources, "com/dwinovo/numen/rdd/core/TaskChain.java", "package com.dwinovo.numen.rdd.core; import java.util.*; import com.dwinovo.numen.rdd.api.*; public record TaskChain(Goal goal,PrimaryGoal currentPrimary) { public record Goal(List<PrimaryGoal> primaryGoals) {} } ");
        source(sources, "com/dwinovo/numen/rdd/policy/FutureNeedScanner.java", Files.readString(root.resolve("rdd-core/src/main/java/com/dwinovo/numen/rdd/policy/FutureNeedScanner.java")));
        source(sources, "com/dwinovo/numen/api/WorldContextStamp.java", """
            package com.dwinovo.numen.api;
            import java.util.*;
            public class WorldContextStamp {
                public record Token(Map<String,String> identity,long epoch) {}
                public static Token current;
                public static Token capture() { return current; }
                public static Token capture(Object server) { return current; }
            }
            """);
        source(sources, "net/minecraft/core/BlockPos.java", """
            package net.minecraft.core;
            public class BlockPos {
                protected int x,y,z;
                public int getX(){return x;} public int getY(){return y;} public int getZ(){return z;}
                public BlockPos immutable(){ BlockPos p=new BlockPos(); p.x=x;p.y=y;p.z=z;return p; }
                public static class MutableBlockPos extends BlockPos { public void set(int a,int b,int c){x=a;y=b;z=c;} }
            }
            """);
        source(sources, "net/minecraft/world/level/block/state/BlockState.java", """
            package net.minecraft.world.level.block.state;
            public record BlockState(boolean isAir) { public Block getBlock(){return new Block();} public static class Block {public String getDescriptionId(){return "chest";}} }
            """);
        source(sources, "net/minecraft/server/level/ServerLevel.java", """
            package net.minecraft.server.level;
            import net.minecraft.core.BlockPos;
            public class ServerLevel {
                public Object server=new Object(); public String dim="overworld";
                public Object getServer(){return server;}
                public Dim dimension(){return new Dim(dim);} public record Dim(String location) {}
                public boolean hasChunkAt(BlockPos p){return true;}
                public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos p){return new net.minecraft.world.level.block.state.BlockState(p.getY()!=0 || p.getZ()!=0 || p.getX()<0 || p.getX()>1);}
            }
            """);
        source(sources, "com/dwinovo/numen/entity/NumenPlayer.java", """
            package com.dwinovo.numen.entity;
            import java.util.*; import net.minecraft.core.BlockPos; import net.minecraft.server.level.ServerLevel;
            public class NumenPlayer {
                public UUID id=UUID.randomUUID(); public ServerLevel level=new ServerLevel();
                public UUID getUUID(){return id;} public ServerLevel serverLevel(){return level;}
                public BlockPos blockPosition(){return new BlockPos();}
            }
            """);
        StringBuilder m = new StringBuilder("""
            package com.dwinovo.numen.plugins.rdd;
            import java.util.*; import java.util.concurrent.*;
            import net.minecraft.core.BlockPos; import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.level.block.state.BlockState;
            import com.dwinovo.numen.entity.NumenPlayer;
            final class RddContainerMemory {
                static final int RADIUS=8, MAX_CONTAINERS=3, MAX_TYPES=10;
                static final long REFRESH_TICKS=40L, STALE_AFTER_MS=1000L;
                static final Map<UUID,Published> PUBLISHED=new ConcurrentHashMap<>();
                static final java.util.concurrent.atomic.AtomicLong NEARBY_GENERATION=new java.util.concurrent.atomic.AtomicLong();
                static final Map<UUID,Long> LAST_REFRESH=new ConcurrentHashMap<>();
                static final Map<UUID,Object> POLLERS=new ConcurrentHashMap<>();
                static final Map<UUID,String> CHANGE_EPOCH=new ConcurrentHashMap<>();
                static final Logger LOG=new Logger();
                static class Logger { void warn(String s,Object... x){} void info(String s,Object... x){} }
            """);
        for (String declaration : List.of("private record Context(", "static record NearbySnapshot(", "private record Published(", "private record Scan(", "private record ContainerRead(")) m.append(method(memory, declaration).replace("private record ContainerRead(", "static record ContainerRead("));
        for (String sig : List.of("private static boolean knownIdentity(", "private static boolean sameContext(", "static synchronized void refresh(", "static NearbySnapshot cachedSnapshot(", "static boolean isCurrent(", "private static boolean sameEvidence(", "static synchronized void clear(", "private static Scan scan(NumenPlayer body")) m.append(method(memory, sig));
        m.append("""
                static final Map<Integer,ContainerRead> reads=new HashMap<>();
                static boolean pollContainerChanges(UUID id){return false;}
                static ContainerRead readContainer(NumenPlayer b,ServerLevel l,BlockPos p,long t){return reads.get(p.getX());}
            }
            """);
        source(sources, "com/dwinovo/numen/plugins/rdd/RddContainerMemory.java", m.toString());
        StringBuilder f = new StringBuilder("""
            package com.dwinovo.numen.plugins.rdd;
            import java.util.*; import java.util.concurrent.*;
            import com.dwinovo.numen.rdd.api.PrimaryGoal;
            import com.dwinovo.numen.rdd.core.TaskChain;
            import com.dwinovo.numen.rdd.policy.FutureNeedScanner;
            final class RddFutureNeeds {
            """);
        f.append(method(future, "private record ScanResult("));
        f.append("static final Map<UUID,ScanResult> BLOCK=new ConcurrentHashMap<>(); static final Map<UUID,String> SIGNATURE=new ConcurrentHashMap<>();\n");
        for (String sig : List.of("static void tick(", "static String block(", "static List<PrimaryGoal> futurePrimaries(", "private static String signature(", "private static String render(")) f.append(method(future, sig));
        f.append("static void publish(UUID id,List<FutureNeedScanner.Sighting> hits) {}\n}\n");
        source(sources, "com/dwinovo/numen/plugins/rdd/RddFutureNeeds.java", f.toString());
        source(sources, "com/dwinovo/numen/plugins/rdd/NeedFixture.java", FIXTURE);
        var compiler=ToolProvider.getSystemJavaCompiler(); assertNotNull(compiler,"JDK required");
        List<String> args=new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-d",temp.toString()));
        sources.forEach(p -> args.add(p.toString()));
        assertEquals(0,compiler.run(null,null,null,args.toArray(String[]::new)),"extracted production source must compile");
        fixture=new URLClassLoader(new URL[]{temp.toUri().toURL()},ClassLoader.getPlatformClassLoader()).loadClass("com.dwinovo.numen.plugins.rdd.NeedFixture");
    }
    String scenario(String kind) throws Exception {return (String)fixture.getMethod("run",String.class).invoke(null,kind);}
    void gone(String kind) throws Exception {
        String text=scenario(kind);
        assertFalse(text.contains("minecraft:obsidian"),text);
        assertTrue(text.contains("minecraft:bread ×2/3（还差）［背包］"),text);
        assertFalse(text.contains("minecraft:diamond"),text);
    }
    @Test void nearDeadlineRescanDoesNotExtendObservation() throws Exception {gone("near");}
    @Test void multipleSourcesUseEarliestDeadline() throws Exception {gone("multiple");}
    @Test void worldSwitchInvalidates() throws Exception {gone("world");}
    @Test void sessionSwitchInvalidates() throws Exception {gone("session");}
    @Test void epochSwitchInvalidates() throws Exception {gone("epoch");}
    @Test void dimensionSwitchInvalidatesAfterRefresh() throws Exception {gone("dimension");}
    @Test void alreadyGeneratedBlockExpiresWithoutAnotherTick() throws Exception {gone("block");}
    @Test void equivalentRescanKeepsNearbyBeforeExpiry() throws Exception {
        assertTrue(scenario("equivalent").contains("minecraft:obsidian ×10/10（够）［附近容器］"));
    }
    @Test void changedRescanCannotReviveOldCount() throws Exception { gone("changed"); }
    @Test void equalRescanCannotExtendOriginalDeadline() throws Exception { gone("equal-expired"); }
    @Test void changedThenRestoredCannotReviveOldHint() throws Exception { gone("aba"); }
    @Test void clearedThenRestoredCannotReviveOldHint() throws Exception { gone("clear-aba"); }
    @Test void dimensionChangedRescanCannotReviveOldNearby() throws Exception { gone("moved"); }
    @Test void realInventoryInputAndFreshNearbyAreReported() throws Exception {
        String text=scenario("fresh");
        assertTrue(text.contains("minecraft:obsidian ×10/10（够）［附近容器］"),text);
        assertTrue(text.contains("minecraft:bread ×2/3（还差）［背包］"),text);
        assertFalse(text.contains("minecraft:diamond"),text);
    }
    @Test void freshSamplingRestoresNearbyAfterExpiry() throws Exception {
        String text=scenario("recover");
        assertTrue(text.contains("minecraft:obsidian ×10/10（够）［附近容器］"),text);
        assertTrue(text.contains("minecraft:bread ×2/3（还差）［背包］"),text);
    }
    void source(List<Path> files,String name,String text) throws Exception {Path p=temp.resolve("sources").resolve(name);Files.createDirectories(p.getParent());Files.writeString(p,text);files.add(p);}
    static Path root(){for(Path p=Path.of("").toAbsolutePath();p!=null;p=p.getParent()) if(Files.isRegularFile(p.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddFutureNeeds.java"))) return p;throw new AssertionError("checkout root missing");}
    static String method(String source,String signature){int start=source.indexOf(signature);assertTrue(start>=0,"missing "+signature);int open=source.indexOf('{',start), depth=0; boolean string=false, escape=false; for(int i=open;i<source.length();i++){char c=source.charAt(i); if(string){if(escape) escape=false; else if(c=='\\') escape=true; else if(c=='"') string=false; continue;} if(c=='"'){string=true;continue;} if(c=='{') depth++; else if(c=='}' && --depth==0) return source.substring(start,i+1)+"\n"; } throw new AssertionError("unterminated "+signature);}

    static final String FIXTURE="""
        package com.dwinovo.numen.plugins.rdd;
        import java.util.*; import com.dwinovo.numen.rdd.api.*; import com.dwinovo.numen.rdd.core.TaskChain;
        import com.dwinovo.numen.entity.NumenPlayer; import com.dwinovo.numen.api.WorldContextStamp;
        class System { static long now=10000; static long currentTimeMillis(){return now;} }
        public class NeedFixture {
            static WorldContextStamp.Token token(String world,String session,long epoch){return new WorldContextStamp.Token(Map.of("world_id",world,"server_session",session),epoch);}
            public static String run(String kind){
                System.now=10000; WorldContextStamp.current=token("A","S",1);
                var body=new NumenPlayer(); var id=body.id;
                var current=new PrimaryGoal("p1","now",List.of());
                var next=new PrimaryGoal("p2","later",List.of(new AssetRequirement("minecraft:obsidian",10),new AssetRequirement("minecraft:bread",3),new AssetRequirement("minecraft:diamond",1)));
                var chain=new TaskChain(new TaskChain.Goal(List.of(current,next)),current);
                RddContainerMemory.reads.put(0,new RddContainerMemory.ContainerRead(Map.of("minecraft:obsidian",10),false,10100));
                RddContainerMemory.reads.put(1,new RddContainerMemory.ContainerRead(kind.equals("multiple") ? Map.of("minecraft:stone",1) : Map.of(),false,10500));
                RddContainerMemory.refresh(body,0); RddFutureNeeds.tick(id,chain,Map.of("minecraft:bread",2));
                if(!RddFutureNeeds.block(id).contains("minecraft:obsidian")) throw new AssertionError("fresh setup failed");
                if(kind.equals("fresh")) return RddFutureNeeds.block(id);
                if(kind.equals("aba") || kind.equals("clear-aba")) {
                    System.now=10020;
                    if(kind.equals("clear-aba")) RddContainerMemory.clear(id);
                    else { RddContainerMemory.reads.put(0,new RddContainerMemory.ContainerRead(Map.of("minecraft:obsidian",1),false,10100)); RddContainerMemory.refresh(body,40); }
                    System.now=10040; RddContainerMemory.reads.put(0,new RddContainerMemory.ContainerRead(Map.of("minecraft:obsidian",10),false,10100));
                    RddContainerMemory.refresh(body,80); return RddFutureNeeds.block(id);
                }
                if(kind.equals("equal-expired")) {
                    System.now=10050; RddContainerMemory.reads.put(0,new RddContainerMemory.ContainerRead(Map.of("minecraft:obsidian",10),false,11000));
                    RddContainerMemory.refresh(body,40); System.now=10099;
                    if(!RddFutureNeeds.block(id).contains("minecraft:obsidian")) throw new AssertionError("equal rescan hid before original expiry");
                    System.now=10100; return RddFutureNeeds.block(id);
                }
                if(kind.equals("equivalent") || kind.equals("changed")) {
                    System.now=10050;
                    if(kind.equals("changed")) RddContainerMemory.reads.put(0,new RddContainerMemory.ContainerRead(Map.of("minecraft:obsidian",1),false,10100));
                    RddContainerMemory.refresh(body,40); return RddFutureNeeds.block(id);
                }
                if(kind.equals("moved")) { body.level.dim="nether"; RddContainerMemory.refresh(body,40); return RddFutureNeeds.block(id); }
                if(kind.equals("world")) WorldContextStamp.current=token("B","S",1);
                if(kind.equals("session")) WorldContextStamp.current=token("A","T",1);
                if(kind.equals("epoch")) WorldContextStamp.current=token("A","S",2);
                if(kind.equals("dimension")) {body.level.dim="nether";RddContainerMemory.reads.clear();RddContainerMemory.refresh(body,40);}
                if(kind.equals("near")) {System.now=10099; RddContainerMemory.refresh(body,40); RddFutureNeeds.tick(id,chain,Map.of("minecraft:bread",2)); if(!RddFutureNeeds.block(id).contains("minecraft:obsidian")) throw new AssertionError("rescan setup failed");}
                if(kind.equals("near") || kind.equals("multiple") || kind.equals("block") || kind.equals("recover")) System.now=10100;
                if(kind.equals("recover")) {
                    if(RddFutureNeeds.block(id).contains("minecraft:obsidian")) throw new AssertionError("expired block survived");
                    RddContainerMemory.reads.put(0,new RddContainerMemory.ContainerRead(Map.of("minecraft:obsidian",10),false,11000));
                    RddContainerMemory.refresh(body,80); RddFutureNeeds.tick(id,chain,Map.of("minecraft:bread",2));
                }
                return RddFutureNeeds.block(id);
            }
        }
        """;
}
