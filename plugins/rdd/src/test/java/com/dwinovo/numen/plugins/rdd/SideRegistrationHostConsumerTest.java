package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.*;
import com.dwinovo.numen.rdd.side.*;
import com.google.gson.Gson;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source-extracted Host consumer: actual registerRecurring -> server-thread sampling ->
 * request-side cache rendering. Store/Decorator are compiled whole from current sources;
 * Engine, TaskChain and RddRuntime are real core classes, not schedule/owner simulations.
 * Only MC server, capability identity lookup, runtime lookup and world port are IO seams.
 * The plan deliberately has no recurrence: registration facts must come from the engine.
 * This is not a provider-send or live Minecraft test. Fixture errors fail as SETUP ERROR.
 */
class SideRegistrationHostConsumerTest {
    @TempDir static Path temp;
    private static final String PKG = "com.dwinovo.numen.plugins.rdd.";
    private static final UUID SELF = UUID.fromString("00000000-0000-0000-0000-000000000071");
    private static URLClassLoader loader;
    private static Class<?> host, serverClass, ticketClass, advisoryClass, plugins;
    private Object server;
    private SideTaskWorldPort port;
    private TaskChain chain;
    private SideTaskEngine engine;
    private int portReads;
    private static final String UNKNOWN = "<daily_registrations status=\"UNKNOWN\"/>";

