package com.dwinovo.numen.core.task.mine;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exact AutoMineTool.onServerCall and Args declaration. Parsing uses actual Gson, not
 * production ToolArgs coercion; legacy array inputs only are covered. Body/task creation,
 * ctx and reply transport are boundaries. Rejection must precede both task construction and dispatch. */
class MineUnsupportedRangeConsumerTest {
    @TempDir static Path temp;
    static URLClassLoader loader;
    static Class<?> fixture;
    @BeforeAll static void setup() throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while(root!=null&&!Files.isDirectory(root.resolve("core/common/src/main/java")))root=root.getParent();
        assertNotNull(root);
        String source=Files.readString(root.resolve("core/common/src/main/java/com/dwinovo/numen/core/tools/work/AutoMineTool.java"));
        String method=MineHarvestRecoveryConsumerTest.method(source,"    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {");
        int start=source.indexOf("    private record Args(");int end=source.indexOf("\n",start);
        assertTrue(start>=0&&end>start);
        String argsDeclaration=source.substring(start,end);
        Path src=temp.resolve("src"),out=temp.resolve("classes");Files.createDirectories(out);
        List<String> compilerArgs=new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-d",out.toString()));
        String helpers="";
        for(String prefix:List.of("    private static BlockPos[] parseTargetBounds(","    private static int targetCoordinate(")){int at=source.indexOf(prefix);if(at>=0){int finish=source.indexOf("\n    }",at);helpers+=source.substring(at,finish+6);}}
        MineHarvestRecoveryConsumerTest.add(src,compilerArgs,"fixture/RangeConsumer.java",BOUNDARIES.replace("ARGS_DECLARATION",argsDeclaration).replace("CALL_METHOD",method+helpers));
        URL gson=com.google.gson.Gson.class.getProtectionDomain().getCodeSource().getLocation();
        compilerArgs.addAll(List.of("-classpath",Path.of(gson.toURI()).toString()));
        ByteArrayOutputStream diagnostics=new ByteArrayOutputStream();
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,diagnostics,diagnostics,compilerArgs.toArray(String[]::new)),"SETUP: "+diagnostics);
        loader=new URLClassLoader(new URL[]{out.toUri().toURL(),gson},ClassLoader.getPlatformClassLoader());fixture=loader.loadClass("fixture.RangeConsumer");
    }
    @AfterAll static void close() throws Exception {if(loader!=null)loader.close();}
    @SuppressWarnings("unchecked") static Map<String,Object> run(String extra)throws Exception{return (Map<String,Object>)fixture.getMethod("run",String.class).invoke(null,extra);}
    @Test void legacyRequestStillDispatchesOnce()throws Exception{
        var r=run("");assertEquals(1,r.get("built"));assertEquals(1,r.get("dispatched"));assertEquals(4,r.get("count"));assertEquals("",r.get("error"));
    }
    @ParameterizedTest
    @ValueSource(strings={"x1","y1","z1","x2","y2","z2","min_y","max_y"})
    void singleUnsupportedConstraintNeverStartsUnconstrainedMining(String key)throws Exception{
        var r=run(",\""+key+"\":0");assertEquals(0,r.get("built"),key);assertEquals(0,r.get("dispatched"),key);assertTrue(((String)r.get("error")).contains(key),key);
    }
    @Test void nestedPathBoundsAreNotSilentlyIgnored()throws Exception{
        var r=run(",\"path_bounds\":{\"x1\":0,\"x2\":10}");
        assertEquals(0,r.get("built"));assertEquals(0,r.get("dispatched"));
        assertTrue(((String)r.get("error")).contains("path_bounds"));
    }
    @Test void explicitNullRangeDoesNotPretendToApplyAConstraint()throws Exception{
        var r=run(",\"min_y\":null");
        assertEquals(1,r.get("built"));assertEquals(1,r.get("dispatched"));
        assertEquals("",r.get("error"),"null means no constraint; this is NOT constrained mining");
    }
    @Test void unrelatedMetadataDoesNotChangeLegacyDispatch()throws Exception{
        var r=run(",\"trace_label\":\"test-only\"");
        assertEquals(1,r.get("built"));assertEquals(1,r.get("dispatched"));
    }
    @Test void fullBoxIsNotSilentlyDropped()throws Exception{
        var r=run(",\"x1\":0,\"y1\":0,\"z1\":0,\"x2\":10,\"y2\":80,\"z2\":10");assertEquals(0,r.get("built"));assertEquals(0,r.get("dispatched"));assertFalse(((String)r.get("error")).isBlank());
    }
    static final String BOUNDARIES="""
        package fixture;
        import java.util.*;import java.util.function.Consumer;import com.google.gson.*;
        public class RangeConsumer {
            ARGS_DECLARATION
            record BlockPos(int x,int y,int z){}
            static class Level{boolean isInWorldBounds(BlockPos p){return true;}}
            static class NumenPlayer {Level level(){return new Level();}}
            static class ToolArgs {static <T>T fromJson(JsonObject a,Class<T> t){return new Gson().fromJson(a,t);}}
            static class BlockActionOps {
                int calls,count;
                Object autoMine(List<String> ids,int n,Object ctx){calls++;count=n;return new Object();}
                Object autoMine(List<String> ids,int n,Object ctx,BlockPos first,BlockPos second){return autoMine(ids,n,ctx);}
            }
            final BlockActionOps impl=new BlockActionOps();int dispatched;
            Object ctx(String id,NumenPlayer p){return new Object();}
            void setTask(NumenPlayer p,Object task,JsonObject args,Consumer<String> reply){dispatched++;reply.accept("accepted");}
            CALL_METHOD
            public static Map<String,Object> run(String extra){
                RangeConsumer c=new RangeConsumer();String error="";
                JsonObject args=new JsonObject(); JsonArray ids=new JsonArray();ids.add("minecraft:iron_ore");
                args.add("block_ids",ids);args.addProperty("count",4);
                if(!extra.isEmpty()){JsonObject constraints=JsonParser.parseString("{"+extra.substring(1)+"}").getAsJsonObject();constraints.entrySet().forEach(e->args.add(e.getKey(),e.getValue()));}
                try{c.onServerCall("call-1",args,new NumenPlayer(),s->{});}catch(IllegalArgumentException rejected){error=rejected.getMessage();}
                return Map.of("built",c.impl.calls,"dispatched",c.dispatched,"count",c.impl.count,"error",error);
            }
        }
        """;
}
