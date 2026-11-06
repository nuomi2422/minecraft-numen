package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-07：隔离编译真实 FarmHarvestSupport.give + MC 林形 stub + 同 loader driver，
 * 用结构化字符串避免跨 loader 类型签名混用。覆盖全接收/部分/零/空；异常另列不冒充。
 */
class FarmHarvestGiveBehaviourTest {
    @TempDir Path temp;

    @Test void realGiveThroughDriver() throws Exception {
        ClassLoader iso = compileAll();
        Class<?> driver = iso.loadClass("harness.Driver");
        Method run = driver.getMethod("run", String.class, int.class);

        String full = (String) run.invoke(null, "full", 3);
        assertEquals("1|3|minecraft:wheat|0|0|3", full, "full actual=" + full);

        String partial = (String) run.invoke(null, "partial", 3);
        assertEquals("1|2|minecraft:wheat|1|1|2", partial, "partial actual=" + partial);

        String zero = (String) run.invoke(null, "zero", 3);
        assertEquals("0|0|null|1|3|0", zero, "zero actual=" + zero);

        String empty = (String) run.invoke(null, "full", 0);
        assertEquals("0|0|null|0|0|0", empty, "empty actual=" + empty);
    }

    private ClassLoader compileAll() throws Exception {
        Path src = temp.resolve("src"), out = temp.resolve("out");
        Files.createDirectories(src); Files.createDirectories(out);
        write(src, "net/minecraft/world/item/Item.java", "package net.minecraft.world.item; public class Item { public String key; public Item(){} public Item(String k){key=k;} }");
        write(src, "net/minecraft/world/item/ItemStack.java", "package net.minecraft.world.item; public class ItemStack { public int count; public String key; public Item item; public ItemStack(){} public ItemStack(int c,String k){count=c;key=k;item=new Item(k);} public boolean isEmpty(){return count<=0;} public int getCount(){return count;} public Item getItem(){return item;} }");
        write(src, "net/minecraft/world/entity/player/Inventory.java", "package net.minecraft.world.entity.player; public interface Inventory { boolean add(net.minecraft.world.item.ItemStack stack); }");
        write(src, "net/minecraft/world/entity/player/Player.java", "package net.minecraft.world.entity.player; public abstract class Player { public abstract Inventory getInventory(); public abstract void drop(net.minecraft.world.item.ItemStack stack, boolean b); }");
        write(src, "net/minecraft/stats/Stats.java", "package net.minecraft.stats; import net.minecraft.world.item.Item; public class Stats { public static class Stat<T>{ public T value; public Stat(T v){value=v;} } public static class StatType<T>{ public Stat<T> get(T t){return new Stat<T>(t);} } public static StatType<Item> ITEM_PICKED_UP = new StatType<Item>(); }");
        write(src, "net/minecraft/server/level/ServerPlayer.java", "package net.minecraft.server.level; import net.minecraft.world.entity.player.*; import net.minecraft.world.item.*; import net.minecraft.stats.Stats; import java.util.*; public class ServerPlayer extends Player { public final List<Object> awards=new ArrayList<>(); public final List<ItemStack> dropped=new ArrayList<>(); public int lastAwardCount; public String lastAwardItem; public int lastDroppedCount; private final String mode; public ServerPlayer(String m){mode=m;} public Inventory getInventory(){ return stack -> { if(\"throw\".equals(mode)) throw new RuntimeException(\"boom\"); if(\"zero\".equals(mode)) return false; if(\"partial\".equals(mode)){ stack.count=1; return false; } stack.count=0; return true; }; } public void awardStat(Stats.Stat<?> s,int n){ awards.add(s); lastAwardCount=n; Object v=s.value; if(v instanceof Item it) lastAwardItem=it.key; } public void drop(ItemStack s, boolean b){ dropped.add(s); lastDroppedCount=s.getCount(); } }");
        write(src, "harness/Driver.java", "package harness;\nimport net.minecraft.server.level.ServerPlayer;\nimport net.minecraft.world.item.ItemStack;\npublic class Driver {\n  public static String run(String mode, int count) throws Exception {\n    ItemStack s = new ItemStack(count, \"minecraft:wheat\");\n    ServerPlayer p = new ServerPlayer(mode);\n    java.lang.reflect.Method m = Class.forName(\"com.dwinovo.numen.plugins.rdd.FarmHarvestSupport\").getDeclaredMethod(\"give\", ServerPlayer.class, ItemStack.class);\n    m.setAccessible(true);\n    int acc = (Integer) m.invoke(null, p, s);\n    return p.awards.size() + \"|\" + p.lastAwardCount + \"|\" + p.lastAwardItem + \"|\" + p.dropped.size() + \"|\" + p.lastDroppedCount + \"|\" + acc;\n  }\n}");
        Path prod = Path.of("src/main/java/com/dwinovo/numen/plugins/rdd/FarmHarvestSupport.java");
        Files.copy(prod, src.resolve("FarmHarvestSupport.java"));
        List<String> args = new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-d",out.toString()));
        try (var walk = Files.walk(src)) { for (Path p : (Iterable<Path>) walk.filter(x -> x.toString().endsWith(".java"))::iterator) args.add(p.toString()); }
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        assertEquals(0, compiler.run(null, null, null, args.toArray(String[]::new)), "stub+production 编译必须通过");
        return new URLClassLoader(new URL[]{out.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    }

    private static void write(Path src, String rel, String body) throws Exception {
        Path p = src.resolve(rel); Files.createDirectories(p.getParent()); Files.writeString(p, body, StandardCharsets.UTF_8);
    }
}
