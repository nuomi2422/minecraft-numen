package com.dwinovo.numen.plugins.acx;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Full source Schema/Sow/bridge/adapter/loader; only Minecraft host boundaries are fixtures.
 * The world boundary deliberately fails before any write: dispatch is not sowing success. */
class SowBridgeRegistrationConsumerTest {
    @TempDir Path work;
    @Test void shippedFarmPromisesSowingAndDispatchesItsParsedStepToTheRealPort() throws Exception { run("shipped"); }
    @Test void realSchemaRetainsRequiredBoundedRadiusThroughRegistration() throws Exception { run("schema"); }
    @Test void invalidRadiusNeverDispatchesTheRealSowTool() throws Exception { run("invalid"); }
    @Test void validRadiusUsesRegisteredPortButDoesNotInventSowingSuccess() throws Exception { run("valid"); }

    private void run(String mode) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/SowNearbyTool.java"))) root = root.getParent();
        assertNotNull(root, "repository root");
        Path src = work.resolve("sources"), out = work.resolve("classes");
        Files.createDirectories(out);
        Map<String,String> fixtures = new LinkedHashMap<>();
        fixtures.put("net.minecraft.world.level.Level", "package net.minecraft.world.level; public class Level {}");
        fixtures.put("net.minecraft.server.level.ServerLevel", """
                package net.minecraft.server.level;
                public class ServerLevel extends net.minecraft.world.level.Level {
                    public int reads, writes;
                    public boolean hasChunkAt(net.minecraft.core.BlockPos pos) { reads++; throw new IllegalStateException("WORLD_BOUNDARY_NOT_SIMULATED"); }
                    public net.minecraft.world.level.block.state.BlockState getBlockState(net.minecraft.core.BlockPos pos) { throw new AssertionError("not simulated"); }
                    public boolean setBlock(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state, int flags) { writes++; throw new AssertionError("world writes forbidden"); }
                }
                """);
        fixtures.put("net.minecraft.core.BlockPos", """
                package net.minecraft.core;
                public class BlockPos {
                    public int getX(){return 0;} public int getY(){return 64;} public int getZ(){return 0;}
                    public BlockPos above(){return new BlockPos();}
                    public static class MutableBlockPos extends BlockPos { public void set(int x,int y,int z){} }
                }
                """);
        fixtures.put("net.minecraft.resources.ResourceLocation", "package net.minecraft.resources; public record ResourceLocation(String id) { public static ResourceLocation parse(String id){return new ResourceLocation(id);} public String toString(){return id;} }");
        fixtures.put("net.minecraft.world.item.Item", "package net.minecraft.world.item; public class Item {}");
        fixtures.put("net.minecraft.world.item.ItemStack", "package net.minecraft.world.item; public class ItemStack { public boolean isEmpty(){return true;} public Item getItem(){return null;} public int getCount(){return 0;} public boolean is(Item item){return false;} public void shrink(int n){throw new AssertionError();} }");
        fixtures.put("net.minecraft.world.level.block.Block", "package net.minecraft.world.level.block; public class Block { public static final int UPDATE_ALL=3; public net.minecraft.world.level.block.state.BlockState defaultBlockState(){return new net.minecraft.world.level.block.state.BlockState();} }");
        fixtures.put("net.minecraft.world.level.block.Blocks", "package net.minecraft.world.level.block; public class Blocks { public static final Block FARMLAND=new Block(); }");
        fixtures.put("net.minecraft.world.level.block.state.BlockState", "package net.minecraft.world.level.block.state; public class BlockState { public boolean is(net.minecraft.world.level.block.Block block){return false;} public boolean isAir(){return false;} }");
        fixtures.put("net.minecraft.core.registries.BuiltInRegistries", """
                package net.minecraft.core.registries;
                public class BuiltInRegistries {
                    public static final Registry<net.minecraft.world.item.Item> ITEM=new Registry<>();
                    public static final Registry<net.minecraft.world.level.block.Block> BLOCK=new Registry<>();
                    public static class Registry<T> {
                        public T get(net.minecraft.resources.ResourceLocation id){throw new AssertionError("empty inventory must not resolve a seed");}
                        public net.minecraft.resources.ResourceLocation getKey(T value){throw new AssertionError("empty inventory");}
                    }
                }
                """);
        fixtures.put("com.dwinovo.numen.entity.NumenPlayer", """
                package com.dwinovo.numen.entity;
                public class NumenPlayer {
                    public final net.minecraft.server.level.ServerLevel world=new net.minecraft.server.level.ServerLevel();
                    public int levelCalls;
                    public net.minecraft.world.level.Level level(){levelCalls++;return world;}
                    public java.util.UUID getUUID(){return new java.util.UUID(0,1);}
                    public net.minecraft.core.BlockPos blockPosition(){return new net.minecraft.core.BlockPos();}
                    public Inventory getInventory(){return new Inventory();}
                    public static class Inventory { public int getContainerSize(){return 0;} public net.minecraft.world.item.ItemStack getItem(int slot){throw new AssertionError("empty inventory");} }
                }
                """);
        fixtures.put("com.dwinovo.numen.agent.tool.ToolCall", "package com.dwinovo.numen.agent.tool; public class ToolCall {}");
        fixtures.put("com.dwinovo.numen.agent.tool.ServerToolTransport", "package com.dwinovo.numen.agent.tool; public class ServerToolTransport { public static void ship(ToolCall call){throw new AssertionError();} }");
        fixtures.put("com.dwinovo.numen.task.TaskRecord", "package com.dwinovo.numen.task; public class TaskRecord { public String publicId(){return java.util.UUID.randomUUID().toString();} public State getState(){return State.SUCCESS;} public enum State {SUCCESS} }");
        fixtures.put("com.dwinovo.numen.task.CompanionTickDispatcher", "package com.dwinovo.numen.task; public class CompanionTickDispatcher { public static TaskRecord currentTaskFor(java.util.UUID id){return null;} public static TaskRecord lastFinishedTaskFor(java.util.UUID id){return null;} public static TaskRecord finishedTaskFor(java.util.UUID id,String task){return null;} }");
        fixtures.put("net.minecraft.server.MinecraftServer", "package net.minecraft.server; public class MinecraftServer { public boolean isSameThread(){return true;} public void execute(Runnable job){job.run();} }");
        fixtures.put("net.neoforged.neoforge.server.ServerLifecycleHooks", "package net.neoforged.neoforge.server; public class ServerLifecycleHooks { public static net.minecraft.server.MinecraftServer getCurrentServer(){return null;} }");
        fixtures.put("com.dwinovo.numen.plugins.acx.AcxPlugin", """
                package com.dwinovo.numen.plugins.acx;
                public class AcxPlugin {
                    public final com.dwinovo.numen.entity.NumenPlayer self=new com.dwinovo.numen.entity.NumenPlayer();
                    public com.dwinovo.numen.entity.NumenPlayer currentCompanion(){return self;}
                    public AcxHostGate gate(){return new AcxHostGate(100);}
                }
                """);
        fixtures.put("com.dwinovo.numen.plugins.rdd.SowFactory", "package com.dwinovo.numen.plugins.rdd; public class SowFactory { public static com.dwinovo.numen.agent.tool.NumenTool create(){return new SowNearbyTool();} }");
        fixtures.put("com.dwinovo.numen.plugins.acx.SowFixture", CONSUMER);
        List<String> args = new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-classpath",
                Path.of(Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                "-sourcepath",src + java.io.File.pathSeparator + root.resolve("plugins/acx/src/main/java"),"-d",out.toString()));
        for (var entry : fixtures.entrySet()) {
            Path file=src.resolve(entry.getKey().replace('.','/')+".java"); Files.createDirectories(file.getParent());
            Files.writeString(file,entry.getValue()); args.add(file.toString());
        }
        for (String file : List.of(
                "api/common/src/main/java/com/dwinovo/numen/agent/tool/Schema.java",
                "api/common/src/main/java/com/dwinovo/numen/agent/tool/NumenTool.java",
                "ai/src/main/java/com/dwinovo/numen/agent/provider/IToolSpec.java",
                "api/common/src/main/java/com/dwinovo/numen/task/TaskResult.java",
                "plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/SowRules.java",
                "plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/SowNearbyTool.java",
                "plugins/acx/src/main/java/com/dwinovo/numen/plugins/acx/AcxHostGate.java",
                "plugins/acx/src/main/java/com/dwinovo/numen/plugins/acx/NumenToolBridgeX.java",
                "plugins/acx/src/main/java/com/dwinovo/numen/acx/core/AcxHostBridge.java",
                "plugins/acx/src/main/java/com/dwinovo/numen/acx/core/AcxLoader.java",
                "plugins/acx/src/main/java/com/dwinovo/numen/acx/core/PortToolAdapter.java")) args.add(root.resolve(file).toString());
        assertNotNull(ToolProvider.getSystemJavaCompiler());
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)),"SETUP: full production sources must compile before assertions");
        try (var loader = new URLClassLoader(new java.net.URL[]{out.toUri().toURL(),Gson.class.getProtectionDomain().getCodeSource().getLocation()}, ClassLoader.getPlatformClassLoader())) {
            for(String type:List.of("com.dwinovo.numen.agent.tool.Schema","com.dwinovo.numen.plugins.rdd.SowNearbyTool","com.dwinovo.numen.plugins.acx.NumenToolBridgeX","com.dwinovo.numen.acx.core.PortToolAdapter","com.dwinovo.numen.acx.core.AcxLoader")) assertSame(loader,loader.loadClass(type).getClassLoader());
            try { loader.loadClass("com.dwinovo.numen.plugins.acx.SowFixture").getMethod("run",String.class,String.class).invoke(null,mode,Files.readString(root.resolve("plugins/acx/src/main/resources/acx-lib/stable/farm_maintain_loop.ac"))); }
            catch(java.lang.reflect.InvocationTargetException failure){throw new AssertionError("consumer "+mode,failure.getCause());}
        }
    }

    private static final String CONSUMER = """
            package com.dwinovo.numen.plugins.acx;
            import java.util.*;
            import com.dwinovo.numen.acx.api.*;
            import com.dwinovo.numen.acx.core.*;
            import com.dwinovo.numen.agent.tool.NumenTool;
            public class SowFixture {
                static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
                static class Registry implements AcxToolRegistry {
                    Map<String,AcxTool> tools=new LinkedHashMap<>();
                    public Optional<AcxTool> find(String n){return Optional.ofNullable(tools.get(n));}
                    public boolean contains(String n){return tools.containsKey(n);}
                    public Collection<String> names(){return tools.keySet();}
                    public void register(AcxTool tool){if(tools.putIfAbsent(tool.name(),tool)!=null)throw new AssertionError("duplicate");}
                }
                public static void run(String mode,String shippedJson){
                    NumenTool sow=com.dwinovo.numen.plugins.rdd.SowFactory.create();
                    var plugin=new AcxPlugin();
                    var port=new NumenToolBridgeX(sow,plugin,100);
                    var registry=new Registry();
                    var report=AcxHostBridge.registerAll(registry,List.of(port),null);
                    require(report.registered().equals(List.of("sow_nearby")),"correct real port registration");
                    var adapter=(PortToolAdapter)registry.find("sow_nearby").orElseThrow();
                    require(adapter.port()==port,"registration must preserve original port");
                    var raw=sow.parameterSchema();
                    require(((List<?>)raw.get("required")).equals(List.of("radius")),"real Schema required radius");
                    require(port.schema().required().equals(Set.of("radius")),"bridge dropped required");
                    var radius=port.schema().params().get("radius");
                    require(radius.type()==AcxPortSchema.Type.INTEGER && radius.min()==1d && radius.max()==32d,"bridge dropped real bounds");
                    if(mode.equals("schema"))return;
                    if(mode.equals("shipped")){
                        var declaration=com.google.gson.JsonParser.parseString(shippedJson).getAsJsonObject();
                        require(declaration.get("description").getAsString().contains("sow_nearby"),"actual shipped resource must promise sowing");
                        var definition=AcxLoader.parseJson(shippedJson,registry);
                        var blocks=definition.steps().stream().map(step->step.block()).toList();
                        require(blocks.equals(List.of("get_self_status", "farm_nearby", "sow_nearby", "collect_items", "get_self_status")), "SHIPPED_ORDER_OR_REQUIRED_STEP_MISMATCH: " + blocks);
                        var sowSteps=definition.steps().stream().flatMap(step->step.flattenSelfAndDescendants().stream()).filter(step->"sow_nearby".equals(step.block())).toList();
                        require(!sowSteps.isEmpty(),"SHIPPED_SCENE_OMITS_SOW: declaration promises sowing but parsed steps omit it");
                        for(var step:sowSteps){
                            var registered=registry.find(step.block()).orElseThrow();
                            require(registered==adapter,"shipped step must resolve to registered real sow adapter");
                            int before=plugin.self.levelCalls;
                            var result=registered.execute(step.params(),null);
                            require(plugin.self.levelCalls==before+1 && plugin.self.world.reads>0,"shipped radius must pass actual adapter validation");
                            require(result.status()==AcxStatus.FAIL && result.message().contains("WORLD_BOUNDARY_NOT_SIMULATED"),"do not fabricate shipped sow success");
                            require(plugin.self.world.writes==0,"no world writes");
                        }
                        return;
                    }
                    List<Map<String,Object>> values=mode.equals("invalid")
                            ? List.of(Map.of(),Map.of("radius",0),Map.of("radius",33),Map.of("radius",1.5),Map.of("radius","bad"))
                            : List.of(Map.of("radius",1),Map.of("radius",32));
                    for(var params:values){
                        String json=new com.google.gson.Gson().toJson(Map.of("name","sow_test","steps",List.of(Map.of("id","seed","block","sow_nearby","params",params))));
                        var def=AcxLoader.parseJson(json,registry);
                        var step=def.steps().getFirst();
                        var tool=registry.find(step.block()).orElseThrow();
                        int before=plugin.self.levelCalls;
                        var result=tool.execute(step.params(),null);
                        if(mode.equals("invalid")){
                            require(result.status()==AcxStatus.FAIL && result.message().contains("参数校验失败"),"invalid args not rejected by adapter: "+result);
                            require(plugin.self.levelCalls==before && plugin.self.world.reads==0,"invalid args reached real Sow tool");
                        }else{
                            require(plugin.self.levelCalls==before+1 && plugin.self.world.reads>0,"legal radius did not dispatch to ServerLevel-compatible Sow");
                            require(result.status()==AcxStatus.FAIL && result.message().contains("WORLD_BOUNDARY_NOT_SIMULATED"),"fixture must not invent sowing success: "+result);
                        }
                        require(plugin.self.world.writes==0,"no simulated crop mutation allowed");
                    }
                }
            }
            """;
}