    @BeforeAll static void compileOriginalHostConsumers() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("plugins/rdd/src/main/java"))) root = root.getParent();
        assertNotNull(root, "SETUP ERROR: candidate tree unavailable");
        Path main = root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd");
        String raw = Files.readString(main.resolve("SideTaskHost.java"));
        var args = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none", "-classpath",
                location(SideTaskEngine.class) + File.pathSeparator + location(Gson.class)
                        + File.pathSeparator + System.getProperty("java.class.path"),
                "-sourcepath", temp.toString(), "-d", temp.toString()));
        StringBuilder extracted = new StringBuilder("""
                package com.dwinovo.numen.plugins.rdd;
                import java.util.*;
                import java.nio.charset.StandardCharsets;
                import java.nio.file.*;
                import com.google.gson.*;
                import com.dwinovo.numen.rdd.api.*;
                import com.dwinovo.numen.rdd.core.*;
                import com.dwinovo.numen.rdd.side.*;
                import net.minecraft.server.MinecraftServer;
                final class SideTaskHost {
                    // Mutable only in this fixture: cutting the engine proves getter cache-only behavior.
                    static SideTaskEngine ENGINE = new SideTaskEngine(null, SideTaskHost::validateMainline);
                    static final SideContextStore SIDE_CONTEXT = new SideContextStore();
                    // 060-D: the night-B AC projection reads this directory + one bounded mtime cache.
                    static java.nio.file.Path CONFIG_DIR;
                    static volatile String ACX_HINT_CACHE = "";
                    static volatile long ACX_HINT_MTIME = -1L, ACX_HINT_SIZE = -1L;
                """);
        for (String signature : List.of(
                "    private static void refreshContext(MinecraftServer server, UUID companionId, SideTaskWorldPort port)",
                "    private static void refreshContext(UUID companionId, SideTaskWorldPort port, Map<String, String> stamp)",
                "    private static MainlineResumeToken currentMainlineToken(UUID companionId)",
                "    private static MainlineResumeToken validateMainline(MainlineResumeToken token)",
                "    static String registrationContextFor(UUID companionId, SideContextStore.Ticket current)",
                "    static String advisoryContextFor(UUID companionId, String currentWorldId, String currentSession)",
                "    private static String advisory(String type, String world, String session, String dimension",
                "    private static String contextXml(String value)")) {
            extracted.append(method(raw, signature)).append('\n');
        }
        // 060-D: red phase has no reader; the product adds it and the same consumer must compile it.
        if (raw.contains("private static String acxShelterHint(")) {
            extracted.append(method(raw, "    private static String acxShelterHint()"));
        }
        add(args, "com/dwinovo/numen/plugins/rdd/SideTaskHost.java", extracted.append("}\n").toString());
        System.out.println("CONSUMER_SOURCE SideTaskHost.java sha256=" + sha(raw));
        for (String name : List.of("SideContextStore", "SideContextDecorator")) {
            String source = Files.readString(main.resolve(name + ".java"));
            System.out.println("CONSUMER_SOURCE " + name + ".java sha256=" + sha(source));
            add(args, "com/dwinovo/numen/plugins/rdd/" + name + ".java", source);
        }
        // Keep the actual boundary interface and snapshot shape; substitute only its implementation.
        add(args, "com/dwinovo/numen/api/ServerIdentityProvider.java", Files.readString(root.resolve(
                "api/common/src/main/java/com/dwinovo/numen/api/ServerIdentityProvider.java")));
        add(args, "net/minecraft/server/MinecraftServer.java", """
                package net.minecraft.server;
                public final class MinecraftServer {
                    public boolean sameThread = true;
                    public boolean isSameThread() { return sameThread; }
                }
                """);
        add(args, "com/dwinovo/numen/api/NumenPlugins.java", """
                package com.dwinovo.numen.api;
                import net.minecraft.server.MinecraftServer;
                public final class NumenPlugins {
                    public static MinecraftServer active;
                    public static boolean available = true;
                    public static String world = "world", session = "session";
                    public static int snapshots;
                    public static <T> T capability(Class<T> type) {
                        if (!available) return null;
                        ServerIdentityProvider p = server -> {
                            snapshots++;
                            return server == active ? new ServerIdentityProvider.Snapshot(world, session) : null;
                        };
                        return type.cast(p);
                    }
                }
                """);
        // Equipment sample is a seam: null -> the real Host must say UNKNOWN, not "no armor".
        add(args, "com/dwinovo/numen/plugins/rdd/RddDeathEquipment.java", """
                package com.dwinovo.numen.plugins.rdd;
                import com.dwinovo.numen.api.observation.WorldObservationService;
                import java.util.*;
                final class RddDeathEquipment {
                    record Sample(List<String> armor, String mainhand, String offhand) { }
                    static Sample cached(WorldObservationService service, UUID id, String dimension,
                                         Map<String, String> stamp, long now) { return null; }
                }
                """);
        add(args, "com/dwinovo/numen/plugins/rdd/RddPlugin.java", """
                package com.dwinovo.numen.plugins.rdd;
                import java.util.UUID;
                import com.dwinovo.numen.rdd.core.RddRuntime;
                public final class RddPlugin {
                    public static RddRuntime value;
                    public static RddRuntime runtime(UUID id) { return value; }
                }
                """);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "SETUP ERROR: JDK compiler required");
        var diagnostics = new ByteArrayOutputStream();
        assertEquals(0, compiler.run(null, diagnostics, diagnostics, args.toArray(String[]::new)),
                () -> "SETUP ERROR: extracted Host compilation:\n" + diagnostics.toString(StandardCharsets.UTF_8));
        loader = new URLClassLoader(new URL[]{temp.toUri().toURL()}, SideRegistrationHostConsumerTest.class.getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith(PKG) || name.equals("net.minecraft.server.MinecraftServer")
                        || name.equals("com.dwinovo.numen.api.NumenPlugins")
                        || name.startsWith("com.dwinovo.numen.api.ServerIdentityProvider")) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) c = findClass(name);
                    if (resolve) resolveClass(c);
                    return c;
                }
                return super.loadClass(name, resolve);
            }
        };
        host = loader.loadClass(PKG + "SideTaskHost");
        ticketClass = loader.loadClass(PKG + "SideContextStore$Ticket");
        advisoryClass = loader.loadClass(PKG + "SideContextStore$Advisory");
        serverClass = loader.loadClass("net.minecraft.server.MinecraftServer");
        plugins = loader.loadClass("com.dwinovo.numen.api.NumenPlugins");
        for (Class<?> real : List.of(SideTaskEngine.class, TaskChain.class, RddRuntime.class))
            assertSame(real, loader.loadClass(real.getName()), "SETUP ERROR: core must not be shadowed");
        for (String name : List.of("SideTaskHost", "SideContextStore", "SideContextDecorator"))
            assertEquals(temp.toUri().toURL(), loader.loadClass(PKG + name).getProtectionDomain().getCodeSource().getLocation(),
                    "SETUP ERROR: current consumer source must be loaded");
    }

    @AfterAll static void close() throws Exception { if (loader != null) loader.close(); }

    @BeforeEach void reset() throws Exception {
        chain = new TaskChain(new Goal("g", "ordinary mainline without recurrence", List.of(
                new PrimaryGoal("p", "main", List.of(Subtask.aiAssisted("s", "ordinary step", 1, 1, false))))));
        set(loader.loadClass(PKG + "RddPlugin"), "value", new RddRuntime(chain, new AssetRegistry()));
        engine = new SideTaskEngine(null, null);
        set(host, "ENGINE", engine);
        invokeStore("clear");
        server = serverClass.getConstructor().newInstance();
        set(plugins, "active", server); set(plugins, "available", true);
        set(plugins, "world", "world"); set(plugins, "session", "session"); set(plugins, "snapshots", 0);
        portReads = 0;
        port = (SideTaskWorldPort) java.lang.reflect.Proxy.newProxyInstance(
                SideTaskWorldPort.class.getClassLoader(), new Class<?>[]{SideTaskWorldPort.class}, (p, m, a) -> {
                    portReads++;
                    return switch (m.getName()) {
                        case "worldId" -> "minecraft:overworld";
                        case "gameDay" -> 10L;
                        case "gameTime", "dayTime" -> 240000L;
                        // Real server-tick advisory sample reads these too; known standard values only.
                        // Any other read still throws: misread detection stays on.
                        case "hasDayNightCycle" -> true;
                        case "hunger", "health" -> 17;
                        case "edibleCount" -> 3;
                        case "isSleeping", "isSpawnBound", "bodyBusy" -> false;
                        default -> throw new AssertionError("unexpected world read: " + m.getName());
                    };
                });
    }

    @Test void actualRegistrationSurvivesNoActiveSideAndRendersEscapedFacts() throws Exception {
        register("daily:g:one", "water<&\"", true, false);
        assertTrue(engine.current(SELF).isEmpty());
        assertEquals(UNKNOWN, render(ticket(owner(), "session", 10)));
        sample(server);
        assertTrue(engine.current(SELF).isEmpty(), "sampling must not activate a side task");
        String xml = render(ticket(owner(), "session", 10));
        assertTrue(xml.contains("status=\"CURRENT\""), xml);
        assertTrue(xml.contains("<schedule_id>daily:g:one</schedule_id>"), xml);
        assertTrue(xml.contains("<template_id>water&lt;&amp;&quot;</template_id>"), xml);
        assertTrue(xml.contains("<data>{description=a&lt;b&amp;&quot;c&gt;}</data>"), xml);
        assertTrue(xml.contains("<sampled_game_day>10</sampled_game_day><calendar_due>true</calendar_due>"), xml);
        assertTrue(xml.contains("<enabled>true</enabled><prompt_only>false</prompt_only>"), xml);
        assertTrue(xml.contains("<admission>UNKNOWN</admission>"), "calendar fact is not action admission");
        assertEquals(1, plugins.getField("snapshots").get(null));
    }

    @Test void exactGoalBoundaryDoesNotLeakSiblingOrColonChildRegistrations() throws Exception {
        for (String id : List.of("daily:g:own", "daily:g2:sibling", "daily:g:child:nested", "daily:g:"))
            register(id, "template", true, true);
        sample(server);
        String xml = render(ticket(owner(), "session", 10));
        assertTrue(xml.contains("daily:g:own"), xml);
        for (String excluded : List.of("daily:g2:sibling", "daily:g:child:nested", "<schedule_id>daily:g:</schedule_id>"))
            assertFalse(xml.contains(excluded), xml);
        assertEquals(1, xml.split("<registration>", -1).length - 1, xml);
    }

    @Test void sameSessionWrongChainRevisionOrDayIsUnknownAndCannotEvictGoodTicket() throws Exception {
        register("daily:g:one", "template", true, true); sample(server);
        MainlineResumeToken good = owner();
        List<Object> wrong = List.of(ticket(good, "other-session", 10), ticket(good, "session", 11),
                ticket(new MainlineResumeToken(SELF, "replacement-chain", "g", "p", "s", chain.planRevision(), 0), "session", 10),
                ticket(new MainlineResumeToken(SELF, chain.chainInstanceId(), "g", "p", "s", chain.planRevision() + 1, 0), "session", 10));
        for (Object bad : wrong) assertEquals(UNKNOWN, render(bad));
        assertTrue(render(ticket(good, "session", 10)).contains("daily:g:one"));
    }

    @Test void getterNeedsNeitherEngineNorWorldNorIdentityProviderAfterSampling() throws Exception {
        register("daily:g:one", "template", true, true); sample(server);
        Object current = ticket(owner(), "session", 10);
        String sampled = render(current);
        engine.clearRecurring();
        set(host, "ENGINE", null);
        set(plugins, "available", false);
        set(loader.loadClass(PKG + "RddPlugin"), "value", null);
        int reads = portReads;
        assertEquals(sampled, render(current), "request getter must consume cached actual registrations");
        assertEquals(reads, portReads); assertEquals(1, plugins.getField("snapshots").get(null));
    }

    @Test void knownEmptySnapshotIsCurrentWithoutInventingRegistrationsFromPlan() throws Exception {
        sample(server);
        assertEquals("<daily_registrations status=\"CURRENT\"></daily_registrations>", render(ticket(owner(), "session", 10)));
    }

    @Test void invalidServerThreadProviderOrOwnerInvalidatesPreviousRegistrationCache() throws Exception {
        register("daily:g:one", "template", true, true);
        Object current = ticket(owner(), "session", 10);
        sample(server); serverClass.getField("sameThread").set(server, false);
        sample(server); assertEquals(UNKNOWN, render(current));
        assertEquals(1, plugins.getField("snapshots").get(null), "off-thread must not call provider snapshot");
        serverClass.getField("sameThread").set(server, true);
        sample(server); sample(serverClass.getConstructor().newInstance()); assertEquals(UNKNOWN, render(current));
        sample(server); set(plugins, "available", false); sample(server); assertEquals(UNKNOWN, render(current));
        set(plugins, "available", true); sample(server); set(plugins, "world", " "); sample(server);
        assertEquals(UNKNOWN, render(current));
        set(plugins, "world", "world"); sample(server);
        chain.pausePendingForDeathRecovery("s", "recovery_not_verified:test"); sample(server);
        assertEquals(UNKNOWN, render(current), "real validateMainline must reject recovery-paused owner");
    }

    // ---- B advisory: real Host.advisoryContextFor over real Store.Advisory ----

    @Test void advisoryFourFactsReachTheExecutingMain() throws Exception {
        set(plugins, "available", false); // equipment seam unavailable -> the fact must stay UNKNOWN
        putAdvisory("world", "session", "minecraft:overworld", 11_800L, true, 17, 17, 3, false, true, false, 240_000L);
        String xml = advisory(SELF, "world", "session");
        assertTrue(xml.contains("<advisory type=\"equipment\" scope=\"B\""), xml);
        assertTrue(xml.contains("UNKNOWN: current worn equipment observation unavailable"),
                "missing sample must not read as no armor: " + xml);
        assertTrue(xml.contains("<advisory type=\"hp_eat\""), xml);
        assertTrue(xml.contains("hp=17/20 hunger=17/20 edible=3"), xml);
        assertTrue(xml.contains("<advisory type=\"food_reserve\""), xml);
        assertTrue(xml.contains("edible=3 hunger=17/20 health=17/20"), xml);
        assertTrue(xml.contains("enough_for_trip=UNKNOWN (no declared route/reserve requirement)"), xml);
        assertTrue(xml.contains("<advisory type=\"sleep\""), xml);
        assertTrue(xml.contains("dayTime=11800"), "11800 is the existing night window: " + xml);
        assertFalse(xml.contains("<rdd>"), "advisory is inner XML only: " + xml);
    }

    @Test void foodReserveStaysVisibleWhenNotCriticalAndUnknownEdibleIsUnknown() throws Exception {
        set(plugins, "available", false);
        putAdvisory("world", "session", "minecraft:overworld", 0L, true, 20, 20, -1, false, false, false, 240_000L);
        String xml = advisory(SELF, "world", "session");
        assertFalse(xml.contains("<advisory type=\"hp_eat\""), "20/20 is not an eat prompt: " + xml);
        assertTrue(xml.contains("<advisory type=\"food_reserve\""), "reserve is advisory even when not critical: " + xml);
        assertTrue(xml.contains("edible=UNKNOWN"), xml);
        assertFalse(xml.contains("edible=0"), "unknown must not become zero: " + xml);
        assertFalse(xml.contains("armor="), "no invented worn gear: " + xml);
    }

    @Test void fixedDayTimeWithoutNightWindowDoesNotInventSleep() throws Exception {
        set(plugins, "available", false);
        putAdvisory("world", "session", "minecraft:overworld", 0L, true, 17, 17, 3, false, false, false, 240_000L);
        String xml = advisory(SELF, "world", "session");
        assertFalse(xml.contains("<advisory type=\"sleep\""), "fixed dayTime 0 is outside 11800..23000: " + xml);
        assertTrue(xml.contains("<advisory type=\"hp_eat\""), xml);
        assertTrue(xml.contains("<advisory type=\"food_reserve\""), xml);
    }

    @Test void staleWorldOrSessionAndMissingIdentityDoNotBorrowTheSnapshot() throws Exception {
        set(plugins, "available", false);
        putAdvisory("world", "session", "minecraft:overworld", 11_800L, true, 17, 17, 3, false, false, false, 240_000L);
        assertTrue(advisory(SELF, "world", "session").contains("food_reserve"));
        assertEquals("", advisory(SELF, "world", "other-session"));
        assertEquals("", advisory(SELF, "other-world", "session"));
        assertEquals("", advisory(SELF, null, "session"));
        assertEquals("", advisory(null, "world", "session"));
    }

    @Test void advisoryReadsNoWorldOrBody() throws Exception {
        set(plugins, "available", false);
        putAdvisory("world", "session", "minecraft:overworld", 11_800L, true, 17, 17, 3, false, false, false, 240_000L);
        int reads = portReads;
        String xml = advisory(SELF, "world", "session");
        assertTrue(xml.contains("food_reserve"));
        assertEquals(reads, portReads, "advisory must not read the world port: " + xml);
        assertEquals(0, plugins.getField("snapshots").get(null), "advisory must not refresh identity: " + xml);
    }

    /**
     * 060-D：夜B建议只列「当前 active+STABLE 且 tags 含 shelter/emergency」的现成脚本；
     * 撤回/非庇护/缺库都不报可用，只是建议、不读世界/身体、不自动执行。
     */
    @Test void nightAdvisoryShowsOnlyActiveShelterScriptsAndNeverOwnsTheBody() throws Exception {
        set(plugins, "available", false);
        Path cfg = temp.resolve("acx-cfg-" + System.nanoTime());
        Path acx = cfg.resolve("acx");
        Files.createDirectories(acx);
        Files.writeString(acx.resolve("library.json"), """
                {"defs":{
                  "dig3_seal1_emergency":{"active_version":"1","versions":{"1":{"status":"STABLE","definition":{"name":"dig3_seal1_emergency","description":"临时急用：挖三格落地再封顶。","tags":["shelter","emergency"]}}}},
                  "old_withdrawn":{"active_version":"0","versions":{"0":{"status":"WITHDRAWN","definition":{"name":"old_withdrawn","description":"撤回的旧脚本。","tags":["shelter"]}}}},
                  "farm_loop":{"active_version":"3","versions":{"3":{"status":"STABLE","definition":{"name":"farm_loop","description":"农活。","tags":["farm"]}}}}
                }}""");
        set(host, "CONFIG_DIR", cfg);
        putAdvisory("world", "session", "minecraft:overworld", 11_800L, true, 17, 17, 3, false, false, false, 240_000L);
        int reads = portReads;
        String xml = advisory(SELF, "world", "session");
        assertTrue(xml.contains("<advisory type=\"sleep\""), xml);
        assertTrue(xml.contains("dig3_seal1_emergency"), "active shelter script must be visible: " + xml);
        assertTrue(xml.contains("acx_execute ac_name=dig3_seal1_emergency"), xml);
        assertFalse(xml.contains("old_withdrawn"), "withdrawn script must not reappear: " + xml);
        assertFalse(xml.contains("farm_loop"), "non-shelter scripts are not offered here: " + xml);
        assertTrue(xml.contains("建议，不是命令"), "the hint stays advice, never auto-execution: " + xml);
        assertEquals(reads, portReads, "the AC projection must not read the body/world port: " + xml);

        putAdvisory("world", "session", "minecraft:overworld", 0L, true, 17, 17, 3, false, false, false, 240_000L);
        String day = advisory(SELF, "world", "session");
        assertFalse(day.contains("<advisory type=\"sleep\""), day);
        assertFalse(day.contains("dig3_seal1_emergency"), "no night window, no sleep advice: " + day);

        set(host, "CONFIG_DIR", temp.resolve("missing-" + System.nanoTime()));
        putAdvisory("world", "session", "minecraft:overworld", 11_800L, true, 17, 17, 3, false, false, false, 240_000L);
        String missing = advisory(SELF, "world", "session");
        assertTrue(missing.contains("<advisory type=\"sleep\""), missing);
        assertFalse(missing.contains("acx_execute"), "missing library must not claim an active script: " + missing);
    }

    private void putAdvisory(String world, String session, String dim, long dayTime, boolean cycle,
                             int hunger, int health, int edible,
                             boolean sleeping, boolean spawnBound, boolean bodyBusy, long sampled) throws Exception {
        var ctor = advisoryClass.getDeclaredConstructor(String.class, String.class, String.class, long.class,
                boolean.class, int.class, int.class, int.class, boolean.class, boolean.class, boolean.class, long.class);
        ctor.setAccessible(true);
        Object advisory = ctor.newInstance(world, session, dim, dayTime, cycle, hunger, health, edible,
                sleeping, spawnBound, bodyBusy, sampled);
        Field field = host.getDeclaredField("SIDE_CONTEXT"); field.setAccessible(true);
        Object store = field.get(null);
        Method put = store.getClass().getDeclaredMethod("putAdvisory", UUID.class, advisoryClass);
        put.setAccessible(true);
        put.invoke(store, SELF, advisory);
    }

    private String advisory(UUID id, String world, String session) throws Exception {
        Method m = host.getDeclaredMethod("advisoryContextFor", UUID.class, String.class, String.class);
        m.setAccessible(true);
        try {
            return (String) m.invoke(null, id, world, session);
        } catch (InvocationTargetException e) {
            throw new AssertionError("consumer threw: advisoryContextFor", e.getCause());
        }
    }
    private void register(String id, String template, boolean enabled, boolean promptOnly) {
        engine.registerRecurring(new RecurringSideTaskSpec(id, template, 2, 0,
                RecurringSideTaskSpec.MissedPolicy.SKIP, enabled, promptOnly,
                Map.of("description", "a<b&\"c>")), "daily_maintenance");
    }
    private MainlineResumeToken owner() throws Exception {
        return (MainlineResumeToken) invoke("currentMainlineToken", new Class<?>[]{UUID.class}, SELF);
    }
    private Object ticket(MainlineResumeToken token, String session, long day) throws Exception {
        var c = ticketClass.getDeclaredConstructor(String.class, String.class, String.class, MainlineResumeToken.class, long.class);
        c.setAccessible(true); return c.newInstance("world", session, "minecraft:overworld", token, day);
    }
    private void sample(Object concreteServer) throws Exception {
        invoke("refreshContext", new Class<?>[]{serverClass, UUID.class, SideTaskWorldPort.class}, concreteServer, SELF, port);
    }
    private String render(Object ticket) throws Exception {
        return (String) invoke("registrationContextFor", new Class<?>[]{UUID.class, ticketClass}, SELF, ticket);
    }
    private static Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = host.getDeclaredMethod(name, types); m.setAccessible(true);
        try { return m.invoke(null, args); }
        catch (InvocationTargetException e) { throw new AssertionError("consumer threw: " + name, e.getCause()); }
    }
    private static void invokeStore(String name) throws Exception {
        Field f = host.getDeclaredField("SIDE_CONTEXT"); f.setAccessible(true); Object store = f.get(null);
        Method m = store.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(store);
    }
    private static void set(Class<?> type, String name, Object value) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); f.set(null, value);
    }
    private static String location(Class<?> c) throws Exception {
        return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
    private static String sha(String source) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
    }
    private static void add(List<String> args, String relative, String source) throws Exception {
        Path path = temp.resolve(relative); Files.createDirectories(path.getParent()); Files.writeString(path, source); args.add(path.toString());
    }
    private static String method(String source,String signature) {
        int start=source.indexOf(signature);assertTrue(start>=0,"SETUP ERROR: missing "+signature);
        int open=source.indexOf('{',start),depth=0;boolean quoted=false,character=false,line=false,block=false,escape=false;
        for(int i=open;i<source.length();i++){
            char c=source.charAt(i),n=i+1<source.length()?source.charAt(i+1):0;
            if(line){if(c=='\n')line=false;continue;}if(block){if(c=='*'&&n=='/'){block=false;i++;}continue;}
            if(quoted||character){if(escape){escape=false;continue;}if(c=='\\'){escape=true;continue;}if(quoted&&c=='"')quoted=false;if(character&&c=='\'')character=false;continue;}
            if(c=='/'&&n=='/'){line=true;i++;continue;}if(c=='/'&&n=='*'){block=true;i++;continue;}
            if(c=='"'){quoted=true;continue;}if(c=='\''){character=true;continue;}
            if(c=='{')depth++;if(c=='}'&&--depth==0)return source.substring(start,i+1);
        }
        throw new AssertionError("SETUP ERROR: unterminated "+signature);
    }
}
