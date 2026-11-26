package com.dwinovo.numen.entity;

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
 * Death-fact regression through the real NumenPlayer death methods, real snapshot capture and
 * real Companions retention/lookup methods. Only Minecraft's inventory/registry/body ports are
 * stubbed; stub vanilla die() performs the production-critical drop/clear synchronously.
 */
class NumenPlayerDeathInventoryTest {
    @TempDir static Path temp;
    private static Path root;
    private static URLClassLoader loader;
    private static Class<?> bodyClass;
    private static Class<?> snapshotClass;
    private static Class<?> companionsClass;

    @BeforeAll static void compileHarness() throws Exception {
        root = repositoryRoot();
        assertNotNull(ToolProvider.getSystemJavaCompiler(), "SETUP: full JDK required");
        String player = Files.readString(root.resolve(
                "api/common/src/main/java/com/dwinovo/numen/entity/NumenPlayer.java"));
        String companions = Files.readString(root.resolve(
                "api/common/src/main/java/com/dwinovo/numen/entity/Companions.java"));

        Map<String, String> units = new LinkedHashMap<>();
        units.put("com/dwinovo/numen/entity/DeathInventorySnapshot.java", Files.readString(root.resolve(
                "api/common/src/main/java/com/dwinovo/numen/entity/DeathInventorySnapshot.java")));
        units.put("net/minecraft/core/registries/BuiltInRegistries.java", """
                package net.minecraft.core.registries;
                import net.minecraft.world.item.Item;
                public final class BuiltInRegistries {
                  public static final ItemRegistry ITEM = new ItemRegistry();
                  public static final class ItemRegistry {
                    public net.minecraft.core.ResourceLocation getKey(Item item) { return item.key; }
                  }
                }
                """);
        units.put("net/minecraft/core/ResourceLocation.java", """
                package net.minecraft.core;
                public record ResourceLocation(String value) { public String toString(){ return value; } }
                """);
        units.put("net/minecraft/world/item/Item.java", """
                package net.minecraft.world.item;
                public final class Item {
                  public final net.minecraft.core.ResourceLocation key;
                  public Item(String id){ key=new net.minecraft.core.ResourceLocation(id); }
                }
                """);
        units.put("net/minecraft/world/item/ItemStack.java", """
                package net.minecraft.world.item;
                public final class ItemStack {
                  private final Item item; private final int count;
                  public ItemStack(){ this(null,0); }
                  public ItemStack(String id,int count){ this.item=count<=0?null:new Item(id); this.count=count; }
                  public boolean isEmpty(){ return item==null || count<=0; }
                  public Item getItem(){ return item; }
                  public int getCount(){ return count; }
                }
                """);
        units.put("net/minecraft/world/level/Level.java", """
                package net.minecraft.world.level;
                public class Level {
                  public long getGameTime(){ return 1234L; }
                  public Dimension dimension(){ return new Dimension("minecraft:overworld"); }
                  public record Dimension(String id) {
                    public net.minecraft.core.ResourceLocation location(){ return new net.minecraft.core.ResourceLocation(id); }
                  }
                }
                """);
        units.put("net/minecraft/world/damagesource/DamageSource.java", """
                package net.minecraft.world.damagesource;
                public class DamageSource {
                  private final String id; private final net.minecraft.world.entity.Entity entity;
                  public DamageSource(String id,net.minecraft.world.entity.Entity entity){ this.id=id; this.entity=entity; }
                  public String getMsgId(){ return id; }
                  public net.minecraft.world.entity.Entity getEntity(){ return entity; }
                }
                """);
        units.put("net/minecraft/world/entity/Entity.java", """
                package net.minecraft.world.entity;
                public class Entity {
                  public net.minecraft.network.chat.Component getName(){ return new net.minecraft.network.chat.Component("attacker"); }
                }
                """);
        units.put("net/minecraft/network/chat/Component.java", """
                package net.minecraft.network.chat;
                public class Component { private final String text; public Component(String t){text=t;} public String getString(){return text;} }
                """);
        units.put("net/minecraft/server/level/ServerPlayer.java", """
                package net.minecraft.server.level;
                import net.minecraft.world.damagesource.DamageSource;
                import net.minecraft.world.item.ItemStack;
                public class ServerPlayer {
                  public Inventory inventory = new Inventory();
                  public float health = 20.0f;
                  public boolean dead;
                  public int vanillaDieCalls;
                  public void die(DamageSource cause){ vanillaDieCalls++; inventory.clear(); health=0; dead=true; }
                  public Inventory getInventory(){ return inventory; }
                  public float getHealth(){ return health; }
                  public boolean isDeadOrDying(){ return dead; }
                  public static class Inventory {
                    public final java.util.Map<Integer,ItemStack> slots=new java.util.LinkedHashMap<>();
                    public int getContainerSize(){ return slots.size(); }
                    public ItemStack getItem(int slot){ return slots.getOrDefault(slot,new ItemStack()); }
                    public void clear(){ slots.clear(); }
                  }
                }
                """);
        units.put("com/dwinovo/numen/Constants.java", """
                package com.dwinovo.numen;
                public final class Constants {
                  public static final Log LOG=new Log();
                  public static final class Log { public void warn(String s,Object... a){} }
                }
                """);
        units.put("com/dwinovo/numen/entity/Companions.java", """
                package com.dwinovo.numen.entity;
                import java.util.*;
                import java.util.concurrent.ConcurrentHashMap;
                public final class Companions {
                  private Companions() {}
                  private static final Map<UUID, DeathInventorySnapshot> DEATH_INVENTORY_SNAPSHOTS = new ConcurrentHashMap<>();
                """ + method(companions, "    static void rememberDeathInventory(")
                + method(companions, "    public static Optional<DeathInventorySnapshot> deathInventorySnapshot(NumenPlayer body)")
                + method(companions, "    public static Optional<DeathInventorySnapshot> deathInventorySnapshot(UUID companionId, int bodyEntityId)")
                + method(companions, "    private static void forgetDeathInventory(UUID companionId, int bodyEntityId)")
                + """
                  public static void clear(){ DEATH_INVENTORY_SNAPSHOTS.clear(); }
                  public static void remove(UUID id,int bodyId){ forgetDeathInventory(id,bodyId); }
                }
                """);
        units.put("com/dwinovo/numen/entity/NumenPlayer.java", """
                package com.dwinovo.numen.entity;
                import java.util.*;
                import net.minecraft.server.level.ServerPlayer;
                import net.minecraft.world.item.ItemStack;
                public final class NumenPlayer extends ServerPlayer {
                  private long deathGeneration;
                  private DeathInventorySnapshot deathInventorySnapshot;
                  private String deathMessage, deathCauseId, deathCauseAttacker;
                  private final UUID uuid; private final int entityId; private final net.minecraft.world.level.Level world=new net.minecraft.world.level.Level();
                  public boolean failCapture;
                  public NumenPlayer(UUID uuid,int id){ this.uuid=uuid; this.entityId=id; }
                  public UUID getUUID(){ return uuid; } public int getId(){ return entityId; }
                  public net.minecraft.world.level.Level level(){ return world; }
                  @Override public Inventory getInventory(){
                    if(failCapture) throw new IllegalStateException("capture unavailable");
                    return super.getInventory();
                  }
                  public CombatTracker getCombatTracker(){ return new CombatTracker(); }
                  public static class CombatTracker {
                    public net.minecraft.network.chat.Component getDeathMessage(){ return new net.minecraft.network.chat.Component("died"); }
                  }
                  public void observeDeathWithoutDie(){ latchDeath(false); }
                  public void put(int slot,String id,int count){ inventory.slots.put(slot,new ItemStack(id,count)); }
                  public void replaceItems(Map<String,Integer> items){
                    inventory.clear(); int slot=0;
                    for(var e:items.entrySet()) put(slot++,e.getKey(),e.getValue());
                  }
                """ + method(player, "    private void latchDeath(")
                + method(player, "    public long deathGeneration(")
                + method(player, "    public boolean deathPending(")
                + method(player, "    public Optional<DeathInventorySnapshot> deathInventorySnapshot(")
                + method(player, "    public void die(net.minecraft.world.damagesource.DamageSource cause)")
                + """
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
        assertEquals(0, rc, "SETUP: death-inventory harness compile failed:\n" + diagnostics);
        loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        bodyClass = loader.loadClass("com.dwinovo.numen.entity.NumenPlayer");
        snapshotClass = loader.loadClass("com.dwinovo.numen.entity.DeathInventorySnapshot");
        companionsClass = loader.loadClass("com.dwinovo.numen.entity.Companions");
        companionsClass.getMethod("clear").invoke(null);
    }

    @AfterAll static void close() throws Exception { if (loader != null) loader.close(); }

    @Test void preDeathInventorySurvivesVanillaClearAndDelayedDeathRead() throws Exception {
        UUID id = UUID.randomUUID();
        Object body = body(id, 41, Map.of("minecraft:diamond_sword", 1, "minecraft:bread", 3));
        bodyClass.getMethod("die", loader.loadClass("net.minecraft.world.damagesource.DamageSource"))
                .invoke(body, (Object) null);

        Object clearedInventory = bodyClass.getMethod("getInventory").invoke(body);
        assertEquals(0, clearedInventory.getClass().getMethod("getContainerSize").invoke(clearedInventory),
                "stub vanilla die must synchronously clear the body");
        Object snapshot = snapshot(body);
        assertEquals(Map.of("minecraft:diamond_sword", 1, "minecraft:bread", 3), inventory(snapshot));
        assertEquals(1L, bodyClass.getMethod("deathGeneration").invoke(body));
        assertTrue((boolean) bodyClass.getMethod("deathPending").invoke(body));
        Object retained = ((Optional<?>) companionsClass.getMethod("deathInventorySnapshot", UUID.class, int.class)
                .invoke(null, id, 41)).orElseThrow();
        assertSame(snapshot, retained, "delayed consumer must still read the retained pre-clear fact");
        assertEquals(1234L, snapshotClass.getMethod("capturedAtGameTick").invoke(snapshot));
        assertEquals("minecraft:overworld", snapshotClass.getMethod("dimension").invoke(snapshot));
        assertThrows(UnsupportedOperationException.class, () -> inventory(snapshot).put("x", 1));
    }

    @Test void repeatedDieKeepsFirstSnapshotAndDeathGeneration() throws Exception {
        Object body = body(UUID.randomUUID(), 42, Map.of("minecraft:iron_ingot", 2));
        bodyClass.getMethod("die", loader.loadClass("net.minecraft.world.damagesource.DamageSource")).invoke(body, (Object) null);
        Object first = snapshot(body);
        bodyClass.getMethod("replaceItems", Map.class).invoke(body, Map.of("minecraft:diamond", 9));
        bodyClass.getMethod("die", loader.loadClass("net.minecraft.world.damagesource.DamageSource")).invoke(body, (Object) null);

        assertSame(first, snapshot(body), "repeated die must not replace the first-death fact");
        assertEquals(Map.of("minecraft:iron_ingot", 2), inventory(first));
        assertEquals(1L, bodyClass.getMethod("deathGeneration").invoke(body));
        assertEquals(2, bodyClass.getField("vanillaDieCalls").get(body));
    }

    @Test void missingSnapshotIsUnknownAndDeathGenerationIsStillLatched() throws Exception {
        Object body = body(UUID.randomUUID(), 43, Map.of());
        bodyClass.getMethod("observeDeathWithoutDie").invoke(body);
        assertTrue(((Optional<?>) bodyClass.getMethod("deathInventorySnapshot").invoke(body)).isEmpty(),
                "absence is UNKNOWN, never a known-empty map");
        assertEquals(1L, bodyClass.getMethod("deathGeneration").invoke(body));
        assertTrue((boolean) bodyClass.getMethod("deathPending").invoke(body));
    }

    @Test void capturedEmptyInventoryIsPresentButDoesNotClaimNoLoss() throws Exception {
        Object body = body(UUID.randomUUID(), 44, Map.of());
        bodyClass.getMethod("die", loader.loadClass("net.minecraft.world.damagesource.DamageSource")).invoke(body, (Object) null);
        Object snapshot = snapshot(body);
        assertEquals(Map.of(), inventory(snapshot));
        assertTrue((boolean) snapshotClass.getMethod("isEmpty").invoke(snapshot));
        assertTrue(((Optional<?>) bodyClass.getMethod("deathInventorySnapshot").invoke(body)).isPresent(),
                "captured empty evidence must remain distinguishable from UNKNOWN");
    }

    @Test void sameUuidNewBodyCannotReadOldBodySnapshot() throws Exception {
        UUID id = UUID.randomUUID();
        Object oldBody = body(id, 45, Map.of("minecraft:gold_ingot", 4));
        bodyClass.getMethod("die", loader.loadClass("net.minecraft.world.damagesource.DamageSource")).invoke(oldBody, (Object) null);
        Object oldSnapshot = snapshot(oldBody);
        Object newBody = body(id, 46, Map.of());

        assertFalse((boolean) snapshotClass.getMethod("belongsTo", bodyClass).invoke(oldSnapshot, newBody));
        assertTrue(((Optional<?>) companionsClass.getMethod("deathInventorySnapshot", UUID.class, int.class)
                .invoke(null, id, 46)).isEmpty());
    }

    @Test void failedCaptureIsNotRetriedAfterVanillaClear() throws Exception {
        Object body = body(UUID.randomUUID(), 47, Map.of("minecraft:stone", 1));
        bodyClass.getField("failCapture").set(body, true);
        Class<?> damage = loader.loadClass("net.minecraft.world.damagesource.DamageSource");
        bodyClass.getMethod("die", damage).invoke(body, (Object) null);
        bodyClass.getField("failCapture").set(body, false);
        bodyClass.getMethod("replaceItems", Map.class).invoke(body, Map.of());
        bodyClass.getMethod("die", damage).invoke(body, (Object) null);

        assertTrue(((Optional<?>) bodyClass.getMethod("deathInventorySnapshot").invoke(body)).isEmpty(),
                "a post-clear retry would manufacture a false known-empty snapshot");
        assertEquals(1L, bodyClass.getMethod("deathGeneration").invoke(body));
    }

    private static Object snapshot(Object body) throws Exception {
        return ((Optional<?>) bodyClass.getMethod("deathInventorySnapshot").invoke(body)).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> inventory(Object snapshot) throws Exception {
        return (Map<String, Integer>) snapshotClass.getMethod("inventory").invoke(snapshot);
    }

    private static Object body(UUID id, int entityId, Map<String, Integer> items) throws Exception {
        Object body = bodyClass.getConstructor(UUID.class, int.class).newInstance(id, entityId);
        bodyClass.getMethod("replaceItems", Map.class).invoke(body, items);
        return body;
    }

    private static Path repositoryRoot() {
        Path at = Path.of("").toAbsolutePath();
        while (at != null && !Files.isRegularFile(at.resolve(
                "api/common/src/main/java/com/dwinovo/numen/entity/NumenPlayer.java"))) at = at.getParent();
        return at;
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
