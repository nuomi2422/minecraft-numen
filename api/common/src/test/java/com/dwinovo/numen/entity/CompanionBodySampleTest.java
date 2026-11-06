package com.dwinovo.numen.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Compile full production sampler against counted detached host boundaries (2026-11-06).
 * WHY: same-tick reuse must be measured, and mutable copies must not leak back into frames.
 * USAGE: api:common:test --tests '*CompanionBodySampleTest'; no running Minecraft.
 * Tests identity/lifecycle/slot reads, not sampling performance or automatic store wiring.
 */
class CompanionBodySampleTest {
    @TempDir Path temp;
    @Test void oneScanPerIdentityTickCopiesComponentsAndClearsLifecycle() throws Exception {
        Map<String,String> src=new LinkedHashMap<>();
        src.put("net.minecraft.world.item.ItemStack","""
          package net.minecraft.world.item;
          public class ItemStack { public static final Item ITEM=new Item(); public Item getItem(){return ITEM;} public int getCount(){return 1;} public boolean isEmpty(){return false;} public Name getHoverName(){return new Name();} public static class Name {public String getString(){return "item";}} public int damage; public String component="custom-name";
          public ItemStack copy(){ItemStack c=new ItemStack();c.damage=damage;c.component=component;return c;} }
          """);
        src.put("net.minecraft.world.item.Item","package net.minecraft.world.item; public class Item {}");
        src.put("net.minecraft.world.effect.MobEffectInstance","""
          package net.minecraft.world.effect;
          public class MobEffectInstance {public int getAmplifier(){return 0;}public Effect getEffect(){return new Effect();} public static class Effect {public java.util.Optional<Key> unwrapKey(){return java.util.Optional.of(new Key());}} public static class Key {public String location(){return "minecraft:speed";}} public int duration=200;public MobEffectInstance(){} public MobEffectInstance(MobEffectInstance e){duration=e.duration;} }
          """);
        src.put("com.dwinovo.numen.api.CompanionEvent","""
          package com.dwinovo.numen.api;
          public class CompanionEvent<T> {public static final CompanionEvent<com.dwinovo.numen.entity.NumenPlayer> REMOVE=new CompanionEvent<>(),DEATH=new CompanionEvent<>();}
          """);
        src.put("com.dwinovo.numen.entity.CompanionEvents","""
          package com.dwinovo.numen.entity;
          public class CompanionEvents {public static final java.util.List<java.util.function.Consumer<NumenPlayer>> handlers=new java.util.ArrayList<>();
          public static void subscribe(com.dwinovo.numen.api.CompanionEvent<NumenPlayer> e,java.util.function.Consumer<NumenPlayer> h){handlers.add(h);} }
          """);
        src.put("com.dwinovo.numen.platform.ServerLifecycle","""
          package com.dwinovo.numen.platform; public class ServerLifecycle {public static Runnable stopped;public static void onStopped(Runnable r){stopped=r;} }
          """);
        src.put("com.dwinovo.numen.task.TaskRecord","""
          package com.dwinovo.numen.task; public class TaskRecord {public static final long NO_DEADLINE=9999;public long getStartedGameTime(){return 0;}
          public String publicId(){return "t1";}public String getToolName(){return "follow";} public String describe(){return "follow";}public long getDeadlineGameTime(){return 9999;} }
          """);
        src.put("com.dwinovo.numen.task.CompanionTickDispatcher","""
          package com.dwinovo.numen.task; public class CompanionTickDispatcher {public static TaskRecord currentTaskFor(java.util.UUID id){return null;} }
          """);
        src.put("net.minecraft.core.registries.BuiltInRegistries","""
          package net.minecraft.core.registries; public class BuiltInRegistries {public static final BuiltInRegistries ENTITY_TYPE=new BuiltInRegistries(),ITEM=new BuiltInRegistries();public Key getKey(Object t){return new Key();}public static class Key {public String toString(){return "minecraft:boat";}public String getPath(){return "boat";}} }
          """);
        src.put("com.dwinovo.numen.entity.NumenPlayer","""
          package com.dwinovo.numen.entity;
          public class NumenPlayer {
          public static class Server {public int tick=1; public boolean thread=true; public boolean isSameThread(){return thread;}public int getTickCount(){return tick;}}
          public static class Inventory {public int reads,selected; public net.minecraft.world.item.ItemStack stack=new net.minecraft.world.item.ItemStack();
          public net.minecraft.world.item.ItemStack getItem(int i){reads++;return stack;}}
          public net.minecraft.server.level.ServerPlayer owner=new net.minecraft.server.level.ServerPlayer(); public net.minecraft.server.level.ServerPlayer resolveOwnerPlayer(){return owner;} public net.minecraft.world.item.ItemStack.Name getName(){return new net.minecraft.world.item.ItemStack.Name();}
          public Server server=new Server(); public Inventory inv=new Inventory(); public String dim="minecraft:overworld";
          public java.util.UUID uuid=java.util.UUID.randomUUID(); public net.minecraft.world.effect.MobEffectInstance effect=new net.minecraft.world.effect.MobEffectInstance();
          public Server getServer(){return server;}public NumenPlayer serverLevel(){return this;}public NumenPlayer level(){return this;}
          public NumenPlayer dimension(){return this;}public String location(){return dim;} public long getGameTime(){return 1000;}
          public java.util.UUID getUUID(){return uuid;} public Inventory getInventory(){return inv;}
          public java.util.List<net.minecraft.world.effect.MobEffectInstance> getActiveEffects(){return java.util.List.of(effect);}
          public NumenPlayer getVehicle(){return null;}public Object getType(){return this;}public int getId(){return 1;}
          public float getHealth(){return 20;}public float getMaxHealth(){return 20;}public NumenPlayer getFoodData(){return this;}
          public int getFoodLevel(){return 20;}public float getSaturationLevel(){return 5;}
          }
          """);
        src.put("net.minecraft.server.level.ServerPlayer","package net.minecraft.server.level; public class ServerPlayer {}");
        src.put("com.dwinovo.numen.Constants","package com.dwinovo.numen; public class Constants {public static final Log LOG=new Log();public static class Log {public void info(String s,Object...a){}}}");
        src.put("com.dwinovo.numen.monitor.MonitoringJournal","package com.dwinovo.numen.monitor; public class MonitoringJournal {public static MonitoringJournal get(){return new MonitoringJournal();} public void publish(String a,String b,java.util.Map<String,Object> m){} }");
        src.put("com.dwinovo.numen.platform.Services","package com.dwinovo.numen.platform;public class Services {public static final Network NETWORK=new Network();public static class Network {public int sends;public void sendToPlayer(Object owner,Object p){sends++;}}}");
        src.put("com.dwinovo.numen.network.payload.NumenStatePayload","""
          package com.dwinovo.numen.network.payload;
          public record NumenStatePayload(java.util.List<net.minecraft.world.item.ItemStack> items,int selectedSlot,net.minecraft.world.item.ItemStack offhand,
              java.util.List<net.minecraft.world.effect.MobEffectInstance> effects,String vehicleType,int vehicleId){}
          """);
        src.put("com.dwinovo.numen.network.payload.RequestStatePayload","""
          package com.dwinovo.numen.network.payload; public class RequestStatePayload {
          public static NumenStatePayload snapshot(com.dwinovo.numen.entity.NumenPlayer p){var f=com.dwinovo.numen.entity.CompanionBodySample.capture(p);
          return new NumenStatePayload(f.main(),f.selected(),f.slot(40),f.effects(),f.vehicleType(),f.vehicleId());}}
          """);
        List<String> args=new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-d",temp.toString()));
        for(var e:src.entrySet()){Path p=temp.resolve(e.getKey().replace('.','/')+".java");Files.createDirectories(p.getParent());Files.writeString(p,e.getValue());args.add(p.toString());}
        Path root=Path.of("").toAbsolutePath();while(!Files.exists(root.resolve("api/common/src/main/java/com/dwinovo/numen/entity/CompanionBodySample.java")))root=root.getParent();
        args.add(root.resolve("api/common/src/main/java/com/dwinovo/numen/entity/CompanionBodySample.java").toString());
        args.add(root.resolve("api/common/src/main/java/com/dwinovo/numen/entity/CompanionStateWatch.java").toString());
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
        try(URLClassLoader loader=new URLClassLoader(new URL[]{temp.toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
            Class<?> body=loader.loadClass("com.dwinovo.numen.entity.NumenPlayer"), sample=loader.loadClass("com.dwinovo.numen.entity.CompanionBodySample");
            Object p=body.getConstructor().newInstance(), inv=body.getField("inv").get(p), server=body.getField("server").get(p);
            var capture=sample.getMethod("capture",body);
            Class<?> watch=loader.loadClass("com.dwinovo.numen.entity.CompanionStateWatch");
            var watchTick=watch.getMethod("tick",body,long.class);
            watchTick.invoke(null,p,1L);
            Object first=capture.invoke(null,p);assertSame(first,capture.invoke(null,p));
            watchTick.invoke(null,p,2L);
            Object network=loader.loadClass("com.dwinovo.numen.platform.Services").getField("NETWORK").get(null);
            assertEquals(1,network.getClass().getField("sends").getInt(network));
            // Owner-offline behavior remains unchanged in this slice: Watch does not auto-sample.
            body.getField("owner").set(p,null);watchTick.invoke(null,p,20L);
            assertEquals(41,inv.getClass().getField("reads").getInt(inv));
            assertEquals(41,inv.getClass().getField("reads").getInt(inv));
            var slot=first.getClass().getMethod("slot",int.class);
            Object copy=slot.invoke(first,0);copy.getClass().getField("damage").setInt(copy,9);
            assertEquals(0,copy.getClass().getField("damage").getInt(slot.invoke(first,0)));
            assertEquals("custom-name",copy.getClass().getField("component").get(slot.invoke(first,0)));
            Object live=inv.getClass().getField("stack").get(inv);
            live.getClass().getField("component").set(live,"changed-after-capture");
            assertEquals("custom-name",copy.getClass().getField("component").get(slot.invoke(first,0)));
            var effects=first.getClass().getMethod("effects");
            Object returnedEffect=((List<?>)effects.invoke(first)).getFirst();
            returnedEffect.getClass().getField("duration").setInt(returnedEffect,1);
            assertEquals(200,returnedEffect.getClass().getField("duration").getInt(((List<?>)effects.invoke(first)).getFirst()));
            server.getClass().getField("tick").setInt(server,2);
            Object second=capture.invoke(null,p);assertNotSame(first,second);assertEquals(82,inv.getClass().getField("reads").getInt(inv));
            body.getField("dim").set(p,"minecraft:the_end");assertNotSame(second,capture.invoke(null,p));
            Object replacement=body.getConstructor().newInstance();body.getField("uuid").set(replacement,body.getField("uuid").get(p));
            assertNotSame(capture.invoke(null,p),capture.invoke(null,replacement));
            Object nextServer=server.getClass().getConstructor().newInstance();body.getField("server").set(p,nextServer);
            Object beforeStop=capture.invoke(null,p);
            ((Runnable)loader.loadClass("com.dwinovo.numen.platform.ServerLifecycle").getField("stopped").get(null)).run();
            assertNotSame(beforeStop,capture.invoke(null,p));
            @SuppressWarnings("unchecked") var callbacks=(List<java.util.function.Consumer<Object>>)loader.loadClass("com.dwinovo.numen.entity.CompanionEvents").getField("handlers").get(null);
            for(var callback:callbacks){Object before=capture.invoke(null,p);callback.accept(p);assertNotSame(before,capture.invoke(null,p));}
            nextServer.getClass().getField("thread").setBoolean(nextServer,false);
            var offThread=assertThrows(java.lang.reflect.InvocationTargetException.class,()->capture.invoke(null,p));
            assertInstanceOf(IllegalStateException.class,offThread.getCause());
            nextServer.getClass().getField("thread").setBoolean(nextServer,true);
            var latest=sample.getDeclaredField("LATEST");latest.setAccessible(true);
            Object entry=((Map<?,?>)latest.get(null)).values().iterator().next();
            var weak=entry.getClass().getDeclaredMethod("body");weak.setAccessible(true);
            assertInstanceOf(java.lang.ref.WeakReference.class,weak.invoke(entry));
            for(var field:first.getClass().getDeclaredFields())assertFalse(field.getType().equals(body)||field.getType().equals(server.getClass()));
            sample.getMethod("forget",java.util.UUID.class).invoke(null,body.getField("uuid").get(p));
            assertTrue(((Map<?,?>)latest.get(null)).isEmpty());
        }
    }
}
