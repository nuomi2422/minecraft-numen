package com.dwinovo.numen.client.agent;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes complete production private formatting methods without Minecraft or registry stubs.
 * Source slicing follows the existing detached-consumer tests; it does not validate MC adaptation,
 * registry names, snapshot countdowns or a constructed EntityAgentLoop. InventoryBlockTest covers
 * those separately and is MC-tagged/excluded by default. No production API is widened for this test.
 */
class BodySnapshotRendererFormatTest {
    @TempDir static Path build;
    private static URLClassLoader loader;
    private static Method format;
    private static String renderer;
    private static String loop;

    @BeforeAll
    static void compileProductionFormatting() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("api/common/src"))) root = root.getParent();
        assertNotNull(root, "repository root required");
        Path agents = root.resolve("api/common/src/client/java/com/dwinovo/numen/client/agent");
        renderer = Files.readString(agents.resolve("BodySnapshotRenderer.java"));
        loop = Files.readString(agents.resolve("EntityAgentLoop.java"));
        String source = "import java.util.List; public final class FormatFixture {\n"
                + method(renderer, "private static String formatInventory(") + "\n"
                + method(renderer, "private static String describe(") + "\n"
                + method(renderer, "private static String xml(") + "\n}";
        Path file = build.resolve("FormatFixture.java");
        Files.writeString(file, source);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK compiler required; do not silently skip formatting assertions");
        assertEquals(0, compiler.run(null, null, null, "-d", build.toString(), file.toString()),
                "complete production formatting methods must compile before assertions");
        loader = new URLClassLoader(new URL[]{build.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        format = loader.loadClass("FormatFixture").getDeclaredMethod("formatInventory",
                Map.class, String.class, String.class);
        format.setAccessible(true);
    }

    @AfterAll
    static void closeLoader() throws Exception {
        if (loader != null) loader.close();
    }

    @Test
    void emptyBackpackHasTheExactOriginalEnvelope() throws Exception {
        assertEquals(expected("nothing", "(empty)", "(empty)"), render(Map.of(), null, null));
    }

    @Test
    void totalsSortDescendingAndTiesStayInItemIdOrderWithoutMutatingInput() throws Exception {
        Map<String, Integer> totals = new TreeMap<>(Map.of(
                "minecraft:z", 3, "minecraft:a", 3, "minecraft:stone", 64));
        var before = new TreeMap<>(totals);
        assertEquals(expected("minecraft:stone x64, minecraft:a x3, minecraft:z x3",
                "minecraft:stone", "(empty)"), render(totals, "minecraft:stone", null));
        assertEquals(before, totals);
    }

    @Test
    void catalogueNeverTruncatesAtTheOldTwentyKindLimit() throws Exception {
        Map<String, Integer> totals = new TreeMap<>();
        for (int i = 0; i < 27; i++) totals.put("minecraft:item_" + i, 1);
        String carrying = String.join(", ", totals.keySet().stream().map(id -> id + " x1").toList());
        assertEquals(expected(carrying, "(empty)", "(empty)"), render(totals, null, null));
    }

    @Test
    void handReferencesNeverAddAnotherCountOrOffhandTotal() throws Exception {
        String actual = render(Map.of("minecraft:furnace", 64), "minecraft:furnace", "minecraft:torch");
        assertEquals(expected("minecraft:furnace x64", "minecraft:furnace", "minecraft:torch"), actual);
        assertEquals(1, actual.split("x64", -1).length - 1);
    }

    @Test
    void handXmlEscapesAllFiveCharactersAndPreservesPotionLabels() throws Exception {
        String id = "mod:potion[strong_healing+poison]";
        assertEquals(expected(id + " x2", "&amp;&lt;&gt;&quot;&apos;", id),
                render(Map.of(id, 2), "&<>\"'", id));
    }

    @Test
    void productionCallersUseTheRendererAndItsPrivateFormattingBoundary() {
        assertTrue(method(loop, "private String inventoryXml(")
                .contains("inventoryRendered = BodySnapshotRenderer.renderInventory(snapshot);"));
        assertTrue(method(loop, "private String effectsXml(")
                .contains("BodySnapshotRenderer.renderEffects(snapshot, System.currentTimeMillis())"));
        String inventory = method(renderer, "static String renderInventory(");
        assertTrue(inventory.contains("totals.merge(itemId(stack), stack.getCount(), Integer::sum)"));
        assertTrue(inventory.contains("return formatInventory(totals,"));
        for (String name : List.of("renderInventory", "renderInventoryForExecution")) {
            assertTrue(method(loop, "static String " + name + "(")
                    .contains("return BodySnapshotRenderer.renderInventory(snapshot);"));
        }
        assertTrue(method(loop, "static String renderEffects(")
                .contains("return BodySnapshotRenderer.renderEffects(snapshot, nowMs);"));
        assertTrue(loop.contains("public static final String INVENTORY_DETAIL_OWNER = \"runtime_state.execution\";"));
        assertFalse(renderer.contains("ClientNumenState.get("));
    }

    private static String render(Map<String, Integer> totals, String main, String off) throws Exception {
        return (String) format.invoke(null, totals, main, off);
    }

    private static String expected(String carrying, String main, String off) {
        return "<inventory>Your backpack right now, totalled across all 36 backpack slots — trust it, "
                + "do not spend a call on get_self_status to rediscover it. "
                + "Call inspect_gui only when exact slots matter. A newer tool result wins over this."
                + "\ncarrying=" + carrying
                + "\nholding (already counted above)=main " + main + ", off " + off + "</inventory>";
    }

    /** These small formatting methods have no brace characters in string literals. */
    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing production method: " + signature);
        int open = source.indexOf('{', start);
        int depth = 1;
        for (int i = open + 1; i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            else if (source.charAt(i) == '}' && --depth == 0) return source.substring(start, i + 1);
        }
        throw new AssertionError("unterminated production method: " + signature);
    }
}
