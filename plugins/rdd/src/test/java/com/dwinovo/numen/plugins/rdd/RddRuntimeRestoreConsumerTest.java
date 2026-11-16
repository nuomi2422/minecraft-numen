package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.RationaleContext;
import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.*;
import com.google.gson.Gson;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Verbatim Plugin restore consumer + real commit/codec/why/DAILY registration and handoff.
 * Only the platform shell (assets lookup, monitor/log, side persist) is replaced. No MC boot,
 * no claim of full Plugin lifecycle or transactional rollback after host publication failure.
 * Compiler/classpath failure is SETUP ERROR, never a business red. */
class RddRuntimeRestoreConsumerTest {
    @TempDir static Path compiled;
    @TempDir Path dir;
    static URLClassLoader loader;
    static Class<?> host;
    UUID id;

    @BeforeAll static void compileConsumers() throws Exception {
        Path plugin = root().resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd");
        String source = Files.readString(plugin.resolve("RddPlugin.java")).replace("\r\n", "\n");
        String side = Files.readString(plugin.resolve("SideTaskHost.java")).replace("\r\n", "\n");
        String generated = HEAD + method(source, "    public static void restoreRuntimes()")
                + method(source, "    public static void saveRuntimes()")
                + method(source, "    static synchronized StageCompletionCommit completionCommit()")
                + source.lines().filter(line -> line.contains("static boolean completionPending(UUID id)")).findFirst().orElseThrow() + "\n}\n";
        Path file = compiled.resolve("RddPlugin.java"); Files.writeString(file, generated);
        Path sideFile = compiled.resolve("SideTaskHost.java");
        Files.writeString(sideFile, SIDE_HEAD + method(side, "    static void restoreDailySpecs(")
                + method(side, "    static final class DailyRegistration {") + "}\n");
        Path monitor = compiled.resolve("RddMonitor.java");
        Files.writeString(monitor, "package com.dwinovo.numen.plugins.rdd; class RddMonitor { static void publish(String event, java.util.Map<String,?> data) {} }");
        List<String> args = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none", "-classpath",
                location(TaskChain.class) + File.pathSeparator + location(Gson.class) + File.pathSeparator
                        + location(RationaleContext.class) + File.pathSeparator + System.getProperty("java.class.path"),
                "-d", compiled.toString(), file.toString(), sideFile.toString(), monitor.toString()));
        for (String name : List.of("StageCompletionCommit", "RddRationale", "RddFactStore", "RddTaskHandoff"))
            args.add(plugin.resolve(name + ".java").toString());
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "SETUP ERROR: JDK compiler required");
        assertEquals(0, compiler.run(null, null, null, args.toArray(String[]::new)), "SETUP ERROR: real consumers must compile");
        loader = new URLClassLoader(new URL[]{compiled.toUri().toURL()}, RddRuntimeRestoreConsumerTest.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("com.dwinovo.numen.plugins.rdd.")) {
                    Class<?> found = findLoadedClass(name);
                    if (found == null) { try { found = findClass(name); } catch (ClassNotFoundException ignored) {} }
                    if (found != null) { if (resolve) resolveClass(found); return found; }
                }
                return super.loadClass(name, resolve);
            }
        };
        host = loader.loadClass("com.dwinovo.numen.plugins.rdd.RddPlugin");
        assertEquals(compiled.toUri().toURL(), host.getProtectionDomain().getCodeSource().getLocation());
    }
    @AfterAll static void close() throws Exception { if (loader != null) loader.close(); }
    @BeforeEach void configure() throws Exception { id = UUID.randomUUID(); call("configure", new Class<?>[]{Path.class}, dir); }
    @AfterEach void clearWhy() { RationaleContext.clear(id); }

    static TaskChain chain() {
        return new TaskChain(new Goal("restore", "restore original objective", List.of(
                PrimaryGoal.unexpanded("first", "iron", List.of()), PrimaryGoal.unexpanded("second", "diamond", List.of()))));
    }
    Path task(UUID uuid) { return dir.resolve("tasks").resolve(uuid + ".json"); }
    void save(UUID uuid, TaskChain chain) throws Exception {
        call("save", new Class<?>[]{UUID.class, TaskChain.class}, uuid, chain);
    }
    RddRuntime runtime(UUID uuid) throws Exception { return (RddRuntime)call("runtime", new Class<?>[]{UUID.class}, uuid); }
    void restore() throws Exception { call("restoreRuntimes", new Class<?>[0]); }

    @Test void pendingOnlyRecoversBeforeInstallingAndInvalidatesFactCache() throws Exception {
        call("pending", new Class<?>[]{UUID.class, TaskChain.class, boolean.class}, id, chain(), true);
        assertFalse(Files.exists(task(id)), "intent exists before first task projection");
        restore();
        assertEquals("second", runtime(id).chain().currentPrimary().id());
        assertFalse(Files.exists(dir.resolve("tasks").resolve(id + ".completion-pending")));
        assertEquals(false, call("cachedFacts", new Class<?>[]{UUID.class}, id));
        assertEquals(1, call("factCount", new Class<?>[]{UUID.class}, id));
    }
    @Test void failedPendingBlocksProjectedJsonButNotHealthyNeighbor() throws Exception {
        call("pending", new Class<?>[]{UUID.class, TaskChain.class, boolean.class}, id, chain(), false);
        assertTrue(Files.exists(task(id)), "task projection succeeded while facts projection failed");
        UUID neighbor = UUID.randomUUID(); save(neighbor, chain());
        restore();
        assertNull(runtime(id)); assertNotNull(runtime(neighbor));
        assertTrue(Files.exists(dir.resolve("tasks").resolve(id + ".completion-pending")));
        assertEquals(true, call("cachedFacts", new Class<?>[]{UUID.class}, id));
    }
    @Test void badNeighborAndRepeatedRestoreKeepSameLiveRuntime() throws Exception {
        save(id, chain()); Files.writeString(task(UUID.randomUUID()), "{broken");
        Files.writeString(task(UUID.randomUUID()), "null");
        restore(); RddRuntime first = runtime(id); assertNotNull(first);
        Files.writeString(task(id), "{now broken on disk"); restore();
        assertSame(first, runtime(id), "installed live owner must not be rebuilt from disk");
    }
    @Test void originalWhyAndDailyRegistrationReachInstalledOwner() throws Exception {
        TaskChain original = chain();
        original.expandCurrentPrimary(List.of(
                new Subtask("daily", "daily wheat", DetectionMode.HARD_CODED,
                        Map.of("asset_key", "minecraft:wheat", "minimum", 1), 0, 0, false, null,
                        NodeType.DAILY, RecurrenceSpec.daily(true)),
                Subtask.hardCoded("main", "iron", Map.of("asset_key", "minecraft:iron_ingot", "minimum", 1))));
        var why = new RationaleContext.Entry("owner reason", "risk", "human", "same goal");
        RationaleContext.noteCurrentChain(id, original.chainInstanceId());
        RationaleContext.set(id, why, original.planRevision(), original.chainInstanceId());
        save(id, original); RationaleContext.clear(id); restore();
        TaskChain live = runtime(id).chain();
        assertNotSame(original, live); assertEquals(original.chainInstanceId(), live.chainInstanceId());
        assertTrue(RationaleContext.applicable(id, live.planRevision(), live.chainInstanceId()));
        assertEquals(RationaleContext.renderEntry(why), RationaleContext.render(id));
        assertEquals(true, call("sideSawInstalledWhy", new Class<?>[]{UUID.class}, id),
                "runtime and applicable reason must exist before DAILY persistence");
        String spec = "daily:restore:" + Integer.toHexString("daily wheat".hashCode());
        assertEquals(true, call("dailyExists", new Class<?>[]{String.class}, spec));
        save(id, chain()); restore();
        assertSame(live, runtime(id).chain(), "owner callback remains bound to current live map");
    }
    @Test void realHandoffRemovalCannotReappearAndFailureKeepsLiveOwner() throws Exception {
        save(id, chain()); restore(); RddRuntime live = runtime(id);
        Path staging = task(id).resolveSibling(id + ".json.tmp");
        Files.createDirectory(staging); Files.writeString(staging.resolve("block"), "keep");
        assertThrows(java.io.IOException.class, () -> call("removeHandoff", new Class<?>[]{UUID.class}, id));
        assertSame(live, runtime(id)); assertTrue(Files.exists(task(id)));
        Files.delete(staging.resolve("block")); Files.delete(staging); // repair only the injected IO obstacle
        call("removeHandoff", new Class<?>[]{UUID.class}, id);
        assertFalse(Files.exists(task(id))); restore(); assertNull(runtime(id));
    }
    @Test void hostFailureAfterInstallDoesNotClaimRollbackOrReinstall() throws Exception {
        save(id, chain()); call("failSidePersist", new Class<?>[]{boolean.class}, true); restore();
        RddRuntime installed = runtime(id); assertNotNull(installed, "original catch retains installed runtime");
        call("failSidePersist", new Class<?>[]{boolean.class}, false); restore();
        assertSame(installed, runtime(id), "no automatic retry of later host work for an already installed owner");
    }

    static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        try { return host.getMethod(name, types).invoke(null, args); }
        catch (InvocationTargetException ex) { if (ex.getCause() instanceof Exception e) throw e;
            if (ex.getCause() instanceof Error e) throw e; throw ex; }
    }
    static Path root() {
        for (Path p=Path.of("").toAbsolutePath(); p!=null; p=p.getParent())
            if (Files.isRegularFile(p.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddPlugin.java"))) return p;
        throw new AssertionError("SETUP ERROR: source root missing");
    }
    static String location(Class<?> c) throws Exception { return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
    static String method(String source, String signature) {
        int start=source.indexOf(signature); assertTrue(start>=0, "SETUP ERROR: missing " + signature);
        int end=source.indexOf("\n    }", source.indexOf('{',start));
        assertTrue(end>start, "SETUP ERROR: missing closing boundary"); return source.substring(start,end+6)+"\n";
    }

    static final String HEAD = """
        package com.dwinovo.numen.plugins.rdd;
        import java.util.*; import java.nio.file.*; import java.nio.charset.StandardCharsets; import java.io.IOException;
        import com.dwinovo.numen.rdd.api.*; import com.dwinovo.numen.rdd.core.*;
        import com.dwinovo.numen.rdd.fact.CompletedFactStore;
        public class RddPlugin {
            static Path tasksDir, factsDir;
            static final Map<UUID,RddRuntime> RUNTIMES=new HashMap<>();
            static final Map<UUID,CompletedFactStore> FACTS=new HashMap<>();
            static StageCompletionCommit completionCommit;
            static final Log LOG=new Log();
            static class Log { void warn(String s,Object... a) {} void info(String s,Object... a) {} }
            static AssetRegistry assets(UUID id) { return new AssetRegistry(); }
            static void publishTaskSnapshot(UUID id,String reason) { }
            public static void configure(Path dir) {
                tasksDir=dir.resolve("tasks"); factsDir=dir.resolve("facts");
                RUNTIMES.clear(); FACTS.clear(); completionCommit=null; SideTaskHost.reset();
            }
            public static RddRuntime runtime(UUID id) { return RUNTIMES.get(id); }
            public static void save(UUID id,TaskChain c) throws IOException { completionCommit().saveRuntime(id,c); }
            public static void pending(UUID id,TaskChain c,boolean intentOnly) throws IOException {
                Files.createDirectories(tasksDir); FACTS.put(id,new CompletedFactStore());
                if(!intentOnly) Files.writeString(factsDir,"not a directory");
                StageCompletionCommit failing=new StageCompletionCommit(tasksDir,factsDir, point -> {
                    if(intentOnly && point==StageCompletionCommit.WritePoint.INTENT_WRITTEN) throw new IOException("stop after intent");
                });
                if(failing.settle(id,c,new CompletedFactStore(),"first","verified",true,1L).committed())
                    throw new AssertionError("failure fixture unexpectedly committed");
                completionCommit=new StageCompletionCommit(tasksDir,factsDir);
            }
            public static boolean cachedFacts(UUID id) { return FACTS.containsKey(id); }
            public static int factCount(UUID id) { return RddFactStore.load(factsDir,id).stageCount(); }
            public static void removeHandoff(UUID id) throws IOException {
                RddTaskHandoff.remove(tasksDir,id,() -> RUNTIMES.remove(id));
            }
            public static boolean dailyExists(String id) { return SideTaskHost.ENGINE.recurringSpec(id)!=null; }
            public static boolean sideSawInstalledWhy(UUID id) { return SideTaskHost.sawWhy.contains(id); }
            public static void failSidePersist(boolean on) { SideTaskHost.fail=on; }
        """;
    static final String SIDE_HEAD = """
        package com.dwinovo.numen.plugins.rdd;
        import java.util.*; import com.dwinovo.numen.rdd.api.*; import com.dwinovo.numen.rdd.core.*;
        import com.dwinovo.numen.rdd.side.*; import org.slf4j.Logger; import org.slf4j.LoggerFactory;
        class SideTaskHost {
            static SideTaskEngine ENGINE; static boolean fail; static final Set<UUID> sawWhy=new HashSet<>();
            static void reset() { ENGINE=new SideTaskEngine(SideTaskEvents.NOOP,t -> t);
                ENGINE.registerType(new DailyMaintenanceSideTaskType()); fail=false; sawWhy.clear(); }
            static void persist() {
                for(var entry:RddPlugin.RUNTIMES.entrySet()) {
                    var c=entry.getValue().chain();
                    if(com.dwinovo.numen.api.RationaleContext.get(entry.getKey())!=null
                       && com.dwinovo.numen.api.RationaleContext.applicable(entry.getKey(),c.planRevision(),c.chainInstanceId()))
                        sawWhy.add(entry.getKey());
                }
                if(fail) throw new IllegalStateException("host persistence failure");
            }
        """;
}
