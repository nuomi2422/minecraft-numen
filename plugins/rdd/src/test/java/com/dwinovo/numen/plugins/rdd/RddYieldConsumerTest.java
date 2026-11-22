package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.*;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.File;
import java.net.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Compiles the complete production tool with fake Minecraft/monitor boundaries and real TaskChain. */
class RddYieldConsumerTest {
 @TempDir static Path tmp;
 static ClassLoader loader;
 static Class<?> harness;
 @BeforeAll static void compile() throws Exception {
  Path root=Path.of("").toAbsolutePath();
  while(root!=null&&!Files.isDirectory(root.resolve("plugins/rdd/src/main/java"))) root=root.getParent();
  assertNotNull(root);
  var files=new LinkedHashMap<String,String>();
  files.put("com/dwinovo/numen/plugins/rdd/RddReportYieldTool.java",Files.readString(root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddReportYieldTool.java")));
  files.put("com/dwinovo/numen/agent/tool/NumenTool.java","package com.dwinovo.numen.agent.tool; import java.util.*; import java.util.function.*; import com.google.gson.JsonObject; import com.dwinovo.numen.entity.NumenPlayer; public interface NumenTool {String name();String description();Map<String,Object> parameterSchema();void onServerCall(String id,JsonObject a,NumenPlayer p,Consumer<String> reply);}");
  files.put("com/dwinovo/numen/agent/tool/Schema.java","package com.dwinovo.numen.agent.tool; import java.util.*; public class Schema { public static Schema object(){return new Schema();} public Schema string(String a,String b){return this;} public Schema integer(String a,String b,int c,int d){return this;} public Map<String,Object> build(){return Map.of();} }");
  files.put("net/minecraft/server/MinecraftServer.java","package net.minecraft.server; public class MinecraftServer { public boolean isSameThread(){return true;} }");
  files.put("net/minecraft/server/level/ServerPlayer.java","package net.minecraft.server.level; import java.util.*; public class ServerPlayer {public UUID id; public ServerPlayer(UUID id){this.id=id;} public UUID getUUID(){return id;} }");
  files.put("com/dwinovo/numen/entity/NumenPlayer.java","package com.dwinovo.numen.entity; import java.util.*; import net.minecraft.server.MinecraftServer; public class NumenPlayer { public UUID getUUID(){return new UUID(0,1);} public boolean isOwnedByPlayer(UUID u){return new UUID(0,2).equals(u);} public MinecraftServer getServer(){return new MinecraftServer();} }");
  files.put("com/dwinovo/numen/api/ServerIdentityProvider.java","package com.dwinovo.numen.api; import net.minecraft.server.MinecraftServer; public interface ServerIdentityProvider {record Snapshot(String worldId,String serverSession){} Snapshot snapshot(MinecraftServer s);}");
  files.put("com/dwinovo/numen/api/NumenPlugins.java","package com.dwinovo.numen.api; public class NumenPlugins {public static <T> T capability(Class<T> c){return c.cast((ServerIdentityProvider)(s->new ServerIdentityProvider.Snapshot(\"world\",\"server\")));}}");
  files.put("com/dwinovo/numen/agent/tool/OwnerAuthenticatedTool.java",Files.readString(root.resolve("api/common/src/main/java/com/dwinovo/numen/agent/tool/OwnerAuthenticatedTool.java")));
  files.put("com/dwinovo/numen/agent/tool/TrustedYieldTool.java",Files.readString(root.resolve("api/common/src/main/java/com/dwinovo/numen/agent/tool/TrustedYieldTool.java")));
  files.put("com/dwinovo/numen/plugins/rdd/RddBodyDispatcher.java",Files.readString(root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddBodyDispatcher.java")));
  files.put("org/slf4j/Logger.java","package org.slf4j; public interface Logger {default void error(String s,Object...a){} default void info(String s,Object...a){} default void warn(String s,Object...a){}}");
  files.put("org/slf4j/LoggerFactory.java","package org.slf4j; public class LoggerFactory {public static Logger getLogger(Class<?> c){return new Logger(){};}}");
  files.put("com/dwinovo/numen/plugins/rdd/RddBodyTools.java","package com.dwinovo.numen.plugins.rdd; import java.util.*;import com.google.gson.*; class RddBodyTools {static final List<String> SUPPORTED=List.of();static String canonical(String s){return null;}static JsonObject buildArgs(String s,Map<String,Object> a,Integer n){return null;}}");
  files.put("com/dwinovo/numen/task/TaskResult.java","package com.dwinovo.numen.task; import java.util.*; import com.google.gson.Gson; public record TaskResult(boolean success,String message,Map<String,Object> data) { public static TaskResult fail(String m){return new TaskResult(false,m,Map.of());} public static TaskResult ok(String m,Map<String,Object>d){return new TaskResult(true,m,d);} public String toJson(){return new Gson().toJson(this);} }");
  String plugin=Files.readString(root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddPlugin.java"));
  int ps=plugin.indexOf("    private static com.dwinovo.numen.rdd.core.YieldOwnerBinding yieldTickets");
  int pe=plugin.indexOf("    /** Disk handoffs survive",ps); assertTrue(ps>=0 && pe>ps);
  files.put("com/dwinovo/numen/plugins/rdd/RddPlugin.java","package com.dwinovo.numen.plugins.rdd; import java.util.*;import com.dwinovo.numen.rdd.core.*; import com.dwinovo.numen.entity.NumenPlayer; import net.minecraft.server.level.ServerPlayer; public class RddPlugin {public static RddRuntime rt; static record BodyState(String subtaskId,int submitCount,TaskChain yieldChain,Object binding,String result){} static BodyState body; static BodyState bodyState(UUID id){return body;} static void rememberBody(UUID id,String sub,int n){body=new BodyState(sub,n,null,null,\"\");} static void rememberYieldBody(UUID id,String sub,TaskChain chain,Object b,String r){body=new BodyState(sub,1,chain,b,r==null?\"UNKNOWN\":r);} static String nextBodyCallId(){return \"body\";} static RddRuntime runtime(UUID id){return rt;} static void saveRuntimes(){} static void publishTaskSnapshot(UUID id,String why){} "+plugin.substring(ps,pe)+" }");
  String host=Files.readString(root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/SideTaskHost.java"));
  String signature="    static SideTask activeYieldOwner(UUID companionId) {";
  int at=host.indexOf(signature); assertTrue(at>=0, "production owner method missing");
  int end=host.indexOf("\n    }", at); assertTrue(end>at);
  String realMethod=host.substring(at,end+6);
  files.put("com/dwinovo/numen/plugins/rdd/SideTaskHost.java", "package com.dwinovo.numen.plugins.rdd; import java.util.*; import com.dwinovo.numen.rdd.side.*; class SideTaskHost { static final SideTaskEngine ENGINE=new SideTaskEngine(null,null); "+realMethod+" }");
  String payload=Files.readString(root.resolve("api/common/src/main/java/com/dwinovo/numen/network/payload/ExecuteToolPayload.java"));
  int hs=payload.indexOf("    private static void handleCompanion("); int he=payload.indexOf("\n    /**",hs); assertTrue(hs>=0&&he>hs);
  String handle=payload.substring(hs,he).replace("private static void handleCompanion", "public static void handleCompanion");
  int vs=payload.indexOf("    static boolean validYieldTicketReplyId("); int ve=payload.indexOf("    static String diagnosticArgs(",vs); assertTrue(vs>=0&&ve>vs);
  handle+=payload.substring(vs,ve);
  files.put("com/dwinovo/numen/network/payload/ExecuteToolPayload.java", "package com.dwinovo.numen.network.payload; import java.util.*; import com.google.gson.*; import net.minecraft.server.level.ServerPlayer; import com.dwinovo.numen.agent.tool.*; import com.dwinovo.numen.api.WorldContextStamp; import com.dwinovo.numen.monitor.MonitoringJournal; public record ExecuteToolPayload(UUID entityUuid,String toolCallId,String toolName,String argumentsJson){ "+handle+" static void replyError(ServerPlayer p,ExecuteToolPayload q,String s){ com.dwinovo.numen.platform.Services.NETWORK.result=s;} static String truncate(String s){return s;} }");
  files.put("com/dwinovo/numen/api/WorldContextStamp.java","package com.dwinovo.numen.api; public class WorldContextStamp {public static class Token{}}");
  files.put("com/dwinovo/numen/monitor/MonitoringJournal.java","package com.dwinovo.numen.monitor; public class MonitoringJournal {public static int count; public static MonitoringJournal get(){return new MonitoringJournal();} public void publish(Object... a){count++;}}");
  files.put("com/dwinovo/numen/agent/tool/ToolRegistry.java","package com.dwinovo.numen.agent.tool; public class ToolRegistry { public static NumenTool tool; public static Runnable before=()->{}; public static NumenTool get(String n){return tool;} public static NumenTool resolve(String n){before.run();return tool;} }");
  files.put("com/dwinovo/numen/agent/tool/ToolCallLoopWatch.java","package com.dwinovo.numen.agent.tool; public class ToolCallLoopWatch {public static void onSucceeded(Object... a){}}");
  files.put("com/dwinovo/numen/network/payload/TaskResultPayload.java","package com.dwinovo.numen.network.payload; import java.util.*; public record TaskResultPayload(UUID id,String call,String json){}");
  files.put("com/dwinovo/numen/platform/Services.java","package com.dwinovo.numen.platform; import com.dwinovo.numen.network.payload.TaskResultPayload; public class Services {public static final Net NETWORK=new Net(); public static class Net {public String result; public void sendToPlayer(Object p,TaskResultPayload r){result=r.json();}}}");
  files.put("com/dwinovo/numen/plugins/rdd/RddMonitor.java","package com.dwinovo.numen.plugins.rdd; import java.util.*; class RddMonitor {static void publish(String s,Map<String,Object> d){} }");
  files.put("com/dwinovo/numen/plugins/rdd/YieldHarness.java","""
package com.dwinovo.numen.plugins.rdd; import com.google.gson.*;import com.dwinovo.numen.rdd.core.*;import com.dwinovo.numen.entity.NumenPlayer; import net.minecraft.server.level.ServerPlayer; import java.util.*; public class YieldHarness { public static String issueBadId(RddRuntime rt){RddPlugin.rt=rt;com.dwinovo.numen.agent.tool.ToolRegistry.tool=new RddReportYieldTool();var p=new com.dwinovo.numen.network.payload.ExecuteToolPayload(body.getUUID(),"ordinary-wire-id","_rdd_yield_ticket","{}");com.dwinovo.numen.network.payload.ExecuteToolPayload.handleCompanion(p,player(true),body,null);return com.dwinovo.numen.platform.Services.NETWORK.result;} public static String issueNetwork(RddRuntime rt,boolean owner){RddPlugin.rt=rt;com.dwinovo.numen.agent.tool.ToolRegistry.tool=new RddReportYieldTool();com.dwinovo.numen.monitor.MonitoringJournal.count=0;var p=new com.dwinovo.numen.network.payload.ExecuteToolPayload(body.getUUID(),"yield-ticket-00000000-0000-0000-0000-000000000001","_rdd_yield_ticket","{}");com.dwinovo.numen.network.payload.ExecuteToolPayload.handleCompanion(p,player(owner),body,null);return com.dwinovo.numen.platform.Services.NETWORK.result;} public static int journalCount(){return com.dwinovo.numen.monitor.MonitoringJournal.count;} static class UnknownYield implements com.dwinovo.numen.agent.tool.NumenTool,com.dwinovo.numen.agent.tool.TrustedYieldTool {int calls;public String name(){return "rdd_report_yield";}public String description(){return "";}public Map<String,Object> parameterSchema(){return Map.of();}public Object captureHostBinding(NumenPlayer p){return RddPlugin.yieldIdentity(p);}public void onServerCall(String i,JsonObject a,NumenPlayer p,java.util.function.Consumer<String> r){throw new AssertionError("untrusted entry");}public void onTrustedHostCall(String i,JsonObject a,NumenPlayer p,Object b,java.util.function.Consumer<String> r){calls++;}} public static boolean unknownBody(RddRuntime rt){RddPlugin.body=null;RddPlugin.rt=rt;var tool=new UnknownYield();com.dwinovo.numen.agent.tool.ToolRegistry.tool=tool;var chain=rt.chain();var current=chain.currentSubtask();Object expected=RddPlugin.yieldIdentity(body);RddBodyDispatcher.maybeSubmit(body,current,chain);var first=RddPlugin.body;RddBodyDispatcher.maybeSubmit(body,current,chain);return first!=null && first.binding().equals(expected) && "UNKNOWN".equals(first.result()) && first==RddPlugin.body && tool.calls==1 && chain.currentMinimum("s")==1;} public static boolean staleAttempt(RddRuntime rt){RddPlugin.body=null;RddPlugin.rt=rt;com.dwinovo.numen.agent.tool.ToolRegistry.tool=new RddReportYieldTool();var chain=rt.chain();var old=chain.currentSubtask();com.dwinovo.numen.agent.tool.ToolRegistry.before=()->{chain.markFailed("s","fixture");chain.retrySubtask("s");chain.startCurrent();};try{RddBodyDispatcher.maybeSubmit(body,old,chain);return RddPlugin.body==null;}finally{com.dwinovo.numen.agent.tool.ToolRegistry.before=()->{};}} public static String validBody(RddRuntime rt){RddPlugin.body=null;RddPlugin.rt=rt;com.dwinovo.numen.agent.tool.ToolRegistry.tool=new RddReportYieldTool();RddBodyDispatcher.maybeSubmit(body,rt.chain().currentSubtask(),rt.chain());return RddPlugin.body==null?"missing":RddPlugin.body.result();} public static boolean staleBody(RddRuntime oldRt,RddRuntime newRt){RddPlugin.body=null;RddPlugin.rt=newRt;com.dwinovo.numen.agent.tool.ToolRegistry.tool=new RddReportYieldTool();RddBodyDispatcher.maybeSubmit(body,oldRt.chain().currentSubtask(),oldRt.chain());return RddPlugin.body==null;} public static String network(RddRuntime rt,String ticket,boolean owner,boolean other){RddPlugin.rt=rt; com.dwinovo.numen.agent.tool.ToolRegistry.tool=other?new com.dwinovo.numen.agent.tool.NumenTool(){public String name(){return "other";}public String description(){return "";}public Map<String,Object> parameterSchema(){return Map.of();}public void onServerCall(String i,JsonObject a,NumenPlayer b,java.util.function.Consumer<String> r){r.accept("OTHER_OK");}}:new RddReportYieldTool(); JsonObject a=new JsonObject();a.addProperty("expected_subtask_id","s");a.addProperty("asset_key","minecraft:iron_pickaxe");a.addProperty("observed",9);a.addProperty("take",9);a.addProperty("_yield_ticket",ticket); var p=new com.dwinovo.numen.network.payload.ExecuteToolPayload(body.getUUID(),"wire",other?"other":"rdd_report_yield",a.toString());com.dwinovo.numen.network.payload.ExecuteToolPayload.handleCompanion(p,player(owner),body,null);return com.dwinovo.numen.platform.Services.NETWORK.result;} public static void clearSide(){SideTaskHost.ENGINE.runtime().remove(new UUID(0,1));} static final NumenPlayer body=new NumenPlayer(); static ServerPlayer player(boolean owner){return new ServerPlayer(new UUID(0,owner?2:3));} public static String issue(RddRuntime rt){RddPlugin.rt=rt;return RddPlugin.issueYieldTicket(player(true),body,System.currentTimeMillis());} public static String call(RddRuntime rt,String id,String asset,int observed,int take,String ticket,boolean owner,boolean direct){RddPlugin.rt=rt;SideTaskHost.ENGINE.runtime().remove(new UUID(0,1)); if(asset.startsWith("SIDE:")){SideTaskHost.ENGINE.runtime().activate(new com.dwinovo.numen.rdd.side.SideTask(new UUID(0,1),"side-42","sleep",42,0,0,100,null,"",Map.of(),null));asset=asset.substring(5);}JsonObject a=new JsonObject();a.addProperty("expected_subtask_id",id);a.addProperty("asset_key",asset);a.addProperty("observed",observed);a.addProperty("take",take);a.addProperty("_yield_ticket",ticket);String[] result={null};if(direct)new RddReportYieldTool().onServerCall("wire",a,body,v->result[0]=v);else new RddReportYieldTool().onOwnerCall("wire",a,body,player(owner),v->result[0]=v);return result[0];} }
""");
  var args=new ArrayList<String>(List.of("-encoding","UTF-8","-proc:none","-classpath",System.getProperty("java.class.path")+File.pathSeparator+Path.of(TaskChain.class.getProtectionDomain().getCodeSource().getLocation().toURI())+File.pathSeparator+Path.of(Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI()),"-d",tmp.toString()));
  for(var e:files.entrySet()){Path p=tmp.resolve(e.getKey());Files.createDirectories(p.getParent());Files.writeString(p,e.getValue(),StandardCharsets.UTF_8);args.add(p.toString());}
  assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
  loader=new URLClassLoader(new URL[]{tmp.toUri().toURL()},RddYieldConsumerTest.class.getClassLoader()) {
   @Override protected synchronized Class<?> loadClass(String n, boolean resolve) throws ClassNotFoundException {
    if(n.startsWith("com.dwinovo.numen.plugins.rdd.") || n.startsWith("com.dwinovo.numen.network.payload.") || n.startsWith("com.dwinovo.numen.monitor.") || n.startsWith("com.dwinovo.numen.platform.") || n.startsWith("com.dwinovo.numen.api.WorldContextStamp") || n.startsWith("net.minecraft.server.") || n.equals("com.dwinovo.numen.api.NumenPlugins") || n.startsWith("com.dwinovo.numen.api.ServerIdentityProvider") || n.startsWith("com.dwinovo.numen.entity.NumenPlayer")
       || n.startsWith("com.dwinovo.numen.agent.tool.") || n.equals("com.dwinovo.numen.task.TaskResult")) {
      Class<?> c=findLoadedClass(n); if(c==null)c=findClass(n); if(resolve)resolveClass(c); return c;
    }
    return super.loadClass(n,resolve);
   }
  };harness=loader.loadClass("com.dwinovo.numen.plugins.rdd.YieldHarness");
 }
 @Test void realOwnerBoundaryIssuesTicketOnlyToOwnerWithoutResultJournal() throws Exception {
  var goal=new Goal("g","goal",List.of(new PrimaryGoal("p","p",List.of(Subtask.hardCoded("s","s",Map.of("asset_key","minecraft:iron_pickaxe","minimum",1))))));
  var chain=new TaskChain(goal);chain.startCurrent();var rt=new RddRuntime(chain,new AssetRegistry());
  var method=harness.getMethod("issueNetwork",RddRuntime.class,boolean.class);
  assertEquals("not the owner",method.invoke(null,rt,false));
  String raw=(String)method.invoke(null,rt,true);var json=JsonParser.parseString(raw).getAsJsonObject();
  assertTrue(json.get("success").getAsBoolean());assertFalse(json.getAsJsonObject("data").get("ticket").getAsString().isBlank());
  assertEquals(0,harness.getMethod("journalCount").invoke(null));
 }
 @Test void ownerWithNonInternalIdMustNotReceiveTicket() throws Exception {
  var goal=new Goal("g","goal",List.of(new PrimaryGoal("p","p",List.of(Subtask.hardCoded("s","s",Map.of("asset_key","minecraft:iron_pickaxe","minimum",1))))));
  var chain=new TaskChain(goal);chain.startCurrent();var rt=new RddRuntime(chain,new AssetRegistry());
  String bad=(String)harness.getMethod("issueBadId",RddRuntime.class).invoke(null,rt);
  assertFalse(bad.contains("ticket\":\""),"invalid reply ID must not receive a ticket");
 }
 @Test void unknownBodyYieldRetainsOriginalBindingWithoutReplay() throws Exception {
  var goal=new Goal("g","goal",List.of(new PrimaryGoal("p","p",List.of(Subtask.hardCoded("s","s",Map.of("asset_key","minecraft:iron_pickaxe","minimum",1),new BodyInstruction("rdd_report_yield",Map.of()))))));
  var chain=new TaskChain(goal);chain.startCurrent();
  assertTrue((Boolean)harness.getMethod("unknownBody",RddRuntime.class).invoke(null,new RddRuntime(chain,new AssetRegistry())));
 }
 @Test void currentBodyYieldActuallyCompletes() throws Exception {
  var args=Map.<String,Object>of("expected_subtask_id","s","asset_key","minecraft:iron_pickaxe","observed",9,"take",9);
  var goal=new Goal("g","goal",List.of(new PrimaryGoal("p","p",List.of(Subtask.hardCoded("s","s",Map.of("asset_key","minecraft:iron_pickaxe","minimum",1),new BodyInstruction("rdd_report_yield",args))))));
  var chain=new TaskChain(goal);chain.startCurrent();
  String result=(String)harness.getMethod("validBody",RddRuntime.class).invoke(null,new RddRuntime(chain,new AssetRegistry()));
  assertTrue(JsonParser.parseString(result).getAsJsonObject().get("success").getAsBoolean(),result);assertEquals(9,chain.currentMinimum("s"));
 }
 @Test void staleAttemptMustNotPoisonNewAttempt() throws Exception {
  var goal=new Goal("g","goal",List.of(new PrimaryGoal("p","p",List.of(Subtask.hardCoded("s","s",Map.of("asset_key","minecraft:iron_pickaxe","minimum",1),new BodyInstruction("rdd_report_yield",Map.of()))))));
  var chain=new TaskChain(goal);chain.startCurrent();
  assertTrue((Boolean)harness.getMethod("staleAttempt",RddRuntime.class).invoke(null,new RddRuntime(chain,new AssetRegistry())),"old attempt must not record on new attempt");
  assertEquals(2,chain.attempts("s"));
 }
 @Test void staleBodyMustNotPoisonReplacementChain() throws Exception {
  var goal=new Goal("g","goal",List.of(new PrimaryGoal("p","p",List.of(Subtask.hardCoded("s","s",Map.of("asset_key","minecraft:iron_pickaxe","minimum",1),new BodyInstruction("rdd_report_yield",Map.of()))))));
  var oldChain=new TaskChain(goal);oldChain.startCurrent();var newChain=new TaskChain(goal);newChain.startCurrent();
  assertTrue((Boolean)harness.getMethod("staleBody",RddRuntime.class,RddRuntime.class).invoke(null,new RddRuntime(oldChain,new AssetRegistry()),new RddRuntime(newChain,new AssetRegistry())),"rejected old body must not write replacement state");
 }
 @Test void oldSameIdRequestMustNotMutateReplacementChain() throws Exception {
  var goal = new Goal("g","goal",List.of(new PrimaryGoal("p","p",List.of(Subtask.hardCoded("s","s",Map.of("asset_key","minecraft:iron_pickaxe","minimum",1))))));
  TaskChain oldChain=new TaskChain(goal); oldChain.startCurrent();
  TaskChain replacement=new TaskChain(goal); replacement.startCurrent();
  assertNotEquals(oldChain.chainInstanceId(),replacement.chainInstanceId());
  long before=replacement.stateVersion();
  var call=harness.getMethod("call",RddRuntime.class,String.class,String.class,int.class,int.class,String.class,boolean.class,boolean.class);
  String ticket=(String)harness.getMethod("issue",RddRuntime.class).invoke(null,new RddRuntime(oldChain,new AssetRegistry()));
  JsonObject late=JsonParser.parseString((String)call.invoke(null,new RddRuntime(replacement,new AssetRegistry()),"s","minecraft:iron_pickaxe",9,9,ticket,true,false)).getAsJsonObject();
  assertFalse(late.get("success").getAsBoolean(),"old request with same id must be rejected");
  assertEquals(1,replacement.currentMinimum("s")); assertEquals(before,replacement.stateVersion());
 }

 @Test void toolRejectsMismatchedAssetAndPreservesValidPaths() throws Exception {
  TaskChain c=new TaskChain(new Goal("g","goal",List.of(new PrimaryGoal("p","p",List.of(Subtask.hardCoded("s","s",Map.of("asset_key","minecraft:iron_pickaxe","minimum",1))))))); c.startCurrent();
  RddRuntime rt=new RddRuntime(c,new AssetRegistry()); var call=harness.getMethod("call",RddRuntime.class,String.class,String.class,int.class,int.class,String.class,boolean.class,boolean.class);
  var issue=harness.getMethod("issue",RddRuntime.class);
  JsonObject denied=JsonParser.parseString((String)call.invoke(null,rt,"s","minecraft:hay_block",9,9,issue.invoke(null,rt),true,false)).getAsJsonObject();
  assertFalse(denied.get("success").getAsBoolean());assertEquals(1,c.currentMinimum("s"));
  long version=c.stateVersion();
  JsonObject side=JsonParser.parseString((String)call.invoke(null,rt,"s","SIDE:minecraft:iron_pickaxe",9,9,issue.invoke(null,rt),true,false)).getAsJsonObject();
  assertFalse(side.get("success").getAsBoolean()); assertEquals(1,c.currentMinimum("s")); assertEquals(version,c.stateVersion());
  harness.getMethod("clearSide").invoke(null);
  String ticket=(String)issue.invoke(null,rt);
  var network=harness.getMethod("network",RddRuntime.class,String.class,boolean.class,boolean.class);
  assertEquals("not the owner",network.invoke(null,rt,ticket,false,false));
  assertEquals("OTHER_OK",network.invoke(null,rt,"",true,true));
  JsonObject direct=JsonParser.parseString((String)call.invoke(null,rt,"s","minecraft:iron_pickaxe",9,9,ticket,true,true)).getAsJsonObject();
  assertFalse(direct.get("success").getAsBoolean());
  JsonObject ok=JsonParser.parseString((String)network.invoke(null,rt,ticket,true,false)).getAsJsonObject();
  assertTrue(ok.get("success").getAsBoolean(), () -> "owner current-ticket result=" + ok);assertEquals(9,c.currentMinimum("s"));
 }
}
