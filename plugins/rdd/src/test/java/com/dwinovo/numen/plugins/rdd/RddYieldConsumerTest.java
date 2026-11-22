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
  files.put("com/dwinovo/numen/entity/NumenPlayer.java","package com.dwinovo.numen.entity; import java.util.*; public class NumenPlayer { public UUID getUUID(){return new UUID(0,1);} }");
  files.put("com/dwinovo/numen/task/TaskResult.java","package com.dwinovo.numen.task; import java.util.*; import com.google.gson.Gson; public record TaskResult(boolean success,String message,Map<String,Object> data) { public static TaskResult fail(String m){return new TaskResult(false,m,Map.of());} public static TaskResult ok(String m,Map<String,Object>d){return new TaskResult(true,m,d);} public String toJson(){return new Gson().toJson(this);} }");
  files.put("com/dwinovo/numen/plugins/rdd/RddPlugin.java","package com.dwinovo.numen.plugins.rdd; import java.util.*;import com.dwinovo.numen.rdd.core.*; public class RddPlugin {public static RddRuntime rt; static RddRuntime runtime(UUID id){return rt;} static void saveRuntimes(){} static void publishTaskSnapshot(UUID id,String why){} }");
  files.put("com/dwinovo/numen/plugins/rdd/RddMonitor.java","package com.dwinovo.numen.plugins.rdd; import java.util.*; class RddMonitor {static void publish(String s,Map<String,Object> d){} }");
  files.put("com/dwinovo/numen/plugins/rdd/YieldHarness.java","""
package com.dwinovo.numen.plugins.rdd; import com.google.gson.*;import com.dwinovo.numen.rdd.core.*;import com.dwinovo.numen.entity.NumenPlayer; public class YieldHarness { public static String call(RddRuntime rt,String id,String asset,int observed,int take){RddPlugin.rt=rt;JsonObject a=new JsonObject();a.addProperty("expected_subtask_id",id);a.addProperty("asset_key",asset);a.addProperty("observed",observed);a.addProperty("take",take);String[] result={null};new RddReportYieldTool().onServerCall("wire",a,new NumenPlayer(),v->result[0]=v);return result[0];} }
""");
  var args=new ArrayList<String>(List.of("-encoding","UTF-8","-proc:none","-classpath",System.getProperty("java.class.path")+File.pathSeparator+Path.of(TaskChain.class.getProtectionDomain().getCodeSource().getLocation().toURI())+File.pathSeparator+Path.of(Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI()),"-d",tmp.toString()));
  for(var e:files.entrySet()){Path p=tmp.resolve(e.getKey());Files.createDirectories(p.getParent());Files.writeString(p,e.getValue(),StandardCharsets.UTF_8);args.add(p.toString());}
  assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
  loader=new URLClassLoader(new URL[]{tmp.toUri().toURL()},RddYieldConsumerTest.class.getClassLoader());harness=loader.loadClass("com.dwinovo.numen.plugins.rdd.YieldHarness");
 }
 @Test void toolRejectsMismatchedAssetAndPreservesValidPaths() throws Exception {
  TaskChain c=new TaskChain(new Goal("g","goal",List.of(new PrimaryGoal("p","p",List.of(Subtask.hardCoded("s","s",Map.of("asset_key","minecraft:iron_pickaxe","minimum",1))))))); c.startCurrent();
  RddRuntime rt=new RddRuntime(c,new AssetRegistry()); var call=harness.getMethod("call",RddRuntime.class,String.class,String.class,int.class,int.class);
  JsonObject denied=JsonParser.parseString((String)call.invoke(null,rt,"s","minecraft:hay_block",9,9)).getAsJsonObject();
  assertFalse(denied.get("success").getAsBoolean());assertEquals(1,c.currentMinimum("s"));
  JsonObject ok=JsonParser.parseString((String)call.invoke(null,rt,"s","minecraft:iron_pickaxe",9,9)).getAsJsonObject();
  assertTrue(ok.get("success").getAsBoolean());assertEquals(9,c.currentMinimum("s"));
 }
}
