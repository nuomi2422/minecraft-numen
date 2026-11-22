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

/** Exact tool call/schema methods -> actual autoMine methods -> entire production record.
 * Gson parsing replaces ToolArgs coercion (array inputs only); MC registry, ctx and dispatch are ports.
 * This does not prove schema delivery, model adoption, route confinement or actual game execution. */
class MinePublicTargetBoundsConsumerTest {
    @TempDir static Path temp;static URLClassLoader loader;static Class<?> fixture;
    @BeforeAll static void compile()throws Exception{
        Path root=Path.of("").toAbsolutePath();while(root!=null&&!Files.isDirectory(root.resolve("core/common/src/main/java")))root=root.getParent();assertNotNull(root);
        Path core=root.resolve("core/common/src/main/java/com/dwinovo/numen/core");
        String tool=Files.readString(core.resolve("tools/work/AutoMineTool.java"));
        String call=MineHarvestRecoveryConsumerTest.method(tool,"    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {");
        String schema=MineHarvestRecoveryConsumerTest.method(tool,"    public Map<String, Object> parameterSchema() {");
        int start=tool.indexOf("    private record Args(");int end=tool.indexOf("\n",start);assertTrue(start>=0&&end>start);
        String methods=call+schema+tool.substring(start,end);
        // Include locally added parser methods after implementation; no copied parser in test.
        for(String prefix:List.of("    private static BlockPos[] parseTargetBounds(","    private static int targetCoordinate(")){
            int at=tool.indexOf(prefix);if(at>=0){int finish=tool.indexOf("\n    }",at);methods+=tool.substring(at,finish+6);}
        }
        String opsSource=Files.readString(core.resolve("tools/BlockActionOps.java")),ops="";int at=0;
        while((at=opsSource.indexOf("    public TaskRecord autoMine(",at))>=0){int finish=opsSource.indexOf("\n    }",at);ops+=opsSource.substring(at,finish+6);at=finish+6;}
        String record=Files.readString(core.resolve("task/mine/MineBlockTaskRecord.java")).replace("package com.dwinovo.numen.core.task.mine;","package fixture;").replace("import com.dwinovo.numen.task.TaskRecord;", "").replace("import net.minecraft.world.level.block.Block;", "").replace("import net.minecraft.core.BlockPos;", "");
        Path src=temp.resolve("src"),out=temp.resolve("classes");Files.createDirectories(out);List<String> args=new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-d",out.toString()));
        add(src,args,"fixture/PublicMine.java",BOUNDARIES.replace("TOOL_METHODS",methods).replace("OPS_METHODS",ops));add(src,args,"fixture/MineBlockTaskRecord.java",record);
        add(src,args,"fixture/Schema.java",Files.readString(root.resolve("api/common/src/main/java/com/dwinovo/numen/agent/tool/Schema.java")).replace("package com.dwinovo.numen.agent.tool;","package fixture;"));
        URL gson=com.google.gson.Gson.class.getProtectionDomain().getCodeSource().getLocation();args.addAll(List.of("-classpath",Path.of(gson.toURI()).toString()));
        ByteArrayOutputStream errors=new ByteArrayOutputStream();assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,errors,errors,args.toArray(String[]::new)),"SETUP: "+errors);
        loader=new URLClassLoader(new URL[]{out.toUri().toURL(),gson},ClassLoader.getPlatformClassLoader());fixture=loader.loadClass("fixture.PublicMine");
    }
    static void add(Path p,List<String>a,String n,String s)throws Exception{MineHarvestRecoveryConsumerTest.add(p,a,n,s);}
    @AfterAll static void close()throws Exception{if(loader!=null)loader.close();}
    @SuppressWarnings("unchecked") static Map<String,Object> run(String bounds)throws Exception{return (Map<String,Object>)fixture.getMethod("run",String.class).invoke(null,bounds);}
    @Test void validTargetBoundsReachActualCreatedRecord()throws Exception{var r=run("valid");assertEquals(1,r.get("dispatch"));assertEquals(true,r.get("inside"));assertEquals(false,r.get("outside"));assertEquals(4,r.get("count"));}
    @Test void legacyInputKeepsUnboundedTask()throws Exception{var r=run("absent");assertEquals(1,r.get("dispatch"));assertEquals(true,r.get("outside"));}
    @Test void nullBoundsKeepUnboundedTask()throws Exception{var r=run("null");assertEquals(1,r.get("dispatch"));assertEquals(true,r.get("outside"));}
    @Test void publishedSchemaDescribesOptionalOreBounds()throws Exception{var r=run("valid");assertEquals(true,r.get("schemaBounds"));assertEquals(false,r.get("schemaRequired"));}
    @ParameterizedTest @ValueSource(strings={"missing","fraction","string","boolean","overflow","extra","array","memberNull","reversed","empty","horizontal","vertical"})
    void malformedBoundsNeverDispatch(String s)throws Exception{var r=run(s);assertEquals(0,r.get("dispatch"),s);assertFalse(((String)r.get("error")).isBlank(),s);}
    static final String BOUNDARIES="""
        package fixture;
        import java.util.*;import java.util.function.Consumer;import com.google.gson.*;
        record BlockPos(int x,int y,int z){int getX(){return x;}int getY(){return y;}int getZ(){return z;}}
        class Block{}
        class TaskRecord{TaskRecord(String t,String c,long d){}public String describe(){return "";}}
        class ToolContext{long deadline(long n){return n;}String toolCallId(){return "call";}}
        class ToolParse{static Set<Block> parseBlocks(List<String> ids){return Set.of(new Block());}}
        class BlockActionOps{
            static final int MAX_COUNT=256;static final long MIN_TIMEOUT_TICKS=1200,TICKS_PER_BLOCK=600;
            static String labelFor(Set<Block> b){return "ore";}
        OPS_METHODS
        }
        public class PublicMine{
            static class NumenPlayer{}
            static class ToolArgs{static <T>T fromJson(JsonObject a,Class<T> t){return new Gson().fromJson(a,t);}}
            BlockActionOps impl=new BlockActionOps();MineBlockTaskRecord captured;int dispatch;
            ToolContext ctx(String id,NumenPlayer p){return new ToolContext();}
            void setTask(NumenPlayer p,TaskRecord r,JsonObject args,Consumer<String> reply){dispatch++;captured=(MineBlockTaskRecord)r;}
        TOOL_METHODS
            public static Map<String,Object> run(String scenario){
                JsonObject a=new JsonObject();JsonArray ids=new JsonArray();ids.add("minecraft:iron_ore");a.add("block_ids",ids);a.addProperty("count",4);
                JsonObject bounds=new JsonObject();for(String k:List.of("x1","y1","z1"))bounds.addProperty(k,0);for(String k:List.of("x2","y2","z2"))bounds.addProperty(k,2);
                switch(scenario){
                    case "absent" -> {}
                    case "null" -> a.add("target_bounds",JsonNull.INSTANCE);
                    case "missing" -> bounds.remove("z2");
                    case "reversed" -> bounds.addProperty("x1",3);
                    case "empty" -> bounds=new JsonObject();
                    case "horizontal" -> bounds.addProperty("x2",30000001);
                    case "vertical" -> bounds.addProperty("y2",2147483648L);
                    case "fraction" -> bounds.addProperty("x1",0.5);
                    case "string" -> bounds.addProperty("x1","0");
                    case "boolean" -> bounds.addProperty("x1",true);
                    case "overflow" -> bounds.addProperty("x1",2147483648L);
                    case "extra" -> bounds.addProperty("radius",3);
                    case "memberNull" -> bounds.add("x1",JsonNull.INSTANCE);
                    case "array","valid" -> {}
                    default -> throw new IllegalArgumentException(scenario);
                }
                if(!scenario.equals("absent")&&!scenario.equals("null"))a.add("target_bounds",scenario.equals("array")?new JsonArray():bounds);
                PublicMine tool=new PublicMine();String error="";try{tool.onServerCall("call",a,new NumenPlayer(),s->{});}catch(IllegalArgumentException e){error=e.getMessage();}
                Map<String,Object> schema=tool.parameterSchema();Map<?,?> properties=(Map<?,?>)schema.get("properties");
                return Map.of("dispatch",tool.dispatch,"inside",tool.captured!=null&&tool.captured.containsTarget(new BlockPos(1,1,1)),
                    "outside",tool.captured!=null&&tool.captured.containsTarget(new BlockPos(9,9,9)),"count",tool.captured==null?0:tool.captured.count,"error",error,
                    "schemaBounds",properties.containsKey("target_bounds"),"schemaRequired",((List<?>)schema.get("required")).contains("target_bounds"));
            }
        }
        """;
}
