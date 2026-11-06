package com.dwinovo.numen.entity;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Compile full production sampler + watcher against counted detached host boundaries (2026-11-06).
 * WHY: same-tick reuse must be measured, mutable copies must not leak back into frames, and the
 * automatic SELF sampling segment must keep its own cadence independent of the owner/packet logic.
 * USAGE: api:common:test --tests '*CompanionBodySampleTest'; no running Minecraft.
 * Tests identity/lifecycle/slot-read counts and event firing, not sampling performance, not the
 * 10-tick candidate's real-world cost, and not the world-state store wiring (that is a plugin test).
 */
class CompanionBodySampleTest {
    @TempDir static Path build;
    private static URLClassLoader loader;
    private static Class<?> bodyType, sampleType, watchType, servicesType, eventsType, eventType;

    @BeforeAll static void compileProductionSourcesAgainstStubs() throws Exception {
        Map<String,String> src=new LinkedHashMap<>();
        src.put("net.minecraft.world.item.ItemStack","""
          package net.minecraft.world.item;
          public class ItemStack { public static final Item ITEM=new Item(); public Item getItem(){return ITEM;} public int count=1; public int getCount(){return count;} public boolean isEmpty(){return false;} public Name getHoverName(){return new Name();} public static class Name {public String getString(){return "item";}} public int damage; public String component="custom-name";
          public ItemStack copy(){ItemStack c=new ItemStack();c.damage=damage;c.component=component;c.count=count;return c;} }
          """);
        src.put("net.minecraft.world.item.Item","package net.minecraft.world.item; public class Item {}");
        src.put("net.minecraft.world.effect.MobEffectInstance","""
          package net.minecraft.world.effect;
          public class MobEffectInstance {public int getAmplifier(){return 0;}public Effect getEffect(){return new Effect();} public static class Effect {public java.util.Optional<Key> unwrapKey(){return java.util.Optional.of(new Key());}} public static class Key {public String location(){return "minecraft:speed";}} public int duration=200;public MobEffectInstance(){} public MobEffectInstance(MobEffectInstance e){duration=e.duration;} }
          """);
        src.put("com.dwinovo.numen.api.CompanionEvent","""
          package com.dwinovo.numen.api;
          public class CompanionEvent<T> {public static final CompanionEvent<com.dwinovo.numen.entity.NumenPlayer> REMOVE=new CompanionEvent<>(),DEATH=new CompanionEvent<>(),SELF_SAMPLE_READY=new CompanionEvent<>();}
          """);
        src.put("com.dwinovo.numen.entity.CompanionEvents","""
          package com.dwinovo.numen.entity;
          public class CompanionEvents {
          public static final java.util.Map<Object,java.util.List<java.util.function.Consumer<NumenPlayer>>> buckets=new java.util.HashMap<>();
          public static int ready;
          public static void subscribe(com.dwinovo.numen.api.CompanionEvent<NumenPlayer> e,java.util.function.Consumer<NumenPlayer> h){buckets.computeIfAbsent(e,k->new java.util.ArrayList<>()).add(h);}
          // 真实总线逐个监听者兜底(CompanionEvents.fire)；这里故意不兜，用来验证 Watch 自己那层兜底真的在。
          public static void fire(com.dwinovo.numen.api.CompanionEvent<NumenPlayer> e,NumenPlayer p){
            if(e==com.dwinovo.numen.api.CompanionEvent.SELF_SAMPLE_READY)ready++;
            java.util.List<java.util.function.Consumer<NumenPlayer>> list=buckets.get(e); if(list==null)return;
            for(var h:list)h.accept(p);}
          }
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
          public boolean dead,removed;
          public java.util.UUID uuid=java.util.UUID.randomUUID(); public net.minecraft.world.effect.MobEffectInstance effect=new net.minecraft.world.effect.MobEffectInstance();
          public Server getServer(){return server;}public NumenPlayer serverLevel(){return this;}public NumenPlayer level(){return this;}
          public NumenPlayer dimension(){return this;}public String location(){return dim;} public long getGameTime(){return 1000;}
          public java.util.UUID getUUID(){return uuid;} public Inventory getInventory(){return inv;}
          public boolean isDeadOrDying(){return dead;} public boolean isRemoved(){return removed;}
          public java.util.List<net.minecraft.world.effect.MobEffectInstance> getActiveEffects(){return java.util.List.of(effect);}
          public NumenPlayer getVehicle(){return null;}public Object getType(){return this;}public int getId(){return 1;}
          public float getHealth(){return 20;}public float getMaxHealth(){return 20;}public NumenPlayer getFoodData(){return this;}
          public int getFoodLevel(){return 20;}public float getSaturationLevel(){return 5;}
          }
          """);
        src.put("net.minecraft.server.level.ServerPlayer","package net.minecraft.server.level; public class ServerPlayer {}");
        src.put("com.dwinovo.numen.Constants","package com.dwinovo.numen; public class Constants {public static final Log LOG=new Log();public static class Log {public void info(String s,Object...a){}public void warn(String s,Object...a){}}}");
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
        List<String> args=new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-d",build.toString()));
        for(var e:src.entrySet()){Path p=build.resolve(e.getKey().replace('.','/')+".java");Files.createDirectories(p.getParent());Files.writeString(p,e.getValue());args.add(p.toString());}
        Path root=Path.of("").toAbsolutePath();while(!Files.exists(root.resolve("api/common/src/main/java/com/dwinovo/numen/entity/CompanionBodySample.java")))root=root.getParent();
        args.add(root.resolve("api/common/src/main/java/com/dwinovo/numen/entity/CompanionBodySample.java").toString());
        args.add(root.resolve("api/common/src/main/java/com/dwinovo/numen/entity/CompanionStateWatch.java").toString());
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
        loader=new URLClassLoader(new URL[]{build.toUri().toURL()},ClassLoader.getPlatformClassLoader());
        bodyType=loader.loadClass("com.dwinovo.numen.entity.NumenPlayer");
        sampleType=loader.loadClass("com.dwinovo.numen.entity.CompanionBodySample");
        watchType=loader.loadClass("com.dwinovo.numen.entity.CompanionStateWatch");
        servicesType=loader.loadClass("com.dwinovo.numen.platform.Services");
        eventsType=loader.loadClass("com.dwinovo.numen.entity.CompanionEvents");
        eventType=loader.loadClass("com.dwinovo.numen.api.CompanionEvent");
    }

