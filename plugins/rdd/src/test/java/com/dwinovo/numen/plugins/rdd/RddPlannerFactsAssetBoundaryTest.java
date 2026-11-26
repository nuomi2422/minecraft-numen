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
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Compiles the unmodified planner; MC and asset/container boundaries are test doubles.
 * This does not exercise the real asset renderer or a final model request. */
class RddPlannerFactsAssetBoundaryTest {
    @TempDir Path tmp;
    private static final String CATALOG = "<assets><asset id=\"home\">bed,chest,furnace</asset>"
            + "<asset id=\"farm\">wheat,water</asset></assets>";

    @Test void shortInputsKeepCompleteDirectory() throws Exception {
        String out = run("iron x 2", CATALOG);
        assertTrue(out.length() <= 420, out);
        assertTrue(out.contains("箱子里：iron x 2"), out);
        assertTrue(out.contains(CATALOG), out);
        assertTrue(out.endsWith("</situation>"), out);
    }

    @Test void longChestNeverDeliversPartialDirectoryOrExceedsBudget() throws Exception {
        String out = run("item,".repeat(65), CATALOG);
        assertAll(
                () -> assertTrue(out.contains("<assets>") || out.contains("世界里：未投递"), "fixture must reach asset boundary: " + out),
                () -> assertTrue(out.length() <= 420, "length=" + out.length() + ": " + out),
                () -> assertTrue(out.contains(CATALOG) || out.contains("世界里：未投递"),
                        "asset directory must be whole or explicitly undelivered: " + out));
    }

    @Test void emptyStorageCacheIsUnknownNotAbsence() throws Exception {
        String out = run("", CATALOG);
        assertTrue(out.contains("附近容器未观测") || out.contains("附近容器未知"), out);
        assertFalse(out.contains("身边 8 格内没有装东西的容器"), out);
    }

    @Test void historicalStorageKeepsItsQualifier() throws Exception {
        String out = run("[历史/当前不可确认] <storage>iron x 2</storage>", CATALOG);
        assertTrue(out.contains("历史/当前不可确认"), out);
        assertTrue(out.contains("iron x 2"), out);
    }

    @Test void excessiveDirectoryIsExplicitlyUndelivered() throws Exception {
        String out = run("iron x 2", "W".repeat(500));
        assertTrue(out.length() <= 420, out);
        assertTrue(out.contains("世界里：未投递"), out);
        assertTrue(out.endsWith("</situation>"), out);
    }

    private String run(String storage, String catalog) throws Exception {
        return run(storage, catalog, null, false);
    }

    String offlineFacts(Map<String, Integer> held, boolean observed) throws Exception {
        return run("", "", held, observed);
    }

    private String run(String storage, String catalog, Map<String, Integer> held, boolean observed) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddPlannerFacts.java"))) root = root.getParent();
        assertNotNull(root, "SETUP ERROR: planner source not found");
        Path source = root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddPlannerFacts.java");
        List<String> args = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none", "-d", tmp.toString()));
        args.add(source.toString());
        stub(args, "net/minecraft/core/BlockPos.java", "package net.minecraft.core; public class BlockPos { public int getX(){return 1;} public int getY(){return 64;} public int getZ(){return 2;} }");
        stub(args, "net/minecraft/core/registries/BuiltInRegistries.java", "package net.minecraft.core.registries; public class BuiltInRegistries {} ");
        stub(args, "net/minecraft/world/level/biome/Biome.java", "package net.minecraft.world.level.biome; public class Biome {} ");
        stub(args, "net/minecraft/world/level/Level.java", "package net.minecraft.world.level; public class Level { public Key dimension(){return new Key();} public static class Key { public String location(){return \"minecraft:overworld\";} } }");
        stub(args, "net/minecraft/server/level/ServerLevel.java", "package net.minecraft.server.level; public class ServerLevel extends net.minecraft.world.level.Level { public Holder getBiome(net.minecraft.core.BlockPos p){return new Holder();} public static class Holder { public java.util.Optional<net.minecraft.world.level.Level.Key> unwrapKey(){return java.util.Optional.of(new net.minecraft.world.level.Level.Key());} } }");
        stub(args, "net/minecraft/server/level/ServerPlayer.java", "package net.minecraft.server.level; public class ServerPlayer { public Object getServer(){return null;} public net.minecraft.core.BlockPos blockPosition(){return new net.minecraft.core.BlockPos();} public net.minecraft.world.level.Level level(){return new ServerLevel();} }");
        stub(args, "com/dwinovo/numen/api/WorldContextStamp.java", "package com.dwinovo.numen.api; public class WorldContextStamp { public record Token(java.util.Map<String,String> identity,long epoch) {} public static Token capture(Object s){return new Token(null,0);} }");
        stub(args, "com/dwinovo/numen/api/NumenPlugins.java", "package com.dwinovo.numen.api; public class NumenPlugins { public static <T> T capability(Class<T> c){return null;} }");
        for (String n : java.util.List.of("ObservationMetadata","WorldObservationService","SelfInventoryQuery","ObservationBatch","ObservationBatchReader","ChangesQuery","ChangesV2Response","Readiness","WorldTime")) args.add(root.resolve("api/common/src/main/java/com/dwinovo/numen/api/observation/"+n+".java").toString());
        stub(args, "com/dwinovo/numen/plugins/rdd/Fixture.java", """
                package com.dwinovo.numen.plugins.rdd;
                class RddContainerMemory { static String value; static String cached(java.util.UUID id){return value;} }
                class RddPlugin { static Object assets(java.util.UUID id){return new Object();} }
                class RddAssetContext { static String value; static String renderForPlanner(Object assets,int max,String objective){return value;} }
                public class Fixture {
                    public static String offline(java.util.Map<String,Integer> held, boolean observed) {
                        return RddPlannerFacts.block(held, observed);
                    }
                    public static String run(String storage,String world) {
                        RddContainerMemory.value=storage; RddAssetContext.value=world;
                        return RddPlannerFacts.block(new net.minecraft.server.level.ServerPlayer(),java.util.UUID.randomUUID());
                    }
                }
                """);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "SETUP ERROR: JDK required");
        assertEquals(0, compiler.run(null, null, null, args.toArray(String[]::new)), "SETUP ERROR: isolated planner compilation failed");
        try (var loader = new URLClassLoader(new URL[]{tmp.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class<?> fixture = loader.loadClass("com.dwinovo.numen.plugins.rdd.Fixture");
            assertEquals(tmp.toUri().toURL(), loader.loadClass("com.dwinovo.numen.plugins.rdd.RddPlannerFacts").getProtectionDomain().getCodeSource().getLocation());
            if (held != null) return (String) fixture.getMethod("offline", Map.class, boolean.class).invoke(null, held, observed);
            return (String) fixture.getMethod("run", String.class, String.class).invoke(null, storage, catalog);
        }
    }

    private void stub(List<String> args, String name, String text) throws Exception {
        Path file = tmp.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        args.add(file.toString());
    }
}
