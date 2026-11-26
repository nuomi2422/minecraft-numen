package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.TaskChain;
import com.google.gson.Gson;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * NOT EXECUTED: reserved for the parent's serialized Java/Gradle slot.
 * Independent additive test; the existing concurrent consumer test is read, never edited or run.
 * Reuses its source extraction/compiler shell, state registry, production Plugin renderer,
 * EntityAgentLoop snapshot/section allocation and AgentRequestContext.attach, not a hand-built prompt.
 * Real: DAILY registration and engine/core types, Store.putRegistrations/cache, Host registration
 * renderer/contextXml, Plugin cached-observation helper, final attached User.
 * Explicit boundary: cache is populated via real Store.putRegistrations from the real engine snapshot;
 * this does NOT test Host tick sampling, actual world provider, MC scheduling or network serialization.
 * Provider responses use unchanged observation DTO sources. Body/history/assets/retrieval remain the
 * existing shell's empty boundaries. This is an extracted request-assembly probe, NOT a full live loop.
 * Every extraction/compilation/reflection failure is SETUP failure, never skipped or accepted as UNKNOWN.
 */
class RddRegisteredDailyRequestConsumerTest {
    @TempDir static Path temp;
    static Path root;
    static URLClassLoader bootstrapLoader;
    static Class<?> bootstrap;
    static Class<?> scenario;
    static final String P = "com/dwinovo/numen/plugins/rdd/";
    static final String A = "com/dwinovo/numen/api/";

