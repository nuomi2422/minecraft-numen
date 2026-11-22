package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Executes extracted production observer methods with real registry/claims; the base inspection and Minecraft types are doubles, not a world scan. */
class RddBaseRecheckSourceTest {
    @TempDir Path temp;

    @Test void baseRecheckMappingRecoveryAndWorldIsolation() throws Exception {
        Path root = RddHomeIdentityLifecycleSourceTest.root();
        Path src = root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd");
        String observer = Files.readString(src.resolve("RddWorldAssetObserver.java"));
        String context = Files.readString(src.resolve("RddAssetContext.java"));
        String store = Files.readString(src.resolve("RddAssetStore.java"));
        String methods = method(observer, "private static int observeBase(")
                + method(observer, "private static void markBaseRecheck(")
                + method(observer, "private static Map<String, Object> common(")
                + method(observer, "private static void apply(")
                + method(observer, "private static Map<String, Integer> topCounts(");
        methods = methods.replace("com.dwinovo.numen.api.ServerIdentityProvider.Snapshot", "Identity");
        String source = HEADER + methods + "}\n"
                + "class RddAssetContext {" + method(context, "static List<AssetRegistry.AssetEntry> worldAssets(") + "}\n"
                + "class RddAssetStore {" + method(store, "static boolean isWorldAsset(") + "}\n" + DOUBLES;
        Path file = temp.resolve("fixture/recheck/Fixture.java");
        Files.createDirectories(file.getParent()); Files.writeString(file, source);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK required");
        // Include the actual registry and Gson, independently of Gradle's worker classpath layout.
        String cp = String.join(System.getProperty("path.separator"), System.getProperty("java.class.path"),
                Path.of(AssetRegistry.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                Path.of(Observation.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                Path.of(com.google.gson.Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        assertEquals(0, compiler.run(null, null, null, "-encoding", "UTF-8", "-proc:none", "-classpath", cp,
                "-d", temp.toString(), file.toString()), "extracted production methods must compile");
        try (var loader = new URLClassLoader(new java.net.URL[]{temp.toUri().toURL()}, getClass().getClassLoader())) {
            var run = loader.loadClass("fixture.recheck.Fixture").getMethod("observe", AssetRegistry.class, String.class, String.class, String.class);
            AssetRegistry registry = new AssetRegistry();
            var original = observation("old", "world-A", "session-A");
            String id = "base|overworld|123";
            registry.apply(original, id, AssetScope.GLOBAL, null);
            registry.apply(observation("other", "world-B", "session-A"), "other-world", AssetScope.GLOBAL, null);
            run.invoke(null, registry, "UNKNOWN", "world-A", "session-A");
            assertEquals(AssetStatus.UNKNOWN, registry.get(id).orElseThrow().status());
            assertFalse(PlanningAssetSnapshot.worldClaims(registry).containsKey(id));
            assertEquals(AssetStatus.OBSERVED, registry.get("other-world").orElseThrow().status());
            assertTrue(registry.history().contains(original));
            run.invoke(null, registry, "ABSENT", "world-A", "session-A");
            assertEquals(AssetStatus.INVALID, registry.get(id).orElseThrow().status());
            assertFalse(PlanningAssetSnapshot.worldClaims(registry).containsKey(id));
            assertTrue(registry.history().contains(original));
            assertEquals(AssetStatus.OBSERVED, registry.get("other-world").orElseThrow().status());
            run.invoke(null, registry, "OBSERVED", "world-A", "session-A");
            assertEquals(AssetStatus.OBSERVED, registry.get(id).orElseThrow().status());
            assertTrue(PlanningAssetSnapshot.worldClaims(registry).containsKey(id));
            assertTrue(registry.history().contains(original));
            assertEquals(3, registry.history().size());
        }
    }

    private static Observation observation(String id, String world, String session) {
        return new Observation(id, "world_base", "fixture", "overworld", 1,
                Map.of("kind", "base", "world_id", world, "server_session", session,
                        "dimension", "overworld", "x", 1, "y", 64, "z", 2));
    }
    private static String method(String source, String signature) {
        return RddHomeIdentityLifecycleSourceTest.method(source, signature);
    }
    private static final String HEADER = """
        package fixture.recheck;
        import java.util.*;
        import java.util.concurrent.ConcurrentHashMap;
        import com.dwinovo.numen.rdd.api.*;
        import com.dwinovo.numen.rdd.core.AssetRegistry;
        public class Fixture {
            static final Map<UUID, Map<String, Long>> SEEN_TICK = new ConcurrentHashMap<>();
            public static void observe(AssetRegistry registry, String status, String world, String session) {
                RddBaseFacts.next = RddBaseFacts.BaseStatus.valueOf(status);
                observeBase(new NumenPlayer(), registry, new Identity(world, session));
            }
        """;
    private static final String DOUBLES = """
        record Identity(String worldId, String serverSession) {}
        class BlockPos {
            static final BlockPos ZERO = new BlockPos(0,0,0);
            final int x,y,z;
            BlockPos(int x,int y,int z) { this.x=x; this.y=y; this.z=z; }
            int getX(){return x;} int getY(){return y;} int getZ(){return z;} long asLong(){return 123;}
            public boolean equals(Object o) { return o instanceof BlockPos p && x==p.x && y==p.y && z==p.z; }
            public int hashCode(){return Objects.hash(x,y,z);}
        }
        class NumenPlayer { UUID getUUID(){return null;} }
        class RddInstrumentation { static long currentGameTimeTicks(){return 0;} }
        class RddWorldFacts {
            record BaseSnapshot(String dimension, BlockPos spawn, boolean complete, List<String> facilities, Map<String,Integer> storedItems) {}
        }
        class RddBaseFacts {
            enum BaseStatus { OBSERVED, UNKNOWN, ABSENT }
            record BaseInspection(BaseStatus status, RddWorldFacts.BaseSnapshot snapshot, String dimension, BlockPos spawn) {}
            static BaseStatus next;
            static BaseInspection inspectBaseResult(NumenPlayer p, Map<String,Object> condition) {
                BlockPos pos = new BlockPos(1,64,2);
                var snapshot = next == BaseStatus.OBSERVED ? new RddWorldFacts.BaseSnapshot("overworld", pos, true, List.of(), Map.of()) : null;
                return new BaseInspection(next, snapshot, "overworld", pos);
            }
        }
        """;
}
