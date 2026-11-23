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
 * 2026-11-23: externalOnly must be refused by the real inner-brain dispatcher,
 * not by a tool-name or call-id prefix. The dispatcher is compiled verbatim
 * against detached seams; the fixture is the consumer.
 *
 * RED before ToolDispatcher gains the guard: owner_tool.invoke() runs and the
 * ownerInvoked count is 1 instead of 0. GREEN after the guard rejects before
 * inFlight/invoke and lets the serial queue continue.
 *
 * LIMIT: no Minecraft, no ExecuteToolPayload/server admission, no build/deploy.
 */
class ToolDispatcherExternalOnlyTest {

    @TempDir static Path build;
    static URLClassLoader loader;
    static Class<?> fixture;

    @BeforeAll static void compile() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("api/common/src"))) root = root.getParent();
        assertNotNull(root, "repository source root required");

        Map<String, String> sources = new LinkedHashMap<>();
        for (String rel : List.of(
                "api/common/src/client/java/com/dwinovo/numen/client/agent/ToolDispatcher.java",
                "api/common/src/main/java/com/dwinovo/numen/agent/tool/ToolCall.java",
                "api/common/src/main/java/com/dwinovo/numen/agent/tool/ToolInvocation.java",
                "api/common/src/main/java/com/dwinovo/numen/agent/tool/ToolAnchor.java")) {
            String src = Files.readString(root.resolve(rel));
            String pkg = src.substring(src.indexOf("package ") + 8, src.indexOf(';')).trim();
            String name = Path.of(rel).getFileName().toString().replace(".java", "");
            sources.put(pkg + "." + name, src);
        }
        sources.put("com.dwinovo.numen.Constants",
                "package com.dwinovo.numen; public class Constants {public static final Log LOG=new Log(); public static class Log {public void info(String s,Object...a){} public void warn(String s,Object...a){}}}");
        sources.put("net.minecraft.client.player.AbstractClientPlayer",
                "package net.minecraft.client.player; public class AbstractClientPlayer {}");
        sources.put("com.google.gson.JsonObject",
                "package com.google.gson; public class JsonObject {}");
        sources.put("com.google.gson.JsonParser",
                "package com.google.gson; public class JsonParser {public static JsonParser parseString(String s){return new JsonParser();} public JsonObject getAsJsonObject(){return new JsonObject();}}");
        sources.put("com.dwinovo.numen.agent.tool.ClientToolContext",
                "package com.dwinovo.numen.agent.tool; public record ClientToolContext(net.minecraft.client.player.AbstractClientPlayer entity,java.util.UUID entityUuid) implements ToolAnchor {}");
        sources.put("com.dwinovo.numen.agent.tool.NumenTool",
                "package com.dwinovo.numen.agent.tool; public interface NumenTool {String name(); void invoke(ToolCall call); default boolean externalOnly(){return false;}}");
        sources.put("com.dwinovo.numen.agent.tool.ToolRegistry",
                "package com.dwinovo.numen.agent.tool; public class ToolRegistry {public static final java.util.Map<String,NumenTool> tools=new java.util.LinkedHashMap<>(); public static NumenTool resolve(String n){return tools.get(n);}}");
        sources.put("com.dwinovo.numen.agent.tool.ToolAliases",
                "package com.dwinovo.numen.agent.tool; public class ToolAliases {public static String resolve(String s){return s;}}");
        sources.put("com.dwinovo.numen.agent.tool.LocalToolCallLog",
                "package com.dwinovo.numen.agent.tool; public class LocalToolCallLog {public static boolean runsLocally(NumenTool t){return false;} public static void publishSynthetic(Object...a){} public static void publishResult(Object...a){} public static void publishCall(Object...a){} public static void publishRejected(Object...a){}}");
        sources.put("com.dwinovo.numen.agent.tool.ToolDisclosure",
                "package com.dwinovo.numen.agent.tool; public class ToolDisclosure {public static String notExpanded(String s){return s;}}");
        sources.put("com.dwinovo.numen.task.TaskResult",
                "package com.dwinovo.numen.task; public record TaskResult(String text){public static TaskResult fail(String s){return new TaskResult(s);} public String toJson(){return text;}}");
        sources.put("com.dwinovo.numen.api.CompanionEvent",
                "package com.dwinovo.numen.api; public class CompanionEvent {public static final Object ABORT=new Object();}");
        sources.put("com.dwinovo.numen.entity.CompanionEvents",
                "package com.dwinovo.numen.entity; public class CompanionEvents {public static void fire(Object a,Object b){}}");
        sources.put("com.dwinovo.numen.agent.tool.ServerToolTransport",
                "package com.dwinovo.numen.agent.tool; public class ServerToolTransport {public static void forget(java.util.UUID id){}}");

        sources.put("com.dwinovo.numen.client.agent.ExternalOnlyFixture", """
            package com.dwinovo.numen.client.agent;
            import com.dwinovo.numen.agent.tool.*;
            import java.util.*;
            public class ExternalOnlyFixture {
              final UUID entityUuid=UUID.randomUUID();
              int ownerInvoked,ordinaryInvoked,results,settled;
              final List<String> resultJson=new ArrayList<>();
              final ToolDispatcher dispatcher;
              public ExternalOnlyFixture(){
                ToolRegistry.tools.put("owner_tool",new NumenTool(){
                  public String name(){return "owner_tool";}
                  public boolean externalOnly(){return true;}
                  public void invoke(ToolCall call){ownerInvoked++;call.complete("owner-invoked");}
                });
                ToolRegistry.tools.put("ordinary",new NumenTool(){
                  public String name(){return "ordinary";}
                  public void invoke(ToolCall call){ordinaryInvoked++;call.complete("ordinary-ok");}
                });
                dispatcher=new ToolDispatcher(entityUuid,new ToolDispatcher.Sink(){
                  public void onResult(ToolInvocation inv,String json){results++;resultJson.add(json);}
                  public void onAllSettled(){settled++;}
                  public net.minecraft.client.player.AbstractClientPlayer entity(){return null;}
                });
              }
              void one(String id,String tool,boolean expanded){
                dispatcher.dispatch(List.of(new ToolInvocation(id,tool,"{}")),expanded?Set.of(tool):Set.of());
              }
              public static Map<String,Object> expandedOwnerOnly(){
                var f=new ExternalOnlyFixture();f.one("call-1","owner_tool",true);f.one("call-2","ordinary",true);
                return Map.of("ownerInvoked",f.ownerInvoked,"ordinaryInvoked",f.ordinaryInvoked,
                  "results",f.results,"settled",f.settled,"busy",f.dispatcher.busy(),
                  "ownerResult",f.resultJson.get(0),"ordinaryResult",f.resultJson.get(1));
              }
              public static Map<String,Object> guessedOwnerOnly(){
                var f=new ExternalOnlyFixture();f.one("call-9","owner_tool",false);
                return Map.of("ownerInvoked",f.ownerInvoked,"results",f.results,"settled",f.settled,
                  "busy",f.dispatcher.busy(),"result",f.resultJson.get(0));
              }
              public static Map<String,Object> fakeMcpOwnerOnly(){
                var f=new ExternalOnlyFixture();f.one("mcp-123","owner_tool",true);
                return Map.of("ownerInvoked",f.ownerInvoked,"results",f.results,"busy",f.dispatcher.busy(),
                  "result",f.resultJson.get(0));
              }
              public static Map<String,Object> queueContinues(){
                var f=new ExternalOnlyFixture();
                f.dispatcher.dispatch(List.of(new ToolInvocation("call-1","owner_tool","{}"),
                  new ToolInvocation("call-2","ordinary","{}")),Set.of("owner_tool","ordinary"));
                return Map.of("ownerInvoked",f.ownerInvoked,"ordinaryInvoked",f.ordinaryInvoked,
                  "results",f.results,"settled",f.settled,"busy",f.dispatcher.busy());
              }
              public static Map<String,Object> ordinaryFakeMcpId(){
                var f=new ExternalOnlyFixture();f.one("mcp-999","ordinary",true);
                return Map.of("ordinaryInvoked",f.ordinaryInvoked,"results",f.results,"busy",f.dispatcher.busy(),
                  "result",f.resultJson.get(0));
              }
            }
            """);

        List<String> args = new ArrayList<>(List.of("-d", build.toString()));
        for (var e : sources.entrySet()) {
            Path file = build.resolve(e.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getValue());
            args.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)),
                "detached dispatcher consumers must compile");
        loader = new URLClassLoader(new URL[]{build.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        fixture = loader.loadClass("com.dwinovo.numen.client.agent.ExternalOnlyFixture");
    }

    @AfterAll static void close() throws Exception { if (loader != null) loader.close(); }

    static Map<String, Object> run(String method) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) fixture.getMethod(method).invoke(null);
        return r;
    }

    @Test void expandedOwnerOnlyIsRejectedAndOrdinaryContinues() throws Exception {
        var r = run("expandedOwnerOnly");
        assertEquals(0, r.get("ownerInvoked"), "externalOnly tool must never reach invoke()");
        assertEquals(1, r.get("ordinaryInvoked"));
        assertEquals(2, r.get("results"));
        assertEquals(2, r.get("settled"));
        assertEquals(false, r.get("busy"));
        assertTrue(((String) r.get("ownerResult")).contains("external-only"));
        assertEquals("ordinary-ok", r.get("ordinaryResult"));
    }

    @Test void guessedOwnerOnlyIsRejectedBeforeNotExpanded() throws Exception {
        var r = run("guessedOwnerOnly");
        assertEquals(0, r.get("ownerInvoked"));
        assertEquals(1, r.get("results"));
        assertEquals(false, r.get("busy"));
        assertTrue(((String) r.get("result")).contains("external-only"));
    }

    @Test void fakeMcpIdDoesNotMakeOwnerOnlyCallable() throws Exception {
        var r = run("fakeMcpOwnerOnly");
        assertEquals(0, r.get("ownerInvoked"));
        assertEquals(1, r.get("results"));
        assertTrue(((String) r.get("result")).contains("external-only"));
    }

    @Test void serialQueueContinuesAfterRejection() throws Exception {
        var r = run("queueContinues");
        assertEquals(0, r.get("ownerInvoked"));
        assertEquals(1, r.get("ordinaryInvoked"));
        assertEquals(2, r.get("results"));
        assertEquals(1, r.get("settled"));
        assertEquals(false, r.get("busy"));
    }

    @Test void ordinaryToolIgnoresMcpLookingId() throws Exception {
        var r = run("ordinaryFakeMcpId");
        assertEquals(1, r.get("ordinaryInvoked"));
        assertEquals("ordinary-ok", r.get("result"));
    }

    @Test void externalActuatorPathStaysOutsideDispatcher() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("api/common/src"))) root = root.getParent();
        assertNotNull(root);
        String actuator = Files.readString(root.resolve("api/common/src/client/java/com/dwinovo/numen/api/NumenActuator.java"));
        assertTrue(actuator.contains("tool.invoke(call)"), "NumenActuator must keep its direct invoke path");
        assertFalse(actuator.contains("ToolDispatcher"), "external actuator must not route through the inner-brain dispatcher");
    }
}
