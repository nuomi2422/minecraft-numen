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
 * 2026-11-12: freeze wire/model binding at real ship and LocalToolCallLog consumers.
 * WHY: reply isolation needs fresh wire IDs while conversation and client evidence retain model IDs.
 * USAGE: managed api:common exact ServerToolTransportBindingTest; compiler is invoked by the test.
 * Whole production transport, ToolCall, ToolAnchor and LocalToolCallLog compile unchanged.
 * Only network IO, journal and MC/Gson dependencies are detached; no alternative mapping algorithm.
 * Journal events prove a client association attempt, never server acceptance or durable evidence.
 * No live codec, server admission, disk persistence or complete owner loop is exercised.
 * The six frozen ServerToolTransportReplyIsolationTest cases remain separate and unchanged.
 */
class ServerToolTransportBindingTest {
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
                "api/common/src/main/java/com/dwinovo/numen/agent/tool/ToolAnchor.java")) {
            String source = Files.readString(root.resolve(relative));
            String pkg = source.substring(source.indexOf("package ")+8, source.indexOf(';')).trim();
            sources.put(pkg+"."+Path.of(relative).getFileName().toString().replace(".java",""), source);
        }
        sources.put("com.dwinovo.numen.Constants",
                "package com.dwinovo.numen; public class Constants {public static final Log LOG=new Log(); public static class Log {public void debug(String s,Object...a){}}}");
        sources.put("com.dwinovo.numen.agent.tool.NumenTool",
                "package com.dwinovo.numen.agent.tool; public interface NumenTool {String name(); default void invoke(ToolCall c){ServerToolTransport.ship(c);}}");
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
        sources.put("com.dwinovo.numen.client.agent.TransportBindingFixture", """
                package com.dwinovo.numen.client.agent;
                import java.util.*;
                import com.dwinovo.numen.agent.tool.*;
                import com.dwinovo.numen.network.payload.*;
                import com.dwinovo.numen.platform.Services;
                import com.dwinovo.numen.monitor.MonitoringJournal;
                public class TransportBindingFixture {
                  static final Set<UUID> actors = new HashSet<>();
                  static void setup() {
                    for (UUID actor : actors) ServerToolTransport.forget(actor);
                    actors.clear(); Services.NETWORK.packets.clear();
                    MonitoringJournal.events.clear(); MonitoringJournal.failure=""; MonitoringJournal.attempts=0;
                  }
                  static ToolCall call(UUID id,String modelId,String tool,List<String> results) {
                    actors.add(id);
                    return new ToolCall(modelId,tool,"{\\"probe\\":1}",() -> id,json -> results.add(modelId+":"+json));
                  }
                  static List<ExecuteToolPayload> sends() {
                    return Services.NETWORK.packets.stream().filter(ExecuteToolPayload.class::isInstance)
                        .map(ExecuteToolPayload.class::cast).toList();
                  }
                  static void reply(ExecuteToolPayload request,String text) {
                    ServerToolTransport.deliver(request.toolCallId(),text);
                  }
                  public static Map<String,Object> unique() {
                    setup(); UUID a=UUID.randomUUID(), b=UUID.randomUUID();
                    String model="p".repeat(128); List<String> results=new ArrayList<>();
                    List<String> ids=new ArrayList<>(); List<String> originalIds=new ArrayList<>();
                    for (int i=0;i<8;i++) {
                      UUID who=i%2==0?a:b;
                      ToolCall call=call(who,model,"mine",results);
                      ServerToolTransport.ship(call); originalIds.add(call.id());
                      ExecuteToolPayload request=sends().get(i); ids.add(request.toolCallId());
                      reply(request,"ok");
                    }
                    return Map.of("ids",ids,"model",model,"originalIds",originalIds,"results",results);
                  }
                  public static Map<String,Object> bindings() {
                    setup(); UUID a=UUID.randomUUID(), b=UUID.randomUUID();
                    String model="same"; List<String> results=new ArrayList<>();
                    ServerToolTransport.ship(call(a,model,"mine",results));
                    ExecuteToolPayload first=sends().get(0);
                    ServerToolTransport.ship(call(b,model,"craft",results));
                    ExecuteToolPayload second=sends().get(1);
                    reply(first,"a"); reply(second,"b");
                    return Map.of("events",List.copyOf(MonitoringJournal.events),
                        "a",a.toString(),"b",b.toString(),"firstWire",first.toolCallId(),
                        "secondWire",second.toolCallId(),"results",results,
                        "entity",first.entityUuid().equals(a),"tool",first.toolName(),"args",first.argumentsJson());
                  }
                  public static Map<String,Object> journalFailure(String failure) {
                    setup(); UUID actor=UUID.randomUUID(); List<String> results=new ArrayList<>();
                    MonitoringJournal.failure=failure;
                    ServerToolTransport.ship(call(actor,"model-original","mine",results));
                    ExecuteToolPayload packet=sends().get(0); reply(packet,"ok"); reply(packet,"duplicate");
                    return Map.of("attempts",MonitoringJournal.attempts,"events",MonitoringJournal.events.size(),
                        "sent",sends().size(),"results",results,"entity",packet.entityUuid().equals(actor));
                  }
                }
                """);
        List<String> args = new ArrayList<>(List.of("-encoding","UTF-8","-classpath",build.toString(),"-d",build.toString()));
        for (var entry : sources.entrySet()) {
            Path path = build.resolve(entry.getKey().replace('.','/')+".java");
            Files.createDirectories(path.getParent()); Files.writeString(path,entry.getValue());
            args.add(path.toString());
        }
        assertNotNull(ToolProvider.getSystemJavaCompiler(), "JDK compiler required");
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)),
                "real consumers must compile before any business assertion");
        loader = new URLClassLoader(new URL[]{build.toUri().toURL()},ClassLoader.getPlatformClassLoader());
        fixture = loader.loadClass("com.dwinovo.numen.client.agent.TransportBindingFixture");
    }

    @AfterAll static void close() throws Exception { if(loader!=null)loader.close(); }

    @SuppressWarnings("unchecked")
    static Map<String,Object> run(String name) throws Exception {
        return (Map<String,Object>)fixture.getMethod(name).invoke(null);
    }

    @Test void eachSendUsesUniqueBoundedNonExternalWireIdAndPreserves128CharModelId() throws Exception {
        var r=run("unique"); var ids=(List<?>)r.get("ids");
        assertEquals(8,new java.util.HashSet<>(ids).size(),"each send needs its own reply key");
        for(Object value:ids) {
            String wire=(String)value;
            assertTrue(!wire.isBlank() && wire.length()<=128,"wire ID must fit existing payload");
            assertFalse(wire.startsWith("mcp-"),"built-in wire must not select the external task route");
        }
        assertEquals(java.util.Collections.nCopies(8,r.get("model")),r.get("originalIds"));
        assertEquals(java.util.Collections.nCopies(8,r.get("model")+":ok"),r.get("results"));
    }

    @SuppressWarnings("unchecked")
    @Test void realJournalConsumerRecordsExactModelWireCompanionToolAssociationOnly() throws Exception {
        var r=run("bindings"); var events=(List<Map<String,Object>>)r.get("events");
        assertEquals(2,events.size(),"ship emits one binding per send and no duplicate call/result");
        Map<String,Object> first=events.get(0), second=events.get(1);
        assertEquals("tools",first.get("category")); assertEquals("tool_transport_binding",first.get("type"));
        assertEquals("tools",second.get("category")); assertEquals("tool_transport_binding",second.get("type"));
        assertEquals(Map.of("model_call_id","same","wire_call_id",r.get("firstWire"),
                "companion_id",r.get("a"),"tool","mine","journaled_by","client-transport"),first.get("data"));
        assertEquals(Map.of("model_call_id","same","wire_call_id",r.get("secondWire"),
                "companion_id",r.get("b"),"tool","craft","journaled_by","client-transport"),second.get("data"));
        assertEquals(true,r.get("entity")); assertEquals("mine",r.get("tool"));
        assertEquals("{\"probe\":1}",r.get("args"));
    }

    @Test void originalModelResultIdsSurviveIndependentSameIdNetworkReplies() throws Exception {
        var r=run("bindings");
        assertEquals(List.of("same:a","same:b"),r.get("results"));
        assertNotEquals(r.get("firstWire"),r.get("secondWire"));
    }

    @SuppressWarnings("unchecked")
    static void assertJournalFailureIsolated(String failure) throws Exception {
        var r=(Map<String,Object>)fixture.getMethod("journalFailure",String.class).invoke(null,failure);
        assertEquals(1,r.get("attempts"),"fault injection must exercise the real binding publication");
        assertEquals(0,r.get("events")); assertEquals(1,r.get("sent")); assertEquals(true,r.get("entity"));
        assertEquals(List.of("model-original:ok"),r.get("results"),"journal failure must not block reply or duplicate it");
    }

    @Test void journalRuntimeFailureDoesNotBlockShipOrResult() throws Exception {
        assertJournalFailureIsolated("runtime");
    }

    @Test void journalLinkageFailureDoesNotBlockShipOrResult() throws Exception {
        assertJournalFailureIsolated("linkage");
    }
}
