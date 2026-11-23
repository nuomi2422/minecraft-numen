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
    private static Class<?> host, serverClass, ticketClass, plugins;
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
                import com.dwinovo.numen.rdd.api.*;
                import com.dwinovo.numen.rdd.core.*;
                import com.dwinovo.numen.rdd.side.*;
                import net.minecraft.server.MinecraftServer;
                final class SideTaskHost {
                    // Mutable only in this fixture: cutting the engine proves getter cache-only behavior.
                    static SideTaskEngine ENGINE = new SideTaskEngine(null, SideTaskHost::validateMainline);
                    static final SideContextStore SIDE_CONTEXT = new SideContextStore();
                """);
        for (String signature : List.of(
                "    private static void refreshContext(MinecraftServer server, UUID companionId, SideTaskWorldPort port)",
                "    private static void refreshContext(UUID companionId, SideTaskWorldPort port, Map<String, String> stamp)",
                "    private static MainlineResumeToken currentMainlineToken(UUID companionId)",
                "    private static MainlineResumeToken validateMainline(MainlineResumeToken token)",
                "    static String registrationContextFor(UUID companionId, SideContextStore.Ticket current)",
                "    private static String contextXml(String value)")) {
            extracted.append(method(raw, signature)).append('\n');
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
