package com.dwinovo.numen.client.agent;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-12: execute the full production ToolDispatcher plus the unchanged sleep
 * consumer method and LLM generation guard, with detached MC/transport boundaries.
 * WHY: stopping the server body alone leaves queued calls and late responses alive.
 * USAGE: managed api:common test, exact SleepQueueGenerationTest; no Minecraft.
 * LIMIT: the loop method/guard are source slices, not a constructed 3500-line loop.
 * Full loader compilation and real network delivery still require separate validation.
 */
class SleepQueueGenerationTest {
    @TempDir static Path build;
    static URLClassLoader loader;
    static Class<?> fixture;

    @BeforeAll static void compileConsumers() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("api/common/src"))) root = root.getParent();
        Map<String,String> sources = new LinkedHashMap<>();
        for (String relative : List.of(
                "api/common/src/client/java/com/dwinovo/numen/client/agent/ToolDispatcher.java",
                "api/common/src/main/java/com/dwinovo/numen/agent/tool/ToolInvocation.java",
                "api/common/src/main/java/com/dwinovo/numen/agent/tool/ToolCall.java",
                "api/common/src/main/java/com/dwinovo/numen/agent/tool/ToolAnchor.java",
                "ai/src/main/java/com/dwinovo/numen/agent/llm/ConvoState.java")) {
            String source = Files.readString(root.resolve(relative));
            String pkg = source.substring(source.indexOf("package ")+8, source.indexOf(';')).trim();
            sources.put(pkg+"."+Path.of(relative).getFileName().toString().replace(".java",""), source);
        }
        sources.put("com.dwinovo.numen.api.Internal","package com.dwinovo.numen.api; public @interface Internal {}");
        sources.put("com.dwinovo.numen.Constants","package com.dwinovo.numen; public class Constants {public static final Log LOG=new Log();public static class Log{public void info(String s,Object...a){}public void warn(String s,Object...a){}}}");
        sources.put("net.minecraft.client.player.AbstractClientPlayer","package net.minecraft.client.player; public class AbstractClientPlayer {}");
        sources.put("com.google.gson.JsonObject","package com.google.gson; public class JsonObject {}");
        sources.put("com.google.gson.JsonParser","package com.google.gson; public class JsonParser {public static JsonParser parseString(String s){return new JsonParser();} public JsonObject getAsJsonObject(){return new JsonObject();}}");
        sources.put("com.dwinovo.numen.agent.provider.AssistantTurn","package com.dwinovo.numen.agent.provider; public record AssistantTurn(String content,java.util.List<Object> toolCalls,Object extras) {}");
        sources.put("com.dwinovo.numen.agent.tool.ClientToolContext","package com.dwinovo.numen.agent.tool; public record ClientToolContext(net.minecraft.client.player.AbstractClientPlayer entity,java.util.UUID entityUuid) implements ToolAnchor {}");
        sources.put("com.dwinovo.numen.agent.tool.NumenTool","package com.dwinovo.numen.agent.tool; public interface NumenTool {String name();void invoke(ToolCall call);}");
        sources.put("com.dwinovo.numen.agent.tool.ToolRegistry","package com.dwinovo.numen.agent.tool; public class ToolRegistry {public static NumenTool tool;public static NumenTool resolve(String name){return tool;}}");
        sources.put("com.dwinovo.numen.agent.tool.ToolAliases","package com.dwinovo.numen.agent.tool; public class ToolAliases {public static String resolve(String s){return s;}}");
        sources.put("com.dwinovo.numen.agent.tool.LocalToolCallLog","package com.dwinovo.numen.agent.tool; public class LocalToolCallLog {public static boolean runsLocally(NumenTool t){return false;}public static void publishSynthetic(Object...a){}public static void publishResult(Object...a){}public static void publishCall(Object...a){}public static void publishRejected(Object...a){}}");
        sources.put("com.dwinovo.numen.agent.tool.ToolDisclosure","package com.dwinovo.numen.agent.tool; public class ToolDisclosure {public static String notExpanded(String s){return s;}}");
        sources.put("com.dwinovo.numen.task.TaskResult","package com.dwinovo.numen.task; public record TaskResult(String text){public static TaskResult fail(String s){return new TaskResult(s);}public String toJson(){return text;}}");
        sources.put("com.dwinovo.numen.api.CompanionEvent","package com.dwinovo.numen.api; public class CompanionEvent {public static final Object ABORT=new Object();}");
        sources.put("com.dwinovo.numen.entity.CompanionEvents","package com.dwinovo.numen.entity; public class CompanionEvents {public static int aborts;public static void fire(Object a,Object b){aborts++;}}");
        sources.put("com.dwinovo.numen.agent.tool.ServerToolTransport","package com.dwinovo.numen.agent.tool; public class ServerToolTransport {public static int forgets;public static void forget(java.util.UUID id){forgets++;}}");
        sources.put("com.dwinovo.numen.api.NumenApi","package com.dwinovo.numen.api; public interface NumenApi {String SLEEP_INTERRUPT=\"sleep_interrupt\";}");

        String loop = Files.readString(root.resolve("api/common/src/client/java/com/dwinovo/numen/client/agent/EntityAgentLoop.java"));
        String natural = method(loop,"public boolean interruptForSleep(");
        // Baseline has no seam: keep its actual no-cancellation behavior, yielding assertion reds.
        if (natural == null) natural = "public boolean interruptForSleep(String taskId){return false;}";
        String response = loop.substring(loop.indexOf("private void handleResponse("));
        String guard = response.substring(response.indexOf('{')+1,response.indexOf("        awaitingLlmResponse = false;"));
        sources.put("com.dwinovo.numen.client.agent.SleepFixture", """
            package com.dwinovo.numen.client.agent;
            import java.util.*;
            import com.dwinovo.numen.Constants;
            import com.dwinovo.numen.agent.tool.*;
            import com.dwinovo.numen.agent.llm.ConvoState;
            import com.dwinovo.numen.agent.provider.AssistantTurn;
            import com.dwinovo.numen.api.NumenApi;
            public class SleepFixture {
              UUID entityUuid=UUID.randomUUID(); int turnGeneration;
              boolean awaitingLlmResponse=true,compacting=true,dead,external,turnRetried=true;
              String turnPause="NONE",goal="original";int cursor=7,retries=2,streamClears;
              record CurrentTask(String id){}
              CurrentTask currentTask=new CurrentTask("body-1");
              ConvoState convo=new ConvoState();
              Presenter presenter=new Presenter();
              class Presenter {void clearPartial(){streamClears++;}void finishStreamLine(){}void interruptVoice(){}}
              boolean isExternallyDriven(){return external;}
              boolean stoppedByOwner(){return "OWNER_INTERRUPT".equals(turnPause);}
              final List<ToolCall> sent=new ArrayList<>();
              int settled,results;
              ToolDispatcher dispatcher=new ToolDispatcher(entityUuid,new ToolDispatcher.Sink(){
                public void onResult(ToolInvocation inv,String json){results++;convo.addToolResult(inv.id(),json);}
                public void onAllSettled(){settled++;}
                public net.minecraft.client.player.AbstractClientPlayer entity(){return null;}
              });
              public SleepFixture(){ToolRegistry.tool=new NumenTool(){public String name(){return "mine";}public void invoke(ToolCall c){sent.add(c);}};}
              void dispatch(String... ids){dispatcher.dispatch(Arrays.stream(ids).map(id->new ToolInvocation(id,"mine","{}")).toList(),Set.of("mine"));}
              public void response(int gen,String id){GUARD dispatcher.dispatch(List.of(new ToolInvocation(id,"mine","{}")),Set.of("mine"));}
              NATURAL
              public static Map<String,Object> cancelQueued(){
                var f=new SleepFixture();f.dispatch("old-1","old-2");boolean accepted=f.interruptForSleep("body-1");
                f.sent.get(0).complete("late");return Map.of("accepted",accepted,"sent",f.sent.size(),"busy",f.dispatcher.busy(),"results",f.results,"protocol",f.convo.snapshot().size(),"goal",f.goal,"cursor",f.cursor,"retries",f.retries,"pause",f.turnPause,"turnRetried",f.turnRetried);
              }
              public static Map<String,Object> staleLlm(){
                var f=new SleepFixture();f.convo.addUser("original");int old=f.turnGeneration;
                f.interruptForSleep("body-1");f.awaitingLlmResponse=true;f.response(old,"late");
                int oldSent=f.sent.size();boolean awaiting=f.awaitingLlmResponse;
                f.response(f.turnGeneration,"new");return Map.of("oldSent",oldSent,"sent",f.sent.size(),"awaiting",awaiting,"protocol",f.convo.snapshot().size());
              }
              public static Map<String,Object> reusedId(){
                var f=new SleepFixture();f.dispatch("same");ToolCall old=f.sent.get(0);
                f.dispatcher.cancelAndDrain(false);f.dispatch("same","next");old.complete("old");
                int afterOld=f.sent.size(),results=f.results;f.sent.get(1).complete("new");
                return Map.of("afterOld",afterOld,"resultsAfterOld",results,"afterNew",f.sent.size());
              }
              public static Map<String,Object> exclusions(){
                var f=new SleepFixture();f.external=true;f.dispatch("old","queued");boolean ext=f.interruptForSleep("body-1");
                f.external=false;f.turnPause="OWNER_INTERRUPT";boolean owner=f.interruptForSleep("body-1");
                f.turnPause="NONE";f.currentTask=new CurrentTask("replacement");boolean replaced=f.interruptForSleep("body-1");
                return Map.of("external",ext,"owner",owner,"replaced",replaced,"sent",f.sent.size(),"busy",f.dispatcher.busy(),"generation",f.turnGeneration);
              }
              public static Map<String,Object> fresh(){
                var f=new SleepFixture();f.dispatch("old","queued");f.interruptForSleep("body-1");
                f.response(f.turnGeneration,"new");int before=f.sent.size();f.sent.get(f.sent.size()-1).complete("ok");
                return Map.of("sent",before,"results",f.results,"busy",f.dispatcher.busy(),"settled",f.settled,"aborts",com.dwinovo.numen.entity.CompanionEvents.aborts);
              }
            }
            """.replace("GUARD",guard).replace("NATURAL",natural));
        List<String> args=new ArrayList<>(List.of("-d",build.toString()));
        for(var entry:sources.entrySet()){
            Path file=build.resolve(entry.getKey().replace('.','/')+".java");
            Files.createDirectories(file.getParent());Files.writeString(file,entry.getValue());args.add(file.toString());
        }
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)),"detached consumers must compile before behavioral assertions");
        loader=new URLClassLoader(new URL[]{build.toUri().toURL()},ClassLoader.getPlatformClassLoader());
        fixture=loader.loadClass("com.dwinovo.numen.client.agent.SleepFixture");
    }

    @AfterAll static void close() throws Exception {if(loader!=null)loader.close();}
    @SuppressWarnings("unchecked") static Map<String,Object> run(String method)throws Exception {
        return (Map<String,Object>)fixture.getMethod(method).invoke(null);
    }
    @Test void naturalSleepCancelsInFlightAndQueuedWithoutOwnerStop()throws Exception{
        var r=run("cancelQueued");assertEquals(true,r.get("accepted"));assertEquals(1,r.get("sent"));
        assertEquals(false,r.get("busy"));assertEquals(0,r.get("results"));assertEquals(2,r.get("protocol"));
        assertEquals("original",r.get("goal"));assertEquals(7,r.get("cursor"));assertEquals(2,r.get("retries"));
        assertEquals("NONE",r.get("pause"));assertEquals(true,r.get("turnRetried"));
    }
    @Test void lateLlmCannotDispatchOrClearNewAwaitingFlag()throws Exception{
        var r=run("staleLlm");assertEquals(0,r.get("oldSent"));assertEquals(true,r.get("awaiting"));
        assertEquals(1,r.get("sent"));assertEquals(2,r.get("protocol"));
    }
    @Test void cancelledCallbackCannotConsumeReusedCallId()throws Exception{
        var r=run("reusedId");assertEquals(2,r.get("afterOld"));assertEquals(0,r.get("resultsAfterOld"));assertEquals(3,r.get("afterNew"));
    }
    @Test void externalOwnerStopAndReplacementStayUntouched()throws Exception{
        var r=run("exclusions");assertEquals(false,r.get("external"));assertEquals(false,r.get("owner"));
        assertEquals(false,r.get("replaced"));assertEquals(1,r.get("sent"));assertEquals(true,r.get("busy"));assertEquals(0,r.get("generation"));
    }
    @Test void freshGenerationRunsAndSettlesNormally()throws Exception{
        var r=run("fresh");assertEquals(2,r.get("sent"));assertEquals(1,r.get("results"));assertEquals(false,r.get("busy"));assertEquals(1,r.get("settled"));assertEquals(0,r.get("aborts"));
    }

    /** Balanced Java method slice, preserving its full body verbatim. Braces inside strings
     * are balanced in these methods; no rewritten cancellation/guard algorithm lives here. */
    static String method(String source,String signature){
        int start=source.indexOf(signature);if(start<0)return null;
        int open=source.indexOf('{',start),depth=1;
        for(int i=open+1;i<source.length();i++){
            if(source.charAt(i)=='{')depth++;
            else if(source.charAt(i)=='}'&&--depth==0)return source.substring(start,i+1);
        }
        throw new AssertionError("unterminated production method: "+signature);
    }
}
