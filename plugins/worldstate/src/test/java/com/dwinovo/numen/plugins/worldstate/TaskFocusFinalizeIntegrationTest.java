package com.dwinovo.numen.plugins.worldstate;

import org.junit.jupiter.api.Test;
import javax.tools.ToolProvider;
import java.lang.invoke.MethodHandles;
import java.nio.file.*;
import java.util.Comparator;

/** Reuses the slot integration's source-compilation fixture, changing only its scenario. */
class TaskFocusFinalizeIntegrationTest {
    @Test void finalizeReleasesPin() throws Exception { runFixture(); }
    public static void main(String[] args) throws Exception { runFixture(); }

    static void runFixture() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        String fixturePath = "plugins/worldstate/src/test/java/com/dwinovo/numen/plugins/worldstate/TaskFocusSlotIntegrationTest.java";
        while (root != null && !Files.exists(root.resolve(fixturePath))) root = root.getParent();
        if (root == null) throw new IllegalStateException("repository root not found");
        String source = Files.readString(root.resolve(fixturePath));
        source = replaceOnce(source, "TaskFocusSlotIntegrationTest", "TaskFocusFinalizeFixtureGenerated");
        source = replaceOnce(source,
            "com.dwinovo.numen.task.BrainFixture.preempt(body,task); check(pins.active(id,\"world\",\"new\",System.currentTimeMillis()).size()==1,\"preemption must retain pin\"); com.dwinovo.numen.task.BrainFixture.finish(task);",
            "com.dwinovo.numen.task.BrainFixture.finish(body,task);");
        source = replaceOnce(source,
            "public static void finish(TaskRecord rec){rec.setState(TaskState.SUCCESS); brain.current.settleIfTerminal(); if(!brain.current.isEmpty()) throw new AssertionError(\"terminal slot not cleared\"); brain.current.settleIfTerminal();}",
            "public static void finish(com.dwinovo.numen.entity.NumenPlayer body,TaskRecord rec){brain.finalizeActive(body); if(rec.getState()!=TaskState.CANCELLED || !brain.current.isEmpty()) throw new AssertionError(\"finalize must cancel and clear slot\"); brain.finalizeActive(body);}");
        Path tmp = Files.createTempDirectory("focus-finalize-wrapper-");
        try {
            Path file = tmp.resolve("TaskFocusFinalizeFixtureGenerated.java");
            Files.writeString(file, source);
            var compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) throw new AssertionError("JDK compiler required");
            if (compiler.run(null, null, null, "-encoding", "UTF-8", "-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", tmp.toString(), file.toString()) != 0)
                throw new AssertionError("fixture wrapper compilation failed");
            byte[] bytes = Files.readAllBytes(tmp.resolve("com/dwinovo/numen/plugins/worldstate/TaskFocusFinalizeFixtureGenerated.class"));
            var generated = MethodHandles.lookup().defineClass(bytes);
            var method = generated.getDeclaredMethod("runFixture");
            method.invoke(null);
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
