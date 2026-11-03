package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.HardCodedEvaluator;
import com.dwinovo.numen.rdd.policy.AssetDerivation;
import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline regression for the inventory-only branch of RddDetector.conditionMatches.
 * The real method body is compiled unchanged against tiny host stubs because the Minecraft
 * runtime is unavailable to pure JVM tests. This does not test world facts or game execution.
 */
class EarlyAchievementInventoryRegressionTest {
    /** Run the same assertions directly when a JUnit launcher is unavailable offline. */
    public static void main(String[] args) throws Exception {
        var test = new EarlyAchievementInventoryRegressionTest();
        test.wheatAndInsufficientBreadNeverSatisfyBreadHold();
        test.realInventoryStillSatisfiesHold();
        test.strategicDerivationRemainsAvailableOutsideCompletion();
        System.out.println("EarlyAchievementInventoryRegressionTest: 3 passed, 0 failed");
    }

    private static Path detectorSource() {
        String override = System.getProperty("rdd.detector.source");
        if (override != null) return Path.of(override);
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            Path fromRoot = p.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddDetector.java");
            if (Files.isRegularFile(fromRoot)) return fromRoot;
            Path fromPlugin = p.resolve("src/main/java/com/dwinovo/numen/plugins/rdd/RddDetector.java");
            if (Files.isRegularFile(fromPlugin)) return fromPlugin;
        }
        throw new IllegalStateException("RddDetector.java not found");
    }

    private static Method compileDetectorMethod() throws Exception {
        String source = Files.readString(detectorSource(), StandardCharsets.UTF_8);
        int start = source.indexOf("    static boolean conditionMatches(");
        int end = source.indexOf("    private static boolean isStatCondition(", start);
        assertTrue(start >= 0 && end > start, "conditionMatches boundaries missing");
        String method = source.substring(start, end);
        String probe = """
                import java.util.Map;
                import com.dwinovo.numen.rdd.api.Subtask;
                import com.dwinovo.numen.rdd.core.HardCodedEvaluator;
                public class InventoryDetectorProbe {
                    static class NumenPlayer {}
                    static class RddWorldFacts {
                        static boolean matches(NumenPlayer ap, Map<String, Object> condition) {
                            throw new AssertionError("world facts are outside this test");
                        }
                    }
                    static boolean isStatCondition(Map<String, Object> condition) { return false; }
                    static boolean statDeltaSatisfied(NumenPlayer ap, Subtask task, Map<String, Integer> counts) {
                        throw new AssertionError("stats are outside this test");
                    }
                    static Map<String, Integer> acquireBaselineFor(NumenPlayer ap, Subtask task) { return null; }
                    static void detectAssetMismatch(NumenPlayer ap, Subtask task, Map<String, Integer> counts) {}
                """ + method + """
                    public static boolean evaluate(Subtask task, Map<String, Integer> counts) {
                        return conditionMatches(null, task, counts);
                    }
                }
                """;
        Path dir = Files.createTempDirectory("rdd-inventory-regression-");
        Path javaFile = dir.resolve("InventoryDetectorProbe.java");
        Files.writeString(javaFile, probe, StandardCharsets.UTF_8);
        String coreClasses = Path.of(HardCodedEvaluator.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI()).toString();
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "a JDK is required for this source-isolated test");
        int result = compiler.run(null, null, null, "-encoding", "UTF-8", "-classpath", coreClasses,
                "-d", dir.toString(), javaFile.toString());
        assertEquals(0, result, "could not compile the real conditionMatches method");
        var loader = new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},
                EarlyAchievementInventoryRegressionTest.class.getClassLoader());
        return loader.loadClass("InventoryDetectorProbe").getMethod("evaluate", Subtask.class, Map.class);
    }

    private static boolean evaluate(Method method, Map<String, Object> condition,
                                    Map<String, Integer> counts) throws Exception {
        Subtask task = Subtask.hardCoded("inventory-test", "inventory-only test", condition);
        return (boolean) method.invoke(null, task, counts);
    }

    @Test
    void wheatAndInsufficientBreadNeverSatisfyBreadHold() throws Exception {
        Method method = compileDetectorMethod();
        var implicit = Map.<String, Object>of("asset_key", "minecraft:bread", "minimum", 8);
        var explicit = Map.<String, Object>of("type", "inventory", "mode", "hold",
                "asset_key", "minecraft:bread", "minimum", 8);
        assertFalse(evaluate(method, implicit, Map.of("minecraft:wheat", 12)));
        assertFalse(evaluate(method, implicit, Map.of("minecraft:bread", 5, "minecraft:wheat", 6)));
        assertFalse(evaluate(method, explicit, Map.of("minecraft:bread", 7, "minecraft:wheat", 100)));
    }

    @Test
    void realInventoryStillSatisfiesHold() throws Exception {
        Method method = compileDetectorMethod();
        var bread = Map.<String, Object>of("asset_key", "minecraft:bread", "minimum", 8);
        assertTrue(evaluate(method, bread, Map.of("minecraft:bread", 8)));
        assertTrue(evaluate(method, bread, Map.of("minecraft:bread", 8, "minecraft:wheat", 100)));
        assertFalse(evaluate(method, bread, Map.of("minecraft:bread", 7)));
        var group = Map.<String, Object>of("group", "food", "minimum", 3);
        assertTrue(evaluate(method, group, Map.of("minecraft:bread", 2, "minecraft:apple", 1)));
        assertFalse(evaluate(method, group, Map.of("minecraft:wheat", 100)));
        var item = Map.<String, Object>of("type", "item", "item", "minecraft:bread", "minimum", 8);
        assertTrue(evaluate(method, item, Map.of("minecraft:bread", 8)));
        assertFalse(evaluate(method, item, Map.of("minecraft:wheat", 100)));
    }

    @Test
    void strategicDerivationRemainsAvailableOutsideCompletion() {
        assertEquals(8, AssetDerivation.equivalentCount("minecraft:bread",
                Map.of("minecraft:bread", 2, "minecraft:wheat", 6)));
    }
}
