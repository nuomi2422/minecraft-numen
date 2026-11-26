package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.api.observation.WorldObservationService;
import com.dwinovo.numen.worldstate.core.provider.WorldProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** One runtime: production ContainerWorldProvider -> real core store/query/refresh -> complete
 * ObservationCapability/ObservationResponses -> complete RddContainerMemory.scan -> published counts.
 * Reuses the provider fixture's MC ports; block-only, no Minecraft runtime or deployment claim.
 * Explicit boundary: WorldStateService is a minimal core adapter, NOT the production host constructor,
 * thread lifecycle, identity provider or persistence. The full host unconditionally wires four other
 * providers, SELF/body sampling and focus machinery; none is necessary for this container chain.
 * No capability, mapper, memory read/scan method or acceptance gate is replaced.
 */
class ContainerCapabilityMemoryConsumerTest {
    private static final String WS = "com.dwinovo.numen.plugins.worldstate.";
    private static final String RDD = "com.dwinovo.numen.plugins.rdd.";
    @TempDir Path temp;
    private URLClassLoader loader;

    @BeforeEach void compileChain() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("world-state-core/src/main/java"))) root = root.getParent();
        assertNotNull(root);
        // Reuse the existing fixture without modifying its source or invoking its compiler/setup.
        var boundaries = ContainerProviderReadConsumerTest.class.getDeclaredMethod("boundaries");
        boundaries.setAccessible(true);
        @SuppressWarnings("unchecked")
        var sources = new LinkedHashMap<>((Map<String, String>) boundaries.invoke(null));
        sources.remove(WS + "ContainerBoundaryFixture");
        sources.put("net.minecraft.core.BlockPos", """
            package net.minecraft.core;
            public class BlockPos {protected int x,y,z;public BlockPos(){}public BlockPos(int x,int y,int z){this.x=x;this.y=y;this.z=z;}
              public int getX(){return x;}public int getY(){return y;}public int getZ(){return z;}
              public BlockPos immutable(){return new BlockPos(x,y,z);}
              public static class MutableBlockPos extends BlockPos {public void set(int a,int b,int c){x=a;y=b;z=c;}}}
            """);
        // Merge the existing provider chest port with the existing RDD fixture's discovery/identity ports.
        sources.put("net.minecraft.server.level.ServerLevel", """
            package net.minecraft.server.level;
            import net.minecraft.core.BlockPos;
            public class ServerLevel extends net.minecraft.world.level.Level {
              public final net.minecraft.server.MinecraftServer server=new net.minecraft.server.MinecraftServer();
              public net.minecraft.server.MinecraftServer getServer(){return server;}
              public record Dim(String location){}public Dim dimension(){return new Dim("minecraft:overworld");}
              public boolean hasChunkAt(BlockPos p){return true;}
              public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos p){return new net.minecraft.world.level.block.state.BlockState(p.getX()!=0||p.getY()!=0||p.getZ()!=0);}
              public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos p){return new Chest();}
              private static class Chest extends net.minecraft.world.level.block.entity.BlockEntity implements net.minecraft.world.Container {public int getContainerSize(){return 27;}}
            }
            """);
        sources.put("net.minecraft.world.level.block.state.BlockState", """
            package net.minecraft.world.level.block.state;
            public record BlockState(boolean isAir){public Block getBlock(){return new Block();}
              public static class Block {public String getDescriptionId(){return "block.minecraft.chest";}}}
            """);
        sources.put("net.minecraft.server.MinecraftServer", """
            package net.minecraft.server;
            public class MinecraftServer {public boolean isSameThread(){return true;}
              public MinecraftServer getWorldData(){return this;}public String getLevelName(){return "world";}
              public net.minecraft.server.players.PlayerList getPlayerList(){throw new AssertionError("player branch not covered");}}
            """);
        sources.put("com.dwinovo.numen.entity.NumenPlayer", """
            package com.dwinovo.numen.entity;
            public class NumenPlayer {public final java.util.UUID id=new java.util.UUID(0,6);
              public final net.minecraft.server.level.ServerLevel level=new net.minecraft.server.level.ServerLevel();
              public java.util.UUID getUUID(){return id;}public net.minecraft.server.level.ServerLevel serverLevel(){return level;}
              public net.minecraft.server.level.ServerLevel level(){return level;}public net.minecraft.server.MinecraftServer getServer(){return level.server;}
              public net.minecraft.core.BlockPos blockPosition(){return new net.minecraft.core.BlockPos();}
              public static NumenPlayer findByUuid(Object s,java.util.UUID id){return com.dwinovo.numen.plugins.rdd.CapabilityMemoryHarness.body;}}
            """);
        sources.put("com.dwinovo.numen.api.WorldContextStamp", """
            package com.dwinovo.numen.api;
            public class WorldContextStamp {public record Token(java.util.Map<String,String> identity,long epoch){}
              public static Token current=new Token(java.util.Map.of("world_id","world","server_session","session"),1);
              public static Token capture(){return current;}public static Token capture(Object s){return current;}}
            """);
        sources.put("com.dwinovo.numen.api.NumenPlugins", "package com.dwinovo.numen.api;public class NumenPlugins {public static Object service;public static <T>T capability(Class<T> c){return c.cast(service);}}");
        sources.put("net.neoforged.neoforge.server.ServerLifecycleHooks", "package net.neoforged.neoforge.server;public class ServerLifecycleHooks {public static Object getCurrentServer(){return com.dwinovo.numen.plugins.rdd.CapabilityMemoryHarness.body.getServer();}}");
        sources.put("org.slf4j.Logger", "package org.slf4j;public class Logger {public void info(String s,Object...a){}public void warn(String s,Object...a){}public void debug(String s,Object...a){}}");
        sources.put("org.slf4j.LoggerFactory", "package org.slf4j;public class LoggerFactory {public static Logger getLogger(Class<?> c){return new Logger();}}");
        sources.put(WS + "WorldStateMonitor", "package com.dwinovo.numen.plugins.worldstate;public class WorldStateMonitor {public static void publish(String s,java.util.Map<String,Object> data){}}");
        sources.put(WS + "McCollectionContext", """
            package com.dwinovo.numen.plugins.worldstate;
            public class McCollectionContext implements com.dwinovo.numen.worldstate.core.provider.CollectionContext {
              public net.minecraft.server.level.ServerLevel levelOf(String d){return com.dwinovo.numen.plugins.rdd.CapabilityMemoryHarness.body.level;}
              public net.minecraft.server.MinecraftServer server(){return com.dwinovo.numen.plugins.rdd.CapabilityMemoryHarness.body.getServer();}
              public String worldId(){return "world";}public String serverSession(){return com.dwinovo.numen.api.WorldContextStamp.current.identity().get("server_session");}
              public long gameTick(){return 20;}public long nowMs(){return java.lang.System.currentTimeMillis()-com.dwinovo.numen.plugins.rdd.CapabilityMemoryHarness.sampleAge;}}
            """);
        sources.put(WS + "WorldStateService", HOST);
        sources.put(RDD + "CapabilityMemoryHarness", HARNESS);
        var args = new ArrayList<String>(List.of("-encoding", "UTF-8", "-proc:none", "-classpath",
                Path.of(WorldProvider.class.getProtectionDomain().getCodeSource().getLocation().toURI()) + java.io.File.pathSeparator
                + Path.of(WorldObservationService.class.getProtectionDomain().getCodeSource().getLocation().toURI()) + java.io.File.pathSeparator
                + Path.of(com.google.gson.Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI()),
                "-sourcepath", temp.toString(), "-d", temp.toString()));
        for (var e : sources.entrySet()) {
            Path p = temp.resolve(e.getKey().replace('.', '/') + ".java");
            Files.createDirectories(p.getParent()); Files.writeString(p, e.getValue()); args.add(p.toString());
        }
        var real = new LinkedHashMap<String, Path>();
        for (String n : List.of("ObservationCapability", "ObservationResponses", "ObservationMap", "VersionedChangesMapper", "SelfObservationProjection", "provider.ContainerWorldProvider", "provider.McCollectionContexts"))
            real.put(WS + n, root.resolve("plugins/worldstate/src/main/java/" + (WS + n).replace('.', '/') + ".java"));
        for (String n : List.of("RddContainerMemory", "RddContainerObservationGate", "ContainerChangePoller", "RddStorageStaleness"))
            real.put(RDD + n, root.resolve("plugins/rdd/src/main/java/" + (RDD + n).replace('.', '/') + ".java"));
        real.put("com.dwinovo.numen.api.storage.ContainerReader", root.resolve("api/common/src/main/java/com/dwinovo/numen/api/storage/ContainerReader.java"));
        real.values().forEach(p -> args.add(p.toString()));
        var diagnostics = new ByteArrayOutputStream();
        assertNotNull(ToolProvider.getSystemJavaCompiler(), "JDK required");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics, args.toArray(String[]::new)), diagnostics.toString(StandardCharsets.UTF_8));
        var isolated = new HashSet<>(sources.keySet()); isolated.addAll(real.keySet());
        loader = new URLClassLoader(new URL[]{temp.toUri().toURL()}, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (isolated.stream().anyMatch(n -> name.equals(n) || name.startsWith(n + "$"))) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> c = findLoadedClass(name); if (c == null) c = findClass(name);
                        if (resolve) resolveClass(c); return c;
                    }
                }
                return super.loadClass(name, resolve);
            }
        };
        for (String n : real.keySet()) assertEquals(temp.toUri().toURL(), loader.loadClass(n).getProtectionDomain().getCodeSource().getLocation());
    }
    @AfterEach void close() throws Exception { if (loader != null) loader.close(); }
    private List<?> run(String scenario) throws Exception {
        return (List<?>) loader.loadClass(RDD + "CapabilityMemoryHarness").getMethod("run", String.class).invoke(null, scenario);
    }
    @Test void knownEmptyPublishesObservedEmpty() throws Exception { assertEquals(List.of(true, Map.of(), true, false), run("empty")); }
    @Test void nonemptyThenNewEmptyReplacesPublishedCounts() throws Exception { assertEquals(List.of(true, Map.of(), true, false), run("change-empty")); }
    @Test void unknownReaderCreatesNoEmptyObservationOrCurrentSnapshot() throws Exception { assertEquals(List.of(false, Map.of(), false, false), run("unknown")); }
    @Test void changedSessionDoesNotTreatOldObservationAsCurrent() throws Exception { assertEquals(List.of(false, Map.of(), false, false), run("session")); }

    @Test void expiredProviderObservationCannotBecomeCurrentPublishedStock() throws Exception { assertEquals(List.of(false, Map.of(), false, false), run("expired")); }

    private static final String HOST = """
        package com.dwinovo.numen.plugins.worldstate;
        import com.dwinovo.numen.worldstate.core.provider.*;
        import com.dwinovo.numen.worldstate.core.query.*;
        import com.dwinovo.numen.worldstate.core.refresh.*;
        import com.dwinovo.numen.worldstate.core.request.*;
        import com.dwinovo.numen.worldstate.core.model.*;
        import com.dwinovo.numen.worldstate.core.store.*;
        import com.dwinovo.numen.worldstate.core.changes.*;
        import com.dwinovo.numen.worldstate.core.changes.v2.*;
        import com.dwinovo.numen.worldstate.core.freshness.*;
        import net.minecraft.server.MinecraftServer;
        public class WorldStateService {
          private final ProviderRegistry registry=new ProviderRegistry();
          private final TopicFreshnessPolicy policy=TopicFreshnessPolicy.defaults();
          private final InMemoryChangeLog log=new InMemoryChangeLog();
          private final InMemoryChangeFeed feed=new InMemoryChangeFeed("container-chain");
          public final InMemoryObservationStore store=new InMemoryObservationStore(4,log,feed);
          private final QueryService query;
          public WorldStateService(){registry.register(new com.dwinovo.numen.plugins.worldstate.provider.ContainerWorldProvider());
            query=new QueryService(store,policy,new RefreshService(registry,store,Runnable::run),java.lang.System::currentTimeMillis);}
          public java.util.Set<Topic> topics(){return registry.topics();}public long topicTtlMs(Topic t){return policy.ttlOf(t);}
          public ChangeLog changeLog(){return log;}public InMemoryChangeFeed changeFeedV2(){return feed;}
          public static String worldId(MinecraftServer s){return "world";}
          public ReadResult read(MinecraftServer s,ObservationRequest r){return query.read(worldId(s),new McCollectionContext().serverSession(),r);}
          public RefreshResult refresh(MinecraftServer s,ObservationRequest r){
            com.dwinovo.numen.plugins.rdd.CapabilityMemoryHarness.collecting=true;
            try{return query.refresh(new McCollectionContext(),r);}
            finally{com.dwinovo.numen.plugins.rdd.CapabilityMemoryHarness.collecting=false;}}
          public com.dwinovo.numen.api.observation.WorldObservationService.Response readSelf(MinecraftServer s,String c,java.util.UUID u,long age,long wm){throw new AssertionError("SELF outside fixture");}
        }
        """;
    private static final String HARNESS = """
        package com.dwinovo.numen.plugins.rdd;
        import java.util.*;import com.dwinovo.numen.api.*;import com.dwinovo.numen.api.storage.ContainerReader;
        import com.dwinovo.numen.api.observation.*;import com.dwinovo.numen.plugins.worldstate.*;
        public class CapabilityMemoryHarness {
          public static com.dwinovo.numen.entity.NumenPlayer body;
          static String mode;static int reads;public static long sampleAge;
          public static boolean collecting;static int providerReads,directReads;static String refreshResult="not_called";static final List<String> trace=new ArrayList<>();
          static final WorldObservationService.Request REQUEST=new WorldObservationService.Request("rdd-storage","minecraft:overworld","containers",Map.of("container","block:0,0,0"),0,64,1000);
          static void require(boolean b,String why){if(!b)throw new AssertionError(why);}
          public static List<Object> run(String scenario){
            body=new com.dwinovo.numen.entity.NumenPlayer();mode=scenario.equals("empty")?"empty":scenario.equals("unknown")?"unknown":"stock";
            com.dwinovo.numen.platform.Services.CONTAINERS=(l,p)->{
              require(l==body.level&&p.getX()==0&&p.getY()==0&&p.getZ()==0,"wrong reader target");reads++;
              if(collecting)providerReads++;else directReads++;
              trace.add((collecting?"provider":"direct")+"@"+java.lang.System.currentTimeMillis());
              return mode.equals("unknown")?ContainerReader.Result.unknown("unreadable"):ContainerReader.Result.known(mode.equals("empty")?Map.of():Map.of("minecraft:iron_ingot",5));};
            // Warm the real provider/core/capability path, then discard every warm-up fact and counter.
            // The production default 50ms deadline and all measured-path assertions stay unchanged.
            var warmHost=new WorldStateService();var warmCapability=new ObservationCapability(warmHost,()->body.getServer());
            warmCapability.refresh(REQUEST);
            RddContainerMemory.clear(body.id);reads=0;providerReads=0;directReads=0;trace.clear();
            var host=new WorldStateService();var capability=new ObservationCapability(host,()->body.getServer());
            NumenPlugins.service=new WorldObservationService(){
              public Response read(Request r){return capability.read(r);}
              public Response refresh(Request r){var response=capability.refresh(r);refreshResult=response.status()+"/"+response.reason();return response;}
              public ChangesV2Response changes(ChangesQuery q){return capability.changes(q);}
              public ChangesResponse changes(long a,List<String> topics,int limit){return capability.changes(a,topics,limit);}
            };
            if(scenario.equals("expired")){
              sampleAge=host.topicTtlMs(com.dwinovo.numen.worldstate.core.model.Topic.CONTAINERS)+1000;
              var stale=capability.refresh(REQUEST);
              require(host.store.size()==1,"real provider must write old sample");
              require(stale.status()==WorldObservationService.Status.PARTIAL&&stale.metadata().freshness()==ObservationMetadata.Freshness.STALE,"expired sample not mapped stale");
              require(stale.metadata().validUntilMs()<=java.lang.System.currentTimeMillis(),"expired deadline renewed");
              mode="unknown";RddContainerMemory.refresh(body,1000);
              require(capability.read(REQUEST).metadata().freshness()==ObservationMetadata.Freshness.STALE,"failed refresh promoted expired sample");
              var expiredSnapshot=RddContainerMemory.cachedSnapshot(body.id);
              return List.of(expiredSnapshot!=null,RddContainerMemory.cachedCounts(body.id),expiredSnapshot!=null&&expiredSnapshot.observed(),expiredSnapshot!=null&&expiredSnapshot.direct());
            }
            RddContainerMemory.refresh(body,1000);
            if(scenario.equals("unknown")){
              require(reads>=2,"provider and direct fallback must both see unknown");
              require(host.store.size()==0&&capability.read(REQUEST).status()==WorldObservationService.Status.PENDING,"unknown became observation");
            }else{
              var first=capability.read(REQUEST);require(first.status()==WorldObservationService.Status.OK&&first.coverage()==WorldObservationService.Coverage.FULL,"initial real response not ready");
              require(reads==1&&directReads==0,"observed scan must not direct-read: total="+reads+",provider="+providerReads+",direct="+directReads+",trace="+trace+",originalRefresh="+refreshResult+",response="+first.status()+",reason="+first.reason()+",freshness="+first.metadata().freshness()+",observedAt="+first.metadata().observedAtMs()+",validUntil="+first.metadata().validUntilMs()+",now="+java.lang.System.currentTimeMillis());
              require(RddContainerMemory.cachedCounts(body.id).equals(scenario.equals("empty")?Map.of():Map.of("minecraft:iron_ingot",5)),"initial counts missing");
              var previous=host.store.all().getFirst();
              if(scenario.equals("change-empty")){
                mode="empty";RddContainerMemory.refresh(body,1240);
                var next=host.store.all().getFirst();require(next.revision()>previous.revision(),"no new observation");
                require(capability.read(REQUEST).items().isEmpty()&&reads==2,"new empty not from provider");
              }
              if(scenario.equals("session")){
                WorldContextStamp.current=new WorldContextStamp.Token(Map.of("world_id","world","server_session","next"),2);
                var old=capability.read(REQUEST);require(old.status()==WorldObservationService.Status.PARTIAL&&old.metadata().freshness()==ObservationMetadata.Freshness.HISTORICAL,"old session still current");
                mode="unknown";RddContainerMemory.refresh(body,1240);
                require(host.store.all().getFirst()==previous,"failed refresh overwrote old observation");
                require(capability.read(REQUEST).metadata().freshness()==ObservationMetadata.Freshness.HISTORICAL,"failed refresh promoted old observation");
              }
            }
            var snapshot=RddContainerMemory.cachedSnapshot(body.id);
            return List.of(snapshot!=null,RddContainerMemory.cachedCounts(body.id),snapshot!=null&&snapshot.observed(),snapshot!=null&&snapshot.direct());
          }
        }
        """;
}
