package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real RddDetector death-consumer methods compiled from production source. The probe executes
 * countInventory/freezeDeathInventoryAndSkip with narrow body/projection/cache ports and verifies
 * that a corpse's cleared inventory can neither replace the death fact nor advance living work.
 */
class RddDetectorDeathInventoryTest {
    @TempDir static Path temp;
    private static URLClassLoader loader;
    private static Class<?> probe;
    private static Class<?> bodyClass;
    private static Class<?> snapshotClass;

    @BeforeAll static void compileHarness() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(
                "plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddDetector.java"))) root = root.getParent();
        assertNotNull(root, "SETUP: repository root missing");
        assertNotNull(ToolProvider.getSystemJavaCompiler(), "SETUP: full JDK required");
        String detector = Files.readString(root.resolve(
                "plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddDetector.java"));

        Map<String, String> units = new LinkedHashMap<>();
        units.put("com/dwinovo/numen/entity/DeathInventorySnapshot.java", """
                package com.dwinovo.numen.entity;
                import java.util.*;
                public record DeathInventorySnapshot(UUID companionId,int bodyEntityId,long deathGeneration,
                                                     long capturedAtGameTick,String dimension,Map<String,Integer> inventory) {
                  public DeathInventorySnapshot { inventory=Map.copyOf(inventory); }
                  public boolean belongsTo(NumenPlayer body){ return body!=null && companionId.equals(body.getUUID()) && bodyEntityId==body.getId(); }
                }
                """);
        units.put("com/dwinovo/numen/entity/NumenPlayer.java", """
                package com.dwinovo.numen.entity;
                import java.util.*;
                public class NumenPlayer {
                  private final UUID uuid; private final int id; private final boolean deathPending;
                  private final Optional<DeathInventorySnapshot> snapshot; private final Map<String,Integer> live;
                  public NumenPlayer(UUID uuid,int id,boolean deathPending,Optional<DeathInventorySnapshot> snapshot,Map<String,Integer> live){
                    this.uuid=uuid; this.id=id; this.deathPending=deathPending; this.snapshot=snapshot; this.live=live;
                  }
                  public UUID getUUID(){return uuid;} public int getId(){return id;}
                  public boolean deathPending(){return deathPending;}
                  public Optional<DeathInventorySnapshot> deathInventorySnapshot(){return snapshot;}
                  public Map<String,Integer> live(){return live;}
                }
                """);
        units.put("com/dwinovo/numen/plugins/rdd/RddInventoryProjection.java", """
                package com.dwinovo.numen.plugins.rdd;
                import java.util.*; import com.dwinovo.numen.entity.NumenPlayer;
                public final class RddInventoryProjection {
                  public static int SCANS;
                  public static Map<String,Integer> countInventory(NumenPlayer body){ SCANS++; return body.live(); }
                }
                """);
        units.put("com/dwinovo/numen/plugins/rdd/RddPlugin.java", """
                package com.dwinovo.numen.plugins.rdd;
                import java.util.*; import com.dwinovo.numen.entity.NumenPlayer;
                public final class RddPlugin {
                  public static final List<Map.Entry<UUID,Map<String,Integer>>> CACHE_WRITES=new ArrayList<>();
                  public static void cacheInventory(UUID id,Map<String,Integer> counts){ CACHE_WRITES.add(Map.entry(id,Map.copyOf(counts))); }
                }
                """);
        units.put("com/dwinovo/numen/plugins/rdd/RddDetectorProbe.java", """
                package com.dwinovo.numen.plugins.rdd;
                import java.util.*; import com.dwinovo.numen.entity.*; import com.dwinovo.numen.entity.NumenPlayer;
                public final class RddDetectorProbe {
                """ + method(detector, "    private static boolean freezeDeathInventoryAndSkip(")
                + method(detector, "    static Map<String, Integer> countInventory(") + """
                  public static void reset(){ RddPlugin.CACHE_WRITES.clear(); RddInventoryProjection.SCANS=0; }
                  public static Map<String,Integer> count(NumenPlayer body){ return countInventory(body); }
                  public static boolean freeze(NumenPlayer body){ return freezeDeathInventoryAndSkip(body); }
                  public static List<Map.Entry<UUID,Map<String,Integer>>> writes(){ return RddPlugin.CACHE_WRITES; }
                  public static int scans(){ return RddInventoryProjection.SCANS; }
                }
                """);

        Path classes = temp.resolve("classes");
        Files.createDirectories(classes);
        var args = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none", "-d", classes.toString()));
        for (var entry : units.entrySet()) {
            Path file = temp.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
            args.add(file.toString());
        }
        var diagnostics = new ByteArrayOutputStream();
        int rc = ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics, args.toArray(String[]::new));
        assertEquals(0, rc, "SETUP: Detector death-consumer harness compile failed:\n" + diagnostics);
        loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        probe = loader.loadClass("com.dwinovo.numen.plugins.rdd.RddDetectorProbe");
        bodyClass = loader.loadClass("com.dwinovo.numen.entity.NumenPlayer");
        snapshotClass = loader.loadClass("com.dwinovo.numen.entity.DeathInventorySnapshot");
        reset();
    }

    @AfterAll static void close() throws Exception { if (loader != null) loader.close(); }

    @Test void deathSnapshotWinsOverClearedBodyAndIsFrozenIntoCache() throws Exception {
        UUID id = UUID.randomUUID();
        Object snapshot = snapshot(id, 51, Map.of("minecraft:diamond", 3));
        Object body = body(id, 51, true, snapshot, Map.of());
        reset();

        assertEquals(Map.of("minecraft:diamond", 3), count(body));
        assertTrue((boolean) freeze(body));
        assertEquals(0, scans(), "death consumer must not scan the cleared corpse");
        assertEquals(1, writes().size());
        assertEquals(id, writes().getFirst().getKey());
        assertEquals(Map.of("minecraft:diamond", 3), writes().getFirst().getValue());
    }

    @Test void ordinaryLivingRefreshStillReadsRealInventoryAndDoesNotSkipRuntime() throws Exception {
        Object body = body(UUID.randomUUID(), 52, false, null, Map.of("minecraft:bread", 2));
        reset();
        assertEquals(Map.of("minecraft:bread", 2), count(body));
        assertFalse((boolean) freeze(body));
        assertEquals(1, scans());
        assertTrue(writes().isEmpty());
    }

    @Test void deathWithoutSnapshotSkipsOrdinaryRefreshInsteadOfCachingEmpty() throws Exception {
        Object body = body(UUID.randomUUID(), 53, true, null, Map.of());
        reset();
        assertTrue((boolean) freeze(body), "UNKNOWN death evidence must skip living runtime, not continue");
        assertTrue(writes().isEmpty(), "an empty corpse must not overwrite the last living cache");
        assertEquals(0, scans());
    }

    @Test void capturedEmptySnapshotIsAnExplicitKnownEmptyCacheFact() throws Exception {
        UUID id = UUID.randomUUID();
        Object body = body(id, 54, true, snapshot(id, 54, Map.of()), Map.of());
        reset();
        assertEquals(Map.of(), count(body));
        assertTrue((boolean) freeze(body));
        assertEquals(1, writes().size(), "present-empty must remain distinguishable from missing evidence");
        assertEquals(Map.of(), writes().getFirst().getValue());
    }

    @Test void snapshotFromAnotherBodyCannotBeUsedByThisBody() throws Exception {
        UUID id = UUID.randomUUID();
        Object oldSnapshot = snapshot(id, 55, Map.of("minecraft:gold_ingot", 8));
        Object newBody = body(id, 56, true, oldSnapshot, Map.of("minecraft:stone", 1));
        reset();
        assertEquals(Map.of("minecraft:stone", 1), count(newBody));
        assertTrue((boolean) freeze(newBody));
        assertTrue(writes().isEmpty(), "body identity mismatch must not cache the prior body's fact");
    }

    @SuppressWarnings("unchecked")
    private static List<Map.Entry<UUID, Map<String, Integer>>> writes() throws Exception {
        return (List<Map.Entry<UUID, Map<String, Integer>>>) probe.getMethod("writes").invoke(null);
    }

    private static int scans() throws Exception { return (int) probe.getMethod("scans").invoke(null); }

    private static Map<?, ?> count(Object body) throws Exception {
        return (Map<?, ?>) probe.getMethod("count", bodyClass).invoke(null, body);
    }

    private static boolean freeze(Object body) throws Exception {
        return (boolean) probe.getMethod("freeze", bodyClass).invoke(null, body);
    }

    private static void reset() throws Exception { probe.getMethod("reset").invoke(null); }

    private static Object snapshot(UUID id, int bodyId, Map<String, Integer> counts) throws Exception {
        return snapshotClass.getConstructor(UUID.class, int.class, long.class, long.class, String.class, Map.class)
                .newInstance(id, bodyId, 1L, 100L, "minecraft:overworld", counts);
    }

    private static Object body(UUID id, int bodyId, boolean pending, Object snapshot,
                               Map<String, Integer> live) throws Exception {
        return bodyClass.getConstructor(UUID.class, int.class, boolean.class, Optional.class, Map.class)
                .newInstance(id, bodyId, pending, Optional.ofNullable(snapshot), live);
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "SETUP: method missing: " + signature);
        int depth = 0;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(start, i + 1) + "\n";
        }
        throw new AssertionError("SETUP: unbalanced method: " + signature);
    }
}