    @AfterAll static void closeLoader() throws Exception { if (loader != null) loader.close(); }

    /** Watch 的镜像表是世界作用域的；每个用例从零开始，免得上一个用例的身份残留。 */
    @BeforeEach void resetWatchTables() throws Exception {
        watchType.getMethod("dropAll").invoke(null);
        // READY 计数与发包计数在替身里都是静态的：不清零会让后一个用例读到前一个用例的次数。
        eventsType.getField("ready").setInt(null, 0);
        Object network = servicesType.getField("NETWORK").get(null);
        network.getClass().getField("sends").setInt(network, 0);
        var latest = sampleType.getDeclaredField("LATEST");
        latest.setAccessible(true);
        ((Map<?,?>) latest.get(null)).clear();
    }

    private static Object player() throws Exception { return bodyType.getConstructor().newInstance(); }
    private static Object field(Object target, String name) throws Exception { return target.getClass().getField(name).get(target); }
    private static void set(Object target, String name, Object value) throws Exception { target.getClass().getField(name).set(target, value); }
    private static int reads(Object p) throws Exception { return ((Number) field(field(p,"inv"),"reads")).intValue(); }
    private static Object stack(Object p) throws Exception { return field(field(p,"inv"),"stack"); }
    private static int stackCount(Object p) throws Exception { return ((Number) stack(p).getClass().getField("count").get(stack(p))).intValue(); }
    private static void setStackCount(Object p, int count) throws Exception { stack(p).getClass().getField("count").setInt(stack(p), count); }
    private static int sends() throws Exception {
        Object network = servicesType.getField("NETWORK").get(null);
        return network.getClass().getField("sends").getInt(network);
    }
    private static int readyEvents() throws Exception { return eventsType.getField("ready").getInt(null); }
    private static Object newOwner() throws Exception {
        return loader.loadClass("net.minecraft.server.level.ServerPlayer").getConstructor().newInstance();
    }
    /** 生产里 Watch 收到的 tick 与 capture 读到的 server tick 是同一个数；替身把两者对齐。 */
    private static void tick(Object p, long serverTick) throws Exception {
        Object server = field(p, "server");
        server.getClass().getField("tick").setInt(server, (int) serverTick);
        watchType.getMethod("tick", bodyType, long.class).invoke(null, p, serverTick);
    }
    @SuppressWarnings("unchecked")
    private static List<java.util.function.Consumer<Object>> handlers(String eventField) throws Exception {
        Object event = eventType.getField(eventField).get(null);
        Map<Object,List<java.util.function.Consumer<Object>>> buckets =
                (Map<Object,List<java.util.function.Consumer<Object>>>) eventsType.getField("buckets").get(null);
        return buckets.computeIfAbsent(event, k -> new ArrayList<>());
    }
    private static Object watchOf(Object p) throws Exception {
        var watches=watchType.getDeclaredField("WATCHES");watches.setAccessible(true);
        return ((Map<?,?>)watches.get(null)).get(field(p,"uuid"));
    }

