package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Compile the complete current owner and real facade excerpts with a real TaskChain.
 * Only NeoForge plugin binding, API enqueue, runtime projection and monitor IO are bounded shells.
 * This is not a Minecraft callback, event-bus initialization or client-delivery acknowledgement test.
 */
class RddSupervisionNotificationsTest {
    @TempDir static Path output;
    private static URLClassLoader loader;
    private static Class<?> harness;
    private static final String PKG = "com.dwinovo.numen.plugins.rdd.";

    private static Path root() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent())
            if (Files.isDirectory(p.resolve("plugins/rdd/src/main/java"))) return p;
        throw new AssertionError("repository source missing");
    }
    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature);
        int open = source.indexOf('{', start), depth = 0;
        for (int i = open; i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            if (source.charAt(i) == '}' && --depth == 0) return source.substring(start, i + 1);
        }
        throw new AssertionError("unbalanced method " + signature);
    }
    @BeforeAll static void compileRealOwnerAndFacades() throws Exception {
        Path src = root().resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd");
        String plugin = Files.readString(src.resolve("RddPlugin.java"));
        StringBuilder facade = new StringBuilder();
        for (String signature : List.of("    public static boolean nudge(UUID", "    static boolean nudgeAllowed(UUID",
                "    static void clearNudgeThrottle(UUID", "    static void clearNudgeThrottleAll()",
                "    static boolean kickoffNudge(UUID companionId, TaskChain chain)",
                "    static boolean kickoffNudge(UUID companionId, TaskChain chain,",
                "    static void markKickoffHandled(UUID")) facade.append(method(plugin, signature)).append("\n");
        Path host = output.resolve("RddPlugin.java");
        Files.writeString(host, SHELL + facade + "\n}\n" + MONITOR);
        Path owner = output.resolve("RddSupervisionNotifications.java");
        Files.writeString(owner, Files.readString(src.resolve("RddSupervisionNotifications.java")));
        Path runtime = output.resolve("RddRuntime.java");
        Files.writeString(runtime, "package com.dwinovo.numen.rdd.core; public class RddRuntime { private final TaskChain chain; public RddRuntime(TaskChain c){chain=c;} public TaskChain chain(){return chain;} public java.util.Map<String,Object> snapshot(){return java.util.Map.of(\"fixture\",true);} }");
        Path token = output.resolve("WorldContextStamp.java");
        Files.writeString(token, "package com.dwinovo.numen.api; public class WorldContextStamp { public record Token(String id){} }");
        String cp = Path.of(TaskChain.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator + Path.of(org.slf4j.LoggerFactory.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator + Path.of(com.google.gson.Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator + System.getProperty("java.class.path");
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        assertEquals(0, compiler.run(null, null, null, "-encoding", "UTF-8", "-proc:none", "-classpath", cp,
                "-d", output.toString(), host.toString(), owner.toString(), runtime.toString(), token.toString()),
                "compile current production methods before business assertions");
        loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, RddSupervisionNotificationsTest.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith(PKG) || name.equals("com.dwinovo.numen.rdd.core.RddRuntime")
                        || name.startsWith("com.dwinovo.numen.api.WorldContextStamp")) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) try { c = findClass(name); } catch (ClassNotFoundException ignored) { }
                    if (c != null) { if (resolve) resolveClass(c); return c; }
                }
                return super.loadClass(name, resolve);
            }
        };
        harness = loader.loadClass(PKG + "RddPlugin");
        assertEquals(output.toUri().toURL(), harness.getProtectionDomain().getCodeSource().getLocation());
        assertEquals(output.toUri().toURL(), loader.loadClass(PKG + "RddSupervisionNotifications")
                .getProtectionDomain().getCodeSource().getLocation());
    }
    @AfterAll static void close() throws Exception { if (loader != null) loader.close(); }
    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        var m = harness.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(null, args);
    }
    private static Object field(String name) throws Exception { var f = harness.getDeclaredField(name); f.setAccessible(true); return f.get(null); }
    @SuppressWarnings("unchecked") private static List<String> trace() throws Exception { return (List<String>) field("trace"); }
    @BeforeEach void reset() throws Exception { call("reset", new Class<?>[0]); }
    private static TaskChain chain(String step, boolean running) {
        TaskChain chain = new TaskChain(new Goal("g", "fixture", List.of(new PrimaryGoal("p", "phase",
                List.of(Subtask.hardCoded(step, "execute " + step, Map.of("asset_key", "minecraft:dirt", "minimum", 1)))))));
        if (running) assertTrue(chain.activateCurrentWithSnapshot(PlanningAssetSnapshot.from(Map.of(), new AssetRegistry())));
        return chain;
    }
    private static void bind(UUID id, TaskChain chain) throws Exception { call("bindFixture", new Class<?>[]{UUID.class, TaskChain.class}, id, chain); }
    private static boolean send(UUID id, String message) throws Exception { return (boolean) call("nudge", new Class<?>[]{UUID.class,String.class}, id,message); }
    private static boolean allowed(UUID id) throws Exception { return (boolean) call("nudgeAllowed",new Class<?>[]{UUID.class},id); }
    private static boolean kickoff(UUID id, TaskChain chain) throws Exception { return (boolean) call("kickoffNudge",new Class<?>[]{UUID.class,TaskChain.class},id,chain); }
    private static void mode(String value) throws Exception { call("mode",new Class<?>[]{String.class},value); }

    @Test void successUsesSystemEventThenAccountsAndReadOnlyChecksDoNotSpend() throws Exception {
        UUID id=UUID.randomUUID(); bind(id,chain("a",true));
        assertTrue(allowed(id)); assertTrue(allowed(id)); assertTrue(trace().isEmpty());
        assertTrue(send(id,"work")); assertEquals("enqueueSystem:work",trace().get(0));
        assertTrue(trace().contains("event:supervisor_output:null"));
        assertFalse(allowed(id)); assertFalse(send(id,"repeat"));
        assertTrue(trace().contains("event:nudge_throttled:null"));
        assertEquals(1L,trace().stream().filter(s->s.startsWith("enqueueSystem:")).count());
    }
    @Test void pauseNullApiAndInvalidMessageNeverQueueOrSpend() throws Exception {
        UUID id=UUID.randomUUID(); bind(id,chain("a",true)); mode("pause");
        assertFalse(send(id,"work")); assertFalse(allowed(id));
        mode("no_api"); assertFalse(send(id,"work"));
        mode("normal"); assertFalse(send(null,"work")); assertFalse(send(id," ")); assertFalse(send(id,null));
        assertTrue(trace().isEmpty()); assertTrue(allowed(id));
    }
    @Test void enqueueThrowDoesNotSpendButObservationThrowAfterEnqueueRetainsOriginalAccounting() throws Exception {
        UUID id=UUID.randomUUID(); bind(id,chain("a",true)); mode("enqueue_throw");
        assertFalse(send(id,"work")); assertTrue(allowed(id));
        mode("observation_throw"); assertFalse(send(id,"accepted but observation fails"));
        assertFalse(allowed(id),"original post-enqueue failure returns false without undoing successful queue accounting");
    }
    @Test void throttleBucketsAndCompanionClearDoNotCrossStatesOrCompanions() throws Exception {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(); bind(a,chain("a",true)); bind(b,chain("a",true));
        assertTrue(send(a,"a")); assertTrue(allowed(b)); bind(a,chain("b",true)); assertTrue(allowed(a));
        assertTrue(send(a,"b")); assertTrue(send(b,"other"));
        call("clearNudgeThrottle",new Class<?>[]{UUID.class},a); assertTrue(allowed(a)); assertFalse(allowed(b));
        bind(a,chain("a",true)); assertTrue(allowed(a));
        call("clearNudgeThrottleAll",new Class<?>[0]); assertTrue(allowed(b));
    }
    @Test void kickoffDeduplicatesOnlyAfterSuccessAndNewStepCanClearThrottle() throws Exception {
        UUID id=UUID.randomUUID(); TaskChain a=chain("a",true); bind(id,a); mode("enqueue_throw");
        assertFalse(kickoff(id,a)); mode("normal"); assertTrue(kickoff(id,a)); assertFalse(kickoff(id,a));
        TaskChain b=chain("b",true); bind(id,b); assertTrue(send(id,"preexisting throttle"));
        assertTrue(kickoff(id,b),"new kickoff clears companion throttle before successful send");
        TaskChain c=chain("c",true); bind(id,c); call("markKickoffHandled",new Class<?>[]{UUID.class,String.class},id,"c");
        assertFalse(kickoff(id,c));
    }
    @Test void kickoffGuardsSkipOncePerStepAndTokenIsSuppliedNotReconstructed() throws Exception {
        UUID id=UUID.randomUUID(); TaskChain a=chain("a",false); bind(id,a); mode("pause");
        assertFalse(kickoff(id,a)); mode("normal"); assertFalse(kickoff(id,a));
        assertEquals(1L,trace().stream().filter(s->s.startsWith("event:goal_kickoff_skipped")).count());
        assertTrue(a.activateCurrentWithSnapshot(PlanningAssetSnapshot.from(Map.of(),new AssetRegistry())));
        mode("body"); assertFalse(kickoff(id,a)); mode("normal");
        assertEquals(true, call("kickoffWithToken",new Class<?>[]{UUID.class,TaskChain.class,String.class},id,a,"producer"));
        assertTrue(trace().contains("event:goal_kickoff:producer"));
        assertTrue(trace().contains("event:supervisor_output:null"));
    }
    @Test void sentOnlyCleanupRetainsOriginalSkipAndThrottleState() throws Exception {
        UUID id=UUID.randomUUID(); TaskChain a=chain("a",true); bind(id,a); assertTrue(kickoff(id,a));
        assertFalse(kickoff(id,a)); long skips=trace().stream().filter(s->s.startsWith("event:goal_kickoff_skipped")).count();
        call("clearSentFixture",new Class<?>[]{UUID.class},id); assertFalse(allowed(id));
        mode("body"); assertFalse(kickoff(id,a));
        assertEquals(skips,trace().stream().filter(s->s.startsWith("event:goal_kickoff_skipped")).count(),"skip map never cleared in original lifecycle");
    }
    @Test void globalSentClearDoesNotClearThrottleOrSkipAndNoRuntimeUsesNoneBucket() throws Exception {
        UUID id=UUID.randomUUID(); TaskChain a=chain("a",true); bind(id,a);
        assertTrue(kickoff(id,a)); assertFalse(kickoff(id,a));
        long skips=trace().stream().filter(s->s.startsWith("event:goal_kickoff_skipped")).count();
        call("clearSentAllFixture",new Class<?>[0]); assertFalse(allowed(id));
        mode("body"); assertFalse(kickoff(id,a));
        assertEquals(skips,trace().stream().filter(s->s.startsWith("event:goal_kickoff_skipped")).count());
        UUID noRuntime=UUID.randomUUID(); mode("normal"); assertTrue(send(noRuntime,"none bucket"));
        assertFalse(allowed(noRuntime)); call("clearNudgeThrottle",new Class<?>[]{UUID.class},noRuntime);
        assertTrue(allowed(noRuntime));
    }
    private static final String MONITOR = """
        class RddMonitor {
            static void publish(String type,Map<String,Object> data){publish(type,data,null);}
            static void publish(String type,Map<String,Object> data,com.dwinovo.numen.api.WorldContextStamp.Token token){
                RddPlugin.trace.add("event:"+type+":"+(token==null?"null":token.id()));
            }
        }
        """;
    private static final String SHELL = """
        package com.dwinovo.numen.plugins.rdd;
        import java.util.*;
        import com.dwinovo.numen.rdd.core.*;
        public class RddPlugin {
            static boolean supervision=true, body=false, observationFails=false;
            static final List<String> trace=new ArrayList<>();
            static final Map<UUID,RddRuntime> runtimes=new HashMap<>();
            static FakeNumenApi api=new FakeNumenApi();
            static class FakeNumenApi {
                boolean fails;
                void enqueueSystem(UUID id,String message) { if(fails) throw new IllegalStateException("queue"); trace.add("enqueueSystem:"+message); }
            }
            static FakeNumenApi notificationApi(){return api;}
            static boolean supervisionEnabled(){return supervision;}
            static boolean bodySubmissionEnabled(){return body;}
            static RddRuntime runtime(UUID id){return runtimes.get(id);}
            static Map<String,Object> observationData(UUID id){if(observationFails)throw new IllegalStateException("observation"); return new LinkedHashMap<>();}
            static void bindFixture(UUID id,TaskChain chain){runtimes.put(id,new RddRuntime(chain));}
            static void mode(String m){supervision=!m.equals("pause");body=m.equals("body");observationFails=m.equals("observation_throw");api=m.equals("no_api")?null:new FakeNumenApi();if(api!=null)api.fails=m.equals("enqueue_throw");}
            static void reset() throws Exception {
                trace.clear();runtimes.clear();mode("normal");
                for(String name:List.of("LAST_NUDGE_AT","KICKOFF_SENT","KICKOFF_SKIP_LOGGED")){
                    var f=RddSupervisionNotifications.class.getDeclaredField(name);f.setAccessible(true);((Map<?,?>)f.get(null)).clear();
                }
            }
            static void clearSentFixture(UUID id){RddSupervisionNotifications.clearKickoff(id);}
            static void clearSentAllFixture(){RddSupervisionNotifications.clearKickoffAll();}
            static boolean kickoffWithToken(UUID id,TaskChain c,String token){return kickoffNudge(id,c,new com.dwinovo.numen.api.WorldContextStamp.Token(token));}
        """;
}
