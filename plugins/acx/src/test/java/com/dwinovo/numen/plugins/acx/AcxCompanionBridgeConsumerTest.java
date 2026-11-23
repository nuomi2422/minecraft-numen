package com.dwinovo.numen.plugins.acx;

import com.google.gson.Gson;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiles whole production Bridge/FacadeTools and pure api/core sources unchanged.
 * Plugin binding/access methods are mechanically extracted; initialization, host thread,
 * Numen bodies and the save backend are fixtures. Replay cases use the production
 * task slot, dispatch and persistence code. The complete production
 * AcxHostGate supplies its real queue state, future and exception propagation. The fixed seven-case
 * baseline is kept separately; these integration additions require the bound plugin API.
 * Setup errors are not business RED.
 * Real PortToolAdapter consumers additionally exercise invoke, accepted receipts and resume probes.
 * MC bootstrap, real body lifecycle and HTTP remain boundary fixtures.
 */
class AcxCompanionBridgeConsumerTest {
    @TempDir static Path temp;
    static URLClassLoader loader;
    static Class<?> harness;
    static final String PREFIX = "com.dwinovo.numen.";

    @BeforeAll static void compileConsumers() throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while(root!=null && !Files.isDirectory(root.resolve("plugins/acx/src/main/java")))root=root.getParent();
        if(root==null)throw new IllegalStateException("SETUP ERROR: source root missing");
        Path main=root.resolve("plugins/acx/src/main/java/com/dwinovo/numen");
        List<String> args=new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-classpath",
                Path.of(Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                "-sourcepath",temp.toString(),"-d",temp.toString()));
        for(String dir:List.of("acx/api","acx/core"))try(var files=Files.list(main.resolve(dir))){
            for(Path file:files.filter(p->p.toString().endsWith(".java")).toList())
                add(args,PREFIX+dir.replace('/','.')+"."+file.getFileName().toString().replace(".java",""),Files.readString(file));
        }
        for(String name:List.of("NumenToolBridgeX","AcxFacadeTools","AcxRegistry","AcxBoundHostCall","AcxHostGate","AcxMod")){
            Path file=main.resolve("plugins/acx/"+name+".java");
            if(Files.exists(file))add(args,PREFIX+"plugins.acx."+name,Files.readString(file));
        }
        String plugin=Files.readString(main.resolve("plugins/acx/AcxPlugin.java"));
        StringBuilder bindings=new StringBuilder();
        for(String signature:List.of("    public void bindCompanion(","    public NumenPlayer currentCompanion(",
                "    AcxSessionManager.Session sessionFor(","    static com.dwinovo.numen.acx.api.AcxRunBinding bindingFor(",
                "    void onServerStopped("))
            if(plugin.contains(signature))bindings.append(method(plugin,signature)).append(System.lineSeparator());
        if (!plugin.contains("executor = newRunExecutor();")
                || !plugin.contains("sessions = new AcxSessionManager(runner, executor);")) {
            throw new IllegalStateException("SETUP ERROR: production executor wiring missing");
        }
        bindings.append(method(plugin, "    static ExecutorService newRunExecutor("));
        add(args,PREFIX+"plugins.acx.AcxPlugin",PLUGIN_HEADER+bindings+"}");
        AcxTaskReplayFixture.addConsumers(args, root);
        add(args,PREFIX+"agent.tool.TrustedYieldTool",Files.readString(root.resolve("api/common/src/main/java/com/dwinovo/numen/agent/tool/TrustedYieldTool.java")));
        for(var e:IO.entrySet())add(args,e.getKey(),e.getValue());
        for(var e:AcxTaskReplayFixture.IO.entrySet())add(args,e.getKey(),
                e.getKey().equals(PREFIX+"api.WorldContextStamp")
                        ? e.getValue().replace("server == null || epoch <= 0 ? null :",
                                "epoch == -1 ? new Token(Map.of(\"server_session\",\"fixture-session\"),1) : "
                                + "epoch == -2 ? new Token(Map.of(\"world_id\",\"fixture-world\"),1) : "
                                + "server == null || epoch <= 0 ? new Token(null, 0) :")
                        : e.getValue());
        for(var e:STOP_IO.entrySet())add(args,e.getKey(),e.getValue());
        add(args,PREFIX+"plugins.acx.BindingHarness",HARNESS.replace("// REPLAY_SCENARIOS", AcxTaskReplayFixture.SCENARIOS));
        var compiler=ToolProvider.getSystemJavaCompiler();
        if(compiler==null)throw new IllegalStateException("SETUP ERROR: JDK compiler missing");
        var diagnostics=new ByteArrayOutputStream();
        int exit=compiler.run(null,diagnostics,diagnostics,args.toArray(String[]::new));
        if(exit!=0)throw new IllegalStateException("SETUP ERROR: consumer compilation exit="+exit+"\n"+diagnostics.toString(StandardCharsets.UTF_8));
        loader=new URLClassLoader(new URL[]{temp.toUri().toURL()},AcxCompanionBridgeConsumerTest.class.getClassLoader()){
            @Override protected synchronized Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
                if(name.startsWith(PREFIX)||name.startsWith("net.minecraft.")||name.startsWith("net.neoforged.")
                         ||name.equals("org.slf4j.LoggerFactory")||name.startsWith("org.slf4j.LoggerFactory$")){
                    Class<?> c=findLoadedClass(name);if(c==null)c=findClass(name);if(resolve)resolveClass(c);return c;
                }
                return super.loadClass(name,resolve);
            }
        };
        harness=loader.loadClass(PREFIX+"plugins.acx.BindingHarness");
    }
    @AfterAll static void close() throws Exception {if(loader!=null)loader.close();}
    static Object call(String name) throws Exception {
        try{return harness.getMethod(name).invoke(null);}
        catch(java.lang.reflect.InvocationTargetException e){throw new RuntimeException(e.getCause());}
    }
    @Test void syncTaskWithoutCallbackUsesOnlyItsExactTerminalRecord() throws Exception {
        assertEquals("COMPLETED:1", call("syncTerminalWithoutCallback"));
    }
    @Test void failedSyncTerminalCarriesItsOriginalOccludedResult() throws Exception {
        assertEquals("FAILED:1:aim 21417,51,22143 is blocked from here — the crosshair lands on stone at 21415,52,22145 instead. break_block that blocker, or goto the target's open side, then retry.",
                call("failedSyncTerminalWithoutCallback"));
    }
    @Test void cancelledSyncTerminalCarriesItsOriginalResult() throws Exception {
        assertEquals("FAILED:1:宿主任务被中断: owner stopped it", call("cancelledSyncTerminalWithoutCallback"));
    }
    @Test void timeoutSyncTerminalCarriesItsOriginalResult() throws Exception {
        assertEquals("FAILED:1:宿主任务超时: deadline", call("timeoutSyncTerminalWithoutCallback"));
    }
    @Test void foreignSyncTerminalNeverCompletesOrRedispatchesOriginalCall() throws Exception {
        assertEquals("PENDING:1", call("foreignSyncTerminalWithoutCallback"));
        assertEquals("PENDING:1", call("unknownIdentitySyncTerminalWithoutCallback"));
        assertEquals("PENDING:1", call("missingWorldSyncTerminalWithoutCallback"));
        assertEquals("PENDING:1", call("missingServerSyncTerminalWithoutCallback"));
    }

    @Test void waitingRunDoesNotBlockIndependentComputation() throws Exception {
        assertTrue((Boolean) call("independentRunsProgress"), "computation must finish while another run is waiting");
    }

    @Test void competingRunWaitsAndRechecksTheOriginalBodySlotOnResume() throws Exception {
        assertEquals(List.of("PAUSED", "body_busy", true, true, "body_busy", true,
                        true, true, "PAUSED", "async_accepted", true),
                call("competingRunWaits"));
    }

    @Test void nestedCancelLateReceiptCannotReleaseNewOwner() throws Exception {
        assertEquals(List.of(true, true, true, true, true, true, 2), call("nestedCancelLateReceiptCannotReleaseNewOwner"));
    }

    @Test void nestedBodySuccessReachesParent() throws Exception {
        assertEquals(List.of("SUCCESS", 1, List.of(4)), call("nestedBodySuccessReachesParent"));
    }

    @Test void cooperativeCancelAndLateReceiptKeepTheOccupiedBodyUnavailable() throws Exception {
        assertEquals(List.of(true, true, true, "PAUSED", 0, true, true, true,
                        "PAUSED", "body_busy", true, true, true, 0),
                call("cancelledRunLateReceiptKeepsBodyBusy"));
    }

    @Test void sameBodyCanObserveWhilePreviousRunWaitsForDrops() throws Exception {
        assertEquals(List.of(true, true, true, 1, true, true, true),
                call("observeWhileMineWaits"));
    }

    @Test void callerOwnedSyncAndCurrentActionsShareBodyAdmission() throws Exception {
        assertEquals(List.of("BODY_BUSY", true, "body_busy", true), call("syncAndCurrentConflict"));
    }

    @Test void delayedBodyRefusalConfirmsNoSecondTaskWasAdmitted() throws Exception {
        assertEquals(List.of(true, "BODY_BUSY", true, true), call("delayedBodyRefusal"));
    }

    @Test void lateAcceptanceCanOnlyBeClaimedByTheOriginalRun() throws Exception {
        assertEquals(List.of(true, false, "A", "ACCEPTED", "late-receipt", true), call("lateAcceptance"));
    }
    @Test void otherCompanionsStatusDoesNotRetargetQueuedRun()throws Exception{
        assertEquals("A",call("statusInterleave"));
    }
    @Test void libraryRequestDoesNotRetargetQueuedRun()throws Exception{
        assertEquals("A",call("libraryInterleave"));
    }
    @Test void rejectedRequestDoesNotRetargetQueuedRun()throws Exception{
        assertEquals("A",call("invalidInterleave"));
    }
    @Test void delayedCallbackRemainsWithItsOriginalBody()throws Exception{
        assertEquals("A:A",call("callbackInterleave"));
    }
    @Test void terminalProbeReturnsOnlyTheExactOriginalResult() throws Exception {
        assertEquals(List.of("DONE", 42, "UNKNOWN", true, "UNKNOWN", true), call("exactTerminalPayload"));
    }
    @Test void probeRemainsWithOriginalOwnerAfterOtherRequest()throws Exception{
        assertEquals("DONE",call("probeInterleave"));
    }
    @Test void queuedHostCancelSendsNoBodyAction()throws Exception{
        assertEquals(0,call("cancelAtGate"));
    }
    @Test void replacementBodyAtGateDoesNotReceiveOldRun()throws Exception{
        assertEquals(0,call("replaceAtGate"));
    }
    @Test void trustedYieldStaysWithOriginalRunAndSnapshot() throws Exception {
        assertEquals("COMPLETED,FAILED,FAILED", call("yieldOriginalRunBinding"));
    }
    @Test void actualAdapterKeepsQueuedDispatchWithOriginalCompanion()throws Exception{
        assertEquals("A",call("adapterStatusInterleave"));
    }
    @Test void actualAdapterResumesOriginalAcceptedTaskWithoutRedispatch()throws Exception{
        assertEquals("PAUSED:SUCCESS:A:0",call("adapterAcceptedResume"));
    }
    @Test void actualAdapterSendsNoBodyActionWhenCancelledAtHostGate()throws Exception{
        assertEquals(0,call("adapterCancelAtGate"));
    }
    @Test void actualAdapterResumesQueuedCancellationWithOneBodyDispatch()throws Exception{
        assertEquals("PAUSED:SUCCESS:A",call("adapterQueuedCancelResume"));
    }
    @Test void hostStopRejectsEmptyAndOtherRunsTaskIds()throws Exception{
        assertEquals("FAILED:0,FAILED:0",call("stopUnownedTask"));
    }
    @Test void hostStopRejectsOriginalTaskWhenCurrentSlotHasChanged()throws Exception{
        assertEquals("FAILED:0",call("stopReplacedTaskSlot"));
    }
    @Test void hostStopDispatchesOnlyAcceptedTaskStillInOriginalSlot()throws Exception{
        assertEquals("COMPLETED:1",call("stopExactOwnedTask"));
    }
    @Test void interruptedGateCannotDispatchAfterBodyLookupResumes() throws Exception {
        assertEquals(List.of("FAILED", 0, 0), call("interruptedDuringOriginalBodyCheck"));
    }
    @Test void actualHostGateCancellationPausesAndResumesWithoutPriorDispatch()throws Exception{
        assertEquals("PAUSED:0:true:SUCCESS:A:0",call("actualGateCancelResume"));
    }
    @Test void cancellationExceptionFromAnAlreadyCalledBodyRetainsOriginalReply() throws Exception {
        assertLateReplyConsumedOnce("lateReplyReplayWithBodyCancellation");
    }
    @Test void invalidatingPausedBodyClearsOnlyItsAdapterReceipt() throws Exception {
        assertEquals(List.of(true, "PAUSED", 1, "PAUSED", 2, 1, false, true),
                call("retirePausedBodyReceipts"));
    }

    @Test void registeredServerStopCallbackClearsRealAdapterReceipts() throws Exception {
        assertEquals(List.of("PAUSED", 1, 0, true, true, false, false, "SUCCESS", "A,A", 0),
                call("serverStoppedCallback"));
    }

    @Test void lateAcceptanceAfterServerStopCannotReopenTheOldRun() throws Exception {
        assertEquals(List.of("PAUSED", 1, 0, true, true, false, false, "UNKNOWN", "UNKNOWN",
                        true, "SUCCESS", "A,A", List.of("A"), 0),
                call("lateAcceptanceAfterServerStop"));
    }

    @Test void invalidatingInFlightBodyClearsReceiptAfterToolReturns() throws Exception {
        assertEquals(List.of(true, 1, 0, false, true),
                call("retireAfterInFlightReceipt"));
    }

    @Test void missingHostReplyDoesNotAdvanceOrRedispatchOnResume() throws Exception {
        assertEquals(List.of("PAUSED", 0, true, "PAUSED", 1, 0, true, true, "PAUSED", 1, 0, true),
                call("unansweredReplyReplay"));
    }

    @Test void lateHostReplyCanOnlyAdvanceItsOriginalRun() throws Exception {
        assertLateReplyConsumedOnce("lateReplyReplay");
    }

    private static void assertLateReplyConsumedOnce(String scenario) throws Exception {
        assertEquals(List.of(true, "PAUSED", 1, 0, "SUCCESS", false, "PAUSED", true, "SUCCESS",
                        false, true, "A,B", "B,A", 0), call(scenario));
    }

    @Test void immediateMineAcceptanceKeepsExactBodyAndTerminalPayload() throws Exception {
        assertEquals(List.of("SUCCESS", 2, 1, List.of(4), 0, false), call("immediateMineBodyReply"));
    }

    @Test void lateMineAcceptanceKeepsDispatchIdentityNotReceiptTimeIdentity() throws Exception {
        assertEquals(List.of("SUCCESS", 2, 1, List.of(4), 0, false), call("lateMineBodyReply"));
    }

    @Test void mineCancellationAndFailureDoNotAuthorizeTheNextStep() throws Exception {
        assertEquals(List.of("body_terminal_unknown", "body_terminal_reobserve", "PAUSED", 0, 1, 1),
                call("mineTerminalWithoutSuccess"));
    }

    // Keep the original managed suite selection; extend it without a new whitelist entry.
    @Nested class Restore extends AcxTaskReplayFixture { }

    static void add(List<String> args,String cls,String source)throws Exception{
        Path file=temp.resolve(cls.replace('.','/')+".java");Files.createDirectories(file.getParent());Files.writeString(file,source);
        args.add(file.toString());
        if((cls.contains("acx.")&&!cls.endsWith("AcxPlugin")&&!cls.endsWith("BindingHarness"))
                 || cls.equals(PREFIX+"task.TaskDispatch") || cls.equals(PREFIX+"task.TaskPersistence"))
            System.out.println("CONSUMER_SOURCE "+cls+" sha256="+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))));
    }
    static String method(String source,String signature){
        int start=source.indexOf(signature),open=source.indexOf('{',start),depth=1,i=open+1;
        if(start<0||open<0)throw new IllegalStateException("SETUP ERROR: missing method "+signature);
        for(;i<source.length()&&depth>0;i++){char c=source.charAt(i);if(c=='{')depth++;else if(c=='}')depth--;}
        if(depth!=0)throw new IllegalStateException("SETUP ERROR: unclosed method "+signature);
        return source.substring(start,i);
    }

    static final String PLUGIN_HEADER="""
        package com.dwinovo.numen.plugins.acx;
        import com.dwinovo.numen.acx.api.*;
        import com.dwinovo.numen.acx.core.*;
        import com.dwinovo.numen.entity.NumenPlayer;
        import java.util.*;
        import java.util.concurrent.*;
        class AcxPlugin {
            NumenPlayer companion;
            AcxSessionManager sessions;
            AcxFacade facade;
            boolean ready;
            final Deque<Runnable> queue=new ArrayDeque<>();
            Runnable before=()->{};
            AcxHostGate gate=new AcxHostGate(100,()->new AcxHostGate.HostThread(){
                public boolean onServerThread(){return false;}
                public void execute(Runnable task){Runnable hook=before;before=()->{};hook.run();task.run();}
            });
            void ensureReady() { }
            public AcxHostGate gate(){return gate;}
            public AcxFacade facade(){return facade;}
        """;

    static final Map<String,String> IO=Map.of(
        PREFIX+"agent.tool.NumenTool","""
            package com.dwinovo.numen.agent.tool;
            import java.util.*;import java.util.function.Consumer;import com.google.gson.JsonObject;
            import com.dwinovo.numen.entity.NumenPlayer;
            public interface NumenTool {String name();default String description(){return "";}
                enum Residency { RESIDENT, DEFERRED }
                default Residency residency() { return Residency.DEFERRED; }
                default Map<String,Object> parameterSchema(){return Map.of();}
                void onServerCall(String id,JsonObject args,NumenPlayer body,Consumer<String> reply);}
            """,
        PREFIX+"agent.tool.Schema","""
            package com.dwinovo.numen.agent.tool;import java.util.*;
            public class Schema {public static Schema object(){return new Schema();}public static Map<String,Object> none(){return Map.of();}
                public Schema string(String n,String d){return this;}public Schema optionalString(String n,String d){return this;}
                public Map<String,Object> build(){return Map.of();}}
            """,
        PREFIX+"entity.NumenPlayer","""
            package com.dwinovo.numen.entity;
            import java.util.*;
            import net.minecraft.server.MinecraftServer;
            public class NumenPlayer {
                private static final Map<UUID,NumenPlayer> BODIES = new HashMap<>();
                private static final MinecraftServer SERVER = new MinecraftServer();
                public final String name;
                private final UUID uuid;
                public long gameTime;
                public Runnable beforeServerLookup = () -> {};
                public NumenPlayer(String name) {
                    this.name = name; uuid = UUID.nameUUIDFromBytes(name.getBytes()); BODIES.put(uuid, this);
                }
                public UUID getUUID() { return uuid; }
                public MinecraftServer getServer() { beforeServerLookup.run(); return SERVER; }
                public NumenPlayer level() { return this; }
                public long getGameTime() { return gameTime; }
                public boolean isRemoved() { return false; }
                public boolean isAlive() { return true; }
                public static NumenPlayer findByUuid(MinecraftServer server, UUID id) { return BODIES.get(id); }
            }
            """,
        PREFIX+"task.TaskRecord","""
            package com.dwinovo.numen.task;
            import com.dwinovo.numen.api.WorldContextStamp;
            public class TaskRecord {
                public static final long NO_DEADLINE = Long.MAX_VALUE / 2;
                private static int nextId;
                private final String id, tool, callId;
                private final long deadline;
                private TaskState state;
                private TaskResult result;
                private WorldContextStamp.Token identity;
                private long accepted = -1;
                private boolean callerOwned;
                public TaskRecord(String id, String state) { this(id,state,null); }
                public TaskRecord(String id, String state, TaskResult result) {
                    this(id, "fixture", "fixture", 1000);
                    this.state = TaskState.valueOf(state); this.result = result;
                }
                public TaskRecord(String tool, String callId, long deadline) {
                    this("fixture-task-" + ++nextId, tool, callId, deadline);
                }
                public TaskRecord(String id, String tool, String callId, long deadline) {
                    this.id = id; this.tool = tool; this.callId = callId; this.deadline = deadline;
                    this.state = TaskState.PENDING;
                }
                public String publicId() { return id; }
                public String describe() { return tool; }
                public String getToolName() { return tool; }
                public String getToolCallId() { return callId; }
                public boolean isExternalCall() { return callId.startsWith("mcp-"); }
                public long getDeadlineGameTime() { return deadline; }
                public void extendDeadlineTo(long time) {}
                public void markStarted(long time) { accepted = time; }
                public boolean acceptedWithin(long time, long grace) { return accepted >= 0 && time <= accepted + grace; }
                public void bindIdentity(WorldContextStamp.Token token) { if (identity == null) identity = token; }
                public WorldContextStamp.Token identityToken() { return identity; }
                public TaskResult getResult() { return result; }
                public void setResult(TaskResult result) { this.result = result; }
                public TaskState getState() { return state; }
                public void setState(TaskState state) { this.state = state; }
                public void markCallerOwned() { callerOwned = true; }
                public boolean isCallerOwned() { return callerOwned; }
                public void markAsync() {}
                public boolean isAsync() { return true; }
            }
            """,
        PREFIX+"task.CompanionTickDispatcher","""
            package com.dwinovo.numen.task;
            import java.util.*;
            import com.dwinovo.numen.entity.NumenPlayer;
            public class CompanionTickDispatcher {
                public static final Map<UUID,TaskRecord> active = new HashMap<>();
                public static final Map<String,TaskRecord> finished = new HashMap<>();
                private static final Map<UUID,TaskSlot> slots = new HashMap<>(), syncSlots = new HashMap<>();
                static TaskSlot currentSlotFor(UUID id) { return slots.computeIfAbsent(id, unused -> new TaskSlot(r -> {})); }
                static TaskSlot syncSlotFor(UUID id) { return syncSlots.computeIfAbsent(id, unused -> new TaskSlot(r -> {})); }
                public static TaskRecord currentTaskFor(UUID id) {
                    var slot = slots.get(id); return slot == null ? active.get(id) : slot.record();
                }
                static boolean currentFreshlyAccepted(NumenPlayer body) {
                    var slot = slots.get(body.getUUID()); return slot != null && slot.freshlyAccepted(body);
                }
                public static TaskRecord lastFinishedTaskFor(UUID id) { return finished.get(id+"/latest"); }
                public static TaskRecord finishedTaskFor(UUID id, String task) { return finished.get(id+"/"+task); }
                public static void settle(NumenPlayer body, TaskRecord record, TaskState state) {
                    var slot = slots.get(body.getUUID());
                    if (slot == null || slot.record() != record) throw new AssertionError("wrong fixture slot");
                    record.setState(state);
                    slot.settleIfTerminal();
                }
                public static void removeBody(NumenPlayer body) {
                    var slot = slots.remove(body.getUUID());
                    if (slot != null) slot.finalizeInline();
                    var sync = syncSlots.remove(body.getUUID());
                    if (sync != null) sync.finalizeInline();
                }
            }
            """,
        "net.minecraft.server.MinecraftServer","""
            package net.minecraft.server;
            public class MinecraftServer {public boolean isSameThread(){return true;}public void execute(Runnable task){task.run();}}
            """,
        "net.neoforged.neoforge.server.ServerLifecycleHooks","""
            package net.neoforged.neoforge.server;import net.minecraft.server.MinecraftServer;
            public class ServerLifecycleHooks {public static MinecraftServer getCurrentServer(){return null;}}
            """
    );

    // Stub only the launcher and registration boundaries. AcxMod itself is production source.
    static final Map<String,String> STOP_IO=Map.of(
        PREFIX+"api.NumenPlugins","""
            package com.dwinovo.numen.api;
            public class NumenPlugins {public static Object registered;
                public static void register(Object plugin){registered=plugin;}}
            """,
        "net.neoforged.fml.common.Mod","""
            package net.neoforged.fml.common;
            public @interface Mod {String value();}
            """,
        "net.neoforged.neoforge.event.server.ServerStoppedEvent","""
            package net.neoforged.neoforge.event.server;
            public class ServerStoppedEvent {}
            """,
        "net.neoforged.neoforge.common.NeoForge","""
            package net.neoforged.neoforge.common;
            import java.util.*;import java.util.function.Consumer;
            import net.neoforged.neoforge.event.server.ServerStoppedEvent;
            public class NeoForge {public static final Bus EVENT_BUS=new Bus();
                public static class Bus {
                    final List<Consumer<ServerStoppedEvent>> listeners=new ArrayList<>();
                    public void addListener(Consumer<ServerStoppedEvent> listener){listeners.add(listener);}
                    public void fireStopped(){listeners.forEach(f->f.accept(new ServerStoppedEvent()));}
                    public void clear(){listeners.clear();}
                }}
            """
    );

    static final String HARNESS="""
        package com.dwinovo.numen.plugins.acx;
        import com.dwinovo.numen.acx.api.*;import com.dwinovo.numen.acx.core.*;
        import com.dwinovo.numen.agent.tool.NumenTool;import com.dwinovo.numen.agent.tool.ToolRegistry;
        import com.dwinovo.numen.entity.NumenPlayer;import com.dwinovo.numen.entity.CompanionRegistry;
        import com.dwinovo.numen.task.*;import com.google.gson.*;
        import com.dwinovo.numen.api.NumenPlugins;import com.dwinovo.numen.api.WorldContextStamp;
        import net.neoforged.neoforge.common.NeoForge;
        import java.util.*;import java.util.function.Consumer;
        public class BindingHarness {
            // A no-op body task still has a real TaskSlot and a real persistence/replay path.
            static TaskRecord task(String callId) { return new TaskRecord("replay_fixture", callId, 1000); }
            static class Fixture {
                final AcxPlugin plugin;final NumenPlayer a=new NumenPlayer("A"),b=new NumenPlayer("B");
                final List<String> invoked=new ArrayList<>();final List<String> results=new ArrayList<>();String run;
                boolean callbackInterleave, async, bodyThrowsCancellation;PortToolAdapter adapter;
                Runnable beforeAsyncReply=()->{};
                Fixture(){this(false);}
                Consumer<String> heldReply;
                final List<String> successorInputs=new ArrayList<>();
                Fixture(boolean realAdapter){this(realAdapter,false);}
                Fixture(boolean realAdapter,boolean holdFirstReply){this(realAdapter,holdFirstReply,new AcxPlugin());}
                Fixture(boolean realAdapter,AcxPlugin plugin){this(realAdapter,false,plugin);}
                Fixture(boolean realAdapter,boolean holdFirstReply,AcxPlugin plugin){
                    this.plugin=plugin;
                    AcxRegistry registry=new AcxRegistry();
                    NumenTool body=new NumenTool(){public String name(){return "binding_body";}
                        public void onServerCall(String id,JsonObject args,NumenPlayer p,Consumer<String> reply){
                            invoked.add(p.name);
                            if(holdFirstReply && heldReply==null){
                                heldReply=reply;
                                if(bodyThrowsCancellation)throw new java.util.concurrent.CancellationException("body already called");
                                return;
                            }
                            if(async){beforeAsyncReply.run();reply.accept(new Gson().toJson(Map.of("success",true,"data",Map.of("async",true,"task_id","t1"))));return;}
                            if(callbackInterleave)request(new AcxFacadeTools.Library(plugin),new JsonObject(),b);
                            reply.accept(new Gson().toJson(Map.of("success",true,"data",Map.of("owner",p.name))));
                        }};
                    NumenToolBridgeX port=new NumenToolBridgeX(body,plugin,100);
                    if(realAdapter)registry.register(adapter=PortToolAdapter.builder(port).probe(NumenToolBridgeX.probeFor(plugin)).build());
                    else registry.register(new AcxTool(){public String name(){return "binding_body";}
                        public AcxToolSchema schema(){return AcxToolSchema.builder().build();}
                        public AcxStepOutcome execute(Map<String,Object> params,AcxCallContext ctx){
                            try{
                                AcxToolPort.Result r;
                                try{r=(AcxToolPort.Result)AcxToolPort.class.getMethod("invoke",Map.class,AcxCallContext.class).invoke(port,params,ctx);}
                                catch(NoSuchMethodException legacy){r=port.invoke(params);}
                                if(r.isFailed())return AcxStepOutcome.failed(r.message());
                                results.add(String.valueOf(r.data().get("owner")));return AcxStepOutcome.success(r.data());
                            }catch(Exception e){throw new RuntimeException(e);}
                        }});
                    if(holdFirstReply) registry.register(new AcxTool(){
                        public String name(){return "binding_next";}
                        public AcxToolSchema schema(){return AcxToolSchema.builder().param("owner", AcxToolSchema.ParamType.STRING).build();}
                        public AcxStepOutcome execute(Map<String,Object> params,AcxCallContext ctx){
                            successorInputs.add(String.valueOf(params.get("owner")));
                            return AcxStepOutcome.success(Map.of());
                        }});
                    List<AcxStep> steps=holdFirstReply
                            ? List.of(new AcxStep("body","binding_body",Map.of("ignore_failure",true)),
                                      new AcxStep("next","binding_next",Map.of("owner","$prev.owner")))
                            : List.of(new AcxStep("body","binding_body",Map.of()));
                    AcxDefinition definition=new AcxDefinition("binding","1","",List.of(),steps,"","");
                    plugin.sessions=new AcxSessionManager(AcxRunner.builder().tools(registry).build(),plugin.queue::add);
                    plugin.facade=new AcxFacade(plugin.sessions,name->definition,registry,null);
                    plugin.ready=true;
                }
                void start(){start(a);}
                void start(NumenPlayer body){JsonObject args=new JsonObject();args.addProperty("ac_name","binding");
                    String response=request(new AcxFacadeTools.Execute(plugin),args,body);
                    run=JsonParser.parseString(response).getAsJsonObject().getAsJsonObject("data").get("run_id").getAsString();}
                JsonObject status(){JsonObject args=new JsonObject();args.addProperty("run_id",run);return args;}
                void drain(){while(!plugin.queue.isEmpty())plugin.queue.remove().run();}
                String body(){return String.join(",",invoked);}
            }
            static String request(NumenTool tool,JsonObject args,NumenPlayer body){String[] response={null};tool.onServerCall("fixture",args,body,r->response[0]=r);return response[0];}
            static AcxTool.AcxCallContext ctx(String run){return new AcxTool.AcxCallContext(){
                public String runId(){return run;}public String stepId(){return "body";}public boolean isCancelRequested(){return false;}public void emitDetail(String k,Object v){}};}
            public static String yieldOriginalRunBinding() {
                Fixture f=new Fixture();
                class Yield implements NumenTool, com.dwinovo.numen.agent.tool.TrustedYieldTool {
                    String current="chain-a";
                    public String name(){return "rdd_report_yield";}
                    public Object captureHostBinding(NumenPlayer p){return p.name+":"+current;}
                    public void onServerCall(String id,JsonObject args,NumenPlayer p,Consumer<String> reply){reply.accept(new Gson().toJson(Map.of("success",false,"message","untrusted")));}
                    public void onTrustedHostCall(String id,JsonObject args,NumenPlayer p,Object bound,Consumer<String> reply){reply.accept(new Gson().toJson(Map.of("success",Objects.equals(bound,p.name+":"+current),"data",Map.of())));}
                }
                Yield y=new Yield(); ToolRegistry.register(y);
                try {
                    f.start(); request(new AcxFacadeTools.Status(f.plugin),f.status(),f.b);
                    var port=new NumenToolBridgeX(y,f.plugin,100);
                    var good=port.invoke(Map.of(),ctx(f.run)).outcome();
                    y.current="chain-b";var stale=port.invoke(Map.of(),ctx(f.run)).outcome();
                    f.plugin.before=()->new NumenPlayer("A");
                    var replacement=port.invoke(Map.of(),ctx(f.run)).outcome();
                    return good+","+stale+","+replacement;
                } finally {ToolRegistry.remove(y.name());}
            }
            public static String syncTerminalWithoutCallback(){return syncTerminal(false);}
            public static String failedSyncTerminalWithoutCallback(){return syncTerminal(false,1,TaskState.FAILED,
                    TaskResult.fail("aim 21417,51,22143 is blocked from here — the crosshair lands on stone at 21415,52,22145 instead. break_block that blocker, or goto the target's open side, then retry.",Map.of("button","left")),true);}
            public static String cancelledSyncTerminalWithoutCallback(){return syncTerminal(false,1,TaskState.CANCELLED,TaskResult.cancelled("owner stopped it"),true);}
            public static String timeoutSyncTerminalWithoutCallback(){return syncTerminal(false,1,TaskState.TIMEOUT,TaskResult.timeout("deadline"),true);}
            public static String foreignSyncTerminalWithoutCallback(){return syncTerminal(true);}
            public static String unknownIdentitySyncTerminalWithoutCallback(){return syncTerminal(false,0);}
            public static String missingWorldSyncTerminalWithoutCallback(){return syncTerminal(false,-1);}
            public static String missingServerSyncTerminalWithoutCallback(){return syncTerminal(false,-2);}
            static String syncTerminal(boolean foreign){return syncTerminal(foreign,1);}
            static String syncTerminal(boolean foreign,long epoch){return syncTerminal(foreign,epoch,TaskState.SUCCESS,TaskResult.ok("real sync terminal",Map.of("changed",true)),false);}
            static String syncTerminal(boolean foreign,long epoch,TaskState state,TaskResult terminalResult,boolean withMessage){
                long beforeEpoch=WorldContextStamp.epoch;WorldContextStamp.epoch=epoch;
                // A queued real session owns this host call; it remains inFlight during invocation.
                Fixture waiting=new Fixture();waiting.start();
                waiting.plugin.gate=new AcxHostGate(100,()->new AcxHostGate.HostThread(){
                    public boolean onServerThread(){return true;} public void execute(Runnable r){r.run();}});
                int[] calls={0};
                NumenTool sync=new NumenTool(){public String name(){return "interact_at";}
                    public void onServerCall(String id,JsonObject args,NumenPlayer body,Consumer<String> ignored){
                        calls[0]++;var rec=new TaskRecord("sync-record","interact_at",foreign?"another-call":id,1000);
                        rec.bindIdentity(WorldContextStamp.capture(body.getServer()));rec.setState(state);
                        rec.setResult(terminalResult);
                        CompanionTickDispatcher.finished.put(body.getUUID()+"/latest",rec);
                        // TaskDispatch.runSync deliberately does NOT invoke the supplied callback.
                    }};
                try{
                    var port=new NumenToolBridgeX(sync,waiting.plugin,10);
                    try{var reply=port.invoke(Map.of(),ctx(waiting.run));return format(reply.outcome().name(),calls[0],null);}
                    catch(AcxToolPort.ReplyPendingAfterDispatchException pending){
                        var value=pending.receipt().poll();
                        String outcome=value.isPresent()?value.get().outcome().name():"PENDING";
                        String message=value.isPresent()?value.get().message():null;
                        return format(outcome,calls[0],withMessage?message:null);
                    }
                }finally {CompanionTickDispatcher.finished.remove(waiting.a.getUUID()+"/latest");WorldContextStamp.epoch=beforeEpoch;}
            }
            static String format(String outcome,int calls,String message){return outcome+":"+calls+(message==null?"":":"+message);}
            // REPLAY_SCENARIOS
            public static String statusInterleave(){Fixture f=new Fixture();f.start();request(new AcxFacadeTools.Status(f.plugin),f.status(),f.b);f.drain();return f.body();}
            public static String libraryInterleave(){Fixture f=new Fixture();f.start();request(new AcxFacadeTools.Library(f.plugin),new JsonObject(),f.b);f.drain();return f.body();}
            public static String invalidInterleave(){Fixture f=new Fixture();f.start();JsonObject args=new JsonObject();args.addProperty("input","{");request(new AcxFacadeTools.Execute(f.plugin),args,f.b);f.drain();return f.body();}
            public static String callbackInterleave(){Fixture f=new Fixture();f.callbackInterleave=true;f.start();f.drain();return f.body()+":"+String.join(",",f.results);}
            public static int cancelAtGate(){Fixture f=new Fixture();f.start();f.plugin.before=()->f.plugin.sessions.cancel(f.run);f.drain();return f.invoked.size();}
            public static int replaceAtGate(){Fixture f=new Fixture();f.start();f.plugin.before=()->new NumenPlayer("A");f.drain();return f.invoked.size();}
            public static String adapterStatusInterleave(){Fixture f=new Fixture(true);f.start();request(new AcxFacadeTools.Status(f.plugin),f.status(),f.b);f.drain();return f.body();}
            public static int adapterCancelAtGate(){Fixture f=new Fixture(true);f.start();f.plugin.before=()->f.plugin.sessions.cancel(f.run);f.drain();return f.invoked.size();}
            public static String adapterAcceptedResume(){
                Fixture f=new Fixture(true);f.async=true;f.start();f.drain();
                String first=f.plugin.sessions.session(f.run).record().status().name();
                CompanionTickDispatcher.finished.put(f.a.getUUID()+"/t1",new TaskRecord("t1","SUCCESS"));
                CompanionTickDispatcher.finished.put(f.b.getUUID()+"/t1",new TaskRecord("t1","FAILED"));
                request(new AcxFacadeTools.Status(f.plugin),f.status(),f.b);
                String resumed=request(new AcxFacadeTools.Resume(f.plugin),f.status(),f.a);
                if(!JsonParser.parseString(resumed).getAsJsonObject().get("success").getAsBoolean())throw new AssertionError(resumed);
                f.drain();return first+":"+f.plugin.sessions.session(f.run).record().status().name()+":"+f.body()+":"+f.adapter.pendingCount();
            }
            public static String adapterQueuedCancelResume(){
                Fixture f=new Fixture(true);f.start();request(new AcxFacadeTools.Cancel(f.plugin),f.status(),f.a);f.drain();
                String first=f.plugin.sessions.session(f.run).record().status().name();
                if(!f.invoked.isEmpty())throw new AssertionError("cancelled queued body dispatched");
                String resumed=request(new AcxFacadeTools.Resume(f.plugin),f.status(),f.a);
                if(!JsonParser.parseString(resumed).getAsJsonObject().get("success").getAsBoolean())throw new AssertionError(resumed);
                f.drain();return first+":"+f.plugin.sessions.session(f.run).record().status().name()+":"+f.body();
            }
            // Acceptance and dispatcher records are fixtures; the stop guard and IO dispatch
            // below run through the unchanged production Bridge and bound host gate helper.
            static String stopCase(String wanted,String active,boolean accepted){
                Fixture f=new Fixture(true);f.start();
                if(accepted)f.plugin.sessions.session(f.run).noteAcceptedTask(wanted);
                CompanionTickDispatcher.active.put(f.a.getUUID(),new TaskRecord(active,"RUNNING"));
                int[] calls={0};
                NumenTool stop=new NumenTool(){public String name(){return "task_stop";}
                    public void onServerCall(String id,JsonObject args,NumenPlayer body,Consumer<String> reply){
                        calls[0]++;reply.accept(new Gson().toJson(Map.of("success",true)));}};
                try{
                    AcxToolPort.Result result=new NumenToolBridgeX(stop,f.plugin,100).invoke(Map.of("task_id",wanted),ctx(f.run));
                    return result.outcome().name()+":"+calls[0];
                }finally{CompanionTickDispatcher.active.remove(f.a.getUUID());}
            }
            public static String stopUnownedTask(){return stopCase("","main-task",false)+","+stopCase("other-run-task","other-run-task",false);}
            public static String stopReplacedTaskSlot(){return stopCase("owned-task","main-task",true);}
            public static String stopExactOwnedTask(){return stopCase("owned-task","owned-task",true);}
            public static List<Object> interruptedDuringOriginalBodyCheck() throws Exception {
                Fixture f=new Fixture(true);f.start();
                var reachedLookup=new java.util.concurrent.CountDownLatch(1);
                var releaseLookup=new java.util.concurrent.CountDownLatch(1);
                var lookupReleased=new java.util.concurrent.CountDownLatch(1);
                var calls=new java.util.concurrent.atomic.AtomicInteger();
                Thread[] worker={null};
                f.a.beforeServerLookup=()->{
                    reachedLookup.countDown();
                    await(releaseLookup,"worker waited for the invocation to return");
                    lookupReleased.countDown();
                };
                f.plugin.gate=new AcxHostGate(5_000,()->new AcxHostGate.HostThread(){
                    public boolean onServerThread(){return false;}
                    public void execute(Runnable task){
                        worker[0]=new Thread(task,"host-before-tool-entry");
                        worker[0].setDaemon(true);worker[0].start();
                        await(reachedLookup,"real gate entered the original body check");
                        Thread.currentThread().interrupt();
                    }
                });
                NumenTool body=new NumenTool(){public String name(){return "binding_body";}
                    public void onServerCall(String id,JsonObject args,NumenPlayer p,Consumer<String> reply){
                        calls.incrementAndGet();
                    }};
                AcxToolPort.Result result;
                int callsBeforeRelease;
                try {
                    result=new NumenToolBridgeX(body,f.plugin,100).invoke(Map.of(),ctx(f.run));
                    callsBeforeRelease=calls.get();
                } finally {
                    Thread.interrupted();releaseLookup.countDown();
                    if(worker[0]!=null)worker[0].join(2_000);
                }
                if(reachedLookup.getCount()!=0 || lookupReleased.getCount()!=0 || worker[0]==null || worker[0].isAlive())
                    throw new AssertionError("SETUP ERROR: host did not traverse the controlled gate");
                return List.of(result.outcome().name(),callsBeforeRelease,calls.get());
            }
            private static void await(java.util.concurrent.CountDownLatch latch,String phase){
                try {
                    if(!latch.await(2,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("SETUP ERROR: "+phase);
                } catch(InterruptedException e){throw new AssertionError(e);}
            }
            public static String actualGateCancelResume(){
                Fixture f=new Fixture(true);f.start();f.plugin.before=()->f.plugin.sessions.cancel(f.run);f.drain();
                String first=f.plugin.sessions.session(f.run).record().status().name();int dispatched=f.invoked.size();
                String resumed=request(new AcxFacadeTools.Resume(f.plugin),f.status(),f.a);
                boolean accepted=JsonParser.parseString(resumed).getAsJsonObject().get("success").getAsBoolean();
                f.drain();return first+":"+dispatched+":"+accepted+":"+f.plugin.sessions.session(f.run).record().status().name()+":"+f.body()+":"+f.adapter.pendingCount();
            }
            private static void cancelQueued(Fixture f) {
                f.start();
                request(new AcxFacadeTools.Cancel(f.plugin),f.status(),f.a);
                f.drain();
            }
            private static boolean resumeAndDrain(Fixture f){
                String response=request(new AcxFacadeTools.Resume(f.plugin),f.status(),f.a);
                f.drain();
                return JsonParser.parseString(response).getAsJsonObject().get("success").getAsBoolean();
            }
            private static AcxRunRecord record(Fixture f){return f.plugin.sessions.session(f.run).record();}
            private static boolean sameCheckpoint(AcxRunRecord a,AcxRunRecord b){
                return a.runId().equals(b.runId()) && a.completedStepIndex()==b.completedStepIndex();
            }
            public static List<Object> unansweredReplyReplay(){
                Fixture f=new Fixture(true,true);
                cancelQueued(f);
                AcxRunRecord before=record(f);
                int countBefore=f.invoked.size();
                boolean started=resumeAndDrain(f);
                AcxRunRecord unanswered=record(f);
                int dispatched=f.invoked.size(),next=f.successorInputs.size();
                boolean retried=resumeAndDrain(f);
                AcxRunRecord after=record(f);
                return List.of(before.status().name(),countBefore,started,unanswered.status().name(),dispatched,next,
                        sameCheckpoint(before,unanswered),retried,after.status().name(),f.invoked.size(),
                        f.successorInputs.size(),sameCheckpoint(before,after));
            }
            public static List<Object> lateReplyReplay(){return lateReplyReplay(false);}
            public static List<Object> lateReplyReplayWithBodyCancellation(){return lateReplyReplay(true);}
            private static List<Object> lateReplyReplay(boolean bodyThrowsCancellation){
                Fixture f=new Fixture(true,true);
                f.bodyThrowsCancellation=bodyThrowsCancellation;
                cancelQueued(f);
                boolean started=resumeAndDrain(f);
                String before=record(f).status().name();
                int dispatched=f.invoked.size(),next=f.successorInputs.size();
                String originalRun=f.run;
                f.start(f.b);f.drain();
                String otherStatus=record(f).status().name();
                f.run=originalRun;
                request(new AcxFacadeTools.Status(f.plugin),f.status(),f.b);
                f.heldReply.accept(new Gson().toJson(Map.of("success",true,"data",Map.of("owner","A"))));
                f.heldReply.accept(new Gson().toJson(Map.of("success",true,"data",Map.of("owner","duplicate"))));
                String foreign=request(new AcxFacadeTools.Resume(f.plugin),f.status(),f.b);
                boolean foreignResumed=JsonParser.parseString(foreign).getAsJsonObject().get("success").getAsBoolean();
                f.drain();
                String beforeResume=record(f).status().name();
                boolean resumed=resumeAndDrain(f);
                AcxRunRecord completed=record(f);
                boolean replayed=resumeAndDrain(f);
                return List.of(started,before,dispatched,next,otherStatus,foreignResumed,beforeResume,resumed,
                        record(f).status().name(),replayed,sameCheckpoint(completed,record(f)),
                        f.body(),String.join(",",f.successorInputs),f.adapter.pendingCount());
            }
            public static boolean independentRunsProgress() {
                var registry = new AcxRegistry();
                var entered = new java.util.concurrent.CountDownLatch(1);
                var release = new java.util.concurrent.CountDownLatch(1);
                var computed = new java.util.concurrent.CountDownLatch(1);
                registry.register(new AcxTool() {
                    public String name() { return "wait_fixture"; }
                    public AcxToolSchema schema() { return AcxToolSchema.builder().build(); }
                    public AcxStepOutcome execute(Map<String,Object> params, AcxCallContext ctx) {
                        entered.countDown();
                        try {
                            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                throw new AssertionError("SETUP ERROR: wait was not released");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                        }
                        return AcxStepOutcome.success(Map.of());
                    }
                });
                registry.register(new AcxTool() {
                    public String name() { return "compute_fixture"; }
                    public AcxToolSchema schema() { return AcxToolSchema.builder().build(); }
                    public AcxStepOutcome execute(Map<String,Object> params, AcxCallContext ctx) {
                        return AcxStepOutcome.success(Map.of("value", 42));
                    }
                });
                var plugin = new AcxPlugin();
                try (var executor = AcxPlugin.newRunExecutor()) {
                    var runner = AcxRunner.builder().tools(registry).events(event -> {
                        if (event.kind() == AcxEvent.Kind.STEP_SUCCEEDED && "compute_fixture".equals(event.acName()))
                            computed.countDown();
                    }).build();
                    plugin.sessions = new AcxSessionManager(runner, executor);
                    plugin.facade = new AcxFacade(plugin.sessions, name -> new AcxDefinition(name, "1", "",
                            List.of(), List.of(new AcxStep("step", name, Map.of())), "", ""), registry, null);
                    var body = new NumenPlayer("parallel-observer");
                    try {
                        startNamed(plugin, body, "wait_fixture");
                        await(entered, "wait fixture must enter before the second run");
                        startNamed(plugin, body, "compute_fixture");
                        return computed.await(2, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    } finally { release.countDown(); }
                }
            }

            private static String startNamed(AcxPlugin plugin, NumenPlayer body, String name) {
                var args = new JsonObject();
                args.addProperty("ac_name", name);
                var reply = request(new AcxFacadeTools.Execute(plugin), args, body);
                return JsonParser.parseString(reply).getAsJsonObject().getAsJsonObject("data").get("run_id").getAsString();
            }

            public static List<Object> competingRunWaits() {
                var f = new MineReceiptFixture(false);
                try {
                    f.start(); f.drain();
                    TaskRecord original = f.mine;
                    String originalRun = f.run;
                    f.a.gameTime += 21; // The legacy freshness guard no longer protects this task.
                    f.start(); f.drain();
                    String competingRun = f.run;
                    var blocked = record(f);
                    boolean keptOriginal = CompanionTickDispatcher.currentTaskFor(f.a.getUUID()) == original
                            && f.plugin.sessions.session(originalRun).ownsTask(original.publicId());
                    boolean stillWaiting = resumeAndDrain(f);
                    var blockedAgain = record(f);
                    boolean sameCheckpoint = sameCheckpoint(blocked, blockedAgain) && f.adapter.pendingCount() == 1
                            && CompanionTickDispatcher.currentTaskFor(f.a.getUUID()) == original;
                    f.start(f.b); f.drain();
                    var other = CompanionTickDispatcher.currentTaskFor(f.b.getUUID());
                    boolean otherAdmitted = other != null && f.plugin.sessions.session(f.run).ownsTask(other.publicId());
                    f.run = competingRun;
                    if (CompanionTickDispatcher.currentTaskFor(f.a.getUUID()) == original)
                        CompanionTickDispatcher.settle(f.a, original, TaskState.SUCCESS);
                    boolean resumed = resumeAndDrain(f);
                    var admitted = record(f);
                    TaskRecord current = CompanionTickDispatcher.currentTaskFor(f.a.getUUID());
                    return List.of(blocked.status().name(), blocked.pausedReason(), keptOriginal, stillWaiting,
                            blockedAgain.pausedReason(), sameCheckpoint, otherAdmitted, resumed,
                            admitted.status().name(), admitted.pausedReason(),
                            current != null && current != original
                                    && f.plugin.sessions.session(competingRun).ownsTask(current.publicId()));
                } finally {
                    CompanionTickDispatcher.removeBody(f.b);
                    f.close();
                }
            }

            public static List<Object> nestedCancelLateReceiptCannotReleaseNewOwner() throws Exception {
                var entered = new java.util.concurrent.CountDownLatch(1);
                var release = new java.util.concurrent.CountDownLatch(1);
                var f = new MineReceiptFixture(true, null, entered, release, null, true);
                Thread worker = null;
                try {
                    WorldContextStamp.epoch=1;
                    f.start(); String oldRun=f.run; var old=f.plugin.sessions.session(oldRun);
                    var oldArgs=f.status(); worker=new Thread(f::drain, "nested-held-body-call"); worker.start();
                    await(entered, "nested child must occupy body before cancel");
                    TaskRecord original=f.mine;
                    boolean occupied=original!=null && CompanionTickDispatcher.currentTaskFor(f.a.getUUID())==original;
                    var cancel=JsonParser.parseString(request(new AcxFacadeTools.Cancel(f.plugin),oldArgs,f.a)).getAsJsonObject();
                    boolean sent=cancel.get("success").getAsBoolean();
                    release.countDown(); worker.join(2000);
                    if(worker.isAlive() || f.deliver==null || old.record()==null) throw new AssertionError("SETUP ERROR: delayed receipt unavailable");
                    var cancelled=old.record(); Runnable oldReceipt=f.deliver;
                    boolean paused=cancelled.status()==AcxStatus.PAUSED;
                    // The original body is explicitly terminal before a new run owns the slot.
                    f.finish(original, TaskState.SUCCESS);
                    f.start(); String newRun=f.run; f.drain(); TaskRecord replacement=f.mine;
                    if(f.deliver==null) throw new AssertionError("SETUP ERROR: new acceptance missing");
                    f.deliver.run(); resumeAndDrain(f);
                    boolean newer=replacement!=null && replacement!=original && CompanionTickDispatcher.currentTaskFor(f.a.getUUID())==replacement
                            && f.plugin.sessions.session(newRun).ownsTask(replacement.publicId());
                    oldReceipt.run();
                    boolean isolated=CompanionTickDispatcher.currentTaskFor(f.a.getUUID())==replacement && cancelled.equals(old.record());
                    int before=f.invoked.size();
                    f.run=oldRun; resumeAndDrain(f);
                    boolean noReplay=f.invoked.size()==before && CompanionTickDispatcher.currentTaskFor(f.a.getUUID())==replacement;
                    f.run=newRun;
                    return List.of(occupied,sent,paused,newer,isolated,noReplay,f.invoked.size());
                } finally { release.countDown(); if(worker!=null) worker.join(2000); f.close(); }
            }
            public static List<Object> nestedBodySuccessReachesParent() {
                var f=new MineReceiptFixture(false,null,null,null,null,true);
                try { WorldContextStamp.epoch=1; f.start(); f.drain(); f.finish(f.mine,TaskState.SUCCESS); resumeAndDrain(f);
                    return List.of(record(f).status().name(),f.invoked.size(),f.gathered);
                } finally { f.close(); }
            }
            public static List<Object> cancelledRunLateReceiptKeepsBodyBusy() throws Exception {
                var entered = new java.util.concurrent.CountDownLatch(1);
                var release = new java.util.concurrent.CountDownLatch(1);
                var f = new MineReceiptFixture(true, null, entered, release, null);
                Thread worker = null;
                try {
                    f.start();
                    String oldRun = f.run;
                    var old = f.plugin.sessions.session(oldRun);
                    JsonObject oldArgs = f.status();
                    worker = new Thread(f::drain, "cancel-held-body-call");
                    worker.start();
                    await(entered, "body must occupy its real task slot before cancellation");
                    TaskRecord original = f.mine;
                    boolean occupied = original != null && original.getState() == TaskState.RUNNING
                            && CompanionTickDispatcher.currentTaskFor(f.a.getUUID()) == original;
                    var cancellation = JsonParser.parseString(request(new AcxFacadeTools.Cancel(f.plugin), oldArgs, f.a)).getAsJsonObject();
                    boolean cancelSent = cancellation.get("success").getAsBoolean()
                            && cancellation.getAsJsonObject("data").get("cancel_requested").getAsBoolean();
                    boolean heldAfterCancel = release.getCount() == 1 && old.isRunning()
                            && CompanionTickDispatcher.currentTaskFor(f.a.getUUID()) == original
                            && original.getState() == TaskState.RUNNING && !old.isBodyInvalidated();
                    release.countDown();
                    worker.join(2000);
                    if (worker.isAlive() || f.deliver == null || old.record() == null)
                        throw new AssertionError("SETUP ERROR: held dispatch did not return with its delayed receipt");
                    AcxRunRecord cancelled = old.record();
                    f.deliver.run(); // Only the receipt arrives; the body task is not settled or removed.
                    boolean unchanged = cancelled.equals(old.record()) && !old.isRunning();
                    boolean occupiedAfterReply = CompanionTickDispatcher.currentTaskFor(f.a.getUUID()) == original
                            && original.getState() == TaskState.RUNNING;
                    f.a.gameTime += 21; // Defeat the independent freshly-accepted grace guard.
                    f.start();
                    String competitor = f.run;
                    f.drain();
                    AcxRunRecord blocked = record(f);
                    return List.of(occupied, cancelSent, heldAfterCancel, cancelled.status().name(),
                            cancelled.completedStepIndex(), unchanged, occupiedAfterReply, !oldRun.equals(competitor),
                            blocked.status().name(), blocked.pausedReason(),
                            CompanionTickDispatcher.currentTaskFor(f.a.getUUID()) == original,
                            original.getState() == TaskState.RUNNING,
                            !f.plugin.sessions.session(competitor).ownsTask(original.publicId()), f.gathered.size());
                } finally {
                    release.countDown();
                    if (worker != null) worker.join(2000);
                    f.close(); // Fixture teardown only, after all assertions have been observed.
                }
            }

            public static List<Object> observeWhileMineWaits() throws Exception {
                var entered = new java.util.concurrent.CountDownLatch(1);
                var release = new java.util.concurrent.CountDownLatch(1);
                var observed = new java.util.concurrent.CountDownLatch(1);
                try (var executor = AcxPlugin.newRunExecutor()) {
                    var f = new MineReceiptFixture(false, executor, entered, release, observed);
                    try {
                        f.start();
                        String oldRun = f.run;
                        await(entered, "old body action must be admitted and held");
                        TaskRecord original = f.mine;
                        boolean oldRunning = f.plugin.sessions.session(oldRun).isRunning();
                        boolean slotBefore = CompanionTickDispatcher.currentTaskFor(f.a.getUUID()) == original;
                        String observerRun = startNamed(f.plugin, f.a, "observe_fixture");
                        await(observed, "same-body observation must finish before old action is released");
                        boolean stillHeld = release.getCount() == 1 && f.plugin.sessions.session(oldRun).isRunning();
                        boolean slotAfter = CompanionTickDispatcher.currentTaskFor(f.a.getUUID()) == original;
                        return List.of(oldRunning, slotBefore, !oldRun.equals(observerRun), f.observations,
                                stillHeld, slotAfter, f.gathered.isEmpty());
                    } finally {
                        release.countDown();
                        executor.shutdown();
                        if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
                            throw new AssertionError("SETUP ERROR: worker did not finish");
                        f.close();
                    }
                }
            }

            public static List<Object> syncAndCurrentConflict() {
                var f = new MineReceiptFixture(false);
                try {
                    f.start(); f.drain();
                    TaskRecord original = f.mine;
                    f.start();
                    NumenTool sync = new NumenTool() {
                        public String name() { return "sync_fixture"; }
                        public void onServerCall(String id, JsonObject args, NumenPlayer p, Consumer<String> reply) {
                            TaskDispatch.runSync(p, new TaskRecord(name(), id, 1000), reply);
                        }
                    };
                    var blocked = new NumenToolBridgeX(sync, f.plugin, 5).invoke(Map.of(), ctx(f.run));
                    boolean knowsCurrent = original.publicId().equals(blocked.data().get("blocking_task_id"));
                    CompanionTickDispatcher.settle(f.a, original, TaskState.SUCCESS);
                    var syncTask = new TaskRecord("sync_fixture", "ordinary-sync", 1000);
                    TaskDispatch.runSync(f.a, syncTask, ignored -> {});
                    f.drain(); // The previously queued mine must now wait for the sync slot.
                    return List.of(blocked.outcome().name(), knowsCurrent, record(f).pausedReason(),
                            CompanionTickDispatcher.currentTaskFor(f.a.getUUID()) == null
                                    && syncTask.getState() == TaskState.RUNNING);
                } finally { f.close(); }
            }

            public static List<Object> delayedBodyRefusal() throws Exception {
                var f = new MineReceiptFixture(false);
                f.start(); f.drain();
                var original = f.mine;
                f.a.gameTime += 21;
                var entered = new java.util.concurrent.CountDownLatch(1);
                var release = new java.util.concurrent.CountDownLatch(1);
                Thread[] worker = {null};
                try {
                    f.start(); // A distinct, still-current attempt for the same body.
                    f.plugin.gate = new AcxHostGate(5000, () -> new AcxHostGate.HostThread() {
                        public boolean onServerThread() { return false; }
                        public void execute(Runnable task) {
                            worker[0] = new Thread(task, "late-body-admission");
                            worker[0].setDaemon(true); worker[0].start();
                            await(entered, "the host must claim entry before the caller stops waiting");
                            Thread.currentThread().interrupt();
                        }
                    });
                    NumenTool waiting = new NumenTool() {
                        public String name() { return "body_wait_fixture"; }
                        public void onServerCall(String id, JsonObject args, NumenPlayer body, Consumer<String> reply) {
                            entered.countDown();
                            await(release, "host admission may now proceed");
                            TaskDispatch.setTask(body, new TaskRecord(name(), id, 1000), args, reply);
                        }
                    };
                    AcxToolPort.ReplyReceipt originalReply;
                    try {
                        new NumenToolBridgeX(waiting, f.plugin, 5).invoke(Map.of(), ctx(f.run));
                        throw new AssertionError("SETUP ERROR: dispatch did not leave its original reply unresolved");
                    } catch (AcxToolPort.ReplyPendingAfterDispatchException pending) {
                        originalReply = pending.receipt();
                    } finally {
                        Thread.interrupted();
                    }
                    boolean initiallyUnknown = originalReply.poll().isEmpty();
                    release.countDown();
                    worker[0].join(2000);
                    if (worker[0].isAlive()) throw new AssertionError("SETUP ERROR: host admission did not return");
                    var refusal = originalReply.poll().orElseThrow();
                    return List.of(initiallyUnknown, refusal.outcome().name(),
                            original.publicId().equals(refusal.data().get("blocking_task_id")),
                            CompanionTickDispatcher.currentTaskFor(f.a.getUUID()) == original);
                } finally {
                    Thread.interrupted(); release.countDown();
                    if (worker[0] != null) worker[0].join(2000);
                    f.close();
                }
            }

            static class MineReceiptFixture extends Fixture {
                final List<AcxEvent> events = new ArrayList<>();
                final List<Integer> gathered = new ArrayList<>();
                int observations;
                Runnable deliver;
                TaskRecord mine;
                String key;

                MineReceiptFixture(boolean late) { this(late, null, null, null, null); }
                MineReceiptFixture(boolean late, java.util.concurrent.Executor executor,
                        java.util.concurrent.CountDownLatch entered, java.util.concurrent.CountDownLatch release,
                        java.util.concurrent.CountDownLatch observed) { this(late, executor, entered, release, observed, false); }
                MineReceiptFixture(boolean late, java.util.concurrent.Executor executor,
                        java.util.concurrent.CountDownLatch entered, java.util.concurrent.CountDownLatch release,
                        java.util.concurrent.CountDownLatch observed, boolean nested) {
                    var registry = new AcxRegistry();
                    NumenTool tool = new NumenTool() {
                        public String name() { return "mine"; }
                        public void onServerCall(String id, JsonObject args, NumenPlayer p, Consumer<String> reply) {
                            invoked.add(p.name);
                            mine = new TaskRecord("mine", id, 1000);
                            key = p.getUUID() + "/" + mine.publicId();
                            TaskDispatch.setTask(p, mine, args, raw -> {
                                if (late) deliver = () -> reply.accept(raw);
                                else reply.accept(raw);
                            });
                            if (entered != null) {
                                entered.countDown();
                                await(release, "old body call must stay held until observation finishes");
                            }
                        }
                    };
                    adapter = PortToolAdapter.builder(new NumenToolBridgeX(tool, plugin, 5))
                            .probe(NumenToolBridgeX.probeFor(plugin)).build();
                    registry.register(adapter);
                    NumenTool observe = new NumenTool() {
                        public String name() { return "observe_fixture"; }
                        public void onServerCall(String id, JsonObject args, NumenPlayer p, Consumer<String> reply) {
                            if (p != a) throw new AssertionError("observation reached the wrong body");
                            observations++;
                            reply.accept(new Gson().toJson(Map.of("success", true, "data", Map.of("seen", true))));
                        }
                    };
                    registry.register(PortToolAdapter.builder(new NumenToolBridgeX(observe, plugin, 5)).build());
                    registry.register(new AcxTool() {
                        public String name() { return "mine_next"; }
                        public AcxToolSchema schema() { return AcxToolSchema.builder().build(); }
                        public AcxStepOutcome execute(Map<String,Object> params, AcxCallContext ctx) {
                            gathered.add(((Number)params.get("gathered")).intValue());
                            return AcxStepOutcome.success(Map.of());
                        }
                    });
                    var def = new AcxDefinition("binding", "1", "", List.of(), List.of(
                            new AcxStep("body", "mine", Map.of("block_ids", List.of("minecraft:coal_ore"), "count", 4,
                                    "ignore_failure", true)),
                            new AcxStep("next", "mine_next", Map.of("gathered", "$prev.gathered"))), "", "");
                    var child = new AcxDefinition("nested_mine", "1", "", List.of(), def.steps(), "", "");
                    var parent = new AcxDefinition("binding", "1", "", List.of(),
                            List.of(new AcxStep("child", "nested_mine", Map.of())), "", "");
                    var chosen = nested ? parent : def;
                    plugin.sessions = new AcxSessionManager(AcxRunner.builder().tools(registry)
                            .catalog(AcxCatalog.of(Map.of("nested_mine", child))).events(event -> {
                        synchronized (events) { events.add(event); }
                        if (observed != null && event.kind() == AcxEvent.Kind.STEP_SUCCEEDED
                                && "observe_fixture".equals(event.acName())) observed.countDown();
                    }).build(), executor != null ? executor : plugin.queue::add);
                    var observeDef = new AcxDefinition("observe_fixture", "1", "", List.of(),
                            List.of(new AcxStep("observe", "observe_fixture", Map.of())), "", "");
                    plugin.facade = new AcxFacade(plugin.sessions, name -> "observe_fixture".equals(name) ? observeDef : chosen, registry, null);
                }
                void expectPause(String reason, String evidence) {
                    var current = record(this);
                    var pause = events.stream().filter(e -> e.kind() == AcxEvent.Kind.STEP_PAUSED).reduce((a,b) -> b).orElseThrow();
                    var body = evidence == null ? null : Map.of("state", evidence, "progress", "UNKNOWN", "route", "UNKNOWN");
                    if (!run.equals(current.runId()) || current.completedStepIndex() != 0 || current.status() != AcxStatus.PAUSED
                            || !reason.equals(current.pausedReason()) || !reason.equals(pause.detail().get("_pause_reason"))
                            || !Objects.equals(body, pause.detail().get("body_action")) || !gathered.isEmpty()
                            || invoked.size() != 1 || adapter.pendingCount() != 1) throw new AssertionError(pause.detail());
                }
                void finish(TaskRecord terminal, TaskState state) {
                    if (terminal == mine) CompanionTickDispatcher.settle(a, mine, state);
                    else terminal.setState(state);
                    terminal.setResult(TaskResult.ok("fixture result", Map.of("gathered", 4)));
                    CompanionTickDispatcher.finished.put(key, terminal);
                }
                void close() {
                    CompanionTickDispatcher.removeBody(a);
                    if (key != null) CompanionTickDispatcher.finished.remove(key);
                    WorldContextStamp.epoch = 0;
                }
            }
            public static List<Object> immediateMineBodyReply() { return mineBodyReply(false); }
            public static List<Object> lateMineBodyReply() { return mineBodyReply(true); }
            private static List<Object> mineBodyReply(boolean late) {
                var f = new MineReceiptFixture(late);
                try {
                    WorldContextStamp.epoch = 1;
                    f.start(); f.drain();
                    if (late) {
                        f.expectPause("host_reply_pending", null);
                        resumeAndDrain(f);
                        f.expectPause("host_reply_pending", null);
                        // Publish the late reply while a different generation is visible.
                        WorldContextStamp.epoch = 2;
                        f.deliver.run();
                        resumeAndDrain(f);
                    }
                    f.expectPause("async_accepted", "UNKNOWN");
                    WorldContextStamp.epoch = 1;
                    resumeAndDrain(f);
                    f.expectPause("body_active", "ACTIVE");
                    var other = new TaskRecord(f.mine.publicId(), "mine", "wrong-call", 1000);
                    other.bindIdentity(f.mine.identityToken());
                    f.finish(other, TaskState.SUCCESS);
                    resumeAndDrain(f);
                    f.expectPause("body_terminal_unknown", "UNKNOWN");
                    f.finish(f.mine, TaskState.SUCCESS);
                    WorldContextStamp.epoch = 2;
                    resumeAndDrain(f);
                    f.expectPause("body_terminal_unknown", "UNKNOWN");
                    WorldContextStamp.epoch = 1;
                    resumeAndDrain(f);
                    var done = record(f);
                    return List.of(done.status().name(), done.completedStepIndex(), f.invoked.size(), f.gathered,
                            f.adapter.pendingCount(), resumeAndDrain(f));
                } finally { f.close(); }
            }
            public static List<Object> mineTerminalWithoutSuccess() {
                var stopped = new MineReceiptFixture(false);
                String cancelled;
                try {
                    WorldContextStamp.epoch = 1;
                    stopped.start(); stopped.drain();
                    stopped.finish(stopped.mine, TaskState.CANCELLED);
                    resumeAndDrain(stopped);
                    stopped.expectPause("body_terminal_unknown", "UNKNOWN");
                    cancelled = record(stopped).pausedReason();
                } finally { stopped.close(); }
                var f = new MineReceiptFixture(false);
                try {
                    WorldContextStamp.epoch = 1;
                    f.start(); f.drain();
                    f.finish(f.mine, TaskState.FAILED);
                    resumeAndDrain(f);
                    f.expectPause("body_terminal_reobserve", "FAILED");
                    String failed = record(f).pausedReason();
                    CompanionTickDispatcher.finished.remove(f.key);
                    resumeAndDrain(f);
                    f.expectPause("body_terminal_reobserve", "FAILED");
                    return List.of(cancelled, failed, record(f).status().name(), f.gathered.size(), f.invoked.size(),
                            f.adapter.pendingCount());
                } finally { f.close(); }
            }
            public static List<Object> retirePausedBodyReceipts(){
                Fixture f=new Fixture(true);
                f.async=true;
                f.start();
                f.drain();
                String oldRun=f.run;
                var old=f.plugin.sessions.session(oldRun);
                int oldPending=f.adapter.pendingCount();

                NumenPlayer replacement=new NumenPlayer("A");
                f.start(replacement);
                f.drain();
                var current=f.plugin.sessions.session(f.run);
                int bothPending=f.adapter.pendingCount();

                f.plugin.sessions.invalidateBody(f.a);
                return List.of(f.a.getUUID().equals(replacement.getUUID()) && !oldRun.equals(f.run),
                        old.record().status().name(),oldPending,current.record().status().name(),
                        bothPending,f.adapter.pendingCount(),old.ownsTask("t1"),current.ownsTask("t1"));
            }
            public static List<Object> serverStoppedCallback(){
                try {
                    new AcxMod();
                    Fixture f=new Fixture(true,(AcxPlugin)NumenPlugins.registered);
                    f.async=true;f.start();f.drain();
                    var old=f.plugin.sessions.session(f.run);
                    JsonObject oldArgs=f.status();
                    String paused=old.record().status().name();
                    int before=f.adapter.pendingCount();
                    f.start(f.b);
                    var queued=f.plugin.sessions.session(f.run);
                    NeoForge.EVENT_BUS.fireStopped();
                    f.drain();
                    int after=f.adapter.pendingCount();
                    NumenPlayer replacement=new NumenPlayer("A");
                    String reply=request(new AcxFacadeTools.Resume(f.plugin),oldArgs,replacement);
                    boolean resumed=JsonParser.parseString(reply).getAsJsonObject().get("success").getAsBoolean();
                    f.async=false;f.start(replacement);f.drain();
                    return List.of(paused,before,after,old.isBodyInvalidated(),queued.isBodyInvalidated(),
                            old.ownsTask("t1"),resumed,f.plugin.sessions.session(f.run).record().status().name(),
                            f.body(),f.adapter.pendingCount());
                } finally {
                    NeoForge.EVENT_BUS.clear();NumenPlugins.registered=null;
                }
            }
            public static List<Object> lateAcceptanceAfterServerStop(){
                try {
                    new AcxMod();
                    Fixture f=new Fixture(true,true,(AcxPlugin)NumenPlugins.registered);
                    f.start();f.drain();
                    String oldRun=f.run;
                    var old=f.plugin.sessions.session(oldRun);
                    AcxRunRecord paused=old.record();
                    JsonObject oldArgs=f.status();
                    int pendingBeforeStop=f.adapter.pendingCount();
                    if(f.heldReply==null)throw new AssertionError("SETUP ERROR: original tool was never entered");

                    NeoForge.EVENT_BUS.fireStopped();
                    int pendingAfterStop=f.adapter.pendingCount();
                    NumenPlayer replacement=new NumenPlayer("A");
                    f.start(replacement);
                    var current=f.plugin.sessions.session(f.run);
                    // A callback already retained by the original body arrives after it is retired,
                    // when another run with the same companion UUID is already queued.
                    String taskId="late-after-stop";
                    // Even a terminal record with the same public ID must not settle either context.
                    CompanionTickDispatcher.finished.put(replacement.getUUID()+"/"+taskId,
                            new TaskRecord(taskId,"SUCCESS"));
                    f.heldReply.accept(new Gson().toJson(Map.of("success",true,
                            "data",Map.of("async",true,"task_id",taskId))));
                    var probe=NumenToolBridgeX.probeFor(f.plugin);
                    String oldProbe=probe.probe(taskId,ctx(oldRun)).state().name();
                    String newProbe=probe.probe(taskId,ctx(f.run)).state().name();
                    String reply=request(new AcxFacadeTools.Resume(f.plugin),oldArgs,replacement);
                    boolean resumed=JsonParser.parseString(reply).getAsJsonObject().get("success").getAsBoolean();
                    f.drain();
                    return List.of(paused.status().name(),pendingBeforeStop,pendingAfterStop,
                            f.a.getUUID().equals(replacement.getUUID()) && !oldRun.equals(f.run),
                            old.isBodyInvalidated(),resumed,old.ownsTask(taskId) || current.ownsTask(taskId),
                            oldProbe,newProbe,paused.equals(old.record()),current.record().status().name(),
                            f.body(),List.copyOf(f.successorInputs),f.adapter.pendingCount());
                } finally {
                    NeoForge.EVENT_BUS.clear();NumenPlugins.registered=null;
                }
            }

            public static List<Object> retireAfterInFlightReceipt(){
                Fixture f=new Fixture(true);
                f.async=true;
                boolean[] invalidatedWhileRunning={false};
                f.beforeAsyncReply=()->{
                    var original=f.plugin.sessions.session(f.run);
                    f.plugin.sessions.invalidateBody(f.a);
                    invalidatedWhileRunning[0]=original.isRunning() && original.isBodyInvalidated();
                };
                f.start();
                f.drain();
                var original=f.plugin.sessions.session(f.run);
                return List.of(invalidatedWhileRunning[0],f.invoked.size(),f.adapter.pendingCount(),
                        original.ownsTask("t1"),!original.isRunning());
            }
            public static List<Object> lateAcceptance(){
                Fixture f=new Fixture();f.start();
                List<Consumer<String>> callbacks=new ArrayList<>();
                List<String> bodies=new ArrayList<>();
                NumenTool body=new NumenTool(){public String name(){return "binding_body";}
                    public void onServerCall(String id, JsonObject args, NumenPlayer p, Consumer<String> reply){
                        bodies.add(p.name);callbacks.add(reply);
                    }};
                try {
                    new NumenToolBridgeX(body,f.plugin,25).invoke(Map.of(),ctx(f.run));
                    throw new AssertionError("expected an unresolved original reply");
                } catch (AcxToolPort.ReplyPendingAfterDispatchException pending) {
                    var session=f.plugin.sessions.session(f.run);
                    boolean stillWaiting=pending.receipt().poll().isEmpty() && pending.receipt().poll().isEmpty();
                    boolean ownedBeforeReply=session.ownsTask("late-receipt");
                    request(new AcxFacadeTools.Status(f.plugin), f.status(), f.b);
                    callbacks.getFirst().accept(new Gson().toJson(Map.of("success",true,
                            "data",Map.of("async",true,"task_id","late-receipt"))));
                    AcxToolPort.Result accepted=pending.receipt().poll().orElseThrow();
                    return List.of(stillWaiting,ownedBeforeReply,String.join(",",bodies),accepted.outcome().name(),
                            accepted.taskId(),session.ownsTask("late-receipt"));
                }
            }
            public static List<Object> exactTerminalPayload(){
                Fixture f=new Fixture();f.start();
                String id="probe-payload";
                String originalRun=f.run;
                f.plugin.sessions.session(originalRun).noteAcceptedTask(id);
                var original=new TaskRecord(id,"SUCCESS",TaskResult.ok("done",Map.of("amount",42)));
                var other=new TaskRecord(id,"SUCCESS",TaskResult.ok("done",Map.of("amount",7)));
                CompanionTickDispatcher.active.put(f.a.getUUID(),new TaskRecord("replacement","RUNNING"));
                CompanionTickDispatcher.finished.put(f.a.getUUID()+"/"+id,original);
                CompanionTickDispatcher.finished.put(f.b.getUUID()+"/"+id,other);
                try {
                    request(new AcxFacadeTools.Status(f.plugin),f.status(),f.b);
                    var probe=NumenToolBridgeX.probeFor(f.plugin);
                    var found=probe.probe(id,ctx(originalRun));
                    f.start();
                    if(originalRun.equals(f.run))throw new AssertionError("expected a distinct run");
                    var unowned=probe.probe(id,ctx(f.run));
                    CompanionTickDispatcher.finished.put(f.a.getUUID()+"/"+id,
                            new TaskRecord("different","SUCCESS",TaskResult.ok("done",Map.of("amount",99))));
                    var wrong=probe.probe(id,ctx(originalRun));
                    return List.of(found.state().name(),found.data().getOrDefault("amount",-1),
                            unowned.state().name(),unowned.data().isEmpty(),wrong.state().name(),wrong.data().isEmpty());
                } finally {
                    CompanionTickDispatcher.active.remove(f.a.getUUID());
                    CompanionTickDispatcher.finished.remove(f.a.getUUID()+"/"+id);
                    CompanionTickDispatcher.finished.remove(f.b.getUUID()+"/"+id);
                }
            }
            public static String probeInterleave()throws Exception{
                Fixture f=new Fixture();f.start();AcxCompletionProbe probe;
                try{probe=(AcxCompletionProbe)NumenToolBridgeX.class.getDeclaredMethod("probeFor",AcxPlugin.class).invoke(null,f.plugin);}
                catch(NoSuchMethodException legacy){probe=NumenToolBridgeX.probeFor(()->f.plugin.companion);}
                var original=f.plugin.sessions.session(f.run);
                // The legacy seven-case source can still identify the old misbinding independently.
                try {original.getClass().getMethod("noteAcceptedTask",String.class).invoke(original,"t1");}
                catch(NoSuchMethodException legacy) { }
                CompanionTickDispatcher.finished.put(f.a.getUUID()+"/t1",new TaskRecord("t1","SUCCESS"));
                CompanionTickDispatcher.finished.put(f.b.getUUID()+"/t1",new TaskRecord("t1","FAILED"));
                request(new AcxFacadeTools.Status(f.plugin),f.status(),f.b);
                try{return ((AcxCompletionProbe.Snapshot)AcxCompletionProbe.class.getMethod("probe",String.class,AcxTool.AcxCallContext.class).invoke(probe,"t1",ctx(f.run))).state().name();}
                catch(NoSuchMethodException legacy){return probe.probe("t1").state().name();}
            }
        }
        """;
}
