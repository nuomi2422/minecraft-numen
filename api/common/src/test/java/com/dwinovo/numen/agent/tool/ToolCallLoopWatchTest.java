package com.dwinovo.numen.agent.tool;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Compile the real watcher/fingerprint; only monitor IO is a bounded shell. */
class ToolCallLoopWatchTest {
    @TempDir static Path output;
    private static URLClassLoader loader;
    private static Class<?> watcher;
    private static Class<?> journal;

    @BeforeAll static void compile() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("api/common/src/main/java"))) root = root.getParent();
        assertNotNull(root);
        Path source = root.resolve("api/common/src/main/java/com/dwinovo/numen/agent/tool");
        Path journalFile = output.resolve("com/dwinovo/numen/monitor/MonitoringJournal.java");
        Files.createDirectories(journalFile.getParent());
        Files.writeString(journalFile, """
                package com.dwinovo.numen.monitor;
                import java.util.*;
                public final class MonitoringJournal {
                  public static final List<Map<String,?>> events=new ArrayList<>();
                  public static MonitoringJournal get(){return new MonitoringJournal();}
                  public void publish(String category,String type,Map<String,?> data){events.add(Map.copyOf(data));}
                }
                """);
        URL gson = com.google.gson.JsonParser.class.getProtectionDomain().getCodeSource().getLocation();
        String cp = Path.of(gson.toURI()).toString();
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        int result = compiler.run(null, null, null, "-encoding", "UTF-8", "-proc:none", "-classpath", cp,
                "-d", output.toString(), source.resolve("ToolCallFingerprint.java").toString(),
                source.resolve("ToolCallLoopWatch.java").toString(), journalFile.toString());
        assertEquals(0, result);
        loader = new URLClassLoader(new URL[]{output.toUri().toURL(), gson}, ClassLoader.getPlatformClassLoader());
        watcher = loader.loadClass("com.dwinovo.numen.agent.tool.ToolCallLoopWatch");
        journal = loader.loadClass("com.dwinovo.numen.monitor.MonitoringJournal");
    }

    @AfterAll static void close() throws Exception { if (loader != null) loader.close(); }

    @BeforeEach void clearEvents() throws Exception { events().clear(); }

    @Test void equalCanonicalArgsAndReasonWarnOnceAtThirdFailure() throws Exception {
        UUID id = UUID.randomUUID();
        String a = "{\"target\":{\"x\":1,\"y\":2},\"count\":3}";
        String b = "{\"count\":3,\"target\":{\"y\":2,\"x\":1}}";
        assertNull(reject(id, "goto", "No path to 12,64,2", a));
        assertNull(reject(id, "goto", "No path to 40,70,8", b));
        String advice = reject(id, "goto", "No path to 99,64,1", a);
        assertNotNull(advice);
        assertTrue(advice.contains("3 times"));
        assertNull(reject(id, "goto", "No path to 99,64,1", b), "same issue is not re-warned");
        var events = events();
        assertEquals(1, events.size());
        assertFalse(events.get(0).toString().contains("secret-selection"));
    }

    @Test void differentCoordinatesOrReasonsAreNotTheSameFailure() throws Exception {
        UUID id = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            assertNull(reject(id, "goto", "No path", "{\"x\":" + i + ",\"y\":64,\"z\":2}"));
        }
        for (int i = 0; i < 2; i++) {
            assertNull(reject(id, "goto", "Reason " + (char)('A' + i), "{\"x\":1,\"y\":64,\"z\":2}"));
        }
        assertTrue(events().isEmpty());
    }

    @Test void malformedArgumentsAreNotEvidenceAndSuccessStillClearsOnlyItsTool() throws Exception {
        UUID id = UUID.randomUUID();
        for (int i = 0; i < 4; i++) assertNull(reject(id, "goto", "No path", "{"));
        for (int i = 0; i < 2; i++) assertNull(reject(id, "goto", "No path", "{\"x\":1}"));
        assertNotNull(reject(id, "goto", "No path", "{\"x\":1}"));
        succeed(id, "goto", "{\"x\":1}");
        for (int i = 0; i < 2; i++) assertNull(reject(id, "goto", "No path", "{\"x\":1}"));
        assertNotNull(reject(id, "goto", "No path", "{\"x\":1}"), "cleared failure starts from one again");
    }

    @Test void failedAndAsyncRunningRepliesNeverClearPriorFailureEvidence() throws Exception {
        UUID id = UUID.randomUUID();
        String args = "{\"target\":\"ore\"}";
        assertNull(result(id, "mine", args, "{\"success\":false,\"message\":\"No path\"}"));
        assertNull(result(id, "mine", args, "{\"success\":true,\"data\":{\"async\":true}}"));
        String advice = result(id, "mine", args, "{\"success\":false,\"message\":\"No path\"}");
        assertNull(advice, "the async acceptance is not a second failure");
        assertNotNull(result(id, "mine", args, "{\"success\":false,\"message\":\"No path\"}"),
                "third real failure still warns after a false reply and RUNNING acceptance");
    }

    @Test void onlyRealSuccessForTheSameCanonicalActionClearsEvidence() throws Exception {
        UUID id = UUID.randomUUID();
        assertNull(result(id, "mine", "{\"x\":1,\"y\":2}", "{\"success\":false,\"message\":\"blocked\"}"));
        assertNull(result(id, "mine", "{\"x\":2,\"y\":2}", "{\"success\":true}"));
        assertNull(result(id, "mine", "{\"y\":2,\"x\":1}", "{\"success\":false,\"message\":\"blocked\"}"));
        assertNotNull(result(id, "mine", "{\"x\":1,\"y\":2}", "{\"success\":false,\"message\":\"blocked\"}"),
                "a different successful action must not erase this action's evidence");
        assertNull(result(id, "mine", "{\"x\":1,\"y\":2}", "{\"success\":true}"));
        assertNull(result(id, "mine", "{\"x\":1,\"y\":2}", "{\"success\":false,\"message\":\"blocked\"}"));
        assertNull(result(id, "mine", "{\"x\":1,\"y\":2}", "{\"success\":false,\"message\":\"blocked\"}"));
        assertNotNull(result(id, "mine", "{\"x\":1,\"y\":2}", "{\"success\":false,\"message\":\"blocked\"}"));
    }

    @Test void cancelledAndUnknownRepliesNeitherClearNorCount() throws Exception {
        UUID id = UUID.randomUUID();
        String args = "{}";
        assertNull(result(id, "goto", args, "{\"success\":false,\"interrupted\":true,\"message\":\"stopped\"}"));
        assertNull(result(id, "goto", args, "{\"success\":true,\"interrupted\":true}"));
        assertNull(result(id, "goto", args, "{\"success\":true,\"interrupted\":\"unknown\"}"));
        assertNull(result(id, "goto", args, "not-json"));
        assertNull(result(id, "goto", args, "{\"success\":false,\"message\":\"No path\"}"));
        assertNull(result(id, "goto", args, "{\"success\":false,\"message\":\"No path\"}"));
        assertNotNull(result(id, "goto", args, "{\"success\":false,\"message\":\"No path\"}"));
    }

    private static String result(UUID id, String tool, String args, String json) throws Exception {
        return (String)watcher.getMethod("onResult", UUID.class, String.class, String.class, String.class)
                .invoke(null, id, tool, args, json);
    }

    private static String reject(UUID id, String tool, String reason, String args) throws Exception {
        return (String)watcher.getMethod("onRejected", UUID.class, String.class, String.class, String.class)
                .invoke(null, id, tool, reason, args);
    }
    private static void succeed(UUID id, String tool, String args) throws Exception {
        watcher.getMethod("onSucceeded", UUID.class, String.class, String.class).invoke(null, id, tool, args);
    }
    @SuppressWarnings("unchecked") private static List<Map<String, ?>> events() throws Exception {
        return (List<Map<String, ?>>)journal.getField("events").get(null);
    }
}