    @Test void oneScanPerIdentityTickCopiesComponentsAndClearsLifecycle() throws Exception {
        Object p=player(), inv=field(p,"inv"), server=field(p,"server");
        tick(p,1L);
        assertEquals(41,reads(p),"第一次采样必须读满 41 格");
        assertEquals(1,sends(),"主人第一次在线要收到一份快照");
        assertEquals(1,readyEvents());
        Object first=sampleType.getMethod("capture",bodyType).invoke(null,p);
        assertSame(first,sampleType.getMethod("capture",bodyType).invoke(null,p),"同 tick 复用同一帧");
        assertEquals(41,reads(p),"同 tick 再取不许多读");
        tick(p,2L);
        assertEquals(41,reads(p),"没到 10 tick:采样与推送都不许再读");
        assertEquals(1,sends());
        // 离线仍然采样(这一段与发包解耦),但包一个都不发。
        set(p,"owner",null);
        tick(p,20L);
        assertEquals(82,reads(p),"主人离线:照样按节拍采样");
        assertEquals(2,readyEvents());
        assertEquals(1,sends(),"主人离线:一个包都不发");
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
        Object second=sampleType.getMethod("capture",bodyType).invoke(null,p);assertNotSame(first,second);
        set(p,"dim","minecraft:the_end");assertNotSame(second,sampleType.getMethod("capture",bodyType).invoke(null,p));
        Object replacement=bodyType.getConstructor().newInstance();set(replacement,"uuid",field(p,"uuid"));
        assertNotSame(sampleType.getMethod("capture",bodyType).invoke(null,p),sampleType.getMethod("capture",bodyType).invoke(null,replacement));
        Object nextServer=server.getClass().getConstructor().newInstance();set(p,"server",nextServer);
        Object beforeStop=sampleType.getMethod("capture",bodyType).invoke(null,p);
        ((Runnable)loader.loadClass("com.dwinovo.numen.platform.ServerLifecycle").getField("stopped").get(null)).run();
        assertNotSame(beforeStop,sampleType.getMethod("capture",bodyType).invoke(null,p));
        for(var callback:handlers("REMOVE")){Object before=sampleType.getMethod("capture",bodyType).invoke(null,p);callback.accept(p);assertNotSame(before,sampleType.getMethod("capture",bodyType).invoke(null,p));}
        for(var callback:handlers("DEATH")){Object before=sampleType.getMethod("capture",bodyType).invoke(null,p);callback.accept(p);assertNotSame(before,sampleType.getMethod("capture",bodyType).invoke(null,p));}
        nextServer.getClass().getField("thread").setBoolean(nextServer,false);
        var offThread=assertThrows(java.lang.reflect.InvocationTargetException.class,()->sampleType.getMethod("capture",bodyType).invoke(null,p));
        assertInstanceOf(IllegalStateException.class,offThread.getCause());
        nextServer.getClass().getField("thread").setBoolean(nextServer,true);
        var latest=sampleType.getDeclaredField("LATEST");latest.setAccessible(true);
        Object entry=((Map<?,?>)latest.get(null)).values().iterator().next();
        var weak=entry.getClass().getDeclaredMethod("body");weak.setAccessible(true);
        assertInstanceOf(java.lang.ref.WeakReference.class,weak.invoke(entry));
        for(var f:first.getClass().getDeclaredFields())assertFalse(f.getType().equals(bodyType)||f.getType().equals(server.getClass()));
        sampleType.getMethod("forget",java.util.UUID.class).invoke(null,field(p,"uuid"));
        assertTrue(((Map<?,?>)latest.get(null)).isEmpty());
    }

    @Test void ownerOfflineSamplesEveryTenTicksSendsNothingAndCatchesUpOnReturn() throws Exception {
        Object p=player();
        set(p,"owner",null);
        tick(p,0L);
        assertEquals(41,reads(p)); assertEquals(1,readyEvents()); assertEquals(0,sends());
        tick(p,9L);
        assertEquals(41,reads(p),"9 tick 还不到节拍:一次都不许采");
        assertEquals(1,readyEvents());
        tick(p,10L);
        assertEquals(82,reads(p)); assertEquals(2,readyEvents());
        tick(p,20L);
        assertEquals(123,reads(p)); assertEquals(3,readyEvents()); assertEquals(0,sends(),"离线三次采样,零包");
        // 镜像没被离线采样推着走:整个离线段 changedSince 一次都没跑(lastItem 还是空数组)。
        Object watch=watchOf(p);
        var lastItem=watch.getClass().getDeclaredField("lastItem");lastItem.setAccessible(true);
        assertEquals(0,((Object[])lastItem.get(watch)).length,"离线期间不许推进已发送镜像");
        var lastSentTick=watch.getClass().getDeclaredField("lastSentTick");lastSentTick.setAccessible(true);
        assertEquals(0L,lastSentTick.getLong(watch),"离线期间不许推进发包节拍");
        // 主人回来:离线时攒下的变化一次补发(不是离线期间每拍一包)。
        set(p,"owner",newOwner());
        setStackCount(p,2);
        tick(p,30L);
        assertEquals(1,sends(),"主人回来补发,一次");
        assertEquals(2,stackCount(p));
    }

