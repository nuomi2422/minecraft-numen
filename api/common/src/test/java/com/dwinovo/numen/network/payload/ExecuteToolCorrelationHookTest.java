package com.dwinovo.numen.network.payload;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Compiles the complete production payload unchanged; only external boundaries are fakes.
 * Uses the runtime Gson library; Minecraft, logging and networking remain isolated fakes. */
class ExecuteToolCorrelationHookTest {
    @TempDir static Path dir;
    static URLClassLoader loader;
    static Class<?> fixture;
    @BeforeAll static void compile() throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while(root!=null && !Files.isDirectory(root.resolve("api/common/src/main/java"))) root=root.getParent();
        assertNotNull(root);
        Map<String,String> s=new LinkedHashMap<>();
        s.put("com.dwinovo.numen.network.payload.ExecuteToolPayload", Files.readString(root.resolve("api/common/src/main/java/com/dwinovo/numen/network/payload/ExecuteToolPayload.java")));
        s.put("com.dwinovo.numen.Constants", "package com.dwinovo.numen; public class Constants { public static final String MOD_ID=\"numen\"; public static final Log LOG=new Log(); public static class Log { public void debug(String s,Object... a) { com.dwinovo.numen.network.payload.Fixture.logs.add(java.util.Arrays.toString(a)); } public void warn(String s,Object... a){ com.dwinovo.numen.network.payload.Fixture.logs.add(java.util.Arrays.toString(a)); } } }");
        s.put("net.minecraft.resources.ResourceLocation", "package net.minecraft.resources; public class ResourceLocation { public static ResourceLocation fromNamespaceAndPath(String a,String b){return new ResourceLocation();} }");
        s.put("net.minecraft.network.protocol.common.custom.CustomPacketPayload", "package net.minecraft.network.protocol.common.custom; public interface CustomPacketPayload { record Type<T>(net.minecraft.resources.ResourceLocation id) {} Type<? extends CustomPacketPayload> type(); }");
        s.put("net.minecraft.network.RegistryFriendlyByteBuf", "package net.minecraft.network; public class RegistryFriendlyByteBuf {}");
        s.put("net.minecraft.network.codec.StreamCodec", "package net.minecraft.network.codec; import java.util.function.*; public class StreamCodec<B,T> { public interface Four<A,C,D,E,R>{R make(A a,C c,D d,E e);} public static <B,A,C,D,E,R> StreamCodec<B,R> composite(StreamCodec<B,A> a, Function<R,A> af,StreamCodec<B,C> c,Function<R,C> cf,StreamCodec<B,D> d,Function<R,D> df,StreamCodec<B,E> e,Function<R,E> ef,Four<A,C,D,E,R> f){return new StreamCodec<>();} }");
        s.put("net.minecraft.network.codec.ByteBufCodecs", "package net.minecraft.network.codec; public class ByteBufCodecs { public static StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf,String> stringUtf8(int n){return new StreamCodec<>();} }");
        s.put("net.minecraft.core.UUIDUtil", "package net.minecraft.core; public class UUIDUtil { public static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf,java.util.UUID> STREAM_CODEC=new net.minecraft.network.codec.StreamCodec<>(); }");
        s.put("net.minecraft.server.level.ServerPlayer", "package net.minecraft.server.level; public class ServerPlayer { public Name getName(){return new Name();} public java.util.UUID getUUID(){return new java.util.UUID(0,1);} public Level level(){return new Level();} public static class Name {public String getString(){return \"owner\";}} public static class Level {public Object getServer(){return new Object();}} }");
        s.put("com.dwinovo.numen.entity.NumenPlayer", "package com.dwinovo.numen.entity; public class NumenPlayer { public static NumenPlayer findByUuid(Object s,java.util.UUID u){return com.dwinovo.numen.network.payload.Fixture.mode.equals(\"unknown_entity\")?null:new NumenPlayer();} public java.util.UUID getOwnerUuid(){return new java.util.UUID(0,1);} public java.util.UUID getUUID(){return new java.util.UUID(0,2);} public boolean isOwnedByPlayer(java.util.UUID u){return !com.dwinovo.numen.network.payload.Fixture.mode.equals(\"non_owner\");} }");
        s.put("com.dwinovo.numen.entity.CompanionRegistry", "package com.dwinovo.numen.entity; public class CompanionRegistry {public static CompanionRegistry get(Object s){return new CompanionRegistry();} public Entry find(java.util.UUID u){return null;} public static class Entry {public long diedAt(){return 0;}} }");
        s.put("com.dwinovo.numen.entity.Companions", "package com.dwinovo.numen.entity; public class Companions {public static NumenPlayer respawn(Object s,java.util.UUID u){return null;}} ");
        s.put("com.dwinovo.numen.api.WorldContextStamp", "package com.dwinovo.numen.api; public class WorldContextStamp {public record Token(int epoch) {} public static Token capture(Object s){return new Token(com.dwinovo.numen.network.payload.Fixture.mode.equals(\"epoch\")?2:1);}} ");
        s.put("com.dwinovo.numen.api.SyncSkillCorrelation", Files.readString(root.resolve("api/common/src/main/java/com/dwinovo/numen/api/SyncSkillCorrelation.java")));
        s.put("com.dwinovo.numen.api.NumenPlugins", "package com.dwinovo.numen.api; public class NumenPlugins { public static <T> T capability(Class<T> c){return c.cast(com.dwinovo.numen.network.payload.Fixture.correlation);} }");
        s.put("com.dwinovo.numen.agent.tool.NumenTool", "package com.dwinovo.numen.agent.tool; public interface NumenTool {void onServerCall(String id,com.google.gson.JsonObject args,com.dwinovo.numen.entity.NumenPlayer p,java.util.function.Consumer<String> r);}");
        s.put("com.dwinovo.numen.agent.tool.OwnerAuthenticatedTool", Files.readString(root.resolve("api/common/src/main/java/com/dwinovo/numen/agent/tool/OwnerAuthenticatedTool.java")));
        s.put("com.dwinovo.numen.agent.tool.ToolRegistry", "package com.dwinovo.numen.agent.tool; public class ToolRegistry {public static NumenTool get(String n){return com.dwinovo.numen.network.payload.Fixture.mode.equals(\"unknown_tool\")?null:(id,a,p,r)->{com.dwinovo.numen.network.payload.Fixture.toolArgs.add(a.toString()); if(!com.dwinovo.numen.network.payload.Fixture.mode.equals(\"no_reply\")) r.accept(com.dwinovo.numen.network.payload.Fixture.response);};}} ");
        s.put("com.dwinovo.numen.agent.tool.ToolCallObserver", "package com.dwinovo.numen.agent.tool; public class ToolCallObserver {public static void fire(java.util.UUID u,String i,String n,int l){}} ");
        s.put("com.dwinovo.numen.agent.tool.ToolCallLoopWatch", "package com.dwinovo.numen.agent.tool; public class ToolCallLoopWatch {public static String onRejected(java.util.UUID u,String t,String m,String a){com.dwinovo.numen.network.payload.Fixture.loop.add(m+\"|\"+a);return null;} public static void onSucceeded(java.util.UUID u,String t){}} ");
        s.put("com.dwinovo.numen.task.TaskResult", "package com.dwinovo.numen.task; public record TaskResult(String message) {public static TaskResult fail(String s){return new TaskResult(s);} public String toJson(){return \"{success:false,message:\"+message+\"}\";}} ");
        s.put("com.dwinovo.numen.network.payload.TaskResultPayload", "package com.dwinovo.numen.network.payload; public record TaskResultPayload(java.util.UUID entityUuid,String toolCallId,String resultJson) {} ");
        s.put("com.dwinovo.numen.platform.Services", "package com.dwinovo.numen.platform; public class Services {public static final Network NETWORK=new Network(); public static class Network {public void sendToPlayer(net.minecraft.server.level.ServerPlayer p,com.dwinovo.numen.network.payload.TaskResultPayload v){com.dwinovo.numen.network.payload.Fixture.replies.add(v);}}} ");
        s.put("com.dwinovo.numen.monitor.MonitoringJournal", "package com.dwinovo.numen.monitor; public class MonitoringJournal {public static MonitoringJournal get(){return new MonitoringJournal();} public void publish(String c,String t,java.util.Map<String,Object> m){com.dwinovo.numen.network.payload.Fixture.journal.add(m.toString());} public void publish(String c,String t,java.util.Map<String,Object> m,com.dwinovo.numen.api.WorldContextStamp.Token token){publish(c,t,m);}} ");
        s.put("com.dwinovo.numen.network.payload.Fixture", """
                package com.dwinovo.numen.network.payload;
                import java.util.*;
                public class Fixture {
                  public static String mode; public static String response; public static final List<String> logs=new ArrayList<>(), loop=new ArrayList<>(), journal=new ArrayList<>();
                  public static final List<TaskResultPayload> replies=new ArrayList<>();
                  public static final List<String> toolArgs=new ArrayList<>(), receipts=new ArrayList<>(), binds=new ArrayList<>();
                  public static final com.dwinovo.numen.api.SyncSkillCorrelation correlation = new com.dwinovo.numen.api.SyncSkillCorrelation() {
                    public Binding bind(String id, UUID owner, UUID companion, com.dwinovo.numen.api.WorldContextStamp.Token token, String wire, String tool) {
                      binds.add(owner+"|"+companion+"|"+wire+"|"+tool+"|"+token.epoch());
                      if (!owner.equals(new UUID(0,1)) || !companion.equals(new UUID(0,2)) || token.epoch()!=1 || !tool.equals("tool")) return Binding.REJECTED;
                      return mode.equals("duplicate") ? Binding.DUPLICATE : Binding.BOUND;
                    }
                    public void receipt(String id, UUID owner, UUID companion, com.dwinovo.numen.api.WorldContextStamp.Token token, String wire, String tool, Receipt value) {receipts.add(value.name());}
                  };
                  public static Map<String,Object> run(String m,String args) { return runResponse(m,args,args); }
                  public static Map<String,Object> runResponse(String m,String args,String result) {
                    mode=m; response=result; logs.clear(); loop.clear(); journal.clear(); replies.clear(); toolArgs.clear(); receipts.clear(); binds.clear();
                    UUID id=new UUID(0,2); ExecuteToolPayload.handle(new ExecuteToolPayload(id,"call-1",m.equals("wrong_tool")?"other":"tool",args),new net.minecraft.server.level.ServerPlayer());
                    return Map.of("logs",List.copyOf(logs),"loop",List.copyOf(loop),"journal",List.copyOf(journal),"receipts",List.copyOf(receipts),"binds",List.copyOf(binds),"toolArgs",List.copyOf(toolArgs),"replies",replies.stream().map(r->r.entityUuid()+"|"+r.toolCallId()+"|"+r.resultJson()).toList());
                  }
                }
                """);
        URL gson = com.google.gson.JsonParser.class.getProtectionDomain().getCodeSource().getLocation();
        String classpath = dir + java.io.File.pathSeparator + Path.of(gson.toURI());
        List<String> args=new ArrayList<>(List.of("-encoding","UTF-8","-classpath",classpath,"-d",dir.toString()));
        for(var e:s.entrySet()){Path f=dir.resolve(e.getKey().replace('.','/')+".java");Files.createDirectories(f.getParent());Files.writeString(f,e.getValue());args.add(f.toString());}
        assertNotNull(ToolProvider.getSystemJavaCompiler());
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)),"complete production class must compile");
        loader=new URLClassLoader(new URL[]{dir.toUri().toURL(),gson},ClassLoader.getPlatformClassLoader());
        fixture=loader.loadClass("com.dwinovo.numen.network.payload.Fixture");
    }
    @AfterAll static void close() throws Exception {if(loader!=null) loader.close();}
    @SuppressWarnings("unchecked") static Map<String,Object> run(String mode,String args) throws Exception {return (Map<String,Object>)fixture.getMethod("run",String.class,String.class).invoke(null,mode,args);}
    @SuppressWarnings("unchecked") static Map<String,Object> response(String mode,String args,String result) throws Exception {return (Map<String,Object>)fixture.getMethod("runResponse",String.class,String.class,String.class).invoke(null,mode,args,result);}
    @Test void scopedDispatchAndOriginalReply() throws Exception {
        String args="{\"selection_id\":\"secret\",\"block\":\"stone\"}";
        for(String mode:List.of("success","wrong_tool","epoch","duplicate")) {
            String result="{\"success\":true}";
            var r=response(mode,args,result);
            assertEquals(List.of("{\"block\":\"stone\"}"),r.get("toolArgs"));
            assertEquals(mode.equals("success")?List.of("SUCCESS"):List.of(),r.get("receipts"),mode);
            assertEquals(1,((List<?>)r.get("binds")).size());
            assertTrue(r.get("replies").toString().contains(result));
            for(String key:List.of("logs","loop","journal","toolArgs")) assertFalse(r.get(key).toString().contains("secret"));
        }
    }
    @Test void unknownAsyncAndMissingReply() throws Exception {
        String args="{\"selection_id\":\"secret\"}";
        var async=response("success",args,"{\"success\":true,\"data\":{\"async\":true}}");
        assertEquals(List.of("UNKNOWN"),async.get("receipts"));
        assertEquals(List.of("UNKNOWN"),run("no_reply",args).get("receipts"));
    }
    @Test void ownerAndOrdinaryCall() throws Exception {
        var denied=run("non_owner","{\"selection_id\":\"secret\"}");
        assertEquals(List.of(),denied.get("binds")); assertEquals(List.of(),denied.get("toolArgs"));
        var ordinary=response("success","{\"block\":\"stone\"}","{\"success\":true}");
        assertEquals(List.of(),ordinary.get("binds")); assertEquals(List.of(),ordinary.get("receipts"));
        assertEquals(List.of("{\"block\":\"stone\"}"),ordinary.get("toolArgs"));
    }
}
