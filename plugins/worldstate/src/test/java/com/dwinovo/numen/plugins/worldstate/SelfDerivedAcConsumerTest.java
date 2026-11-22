package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.api.observation.*;
import com.dwinovo.numen.worldstate.core.observation.payload.SelfPayload;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.net.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Production SELF tool serialization -> exact bridge reply mapper -> Loader/PortAdapter/Runner.
 * Detached source service and port dispatch are fixtures; NOT full host/session/MC coverage. */
class SelfDerivedAcConsumerTest {
    @TempDir Path temp;
    private static Path root() {
        Path p=Path.of("").toAbsolutePath();
        while(p!=null && !Files.exists(p.resolve("plugins/acx/src/main/java"))) p=p.getParent();
        if(p==null) throw new IllegalStateException("source root missing"); return p;
    }
    private static String method(String source,String signature) {
        int start=source.indexOf(signature); if(start<0) throw new IllegalStateException(signature);
        int open=source.indexOf('{',start),depth=1,i=open+1;
        for(;depth>0 && i<source.length();i++) {char c=source.charAt(i); if(c=='{') depth++; else if(c=='}') depth--;}
        return source.substring(start,i);
    }
    private URLClassLoader compile() throws Exception {
        Path root=root(); var args=new ArrayList<String>(List.of("-encoding","UTF-8","-proc:none","-classpath",
                Path.of(Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),"-d",temp.toString()));
        Path ac=root.resolve("plugins/acx/src/main/java/com/dwinovo/numen/acx");
        for(String dir:List.of("api","core")) try(var files=Files.list(ac.resolve(dir))) {
            files.filter(p->p.toString().endsWith(".java")).forEach(p->args.add(p.toString()));
        }
        args.add(root.resolve("plugins/acx/src/main/java/com/dwinovo/numen/plugins/acx/AcxRegistry.java").toString());
        String bridge=Files.readString(root.resolve("plugins/acx/src/main/java/com/dwinovo/numen/plugins/acx/NumenToolBridgeX.java"));
        String mapper=method(bridge,"    private static Optional<Result> mapReply(");
        String harness="""
            import java.util.*;
            import com.google.gson.*;
            import com.dwinovo.numen.acx.api.*;
            import com.dwinovo.numen.acx.api.AcxToolPort.Result;
            import com.dwinovo.numen.acx.core.*;
            import com.dwinovo.numen.plugins.acx.AcxRegistry;
            public class DerivedAcHarness {
                private static final Gson GSON=new Gson();
            """+mapper+"""
                public static Map<String,Object> run(String json) {
                    AcxRegistry registry=new AcxRegistry();
                    AcxToolPort port=new AcxToolPort(){
                        public String name(){return "world_state";}
                        public AcxPortSchema schema(){return AcxPortSchema.builder().build();}
                        public Result invoke(Map<String,Object> params){return mapReply(json,"world_state").orElseThrow();}
                    };
                    AcxHostBridge.registerAll(registry,List.of(port),null);
                    String script=GSON.toJson(Map.of("name","derived_food","version","1","steps",List.of(
                        Map.of("id","read","block","world_state","params",Map.of()),
                        Map.of("id","known","block","guard","params",Map.of("condition",Map.of("field","$read.derived.foodState","op","==","value","KNOWN"))),
                        Map.of("id","bind","block","set","params",Map.of("food","$read.derived.foodCount","scope","$read.derived.scope")))));
                    var definition=AcxLoader.parseJson(script,registry);
                    var record=AcxRunner.builder().tools(registry).build().run(definition,Map.of());
                    return Map.of("status",record.status().name(),"vars",record.vars());
                }
            }
            """;
        Path file=temp.resolve("DerivedAcHarness.java"); Files.writeString(file,harness); args.add(file.toString());
        var diagnostics=new ByteArrayOutputStream();
        int exit=ToolProvider.getSystemJavaCompiler().run(null,diagnostics,diagnostics,args.toArray(String[]::new));
        assertEquals(0,exit,diagnostics.toString(java.nio.charset.StandardCharsets.UTF_8));
        return new URLClassLoader(new URL[]{temp.toUri().toURL()},getClass().getClassLoader());
    }
    private static WorldObservationService.Response sample(int count,Boolean edible,long until) {
        UUID id=UUID.fromString("00000000-0000-0000-0000-000000000006");
        var slots=new ArrayList<SelfPayload.Slot>(); for(int i=0;i<36;i++)slots.add(SelfPayload.Slot.empty(i));
        slots.set(0,new SelfPayload.Slot(0,"mod:meal",count,0,0,false,edible));
        var payload=new SelfPayload("dim",id,slots,null,null,0,20f,20f,6,0f,List.of(),null,null);
        return new WorldObservationService.Response(WorldObservationService.Status.OK,"self","fixture",1,WorldObservationService.Coverage.FULL,"",
                List.of(SelfObservationProjection.item(payload)),1,new ObservationMetadata("world","session","dim",id.toString(),"self","key",1,
                System.currentTimeMillis()-50,ObservationMetadata.Freshness.CURRENT,"SERVER_LOADED",until));
    }
    private static String reply(WorldObservationService.Response response) {
        WorldObservationService service=request->response;
        return SelfObservationProjection.toolReply("read",new WorldObservationService.Request("fixture","dim","self",Map.of(),0,1,0),service);
    }
    @Test void componentCountReachesActualAcVariableWithoutModelParsing() throws Exception {
        try(var loader=compile()) {
            var method=loader.loadClass("DerivedAcHarness").getMethod("run",String.class);
            var result=(Map<?,?>)method.invoke(null,reply(sample(3,true,System.currentTimeMillis()+3000)));
            assertEquals("SUCCESS",result.get("status")); var vars=(Map<?,?>)result.get("vars");
            assertEquals(3,((Number)vars.get("food")).intValue()); assertEquals("main36",vars.get("scope"));
        }
    }
    @Test void unknownAndExpiredNeverPassKnownGuardButConfirmedZeroDoes() throws Exception {
        try(var loader=compile()) {
            var method=loader.loadClass("DerivedAcHarness").getMethod("run",String.class);
            for(var input:List.of(sample(3,null,System.currentTimeMillis()+3000),sample(3,true,System.currentTimeMillis()-1))) {
                var result=(Map<?,?>)method.invoke(null,reply(input));
                assertEquals("PAUSED",result.get("status")); assertFalse(((Map<?,?>)result.get("vars")).containsKey("food"));
            }
            var zero=(Map<?,?>)method.invoke(null,reply(sample(0,true,System.currentTimeMillis()+3000)));
            assertEquals("SUCCESS",zero.get("status")); assertEquals(0,((Number)((Map<?,?>)zero.get("vars")).get("food")).intValue());
        }
    }
}