    @Test void sameTickConsumersShareOneScanAndTheSamplerKeepsItsOwnCadence() throws Exception {
        Object p=player();
        set(p,"owner",null);
        tick(p,0L);
        assertEquals(41,reads(p));
        // 同 tick 的消费者(请求快照 / 插件 refresh 都会走 capture)不许再读 41 格。
        Class<?> requestState=loader.loadClass("com.dwinovo.numen.network.payload.RequestStatePayload");
        requestState.getMethod("snapshot",bodyType).invoke(null,p);
        sampleType.getMethod("capture",bodyType).invoke(null,p);
        assertEquals(41,reads(p),"同 tick 的一次采样只允许一次 41 格读");
        tick(p,10L);
        assertEquals(82,reads(p),"tick 变了:采样必须重新读");
        assertEquals(2,readyEvents());
    }

    @Test void failureDeathDimensionAndBodyChangeResetTheCadence() throws Exception {
        Object p=player(), server=field(p,"server");
        set(p,"owner",null);
        tick(p,0L);
        assertEquals(41,reads(p)); assertEquals(1,readyEvents());
        // 失败(capture 抛错)只能发生在"到点那一拍";失败不许被记成"已经采过"。
        server.getClass().getField("thread").setBoolean(server,false);
        assertDoesNotThrow(()->tick(p,10L));
        assertEquals(41,reads(p),"失败不读格子");
        assertEquals(1,readyEvents(),"失败不发 READY");
        server.getClass().getField("thread").setBoolean(server,true);
        tick(p,11L);
        assertEquals(82,reads(p),"失败后只隔 1 tick 就重采(节拍被重置)");
        assertEquals(2,readyEvents());
        // 死亡:不采,且身份作废——活过来第一 tick 立刻重采。
        set(p,"dead",true);
        tick(p,12L);
        assertEquals(82,reads(p),"死了不许采");
        assertEquals(2,readyEvents(),"死了不许发 READY");
        set(p,"dead",false);
        tick(p,13L);
        assertEquals(123,reads(p),"死亡重置了节拍:活过来立刻重采");
        assertEquals(3,readyEvents());
        // 换维度:立刻重采,节拍从新维度那一拍重新数。
        set(p,"dim","minecraft:the_end");
        tick(p,14L);
        assertEquals(164,reads(p),"换维度立刻重采");
        assertEquals(4,readyEvents());
        tick(p,23L);
        assertEquals(164,reads(p),"新维度节拍 14→23 还差 1 tick");
        tick(p,24L);
        assertEquals(205,reads(p));
        assertEquals(5,readyEvents());
        // 换身体(同 UUID):身份变了就立刻重采,不接着数旧身体的节拍。
        Object next=player(); set(next,"uuid",field(p,"uuid")); set(next,"owner",null);
        tick(next,25L);
        assertEquals(41,reads(next),"新身体第一 tick 就采");
        assertEquals(6,readyEvents());
    }

    @Test void listenerFailureDoesNotBreakTheBodyTick() throws Exception {
        Object p=player();
        set(p,"owner",null);
        List<java.util.function.Consumer<Object>> readyHandlers=handlers("SELF_SAMPLE_READY");
        java.util.function.Consumer<Object> broken=companion -> {throw new IllegalStateException("listener boom");};
        readyHandlers.add(broken);
        try {
            // 真实总线逐个监听者兜底;这里替身故意不兜,验证 Watch 自己那层兜底能让身体这一 tick 走完。
            assertDoesNotThrow(()->tick(p,0L));
            assertEquals(41,reads(p));
            assertEquals(1,readyEvents(),"事件确实发过");
            assertDoesNotThrow(()->tick(p,10L));
            assertEquals(82,reads(p),"下一拍照常采样:监听者写坏不影响节拍");
        } finally {
            readyHandlers.remove(broken);
        }
        // 发包段也没有被带坏:主人回来照样补发。
        set(p,"owner",newOwner());
        tick(p,20L);
        assertEquals(1,sends());
    }
}
