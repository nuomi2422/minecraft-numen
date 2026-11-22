package com.dwinovo.numen.plugins.rdd;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual strict review send method plus real proposal parser; endpoint/client transport boundaries are doubles. */
class RddConcernProposalTransportTest {
    @TempDir Path temp;
    @Test void wrongNamesAndMultipleCallsNeverBecomeConcernProposals() throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while(root!=null&&!Files.isRegularFile(root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddDecomposer.java"))) root=root.getParent();
        assertNotNull(root);
        String source=Files.readString(root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddDecomposer.java"));
        int a=source.indexOf("    static void llmAskConcern("),b=source.indexOf("\n    }",a);
        assertTrue(a>=0&&b>a);
        Path out=temp.resolve("out"),file=temp.resolve("fixture/Probe.java");Files.createDirectories(out);Files.createDirectories(file.getParent());
        Files.writeString(file,"""
            package fixture;
            import java.util.*; import java.util.function.*; import java.util.concurrent.*;
            interface IToolSpec { String name(); }
            record Call(String name,String arguments) {}
            record AssistantTurn(List<Call> toolCalls) { boolean hasToolCalls(){return !toolCalls.isEmpty();} }
            record Result(AssistantTurn turn) {}
            class ConvoState { interface Msg { record User(String content) implements Msg {} } }
            class LlmObservation { LlmObservation(String r,String id,String stage,Consumer<Object> sink){} }
            class RddMonitor { static void publish(Object event){} }
            class RddPlugin { static void publishPlanningContext(Object... args){} }
            class RddDecomposerPrompt { static final IToolSpec DECOMPOSE_TOOL=()->"decompose_goal";static final String SYSTEM_PROMPT="system"; }
            class NumenLlmClient {
                static int mode;static List<String> offered;static NumenLlmClient forEndpoint(Object endpoint){return new NumenLlmClient();}
                CompletableFuture<Result> chatStreaming(List<?> msgs,List<IToolSpec> tools,String system,Object chunk,LlmObservation observation){
                    offered=tools.stream().map(IToolSpec::name).toList();
                    List<Call> calls=mode==0?List.of(new Call("decompose_goal","{review}")):
                        mode==1?List.of(new Call("rdd_assets","{review}")):
                        List.of(new Call("decompose_goal","{review}"),new Call("decompose_goal","{other}"));
                    return CompletableFuture.completedFuture(new Result(new AssistantTurn(calls)));
                }
            }
            public class Probe {
                static String situationBlock(UUID id){return "";}
                static void withPlannerEndpoint(UUID id,String stage,Function<Object,CompletableFuture<Result>> send,Consumer<Result> done,Runnable fail){done.accept(send.apply(new Object()).join());}
            """+source.substring(a,b+6)+"""
                public static List<String> run(int mode){NumenLlmClient.mode=mode;var result=new ArrayList<String>();llmAskConcern(UUID.randomUUID(),"facts",result::add,()->result.add("fail"));return List.of(String.join(",",NumenLlmClient.offered),result.getFirst());}
            }
            """);
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-encoding","UTF-8","-proc:none","-d",out.toString(),file.toString()),"SETUP: strict review method compiles");
        try(var loader=new URLClassLoader(new java.net.URL[]{out.toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
            var run=loader.loadClass("fixture.Probe").getMethod("run",int.class);
            assertEquals(List.of("decompose_goal","{review}"),run.invoke(null,0));
            assertEquals(List.of("decompose_goal","fail"),run.invoke(null,1));
            assertEquals(List.of("decompose_goal","fail"),run.invoke(null,2));
        }
    }
    @Test void proposalRefAndEvidenceAreRequiredButNeverVerifiedByTheParser() {
        String base="{\"subtasks\":[{\"description\":\"keep legacy plan\",\"condition\":{\"asset_key\":\"minecraft:bread\",\"minimum\":1}}]";
        String review=",\"concern_review\":{\"concern_ref\":\"ref\",\"proposal\":\"FIXED\",\"reason\":\"claimed\",\"basis\":\"model text\"}}";
        assertEquals(1,RddStageBSpecParser.parse(base+"}").size());
        var proposal=RddStageBSpecParser.parseConcernProposal(base+review,"ref");assertNotNull(proposal);
        assertEquals(RddStageBSpecParser.ConcernProposalKind.FIXED,proposal.proposal());
        assertNull(RddStageBSpecParser.parseConcernProposal(base+review,"wrong"));
        assertNull(RddStageBSpecParser.parseConcernProposal(base+"}","ref"));
        assertNull(RddStageBSpecParser.parseConcernProposal(base+review.replace("model text",""),"ref"));
        assertNull(RddStageBSpecParser.parseConcernProposal(base+review.replace("FIXED","DEFERRED"),"ref"));
        assertEquals(1,RddStageBSpecParser.parse(base+review.replace("FIXED","BAD_ENUM")).size(),"invalid optional review does not invalidate old specs");
    }
}
