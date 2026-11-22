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
  String first=method(s,"        requestBatchTicket(gen, ticket -> llm.chatStreaming(","        return true;");
  String retry=method(s,"                requestBatchTicket(gen2, ticket2 -> llm2.chatStreaming(","                return;");
  Path d=Files.createTempDirectory("yield-preflight-");Path f=d.resolve("PreflightFixture.java");Files.writeString(f,HEADER+method+"void first(){"+first+"}\nvoid retry(){"+retry+"}\n"+TAIL);
  String gson=Path.of(com.google.gson.Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
  assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-classpath",gson,"-d",d.toString(),f.toString()),"fixture setup");
  try(var l=new URLClassLoader(new URL[]{d.toUri().toURL(),com.google.gson.Gson.class.getProtectionDomain().getCodeSource().getLocation()},ClassLoader.getPlatformClassLoader())){
   var c=l.loadClass("PreflightFixture");assertEquals("before=0,after=old,stale=0,logout=0,timeout=null",c.getMethod("run").invoke(null));
  }
 }
 static String method(String s,String a,String b){int i=s.indexOf(a),j=s.indexOf(b,i);assertTrue(i>=0&&j>i);return s.substring(i,j);}
 static final String HEADER="""
import java.util.*; import java.util.function.*;
public class PreflightFixture { UUID entityUuid=UUID.randomUUID();int turnGeneration=1; int gen=1,gen2=1; Object snapshot,tools,systemPrompt,executionObservation,retrySnapshot,retryTools,retrySystem,retryObservation; Presenter presenter=new Presenter(); Voice vt=new Voice(),vt2=new Voice(); Llm llm=new Llm(),llm2=new Llm(); static class Presenter {Object tapForUi(int a,Object b,java.util.function.Function<String,String> c){return null;}} static class Voice {Object sink(){return null;}Runnable finish(){return ()->{};}} static class Llm {int calls;Llm provider(){return this;}String extractReasoningDelta(String s){return s;}java.util.concurrent.CompletableFuture<String> chatStreaming(Object a,Object b,Object c,Object d,Object e){calls++;return new java.util.concurrent.CompletableFuture<>();}} void bounceBackToMain(int g,String t,Object r,Throwable e){}static class Wire {static Consumer<String> cb;static void requestYieldTicket(UUID e,Consumer<String> c){cb=c;}} static class Minecraft {static final Minecraft I=new Minecraft();Object connection=new Object();List<Runnable> tasks=new ArrayList<>();static Minecraft getInstance(){return I;}void execute(Runnable r){tasks.add(r);}Object getConnection(){return connection;}void flush(){var copy=List.copyOf(tasks);tasks.clear();copy.forEach(Runnable::run);}}
""";
 static final String TAIL="""
 static String ticket(String value){var data=new com.google.gson.JsonObject();data.addProperty("ticket",value);var root=new com.google.gson.JsonObject();root.addProperty("success",true);root.add("data",data);return root.toString();}
 public static String run(){PreflightFixture caller=new PreflightFixture();caller.first();int firstBefore=caller.llm.calls;Wire.cb.accept(ticket("first"));Minecraft.I.flush();int firstAfter=caller.llm.calls;caller.retry();int retryBefore=caller.llm2.calls;Wire.cb.accept(ticket("retry"));Minecraft.I.flush();int retryAfter=caller.llm2.calls;caller.first();caller.turnGeneration=2;Wire.cb.accept(ticket("late"));Minecraft.I.flush();if(firstBefore!=0||firstAfter!=1||retryBefore!=0||retryAfter!=1||caller.llm.calls!=1)throw new AssertionError("chat preflight ordering");PreflightFixture f=new PreflightFixture();List<String> starts=new ArrayList<>();f.requestBatchTicket(1,starts::add);int before=starts.size();Wire.cb.accept(ticket("old"));Minecraft.I.flush();String after=starts.get(0);starts.clear();f.requestBatchTicket(1,starts::add);f.turnGeneration=2;Wire.cb.accept(ticket("stale"));Minecraft.I.flush();int stale=starts.size();f.requestBatchTicket(2,starts::add);Minecraft.I.connection=null;Wire.cb.accept(ticket("lost"));Minecraft.I.flush();int logout=starts.size();Minecraft.I.connection=new Object();f.requestBatchTicket(2,starts::add);Wire.cb.accept(null);Minecraft.I.flush();return "before="+before+",after="+after+",stale="+stale+",logout="+logout+",timeout="+starts.get(0);}
}
""";
}
