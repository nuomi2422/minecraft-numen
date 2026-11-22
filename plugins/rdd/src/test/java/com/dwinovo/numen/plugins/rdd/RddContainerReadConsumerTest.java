package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.observation.WorldObservationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.net.*;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Entire production Memory, Gate and Poller; MC world, clock and Reader are controlled ports.
 * Unlike the earlier freshness fixture, neither readContainer nor readViaObservation is replaced.
 * No Minecraft server, storage capability implementation or deployment is exercised. */
class RddContainerReadConsumerTest {
    @TempDir Path temp;
    URLClassLoader loader;
    @BeforeEach void compile() throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while(root!=null&&!Files.exists(root.resolve("plugins/rdd/src/main/java")))root=root.getParent();
        assertNotNull(root);
        var sources=new LinkedHashMap<String,String>();
        sources.put("net.minecraft.core.BlockPos", """
            package net.minecraft.core;
            public class BlockPos { protected int x,y,z; public BlockPos(){} public BlockPos(int x,int y,int z){this.x=x;this.y=y;this.z=z;}
              public int getX(){return x;}public int getY(){return y;}public int getZ(){return z;}
              public BlockPos immutable(){return new BlockPos(x,y,z);}
              public static class MutableBlockPos extends BlockPos {public void set(int a,int b,int c){x=a;y=b;z=c;}} }
            """);
        sources.put("net.minecraft.server.level.ServerLevel", """
            package net.minecraft.server.level;
            import net.minecraft.core.BlockPos;import net.minecraft.world.level.block.state.BlockState;
            public class ServerLevel { public boolean loaded=true; public String dim="dim"; public Server server=new Server();
              public Server getServer(){return server;} public Dim dimension(){return new Dim(dim);} public record Dim(String location){}
              public boolean hasChunkAt(BlockPos p){return loaded;} public BlockState getBlockState(BlockPos p){return new BlockState(p.getX()!=0||p.getY()!=0||p.getZ()!=0);}
              public static class Server {public Server getWorldData(){return this;}public String getLevelName(){return "world";}} }
            """);
        sources.put("net.minecraft.world.level.block.state.BlockState", """
            package net.minecraft.world.level.block.state; public record BlockState(boolean isAir){public Block getBlock(){return new Block();}
              public static class Block {public String getDescriptionId(){return "block.minecraft.chest";}}}
            """);
        sources.put("com.dwinovo.numen.entity.NumenPlayer", """
            package com.dwinovo.numen.entity;import java.util.*;import net.minecraft.core.BlockPos;import net.minecraft.server.level.ServerLevel;
            public class NumenPlayer {public final UUID id=new UUID(0,6);public ServerLevel level=new ServerLevel();
              public UUID getUUID(){return id;}public ServerLevel serverLevel(){return level;} public ServerLevel level(){return level;}
              public ServerLevel.Server getServer(){return level.getServer();} public BlockPos blockPosition(){return new BlockPos();}
              public static NumenPlayer findByUuid(Object s,UUID id){return com.dwinovo.numen.plugins.rdd.ContainerReadHarness.body;} }
            """);
        sources.put("com.dwinovo.numen.api.WorldContextStamp", """
            package com.dwinovo.numen.api;import java.util.*;
            public class WorldContextStamp {public record Token(Map<String,String> identity,long epoch){} public static Token current=new Token(Map.of("world_id","world","server_session","session"),1);
              public static Token capture(){return current;}public static Token capture(Object server){return current;} }
            """);
        sources.put("com.dwinovo.numen.api.NumenPlugins", """
            package com.dwinovo.numen.api; public class NumenPlugins { public static Object service; public static <T>T capability(Class<T> c){return c.cast(service);} }
            """);
        sources.put("com.dwinovo.numen.api.storage.ContainerReader", """
            package com.dwinovo.numen.api.storage;import java.util.*;import net.minecraft.core.BlockPos;import net.minecraft.server.level.ServerLevel;
            public interface ContainerReader { Result read(ServerLevel level,BlockPos pos);record Result(boolean known,Map<String,Integer> items,String reason){} }
            """);
        sources.put("com.dwinovo.numen.platform.Services", """
            package com.dwinovo.numen.platform;import com.dwinovo.numen.api.storage.ContainerReader;
            public class Services {public static ContainerReader CONTAINERS=(l,p)->{com.dwinovo.numen.plugins.rdd.ContainerReadHarness.directReads++;return com.dwinovo.numen.plugins.rdd.ContainerReadHarness.direct;};}
            """);
        sources.put("net.neoforged.neoforge.server.ServerLifecycleHooks", "package net.neoforged.neoforge.server;public class ServerLifecycleHooks {public static Object getCurrentServer(){return com.dwinovo.numen.plugins.rdd.ContainerReadHarness.body.getServer();}}");
        sources.put("org.slf4j.Logger", "package org.slf4j; public class Logger {public void info(String s,Object... a){}public void warn(String s,Object...a){}public void debug(String s,Object...a){}}");
        sources.put("org.slf4j.LoggerFactory", "package org.slf4j;public class LoggerFactory {public static Logger getLogger(Class<?> c){return new Logger();}}");
        sources.put("com.dwinovo.numen.plugins.rdd.System", "package com.dwinovo.numen.plugins.rdd;public class System {public static long now=10000;public static long currentTimeMillis(){return now;}}");
        sources.put("com.dwinovo.numen.plugins.rdd.ContainerReadHarness",HARNESS);
        var args=new ArrayList<String>(List.of("-encoding","UTF-8","-proc:none","-classpath",
            Path.of(WorldObservationService.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),"-sourcepath",temp.toString(),"-d",temp.toString()));
        for(var e:sources.entrySet()){Path p=temp.resolve(e.getKey().replace('.','/')+".java");Files.createDirectories(p.getParent());Files.writeString(p,e.getValue());args.add(p.toString());}
        for(String name:List.of("RddContainerMemory","RddContainerObservationGate","ContainerChangePoller","RddStorageStaleness"))
            args.add(root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/"+name+".java").toString());
        var diagnostics=new ByteArrayOutputStream();int exit=ToolProvider.getSystemJavaCompiler().run(null,diagnostics,diagnostics,args.toArray(String[]::new));
        assertEquals(0,exit,"SETUP compiler failure: "+diagnostics.toString(java.nio.charset.StandardCharsets.UTF_8));
        loader=new URLClassLoader(new URL[]{temp.toUri().toURL()},getClass().getClassLoader()){
            @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
                if(sources.containsKey(name)||name.startsWith("com.dwinovo.numen.plugins.rdd.RddContainerMemory")||name.startsWith("com.dwinovo.numen.plugins.rdd.RddContainerObservationGate")||name.startsWith("com.dwinovo.numen.plugins.rdd.ContainerChangePoller")||name.startsWith("com.dwinovo.numen.plugins.rdd.RddStorageStaleness")||sources.keySet().stream().anyMatch(s->name.startsWith(s+"$"))){
                    synchronized(getClassLoadingLock(name)){Class<?> c=findLoadedClass(name);if(c==null)c=findClass(name);if(resolve)resolveClass(c);return c;}}
                return super.loadClass(name,resolve);
            }};
    }
    @AfterEach void close()throws Exception{if(loader!=null)loader.close();}
    private List<?> run(String scenario)throws Exception{return (List<?>)loader.loadClass("com.dwinovo.numen.plugins.rdd.ContainerReadHarness").getMethod("run",String.class).invoke(null,scenario);}
    @Test void currentObservedEmptyIsKnownAndNeverDirectlyReread()throws Exception{assertEquals(List.of(true,Map.of(),0),run("empty"));}
    @Test void failedPendingAndPartialWithUnknownReaderAreNotEmptyFacts()throws Exception{
        for(String mode:List.of("ERROR","PENDING","PARTIAL","coverage","expired","world","session","dimension","masked"))assertEquals(List.of(false,Map.of(),1),run(mode),mode);
    }
    @Test void nonemptyObservationReachesRealScanPublishedCounts()throws Exception{assertEquals(List.of(true,Map.of("minecraft:iron_ingot",5),0),run("stock"));}
    @Test void changedToEmptyRemovesPreviousStockWithoutInventingUnknown()throws Exception{assertEquals(List.of(true,Map.of(),0),run("change-empty"));}
    @Test void identityChangeWithFailedReadsCannotKeepOldStockCurrent()throws Exception{assertEquals(List.of(false,Map.of(),1),run("switch"));}
    @Test void emptyChangesDoNotKeepExpiredPublishedStockCurrent()throws Exception{assertEquals(List.of(false,Map.of(),0),run("expire-cache"));}
    @Test void unloadedScanCannotKeepPreviousStockCounts()throws Exception{assertEquals(List.of(true,Map.of(),0),run("unloaded"));}
    private static final String HARNESS="""
        package com.dwinovo.numen.plugins.rdd;
        import java.util.*;import com.dwinovo.numen.api.*;import com.dwinovo.numen.api.observation.*;import com.dwinovo.numen.entity.NumenPlayer;
        import com.dwinovo.numen.api.storage.ContainerReader;
        public class ContainerReadHarness {
          public static NumenPlayer body;public static int directReads;public static ContainerReader.Result direct;
          static String mode;static int stock=5;static long tick;
          static WorldObservationService.Response response(){
            var status=mode.equals("ERROR")?WorldObservationService.Status.ERROR:mode.equals("PENDING")?WorldObservationService.Status.PENDING:mode.equals("PARTIAL")?WorldObservationService.Status.PARTIAL:WorldObservationService.Status.OK;
            var coverage=mode.equals("coverage")?WorldObservationService.Coverage.PARTIAL:WorldObservationService.Coverage.FULL;
            var meta=new ObservationMetadata(mode.equals("world")?"other":"world",mode.equals("session")?"other":"session",mode.equals("dimension")?"other":"dim","","containers","key",1,9900,ObservationMetadata.Freshness.CURRENT,"SERVER_LOADED",mode.equals("expired")?10000:10100);
            var items=stock==0?List.<WorldObservationService.Item>of():List.of(new WorldObservationService.Item(Map.of("item","minecraft:iron_ingot","count",stock),mode.equals("masked")?Set.of("count"):Set.of(),Set.of()));
            return new WorldObservationService.Response(status,"containers","s",1,coverage,"",items,0,meta);
          }
          public static List<Object> run(String scenario){
            body=new NumenPlayer();RddContainerMemory.clear(body.id);System.now=10000;stock=5;tick+=1000;
            WorldContextStamp.current=new WorldContextStamp.Token(Map.of("world_id","world","server_session","session"),1);
            directReads=0;direct=new ContainerReader.Result(false,Map.of(),"unreadable");mode="stock";
            NumenPlugins.service=new WorldObservationService(){
              public Response read(Request r){return response();}public Response refresh(Request r){return response();}
              public ChangesV2Response changes(ChangesQuery q){return new ChangesV2Response(Status.OK,List.of(),0,false,0,false,"stream","");}
            };
            if(List.of("stock","change-empty","switch","expire-cache","unloaded").contains(scenario)){
              RddContainerMemory.refresh(body,tick);
              if(!RddContainerMemory.cachedCounts(body.id).equals(Map.of("minecraft:iron_ingot",5)))throw new AssertionError("initial publication missing");
              if(scenario.equals("expire-cache")){System.now=10100;return result();}
              if(scenario.equals("stock"))return result();
              if(scenario.equals("change-empty")){stock=0;System.now=10010;RddContainerMemory.refresh(body,tick+40);}
              if(scenario.equals("switch")){WorldContextStamp.current=new WorldContextStamp.Token(Map.of("world_id","next","server_session","next"),2);mode="ERROR";System.now=10010;RddContainerMemory.refresh(body,tick+1);}
              if(scenario.equals("unloaded")){body.level.loaded=false;System.now=10010;RddContainerMemory.refresh(body,tick+40);}
            }else{mode=scenario;stock=scenario.equals("empty")?0:5;RddContainerMemory.refresh(body,tick);}
            return result();
          }
          static List<Object> result(){return List.of(RddContainerMemory.cachedSnapshot(body.id)!=null,RddContainerMemory.cachedCounts(body.id),directReads);}
        }
        """;
}
