package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.worldstate.core.freshness.TopicFreshnessPolicy;
import com.dwinovo.numen.worldstate.core.model.*;
import com.dwinovo.numen.worldstate.core.observation.payload.ContainerPayload;
import com.dwinovo.numen.worldstate.core.provider.*;
import com.dwinovo.numen.worldstate.core.query.QueryService;
import com.dwinovo.numen.worldstate.core.refresh.RefreshService;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;
import com.dwinovo.numen.worldstate.core.store.InMemoryObservationStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Complete production provider + context guard + public ContainerReader source, with real core
 * query/refresh/store. Only MC, Services and McCollectionContext are boundary fixtures.
 * Exercises block targets only: player signatures are compile-only, not player/MC/platform coverage.
 * Failed refresh preserves an old observation; this does NOT claim it marks that observation stale. */
class ContainerProviderReadConsumerTest {
    private static final String PKG = "com.dwinovo.numen.plugins.worldstate.";
    private static final ObservationRequest REQUEST =
            ObservationRequest.containers("minecraft:overworld", "block:1,64,-3");
    @TempDir static Path compiled;
    private static URLClassLoader loader;
    private static Class<?> fixture;
    private QueryService query;
    private InMemoryObservationStore store;
    private CollectionContext context;

    private static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("world-state-core/src/main/java"))) p = p.getParent();
        if (p == null) throw new IllegalStateException("source root missing");
        return p;
    }

    @BeforeAll static void compileWholeProvider() throws Exception {
        var args = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none", "-classpath",
                Path.of(WorldProvider.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                        + java.io.File.pathSeparator
                        + Path.of(com.google.gson.Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI()),
                "-d", compiled.toString()));
        for (var stub : boundaries().entrySet()) {
            Path source = compiled.resolve(stub.getKey().replace('.', '/') + ".java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, stub.getValue(), StandardCharsets.UTF_8);
            args.add(source.toString());
        }
        Path plugin = root().resolve("plugins/worldstate/src/main/java/com/dwinovo/numen/plugins/worldstate/provider");
        for (String name : List.of("ContainerWorldProvider", "McCollectionContexts"))
            args.add(plugin.resolve(name + ".java").toString());
        args.add(root().resolve("api/common/src/main/java/com/dwinovo/numen/api/storage/ContainerReader.java").toString());
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK required");
        var diagnostics = new ByteArrayOutputStream();
        assertEquals(0, compiler.run(null, diagnostics, diagnostics, args.toArray(String[]::new)),
                diagnostics.toString(StandardCharsets.UTF_8));
        // Never fall back to old plugin/MC/API/platform classes from the test runtime.
        loader = new URLClassLoader(new URL[]{compiled.toUri().toURL()}, ContainerProviderReadConsumerTest.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith(PKG) || name.startsWith("net.minecraft.")
                        || name.equals("com.dwinovo.numen.platform.Services")
                        || name.startsWith("com.dwinovo.numen.api.storage.ContainerReader")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> found = findLoadedClass(name);
                        if (found == null) found = findClass(name);
                        if (resolve) resolveClass(found);
                        return found;
                    }
                }
                return super.loadClass(name, resolve);
            }
        };
        fixture = loader.loadClass(PKG + "ContainerBoundaryFixture");
        for (String name : List.of(PKG + "provider.ContainerWorldProvider", PKG + "provider.McCollectionContexts",
                "com.dwinovo.numen.api.storage.ContainerReader"))
            assertEquals(compiled.toUri().toURL(), loader.loadClass(name).getProtectionDomain().getCodeSource().getLocation());
    }

    @AfterAll static void closeLoader() throws Exception { if (loader != null) loader.close(); }

    @BeforeEach void connectRealCore() throws Exception {
        var registry = new ProviderRegistry();
        registry.register((WorldProvider) loader.loadClass(PKG + "provider.ContainerWorldProvider").getConstructor().newInstance());
        context = (CollectionContext) fixture.getMethod("context").invoke(null);
        store = new InMemoryObservationStore(4);
        query = new QueryService(store, TopicFreshnessPolicy.defaults(), new RefreshService(registry, store, Runnable::run), () -> 1000L);
        configure("empty", true);
    }

    private void configure(String mode, boolean loaded) throws Exception {
        fixture.getMethod("configure", String.class, boolean.class).invoke(null, mode, loaded);
    }
    private int calls() throws Exception { return (int) fixture.getMethod("calls").invoke(null); }
    private ContainerPayload payload() {
        var read = query.read("world", "session", REQUEST);
        assertTrue(read.present());
        assertEquals(Coverage.COMPLETE, read.coverage());
        assertEquals(Freshness.CURRENT, read.freshness());
        return assertInstanceOf(ContainerPayload.class, read.observation().payload());
    }

    @Test void knownEmptyIsCompleteEmptyThroughRefreshStoreAndRead() throws Exception {
        assertTrue(query.refresh(context, REQUEST).success());
        assertEquals(Map.of(), payload().items());
        assertEquals(0, payload().totalItems());
        assertEquals(27, payload().slots());
        assertTrue(query.read("world", "session", REQUEST).canProveAbsence());
        assertEquals(1, calls(), "read must not collect again");
    }

    @Test void legalNonemptyRetainsTypedCountsAndTarget() throws Exception {
        configure("items", true);
        assertTrue(query.refresh(context, REQUEST).success());
        var value = payload();
        assertEquals(Map.of("minecraft:stone", 7, "mod:meal", 2), value.items());
        assertEquals(9, value.totalItems());
        assertEquals(REQUEST.dimension(), value.dimension());
        assertEquals(REQUEST.target(), value.target());
        assertEquals(1, calls());
    }

    @Test void unknownNullAndThrowsNeverCreateKnownEmpty() throws Exception {
        for (String mode : List.of("unknown", "null", "throws", "linkage")) {
            configure(mode, true);
            var failed = query.refresh(context, REQUEST);
            assertFalse(failed.success(), mode);
            assertEquals(FailReason.PROVIDER_ERROR, failed.reason(), mode);
            assertNull(failed.observation(), mode);
            assertTrue(store.all().isEmpty(), mode);
            assertFalse(query.read("world", "session", REQUEST).present(), mode);
            assertFalse(query.read("world", "session", REQUEST).canProveAbsence(), mode);
            assertEquals(1, calls(), mode);
        }
    }

    @Test void unloadedRejectsBeforeReaderAndLeavesStoreMissing() throws Exception {
        configure("throws", false);
        var failed = query.refresh(context, REQUEST);
        assertEquals(FailReason.CHUNK_UNLOADED, failed.reason());
        assertFalse(failed.success());
        assertNull(failed.observation());
        assertEquals(0, calls());
        assertTrue(store.all().isEmpty());
        assertFalse(query.read("world", "session", REQUEST).present());
    }

    @Test void failedRefreshesNeverOverwritePreviousNonemptyObservation() throws Exception {
        configure("items", true);
        var previous = query.refresh(context, REQUEST).observation();
        assertNotNull(previous);
        for (String mode : List.of("unknown", "null", "throws", "linkage", "unloaded")) {
            configure(mode, !mode.equals("unloaded"));
            var failed = query.refresh(context, REQUEST);
            assertFalse(failed.success(), mode);
            assertEquals(mode.equals("unloaded") ? FailReason.CHUNK_UNLOADED : FailReason.PROVIDER_ERROR, failed.reason(), mode);
            assertNull(failed.observation(), mode);
            assertSame(previous, store.get(REQUEST.keyFor("world")).orElseThrow(), mode);
            assertSame(previous, query.read("world", "session", REQUEST).observation(), mode);
            assertEquals(mode.equals("unloaded") ? 0 : 1, calls(), mode);
        }
    }

    private static Map<String, String> boundaries() {
        var sources = new LinkedHashMap<String, String>();
        sources.put("net.minecraft.core.BlockPos", """
                package net.minecraft.core;
                public record BlockPos(int x, int y, int z) {}
                """);
        sources.put("net.minecraft.world.level.Level", "package net.minecraft.world.level; public class Level {}");
        sources.put("net.minecraft.world.Container", "package net.minecraft.world; public interface Container { int getContainerSize(); }");
        sources.put("net.minecraft.world.level.block.entity.BlockEntity", "package net.minecraft.world.level.block.entity; public class BlockEntity {}");
        sources.put("net.minecraft.server.level.ServerLevel", """
                package net.minecraft.server.level;
                public class ServerLevel extends net.minecraft.world.level.Level {
                    public boolean loaded = true;
                    public boolean hasChunkAt(net.minecraft.core.BlockPos pos) { return loaded; }
                    public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(net.minecraft.core.BlockPos pos) { return new Chest(); }
                    private static class Chest extends net.minecraft.world.level.block.entity.BlockEntity implements net.minecraft.world.Container {
                        public int getContainerSize() { return 27; }
                    }
                }
                """);
        sources.put(PKG + "McCollectionContext", """
                package com.dwinovo.numen.plugins.worldstate;
                public class McCollectionContext implements com.dwinovo.numen.worldstate.core.provider.CollectionContext {
                    public final net.minecraft.server.level.ServerLevel level = new net.minecraft.server.level.ServerLevel();
                    public net.minecraft.server.level.ServerLevel levelOf(String dimension) { return level; }
                    public net.minecraft.server.MinecraftServer server() { throw new AssertionError("player path not covered"); }
                    public String worldId() { return "world"; }
                    public String serverSession() { return "session"; }
                    public long gameTick() { return 20L; }
                    public long nowMs() { return 1000L; }
                }
                """);
        sources.put("com.dwinovo.numen.platform.Services", """
                package com.dwinovo.numen.platform;
                public class Services { public static com.dwinovo.numen.api.storage.ContainerReader CONTAINERS; }
                """);
        sources.put(PKG + "ContainerBoundaryFixture", """
                package com.dwinovo.numen.plugins.worldstate;
                import com.dwinovo.numen.api.storage.ContainerReader;
                import com.dwinovo.numen.platform.Services;
                import java.util.Map;
                public class ContainerBoundaryFixture {
                    private static final McCollectionContext CONTEXT = new McCollectionContext();
                    private static int calls;
                    public static com.dwinovo.numen.worldstate.core.provider.CollectionContext context() { return CONTEXT; }
                    public static int calls() { return calls; }
                    public static void configure(String mode, boolean loaded) {
                        calls = 0; CONTEXT.level.loaded = loaded;
                        Services.CONTAINERS = (level, pos) -> {
                            calls++;
                            if (level != CONTEXT.level || !pos.equals(new net.minecraft.core.BlockPos(1,64,-3)))
                                throw new AssertionError("wrong reader arguments");
                            return switch (mode) {
                                case "empty" -> ContainerReader.Result.known(Map.of());
                                case "items" -> ContainerReader.Result.known(Map.of("minecraft:stone",7,"mod:meal",2));
                                case "unknown" -> ContainerReader.Result.unknown("fixture unreadable");
                                case "null" -> null;
                                case "throws" -> throw new IllegalStateException("reader broke");
                                case "linkage" -> throw new NoClassDefFoundError("reader linkage");
                                default -> throw new AssertionError("unexpected reader call: " + mode);
                            };
                        };
                    }
                }
                """);
        // Only signatures required to compile the untouched player's branch. Never executed.
        sources.put("net.minecraft.server.MinecraftServer", """
                package net.minecraft.server;
                public class MinecraftServer { public net.minecraft.server.players.PlayerList getPlayerList() { throw new AssertionError(); } }
                """);
        sources.put("net.minecraft.server.players.PlayerList", """
                package net.minecraft.server.players;
                public class PlayerList { public net.minecraft.server.level.ServerPlayer getPlayer(java.util.UUID id) { throw new AssertionError(); } }
                """);
        sources.put("net.minecraft.server.level.ServerPlayer", """
                package net.minecraft.server.level;
                public class ServerPlayer { public net.minecraft.world.entity.player.Inventory getInventory() { throw new AssertionError(); } }
                """);
        sources.put("net.minecraft.world.entity.player.Inventory", """
                package net.minecraft.world.entity.player;
                public class Inventory {
                    public int getContainerSize() { throw new AssertionError(); }
                    public net.minecraft.world.item.ItemStack getItem(int slot) { throw new AssertionError(); }
                }
                """);
        sources.put("net.minecraft.world.item.ItemStack", """
                package net.minecraft.world.item;
                public class ItemStack {
                    public boolean isEmpty() { throw new AssertionError(); }
                    public Object getItem() { throw new AssertionError(); }
                    public int getCount() { throw new AssertionError(); }
                }
                """);
        sources.put("net.minecraft.core.registries.BuiltInRegistries", """
                package net.minecraft.core.registries;
                public class BuiltInRegistries {
                    public static final Registry ITEM = new Registry();
                    public static class Registry { public Object getKey(Object item) { throw new AssertionError(); } }
                }
                """);
        return sources;
    }
}
