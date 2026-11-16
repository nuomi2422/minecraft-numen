package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the current projection and Detector delegates with tiny MC boundary stubs.
 * Not a Minecraft lifecycle, registry-bootstrap, or observation-provider test. */
class RddInventoryProjectionTest {
    @TempDir Path output;

    private static Path sources() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            for (String prefix : new String[]{"plugins/rdd/", ""}) {
                Path dir = p.resolve(prefix + "src/main/java/com/dwinovo/numen/plugins/rdd");
                if (Files.isRegularFile(dir.resolve("RddInventoryProjection.java"))) return dir;
            }
        }
        throw new IllegalStateException("projection source missing");
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature);
        int open = source.indexOf('{', start), depth = 0;
        for (int i = open; i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            if (source.charAt(i) == '}' && --depth == 0) return source.substring(start, i + 1);
        }
        throw new AssertionError("unbalanced method");
    }

    private void put(String name, String text) throws Exception {
        Path file = output.resolve(name); Files.createDirectories(file.getParent()); Files.writeString(file, text);
    }

    private URLClassLoader compile() throws Exception {
        String projection = Files.readString(sources().resolve("RddInventoryProjection.java"));
        String detector = Files.readString(sources().resolve("RddDetector.java"));
        assertTrue(detector.contains("static final int MAIN_INV_SLOTS = RddInventoryProjection.MAIN_INV_SLOTS;"));
        String delegates = method(detector, "    static Map<String, Integer> countInventory(") + "\n"
                + method(detector, "    static int freeMainSlots(");
        // Strip imports only; compile the complete current projection unchanged, including potion handling.
        projection = projection.replaceAll("(?m)^package .*;", "").replaceAll("(?m)^import .*;", "")
                .replace("final class RddInventoryProjection {", "static final class RddInventoryProjection {");
        String harness = """
                import java.util.*;
                import com.dwinovo.numen.entity.NumenPlayer;
                import net.minecraft.world.item.ItemStack;
                import net.minecraft.core.registries.BuiltInRegistries;
                public class InventoryProjectionHost {
                """ + projection + "\n" + delegates + """
                    public static Object evaluate(String test) {
                        NumenPlayer p = new NumenPlayer();
                        if (test.equals("allSlots")) {
                            p.slots[0] = new ItemStack("minecraft:stone", 2);
                            p.slots[35] = new ItemStack("minecraft:stone", 3);
                            p.slots[36] = new ItemStack("minecraft:iron_helmet", 1);
                            p.slots[40] = new ItemStack("minecraft:shield", 1);
                            return countInventory(p);
                        }
                        if (test.equals("potions")) {
                            p.slots[0] = new ItemStack("minecraft:potion", 2, "fire_resistance");
                            p.slots[1] = new ItemStack("minecraft:splash_potion", 3, "fire_resistance");
                            p.slots[2] = new ItemStack("minecraft:lingering_potion", 1, "fire_resistance");
                            p.slots[3] = new ItemStack("minecraft:potion", 1);
                            p.slots[4] = new ItemStack("minecraft:potion", 1, "throws");
                            p.slots[5] = new ItemStack("minecraft:stone", 4);
                            return countInventory(p);
                        }
                        if (test.equals("free")) {
                            Arrays.fill(p.slots, new ItemStack("minecraft:stone", 1));
                            p.slots[35] = new ItemStack("", 0); p.slots[40] = new ItemStack("", 0);
                            return freeMainSlots(p);
                        }
                        if (test.equals("short")) { p.slots = new ItemStack[]{new ItemStack("",0),new ItemStack("minecraft:stone",1)}; return freeMainSlots(p); }
                        if (test.equals("fresh")) { Map<String,Integer> a=countInventory(p); a.put("fake",9); return countInventory(p); }
                        if (test.equals("empty")) { p.slots=new ItemStack[0]; return freeMainSlots(p); }
                        if (test.equals("equipmentEmpty")) { Arrays.fill(p.slots,new ItemStack("minecraft:stone",1)); for(int i=36;i<41;i++)p.slots[i]=new ItemStack("",0); return freeMainSlots(p); }
                        if (test.equals("slotFailure")) { p.slots=null; return countInventory(p); }
                        throw new IllegalArgumentException(test);
                    }
                }
                """;
        put("InventoryProjectionHost.java", harness);
        put("com/dwinovo/numen/entity/NumenPlayer.java", """
                package com.dwinovo.numen.entity;
                import net.minecraft.world.item.ItemStack;
                public class NumenPlayer {
                    public ItemStack[] slots = new ItemStack[41];
                    public NumenPlayer(){java.util.Arrays.fill(slots,new ItemStack("",0));}
                    public NumenPlayer getInventory(){return this;}
                    public int getContainerSize(){return slots.length;}
                    public ItemStack getItem(int i){return slots[i];}
                }
                """);
        put("net/minecraft/world/item/Items.java", """
                package net.minecraft.world.item;
                public class Items { public static final String POTION="minecraft:potion", SPLASH_POTION="minecraft:splash_potion", LINGERING_POTION="minecraft:lingering_potion"; }
                """);
        put("net/minecraft/core/component/DataComponents.java", """
                package net.minecraft.core.component;
                public class DataComponents { public static final Object POTION_CONTENTS=new Object(); }
                """);
        put("net/minecraft/core/registries/BuiltInRegistries.java", """
                package net.minecraft.core.registries;
                public class BuiltInRegistries { public static final Registry ITEM=new Registry(); public static class Registry { public String getKey(Object item){return (String)item;} } }
                """);
        put("net/minecraft/world/item/ItemStack.java", """
                package net.minecraft.world.item;
                import java.util.Optional;
                public class ItemStack {
                    private final String id,effect; private final int count;
                    public ItemStack(String id,int count){this(id,count,null);}
                    public ItemStack(String id,int count,String effect){this.id=id;this.count=count;this.effect=effect;}
                    public boolean isEmpty(){return count==0;}
                    public Object getItem(){return id;}
                    public int getCount(){return count;}
                    public boolean is(String wanted){return id.equals(wanted);}
                    public Contents get(Object component){if("throws".equals(effect))throw new IllegalStateException("component unreadable");return effect==null?null:new Contents(effect);}
                    public record Contents(String effect){public Optional<Potion> potion(){return Optional.of(new Potion(effect));}}
                    public record Potion(String effect){public Optional<Key> unwrapKey(){return Optional.of(new Key(effect));}}
                    public record Key(String effect){public Key location(){return this;}public String getPath(){return effect;}}
                }
                """);
        var compiler = ToolProvider.getSystemJavaCompiler(); assertNotNull(compiler);
        String core = Path.of(com.dwinovo.numen.rdd.policy.AssetKeys.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        var args = new java.util.ArrayList<String>(java.util.List.of("-encoding","UTF-8","-proc:none","-classpath",core,"-d",output.toString()));
        try(var files=Files.walk(output)){files.filter(f->f.toString().endsWith(".java")).forEach(f->args.add(f.toString()));}
        assertEquals(0,compiler.run(null,null,null,args.toArray(String[]::new)),"current projection and Detector delegates must compile");
        // Isolate boundary stubs from parent runtime classes; AssetKeys is the actual core output.
        return new URLClassLoader(new java.net.URL[]{output.toUri().toURL(),Path.of(core).toUri().toURL()},ClassLoader.getPlatformClassLoader());
    }

    @Test void currentDelegateCountsArmorOffhandAndDuplicatesWithoutSharingMaps() throws Exception {
        try(var loader=compile()) {
            var method=loader.loadClass("InventoryProjectionHost").getMethod("evaluate",String.class);
            assertEquals(Map.of("minecraft:stone",5,"minecraft:iron_helmet",1,"minecraft:shield",1),method.invoke(null,"allSlots"));
            assertEquals(Map.of(),method.invoke(null,"fresh"));
        }
    }
    @Test void potionAliasesKeepPhysicalIdsAndIsolateUnreadableComponents() throws Exception {
        try(var loader=compile()) {
            Object counts=loader.loadClass("InventoryProjectionHost").getMethod("evaluate",String.class).invoke(null,"potions");
            assertEquals(Map.of("minecraft:potion",4,"minecraft:splash_potion",3,"minecraft:lingering_potion",1,"minecraft:fire_resistance_potion",6,"minecraft:stone",4),counts);
        }
    }
    @Test void freeSlotsExcludeArmorOffhandAndClampShortInventories() throws Exception {
        try(var loader=compile()) {
            var method=loader.loadClass("InventoryProjectionHost").getMethod("evaluate",String.class);
            assertEquals(1,method.invoke(null,"free")); assertEquals(1,method.invoke(null,"short"));
            assertEquals(0,method.invoke(null,"empty")); assertEquals(0,method.invoke(null,"equipmentEmpty"));
            var error=assertThrows(java.lang.reflect.InvocationTargetException.class,()->method.invoke(null,"slotFailure"));
            assertInstanceOf(NullPointerException.class,error.getCause(),"do not convert inventory read failure to an empty success");
        }
    }
}

