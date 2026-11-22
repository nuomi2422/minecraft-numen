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
                "api/common/src/main/java/com/dwinovo/numen/agent/tool/ToolAnchor.java",
                "api/common/src/main/java/com/dwinovo/numen/task/TaskResult.java",
                "api/common/src/main/java/com/dwinovo/numen/agent/tool/ToolInvocation.java",
                "api/common/src/client/java/com/dwinovo/numen/client/agent/ToolDispatcher.java")) {
            String source = Files.readString(root.resolve(relative));
            String pkg = source.substring(source.indexOf("package ")+8, source.indexOf(';')).trim();
            sources.put(pkg+"."+Path.of(relative).getFileName().toString().replace(".java",""), source);
        }
        String resultSource=Files.readString(root.resolve("api/common/src/main/java/com/dwinovo/numen/network/payload/TaskResultPayload.java"));
        int start=resultSource.indexOf("    public static void handle(TaskResultPayload p) {");
        int end=resultSource.indexOf("\n    private static String truncate",start); assertTrue(start>=0 && end>start);
        sources.put("com.dwinovo.numen.network.payload.TaskResultPayload","package com.dwinovo.numen.network.payload; import java.util.*; import com.dwinovo.numen.Constants; import com.dwinovo.numen.agent.tool.ServerToolTransport; public record TaskResultPayload(UUID entityUuid,String toolCallId,String resultJson){"+resultSource.substring(start,end)+" static String truncate(String s,int n){return s;} }");
        sources.put("com.dwinovo.numen.Constants",
                "package com.dwinovo.numen; public class Constants {public static final Log LOG=new Log(); public static class Log {public static final java.util.List<String> entries=new java.util.ArrayList<>(); public void debug(String s,Object...a){entries.add(java.util.Arrays.toString(a));} public void info(String s,Object...a){entries.add(java.util.Arrays.toString(a));} public void warn(String s,Object...a){entries.add(java.util.Arrays.toString(a));}}}");
        sources.put("net.minecraft.client.player.AbstractClientPlayer","package net.minecraft.client.player; public class AbstractClientPlayer {}");
        sources.put("com.dwinovo.numen.agent.tool.ClientToolContext","package com.dwinovo.numen.agent.tool; public record ClientToolContext(net.minecraft.client.player.AbstractClientPlayer entity,java.util.UUID entityUuid) implements ToolAnchor {}");
        sources.put("com.dwinovo.numen.agent.tool.ToolRegistry","package com.dwinovo.numen.agent.tool; public class ToolRegistry {public static NumenTool resolve(String s){return ()->s;}}");
        sources.put("com.dwinovo.numen.agent.tool.ToolAliases","package com.dwinovo.numen.agent.tool; public class ToolAliases {public static String resolve(String s){return s;}}");
        sources.put("com.dwinovo.numen.agent.tool.ToolDisclosure","package com.dwinovo.numen.agent.tool; public class ToolDisclosure {public static String notExpanded(String s){return s;}}");
        sources.put("com.dwinovo.numen.api.CompanionEvent","package com.dwinovo.numen.api; public enum CompanionEvent {ABORT}");
        sources.put("com.dwinovo.numen.entity.CompanionEvents","package com.dwinovo.numen.entity; public class CompanionEvents {public static void fire(Object a,Object b){}}");
        sources.put("com.dwinovo.numen.agent.tool.NumenTool",
                "package com.dwinovo.numen.agent.tool; public interface NumenTool {String name(); default void invoke(ToolCall c){ServerToolTransport.ship(c);}}");
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
                  public static Map<String,Object> internalTicket() {
                    setup(); UUID a=UUID.randomUUID(), b=UUID.randomUUID(); actors.add(a); actors.add(b);
                    List<String> received=new ArrayList<>();
                    String id=ServerToolTransport.requestYieldTicket(a,received::add);
                    com.dwinovo.numen.Constants.Log.entries.clear();
                    TaskResultPayload.handle(new TaskResultPayload(b,id,"secret-wrong")); boolean wrong=received.isEmpty();
                    TaskResultPayload.handle(new TaskResultPayload(a,id,"secret-good")); boolean good=received.equals(List.of("secret-good"));
                    TaskResultPayload.handle(new TaskResultPayload(a,id,"secret-late")); boolean late=received.size()==1;
                    String cancelled=ServerToolTransport.requestYieldTicket(a,received::add);
                    ServerToolTransport.cancelYieldTicket(cancelled);
                    boolean afterCancel=ServerToolTransport.deliverInternalTicket(a,cancelled,"secret-cancel");
                    String logout=ServerToolTransport.requestYieldTicket(a,received::add);
                    ServerToolTransport.forget(a);
                    boolean afterLogout=ServerToolTransport.deliverInternalTicket(a,logout,"secret-logout");
                    return Map.of("wrong",wrong,"good",good,"late",late,"cancel",afterCancel,"logout",afterLogout,"received",List.copyOf(received),"events",MonitoringJournal.events.size(),"packet",sends().get(0).toolName(),"clientLog",com.dwinovo.numen.Constants.Log.entries.toString());
                  }
                  public static Map<String,Object> expiredReplyWithoutSweep() throws Exception {
                    setup(); UUID a=UUID.randomUUID(); actors.add(a); List<String> received=new ArrayList<>();
                    String id=ServerToolTransport.requestYieldTicket(a,received::add);
                    var f=ServerToolTransport.class.getDeclaredField("TICKET_WAIT"); f.setAccessible(true);
                    var waits=(Map<String,Object>)f.get(null); Object old=waits.get(id);
                    var ctor=old.getClass().getDeclaredConstructors()[0]; ctor.setAccessible(true);
                    waits.put(id,ctor.newInstance(a,(java.util.function.Consumer<String>)received::add,System.nanoTime()-1));
                    TaskResultPayload.handle(new TaskResultPayload(a,id,"expired-secret"));
                    return Map.of("count",received.size(),"failed",received.size()==1 && received.get(0)==null);
                  }
                  public static Map<String,Object> unclassifiedTicketReply() {
                    setup(); UUID a=UUID.randomUUID();actors.add(a); com.dwinovo.numen.Constants.Log.entries.clear();
                    TaskResultPayload.handle(new TaskResultPayload(a,"ordinary-wire-id","signed-ticket-secret"));
                    return Map.of("log",com.dwinovo.numen.Constants.Log.entries.toString());
                  }
                  public static Map<String,Object> dispatcherBatch() {
                    setup();UUID a=UUID.randomUUID();actors.add(a);List<String> results=new ArrayList<>();
                    ToolDispatcher dispatcher=new ToolDispatcher(a,new ToolDispatcher.Sink(){public void onResult(ToolInvocation i,String s){results.add(i.id()+":"+s);}public void onAllSettled(){}public net.minecraft.client.player.AbstractClientPlayer entity(){return null;}});
                    dispatcher.dispatch(List.of(new ToolInvocation("same","rdd_report_yield","{}")),Set.of("rdd_report_yield"),"host-old");
                    dispatcher.dispatch(List.of(new ToolInvocation("same","rdd_report_yield","{}")),Set.of("rdd_report_yield"),"host-new");
                    var first=sends().get(0);reply(first,"ok-old");var second=sends().get(1);reply(second,"ok-new");
                    return Map.of("first",first.argumentsJson(),"second",second.argumentsJson(),"results",results);
                  }
                  public static Map<String,Object> yieldBatchWire() {
                    setup(); UUID a=UUID.randomUUID();actors.add(a);List<String> responses=new ArrayList<>();
                    com.google.gson.JsonObject argObj=new com.google.gson.JsonObject(); argObj.addProperty("_yield_ticket","model-forged"); argObj.addProperty("take",1); String args=argObj.toString();
                    ServerToolTransport.ship(new ToolCall("same","rdd_report_yield",args,()->a,"host-old",responses::add));
                    ServerToolTransport.ship(new ToolCall("same","rdd_report_yield",args,()->a,"host-new",responses::add));
                    ServerToolTransport.ship(new ToolCall("missing","rdd_report_yield",args,()->a,null,responses::add));
                    return Map.of("packets",sends().stream().map(ExecuteToolPayload::argumentsJson).toList(),"results",List.copyOf(responses),"events",MonitoringJournal.events.toString());
                  }
                  public static Map<String,Object> ticketCapacityAndExpiry() throws Exception {
                    setup(); UUID actor=UUID.randomUUID(); actors.add(actor); List<String> responses=new ArrayList<>();
                    for(int i=0;i<256;i++) ServerToolTransport.requestYieldTicket(actor,responses::add);
                    int before=sends().size(); ServerToolTransport.requestYieldTicket(actor,responses::add);
                    int refused=responses.size(); var m=ServerToolTransport.class.getDeclaredMethod("expireYieldTickets",long.class);m.setAccessible(true);m.invoke(null,Long.MAX_VALUE);
                    int expired=responses.size(); ServerToolTransport.requestYieldTicket(actor,responses::add);
                    int after=sends().size(); ServerToolTransport.forget(actor);
                    return Map.of("before",before,"refused",refused,"expired",expired,"after",after);
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
        List<String> args = new ArrayList<>(List.of("-encoding","UTF-8","-classpath",build.toString()+java.io.File.pathSeparator+Path.of(com.google.gson.Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI()),"-d",build.toString()));
        for (var entry : sources.entrySet()) {
            Path path = build.resolve(entry.getKey().replace('.','/')+".java");
            Files.createDirectories(path.getParent()); Files.writeString(path,entry.getValue());
            args.add(path.toString());
        }
        assertNotNull(ToolProvider.getSystemJavaCompiler(), "JDK compiler required");
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)),
                "real consumers must compile before any business assertion");
        loader = new URLClassLoader(new URL[]{build.toUri().toURL(),com.google.gson.Gson.class.getProtectionDomain().getCodeSource().getLocation()},ClassLoader.getPlatformClassLoader());
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

    @Test void expiredReplyBeforeSweepMustNotReachPending() throws Exception {
        var r=run("expiredReplyWithoutSweep");
        assertEquals(1,r.get("count"));assertEquals(true,r.get("failed"),"expired reply reports failure and never hands out ticket");
    }
    @Test void ticketReplyWithNonInternalIdMustNotLeakToLog() throws Exception {
        assertFalse(run("unclassifiedTicketReply").get("log").toString().contains("signed-ticket-secret"));
    }
    @Test void realDispatcherKeepsTicketPerQueuedBatch() throws Exception {
        var r=run("dispatcherBatch");assertTrue(r.get("first").toString().contains("host-old"));
        assertTrue(r.get("second").toString().contains("host-new"));assertEquals(List.of("same:ok-old","same:ok-new"),r.get("results"));
    }
    @Test void sameModelIdUsesItsOwnFrozenYieldTicketAndMissingFails() throws Exception {
        var r=run("yieldBatchWire");var packets=(List<?>)r.get("packets");
        assertEquals(2,packets.size());assertTrue(packets.get(0).toString().contains("host-old"));
        assertTrue(packets.get(1).toString().contains("host-new"));
        assertFalse(packets.toString().contains("model-forged"));assertFalse(r.get("events").toString().contains("host-old"));
        assertTrue(r.get("results").toString().contains("yield ticket unavailable"));
    }
    @Test void ticketPendingCapacityAndActualExpiryAreBounded() throws Exception {
        var r=run("ticketCapacityAndExpiry");
        assertEquals(256,r.get("before"));assertEquals(1,r.get("refused"));
        assertEquals(257,r.get("expired"));assertEquals(257,r.get("after"));
    }

    @Test void internalTicketReplyIsOwnerScopedAndNeverEntersToolJournal() throws Exception {
        var r=run("internalTicket");
        assertEquals(List.of("secret-good"),r.get("received"));
        for (String key:List.of("wrong","good","late","cancel","logout")) assertEquals(true,r.get(key));
        assertEquals(0,r.get("events"));assertEquals("_rdd_yield_ticket",r.get("packet"));
        assertFalse(r.get("clientLog").toString().contains("secret-"));
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
