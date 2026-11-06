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
        try {
            MonitoringJournal journal = MonitoringJournal.get();
            journal.configure(root);
            journal.publish("events", "task_finished", java.util.Map.of("id", "t1", "status", "done"));
            journal.publish("tools", "tool_call", java.util.Map.of("tool_call_id", "c1"));

            Path events = root.resolve("monitor/events.jsonl");
            Path tools = root.resolve("monitor/tools.jsonl");
            long deadline = System.currentTimeMillis() + 3000;
            while ((!Files.exists(events) || !Files.exists(tools)) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }

            String eventSession = firstSession(events);
            String toolSession = firstSession(tools);
            assertFalse(eventSession.isBlank());
            assertEquals(eventSession, toolSession, "same process must share one session across categories");
        } finally {
            deleteRecursively(root);
        }
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
