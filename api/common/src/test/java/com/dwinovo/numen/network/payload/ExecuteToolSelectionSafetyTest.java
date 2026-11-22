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
class ExecuteToolSelectionSafetyTest {
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
        s.put("com.dwinovo.numen.entity.NumenPlayer", "package com.dwinovo.numen.entity; public class NumenPlayer { public static NumenPlayer findByUuid(Object s,java.util.UUID u){return com.dwinovo.numen.network.payload.Fixture.mode.equals(\"unknown_entity\")?null:new NumenPlayer();} public boolean isOwnedByPlayer(java.util.UUID u){return !com.dwinovo.numen.network.payload.Fixture.mode.equals(\"non_owner\");} public java.util.UUID getOwnerUuid(){return new java.util.UUID(0,1);} public java.util.UUID getUUID(){return new java.util.UUID(0,2);} }");
        s.put("com.dwinovo.numen.entity.CompanionRegistry", "package com.dwinovo.numen.entity; public class CompanionRegistry {public static CompanionRegistry get(Object s){return new CompanionRegistry();} public Entry find(java.util.UUID u){return null;} public static class Entry {public long diedAt(){return 0;}} }");
        s.put("com.dwinovo.numen.entity.Companions", "package com.dwinovo.numen.entity; public class Companions {public static NumenPlayer respawn(Object s,java.util.UUID u){return null;}} ");
        s.put("com.dwinovo.numen.api.WorldContextStamp", "package com.dwinovo.numen.api; public class WorldContextStamp {public static class Token {} public static Token capture(Object s){return new Token();}} ");
        s.put("com.dwinovo.numen.api.SyncSkillCorrelation", Files.readString(root.resolve("api/common/src/main/java/com/dwinovo/numen/api/SyncSkillCorrelation.java")));
        s.put("com.dwinovo.numen.api.NumenPlugins", "package com.dwinovo.numen.api; public class NumenPlugins {public static <T> T capability(Class<T> c){return c.cast(com.dwinovo.numen.network.payload.Fixture.correlation);}} ");
        s.put("com.dwinovo.numen.agent.tool.NumenTool", "package com.dwinovo.numen.agent.tool; public interface NumenTool {void onServerCall(String id,com.google.gson.JsonObject args,com.dwinovo.numen.entity.NumenPlayer p,java.util.function.Consumer<String> r);}");
        s.put("com.dwinovo.numen.agent.tool.ToolRegistry", "package com.dwinovo.numen.agent.tool; public class ToolRegistry {public static NumenTool get(String n){return com.dwinovo.numen.network.payload.Fixture.mode.equals(\"unknown_tool\")?null:(id,a,p,r)->{com.dwinovo.numen.network.payload.Fixture.actions.add(a.toString()); if(!com.dwinovo.numen.network.payload.Fixture.mode.equals(\"late\")) r.accept(com.dwinovo.numen.network.payload.Fixture.response);};}} ");
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
                  public static final List<String> actions=new ArrayList<>(), binds=new ArrayList<>(), receipts=new ArrayList<>();
                  public static final com.dwinovo.numen.api.SyncSkillCorrelation correlation=new com.dwinovo.numen.api.SyncSkillCorrelation() {
                    public Binding bind(String s,UUID o,UUID c,com.dwinovo.numen.api.WorldContextStamp.Token t,String id,String tool) {
                      binds.add(s+"|"+o+"|"+c+"|"+(t!=null)+"|"+id+"|"+tool);
                      return mode.equals("duplicate")?Binding.DUPLICATE:mode.equals("wrong_tool")?Binding.REJECTED:Binding.BOUND;
                    }
                    public void receipt(String s,UUID o,UUID c,com.dwinovo.numen.api.WorldContextStamp.Token t,String id,String tool,Receipt r) {receipts.add(s+"|"+o+"|"+c+"|"+(t!=null)+"|"+id+"|"+tool+"|"+r);}
                  };
                  public static Map<String,Object> run(String m,String args) {
                    mode=m; response=args; logs.clear(); loop.clear(); journal.clear(); replies.clear(); actions.clear(); binds.clear(); receipts.clear();
                    if(m.equals("bound") || m.equals("wrong_tool") || m.equals("duplicate") || m.equals("ordinary")) response="{\\"success\\":true}";
                    if(m.equals("async")) response="{\\"success\\":true,\\"data\\":{\\"async\\":true}}";
                    UUID id=new UUID(0,2); ExecuteToolPayload.handle(new ExecuteToolPayload(id,"call-1","tool",args),new net.minecraft.server.level.ServerPlayer());
                    return Map.of("logs",List.copyOf(logs),"loop",List.copyOf(loop),"journal",List.copyOf(journal),"replies",replies.stream().map(r->r.entityUuid()+"|"+r.toolCallId()+"|"+r.resultJson()).toList(),"actions",List.copyOf(actions),"binds",List.copyOf(binds),"receipts",List.copyOf(receipts));
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
    static void check(String mode,String args) throws Exception {
        var r=run(mode,args); var replies=(List<?>)r.get("replies");
        assertEquals(1,replies.size(),"rejection must be delivered");
        assertTrue(replies.get(0).toString().contains("call-1|{success:false"));
        if (mode.equals("bad_json")) assertTrue(replies.toString().contains("invalid arguments JSON"), "real Gson must take parse-error branch");
        List<String> leaks=new ArrayList<>();
        for(String surface:List.of("logs","loop","journal","replies"))
            if(r.get(surface).toString().contains("secret-selection-42")) leaks.add(surface);
        assertTrue(leaks.isEmpty(),"selection_id leaked in "+leaks);
    }
    @Test void unknownEntity() throws Exception {check("unknown_entity","{\"selection_id\":\"secret-selection-42\"}");}
    @Test void nonOwner() throws Exception {check("non_owner","{\"selection_id\":\"secret-selection-42\"}");}
    @Test void unknownTool() throws Exception {check("unknown_tool","{\"selection_id\":\"secret-selection-42\"}");}
    @Test void malformedJson() throws Exception {check("bad_json","{\"selection_id\":\"secret-selection-42\"");}
    @Test void successfulReplyKeepsSelectionForOwnerButNotJournal() throws Exception {
        String json="{\"selection_id\":\"secret-selection-42\"}";
        var r=run("success",json);
        assertEquals(1,((List<?>)r.get("replies")).size());
        assertTrue(r.get("replies").toString().contains(json),"owner must receive original result");
        assertFalse(r.get("journal").toString().contains("secret-selection-42"),"success journal leaks selection_id");
    }
    @Test void ordinarySuccessReplyUnchanged() throws Exception {
        String json="{\"block\":\"stone\"}"; var r=run("success",json);
        assertTrue(r.get("replies").toString().contains(json));
        assertTrue(r.get("journal").toString().contains(json));
    }
    @Test void realHandleBindsAndReceiptsSuccess() throws Exception {
        var r=run("bound", "{\"selection_id\":\"sel\",\"block\":\"stone\"}");
        String key="sel|00000000-0000-0000-0000-000000000001|00000000-0000-0000-0000-000000000002|true|call-1|tool";
        assertEquals(List.of(key),r.get("binds")); assertEquals(List.of(key+"|SUCCESS"),r.get("receipts"));
        assertEquals(List.of("{\"block\":\"stone\"}"),r.get("actions")); assertTrue(r.get("replies").toString().contains("{\"success\":true}"));
    }
    @Test void asyncAcceptanceReceiptsUnknown() throws Exception {
        var r=run("async", "{\"selection_id\":\"sel\"}");
        assertEquals(1,((List<?>)r.get("binds")).size()); assertTrue(r.get("receipts").toString().endsWith("|UNKNOWN]"));
        assertEquals(List.of("{}"),r.get("actions")); assertTrue(r.get("replies").toString().contains("async"));
    }
    @Test void wrongOwnerNeverBindsOrDispatches() throws Exception {
        var r=run("non_owner", "{\"selection_id\":\"sel\"}");
        assertEquals(List.of(),r.get("binds")); assertEquals(List.of(),r.get("receipts")); assertEquals(List.of(),r.get("actions")); assertTrue(r.get("replies").toString().contains("not the owner"));
    }
    @Test void wrongToolCorrelationDoesNotBlockAction() throws Exception { checkUnboundAction("wrong_tool"); }
    @Test void duplicateCorrelationDoesNotBlockAction() throws Exception { checkUnboundAction("duplicate"); }
    static void checkUnboundAction(String mode) throws Exception {
        var r=run(mode,"{\"selection_id\":\"sel\",\"block\":\"stone\"}");
        assertEquals(1,((List<?>)r.get("binds")).size()); assertEquals(List.of(),r.get("receipts"));
        assertEquals(List.of("{\"block\":\"stone\"}"),r.get("actions")); assertTrue(r.get("replies").toString().contains("{\"success\":true}"));
    }
    @Test void noSelectionDispatchesNormallyWithoutCorrelation() throws Exception {
        var r=run("ordinary","{\"block\":\"stone\"}");
        assertEquals(List.of(),r.get("binds")); assertEquals(List.of(),r.get("receipts"));
        assertEquals(List.of("{\"block\":\"stone\"}"),r.get("actions")); assertTrue(r.get("replies").toString().contains("{\"success\":true}"));
    }
    @Test void ordinaryArgumentsRemainDiagnostic() throws Exception {
        var r=run("unknown_tool","{\"block\":\"stone\"}");
        assertEquals(1,((List<?>)r.get("replies")).size());
        assertTrue(r.get("logs").toString().contains("stone"));
        assertTrue(r.get("loop").toString().contains("stone"));
    }
}
