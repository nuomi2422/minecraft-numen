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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real dispatcher + fingerprint + loop watcher; only Minecraft/IO boundaries are detached. */
class ToolDispatcherLocalSupervisionTest {
    @TempDir static Path build;
    static URLClassLoader loader;
    static Class<?> fixture;

    @BeforeAll static void compile() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("api/common/src"))) root = root.getParent();
        assertNotNull(root);
        Map<String, String> sources = new LinkedHashMap<>();
        for (String rel : List.of(
                "api/common/src/client/java/com/dwinovo/numen/client/agent/ToolDispatcher.java",
                "api/common/src/main/java/com/dwinovo/numen/agent/tool/ToolCall.java",
                "api/common/src/main/java/com/dwinovo/numen/agent/tool/ToolInvocation.java",
                "api/common/src/main/java/com/dwinovo/numen/agent/tool/ToolAnchor.java",
                "api/common/src/main/java/com/dwinovo/numen/agent/tool/ToolCallFingerprint.java",
                "api/common/src/main/java/com/dwinovo/numen/agent/tool/ToolCallLoopWatch.java",
                "api/common/src/main/java/com/dwinovo/numen/agent/tool/ToolCallObserver.java",
                "api/common/src/main/java/com/dwinovo/numen/task/TaskResult.java")) {
            String src = Files.readString(root.resolve(rel));
            String pkg = src.substring(src.indexOf("package ") + 8, src.indexOf(';')).trim();
            sources.put(pkg + "." + Path.of(rel).getFileName().toString().replace(".java", ""), src);
        }
        sources.put("com.dwinovo.numen.api.Internal", "package com.dwinovo.numen.api; public @interface Internal {}");
        sources.put("com.dwinovo.numen.Constants", "package com.dwinovo.numen; public class Constants {public static final Log LOG=new Log(); public static class Log {public void info(String s,Object...a){} public void warn(String s,Object...a){}}}");
        sources.put("net.minecraft.client.player.AbstractClientPlayer", "package net.minecraft.client.player; public class AbstractClientPlayer {}");
        sources.put("com.dwinovo.numen.agent.tool.ClientToolContext", "package com.dwinovo.numen.agent.tool; public record ClientToolContext(net.minecraft.client.player.AbstractClientPlayer entity,java.util.UUID entityUuid) implements ToolAnchor {}");
        sources.put("com.dwinovo.numen.agent.tool.NumenTool", "package com.dwinovo.numen.agent.tool; public interface NumenTool {String name(); void invoke(ToolCall call); default boolean externalOnly(){return false;}}");
        sources.put("com.dwinovo.numen.agent.tool.ToolRegistry", "package com.dwinovo.numen.agent.tool; public class ToolRegistry {public static final java.util.Map<String,NumenTool> tools=new java.util.LinkedHashMap<>(); public static NumenTool resolve(String n){return tools.get(n);}}");
        sources.put("com.dwinovo.numen.agent.tool.ToolAliases", "package com.dwinovo.numen.agent.tool; public class ToolAliases {public static String resolve(String s){return s;}}");
        sources.put("com.dwinovo.numen.agent.tool.LocalToolCallLog", "package com.dwinovo.numen.agent.tool; public class LocalToolCallLog {public static boolean runsLocally(NumenTool t){return t!=null&&t.name().startsWith(\"local_\");} public static void publishSynthetic(Object...a){} public static void publishResult(Object...a){} public static void publishCall(Object...a){} public static void publishRejected(Object...a){}}");
        sources.put("com.dwinovo.numen.agent.tool.ToolDisclosure", "package com.dwinovo.numen.agent.tool; public class ToolDisclosure {public static String notExpanded(String s){return s;}}");
        sources.put("com.dwinovo.numen.api.CompanionEvent", "package com.dwinovo.numen.api; public class CompanionEvent {public static final Object ABORT=new Object();}");
        sources.put("com.dwinovo.numen.entity.CompanionEvents", "package com.dwinovo.numen.entity; public class CompanionEvents {public static void fire(Object a,Object b){}}");
        sources.put("com.dwinovo.numen.agent.tool.ServerToolTransport", "package com.dwinovo.numen.agent.tool; public class ServerToolTransport {public static void forget(java.util.UUID id){}}");
        sources.put("com.dwinovo.numen.monitor.MonitoringJournal", "package com.dwinovo.numen.monitor; public final class MonitoringJournal {public static MonitoringJournal get(){return new MonitoringJournal();} public void publish(String c,String t,Map<String,?> d){}}".replace("Map<String,?>", "java.util.Map<String,?>"));
        sources.put("com.dwinovo.numen.client.agent.LocalSupervisionFixture", """
            package com.dwinovo.numen.client.agent;
            import com.dwinovo.numen.agent.tool.*;
            import com.dwinovo.numen.task.TaskResult;
            import java.util.*;
            public class LocalSupervisionFixture {
              public static final List<String> observed=new ArrayList<>();
              public static void install(){
                ToolRegistry.tools.put("local_fail",new NumenTool(){public String name(){return "local_fail";} public void invoke(ToolCall c){c.complete(TaskResult.fail("No path").toJson());}});
                ToolRegistry.tools.put("local_success",new NumenTool(){public String name(){return "local_success";} public void invoke(ToolCall c){c.complete(TaskResult.ok("done").toJson());}});
                ToolRegistry.tools.put("server_fail",new NumenTool(){public String name(){return "server_fail";} public void invoke(ToolCall c){c.complete(TaskResult.fail("server No path").toJson());}});
                ToolCallObserver.installRawArguments((id,call,tool,args)->observed.add(id+"|"+call+"|"+tool+"|"+args));
              }
              public static Map<String,Object> run(String tool,String args,int calls){
                observed.clear(); UUID id=UUID.randomUUID(); List<String> results=new ArrayList<>();
                var dispatcher=new ToolDispatcher(id,new ToolDispatcher.Sink(){
                  public void onResult(ToolInvocation inv,String json){results.add(json);}
                  public void onAllSettled(){}
                  public net.minecraft.client.player.AbstractClientPlayer entity(){return null;}
                });
                for(int i=0;i<calls;i++) dispatcher.dispatch(List.of(new ToolInvocation("call-"+i,tool,args)),Set.of(tool));
                return Map.of("id",id,"results",List.copyOf(results),"observed",List.copyOf(observed));
              }
            }
            """);
        URL gson = com.google.gson.JsonParser.class.getProtectionDomain().getCodeSource().getLocation();
        String cp = Path.of(gson.toURI()).toString();
        List<String> args = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none", "-classpath", cp, "-d", build.toString()));
        for (var entry : sources.entrySet()) {
            Path file = build.resolve(entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
            args.add(file.toString());
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        assertEquals(0, compiler.run(null, null, null, args.toArray(String[]::new)), "real dispatcher consumer must compile");
        loader = new URLClassLoader(new URL[]{build.toUri().toURL(), gson}, ClassLoader.getPlatformClassLoader());
        fixture = loader.loadClass("com.dwinovo.numen.client.agent.LocalSupervisionFixture");
        fixture.getMethod("install").invoke(null);
    }

    @AfterAll static void close() throws Exception {
        if (loader == null) return;
        Class<?> observer = loader.loadClass("com.dwinovo.numen.agent.tool.ToolCallObserver");
        observer.getMethod("clear").invoke(null);
        loader.close();
    }

    @SuppressWarnings("unchecked") static Map<String, Object> run(String tool, String args, int calls) throws Exception {
        return (Map<String, Object>) fixture.getMethod("run", String.class, String.class, int.class).invoke(null, tool, args, calls);
    }

    @Test void localFailureWarnsOnThirdRealResultAndObserverFiresOncePerInvoke() throws Exception {
        var result = run("local_fail", "{\"target\":\"ore\"}", 3);
        List<String> replies = (List<String>) result.get("results");
        assertFalse(replies.get(0).contains("loop-watch"));
        assertFalse(replies.get(1).contains("loop-watch"));
        assertTrue(replies.get(2).contains("loop-watch"));
        assertEquals(3, ((List<?>) result.get("observed")).size());
    }

    @Test void failedAndRunningRepliesKeepEvidenceButRealSuccessClearsSameAction() throws Exception {
        UUID id = UUID.randomUUID();
        Class<?> watcher = loader.loadClass("com.dwinovo.numen.agent.tool.ToolCallLoopWatch");
        String args = "{\"x\":1}";
        var rejected = watcher.getMethod("onRejected", UUID.class, String.class, String.class, String.class);
        assertNull(rejected.invoke(null, id, "local_fail", "No path", args));
        assertNull(watcher.getMethod("onResult", UUID.class, String.class, String.class, String.class)
                .invoke(null, id, "local_fail", args, "{\"success\":false,\"message\":\"No path\"}"));
        assertNull(watcher.getMethod("onResult", UUID.class, String.class, String.class, String.class)
                .invoke(null, id, "local_fail", args, "{\"success\":true,\"data\":{\"async\":true}}"));
        assertNotNull(watcher.getMethod("onResult", UUID.class, String.class, String.class, String.class)
                .invoke(null, id, "local_fail", args, "{\"success\":false,\"message\":\"No path\"}"));
        assertNull(watcher.getMethod("onResult", UUID.class, String.class, String.class, String.class)
                .invoke(null, id, "local_fail", "{\"x\":2}", "{\"success\":true}"));
        assertNull(watcher.getMethod("onResult", UUID.class, String.class, String.class, String.class)
                .invoke(null, id, "local_fail", args, "{\"success\":false,\"message\":\"No path\"}"));
    }

    @Test void serverResultIsNotCountedAgainByClientDispatcher() throws Exception {
        var result = run("server_fail", "{}", 3);
        assertTrue(((List<?>) result.get("observed")).isEmpty(), "server path owns observation");
        for (Object reply : (List<?>) result.get("results")) assertFalse(reply.toString().contains("loop-watch"));
    }
}
