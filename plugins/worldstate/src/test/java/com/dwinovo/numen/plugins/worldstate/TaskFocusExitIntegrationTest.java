package com.dwinovo.numen.plugins.worldstate;

import org.junit.jupiter.api.Test;
import javax.tools.ToolProvider;
import java.lang.invoke.MethodHandles;
import java.nio.file.*;
import java.util.Comparator;

/** Reuses the isolated real-source slot fixture. Minecraft, records and factory are stubs;
 * this is not a game or scheduler test. Each exit runs in a fresh fixture/classloader. */
class TaskFocusExitIntegrationTest {
    @Test void deathReleasesLease() throws Exception { runFixture(false); }
    @Test void replacementReleasesOldAndRetainsNewLease() throws Exception { runFixture(true); }
    public static void main(String[] args) throws Exception { runFixture(false); runFixture(true); }

    static void runFixture(boolean replacement) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        String fixturePath = "plugins/worldstate/src/test/java/com/dwinovo/numen/plugins/worldstate/TaskFocusSlotIntegrationTest.java";
        while (root != null && !Files.exists(root.resolve(fixturePath))) root = root.getParent();
        if (root == null) throw new IllegalStateException("repository root not found");
        String generatedName = replacement ? "TaskFocusReplacementFixtureGenerated" : "TaskFocusDeathFixtureGenerated";
        String source = replaceOnce(Files.readString(root.resolve(fixturePath)), "TaskFocusSlotIntegrationTest", generatedName);
        String scenario = replacement ? """
            var oldPin=pins.active(id,"world","new",System.currentTimeMillis()).get(0).id();
            var next=new TaskRecord(); next.selection=new TaskRecord.Selection(id.toString(),"skill:demo","v1",digest,newStamp);
            com.dwinovo.numen.task.BrainFixture.replace(body,task,next);
            check(pins.active(id,"world","new",System.currentTimeMillis()).isEmpty(),"replacement must release old lease before reconciling new task");
            com.dwinovo.numen.task.BrainFixture.reconcile(body,next);
            var newPins=pins.active(id,"world","new",System.currentTimeMillis());
            check(newPins.size()==1 && !newPins.get(0).id().equals(oldPin),"new task must retain a distinct lease");
            com.dwinovo.numen.task.BrainFixture.reconcile(body,next);
            check(pins.active(id,"world","new",System.currentTimeMillis()).size()==1 && pins.active(id,"world","new",System.currentTimeMillis()).get(0).id().equals(newPins.get(0).id()),"new lease must remain stable");
            """ : """
            com.dwinovo.numen.task.BrainFixture.die(body,task);
            check(pins.active(id,"world","new",System.currentTimeMillis()).isEmpty(),"death must release old lease");
            """;
        source = replaceOnce(source,
            "com.dwinovo.numen.task.BrainFixture.preempt(body,task); check(pins.active(id,\"world\",\"new\",System.currentTimeMillis()).size()==1,\"preemption must retain pin\"); com.dwinovo.numen.task.BrainFixture.finish(task); check(pins.active(id,\"world\",\"new\",System.currentTimeMillis()).isEmpty(),\"brain finish releases real pin\");",
            scenario);
        source = replaceOnce(source,
            "public static void finish(TaskRecord rec){rec.setState(TaskState.SUCCESS); brain.current.settleIfTerminal(); if(!brain.current.isEmpty()) throw new AssertionError(\"terminal slot not cleared\"); brain.current.settleIfTerminal();}",
            """
            public static void die(com.dwinovo.numen.entity.NumenPlayer body,TaskRecord rec){
              if(brain.current.record()!=rec || rec.getState()!=TaskState.RUNNING) throw new AssertionError("death setup");
              brain.current.dropNoResult(body);
              if(rec.getState()!=TaskState.CANCELLED || !brain.current.isEmpty() || stops!=0) throw new AssertionError("death must cancel and clear without stopping task");
            }
            public static void replace(com.dwinovo.numen.entity.NumenPlayer body,TaskRecord old,TaskRecord next){
              if(brain.current.record()!=old || old.getState()!=TaskState.RUNNING) throw new AssertionError("replacement setup");
              brain.current.put(body,next);
              if(old.getState()!=TaskState.CANCELLED || brain.current.record()!=next || next.getState()!=TaskState.RUNNING || stops!=1 || reason!=Task.StopReason.REPLACED) throw new AssertionError("real put must replace running task");
            }
            """);
        Path tmp = Files.createTempDirectory("focus-exit-wrapper-");
        try {
            Path file = tmp.resolve(generatedName + ".java");
            Files.writeString(file, source);
            var compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) throw new AssertionError("JDK compiler required");
            if (compiler.run(null, null, null, "-encoding", "UTF-8", "-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", tmp.toString(), file.toString()) != 0)
                throw new AssertionError("fixture wrapper compilation failed");
            byte[] bytes = Files.readAllBytes(tmp.resolve("com/dwinovo/numen/plugins/worldstate/" + generatedName + ".class"));
            var generated = MethodHandles.lookup().defineClass(bytes);
            generated.getDeclaredMethod("runFixture").invoke(null);
        } finally {
            try (var paths = Files.walk(tmp)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }

    private static String replaceOnce(String source, String before, String after) {
        int at = source.indexOf(before);
        if (at < 0 || source.indexOf(before, at + before.length()) >= 0)
            throw new AssertionError("slot fixture changed: expected one occurrence of " + before);
        return source.substring(0, at) + after + source.substring(at + before.length());
    }
}
