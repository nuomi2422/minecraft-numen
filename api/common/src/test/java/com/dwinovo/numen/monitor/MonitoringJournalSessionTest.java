package com.dwinovo.numen.monitor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 2026-11-06：tools/events 两份文件要能跨文件关联，靠的是每行信封里的进程级 session。
 * 旧行没有该字段，消费者按 unknown 处理；这里只锁“新写入必带且同进程一致”。
 */
class MonitoringJournalSessionTest {

    @Test void envelopeCarriesOneSessionPerProcess() throws Exception {
        Path root = Files.createTempDirectory("mj-session");
        // 2026-11-07 diagnostic only: preserve real async path and report worker death.
        java.util.List<String> uncaught = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            java.io.StringWriter trace = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(trace));
            uncaught.add(thread.getName() + ": " + trace);
            if (previous != null) previous.uncaughtException(thread, failure);
        });
        try {
            MonitoringJournal journal = MonitoringJournal.get();
            journal.configure(root);
            journal.publish("events", "task_finished", java.util.Map.of("id", "t1", "status", "done"));
            journal.publish("tools", "tool_call", java.util.Map.of("tool_call_id", "c1"));

            Path events = root.resolve("monitor/events.jsonl");
            Path tools = root.resolve("monitor/tools.jsonl");
            long deadline = System.currentTimeMillis() + 3000;
            // A file exists as soon as the writer opens it, before its first flush.
            // Wait for a complete parseable envelope, not merely an empty file.
            while ((!hasCompleteEnvelope(events) || !hasCompleteEnvelope(tools))
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            if (!hasCompleteEnvelope(events) || !hasCompleteEnvelope(tools)) {
                throw new AssertionError("timed out waiting for complete journal envelopes");
            }

            String eventSession = firstSession(events);
            String toolSession = firstSession(tools);
            assertFalse(eventSession.isBlank());
            assertEquals(eventSession, toolSession, "same process must share one session across categories");
        } catch (Exception | AssertionError failure) {
            failure.addSuppressed(new AssertionError(diagnostics(root, uncaught)));
            throw failure;
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
            deleteRecursively(root);
        }
    }

    private static boolean hasCompleteEnvelope(Path file) throws Exception {
        if (!Files.exists(file)) return false;
        String content = Files.readString(file, StandardCharsets.UTF_8);
        int newline = content.indexOf('\n');
        if (newline < 0) return false;
        // A terminated malformed line is a genuine failure, not something to hide/retry.
        JsonObject envelope = JsonParser.parseString(content.substring(0, newline)).getAsJsonObject();
        return envelope.has("session") && !envelope.get("session").isJsonNull()
                && !envelope.get("session").getAsString().isBlank();
    }

    private static String diagnostics(Path root, java.util.List<String> uncaught) {
        StringBuilder out = new StringBuilder("journal diagnostics: uncaught=" + uncaught);
        for (var thread : Thread.getAllStackTraces().entrySet()) {
            if (thread.getKey().getName().equals("numen-monitor-writer")) {
                out.append("; writer alive=").append(thread.getKey().isAlive())
                        .append(" state=").append(thread.getKey().getState())
                        .append(" stack=").append(java.util.Arrays.toString(thread.getValue()));
            }
        }
        for (String category : java.util.List.of("events", "tools")) {
            Path file = root.resolve("monitor/" + category + ".jsonl");
            try {
                out.append("; ").append(category).append(" exists=").append(Files.exists(file));
                if (Files.exists(file)) out.append(" bytes=").append(Files.size(file))
                        .append(" content=[").append(Files.readString(file, StandardCharsets.UTF_8)).append(']');
            } catch (Exception readFailure) {
                out.append(" read failure=").append(readFailure);
            }
        }
        return out.toString();
    }

    private static String firstSession(Path file) throws Exception {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonObject object = JsonParser.parseString(line).getAsJsonObject();
            if (object.has("session")) return object.get("session").getAsString();
        }
        throw new AssertionError("no session field in " + file);
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        }
    }
}
