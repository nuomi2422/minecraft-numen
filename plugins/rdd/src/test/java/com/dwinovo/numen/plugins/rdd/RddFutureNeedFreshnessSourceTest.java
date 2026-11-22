package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes the verbatim cachedCounts, tick/block and rendering methods plus the real
 * FutureNeedScanner source. Cache writes, scan success/failure, clock age, world/session,
 * goal objects, MC and monitoring are controlled fixtures, NOT real MC refresh or identity
 * lifecycle. The identity fixture changes context without simulating production cleanup.
 * These are desired-contract regression tests; stale/current-world failures need product work.
 */
class RddFutureNeedFreshnessSourceTest {
    @TempDir Path temp;
    Class<?> fixture;

    @BeforeEach void compile() throws Exception {
        Path root = root();
        Path plugin = root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd");
        String memory = Files.readString(plugin.resolve("RddContainerMemory.java"));
        String future = Files.readString(plugin.resolve("RddFutureNeeds.java"));
        List<Path> sources = new ArrayList<>();
        source(sources, "com/dwinovo/numen/rdd/api/AssetRequirement.java", """
            package com.dwinovo.numen.rdd.api;
            public record AssetRequirement(String assetKey, int minimum) {}
            """);
        source(sources, "com/dwinovo/numen/rdd/api/PrimaryGoal.java", """
            package com.dwinovo.numen.rdd.api;
            import java.util.*;
            public record PrimaryGoal(String id, String description, List<AssetRequirement> waitFor) {}
            """);
        source(sources, "com/dwinovo/numen/rdd/core/TaskChain.java", """
            package com.dwinovo.numen.rdd.core;
            import java.util.*; import com.dwinovo.numen.rdd.api.*;
            public record TaskChain(Goal goal, PrimaryGoal currentPrimary) {
                public record Goal(List<PrimaryGoal> primaryGoals) {}
            }
            """);
        source(sources, "com/dwinovo/numen/rdd/policy/FutureNeedScanner.java",
                Files.readString(root.resolve("rdd-core/src/main/java/com/dwinovo/numen/rdd/policy/FutureNeedScanner.java")));
        source(sources, "com/dwinovo/numen/plugins/rdd/RddContainerMemory.java", """
            package com.dwinovo.numen.plugins.rdd;
            import java.util.*; import java.util.concurrent.*;
            final class RddContainerMemory {
                static final Map<UUID,Map<String,Integer>> COUNTS = new ConcurrentHashMap<>();
                static final Map<UUID,Long> LAST_OK = new ConcurrentHashMap<>();
            """ + method(memory, "static Map<String, Integer> cachedCounts(") + "}\n");
        StringBuilder extracted = new StringBuilder("""
            package com.dwinovo.numen.plugins.rdd;
            import java.util.*; import java.util.concurrent.*;
            import com.dwinovo.numen.rdd.api.PrimaryGoal;
            import com.dwinovo.numen.rdd.core.TaskChain;
            import com.dwinovo.numen.rdd.policy.FutureNeedScanner;
            final class RddFutureNeeds {
                static final Map<UUID,String> BLOCK = new ConcurrentHashMap<>();
                static final Map<UUID,String> SIGNATURE = new ConcurrentHashMap<>();
            """);
        for (String signature : List.of("static void tick(", "static String block(",
                "static List<PrimaryGoal> futurePrimaries(", "private static String signature(",
                "private static String render(")) extracted.append(method(future, signature));
        // Only event transport is a double; hit selection and text rendering are production.
        extracted.append("static void publish(UUID id, List<FutureNeedScanner.Sighting> hits) {}\n}\n");
        source(sources, "com/dwinovo/numen/plugins/rdd/RddFutureNeeds.java", extracted.toString());
        source(sources, "com/dwinovo/numen/plugins/rdd/NeedFixture.java", FIXTURE);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "SETUP ERROR: JDK required");
        List<String> args = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none", "-d", temp.toString()));
        sources.forEach(p -> args.add(p.toString()));
        assertEquals(0, compiler.run(null, null, null, args.toArray(String[]::new)), "SETUP ERROR: extracted source must compile");
        var loader = new URLClassLoader(new URL[]{temp.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        fixture = loader.loadClass("com.dwinovo.numen.plugins.rdd.NeedFixture");
    }

    String scenario(String kind) throws Exception {
        return (String) fixture.getMethod("run", String.class).invoke(null, kind);
    }

    @Test void freshContainerAndCurrentInventoryAreVisible() throws Exception {
        String text = scenario("fresh");
        assertTrue(text.contains("minecraft:obsidian ×10/10（够）［附近容器］"), text);
        assertTrue(text.contains("minecraft:bread ×2/3（还差）［背包］"), text);
        assertFalse(text.contains("minecraft:diamond"), "unknown is not a zero claim: " + text);
    }

    @Test void unknownDoesNotBecomeAZeroOrAFalseAbsence() throws Exception {
        assertEquals("", scenario("unknown"));
    }

    @Test void expiredSuccessfulScanCannotClaimTheContainerIsVisibleNow() throws Exception {
        String text = scenario("expired");
        assertFalse(text.contains("［附近容器］"), text);
        assertFalse(text.contains("minecraft:obsidian"), text);
        assertTrue(text.contains("minecraft:bread ×2/3（还差）［背包］"), text);
        assertFalse(text.contains("minecraft:diamond"), text);
    }

    @Test void worldOrSessionChangeCannotReuseThePreviousContainer() throws Exception {
        for (String kind : List.of("world", "session")) {
            String text = scenario(kind);
            assertFalse(text.contains("［附近容器］"), kind + ": " + text);
            assertFalse(text.contains("minecraft:obsidian"), kind + ": " + text);
            assertTrue(text.contains("minecraft:bread ×2/3（还差）［背包］"), kind + ": " + text);
            assertFalse(text.contains("minecraft:diamond"), kind + ": " + text);
        }
    }

    @Test void directBlockReadMustNotLeakOldContainerBetweenTicks() throws Exception {
        for (String kind : List.of("expired-before-tick", "world-before-tick", "session-before-tick")) {
            String text = scenario(kind);
            assertFalse(text.contains("［附近容器］"), kind + ": " + text);
            assertFalse(text.contains("minecraft:obsidian"), kind + ": " + text);
            assertTrue(text.contains("minecraft:bread ×2/3（还差）［背包］"), kind + ": " + text);
            assertFalse(text.contains("minecraft:diamond"), kind + ": " + text);
        }
    }

    void source(List<Path> files, String name, String text) throws Exception {
        Path p = temp.resolve("sources").resolve(name);
        Files.createDirectories(p.getParent()); Files.writeString(p, text); files.add(p);
    }
    static Path root() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent())
            if (Files.isRegularFile(p.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddFutureNeeds.java"))) return p;
        throw new AssertionError("SETUP ERROR: checkout root missing");
    }
    static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "SETUP ERROR: missing " + signature);
        int end = source.indexOf("\n    }", source.indexOf('{', start));
        assertTrue(end > start, "SETUP ERROR: unterminated " + signature);
        return source.substring(start, end + 6) + "\n";
    }

    static final String FIXTURE = """
        package com.dwinovo.numen.plugins.rdd;
        import java.util.*;
        import com.dwinovo.numen.rdd.api.*;
        import com.dwinovo.numen.rdd.core.TaskChain;
        public class NeedFixture {
            static String world = "world-A", session = "session-A";
            public static String run(String kind) {
                UUID id = UUID.randomUUID();
                var current = new PrimaryGoal("p1", "now", List.of());
                var next = new PrimaryGoal("p2", "later", List.of(
                    new AssetRequirement("minecraft:obsidian", 10),
                    new AssetRequirement("minecraft:bread", 3),
                    new AssetRequirement("minecraft:diamond", 1)));
                var chain = new TaskChain(new TaskChain.Goal(List.of(current, next)), current);
                if (!kind.equals("unknown")) {
                    // Simulate the writes of a successful scan, not the MC scanning method.
                    RddContainerMemory.COUNTS.put(id, Map.of("minecraft:obsidian", 10));
                    RddContainerMemory.LAST_OK.put(id, System.currentTimeMillis());
                    RddFutureNeeds.tick(id, chain, Map.of("minecraft:bread", 2));
                    if (!RddFutureNeeds.block(id).contains("minecraft:obsidian ×10/10（够）［附近容器］"))
                        throw new AssertionError("SETUP ERROR: fresh container was not consumed");
                    if (kind.startsWith("expired")) RddContainerMemory.LAST_OK.put(id, 1L);
                    if (kind.startsWith("world")) world = "world-B";
                    if (kind.startsWith("session")) session = "session-B";
                    if (kind.endsWith("-before-tick")) return RddFutureNeeds.block(id);
                    // Failed/absent subsequent scan: do not change the cached successful counts.
                }
                RddFutureNeeds.tick(id, chain, kind.equals("unknown") ? Map.of() : Map.of("minecraft:bread", 2));
                return RddFutureNeeds.block(id);
            }
        }
        """;
}
