package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.api.WorldContextStamp;
import com.dwinovo.numen.monitor.MonitoringJournal;
import com.dwinovo.numen.task.TaskCompletionText;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.task.TaskState;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Test-only bridge into verbatim original CompanionBrain result shipping methods.
 * The caller supplies an already external/async original TaskRecord, including its real result
 * and identity token, and owns journal/root isolation and writer completion. This helper does
 * not construct, rebind, settle or change that record, configure the singleton, or await IO.
 *
 * Only these complete methods are sliced from api/common/src/main/java/com/dwinovo/numen/task/
 * CompanionBrain.java: shipResults (356-413), journalSyncOutcome (433-445), and
 * journalTaskOutcome (447-469), at the reviewed source revision. No producer algorithm is copied.
 * Generated sources/classes live only in a disposable test temp directory. The outer Brain
 * lifecycle is intentionally absent; this is method-level producer coverage, not a running MC.
 */
public final class OriginalAsyncJournalProducer {
    private static final String BRAIN = "com.dwinovo.numen.task.CompanionBrain";
    private static final String SOURCE = "api/common/src/main/java/com/dwinovo/numen/task/CompanionBrain.java";

    private OriginalAsyncJournalProducer() {}

    public static void publish(UUID companion, TaskRecord originalTask) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(SOURCE))) root = root.getParent();
        if (root == null) throw new AssertionError("Repository source root required for " + SOURCE);
        publishTaskOutcome(root, companion, originalTask);
    }

    public static void publishTaskOutcome(Path sourceRoot, UUID companion, TaskRecord task) throws Exception {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(companion, "companion");
        Objects.requireNonNull(task, "task");
        if (!task.isAsync() || !task.isExternalCall()) {
            throw new IllegalArgumentException("Caller must supply the original external async task; no fields are repaired");
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new AssertionError("JDK ToolProvider compiler required");
        String original = Files.readString(sourceRoot.resolve(SOURCE), StandardCharsets.UTF_8);
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(BRAIN, """
                package com.dwinovo.numen.task;
                import com.dwinovo.numen.entity.NumenPlayer;
                public final class CompanionBrain {
                    private final java.util.Deque<TaskRecord> outbox = new java.util.ArrayDeque<>();
                    public static void publish(java.util.UUID companion, TaskRecord originalTask) {
                        var brain = new CompanionBrain();
                        brain.outbox.addLast(originalTask);
                        brain.shipResults(new NumenPlayer(companion));
                    }
                """
                + method(original, "private void shipResults(NumenPlayer companion)") + "\n"
                + method(original, "private void journalSyncOutcome(NumenPlayer companion, TaskRecord rec, TaskResult result)") + "\n"
                + method(original, "private void journalTaskOutcome(NumenPlayer companion, TaskRecord rec, String status, String msg, TaskResult result)") + "\n}");
        sources.put("com.dwinovo.numen.entity.NumenPlayer", """
                package com.dwinovo.numen.entity;
                public final class NumenPlayer {
                    private final java.util.UUID id;
                    public NumenPlayer(java.util.UUID id) { this.id = id; }
                    public java.util.UUID getUUID() { return id; }
                    public net.minecraft.server.level.ServerPlayer resolveOwnerPlayer() { return null; }
                }
                """);
        sources.put("net.minecraft.server.level.ServerPlayer",
                "package net.minecraft.server.level; public final class ServerPlayer {}");
        sources.put("com.dwinovo.numen.event.NumenEvents", """
                package com.dwinovo.numen.event;
                public final class NumenEvents {
                    public static void taskFinished(com.dwinovo.numen.entity.NumenPlayer p,
                            String id, String tool, String status, String msg) {
                        throw new AssertionError("External async task must not emit a built-in event");
                    }
                }
                """);
        sources.put("com.dwinovo.numen.network.payload.TaskResultPayload", """
                package com.dwinovo.numen.network.payload;
                public final class TaskResultPayload {
                    public TaskResultPayload(java.util.UUID id, String call, String json) {
                        throw new AssertionError("External async task must not create a sync packet");
                    }
                }
                """);
        sources.put("com.dwinovo.numen.platform.Services", """
                package com.dwinovo.numen.platform;
                public final class Services {
                    public static final Network NETWORK = new Network();
                    public static final class Network {
                        public void sendToPlayer(net.minecraft.server.level.ServerPlayer owner,
                                com.dwinovo.numen.network.payload.TaskResultPayload packet) {
                            throw new AssertionError("External async task must not send a sync packet");
                        }
                    }
                }
                """);
        // This logger is referenced only by the unexercised offline synchronous branch.
        sources.put("org.slf4j.LoggerFactory", """
                package org.slf4j;
                public final class LoggerFactory {
                    public static Log getLogger(Class<?> type) { throw new AssertionError("Unexpected sync branch"); }
                    public static final class Log { public void warn(String text, Object... args) {
                        throw new AssertionError("Unexpected sync branch");
                    } }
                }
                """);
        Path temp = Files.createTempDirectory("original-async-journal-producer-");
        try {
            Path src = Files.createDirectories(temp.resolve("src"));
            Path classes = Files.createDirectories(temp.resolve("classes"));
            List<String> args = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none",
                    "-implicit:none", "-sourcepath", src.toString(), "-classpath", classpath(),
                    "-d", classes.toString()));
            for (var entry : sources.entrySet()) {
                Path file = src.resolve(entry.getKey().replace('.', '/') + ".java");
                Files.createDirectories(file.getParent());
                Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
                args.add(file.toString());
            }
            ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
            int exit = compiler.run(null, diagnostics, diagnostics, args.toArray(String[]::new));
            if (exit != 0) throw new AssertionError("Verbatim original producer did not compile:\n"
                    + diagnostics.toString(StandardCharsets.UTF_8));
            Set<String> isolated = Set.copyOf(sources.keySet());
            try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()},
                    OriginalAsyncJournalProducer.class.getClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    synchronized (getClassLoadingLock(name)) {
                        boolean local = isolated.contains(name) || isolated.stream().anyMatch(n -> name.startsWith(n + "$"));
                        if (!local) return super.loadClass(name, resolve);
                        Class<?> type = findLoadedClass(name);
                        if (type == null) type = findClass(name);
                        if (resolve) resolveClass(type);
                        return type;
                    }
                }
            }) {
                // No child copies of the real task, result, token, or singleton are permitted.
                for (Class<?> type : List.of(TaskRecord.class, TaskResult.class, TaskState.class,
                        WorldContextStamp.class, WorldContextStamp.Token.class, MonitoringJournal.class,
                        TaskCompletionText.class)) {
                    if (loader.loadClass(type.getName()) != type) {
                        throw new AssertionError("Producer must share parent class: " + type.getName());
                    }
                }
                try {
                    loader.loadClass(BRAIN).getMethod("publish", UUID.class, TaskRecord.class)
                            .invoke(null, companion, task);
                } catch (InvocationTargetException failure) {
                    if (failure.getCause() instanceof Error error) throw error;
                    if (failure.getCause() instanceof Exception exception) throw exception;
                    throw failure;
                }
            }
        } finally {
            // Delete only this invocation's generated test cache, never repository sources.
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static String classpath() throws Exception {
        Set<String> entries = new LinkedHashSet<>();
        String configured = System.getProperty("java.class.path", "");
        if (!configured.isBlank()) entries.addAll(List.of(configured.split(java.util.regex.Pattern.quote(File.pathSeparator))));
        for (ClassLoader loader = OriginalAsyncJournalProducer.class.getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) {
                for (URL url : urls.getURLs()) if (url.getProtocol().equals("file")) entries.add(Path.of(url.toURI()).toString());
            }
        }
        // Gradle's test worker classpath property can contain only its launcher jar.
        for (Class<?> type : List.of(TaskRecord.class, TaskResult.class, TaskState.class,
                WorldContextStamp.class, MonitoringJournal.class, TaskCompletionText.class)) {
            var code = type.getProtectionDomain().getCodeSource();
            if (code == null) throw new AssertionError("No compiler classpath location for " + type.getName());
            entries.add(Path.of(code.getLocation().toURI()).toString());
        }
        return String.join(File.pathSeparator, entries);
    }

    /** Like the existing api dynamic fixture, retain the entire signature/body, fail on drift. */
    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0 || source.indexOf(signature, start + signature.length()) >= 0) {
            throw new AssertionError("Missing or ambiguous production method: " + signature);
        }
        int open = start + signature.length();
        while (open < source.length() && Character.isWhitespace(source.charAt(open))) open++;
        if (open == source.length() || source.charAt(open) != '{') {
            throw new AssertionError("Missing production body: " + signature);
        }
        // Ignore braces in comments/string/char literals rather than silently truncating a method.
        int depth = 1;
        char quote = 0;
        boolean lineComment = false, blockComment = false;
        for (int i = open + 1; i < source.length(); i++) {
            char c = source.charAt(i), next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (lineComment) { if (c == '\n' || c == '\r') lineComment = false; continue; }
            if (blockComment) { if (c == '*' && next == '/') { blockComment = false; i++; } continue; }
            if (quote != 0) { if (c == '\\') i++; else if (c == quote) quote = 0; continue; }
            if (c == '/' && next == '/') { lineComment = true; i++; continue; }
            if (c == '/' && next == '*') { blockComment = true; i++; continue; }
            if (c == '"' || c == '\'') { quote = c; continue; }
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(start, i + 1);
        }
        throw new AssertionError("Unterminated production method: " + signature);
    }
}
