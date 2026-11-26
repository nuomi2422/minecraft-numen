package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.TaskChain;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Directed request-consumer probe for the current-subtask instruction.
 * STATUS: NOT EXECUTED. Run only in the integrator's serialized test-only slot.
 * Compiler/extraction/fixture errors are SETUP failures, never assumptions or passing assertions.
 *
 * Real: TaskChain/TaskChain status transitions (PENDING, RUNNING, PAUSED by owner reason,
 * PAUSED by soldier reason, COMPLETED at AWAITING_SUPERVISOR, FAILED, STALLED) and the real
 * renderStateContext body extracted from RddPlugin.
 * Boundaries (documented, not under test here): side-owner store (null => mainline branch),
 * asset/carry/env augmentation, daily registration, rationale binding, negotiation snapshot,
 * death recovery projection, world/body reads. Those are covered by sibling consumer tests.
 * The red contract: a paused/completed/failed/stalled current node must not be ordered as
 * "must execute", while a PENDING/RUNNING node keeps the original execution order.
 */
class RddPausedSubtaskInstructionConsumerTest {
    @TempDir static Path temp;
    static URLClassLoader loader;
    static Class<?> scenario;
    static Path root;
    static final String P = "com/dwinovo/numen/plugins/rdd/";

    @BeforeAll static void setup() throws Exception {
        root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("plugins/rdd/src/main/java"))) root = root.getParent();
        assertNotNull(root, "SETUP: repository root not found");
        assertNotNull(ToolProvider.getSystemJavaCompiler(), "SETUP: full JDK required");
        Map<String,String> units = new LinkedHashMap<>();
        String plugin = source("plugins/rdd/src/main/java/" + P + "RddPlugin.java");
        units.put(P + "RddPlugin.java", """
            package com.dwinovo.numen.plugins.rdd;
            import java.util.*; import com.dwinovo.numen.rdd.api.*; import com.dwinovo.numen.rdd.core.*;
            public class RddPlugin {
              static final Map<UUID,RddRuntime> RUNTIMES = new HashMap<>();
              static final Set<UUID> DECOMPOSING = new HashSet<>();
              static String render(UUID id){ return renderStateContext(id); }
              static String SIDE=null;
              static String ADV_WORLD=null, ADV_SESSION=null;
              static String sideContextFor(UUID id){ return SIDE; } // boundary: side store not under test
              static String advisoryContextFor(UUID id){ return SideTaskHost.advisoryContextFor(id, ADV_WORLD, ADV_SESSION); }
              static String withAssets(UUID id,String text){ return text; } // boundary: assets/carry/env
              static String registeredDailyContext(UUID id, TaskChain chain){ return ""; } // boundary: daily
            """
            + method(plugin, "    private static String renderStateContext(")
            + method(plugin, "    private static String resourceUseContext(")
            + method(plugin, "    static String retrievalTaskFields(")
            + method(plugin, "    private static String escape(")
            // Green contract: the extracted instruction helper must be the one the renderer uses.
            + (plugin.contains("private static String currentSubtaskInstruction(")
                    ? method(plugin, "    private static String currentSubtaskInstruction(") : "")
            + (plugin.contains("static String renderOwnerPause(")
                    ? method(plugin, "    static String renderOwnerPause(") : "")
            + "}");
        units.put(P + "RddRationale.java",
            "package com.dwinovo.numen.plugins.rdd; class RddRationale {"
            + " static void refreshContext(java.util.UUID id, com.dwinovo.numen.rdd.core.TaskChain chain){} }");
        units.put(P + "RddNegotiationInbox.java",
            "package com.dwinovo.numen.plugins.rdd; class RddNegotiationInbox {"
            + " static Object reviewSnapshot(java.util.UUID id){ return java.util.List.of(); } }");
        units.put(P + "SideTaskHost.java", """
            package com.dwinovo.numen.plugins.rdd;
            import java.util.UUID;
            public class SideTaskHost {
              public static String ADVISORY=""; public static int CALLS=0; public static String WORLD=null,SESSION=null;
              public static String advisoryContextFor(UUID id,String world,String session){ CALLS++; WORLD=world; SESSION=session; return ADVISORY; }
            }
            """);
        units.put(P + "PausedScenario.java", """
            package com.dwinovo.numen.plugins.rdd;
            import java.util.*; import com.dwinovo.numen.rdd.api.*; import com.dwinovo.numen.rdd.core.*;
            public class PausedScenario {
              public static String request(String kind){
                UUID id=new UUID(0,1);
                RddPlugin.RUNTIMES.clear(); RddPlugin.DECOMPOSING.clear(); RddPlugin.SIDE=null; SideTaskHost.ADVISORY=""; SideTaskHost.CALLS=0;
                PrimaryGoal primary=PrimaryGoal.unexpanded("P1","phase one",List.of());
                TaskChain chain=new TaskChain(new Goal("G1","objective",List.of(primary)));
                Subtask sub=new Subtask("S1","mine iron",DetectionMode.HARD_CODED,
                    Map.of("asset_key","minecraft:iron_ingot","minimum",7),0,0,false);
                chain.expandCurrentPrimary(List.of(sub));
                if(!kind.equals("pending")){
                  if(!chain.activateCurrent(Map.of())) throw new AssertionError("SETUP: activation");
                  if(kind.equals("paused-owner")) chain.pauseSubtask("S1",TaskChain.OWNER_PAUSE_REASON);
                  else if(kind.equals("paused-soldier")) chain.pauseSubtask("S1","wait until daylight");
                  else if(kind.equals("completed")){
                    if(!chain.applyHardCodedResult("S1",true)) throw new AssertionError("SETUP: completion");
                  }
                  else if(kind.equals("failed")) chain.markFailed("S1","cannot reach");
                  else if(kind.equals("stalled")) chain.markStalled("S1","no progress");
                  else if(!kind.equals("running")) throw new AssertionError("SETUP: unknown kind "+kind);
                }
                RddPlugin.RUNTIMES.put(id,new RddRuntime(chain,new AssetRegistry()));
                String before=chain.toJson();
                String rendered=RddPlugin.render(id);
                if(!before.equals(chain.toJson())) throw new AssertionError("SETUP: render changed task state");
                return rendered+"|STATUS="+chain.currentSubtaskStatus()+"|PRIMARY="+chain.primaryStatus();
              }
              public static String adviceRequest(String kind,String world,String session){
                UUID id=new UUID(0,1);
                RddPlugin.RUNTIMES.clear(); RddPlugin.DECOMPOSING.clear(); RddPlugin.SIDE=null;
                RddPlugin.ADV_WORLD=world; RddPlugin.ADV_SESSION=session;
                SideTaskHost.CALLS=0; SideTaskHost.WORLD=null; SideTaskHost.SESSION=null;
                PrimaryGoal primary=PrimaryGoal.unexpanded("P1","phase one",List.of());
                TaskChain chain=new TaskChain(new Goal("G1","objective",List.of(primary)));
                Subtask sub=new Subtask("S1","mine iron",DetectionMode.HARD_CODED,
                    Map.of("asset_key","minecraft:iron_ingot","minimum",7),0,0,false);
                chain.expandCurrentPrimary(List.of(sub));
                if(!chain.activateCurrent(Map.of())) throw new AssertionError("SETUP: activation");
                if(kind.equals("paused-owner")) chain.pauseSubtask("S1",TaskChain.OWNER_PAUSE_REASON);
                else if(kind.equals("completed") || kind.equals("terminal")){
                  if(!chain.applyHardCodedResult("S1",true)) throw new AssertionError("SETUP: completion");
                  if(kind.equals("terminal"))
                    chain.applySupervisorDecision(new SupervisorDecision(SupervisorDecisionType.CONFIRM, "P1", "owner confirms completion"));
                }
                else if(!kind.equals("running")) throw new AssertionError("SETUP: unknown advice kind "+kind);
                RddPlugin.RUNTIMES.put(id,new RddRuntime(chain,new AssetRegistry()));
                SideTaskHost.ADVISORY="<advisory type='equipment' scope='B'><fact>iron chestplate missing</fact><reason>incoming damage</reason><cost>death loses gear</cost><alternatives>retreat or eat</alternatives><recheck>after threat clears</recheck><instruction>建议，不是命令；你可以自行选择、暂缓或拒绝。</instruction></advisory><advisory type='sleep' scope='B'><fact>night in 55s</fact><reason>bed available</reason><cost>night mobs</cost><alternatives>dig three fill one</alternatives><recheck>in 30s</recheck><instruction>建议，不是命令；你可以自行选择、暂缓或拒绝。</instruction></advisory>";
                String before=chain.toJson();
                String rendered=RddPlugin.render(id);
                if(!before.equals(chain.toJson())) throw new AssertionError("SETUP: advice render changed task state");
                return rendered+"|STATUS="+chain.currentSubtaskStatus()+"|PRIMARY="+chain.primaryStatus()
                    +"|ADVICE_CALLS="+SideTaskHost.CALLS+"|HOST_WORLD="+SideTaskHost.WORLD+"|HOST_SESSION="+SideTaskHost.SESSION;
              }
              public static String sideRequest(String kind){
                UUID id=new UUID(0,1);
                RddPlugin.RUNTIMES.clear(); RddPlugin.DECOMPOSING.clear(); RddPlugin.SIDE=null; SideTaskHost.ADVISORY=""; SideTaskHost.CALLS=0;
                PrimaryGoal primary=PrimaryGoal.unexpanded("P1","phase one",List.of());
                TaskChain chain=new TaskChain(new Goal("G1","objective",List.of(primary)));
                Subtask sub=new Subtask("S1","mine iron",DetectionMode.HARD_CODED,
                    Map.of("asset_key","minecraft:iron_ingot","minimum",7),0,0,false);
                chain.expandCurrentPrimary(List.of(sub));
                if(!chain.activateCurrent(Map.of())) throw new AssertionError("SETUP: activation");
                if(kind.equals("paused-owner")) chain.pauseSubtask("S1",TaskChain.OWNER_PAUSE_REASON);
                else if(kind.equals("paused-soldier")) chain.pauseSubtask("S1","wait until daylight");
                else throw new AssertionError("SETUP: unknown side kind "+kind);
                RddPlugin.RUNTIMES.put(id,new RddRuntime(chain,new AssetRegistry()));
                RddPlugin.SIDE="<rdd><enabled>true</enabled><active>true</active><side_task type='sleep' instance='side-identity-"+kind+"'>overnight sleep</side_task><side_context_status>CURRENT</side_context_status></rdd>";
                String before=chain.toJson();
                String rendered=RddPlugin.render(id);
                if(!before.equals(chain.toJson())) throw new AssertionError("SETUP: side render changed task state");
                return rendered+"|STATUS="+chain.currentSubtaskStatus();
              }
            }
            """);
        var args = new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-sourcepath",temp.toString(),
                "-classpath", classpath(), "-d", temp.toString()));
        for (var entry : units.entrySet()) {
            Path file = temp.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
            args.add(file.toString());
        }
        var diagnostics = new ByteArrayOutputStream();
        int status = ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics, args.toArray(String[]::new));
        assertEquals(0, status, "SETUP: isolated consumer compilation failed (not a business result):" + diagnostics);
        loader = new URLClassLoader(new URL[]{temp.toUri().toURL()}, RddPausedSubtaskInstructionConsumerTest.class.getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String n, boolean resolve) throws ClassNotFoundException {
                if (Files.exists(temp.resolve(n.replace('.', '/') + ".class"))) {
                    Class<?> type = findLoadedClass(n);
                    if (type == null) type = findClass(n);
                    if (resolve) resolveClass(type);
                    return type;
                }
                return super.loadClass(n, resolve);
            }
        };
        scenario = loader.loadClass("com.dwinovo.numen.plugins.rdd.PausedScenario");
    }

    @AfterAll static void close() throws Exception { if (loader != null) loader.close(); }

    @Test void pendingNodeKeepsOriginalExecutionOrder() throws Exception {
        String user = request("pending");
        assertTrue(user.contains("STATUS=PENDING"), user);
        assertTrue(user.contains("必须执行"), user);
    }

    @Test void runningNodeKeepsOriginalExecutionOrder() throws Exception {
        String user = request("running");
        assertTrue(user.contains("STATUS=RUNNING"), user);
        assertTrue(user.contains("必须执行"), user);
        // 060-C：前提失效/要放弃之前的明确行为指引（不是恢复旧自动抢占）。
        assertTrue(user.contains("新鲜事实"), "active order must ask for a fresh prerequisite check: " + user);
        assertTrue(user.contains("少量补"), "small local remediation must be allowed before giving up: " + user);
        assertTrue(user.contains("expected_subtask_id"), "concern must carry the current subtask id: " + user);
        assertTrue(user.contains("写观察到的事实"), "concern reason must be a concrete fact, not a bare refusal: " + user);
        assertTrue(user.contains("不要只说没有"), "a bare 'missing/unavailable' must be refused: " + user);
        assertTrue(user.contains("日常建议可先放着"), "unrelated daily advice must be deferrable: " + user);
    }

    @Test void ownerPausedNodeMustNotBeOrderedAndOnlyOwnerReleases() throws Exception {
        String user = request("paused-owner");
        assertTrue(user.contains("STATUS=PAUSED"), user);
        assertFalse(user.contains("必须执行"), "owner standby must not demand execution: " + user);
        assertTrue(user.contains("已暂停"), user);
        assertTrue(user.contains(TaskChain.OWNER_PAUSE_REASON), "owner reason must stay visible: " + user);
        assertTrue(user.contains("只有主人"), "owner standby must say only the owner releases it: " + user);
        // 060-C：待命中可以报事实，但绝不能借此自行恢复或反向请求重规划。
        assertTrue(user.contains("可以如实上报"), "owner standby may still report unexecutable facts: " + user);
        assertTrue(user.contains("不要借此要求重规划"), "reporting must not become a replan request: " + user);
        assertTrue(user.contains("不构成解除待命的理由"), "reporting must not dissolve owner pause: " + user);
    }

    @Test void soldierPausedNodeMustNotBeOrderedAndMustNotClaimOwnerAuthority() throws Exception {
        String user = request("paused-soldier");
        assertTrue(user.contains("STATUS=PAUSED"), user);
        assertFalse(user.contains("必须执行"), "paused node must not demand execution: " + user);
        assertTrue(user.contains("wait until daylight"), user);
        assertFalse(user.contains("只有主人"), "soldier pause is not an owner order: " + user);
    }

    @Test void completedNodeAtSupervisorGateMustNotBeOrderedAgain() throws Exception {
        String user = request("completed");
        assertTrue(user.contains("STATUS=COMPLETED"), user);
        assertTrue(user.contains("PRIMARY=AWAITING_SUPERVISOR"), user);
        assertFalse(user.contains("必须执行"), "completed node must not be ordered again: " + user);
        assertTrue(user.contains("已完成"), user);
    }

    @Test void failedNodeMustNotBeOrderedAsRunnable() throws Exception {
        String user = request("failed");
        assertTrue(user.contains("STATUS=FAILED"), user);
        assertFalse(user.contains("必须执行"), user);
        assertTrue(user.contains("FAILED"), user);
    }

    @Test void stalledNodeWaitsForSupervisionWithoutBeingDeclaredUnusable() throws Exception {
        String user = request("stalled");
        assertTrue(user.contains("STATUS=STALLED"), user);
        assertFalse(user.contains("必须执行"), user);
        assertTrue(user.contains("复评"), "stalled stays on the host wait/recheck path: " + user);
        assertFalse(user.contains("永久"), "stalled must not be declared permanently unusable: " + user);
    }

    @Test void sideCurrentKeepsOwnerPauseMainlineButLeavesSurvivalOpen() throws Exception {
        String user = sideRequest("paused-owner");
        assertTrue(user.contains("STATUS=PAUSED"), user);
        assertTrue(user.contains("owner_pause"), "owner pause must be raised into the same rdd fragment: " + user);
        assertTrue(user.contains("<owner_pause scope=\"MAIN\"><chain_instance>"),
                "owner pause must be scoped to the mainline and bound to the related chain: " + user);
        assertTrue(user.contains("sleep/FC") && user.contains("不禁止"),
                "owner pause must keep survival side actions open, not a global ban: " + user);
        assertTrue(user.contains(TaskChain.OWNER_PAUSE_REASON), "owner reason must stay visible: " + user);
        assertTrue(user.contains("只有主人"), "owner standby must say only the owner releases it: " + user);
        assertTrue(user.contains("side-identity-paused-owner"), "side owner must remain the current body: " + user);
        assertFalse(user.contains("必须执行"), "paused mainline under a current side must not be ordered: " + user);
    }

    @Test void sideCurrentSoldierPauseDoesNotFakeOwnerSeal() throws Exception {
        String user = sideRequest("paused-soldier");
        assertTrue(user.contains("STATUS=PAUSED"), user);
        assertFalse(user.contains("owner_pause"), "a soldier pause is not an owner seal: " + user);
        assertFalse(user.contains("只有主人"), "soldier pause must not claim owner authority: " + user);
        assertTrue(user.contains("side-identity-paused-soldier"), user);
    }

    @Test void mainlineRunningKeepsExecutionOrderAndAppendsAdvisory() throws Exception {
        String user = advice("running");
        assertTrue(user.contains("STATUS=RUNNING"), user);
        assertTrue(user.contains("<current_task>"), "MAIN current_task must stay: " + user);
        assertTrue(user.contains("<subtask_status>RUNNING</subtask_status>"), user);
        assertTrue(user.contains("必须执行"), "MAIN RUNNING keeps the original execution order: " + user);
        assertTrue(user.contains("|ADVICE_CALLS=1"), "the real request consumer must call the host advisory seam once: " + user);
        assertTrue(user.contains("<advisory type='equipment'"), user);
        assertTrue(user.contains("<advisory type='sleep'"), user);
        assertTrue(user.contains("<reason>") && user.contains("<cost>")
                && user.contains("<alternatives>") && user.contains("<recheck>"), user);
        assertTrue(user.contains("建议") && (user.contains("可拒") || user.contains("自行选择")), user);
        assertFalse(user.contains("SIDE_TASK"), user);
        assertFalse(user.contains("主线已临时冻结"), user);
    }

    @Test void ownerPausedMainlineKeepsOwnerSealAndAdvisoryDoesNotWakeIt() throws Exception {
        String user = advice("paused-owner");
        assertTrue(user.contains("STATUS=PAUSED"), user);
        assertTrue(user.contains("只有主人"), user);
        assertFalse(user.contains("必须执行"), "owner standby must not be ordered: " + user);
        assertTrue(user.contains("<advisory type='equipment'"), user);
        assertFalse(user.contains("SIDE_TASK"), user);
    }

    @Test void advisoryUsesCurrentRequestIdentityNotAGlobalStamp() throws Exception {
        String a = advice("running", "world-A", "session-A");
        assertTrue(a.contains("|HOST_WORLD=world-A") && a.contains("|HOST_SESSION=session-A"), a);
        String b = advice("running", "world-B", "session-B");
        assertTrue(b.contains("|HOST_WORLD=world-B") && b.contains("|HOST_SESSION=session-B"), b);
        assertFalse(a.contains("world-B") && b.contains("world-A"), "identity must follow the current request");
        String empty = advice("running", null, null);
        assertTrue(empty.contains("|HOST_WORLD=null") && empty.contains("|HOST_SESSION=null"), empty);
    }

    @Test void terminalCompletedChainDoesNotGetAdvisory() throws Exception {
        String user = advice("terminal");
        assertTrue(user.contains("STATUS=COMPLETED"), user);
        assertTrue(user.contains("PRIMARY=COMPLETED"), user);
        assertTrue(user.contains("<state>chain_completed</state>"), user);
        assertTrue(user.contains("ADVICE_CALLS=0"), "a completed chain has no live request to wake: " + user);
        assertFalse(user.contains("<advisory"), "terminal must not be woken by B: " + user);
    }

    @Test void awaitingSupervisorCompletedNodeKeepsSurvivalAdvisoryButNotExecutionOrder() throws Exception {
        String user = advice("completed");
        assertTrue(user.contains("STATUS=COMPLETED"), user);
        assertTrue(user.contains("PRIMARY=AWAITING_SUPERVISOR"), user);
        assertTrue(user.contains("<subtask_status>COMPLETED</subtask_status>"), user);
        assertTrue(user.contains("ADVICE_CALLS=1"), "a completed current node still carries survival B advice: " + user);
        assertTrue(user.contains("<advisory type='equipment'"), user);
        assertFalse(user.contains("必须执行"), "completed current node must not be ordered: " + user);
        assertTrue(user.contains("已完成"), user);
    }

    static String advice(String kind) throws Exception { return advice(kind, null, null); }

    static String advice(String kind, String world, String session) throws Exception {
        try { return (String) scenario.getMethod("adviceRequest", String.class, String.class, String.class)
                .invoke(null, kind, world, session); }
        catch (java.lang.reflect.InvocationTargetException e) {
            throw new AssertionError("SETUP/adviceRequest failed before business assertions", e.getCause());
        }
    }

    static String sideRequest(String kind) throws Exception {
        try { return (String) scenario.getMethod("sideRequest", String.class).invoke(null, kind); }
        catch (java.lang.reflect.InvocationTargetException e) {
            throw new AssertionError("SETUP/sideRequest failed before business assertions", e.getCause());
        }
    }

    static String request(String kind) throws Exception {
        try { return (String) scenario.getMethod("request", String.class).invoke(null, kind); }
        catch (java.lang.reflect.InvocationTargetException e) {
            throw new AssertionError("SETUP/request failed before business assertions", e.getCause());
        }
    }

    static String source(String path) throws IOException { return Files.readString(root.resolve(path)); }

    static String method(String text, String signature) {
        int a = text.indexOf(signature);
        assertTrue(a >= 0, "SETUP: method missing: " + signature);
        int indent = signature.indexOf(signature.stripLeading());
        String close = "\n" + " ".repeat(indent) + "}";
        int b = text.indexOf(close, a);
        assertTrue(b > a, "SETUP: method end missing: " + signature);
        return text.substring(a, b + close.length()) + "\n";
    }

    static String classpath() throws Exception {
        var entries = new LinkedHashSet<String>(List.of(
                System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator))));
        for (Class<?> c : List.of(TaskChain.class,
                com.dwinovo.numen.rdd.core.RddRuntime.class,
                com.dwinovo.numen.rdd.core.AssetRegistry.class))
            entries.add(Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        return String.join(File.pathSeparator, entries);
    }
}
