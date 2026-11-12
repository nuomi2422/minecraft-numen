package com.dwinovo.numen.core.task.mine;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V06 recovery regression (2026-11-12): a rejected mine suggested equipping a
 * stone pick, then rejected the same request after that equip succeeded.
 * Compiles the exact production preconditions() and canHarvest() methods and
 * noOreFailure() and production Precondition/FailureType/TaskState definitions, then consumes the returned
 * Failure. No copied harvest algorithm or production-message snapshot.
 * The late branch is a source-confirmed sibling defect, not a second V06 game incident.
 * MC boundaries supply tool/drop eligibility, inventory slots, remembered targets and WorkProfile;
 * vanilla tier tags, actual digging, async transport and model behavior are not
 * simulated. This keeps the failed recovery test independent of MC bootstrap;
 * a source/fixture compiler failure is a setup error, never a business red.
 */
class MineHarvestRecoveryConsumerTest {
    @TempDir static Path temp;
    static URLClassLoader loader;
    static Class<?> fixture;

    @BeforeAll static void compileProductionMethods() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("core/common/src/main/java"))) {
            root = root.getParent();
        }
        assertNotNull(root, "must locate the candidate source tree");
        Path main = root.resolve("core/common/src/main/java/com/dwinovo/numen/core");
        String gate = method(Files.readString(main.resolve("task/mine/MineCompanionTask.java")),
                "    protected List<Precondition> preconditions() {");
        String harvest = method(Files.readString(main.resolve("pathing/util/BlockHelper.java")),
                "    public static boolean canHarvest(Container inv, BlockState state) {");
        String late = method(Files.readString(main.resolve("task/mine/MineCompanionTask.java")),
                "    private TaskState noOreFailure() {");
        Path src = temp.resolve("src"), out = temp.resolve("classes");
        Files.createDirectories(out);
        List<String> args = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none", "-d", out.toString()));
        add(src, args, "com/dwinovo/numen/core/task/base/Precondition.java",
                Files.readString(main.resolve("task/base/Precondition.java")));
        add(src, args, "com/dwinovo/numen/core/FailureType.java",
                Files.readString(main.resolve("FailureType.java")));
        add(src, args, "com/dwinovo/numen/task/TaskState.java",
                Files.readString(root.resolve("api/common/src/main/java/com/dwinovo/numen/task/TaskState.java")));
        add(src, args, "fixture/HarvestFixture.java", """
                package fixture;
                import java.util.*;
                import com.dwinovo.numen.core.FailureType;
                import com.dwinovo.numen.core.task.base.Precondition;
                import com.dwinovo.numen.task.TaskState;
                public class HarvestFixture {
                    // Boundary data: eligibility is supplied, not calculated from a tier table.
                    static class BlockState {
                        final String key; final boolean gated;
                        BlockState(String key, boolean gated) { this.key=key; this.gated=gated; }
                        boolean requiresCorrectToolForDrops() { return gated; }
                    }
                    static class ItemStack {
                        final Set<String> eligible;
                        ItemStack(Set<String> eligible) { this.eligible=eligible; }
                        boolean isCorrectToolForDrops(BlockState s) { return eligible.contains(s.key); }
                    }
                    static class Container {
                        final ItemStack[] slots = new ItemStack[36]; int reads;
                        Container() { Arrays.fill(slots, new ItemStack(Set.of())); }
                        int getContainerSize() { return slots.length; }
                        ItemStack getItem(int i) { reads++; return slots[i]; }
                    }
                    static class Block {
                        final BlockState state;
                        Block(String key, boolean gated) { state=new BlockState(key,gated); }
                        BlockState defaultBlockState() { return state; }
                    }
                    static class Player {
                        final Container inventory=new Container(); boolean creative;
                        Container getInventory() { return inventory; }
                    }
                    static class WorkProfile {
                        final Player player;
                        WorkProfile(Player player) { this.player=player; }
                        static WorkProfile of(Player player) { return new WorkProfile(player); }
                        boolean instaBreak() { return player.creative; }
                    }
                    static class BlockHelper {
                HARVEST_METHOD
                    }
                    static class Request {
                        List<Block> targets; String label="diamond_ore+1"; int mined;
                        int getMined() { return mined; }
                    }
                    final Player player; final Request r;
                    final Set<Integer> unharvestable=new HashSet<>(), unworkable=new HashSet<>();
                    Precondition.Failure terminal; int failureCalls;
                    void fail(String message, FailureType type) {
                        terminal=new Precondition.Failure(message,type); failureCalls++;
                    }
                    HarvestFixture(Player player, Request r) { this.player=player; this.r=r; }
                PRECONDITIONS_METHOD
                LATE_METHOD
                    public static Map<String,Object> runLate(String scenario, int gathered) {
                        Request request=new Request(); request.mined=gathered;
                        HarvestFixture task=new HarvestFixture(new Player(),request);
                        switch(scenario) {
                            // Boundary: prune's remembered target sets, not a second prune algorithm.
                            case "unharvestable" -> task.unharvestable.addAll(Set.of(1,2));
                            case "unworkable" -> task.unworkable.add(1);
                            case "empty" -> { }
                            default -> throw new IllegalArgumentException(scenario);
                        }
                        TaskState state=task.noOreFailure();
                        return Map.of("state",state.name(), "type",task.terminal.type().name(),
                                "message",task.terminal.message(), "failureCalls",task.failureCalls);
                    }
                    public static Map<String,Object> run(String scenario, int slot) {
                        Player player=new Player(); Request request=new Request();
                        Block high=new Block("high",true), low=new Block("low",true);
                        request.targets=List.of(high);
                        switch(scenario) {
                            case "insufficient" -> player.inventory.slots[slot]=new ItemStack(Set.of("low"));
                            case "correct" -> player.inventory.slots[slot]=new ItemStack(Set.of("high"));
                            case "mixed", "mixedReverse" -> {
                                player.inventory.slots[slot]=new ItemStack(Set.of("low"));
                                request.targets=scenario.equals("mixed") ? List.of(high,low) : List.of(low,high);
                            }
                            case "allUnharvestable" -> request.targets=List.of(high,low);
                            case "creative" -> player.creative=true;
                            case "noToolRequired" -> request.targets=List.of(new Block("free",false));
                            case "empty" -> { }
                            default -> throw new IllegalArgumentException(scenario);
                        }
                        List<Precondition> gates=new HarvestFixture(player,request).preconditions();
                        if(gates.size()!=1) throw new AssertionError("unexpected precondition count");
                        Precondition.Failure failure=gates.get(0).check();
                        return Map.of("accepted", failure==null,
                                "type", failure==null ? "" : failure.type().name(),
                                "message", failure==null ? "" : failure.message(),
                                "reads", player.inventory.reads);
                    }
                }
                """.replace("HARVEST_METHOD", harvest).replace("PRECONDITIONS_METHOD", gate).replace("LATE_METHOD", late));
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK compiler is required for the real-method consumer");
        var diagnostics = new ByteArrayOutputStream();
        int code = compiler.run(null, diagnostics, diagnostics, args.toArray(String[]::new));
        assertEquals(0, code, () -> "production slice must compile before behavior assertions: "
                + diagnostics.toString(StandardCharsets.UTF_8));
        loader = new URLClassLoader(new URL[]{out.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        fixture = loader.loadClass("fixture.HarvestFixture");
    }

    @AfterAll static void close() throws Exception { if (loader != null) loader.close(); }

    @Test void insufficientToolInAnyInventoryRegionIsStillWrongTool() throws Exception {
        for (int slot : List.of(0, 8, 9, 35)) {
            var result = run("insufficient", slot);
            assertWrongTool(result);
            assertEquals(36, result.get("reads"), "must consult all inventory slots, slot=" + slot);
        }
    }

    @Test void movingTheSameInsufficientToolIntoTheHandCannotResolveTheFailure() throws Exception {
        var stowed = run("insufficient", 35);
        var held = run("insufficient", 0);
        assertWrongTool(stowed);
        assertWrongTool(held);
        assertEquals(stowed.get("message"), held.get("message"));
        String message = (String) held.get("message");
        assertFalse(message.contains("equip_item"), "re-equipping cannot fix this inventory predicate: " + message);
        assertFalse(message.contains("stone_pickaxe"), "do not advertise an insufficient tier as a generic fix");
    }

    @Test void validToolAnywhereInTheBackpackNeedsNoManualEquip() throws Exception {
        for (int slot : List.of(0, 8, 9, 35)) {
            var result = run("correct", slot);
            assertEquals(true, result.get("accepted"), "correct tool in slot=" + slot);
            assertEquals(slot + 1, result.get("reads"));
        }
    }

    @Test void mixedRequestKeepsAnyHarvestableSemanticsInEitherOrder() throws Exception {
        assertEquals(true, run("mixed", 35).get("accepted"));
        assertEquals(true, run("mixedReverse", 35).get("accepted"));
    }

    @Test void noCorrectToolForAnyRequestedTargetStillRejects() throws Exception {
        assertWrongTool(run("empty", 0));
        assertWrongTool(run("allUnharvestable", 0));
    }

    @Test void instabreakProfilePreservesTheOriginalToolGateExemption() throws Exception {
        var result = run("creative", 0);
        assertEquals(true, result.get("accepted"));
        assertEquals(0, result.get("reads"), "instabreak must bypass the inventory check");
    }

    @Test void blocksWhoseDropsNeedNoToolStillPass() throws Exception {
        var result = run("noToolRequired", 0);
        assertEquals(true, result.get("accepted"));
        assertEquals(0, result.get("reads"));
    }

    @Test void realFailureExplainsMissingCapabilityAndHowToRecover() throws Exception {
        var result = run("insufficient", 35);
        assertWrongTool(result);
        String message = ((String) result.get("message")).toLowerCase(java.util.Locale.ROOT);
        assertTrue(message.contains("diamond_ore+1"), "retain the actual requested label");
        assertTrue(message.contains("inventory"), "explain the whole-inventory prerequisite: " + message);
        assertTrue(message.contains("type") && message.contains("tier"), "required tool kind and tier matter: " + message);
        assertTrue(message.contains("obtain") && message.contains("retry"), "acquire a qualifying tool before retrying: " + message);
        assertTrue(message.contains("same") && message.contains("will not"), "distinguish acquisition from moving the same tool: " + message);
        assertFalse(message.contains("break_block"), "do not suggest bypassing the no-drop refusal");
    }

    @Test void lateUnharvestableKeepsFailedWrongToolAndGatheredCount() throws Exception {
        var result = runLate("unharvestable", 4);
        assertEquals("FAILED", result.get("state"));
        assertEquals("WRONG_TOOL", result.get("type"));
        assertEquals(1, result.get("failureCalls"));
        String message = (String) result.get("message");
        assertTrue(message.startsWith("found 2 diamond_ore+1"), message);
        assertTrue(message.contains("gathered 4"), message);
    }

    @Test void lateRecoveryAlsoRequiresAcquisitionInsteadOfEquippingTheSameTool() throws Exception {
        var result = runLate("unharvestable", 4);
        assertEquals("FAILED", result.get("state"));
        assertEquals("WRONG_TOOL", result.get("type"));
        String message = ((String) result.get("message")).toLowerCase(java.util.Locale.ROOT);
        assertFalse(message.contains("equip_item"), message);
        assertFalse(message.contains("break_block"), message);
        assertTrue(message.contains("inventory"), message);
        assertTrue(message.contains("type") && message.contains("tier"), message);
        assertTrue(message.contains("obtain") && message.contains("retry"), message);
        assertTrue(message.contains("same") && message.contains("will not"), message);
    }

    @Test void lateUnworkableAndEmptyRetainTheirExistingReasons() throws Exception {
        var unworkable = runLate("unworkable", 0);
        assertEquals("FAILED", unworkable.get("state"));
        assertEquals("NO_PATH", unworkable.get("type"));
        assertEquals(1, unworkable.get("failureCalls"));
        assertEquals("found 1 diamond_ore+1 nearby but no clear shot at any of them from any stance I could take; gathered 0",
                unworkable.get("message"));
        var empty = runLate("empty", 0);
        assertEquals("FAILED", empty.get("state"));
        assertEquals("MINED_OUT", empty.get("type"));
        assertEquals(1, empty.get("failureCalls"));
        assertEquals("no reachable diamond_ore+1 found in the loaded area around me", empty.get("message"));
    }

    @SuppressWarnings("unchecked")
    static Map<String,Object> runLate(String scenario, int gathered) throws Exception {
        return (Map<String,Object>) fixture.getMethod("runLate", String.class, int.class).invoke(null, scenario, gathered);
    }

    @SuppressWarnings("unchecked")
    static Map<String,Object> run(String scenario, int slot) throws Exception {
        return (Map<String,Object>) fixture.getMethod("run", String.class, int.class).invoke(null, scenario, slot);
    }

    static void assertWrongTool(Map<String,Object> result) {
        assertEquals(false, result.get("accepted"));
        assertEquals("WRONG_TOOL", result.get("type"));
        assertFalse(((String) result.get("message")).isBlank());
    }

    static void add(Path src, List<String> args, String relative, String source) throws Exception {
        Path path = src.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, source, StandardCharsets.UTF_8);
        args.add(path.toString());
    }

    // These two production methods are at class indentation. Preserve each method
    // byte-for-byte; require a unique signature and its class-level closing brace.
    static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0 && source.indexOf(signature, start + 1) < 0, "unique production method: " + signature);
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start, "complete production method: " + signature);
        return source.substring(start, end + "\n    }".length());
    }
}
