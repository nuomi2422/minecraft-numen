package com.dwinovo.numen.api;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-12: execute the production API's sleep handoff method against counted
 * transport/body boundaries. Notification must precede even an immediate stop reply.
 * No alternative handoff algorithm: the exact Impl method is compiled verbatim.
 * Baseline missing seam behaves as no handoff, yielding an assertion failure.
 * This tests method consumption/order, not network codecs, delivery or real MC stop.
 */
class SleepHandoffConsumerTest {
    @TempDir static Path build;
    static URLClassLoader loader;
    static Class<?> fixture;
    @BeforeAll static void compile() throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while(!Files.exists(root.resolve("api/common/src")))root=root.getParent();
        String source=Files.readString(root.resolve("api/common/src/main/java/com/dwinovo/numen/api/NumenPlugins.java"));
        source=source.substring(source.indexOf("private static final class Impl"));
        String natural=method(source,"public boolean interruptForSleep(");
        if(natural==null)natural="public boolean interruptForSleep(com.dwinovo.numen.entity.NumenPlayer p,com.dwinovo.numen.task.TaskRecord t){return false;}";
        Map<String,String> sources=new LinkedHashMap<>();
        sources.put("com.dwinovo.numen.api.NumenApi","package com.dwinovo.numen.api;public interface NumenApi{String SLEEP_INTERRUPT=\"sleep_interrupt\";}");
        sources.put("com.dwinovo.numen.entity.NumenPlayer","""
            package com.dwinovo.numen.entity;
            public class NumenPlayer {
              public boolean sleeping,dead,thread=true;public int hurtTime;public Object owner=new Object();
              public NumenPlayer getServer(){return this;}public boolean isSameThread(){return thread;}
              public boolean isSleeping(){return sleeping;}public boolean isDeadOrDying(){return dead;}
              public Object resolveOwnerPlayer(){return owner;}public java.util.UUID getUUID(){return new java.util.UUID(0,1);}
            }
            """);
        sources.put("com.dwinovo.numen.task.TaskRecord","""
            package com.dwinovo.numen.task;
            public class TaskRecord {public boolean external;public String tool="mine";
              public boolean isExternalCall(){return external;}public String getToolName(){return tool;}
              public String publicId(){return "body-1";}
            }
            """);
        sources.put("com.dwinovo.numen.network.payload.NumenEventPayload","package com.dwinovo.numen.network.payload;public record NumenEventPayload(java.util.UUID entityUuid,String entryType,String text,long ts,boolean urgent){}");
        sources.put("com.dwinovo.numen.platform.Services","""
            package com.dwinovo.numen.platform;
            public class Services{
              public static final java.util.List<String> trace=new java.util.ArrayList<>();
              public static final Network NETWORK=new Network();
              public static class Network {public void sendToPlayer(Object owner,com.dwinovo.numen.network.payload.NumenEventPayload p){trace.add(p.entryType()+":"+p.text());}}
            }
            """);
        sources.put("com.dwinovo.numen.task.CompanionTickDispatcher","""
            package com.dwinovo.numen.task;
            public class CompanionTickDispatcher{
              public static TaskRecord current;public static int stops;
              public static TaskRecord currentTaskFor(java.util.UUID id){return current;}
              public static TaskRecord stopActive(com.dwinovo.numen.entity.NumenPlayer p,String reason){
                stops++;com.dwinovo.numen.platform.Services.trace.add("stop");
                TaskRecord old=current;current=null;
                com.dwinovo.numen.platform.Services.trace.add("result");return old;
              }
            }
            """);
        sources.put("com.dwinovo.numen.api.SleepHandoffFixture",("""
            package com.dwinovo.numen.api;
            import java.util.*;
            import com.dwinovo.numen.entity.NumenPlayer;
            import com.dwinovo.numen.task.*;
            import com.dwinovo.numen.platform.Services;
            public class SleepHandoffFixture{
              NATURAL
              public static Map<String,Object> run(String mode){
                Services.trace.clear();CompanionTickDispatcher.stops=0;
                var p=new NumenPlayer();var t=new TaskRecord();CompanionTickDispatcher.current=t;
                switch(mode){
                  case "external" -> t.external=true;
                  case "replacement" -> CompanionTickDispatcher.current=new TaskRecord();
                  case "offline" -> p.owner=null;
                  case "offthread" -> p.thread=false;
                  case "sleeping" -> p.sleeping=true;
                  case "combat" -> p.hurtTime=1;
                  case "dead" -> p.dead=true;
                  case "mine" -> {}
                  default -> t.tool=mode;
                }
                boolean accepted=new SleepHandoffFixture().interruptForSleep(p,t);
                return Map.of("accepted",accepted,"stops",CompanionTickDispatcher.stops,"trace",List.copyOf(Services.trace));
              }
            }
            """).replace("NATURAL",natural));
        List<String> args=new ArrayList<>(List.of("-d",build.toString()));
        for(var e:sources.entrySet()){
            Path p=build.resolve(e.getKey().replace('.','/')+".java");
            Files.createDirectories(p.getParent());Files.writeString(p,e.getValue());args.add(p.toString());
        }
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
        loader=new URLClassLoader(new URL[]{build.toUri().toURL()},ClassLoader.getPlatformClassLoader());
        fixture=loader.loadClass("com.dwinovo.numen.api.SleepHandoffFixture");
    }
    @AfterAll static void close()throws Exception{if(loader!=null)loader.close();}
    @SuppressWarnings("unchecked") static Map<String,Object> run(String mode)throws Exception{return (Map<String,Object>)fixture.getMethod("run",String.class).invoke(null,mode);}
    @Test void invalidationPrecedesActualStopAndItsImmediateReply()throws Exception{
        var r=run("mine");assertEquals(true,r.get("accepted"));assertEquals(1,r.get("stops"));
        assertEquals(List.of("sleep_interrupt:body-1","stop","result"),r.get("trace"));
    }
    @Test void externalAndSurvivalActionsCannotBeStopped()throws Exception{
        for(String mode:List.of("external","eat","flee","attack","sleep","unknown","goto")){
            var r=run(mode);assertEquals(false,r.get("accepted"),mode);assertEquals(0,r.get("stops"),mode);assertEquals(List.of(),r.get("trace"),mode);
        }
    }
    @Test void replacementOfflineCombatAndWrongThreadFailClosed()throws Exception{
        for(String mode:List.of("replacement","offline","offthread","combat","sleeping","dead")){
            var r=run(mode);assertEquals(false,r.get("accepted"),mode);assertEquals(0,r.get("stops"),mode);assertEquals(List.of(),r.get("trace"),mode);
        }
    }
    static String method(String source,String signature){
        int start=source.indexOf(signature);if(start<0)return null;
        int open=source.indexOf('{',start),depth=1;
        for(int i=open+1;i<source.length();i++){
            if(source.charAt(i)=='{')depth++;
            else if(source.charAt(i)=='}'&&--depth==0)return source.substring(start,i+1);
        }
        throw new AssertionError("unterminated production method");
    }
}
