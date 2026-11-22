package com.dwinovo.numen.client.agent;
import org.junit.jupiter.api.Test;
import javax.tools.ToolProvider;
import java.net.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
/** Production EntityAgentLoop preflight method with fake network and main-thread scheduler. */
class YieldRequestPreflightConsumerTest {
 @Test void requestPreflightFreezesBeforeStartAndRejectsStaleCallbacks() throws Exception {
  Path root=Path.of("").toAbsolutePath();while(root!=null&&!Files.isDirectory(root.resolve("api/common/src")))root=root.getParent();assertNotNull(root);
  String s=Files.readString(root.resolve("api/common/src/client/java/com/dwinovo/numen/client/agent/EntityAgentLoop.java"));
  int a=s.indexOf("    private void requestBatchTicket("),b=s.indexOf("    private void bounceBackToMain(",a);assertTrue(a>=0&&b>a);
  String method=s.substring(a,b).replace("com.dwinovo.numen.agent.tool.ServerToolTransport","Wire").replace("com.google.gson.JsonParser","com.google.gson.JsonParser");
  Path d=Files.createTempDirectory("yield-preflight-");Path f=d.resolve("PreflightFixture.java");Files.writeString(f,HEADER+method+TAIL);
  String gson=Path.of(com.google.gson.Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
  assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-classpath",gson,"-d",d.toString(),f.toString()),"fixture setup");
  try(var l=new URLClassLoader(new URL[]{d.toUri().toURL(),com.google.gson.Gson.class.getProtectionDomain().getCodeSource().getLocation()},ClassLoader.getPlatformClassLoader())){
   var c=l.loadClass("PreflightFixture");assertEquals("before=0,after=old,stale=0,logout=0,timeout=null",c.getMethod("run").invoke(null));
  }
 }
 static final String HEADER="""
import java.util.*; import java.util.function.*;
public class PreflightFixture { UUID entityUuid=UUID.randomUUID();int turnGeneration=1;static class Wire {static Consumer<String> cb;static void requestYieldTicket(UUID e,Consumer<String> c){cb=c;}} static class Minecraft {static final Minecraft I=new Minecraft();Object connection=new Object();List<Runnable> tasks=new ArrayList<>();static Minecraft getInstance(){return I;}void execute(Runnable r){tasks.add(r);}Object getConnection(){return connection;}void flush(){var copy=List.copyOf(tasks);tasks.clear();copy.forEach(Runnable::run);}}
""";
 static final String TAIL="""
 public static String run(){PreflightFixture f=new PreflightFixture();List<String> starts=new ArrayList<>();f.requestBatchTicket(1,starts::add);int before=starts.size();Wire.cb.accept("{\"success\":true,\"data\":{\"ticket\":\"old\"}}");Minecraft.I.flush();String after=starts.get(0);starts.clear();f.requestBatchTicket(1,starts::add);f.turnGeneration=2;Wire.cb.accept("{\"success\":true,\"data\":{\"ticket\":\"stale\"}}");Minecraft.I.flush();int stale=starts.size();f.requestBatchTicket(2,starts::add);Minecraft.I.connection=null;Wire.cb.accept("{\"success\":true,\"data\":{\"ticket\":\"lost\"}}");Minecraft.I.flush();int logout=starts.size();Minecraft.I.connection=new Object();f.requestBatchTicket(2,starts::add);Wire.cb.accept(null);Minecraft.I.flush();return "before="+before+",after="+after+",stale="+stale+",logout="+logout+",timeout="+starts.get(0);}
}
""";
}