    @BeforeAll static void setup() throws Exception {
        root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("plugins/rdd/src/main/java"))) root = root.getParent();
        assertNotNull(root, "SETUP: repository root missing");
        assertNotNull(ToolProvider.getSystemJavaCompiler(), "SETUP: full JDK required");
        String original = source("plugins/rdd/src/test/java/" + P + "RddConcurrentTaskRequestConsumerTest.java");
        String shell = original.replace("RddConcurrentTaskRequestConsumerTest", "RegisteredDailyBootstrap");
        int tests = shell.indexOf("    @Test void ");
        int utilities = shell.indexOf("    static String source(", tests);
        assertTrue(tests >= 0 && utilities > tests, "SETUP: reusable compiler shell changed");
        shell = shell.substring(0, tests) + shell.substring(utilities);
        shell = replaceOnce(shell, "        boundaryUnits(units);", "        boundaryUnits(units);\n        adjustUnits(units);");
        String self = source("plugins/rdd/src/test/java/" + P + "RddRegisteredDailyRequestConsumerTest.java");
        shell = shell.substring(0, shell.lastIndexOf('}')) + method(self, "    static void " + "adjustUnits(") + "}\n";
        Path file = temp.resolve("bootstrap/" + P + "RegisteredDailyBootstrap.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, shell);
        var cp = new LinkedHashSet<String>(List.of(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator))));
        for (Class<?> type : List.of(TaskChain.class, Gson.class, Test.class, Assertions.class, TempDir.class,
                com.dwinovo.numen.agent.llm.ConvoState.class, RddRegisteredDailyRequestConsumerTest.class))
            cp.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        var diagnostics = new ByteArrayOutputStream();
        int status = ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics,
                "-encoding", "UTF-8", "-proc:none", "-classpath", String.join(File.pathSeparator, cp),
                "-d", temp.resolve("bootstrap").toString(), file.toString());
        assertEquals(0, status, "SETUP: reusable bootstrap compilation failed:\n" + diagnostics);
        bootstrapLoader = new URLClassLoader(new URL[]{temp.resolve("bootstrap").toUri().toURL()}, RddRegisteredDailyRequestConsumerTest.class.getClassLoader());
        bootstrap = bootstrapLoader.loadClass("com.dwinovo.numen.plugins.rdd.RegisteredDailyBootstrap");
        var directory = bootstrap.getDeclaredField("temp"); directory.setAccessible(true); directory.set(null, temp.resolve("consumer"));
        var setup = bootstrap.getDeclaredMethod("setup"); setup.setAccessible(true);
        try { setup.invoke(null); }
        catch (java.lang.reflect.InvocationTargetException e) { throw new AssertionError("SETUP: consumer shell failed", e.getCause()); }
        var result = bootstrap.getDeclaredField("scenario"); result.setAccessible(true); scenario = (Class<?>) result.get(null);
    }

    @AfterAll static void close() throws Exception {
        if (bootstrap != null) { var close = bootstrap.getDeclaredMethod("close"); close.setAccessible(true); close.invoke(null); }
        if (bootstrapLoader != null) bootstrapLoader.close();
    }

    @Test void sameIdentityDayRegistrationAndMainReachOneAttachedUser() throws Exception {
        String user = request("current");
        assertTrue(user.contains("MAIN_IRON_73"));
        String registration = registration(user);
        assertTrue(registration.contains("status=\"CURRENT\""));
        assertTrue(registration.contains("REGISTERED_ONLY_WHEAT_82"));
        assertTrue(registration.contains("minecraft:wheat"));
        assertTrue(registration.contains("<sampled_game_day>1</sampled_game_day>"));
        assertTrue(registration.contains("<calendar_due>true</calendar_due>"));
        assertTrue(registration.contains("<admission>UNKNOWN</admission>"));
        assertEquals(1, user.split("<daily_registrations", -1).length - 1);
    }

    @Test void invalidObservationOrCacheOwnerNeverLeaksRegistrationPayload() throws Exception {
        assertTrue(registration(request("current")).contains("REGISTERED_ONLY_WHEAT_82"), "positive control");
        for (String mode : List.of("stale-self", "expired-clock", "masked-self", "masked-clock", "stale-field",
                "fractional-day", "negative-day", "next-day", "mixed-session", "cross-session", "cross-chain", "revision")) {
            String user = request(mode);
            assertTrue(user.contains("MAIN_IRON_73"), "main positive control: " + mode);
            assertEquals("<daily_registrations status=\"UNKNOWN\"/>", registration(user), mode);
            assertFalse(user.contains("REGISTERED_ONLY_WHEAT_82"), mode);
            assertFalse(user.contains("minecraft:wheat"), mode);
        }
    }

    static String registration(String user) {
        int start = user.indexOf("<daily_registrations");
        assertTrue(start >= 0, "registration block missing from final User: " + user);
        int openEnd = user.indexOf('>', start);
        assertTrue(openEnd > start);
        if (user.charAt(openEnd - 1) == '/') return user.substring(start, openEnd + 1);
        int end = user.indexOf("</daily_registrations>", openEnd);
        assertTrue(end > openEnd, "unclosed registration block");
        return user.substring(start, end + "</daily_registrations>".length());
    }

    static String request(String mode) throws Exception {
        try { return (String) scenario.getMethod("request", String.class, boolean.class).invoke(null, mode, false); }
        catch (java.lang.reflect.InvocationTargetException e) { throw new AssertionError("SETUP/request failed: " + mode, e.getCause()); }
    }

    static String source(String path) throws IOException { return Files.readString(root.resolve(path)); }
    static String replaceOnce(String text, String before, String after) {
        int start = text.indexOf(before);
        assertTrue(start >= 0 && text.indexOf(before, start + before.length()) < 0, "SETUP: unique source anchor missing: " + before);
        return text.substring(0, start) + after + text.substring(start + before.length());
    }
    static String method(String text, String signature) {
        int start = text.indexOf(signature); assertTrue(start >= 0, "SETUP: method missing: " + signature);
        String close = "\n" + " ".repeat(signature.indexOf(signature.stripLeading())) + "}";
        int end = text.indexOf(close, start); assertTrue(end > start, "SETUP: method end missing: " + signature);
        return text.substring(start, end + close.length()) + "\n";
    }

    // This method is extracted verbatim into the reusable compiler shell. Only fixture wiring changes.
    static void adjustUnits(Map<String,String> units) throws Exception {
        String np = units.get(A + "NumenPlugins.java");
        String capability = "public static <T> T capability(Class<T> c){";
        int a = np.indexOf(capability), b = np.indexOf("\n", a);
        assertTrue(a >= 0 && b > a, "SETUP: capability fixture anchor missing");
        np = np.substring(0, a) + "public static com.dwinovo.numen.api.observation.WorldObservationService observation; public static <T> T capability(Class<T> c){ if(c == com.dwinovo.numen.api.observation.WorldObservationService.class) return c.cast(observation); return c == ServerIdentityProvider.class ? c.cast((ServerIdentityProvider)(s -> new ServerIdentityProvider.Snapshot(\"world\", Identity.session))) : null; }" + np.substring(b);
        units.put(A + "NumenPlugins.java", np);
        for (String name : List.of("WorldObservationService", "ObservationMetadata", "ObservationBatch", "ObservationBatchReader", "ChangesQuery", "ChangesV2Response"))
            copy(units, A + "observation/" + name + ".java", "api/common/src/main/java/");
        units.put(P + "ConcurrentScenario.java", """
            package com.dwinovo.numen.plugins.rdd;
            import java.util.*;
            import com.dwinovo.numen.rdd.api.*;
            import com.dwinovo.numen.rdd.core.*;
            import com.dwinovo.numen.rdd.side.*;
            import com.dwinovo.numen.api.observation.*;
            import static com.dwinovo.numen.api.observation.WorldObservationService.*;
            public class ConcurrentScenario {
              static final UUID ID = new UUID(0,1);
              static final String DIM = "minecraft:overworld";
              static boolean registered;
              static class CachedProvider implements WorldObservationService {
                final String mode; int selfReads, clockReads, refreshes;
                CachedProvider(String mode){this.mode=mode;}
                public Response readSelf(String consumer, UUID id, long age){
                  selfReads++;
                  if(!ID.equals(id) || age != 3000 || !consumer.equals("rdd-daily-request")) throw new AssertionError("readSelf binding");
                  return sample(true);
                }
                public Response read(Request r){
                  clockReads++;
                  if(!"environment".equals(r.topic()) || !DIM.equals(r.dimension()) || r.maxAgeMs()!=3000 || !r.selector().isEmpty()) throw new AssertionError("cached clock binding");
                  return sample(false);
                }
                public Response refresh(Request r){refreshes++; throw new AssertionError("request assembly must never refresh");}
                Response sample(boolean self){
                  long now=System.currentTimeMillis();
                  String session=mode.equals("cross-session") || (!self && mode.equals("mixed-session")) ? "foreign" : "current";
                  long observed=now - (self && mode.equals("stale-self") ? 10000 : 0);
                  var meta=new ObservationMetadata("world",session,DIM,self?ID.toString():"", "fixture-cache",self?"self-key":"clock-key",1,observed,
                    ObservationMetadata.Freshness.CURRENT,"SELF",!self && mode.equals("expired-clock")?now-1:now+60000);
                  Set<String> unknown=(self && mode.equals("masked-self"))?Set.of("companionUUID"):
                    (!self && mode.equals("masked-clock"))?Set.of(""):Set.of();
                  Set<String> stale=!self && mode.equals("stale-field")?Set.of("day_time"):Set.of();
                  Number ticks=mode.equals("fractional-day")?new java.math.BigDecimal("24000.5"):
                    mode.equals("negative-day")?Long.valueOf(-1):mode.equals("next-day")?Long.valueOf(48000):Long.valueOf(24000);
                  Map<String,Object> values=self?Map.of("companionUUID",ID.toString(),"dimension",DIM):Map.of("day_time",ticks,"dimension",DIM);
                  return new Response(Status.OK,self?"self":"environment","cache",1,Coverage.FULL,null,List.of(new Item(values,unknown,stale)),0,meta);
                }
              }
              public static String request(String mode,boolean ignored){
                SideTaskHost.SIDE_CONTEXT.clear(); RddPlugin.RUNTIMES.clear(); RddPlugin.DECOMPOSING.clear();
                com.dwinovo.numen.api.Identity.session="current";
                com.dwinovo.numen.api.RationaleContext.clear(ID);
                TaskChain chain=new TaskChain(new Goal("g","iron expedition",List.of(PrimaryGoal.unexpanded("p","prepare iron",List.of()))));
                var main=RddGoalDriver.buildInjection("p",1,List.of(new SubtaskSpec("MAIN_IRON_73",Map.of("asset_key","minecraft:iron_ingot","minimum",7),null,NodeType.MAIN,null)));
                chain.expandCurrentPrimary(main);
                if(!chain.activateCurrent(Map.of())) throw new AssertionError("SETUP: main activation");
                var engine=new SideTaskEngine(null,null); SideTaskHost.ENGINE=engine;
                engine.registerType(new DailyMaintenanceSideTaskType());
                var daily=RddGoalDriver.buildInjection("p",1,List.of(new SubtaskSpec("REGISTERED_ONLY_WHEAT_82",Map.of("asset_key","minecraft:wheat","minimum",4),null,NodeType.DAILY,RecurrenceSpec.daily(false))));
                SideTaskHost.DailyRegistration.register(chain,daily,engine);
                var specs=engine.recurringSpecsSnapshot();
                if(specs.size()!=1 || !"REGISTERED_ONLY_WHEAT_82".equals(specs.get(0).data().get("description")) || !specs.get(0).dueOn(1)) throw new AssertionError("SETUP: real engine DAILY registration");
                var owner=new MainlineResumeToken(ID,chain.chainInstanceId(),chain.goal().id(),chain.currentPrimary().id(),chain.currentSubtask().id(),chain.planRevision(),chain.planRevision());
                // Real Store seam, NOT Host sampling; no pre-rendered prompt is inserted.
                SideTaskHost.SIDE_CONTEXT.putRegistrations(ID,new SideContextStore.Ticket("world","current",DIM,owner,1),specs);
                if(mode.equals("cross-chain")) {
                  var replacement=new TaskChain(chain.goal()); replacement.startCurrent(); chain=replacement;
                }
                if(mode.equals("revision")) chain.nextPlanRevision();
                RddPlugin.RUNTIMES.put(ID,new RddRuntime(chain,new AssetRegistry()));
                var provider=new CachedProvider(mode); com.dwinovo.numen.api.NumenPlugins.observation=provider;
                if(!registered){RddPlugin.register();registered=true;}
                String before=chain.toJson(); String engineBefore=engine.recurringSpecsSnapshot().toString();
                String user=new com.dwinovo.numen.client.agent.EntityAgentLoop().finalUser();
                if(provider.selfReads<1 || (!mode.equals("masked-self") && provider.clockReads<1) || provider.refreshes!=0) throw new AssertionError("cached-only helper not traversed");
                if(!before.equals(chain.toJson()) || !engineBefore.equals(engine.recurringSpecsSnapshot().toString())) throw new AssertionError("read projection mutated tasks");
                return user;
              }
            }
            """);
    }

    static void copy(Map<String,String> units,String path,String prefix) throws IOException { units.put(path,source(prefix+path)); }
}
