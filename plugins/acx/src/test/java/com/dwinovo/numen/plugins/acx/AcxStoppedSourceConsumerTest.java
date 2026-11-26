package com.dwinovo.numen.plugins.acx;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real AC stop hook and projection read path; MC, event bus and WOS source are boundaries, not a game run. */
class AcxStoppedSourceConsumerTest {
    @TempDir Path temp;

    @Test void stoppedQueuedRunCannotReadAndFreshBodyConsumesCurrentSource() throws Exception {
        assertEquals(List.of("SUCCESS", "world-new", "session-new", 17, "value-new", 0, 1), run(false));
    }

    @Test void stoppedCheckpointKeepsOriginalRecordAndFreshBodyConsumesCurrentSource() throws Exception {
        assertEquals(List.of("SUCCESS", "world-new", "session-new", 17, "value-new", 1, 1), run(true));
    }

    private List<?> run(boolean checkpoint) throws Exception {
        // The reused compiler owns static slots: preserve any existing suite loader and restore them per case.
        synchronized (AcxCompanionBridgeConsumerTest.class) {
            var previousTemp = AcxCompanionBridgeConsumerTest.temp;
            var previousLoader = AcxCompanionBridgeConsumerTest.loader;
            var previousHarness = AcxCompanionBridgeConsumerTest.harness;
            AcxCompanionBridgeConsumerTest.temp = temp;
            AcxCompanionBridgeConsumerTest.loader = null;
            AcxCompanionBridgeConsumerTest.harness = null;
            try {
                AcxCompanionBridgeConsumerTest.compileConsumers();
                compileProjectionConsumer();
                return (List<?>) AcxCompanionBridgeConsumerTest.loader
                        .loadClass("com.dwinovo.numen.plugins.acx.AcxStoppedSourceHarness")
                        .getMethod("run", boolean.class).invoke(null, checkpoint);
            } finally {
                try { AcxCompanionBridgeConsumerTest.close(); }
                finally {
                    AcxCompanionBridgeConsumerTest.temp = previousTemp;
                    AcxCompanionBridgeConsumerTest.loader = previousLoader;
                    AcxCompanionBridgeConsumerTest.harness = previousHarness;
                }
            }
        }
    }

