package com.dwinovo.numen.monitor;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MonitoringJournalEventIdTest {
    @TempDir Path dir;
    @Test void eventIdsMustBeStableAcrossQueueDrain() throws Exception {
        MonitoringJournal journal = new MonitoringJournal(() -> dir, null);
        for (int i = 0; i < 200; i++) journal.publish("tools", "x", Map.of("i", i));
        Path f = dir.resolve("monitor/tools.jsonl");
        for (int i = 0; i < 200 && (!Files.exists(f) || Files.readAllLines(f).size() < 200); i++) Thread.sleep(10);
        journal.close(); journal.awaitStopped(2000);
        List<String> ids = new ArrayList<>();
        for (String line : Files.readAllLines(f)) ids.add(JsonParser.parseString(line).getAsJsonObject().get("event_id").getAsString());
        assertEquals(200, ids.size());
        assertEquals(200, new HashSet<>(ids).size(), "event_id collision: " + ids.size() + " rows, " + new HashSet<>(ids).size() + " unique");
    }
}
