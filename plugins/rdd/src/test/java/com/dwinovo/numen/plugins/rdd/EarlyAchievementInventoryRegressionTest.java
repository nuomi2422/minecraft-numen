package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.HardCodedEvaluator;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.dwinovo.numen.rdd.policy.AssetDerivation;
import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;

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
        test.actualCompletionMethodRequiresIndependentRetainEvenWhenOutputIsHeld();
        test.strategicDerivationRemainsAvailableOutsideCompletion();
        test.statQueryDoesNotCaptureLateBaseline();
        test.entryBaselineAllowsFirstCraftDelta();
        test.unknownStatReadDoesNotBecomeZeroBaseline();
        test.lateOldAttemptBaselineIsNotOverwritten();
        test.entryHelperLocksZeroThenFirstCraftDelta();
        test.entryHelperUnknownDoesNotLock();
        test.entryHelperKeepsExistingAttemptBaseline();
        test.entryHelperDoesNotLockLifetimeScope();
        test.entryHelperIgnoresNonStatCurrentTask();
        test.statEntryWiringIsPresentAtAllFiveStartSites();
        System.out.println("EarlyAchievementInventoryRegressionTest: 14 passed, 0 failed");
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
    void actualCompletionMethodRequiresIndependentRetainEvenWhenOutputIsHeld() throws Exception {
        Method method=compileDetectorMethod();
        var task=new Subtask("resource-use-test","output held but retained stock required",
                com.dwinovo.numen.rdd.api.DetectionMode.HARD_CODED,
                Map.of("type","item","item","minecraft:furnace","minimum",1),0,0,false,null,
                com.dwinovo.numen.rdd.api.NodeType.MAIN,null,List.of(
                        new com.dwinovo.numen.rdd.api.ResourceUse("minecraft:cobblestone",8,com.dwinovo.numen.rdd.api.ResourceUse.Kind.CONSUME),
                        new com.dwinovo.numen.rdd.api.ResourceUse("minecraft:cobblestone",7,com.dwinovo.numen.rdd.api.ResourceUse.Kind.RETAIN)));
        assertFalse((boolean)method.invoke(null,task,Map.of("minecraft:furnace",1,"minecraft:cobblestone",6)));
        assertTrue((boolean)method.invoke(null,task,Map.of("minecraft:furnace",1,"minecraft:cobblestone",7)));
        assertFalse((boolean)method.invoke(null,task,null));
    }

    @Test
    void strategicDerivationRemainsAvailableOutsideCompletion() {
        assertEquals(8, AssetDerivation.equivalentCount("minecraft:bread",
                Map.of("minecraft:bread", 2, "minecraft:wheat", 6)));
    }

    @Test
    void statQueryDoesNotCaptureLateBaseline() throws Exception {
        Method method = compileStatDeltaMethod();
        TaskChain chain = statChain();
        Subtask task = chain.currentSubtask();
        chain.startCurrent();
        assertNull(chain.statBaseline(task.id()));
        assertFalse(evaluateStat(method, chain, task, 1));
        assertNull(chain.statBaseline(task.id()), "status/query read must not initialize a baseline");
        assertFalse(evaluateStat(method, chain, task, 2),
                "without an entry baseline the first craft must not silently become the baseline");
    }

    @Test
    void entryBaselineAllowsFirstCraftDelta() throws Exception {
        Method method = compileStatDeltaMethod();
        TaskChain chain = statChain();
        Subtask task = chain.currentSubtask();
        chain.startCurrent();
        chain.captureStatBaseline(task.id(), 0); // what the real start hook does before the AI acts
        assertTrue(evaluateStat(method, chain, task, 1), "first craft after entry must satisfy minimum=1");
    }

    @Test
    void unknownStatReadDoesNotBecomeZeroBaseline() throws Exception {
        Method method = compileStatDeltaMethod();
        TaskChain chain = statChain();
        Subtask task = chain.currentSubtask();
        chain.startCurrent();
        assertFalse(evaluateStat(method, chain, task, -1), "UNKNOWN stat is not a legal zero");
        assertNull(chain.statBaseline(task.id()), "UNKNOWN must not be frozen into a baseline");
    }

    @Test
    void lateOldAttemptBaselineIsNotOverwritten() throws Exception {
        Method method = compileStatDeltaMethod();
        TaskChain chain = statChain();
        Subtask task = chain.currentSubtask();
        chain.startCurrent();
        chain.captureStatBaseline(task.id(), 1);
        assertTrue(evaluateStat(method, chain, task, 2), "an old attempt baseline still permits later deltas");
        chain.captureStatBaseline(task.id(), 99);
        assertEquals(Integer.valueOf(1), chain.statBaseline(task.id()).get("value"),
                "capture must be putIfAbsent, never move an existing attempt baseline");
    }

    @Test
    void entryHelperLocksZeroThenFirstCraftDelta() throws Exception {
        Class<?> probe = compileStatCaptureProbe();
        TaskChain chain = statChain();
        Subtask task = chain.currentSubtask();
        chain.startCurrent();
        captureWithProbe(probe, chain, 0);
        assertEquals(Integer.valueOf(0), chain.statBaseline(task.id()).get("value"),
                "helper must lock the pre-action stat once");
        assertTrue(evaluateStat(compileStatDeltaMethod(), chain, task, 1),
                "first craft after the entry lock must satisfy minimum=1");
    }

    @Test
    void entryHelperUnknownDoesNotLock() throws Exception {
        Class<?> probe = compileStatCaptureProbe();
        TaskChain chain = statChain();
        Subtask task = chain.currentSubtask();
        chain.startCurrent();
        captureWithProbe(probe, chain, -1);
        assertNull(chain.statBaseline(task.id()), "UNKNOWN stat read must not create a baseline");
    }

    @Test
    void entryHelperKeepsExistingAttemptBaseline() throws Exception {
        Class<?> probe = compileStatCaptureProbe();
        TaskChain chain = statChain();
        Subtask task = chain.currentSubtask();
        chain.startCurrent();
        chain.captureStatBaseline(task.id(), 1);
        captureWithProbe(probe, chain, 5);
        assertEquals(Integer.valueOf(1), chain.statBaseline(task.id()).get("value"),
                "a retry start must not move the original attempt baseline");
        assertTrue(evaluateStat(compileStatDeltaMethod(), chain, task, 2));
    }

    @Test
    void entryHelperDoesNotLockLifetimeScope() throws Exception {
        Class<?> probe = compileStatCaptureProbe();
        TaskChain chain = statChain(Map.of("type", "item_crafted",
                "item", "minecraft:wooden_pickaxe", "minimum", 1, "scope", "lifetime"));
        Subtask task = chain.currentSubtask();
        chain.startCurrent();
        captureWithProbe(probe, chain, 0);
        assertNull(chain.statBaseline(task.id()), "explicit lifetime scope must not use a delta baseline");
    }

    @Test
    void entryHelperIgnoresNonStatCurrentTask() throws Exception {
        Class<?> probe = compileStatCaptureProbe();
        Subtask stat = statTask();
        Subtask nonStat = Subtask.hardCoded("non-stat", "no stat condition",
                Map.of("type", "inventory", "asset_key", "minecraft:dirt", "minimum", 1));
        TaskChain chain = new TaskChain(new Goal("stat-goal", "wrong current task",
                List.of(new PrimaryGoal("stat-primary", "wrong current task", List.of(nonStat, stat)))));
        chain.startCurrent();
        captureWithProbe(probe, chain, 0);
        assertNull(chain.statBaseline(stat.id()), "helper must only lock the current subtask");
        assertNull(chain.statBaseline(nonStat.id()), "non-stat current must not create a baseline");
    }

    /** Source wiring only: counts the five start call sites; helper behavior above owns consumption. */
    @Test
    void statEntryWiringIsPresentAtAllFiveStartSites() throws Exception {
        String detector = Files.readString(detectorSource(), StandardCharsets.UTF_8);
        assertEquals(2, count(detector, "captureStatBaselineAtStart(ap,"),
                "both detector mainline start sites must lock the baseline");
        assertFalse(methodBody(detector, "private static boolean statDeltaSatisfied(")
                        .contains("captureStatBaseline"), "condition/status evaluation must stay read-only");
        assertEquals(1, count(readSibling("RddFailureRecovery.java"),
                "RddDetector.captureStatBaselineAtStart("), "retry start site missing");
        assertEquals(1, count(readSibling("RddPlugin.java"),
                "RddDetector.captureStatBaselineAtStart("), "Stage-A fallback start site missing");
        assertEquals(1, count(readSibling("RddSubmitTool.java"),
                "RddDetector.captureStatBaselineAtStart("), "rdd_submit start site missing");
    }

    private static TaskChain statChain() {
        return statChain(Map.of("type", "item_crafted",
                "item", "minecraft:wooden_pickaxe", "minimum", 1));
    }

    private static TaskChain statChain(Map<String, Object> condition) {
        return new TaskChain(new Goal("stat-goal", "stat baseline regression",
                List.of(new PrimaryGoal("stat-primary", "stat baseline", List.of(statTask(condition))))));
    }

    private static Subtask statTask() {
        return statTask(Map.of("type", "item_crafted",
                "item", "minecraft:wooden_pickaxe", "minimum", 1));
    }

    private static Subtask statTask(Map<String, Object> condition) {
        return Subtask.hardCoded("stat-baseline", "craft one wooden pickaxe", condition);
    }

    private static boolean evaluateStat(Method method, TaskChain chain, Subtask task, int count)
            throws Exception {
        return (boolean) method.invoke(null, chain, task, count);
    }

    private static Method compileStatDeltaMethod() throws Exception {
        String source = Files.readString(detectorSource(), StandardCharsets.UTF_8);
        int start = source.indexOf("    private static boolean isStatCondition(");
        int end = source.indexOf("    private static Map<String, Integer> acquireBaselineFor(", start);
        assertTrue(start >= 0 && end > start, "stat methods boundaries missing");
        String method = source.substring(start, end);
        String probe = """
                import java.util.*;
                import com.dwinovo.numen.rdd.api.Subtask;
                import com.dwinovo.numen.rdd.api.SubtaskStatus;
                import com.dwinovo.numen.rdd.core.TaskChain;
                public class StatDeltaProbe {
                    static TaskChain chain;
                    static int count;
                    static final RddRuntime RUNTIME = new RddRuntime();
                    static final class RddRuntime { TaskChain chain() { return chain; } }
                    static final class RddPlugin { static RddRuntime runtime(java.util.UUID id) { return RUNTIME; } }
                    static final class NumenPlayer { java.util.UUID getUUID() { return new java.util.UUID(0L, 0L); } }
                    static final class RddWorldFacts {
                        static int statCount(NumenPlayer ap, Map<String, Object> condition) { return count; }
                        static boolean matches(NumenPlayer ap, Map<String, Object> condition) {
                            throw new AssertionError("world facts are outside this test");
                        }
                    }
                """ + method + """
                    public static boolean evaluate(TaskChain c, Subtask task, int n) {
                        chain = c; count = n;
                        return statDeltaSatisfied(new NumenPlayer(), task, Map.of());
                    }
                }
                """;
        Path dir = Files.createTempDirectory("rdd-stat-regression-");
        Path javaFile = dir.resolve("StatDeltaProbe.java");
        Files.writeString(javaFile, probe, StandardCharsets.UTF_8);
        String coreClasses = Path.of(HardCodedEvaluator.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI()).toString();
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "a JDK is required for this source-isolated test");
        int result = compiler.run(null, null, null, "-encoding", "UTF-8", "-classpath", coreClasses,
                "-d", dir.toString(), javaFile.toString());
        assertEquals(0, result, "could not compile the real statDeltaSatisfied method");
        var loader = new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},
                EarlyAchievementInventoryRegressionTest.class.getClassLoader());
        return loader.loadClass("StatDeltaProbe").getMethod("evaluate", TaskChain.class, Subtask.class, int.class);
    }

    private static Class<?> compileStatCaptureProbe() throws Exception {
        String source = Files.readString(detectorSource(), StandardCharsets.UTF_8);
        int start = source.indexOf("    private static boolean isStatCondition(");
        int end = source.indexOf("    private static boolean statDeltaSatisfied(", start);
        assertTrue(start >= 0 && end > start, "stat capture boundaries missing");
        String methods = source.substring(start, end);
        String probe = """
                import java.util.*;
                import com.dwinovo.numen.rdd.api.Subtask;
                import com.dwinovo.numen.rdd.api.SubtaskStatus;
                import com.dwinovo.numen.rdd.core.TaskChain;
                public class StatCaptureProbe {
                    static TaskChain chain;
                    static int stat;
                    static final RddRuntime RUNTIME = new RddRuntime();
                    static final class RddRuntime { TaskChain chain() { return chain; } }
                    static final class NumenPlayer { java.util.UUID getUUID() { return new java.util.UUID(0L, 0L); } }
                    static final class RddWorldFacts {
                        static int statCount(NumenPlayer ap, Map<String, Object> condition) { return stat; }
                        static boolean matches(NumenPlayer ap, Map<String, Object> condition) {
                            throw new AssertionError("world facts are outside this test");
                        }
                    }
                """ + methods + """
                    public static void capture(TaskChain c, int n) {
                        chain = c; stat = n;
                        captureStatBaselineAtStart(new NumenPlayer(), RUNTIME);
                    }
                }
                """;
        Path dir = Files.createTempDirectory("rdd-stat-capture-");
        Path javaFile = dir.resolve("StatCaptureProbe.java");
        Files.writeString(javaFile, probe, StandardCharsets.UTF_8);
        String coreClasses = Path.of(HardCodedEvaluator.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI()).toString();
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "a JDK is required for this source-isolated test");
        int result = compiler.run(null, null, null, "-encoding", "UTF-8", "-classpath", coreClasses,
                "-d", dir.toString(), javaFile.toString());
        assertEquals(0, result, "could not compile the real captureStatBaselineAtStart method");
        var loader = new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},
                EarlyAchievementInventoryRegressionTest.class.getClassLoader());
        return loader.loadClass("StatCaptureProbe");
    }

    private static void captureWithProbe(Class<?> probe, TaskChain chain, int count) throws Exception {
        probe.getMethod("capture", TaskChain.class, int.class).invoke(null, chain, count);
    }

    private static String readSibling(String name) throws Exception {
        return Files.readString(detectorSource().resolveSibling(name), StandardCharsets.UTF_8);
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing method " + signature);
        int end = source.indexOf("\n    }", source.indexOf('{', start));
        assertTrue(end > start, "missing closing boundary for " + signature);
        return source.substring(start, end + 6);
    }

    private static int count(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}
