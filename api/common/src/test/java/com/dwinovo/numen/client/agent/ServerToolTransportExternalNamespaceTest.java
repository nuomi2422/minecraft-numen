package com.dwinovo.numen.client.agent;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-12 P1: external actuator invocations also use the default tool transport.
 * WHY: wire IDs must retain the existing mcp- namespace used by server owner/routing consumers.
 * USAGE: managed api:common exact ServerToolTransportExternalNamespaceTest.
 * Real Actuator.invoke and Brain.shipResults are sliced verbatim; extraction must fail closed.
 * Whole NumenTool/transport/logger/ToolCall/TaskRecord/TaskOwnership sources execute unchanged.
 * MC executor, network IO, journal, player shell, Gson and outcome sinks are detached boundaries.
 * This proves method-level routing and caller results, not real packets, ownership authentication,
 * server admission, disk durability, full Brain/Actuator classes or the complete client loop.
 * A provider forging mcp- is an existing namespace limitation; this patch adds no authentication.
 */
class ServerToolTransportExternalNamespaceTest {
    @TempDir static Path build;
    static URLClassLoader loader;
    static Class<?> fixture;

    @BeforeAll static void compileConsumers() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("api/common/src"))) root = root.getParent();
        assertNotNull(root, "repository source root required");
        Map<String,String> sources = new LinkedHashMap<>();
        for (String relative : List.of(
                "api/common/src/main/java/com/dwinovo/numen/agent/tool/ServerToolTransport.java",
                "api/common/src/main/java/com/dwinovo/numen/agent/tool/LocalToolCallLog.java",
                "api/common/src/main/java/com/dwinovo/numen/agent/tool/ToolCall.java",
                "api/common/src/main/java/com/dwinovo/numen/agent/tool/ToolAnchor.java",
                "api/common/src/main/java/com/dwinovo/numen/agent/tool/NumenTool.java",
                "api/common/src/main/java/com/dwinovo/numen/task/TaskRecord.java",
                "api/common/src/main/java/com/dwinovo/numen/task/TaskState.java",
                "api/common/src/main/java/com/dwinovo/numen/task/TaskOwnership.java")) {
            String source = Files.readString(root.resolve(relative));
            String pkg = source.substring(source.indexOf("package ")+8, source.indexOf(';')).trim();
            sources.put(pkg+"."+Path.of(relative).getFileName().toString().replace(".java",""), source);
        }
        sources.put("com.dwinovo.numen.Constants",
                "package com.dwinovo.numen; public class Constants {public static final Log LOG=new Log(); public static class Log {public void debug(String s,Object...a){}}}");
        sources.put("com.google.gson.JsonElement",
                "package com.google.gson; public class JsonElement {public boolean isJsonObject(){return false;} public JsonObject getAsJsonObject(){return new JsonObject();} public boolean isJsonPrimitive(){return false;} public JsonPrimitive getAsJsonPrimitive(){return new JsonPrimitive();} public boolean getAsBoolean(){return false;}}");
        sources.put("com.google.gson.JsonObject",
                "package com.google.gson; public class JsonObject extends JsonElement {public boolean has(String s){return false;} public JsonElement get(String s){return new JsonElement();}}");
        sources.put("com.google.gson.JsonPrimitive",
                "package com.google.gson; public class JsonPrimitive extends JsonElement {public boolean isBoolean(){return false;}}");
        sources.put("com.google.gson.JsonParser",
                "package com.google.gson; public class JsonParser {public static JsonElement parseString(String s){return new JsonElement();}}");
        sources.put("com.dwinovo.numen.network.payload.ExecuteToolPayload",
                "package com.dwinovo.numen.network.payload; public record ExecuteToolPayload(java.util.UUID entityUuid,String toolCallId,String toolName,String argumentsJson) {}");
        sources.put("com.dwinovo.numen.network.payload.CancelTasksPayload",
                "package com.dwinovo.numen.network.payload; public record CancelTasksPayload(java.util.UUID entityUuid) {}");
        sources.put("com.dwinovo.numen.platform.Services", """
                package com.dwinovo.numen.platform;
                public class Services {
                  public static final Network NETWORK = new Network();
                  public static class Network {
                    public final java.util.List<Object> packets = new java.util.ArrayList<>();
                    public void sendToServer(Object packet) { packets.add(packet); }
                  }
                }
                """);
        sources.put("com.dwinovo.numen.monitor.MonitoringJournal", """
                package com.dwinovo.numen.monitor;
                public class MonitoringJournal {
                  static final MonitoringJournal INSTANCE = new MonitoringJournal();
                  public static final java.util.List<java.util.Map<String,Object>> events = new java.util.ArrayList<>();
                  public static String failure = ""; public static int attempts;
                  public static MonitoringJournal get() { return INSTANCE; }
                  public void publish(String category,String type,java.util.Map<String,Object> data) {
                    attempts++;
                    if (failure.equals("runtime")) throw new IllegalStateException("journal unavailable");
                    if (failure.equals("linkage")) throw new NoClassDefFoundError("journal unavailable");
                    events.add(java.util.Map.of("category",category,"type",type,"data",java.util.Map.copyOf(data)));
                  }
                }
                """);
        sources.put("com.dwinovo.numen.agent.provider.IToolSpec",
                "package com.dwinovo.numen.agent.provider; public interface IToolSpec {String name();}");
        sources.put("com.dwinovo.numen.task.TaskResult",
                "package com.dwinovo.numen.task; public record TaskResult(String message) {public static TaskResult fail(String s){return new TaskResult(s);} public String toJson(){return message;}}");
        sources.put("com.dwinovo.numen.api.WorldContextStamp",
                "package com.dwinovo.numen.api; public class WorldContextStamp {public record Token(java.util.Map<String,String> identity,long epoch){}}");
        sources.put("net.minecraft.client.player.AbstractClientPlayer",
                "package net.minecraft.client.player; public class AbstractClientPlayer {}");
        sources.put("net.minecraft.server.level.ServerPlayer",
                "package net.minecraft.server.level; public class ServerPlayer {}");
        sources.put("com.dwinovo.numen.entity.NumenPlayer",
                "package com.dwinovo.numen.entity; public class NumenPlayer {public java.util.UUID getUUID(){return new java.util.UUID(0,1);} public net.minecraft.server.level.ServerPlayer resolveOwnerPlayer(){return null;}}");
        sources.put("com.dwinovo.numen.agent.tool.ClientToolContext",
                "package com.dwinovo.numen.agent.tool; public record ClientToolContext(net.minecraft.client.player.AbstractClientPlayer entity,java.util.UUID entityUuid) implements ToolAnchor {}");
        sources.put("net.minecraft.client.Minecraft",
                "package net.minecraft.client; public class Minecraft {static final Minecraft INSTANCE=new Minecraft(); public static int hops; public static Minecraft getInstance(){return INSTANCE;} public void execute(Runnable r){hops++; r.run();}}");
        sources.put("com.dwinovo.numen.client.agent.ClientNumenLookup",
                "package com.dwinovo.numen.client.agent; public class ClientNumenLookup {public static net.minecraft.client.player.AbstractClientPlayer resolve(java.util.UUID id){return null;}}");
        sources.put("com.dwinovo.numen.agent.tool.ToolRegistry",
                "package com.dwinovo.numen.agent.tool; public class ToolRegistry {public static NumenTool tool=new NumenTool(){public String name(){return \"mine\";}}; public static NumenTool resolve(String s){return tool;}}");
        sources.put("com.dwinovo.numen.event.NumenEvents", """
                package com.dwinovo.numen.event;
                public class NumenEvents {
                  public static final java.util.List<String> finished = new java.util.ArrayList<>();
                  public static void taskFinished(com.dwinovo.numen.entity.NumenPlayer p,String id,String tool,String status,String msg){finished.add(id);}
                }
                """);
        sources.put("com.dwinovo.numen.task.TaskCompletionText",
                "package com.dwinovo.numen.task; public class TaskCompletionText {public static String withStopHint(String s){return s;}}");
        sources.put("org.slf4j.LoggerFactory",
                "package org.slf4j; public class LoggerFactory {public static Log getLogger(Class<?> c){return new Log();} public static class Log {public void warn(String s,Object...a){}}}");
        sources.put("com.dwinovo.numen.network.payload.TaskResultPayload",
                "package com.dwinovo.numen.network.payload; public record TaskResultPayload(java.util.UUID entityUuid,String toolCallId,String resultJson) {}");
        // The synchronous branch of the original Brain method must compile too.
        sources.put("com.dwinovo.numen.platform.Services", """
                package com.dwinovo.numen.platform;
                public class Services {
                  public static final Network NETWORK=new Network();
                  public static class Network {
                    public final java.util.List<Object> packets=new java.util.ArrayList<>();
                    public void sendToServer(Object p){packets.add(p);}
                    public void sendToPlayer(Object owner,Object p){packets.add(p);}
                  }
                }
                """);
        String actuator=Files.readString(root.resolve("api/common/src/client/java/com/dwinovo/numen/api/NumenActuator.java"));
        String invoke=method(actuator,"public static CompletableFuture<String> invoke(");
        String sequence=actuator.lines().filter(line -> line.contains("private static final AtomicLong SEQ ="))
                .findFirst().orElseThrow(() -> new AssertionError("production Actuator SEQ declaration absent"));
        sources.put("com.dwinovo.numen.api.NumenActuator", """
                package com.dwinovo.numen.api;
                import com.dwinovo.numen.agent.tool.*;
                import com.dwinovo.numen.client.agent.ClientNumenLookup;
                import com.dwinovo.numen.task.*;
                import net.minecraft.client.Minecraft;
                import net.minecraft.client.player.AbstractClientPlayer;
                import java.util.UUID;
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.atomic.AtomicLong;
                public class NumenActuator {
                SEQUENCE
                INVOKE
                }
                """.replace("SEQUENCE",sequence).replace("INVOKE",invoke));
        String brain=Files.readString(root.resolve("api/common/src/main/java/com/dwinovo/numen/task/CompanionBrain.java"));
        sources.put("com.dwinovo.numen.task.CompanionBrain", """
                package com.dwinovo.numen.task;
                import com.dwinovo.numen.entity.NumenPlayer;
                public class CompanionBrain {
                  final java.util.Deque<TaskRecord> outbox=new java.util.ArrayDeque<>();
                  public static final java.util.List<String> outcomes=new java.util.ArrayList<>();
                  void journalTaskOutcome(NumenPlayer p,TaskRecord t,String status,String msg,TaskResult r){outcomes.add(status);}
                  void journalSyncOutcome(NumenPlayer p,TaskRecord t,TaskResult r){}
                  public static void settle(TaskRecord record) {
                    var b=new CompanionBrain(); b.outbox.add(record); b.shipResults(new NumenPlayer());
                  }
                  SHIP
                }
                """.replace("SHIP",method(brain,"private void shipResults(")));
        sources.put("com.dwinovo.numen.client.agent.ExternalNamespaceFixture", """
                package com.dwinovo.numen.client.agent;
                import java.util.*;
                import java.util.concurrent.*;
                import com.dwinovo.numen.api.NumenActuator;
                import com.dwinovo.numen.agent.tool.*;
                import com.dwinovo.numen.network.payload.*;
                import com.dwinovo.numen.platform.Services;
                import com.dwinovo.numen.monitor.MonitoringJournal;
                import com.dwinovo.numen.task.*;
                import com.dwinovo.numen.event.NumenEvents;
                public class ExternalNamespaceFixture {
                  static final Set<UUID> actors=new HashSet<>();
                  static void setup(){
                    for(UUID actor:actors)ServerToolTransport.forget(actor);
                    actors.clear();Services.NETWORK.packets.clear();MonitoringJournal.events.clear();
                    MonitoringJournal.failure="";MonitoringJournal.attempts=0;
                    NumenEvents.finished.clear();CompanionBrain.outcomes.clear();
                    net.minecraft.client.Minecraft.hops=0;
                  }
                  static CompletableFuture<String> external(UUID actor){
                    actors.add(actor);return NumenActuator.invoke(actor,"mine","{}");
                  }
                  static ExecuteToolPayload send(int index){
                    return Services.NETWORK.packets.stream().filter(ExecuteToolPayload.class::isInstance)
                        .map(ExecuteToolPayload.class::cast).toList().get(index);
                  }
                  static void reply(ExecuteToolPayload packet,String text){ServerToolTransport.deliver(packet.toolCallId(),text);}
                  static TaskRecord task(String wire){return new TaskRecord("mine",wire,100){};}
                  static TaskRecord completed(String wire){
                    TaskRecord t=task(wire);t.markAsync();t.setState(TaskState.SUCCESS);t.setResult(new TaskResult("done"));return t;
                  }
                  @SuppressWarnings("unchecked")
                  static Map<String,Object> binding(int index){
                    return (Map<String,Object>)MonitoringJournal.events.stream()
                        .filter(e -> e.get("type").equals("tool_transport_binding")).toList().get(index).get("data");
                  }
                  public static Map<String,Object> externalRoute(){
                    setup();UUID id=UUID.randomUUID();var future=external(id);var packet=send(0);
                    var record=task(packet.toolCallId());var data=binding(0);boolean waiting=!future.isDone();
                    reply(packet,"external-ok");reply(packet,"duplicate");
                    return Map.of("external",record.isExternalCall(),"waiting",waiting,"result",future.join(),
                        "model",data.get("model_call_id"),"wire",packet.toolCallId(),
                        "hops",net.minecraft.client.Minecraft.hops,"companion",data.get("companion_id").equals(id.toString()));
                  }
                  public static Map<String,Object> completionRoutes(){
                    setup();var future=external(UUID.randomUUID());var ext=completed(send(0).toolCallId());
                    CompanionBrain.settle(ext);int afterExternal=NumenEvents.finished.size();
                    var internal=completed("wire-builtin-probe");CompanionBrain.settle(internal);
                    return Map.of("afterExternal",afterExternal,"afterInternal",NumenEvents.finished.size(),
                        "finished",List.copyOf(NumenEvents.finished),"internalTask",internal.publicId(),
                        "outcomes",List.copyOf(CompanionBrain.outcomes),"futureWaiting",!future.isDone());
                  }
                  public static Map<String,Object> duplicate(){
                    setup();UUID id=UUID.randomUUID();var old=external(id);var oldPacket=send(0);
                    reply(oldPacket,"old");var fresh=external(id);var freshPacket=send(1);
                    reply(oldPacket,"late-old");boolean freshWaiting=!fresh.isDone();
                    reply(freshPacket,"fresh");reply(oldPacket,"duplicate");reply(freshPacket,"duplicate");
                    return Map.of("old",old.join(),"freshWaiting",freshWaiting,"fresh",fresh.join(),
                        "firstExternal",task(oldPacket.toolCallId()).isExternalCall(),
                        "freshExternal",task(freshPacket.toolCallId()).isExternalCall(),
                        "different",!oldPacket.toolCallId().equals(freshPacket.toolCallId()));
                  }
                  public static Map<String,Object> forgotten(){
                    setup();UUID id=UUID.randomUUID();var old=external(id);var oldPacket=send(0);
                    ServerToolTransport.forget(id);var fresh=external(id);var freshPacket=send(1);
                    reply(oldPacket,"late");boolean freshWaiting=!fresh.isDone();reply(freshPacket,"fresh");
                    return Map.of("oldWaiting",!old.isDone(),"freshWaiting",freshWaiting,"fresh",fresh.join(),
                        "freshExternal",task(freshPacket.toolCallId()).isExternalCall());
                  }
                  public static Map<String,Object> mapping(){
                    setup();UUID a=UUID.randomUUID(),b=UUID.randomUUID();var ext=external(a);
                    List<String> internalResults=new ArrayList<>();String model="p".repeat(128);actors.add(b);
                    ToolCall original=new ToolCall(model,"mine","{}",()->b,s -> internalResults.add(model+":"+s));
                    ToolRegistry.tool.invoke(original);
                    var ep=send(0);var ip=send(1);reply(ip,"builtin");reply(ep,"external");
                    return Map.of("externalData",binding(0),"builtinData",binding(1),
                        "externalWire",ep.toolCallId(),"builtinWire",ip.toolCallId(),
                        "a",a.toString(),"b",b.toString(),"model",original.id(),
                        "internalResults",internalResults,"externalResult",ext.join(),
                        "internalExternal",task(ip.toolCallId()).isExternalCall());
                  }
                  public static Map<String,Object> ownership(){
                    setup();external(UUID.randomUUID());TaskRecord external=task(send(0).toolCallId());
                    var owner=TaskOwnership.ownerOf(external.isExternalCall());
                    var conflict=TaskOwnership.decide(
                        new TaskOwnership.Current("internal",TaskOwnership.Owner.BUILTIN_BRAIN,0L),
                        new TaskOwnership.Request("companion",external.publicId(),owner,0L,"mine",false));
                    return Map.of("owner",owner.name(),"decision",conflict.decision().name());
                  }
                }
                """);
        List<String> args=new ArrayList<>(List.of("-encoding","UTF-8","-classpath",build.toString(),"-d",build.toString()));
        for(var entry:sources.entrySet()){
            Path path=build.resolve(entry.getKey().replace('.','/')+".java");
            Files.createDirectories(path.getParent());Files.writeString(path,entry.getValue());args.add(path.toString());
        }
        assertNotNull(ToolProvider.getSystemJavaCompiler(),"JDK compiler required");
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)),
                "actual external and routing consumers must compile before behavioral assertions");
        loader=new URLClassLoader(new URL[]{build.toUri().toURL()},ClassLoader.getPlatformClassLoader());
        fixture=loader.loadClass("com.dwinovo.numen.client.agent.ExternalNamespaceFixture");
    }

    @AfterAll static void close() throws Exception {if(loader!=null)loader.close();}

    @SuppressWarnings("unchecked")
    static Map<String,Object> run(String name)throws Exception{
        return (Map<String,Object>)fixture.getMethod(name).invoke(null);
    }

    @Test void realActuatorDefaultToolRetainsExternalNamespaceAndCallerReply()throws Exception{
        var r=run("externalRoute");
        assertEquals(true,r.get("external"),"real actuator call must remain external after wire serialization");
        assertEquals(true,r.get("waiting"));assertEquals("external-ok",r.get("result"));assertEquals(1,r.get("hops"));
        assertTrue(((String)r.get("model")).startsWith("mcp-"));
        assertTrue(((String)r.get("wire")).startsWith("mcp-"));
        assertNotEquals(r.get("model"),r.get("wire"));assertEquals(true,r.get("companion"));
    }

    @Test void actualBrainCompletionDoesNotWakeBuiltinForExternalTask()throws Exception{
        var r=run("completionRoutes");
        assertEquals(0,r.get("afterExternal"),"external async completion must not emit builtin task_finished");
        assertEquals(1,r.get("afterInternal"));
        assertEquals(List.of(r.get("internalTask")),r.get("finished"));
        assertEquals(List.of("external-done","done"),r.get("outcomes"));
        assertEquals(true,r.get("futureWaiting"),"external async driver polls; completion event is not a tool reply");
    }

    @Test void oldExternalDuplicateCannotConsumeNewActuatorReply()throws Exception{
        var r=run("duplicate");
        assertEquals("old",r.get("old"));assertEquals(true,r.get("freshWaiting"));assertEquals("fresh",r.get("fresh"));
        assertEquals(true,r.get("different"));assertEquals(true,r.get("firstExternal"));assertEquals(true,r.get("freshExternal"));
    }

    @Test void forgottenExternalResultCannotConsumeFreshActuatorCall()throws Exception{
        var r=run("forgotten");
        assertEquals(true,r.get("oldWaiting"));assertEquals(true,r.get("freshWaiting"));
        assertEquals("fresh",r.get("fresh"));assertEquals(true,r.get("freshExternal"));
    }

    @SuppressWarnings("unchecked")
    @Test void bothNamespacesMapOriginalIdsWhileOrdinary128CharModelIdStaysBuiltin()throws Exception{
        var r=run("mapping");var ext=(Map<String,Object>)r.get("externalData");var builtin=(Map<String,Object>)r.get("builtinData");
        String ew=(String)r.get("externalWire"),bw=(String)r.get("builtinWire");
        assertTrue(ew.startsWith("mcp-"));assertFalse(bw.startsWith("mcp-"));
        assertTrue(ew.length()<=128 && bw.length()<=128);assertNotEquals(ew,bw);
        assertEquals(Map.of("model_call_id",ext.get("model_call_id"),"wire_call_id",ew,"companion_id",r.get("a"),
                "tool","mine","journaled_by","client-transport"),ext);
        assertTrue(((String)ext.get("model_call_id")).startsWith("mcp-"));
        assertEquals(Map.of("model_call_id","p".repeat(128),"wire_call_id",bw,"companion_id",r.get("b"),
                "tool","mine","journaled_by","client-transport"),builtin);
        assertEquals("p".repeat(128),r.get("model"));
        assertEquals(List.of("p".repeat(128)+":builtin"),r.get("internalResults"));
        assertEquals("external",r.get("externalResult"));assertEquals(false,r.get("internalExternal"));
    }

    @Test void actualExternalPredicateKeepsExistingOwnerConflictDecision()throws Exception{
        var r=run("ownership");
        assertEquals("EXTERNAL_BRAIN",r.get("owner"));
        assertEquals("CONFLICT",r.get("decision"),"external caller must not become same-owner builtin replacement");
    }

    /** Copy the full method body verbatim; absent or unbalanced production source is a fixture failure. */
    static String method(String source,String signature){
        int start=source.indexOf(signature);assertTrue(start>=0,"missing production method: "+signature);
        int open=source.indexOf('{',start);assertTrue(open>=0,"missing method body: "+signature);
        int depth=1;
        for(int i=open+1;i<source.length();i++){
            if(source.charAt(i)=='{')depth++;
            else if(source.charAt(i)=='}'&&--depth==0)return source.substring(start,i+1);
        }
        throw new AssertionError("unterminated production method: "+signature);
    }
}