    private void compileProjectionConsumer() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("plugins/acx"))) root = root.getParent();
        assertNotNull(root, "SETUP ERROR: source root missing");
        var args = new ArrayList<String>(List.of("-encoding", "UTF-8", "-proc:none", "-classpath",
                temp + File.pathSeparator + Path.of(Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI()),
                "-sourcepath", temp.toString(), "-d", temp.toString()));
        try (var files = Files.list(root.resolve("api/common/src/main/java/com/dwinovo/numen/api/observation"))) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> args.add(p.toString()));
        }
        for (String name : List.of("model/Topic", "observation/ObservationPayload", "observation/payload/SelfPayload"))
            args.add(root.resolve("world-state-core/src/main/java/com/dwinovo/numen/worldstate/core/" + name + ".java").toString());
        args.add(root.resolve("plugins/worldstate/src/main/java/com/dwinovo/numen/plugins/worldstate/SelfObservationProjection.java").toString());
        AcxCompanionBridgeConsumerTest.add(args, "com.dwinovo.numen.plugins.acx.AcxStoppedSourceHarness", HARNESS);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "SETUP ERROR: JDK compiler missing");
        var diagnostics = new ByteArrayOutputStream();
        assertEquals(0, compiler.run(null, diagnostics, diagnostics, args.toArray(String[]::new)),
                diagnostics.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static final String HARNESS = """
        package com.dwinovo.numen.plugins.acx;
        import java.util.*;
        import com.google.gson.*;
        import com.dwinovo.numen.acx.api.*;
        import com.dwinovo.numen.acx.core.*;
        import com.dwinovo.numen.api.NumenPlugins;
        import com.dwinovo.numen.api.observation.*;
        import com.dwinovo.numen.agent.tool.NumenTool;
        import com.dwinovo.numen.entity.NumenPlayer;
        import com.dwinovo.numen.plugins.worldstate.SelfObservationProjection;
        import net.neoforged.neoforge.common.NeoForge;
        public class AcxStoppedSourceHarness {
            static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
            static class Source implements WorldObservationService {
                final String world, session, value, target;
                final long revision;
                int reads;
                Source(String world, String session, long revision, String value, String target) {
                    this.world=world; this.session=session; this.revision=revision; this.value=value; this.target=target;
                }
                public Response read(Request request) {
                    check(request.consumer().equals("acx-stop-test") && request.dimension().equals("dim")
                            && request.topic().equals("self") && request.selector().equals(Map.of("target",target)),
                            "wrong projection read request: " + request);
                    reads++;
                    long now=System.currentTimeMillis();
                    return new Response(Status.OK,"self",value,revision,Coverage.FULL,"",
                            List.of(Item.of(Map.of("value",value))),revision,
                            new ObservationMetadata(world,session,"dim",target.substring(7),"boundary-source","self-key",
                                    revision,now,ObservationMetadata.Freshness.CURRENT,"SERVER_LOADED",now+3000));
                }
            }
            static void start(BindingHarness.Fixture f, NumenPlayer body, boolean ready) {
                var args=new Gson().toJsonTree(Map.of("ac_name","binding","input",Map.of("ready",ready))).getAsJsonObject();
                var reply=JsonParser.parseString(BindingHarness.request(new AcxFacadeTools.Execute(f.plugin),args,body)).getAsJsonObject();
                check(reply.get("success").getAsBoolean(),"execute not accepted: " + reply);
                f.run=reply.getAsJsonObject("data").get("run_id").getAsString();
            }
            public static List<Object> run(boolean checkpoint) {
                try {
                    new AcxMod();
                    var f=new BindingHarness.Fixture(true,(AcxPlugin)NumenPlugins.registered);
                    String target="player:"+f.a.getUUID();
                    var sourceOld=new Source("world-old","session-old",3,"value-old",target);
                    var sourceNew=new Source("world-new","session-new",17,"value-new",target);
                    WorldObservationService[] current={sourceOld};
                    NumenPlayer[] expectedBody={f.a};
                    var registry=new AcxRegistry();
                    NumenTool tool=new NumenTool() {
                        public String name(){return "world_state";}
                        public Map<String,Object> parameterSchema(){return Map.of("type","object","required",List.of("action","topic"),
                                "properties",Map.of("action",Map.of("type","string"),"topic",Map.of("type","string")));}
                        public void onServerCall(String call,JsonObject args,NumenPlayer body,java.util.function.Consumer<String> reply) {
                            check(body==expectedBody[0],"wrong original/replacement body reached tool");
                            check(args.get("action").getAsString().equals("read") && args.get("topic").getAsString().equals("self"),
                                    "wrong world_state action/topic");
                            reply.accept(SelfObservationProjection.toolReply(args.get("action").getAsString(),
                                    new WorldObservationService.Request("acx-stop-test","dim",args.get("topic").getAsString(),
                                            Map.of("target","player:"+body.getUUID()),3000,1,0),current[0]));
                        }
                    };
                    AcxHostBridge.registerAll(registry,List.of(new NumenToolBridgeX(tool,f.plugin,1000)),NumenToolBridgeX.probeFor(f.plugin));
                    var steps=new ArrayList<Map<String,Object>>();
                    steps.add(Map.of("id","read","block","world_state","params",Map.of("action","read","topic","self")));
                    if(checkpoint) steps.add(Map.of("id","ready","block","guard","params",
                            Map.of("condition",Map.of("field","$input.ready","op","==","value",true))));
                    steps.add(Map.of("id","bind","block","set","params",Map.of("world","$read.metadata.worldId",
                            "session","$read.metadata.serverSession","revision","$read.metadata.revision","value","$read.snapshotId")));
                    var definition=AcxLoader.parseJson(new Gson().toJson(Map.of("name","binding","version","1","steps",steps)),registry);
                    f.plugin.sessions=new AcxSessionManager(AcxRunner.builder().tools(registry).build(),f.plugin.queue::add);
                    f.plugin.facade=new AcxFacade(f.plugin.sessions,name->definition,registry,null);
                    if(checkpoint) start(f,f.a,false); else f.start();
                    var old=f.plugin.sessions.session(f.run);
                    String oldRun=f.run;
                    var oldArgs=f.status();
                    if(checkpoint) f.drain();
                    else check(old.isRunning() && f.plugin.queue.size()==1 && old.record()==null,"old run was not queued");
                    AcxRunRecord saved=old.record();
                    check(sourceOld.reads==(checkpoint?1:0) && sourceNew.reads==0,"unexpected pre-stop source reads");
                    if(checkpoint) check(saved!=null && saved.status()==AcxStatus.PAUSED && saved.completedStepIndex()==1
                            && saved.currentStepId().equals("ready") && Boolean.FALSE.equals(saved.input().get("ready")),
                            "old run did not pause at guard checkpoint 1");
                    NeoForge.EVENT_BUS.fireStopped();
                    check(old.isBodyInvalidated(),"real stop listener did not invalidate original session");
                    current[0]=sourceNew;
                    NumenPlayer replacement=new NumenPlayer("A");
                    check(replacement!=f.a && replacement.getUUID().equals(f.a.getUUID()),"same UUID replacement required");
                    expectedBody[0]=replacement;
                    if(checkpoint) start(f,replacement,true); else f.start(replacement);
                    check(!f.run.equals(oldRun),"fresh run reused old id");
                    var fresh=f.plugin.sessions.session(f.run);
                    check(!fresh.isBodyInvalidated(),"fresh body was invalidated");
                    oldArgs.add("input",new Gson().toJsonTree(Map.of("ready",true)));
                    int queued=f.plugin.queue.size();
                    var resumed=JsonParser.parseString(BindingHarness.request(new AcxFacadeTools.Resume(f.plugin),oldArgs,replacement)).getAsJsonObject();
                    check(!resumed.get("success").getAsBoolean() && f.plugin.queue.size()==queued,"old resume was accepted/scheduled");
                    check(sourceOld.reads==(checkpoint?1:0) && sourceNew.reads==0,"rejected resume read a source");
                    if(checkpoint) check(old.record()==saved,"stop/resume replaced original paused record");
                    f.drain();
                    check(sourceOld.reads==(checkpoint?1:0) && sourceNew.reads==1,"retired queue or fresh run read wrong source");
                    if(checkpoint) check(old.record()==saved,"fresh execution changed original record");
                    else check(old.record()!=null && old.record().completedStepIndex()==0 && old.record().vars().isEmpty(),
                            "retired queued run advanced or consumed metadata");
                    var record=fresh.record();
                    check(fresh.error()==null && record!=null && record.status()==AcxStatus.SUCCESS,"fresh execution failed");
                    return List.of(record.status().name(),record.vars().get("world"),record.vars().get("session"),
                            ((Number)record.vars().get("revision")).intValue(),record.vars().get("value"),sourceOld.reads,sourceNew.reads);
                } finally {
                    NeoForge.EVENT_BUS.clear();
                    NumenPlugins.registered=null;
                }
            }
        }
        """;
}
