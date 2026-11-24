package com.dwinovo.numen.plugins.rdd;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RddExternalResetWiringTest {
    /**
     * 2026-11-24 迁移实机：reset 唯一失败是 IOException home store unavailable。
     *
     * <p>行为证据不用 MC 假宿主：把真实的 {@code useFactsDirForTest} 方法体与真实
     * {@code RddHomeAnchor.java} 编进窄 probe（FACTS 用 HashMap，HomeAnchorPolicy 走真实 classpath）。
     * 旧 helper 不绑 DIR → init 后 clear 仍抛 = 红；新 helper 绑定后清掉同 UUID 文件 = 绿。
     * 真实 {@code setup()} 的赋值由 {@code RddMainlineWiringPinTest} 锁，本测试不谎称跑过 MC setup。
     */
    @Test void homeAnchorClearWorksAfterTheFactsStoreInitAndFailsClosedBeforeIt(
            @org.junit.jupiter.api.io.TempDir Path temp) throws Exception {
        Path root = root();
        String plugin = Files.readString(root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddPlugin.java"));
        String anchor = Files.readString(root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddHomeAnchor.java"));
        Path work = temp.resolve("probe");
        Path pkg = work.resolve("com/dwinovo/numen/plugins/rdd");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("RddHomeAnchor.java"), anchor);
        Files.writeString(pkg.resolve("HomeStoreProbe.java"), """
            package com.dwinovo.numen.plugins.rdd;
            import java.nio.file.Path;
            import java.util.*;
            public class HomeStoreProbe {
              static Path factsDir;
              static final Map<UUID,Object> FACTS = new HashMap<>();
            """ + method(plugin, "    static void useFactsDirForTest(Path directory)") + """
              public static void init(Path dir) { useFactsDirForTest(dir); }
              public static boolean bound() { return RddHomeAnchor.DIR != null; }
              public static void clear(UUID id) throws java.io.IOException { RddHomeAnchor.clear(id); }
            }
            """);
        var args = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none",
                "-sourcepath", work.toString(), "-classpath", classpath(), "-d", work.toString()));
        args.add(pkg.resolve("RddHomeAnchor.java").toString());
        args.add(pkg.resolve("HomeStoreProbe.java").toString());
        var diagnostics = new ByteArrayOutputStream();
        int status = javax.tools.ToolProvider.getSystemJavaCompiler()
                .run(null, diagnostics, diagnostics, args.toArray(String[]::new));
        assertEquals(0, status, "SETUP: probe compilation failed (not a business result):\n" + diagnostics);

        java.util.UUID id = java.util.UUID.randomUUID();
        Path file = temp.resolve("home-anchor-" + id + ".json");
        Files.writeString(file, "{}");
        try (var loader = new URLClassLoader(new URL[]{work.toUri().toURL()}, getClass().getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String n, boolean resolve) throws ClassNotFoundException {
                if (Files.exists(work.resolve(n.replace('.', '/') + ".class"))) {
                    Class<?> t = findLoadedClass(n);
                    if (t == null) t = findClass(n);
                    if (resolve) resolveClass(t);
                    return t;
                }
                return super.loadClass(n, resolve);
            }
        }) {
            Class<?> probe = loader.loadClass("com.dwinovo.numen.plugins.rdd.HomeStoreProbe");
            var init = probe.getMethod("init", Path.class);
            var bound = probe.getMethod("bound");
            java.lang.reflect.Method clear = probe.getMethod("clear", java.util.UUID.class);
            assertEquals(Boolean.FALSE, bound.invoke(null), "fresh probe starts unbound");
            try {
                clear.invoke(null, id);
                fail("an unbound store must fail closed instead of silently succeeding");
            } catch (java.lang.reflect.InvocationTargetException e) {
                assertTrue(e.getCause() instanceof java.io.IOException, "SETUP/business: " + e.getCause());
            }
            init.invoke(null, temp);
            assertEquals(Boolean.TRUE, bound.invoke(null),
                    "the real useFactsDirForTest must bind the home store before any clear");
            clear.invoke(null, id);
            assertFalse(Files.exists(file), "clear must delete the same companion's home anchor file");
        }
    }

    @Test void resetFailsClosedAndClearsAllInventoryOnlyAfterChainAndDisk() throws Exception {
        String s = Files.readString(Path.of("src/main/java/com/dwinovo/numen/plugins/rdd/RddPlugin.java"));
        String body = s.substring(s.indexOf("private static Map<String, Object> resetCurrentWorld"), s.indexOf("public static void remove(UUID companionId)"));
        assertTrue(body.indexOf("completionPending(id)") < body.indexOf("removeCurrent(id)"));
        assertTrue(body.indexOf("removeCurrent(id)") < body.indexOf("RddAssetStore.save"));
        assertTrue(body.indexOf("RddAssetStore.save") < body.indexOf("ASSETS.put"));
        assertTrue(body.indexOf("RddHomeAnchor.clear") < body.indexOf("clearContent()"));
        assertTrue(body.indexOf("task_stop") < body.indexOf("removeCurrent(id)"));
        assertTrue(body.contains("getContainerSize()"));
        assertTrue(body.contains("inventory reset incomplete"));
        assertFalse(body.contains("historyDir"));
        assertFalse(body.contains("ledgerDir"));
        // Wiring only: the native Player setters/readback still need an owned-runtime test, not a fake MC host.
        int xpReset = body.indexOf("body.setExperienceLevels(0);");
        assertTrue(xpReset > body.indexOf("inventory reset incomplete"));
        assertTrue(body.contains("body.setExperiencePoints(0);"));
        assertTrue(body.contains("body.totalExperience = 0;"));
        assertTrue(body.contains("body.experienceLevel == 0 && body.totalExperience == 0 && body.experienceProgress == 0.0f"));
        assertTrue(body.indexOf("physical experience reset incomplete") < body.indexOf("RddMonitor.publish(\"external_world_reset\""));
        // Merge wiring only (not native): XP readback, then inheritance retirement, then the success event.
        int xpReadback = body.indexOf("physical experience reset incomplete");
        int retire = body.indexOf("RddFactStore.retireStageInheritance(factsDir, id, completedFacts)");
        assertTrue(xpReadback < retire);
        assertTrue(retire < body.indexOf("RddMonitor.publish(\"external_world_reset\""));
        assertTrue(body.contains("\"level\", body.experienceLevel"));
        assertTrue(body.contains("\"total\", body.totalExperience, \"progress\", body.experienceProgress"));
        assertTrue(body.contains("\"cleared\", physicalExperienceCleared"));
        assertEquals(2, body.split("\"physicalExperience\", physicalExperience", -1).length - 1);
        assertEquals(s.indexOf("body.setExperienceLevels(0);"), s.lastIndexOf("body.setExperienceLevels(0);"));
    }

    static Path root() {
        Path r = Path.of("").toAbsolutePath();
        while (r != null && !Files.isDirectory(r.resolve("plugins/rdd/src/main/java"))) r = r.getParent();
        assertNotNull(r, "SETUP: repository root not found");
        return r;
    }

    static String method(String text, String signature) {
        int a = text.indexOf(signature);
        assertTrue(a >= 0, "SETUP: method missing: " + signature);
        int indent = signature.indexOf(signature.stripLeading());
        String close = "\n" + " ".repeat(indent) + "}";
        int b = text.indexOf(close, a);
        assertTrue(b > a, "SETUP: method end missing: " + signature);
        return text.substring(a, b + close.length());
    }

    static String classpath() throws Exception {
        var entries = new LinkedHashSet<String>(List.of(
                System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator))));
        entries.add(Path.of(com.dwinovo.numen.rdd.core.HomeAnchorPolicy.class
                .getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        return String.join(File.pathSeparator, entries);
    }
}
