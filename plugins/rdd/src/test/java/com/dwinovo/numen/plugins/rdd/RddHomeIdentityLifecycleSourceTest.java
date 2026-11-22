package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Source-extraction integration fixture: executes the current observer entry point,
 * identity cache, body registry and WorldContextStamp production method bodies.
 * Minecraft server/body, capability/provider, AssetRegistry and all world scans are
 * explicitly test doubles. This does not exercise the real identity provider, real
 * Minecraft, native event bus, base observation or complete stop cleanup.
 */
class RddHomeIdentityLifecycleSourceTest {
    @TempDir Path temp;

    @Test void serverSamplingBodyBindingEpochAndStop() throws Exception {
        Path root = root();
        Path plugin = root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd");
        String observer = Files.readString(plugin.resolve("RddWorldAssetObserver.java"));
        String production = Files.readString(plugin.resolve("RddPlugin.java"));
        String body = Files.readString(plugin.resolve("RddCompanionBody.java"));
        String stamp = Files.readString(root.resolve("api/common/src/main/java/com/dwinovo/numen/api/WorldContextStamp.java"));
        String clear = method(production, "private static void clearWorldState(");
        assertTrue(production.contains("ServerStoppedEvent event) -> clearWorldState()"), "stop event wiring changed");
        assertTrue(clear.contains("RddCompanionBody.clearAll();"));
        assertTrue(clear.contains("HOME_IDENTITIES.clear();"));
        String fields = between(production, "    private record HomeIdentity(", "    static void captureHomeIdentity(");
        List<Path> sources = new ArrayList<>();
        source(sources, "com/dwinovo/numen/api/WorldContextStamp.java", stamp);
        source(sources, "com/dwinovo/numen/api/ServerIdentityProvider.java", """
            package com.dwinovo.numen.api;
            import net.minecraft.server.MinecraftServer;
            public interface ServerIdentityProvider {
                record Snapshot(String worldId, String serverSession) {}
                Snapshot snapshot(MinecraftServer server);
            }
            """);
        source(sources, "com/dwinovo/numen/api/NumenPlugins.java", """
            package com.dwinovo.numen.api;
            public class NumenPlugins {
                public static ServerIdentityProvider provider;
                public static <T> T capability(Class<T> type) { return type.cast(provider); }
            }
            """);
        source(sources, "net/minecraft/server/MinecraftServer.java", """
            package net.minecraft.server;
            public class MinecraftServer {
                public boolean sameThread = true;
                public boolean isSameThread() { return sameThread; }
            }
            """);
        source(sources, "com/dwinovo/numen/entity/NumenPlayer.java", """
            package com.dwinovo.numen.entity;
            import java.util.UUID;
            import net.minecraft.server.MinecraftServer;
            public class NumenPlayer {
                private final UUID id; private final MinecraftServer server;
                public NumenPlayer(UUID id, MinecraftServer server) { this.id=id; this.server=server; }
                public UUID getUUID() { return id; }
                public MinecraftServer getServer() { return server; }
            }
            """);
        source(sources, "com/dwinovo/numen/rdd/core/AssetRegistry.java", """
            package com.dwinovo.numen.rdd.core;
            public class AssetRegistry {}
            """);
        source(sources, "com/dwinovo/numen/plugins/rdd/RddCompanionBody.java", body);
        source(sources, "com/dwinovo/numen/plugins/rdd/RddPlugin.java", """
            package com.dwinovo.numen.plugins.rdd;
            import java.util.*;
            final class RddPlugin {
            """ + fields + method(production, "static void captureHomeIdentity(")
                + method(production, "static com.dwinovo.numen.api.ServerIdentityProvider.Snapshot cachedHomeIdentity(")
                // Exact cleanup statements from the real stop handler; unrelated cleanup is not simulated.
                + "static void stopFixture() { " + exact(clear, "RddCompanionBody.clearAll();")
                + exact(clear, "HOME_IDENTITIES.clear();") + " }\n}");
        source(sources, "com/dwinovo/numen/plugins/rdd/RddWorldAssetObserver.java", """
            package com.dwinovo.numen.plugins.rdd;
            import com.dwinovo.numen.entity.NumenPlayer;
            import com.dwinovo.numen.rdd.core.AssetRegistry;
            final class RddWorldAssetObserver {
            """ + method(observer, "static Result observe(") + """
            record Result(int bases, int structures, int machines, int entityGroups) {}
            static int observeBase(NumenPlayer b, AssetRegistry r, com.dwinovo.numen.api.ServerIdentityProvider.Snapshot i) { return 0; }
            static int observeStructures(NumenPlayer b, AssetRegistry r) { return 0; }
            static int observeVillages(NumenPlayer b, AssetRegistry r) { return 0; }
            static int observeMachines(NumenPlayer b, AssetRegistry r) { return 0; }
            static int observeEntities(NumenPlayer b, AssetRegistry r) { return 0; }
            static void trim(NumenPlayer b, AssetRegistry r) {}
            }
            """);
        source(sources, "com/dwinovo/numen/plugins/rdd/LifecycleFixture.java", FIXTURE);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "SETUP ERROR: JDK required");
        List<String> args = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none", "-d", temp.toString()));
        sources.forEach(p -> args.add(p.toString()));
        assertEquals(0, compiler.run(null, null, null, args.toArray(String[]::new)), "SETUP ERROR: extracted production code must compile");
        try (var loader = new URLClassLoader(new URL[]{temp.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            var result = (List<?>) loader.loadClass("com.dwinovo.numen.plugins.rdd.LifecycleFixture")
                    .getMethod("run").invoke(null);
            assertEquals(List.of(true, true, true, true, true, true, true, true, true, true, true, true), result);
        }
    }

    static String exact(String source, String statement) {
        assertTrue(source.contains(statement), "missing production statement: " + statement);
        return statement;
    }
    static String between(String s, String start, String end) {
        int a = s.indexOf(start), b = s.indexOf(end, a + 1);
        assertTrue(a >= 0 && b > a, "source markers missing: " + start);
        return s.substring(a, b);
    }
    static String method(String s, String signature) {
        int a = s.indexOf(signature);
        assertTrue(a >= 0, "source method missing: " + signature);
        int open = s.indexOf('{', a), depth = 0;
        for (int i = open; i < s.length(); i++) {
            if (s.charAt(i) == '{') depth++;
            else if (s.charAt(i) == '}' && --depth == 0) return s.substring(a, i + 1) + "\n";
        }
        throw new AssertionError("unterminated method " + signature);
    }
    void source(List<Path> files, String name, String text) throws Exception {
        Path p = temp.resolve("sources").resolve(name);
        Files.createDirectories(p.getParent()); Files.writeString(p, text); files.add(p);
    }
    static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddPlugin.java"))) p = p.getParent();
        assertNotNull(p, "SETUP ERROR: cannot locate checkout root"); return p;
    }

