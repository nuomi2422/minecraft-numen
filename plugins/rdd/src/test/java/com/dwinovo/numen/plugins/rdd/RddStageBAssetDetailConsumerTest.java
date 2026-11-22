package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.*;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.provider.*;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.io.File;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual owned Stage-B call + request methods + parser/read view; MC, endpoint and client are ports.
 * A controlled model asks for detail, then produces the real parsed specification. Not live LLM/MC. */
class RddStageBAssetDetailConsumerTest {
    public interface Driver {
        void start(); void respond(String name,String id,String args); void respondMultiple(); void addLarge(); void staleAfterRead(String kind);
        int requests(); int reads(); int completions(); List<?> specs(); List<ConvoState.Msg> messages();
        List<String> offered(); void stale(String kind); void budget(int allowed); void drain();
    }
    @TempDir static Path temp;
    static Class<?> host; static URLClassLoader loader;
    private static String method(String source,String prefix){int at=source.indexOf(prefix);assertTrue(at>=0,"SETUP missing method "+prefix);
        int end=source.indexOf("\n    }",source.indexOf('{',at));assertTrue(end>at);return source.substring(at,end+6);}
    private static String optionalMethods(String source,String prefix){String out="";int from=0;while(true){int at=source.indexOf(prefix,from);if(at<0)return out;
        String part=method(source.substring(at),prefix);out+=part+"\n";from=at+part.length();}}
    @BeforeAll static void compile()throws Exception {
        Path root=Path.of("").toAbsolutePath();while(root!=null&&!Files.isDirectory(root.resolve("plugins/rdd/src/main/java")))root=root.getParent();assertNotNull(root);
        Path src=root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd");
        String dc=Files.readString(src.resolve("RddDecomposer.java"));
        String methods=method(dc,"    static void decomposeSpecsWithHint(UUID companionId, TaskChain owner,")
            +method(dc,"    static void llmAsk(UUID companionId,")
            +optionalMethods(dc,"    static void llmAskOwnedStageB(")
            +optionalMethods(dc,"    private static void stageBAssetRound(");
        String shell=Files.readString(root.resolve("plugins/rdd/src/test/resources/stageb-assets/StageBAssetHost.java.in"));
        Path out=temp.resolve("classes");Files.createDirectories(out);Path generated=temp.resolve("StageBAssetHost.java");
        Files.writeString(generated,shell.replace("// @METHODS@",methods));
        List<String> args=new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-classpath",System.getProperty("java.class.path")+File.pathSeparator
            +Path.of(Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI())+File.pathSeparator
            +Path.of(TaskChain.class.getProtectionDomain().getCodeSource().getLocation().toURI())+File.pathSeparator
            +Path.of(ConvoState.class.getProtectionDomain().getCodeSource().getLocation().toURI())+File.pathSeparator
            +Path.of(Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()),"-d",out.toString(),generated.toString()));
        for(String type:List.of("RddAssetReadView","RddAssetContext","RddAssetStore","RddStageBSpecParser","RddKeys","RddCallbackGuard","RddPlannerTransport"))args.add(src.resolve(type+".java").toString());
        args.add(src.resolve("RddStageBAssetHost.java").toString());
        String plugin=Files.readString(src.resolve("RddPlugin.java"));
        Files.writeString(generated,Files.readString(generated).replace("// @ON_SERVER@",method(plugin,"    static void onServer(MinecraftServer server,")));
        source(args,"net.minecraft.server.MinecraftServer","""
            package net.minecraft.server; import java.util.*;
            public class MinecraftServer { public final ArrayDeque<Runnable> queue=new ArrayDeque<>();public boolean same=true;
                public boolean isSameThread(){return same;}public void execute(Runnable action){queue.add(action);} }
            """);
        source(args,"net.neoforged.neoforge.server.ServerLifecycleHooks","""
            package net.neoforged.neoforge.server; import net.minecraft.server.MinecraftServer;
            public class ServerLifecycleHooks {public static MinecraftServer current; public static MinecraftServer getCurrentServer(){return current;}}
            """);
        source(args,"com.dwinovo.numen.entity.NumenPlayer","""
            package com.dwinovo.numen.entity; import net.minecraft.server.MinecraftServer;
            public class NumenPlayer {public final MinecraftServer server;public boolean alive=true,removed;
                public NumenPlayer(MinecraftServer server){this.server=server;}public MinecraftServer getServer(){return server;}
                public boolean isAlive(){return alive;}public boolean isRemoved(){return removed;}}
            """);
        source(args,"com.dwinovo.numen.api.WorldContextStamp","""
            package com.dwinovo.numen.api; import java.util.*; import net.minecraft.server.MinecraftServer;
            public class WorldContextStamp {public record Token(Map<String,String> identity,long epoch){}
                public static Token token=new Token(Map.of("world_id","fixture","server_session","one"),1);
                public static Token capture(MinecraftServer server){return token;}}
            """);
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)),"SETUP actual Stage-B consumers compile");
        loader=new URLClassLoader(new java.net.URL[]{out.toUri().toURL()},RddStageBAssetDetailConsumerTest.class.getClassLoader()){
            protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
                if(name.startsWith("com.dwinovo.numen.plugins.rdd.")&&!name.startsWith(RddStageBAssetDetailConsumerTest.class.getName())){
                    try{Class<?> c=findClass(name);if(resolve)resolveClass(c);return c;}catch(ClassNotFoundException absent){}
                }
                if(name.startsWith("net.minecraft.")||name.startsWith("net.neoforged.")||name.startsWith("com.dwinovo.numen.entity.")||name.startsWith("com.dwinovo.numen.api.WorldContextStamp")){
                    try{Class<?> c=findClass(name);if(resolve)resolveClass(c);return c;}catch(ClassNotFoundException absent){}
                }return super.loadClass(name,resolve);
            }};
        host=loader.loadClass("com.dwinovo.numen.plugins.rdd.StageBAssetHost");
    }
    private static void source(List<String> args,String name,String content)throws Exception{Path path=temp.resolve(name.replace('.','/')+".java");Files.createDirectories(path.getParent());Files.writeString(path,content);args.add(path.toString());}
    private Driver fixture()throws Exception{return (Driver)host.getConstructor().newInstance();}
    private static final String PLAN="{\"subtasks\":[{\"description\":\"use verified fixture clue\",\"condition\":{\"asset_key\":\"minecraft:bread\",\"minimum\":1}}]}";
    @Test void exactRegistryDetailReachesNextModelRequestAndActualSpecs()throws Exception {
        Driver f=fixture();f.start();f.drain();assertTrue(f.offered().contains("rdd_assets"));
        f.respond("rdd_assets","detail-1","{\"action\":\"detail\",\"asset_id\":\"base\"}");f.drain();
        assertEquals(2,f.requests(),"detail is a tool result, not a plan; allow exactly one followup");assertEquals(1,f.reads());assertEquals(0,f.completions());
        var reply=(ConvoState.Msg.Tool)f.messages().getLast();assertEquals("detail-1",reply.toolCallId());
        assertTrue(reply.content().contains("fixture-hidden-entry"));assertTrue(reply.content().length()<=8000);
        assertTrue(f.messages().get(f.messages().size()-2) instanceof ConvoState.Msg.Assistant);
        f.respond("decompose_goal","plan-1",PLAN);f.drain();assertEquals(1,f.completions());assertEquals(1,f.specs().size());
    }
    @Test void missingAndOversizedDetailsAreToolResultsWithOneFinalOpportunity()throws Exception {
        for(String id:List.of("missing","large")){
            Driver f=fixture();if(id.equals("large"))f.addLarge();f.start();f.drain();
            f.respond("rdd_assets","detail-1","{\"action\":\"detail\",\"asset_id\":\""+id+"\"}");f.drain();
            assertEquals(2,f.requests());assertEquals(1,f.reads());assertEquals(0,f.completions());
            String reply=((ConvoState.Msg.Tool)f.messages().getLast()).content();
            assertTrue(reply.contains(id.equals("missing")?"not_found":"too_large"));assertTrue(reply.length()<=8000);
            f.respond("decompose_goal","plan-1",PLAN);f.drain();assertEquals(1,f.specs().size());
        }
    }
    @Test void secondDetailWrongNamesIdsAndMultipleCallsExitWithoutExtraRead()throws Exception {
        Driver f=fixture();f.start();f.drain();f.respond("rdd_assets","detail-1","{\"action\":\"detail\",\"asset_id\":\"base\"}");f.drain();
        f.respond("rdd_assets","detail-2","{\"action\":\"detail\",\"asset_id\":\"base\"}");f.drain();
        assertEquals(2,f.requests());assertEquals(1,f.reads());assertEquals(1,f.completions());assertTrue(f.specs().isEmpty());
        for(String kind:List.of("wrong","blank","multi","catalog","reused")){
            f=fixture();f.start();f.drain();
            if(kind.equals("multi"))f.respondMultiple();
            else if(kind.equals("catalog"))f.respond("rdd_assets","catalog","{\"action\":\"catalog\"}");
            else if(kind.equals("reused")){
                f.respond("rdd_assets","same","{\"action\":\"detail\",\"asset_id\":\"base\"}");f.drain();f.respond("decompose_goal","same",PLAN);
            }else f.respond(kind.equals("wrong")?"rdd_status":"decompose_goal",kind.equals("blank")?" ":"wrong",PLAN);
            f.drain();assertEquals(1,f.completions(),kind);assertTrue(f.specs().isEmpty(),kind);assertEquals(kind.equals("reused")?1:0,f.reads(),kind);
        }
    }
    @Test void eachModelRoundChecksBudgetAndStaleOwnerCannotReadOrPublish()throws Exception {
        Driver f=fixture();f.budget(1);f.start();f.drain();
        f.respond("rdd_assets","detail-1","{\"action\":\"detail\",\"asset_id\":\"base\"}");f.drain();
        assertEquals(1,f.requests());assertEquals(1,f.reads());assertEquals(1,f.completions());assertTrue(f.specs().isEmpty());
        f=fixture();f.start();f.drain();f.respond("rdd_assets","detail-1","{\"action\":\"detail\",\"asset_id\":\"base\"}");
        f.stale("owner");f.drain();assertEquals(1,f.requests());assertEquals(0,f.reads());assertEquals(0,f.completions());
    }
    @Test void realHostScopeRejectsEveryChangedIdentityBeforeReadAndBeforeFinalDelivery()throws Exception {
        for(String kind:List.of("server","body","ticket","chain","revision","primary","world","registry","death")){
            Driver f=fixture();f.start();f.drain();f.respond("rdd_assets","detail-1","{\"action\":\"detail\",\"asset_id\":\"base\"}");
            f.stale(kind);f.drain();assertEquals(0,f.reads(),kind);assertEquals(1,f.requests(),kind);assertEquals(0,f.completions(),kind);
            f=fixture();f.start();f.drain();f.respond("rdd_assets","detail-1","{\"action\":\"detail\",\"asset_id\":\"base\"}");f.drain();
            assertEquals(2,f.requests(),kind);f.respond("decompose_goal","plan-1",PLAN);f.stale(kind);f.drain();
            assertEquals(0,f.completions(),kind);assertEquals(1,f.reads(),kind);
        }
    }
    @Test void scopeChangedByRegistryReadCannotForwardItsResult()throws Exception {
        Driver f=fixture();f.start();f.drain();f.staleAfterRead("ticket");f.respond("rdd_assets","detail-1","{\"action\":\"detail\",\"asset_id\":\"base\"}");f.drain();
        assertEquals(1,f.reads());assertEquals(1,f.requests());assertEquals(0,f.completions());
    }
    @Test void directPlanKeepsOneRequestAndNeverReadsRegistry()throws Exception {
        Driver f=fixture();f.start();f.drain();f.respond("decompose_goal","direct",PLAN);f.drain();
        assertEquals(1,f.requests());assertEquals(0,f.reads());assertEquals(1,f.completions());assertEquals(1,f.specs().size());
    }
}
