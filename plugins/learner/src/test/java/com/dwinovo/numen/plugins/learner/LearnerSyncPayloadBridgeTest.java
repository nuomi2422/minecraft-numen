package com.dwinovo.numen.plugins.learner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** INCOMPLETE compilation probe only, not a selection-to-receipt bridge test. */
class LearnerSyncPayloadBridgeTest {
    @TempDir Path dir;
    @Test void incompletePayloadCompilationProbe() throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while(root!=null && !Files.isDirectory(root.resolve("api/common/src/main/java"))) root=root.getParent();
        assertNotNull(root);
        Map<String,String> sources=new LinkedHashMap<>();
        sources.put("com.dwinovo.numen.Constants", "package com.dwinovo.numen; public class Constants { public static final String MOD_ID=\"numen\"; public static final Log LOG=new Log(); public static class Log { public void debug(String s,Object... a) { com.dwinovo.numen.network.payload.Fixture.logs.add(java.util.Arrays.toString(a)); } public void warn(String s,Object... a){ com.dwinovo.numen.network.payload.Fixture.logs.add(java.util.Arrays.toString(a)); } } }");
        sources.put("net.minecraft.resources.ResourceLocation", "package net.minecraft.resources; public class ResourceLocation { public static ResourceLocation fromNamespaceAndPath(String a,String b){return new ResourceLocation();} }");
        sources.put("net.minecraft.network.protocol.common.custom.CustomPacketPayload", "package net.minecraft.network.protocol.common.custom; public interface CustomPacketPayload { record Type<T>(net.minecraft.resources.ResourceLocation id) {} Type<? extends CustomPacketPayload> type(); }");
        sources.put("net.minecraft.network.RegistryFriendlyByteBuf", "package net.minecraft.network; public class RegistryFriendlyByteBuf {}");
        sources.put("net.minecraft.network.codec.StreamCodec", "package net.minecraft.network.codec; import java.util.function.*; public class StreamCodec<B,T> { public interface Four<A,C,D,E,R>{R make(A a,C c,D d,E e);} public static <B,A,C,D,E,R> StreamCodec<B,R> composite(StreamCodec<B,A> a, Function<R,A> af,StreamCodec<B,C> c,Function<R,C> cf,StreamCodec<B,D> d,Function<R,D> df,StreamCodec<B,E> e,Function<R,E> ef,Four<A,C,D,E,R> f){return new StreamCodec<>();} }");
        sources.put("net.minecraft.network.codec.ByteBufCodecs", "package net.minecraft.network.codec; public class ByteBufCodecs { public static StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf,String> stringUtf8(int n){return new StreamCodec<>();} }");
        sources.put("net.minecraft.core.UUIDUtil", "package net.minecraft.core; public class UUIDUtil { public static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf,java.util.UUID> STREAM_CODEC=new net.minecraft.network.codec.StreamCodec<>(); }");
        sources.put("net.minecraft.server.level.ServerPlayer", "package net.minecraft.server.level; public class ServerPlayer { public Name getName(){return new Name();} public java.util.UUID getUUID(){return new java.util.UUID(0,1);} public Level level(){return new Level();} public static class Name {public String getString(){return \"owner\";}} public static class Level {public net.minecraft.server.MinecraftServer getServer(){return new net.minecraft.server.MinecraftServer();}} }");
        sources.put("com.dwinovo.numen.entity.NumenPlayer", "package com.dwinovo.numen.entity; public class NumenPlayer { public static NumenPlayer findByUuid(Object s,java.util.UUID u){return com.dwinovo.numen.network.payload.Fixture.mode.equals(\"unknown_entity\")?null:new NumenPlayer();} public java.util.UUID getOwnerUuid(){return new java.util.UUID(0,1);} public java.util.UUID getUUID(){return new java.util.UUID(0,2);} public boolean isOwnedByPlayer(java.util.UUID u){return !com.dwinovo.numen.network.payload.Fixture.mode.equals(\"non_owner\");} }");
        sources.put("com.dwinovo.numen.entity.CompanionRegistry", "package com.dwinovo.numen.entity; public class CompanionRegistry {public static CompanionRegistry get(Object s){return new CompanionRegistry();} public Entry find(java.util.UUID u){return null;} public static class Entry {public long diedAt(){return 0;}} }");
        sources.put("com.dwinovo.numen.entity.Companions", "package com.dwinovo.numen.entity; public class Companions {public static NumenPlayer respawn(Object s,java.util.UUID u){return null;}} ");
        sources.put("com.dwinovo.numen.agent.tool.ToolRegistry", "package com.dwinovo.numen.agent.tool; public class ToolRegistry {public static NumenTool get(String n){return null;}}");
        sources.put("com.dwinovo.numen.agent.tool.ToolCallObserver", "package com.dwinovo.numen.agent.tool; public class ToolCallObserver {public static void fire(java.util.UUID u,String i,String n,int l){}} ");
        sources.put("com.dwinovo.numen.agent.tool.ToolCallLoopWatch", "package com.dwinovo.numen.agent.tool; public class ToolCallLoopWatch {public static String onRejected(java.util.UUID u,String t,String m,String a){com.dwinovo.numen.network.payload.Fixture.loop.add(m+\"|\"+a);return null;} public static void onSucceeded(java.util.UUID u,String t){}} ");
        sources.put("com.dwinovo.numen.network.payload.TaskResultPayload", "package com.dwinovo.numen.network.payload; public record TaskResultPayload(java.util.UUID entityUuid,String toolCallId,String resultJson) {} ");
        sources.put("com.dwinovo.numen.platform.Services", "package com.dwinovo.numen.platform; public class Services {public static final Network NETWORK=new Network(); public static class Network {public void sendToPlayer(net.minecraft.server.level.ServerPlayer p,com.dwinovo.numen.network.payload.TaskResultPayload v){com.dwinovo.numen.network.payload.Fixture.replies.add(v);}}} ");
        sources.put("com.dwinovo.numen.monitor.MonitoringJournal", "package com.dwinovo.numen.monitor; public class MonitoringJournal {public static MonitoringJournal get(){return new MonitoringJournal();} public void publish(String c,String t,java.util.Map<String,Object> m){com.dwinovo.numen.network.payload.Fixture.journal.add(m.toString());} public void publish(String c,String t,java.util.Map<String,Object> m,com.dwinovo.numen.api.WorldContextStamp.Token token){publish(c,t,m);}} ");
        sources.put("net.minecraft.server.MinecraftServer", "package net.minecraft.server; public class MinecraftServer {}");
        sources.put("com.dwinovo.numen.network.payload.Fixture", "package com.dwinovo.numen.network.payload; public class Fixture { public static String mode=\"\"; public static java.util.List<String> logs=new java.util.ArrayList<>(),loop=new java.util.ArrayList<>(),journal=new java.util.ArrayList<>(),toolArgs=new java.util.ArrayList<>(); public static String response=\"{}\"; public static java.util.List<TaskResultPayload> replies=new java.util.ArrayList<>(); }");
        sources.put("com.dwinovo.numen.network.payload.ExecuteToolPayload", Files.readString(root.resolve("api/common/src/main/java/com/dwinovo/numen/network/payload/ExecuteToolPayload.java")));
        var cp = new LinkedHashSet<String>();
        for (Class<?> c : List.of(LearnerUsageTool.class, com.dwinovo.numen.api.WorldContextStamp.class, com.dwinovo.numen.agent.provider.IToolSpec.class, com.google.gson.JsonObject.class))
            cp.add(Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        var args=new ArrayList<String>(List.of("-encoding","UTF-8","-classpath",String.join(java.io.File.pathSeparator, cp),"-d",dir.toString()));
        for(var e:sources.entrySet()){Path f=dir.resolve(e.getKey().replace('.','/')+".java");Files.createDirectories(f.getParent());Files.writeString(f,e.getValue());args.add(f.toString());}
        assertNotNull(ToolProvider.getSystemJavaCompiler());
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)),"complete unchanged payload must compile");
        fail("INCOMPLETE: no same-loader selection, dispatch or ledger verification yet; ToolRegistry stub returns null");
    }
}
