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
        void start(); void respond(String name,String id,String args);
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
        for(String type:List.of("RddAssetReadView","RddAssetContext","RddAssetStore","RddStageBSpecParser","RddKeys","RddCallbackGuard"))args.add(src.resolve(type+".java").toString());
        if(Files.exists(src.resolve("RddStageBAssetHost.java")))args.add(src.resolve("RddStageBAssetHost.java").toString());
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)),"SETUP actual Stage-B consumers compile");
        loader=new URLClassLoader(new java.net.URL[]{out.toUri().toURL()},RddStageBAssetDetailConsumerTest.class.getClassLoader()){
            protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
                if(name.startsWith("com.dwinovo.numen.plugins.rdd.")&&!name.startsWith(RddStageBAssetDetailConsumerTest.class.getName())){
                    try{Class<?> c=findClass(name);if(resolve)resolveClass(c);return c;}catch(ClassNotFoundException absent){}
                }return super.loadClass(name,resolve);
            }};
        host=loader.loadClass("com.dwinovo.numen.plugins.rdd.StageBAssetHost");
    }
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
    @Test void directPlanKeepsOneRequestAndNeverReadsRegistry()throws Exception {
        Driver f=fixture();f.start();f.drain();f.respond("decompose_goal","direct",PLAN);f.drain();
        assertEquals(1,f.requests());assertEquals(0,f.reads());assertEquals(1,f.completions());assertEquals(1,f.specs().size());
    }
}