    static final String FIXTURE = """
        package com.dwinovo.numen.plugins.rdd;
        import java.util.*;
        import com.dwinovo.numen.api.*;
        import com.dwinovo.numen.entity.NumenPlayer;
        import com.dwinovo.numen.rdd.core.AssetRegistry;
        import net.minecraft.server.MinecraftServer;
        public class LifecycleFixture {
            public static List<Boolean> run() {
                var checks = new ArrayList<Boolean>();
                var server = new MinecraftServer(); var otherServer = new MinecraftServer();
                var a = UUID.randomUUID(); var b = UUID.randomUUID();
                var bodyA = new NumenPlayer(a, server); var bodyB = new NumenPlayer(b, otherServer);
                var snapshot = new ServerIdentityProvider.Snapshot("world-A", "session-A");
                var seen = new ArrayList<MinecraftServer>();
                NumenPlugins.provider = s -> { seen.add(s); return snapshot; };
                WorldContextStamp.set("world-A", "session-A");
                RddCompanionBody.remember(bodyA); RddCompanionBody.remember(bodyB);
                var registry = new AssetRegistry();
                RddWorldAssetObserver.observe(bodyA, registry);
                checks.add(seen.equals(List.of(server)));
                checks.add(snapshot.equals(RddPlugin.cachedHomeIdentity(a)));
                checks.add(RddPlugin.cachedHomeIdentity(b) == null);
                RddWorldAssetObserver.observe(bodyB, registry);
                checks.add(seen.equals(List.of(server, otherServer)) && snapshot.equals(RddPlugin.cachedHomeIdentity(b)));
                var replacement = new NumenPlayer(a, server); RddCompanionBody.remember(replacement);
                checks.add(RddPlugin.cachedHomeIdentity(a) == null && snapshot.equals(RddPlugin.cachedHomeIdentity(b)));
                RddWorldAssetObserver.observe(replacement, registry);
                checks.add(snapshot.equals(RddPlugin.cachedHomeIdentity(a)));
                WorldContextStamp.set("world-A", "session-A");
                checks.add(RddPlugin.cachedHomeIdentity(a) == null && RddPlugin.cachedHomeIdentity(b) == null);
                RddWorldAssetObserver.observe(replacement, registry);
                checks.add(snapshot.equals(RddPlugin.cachedHomeIdentity(a)));
                WorldContextStamp.set("world-B", "session-A");
                RddWorldAssetObserver.observe(replacement, registry);
                checks.add(RddPlugin.cachedHomeIdentity(a) == null);
                WorldContextStamp.set("world-A", "session-A");
                RddWorldAssetObserver.observe(replacement, registry);
                checks.add(snapshot.equals(RddPlugin.cachedHomeIdentity(a)));
                RddPlugin.stopFixture();
                checks.add(RddPlugin.cachedHomeIdentity(a) == null && RddCompanionBody.get(a) == null);
                RddCompanionBody.remember(replacement);
                checks.add(RddPlugin.cachedHomeIdentity(a) == null);
                return checks;
            }
        }
        """;
}
