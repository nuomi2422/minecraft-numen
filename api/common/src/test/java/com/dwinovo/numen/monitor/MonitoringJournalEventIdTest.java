package com.dwinovo.numen.monitor;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MonitoringJournalEventIdTest {
    @TempDir Path dir;
    @Test void eventSeqIsUniqueUnderConcurrency() throws Exception {
        MonitoringJournal journal = new MonitoringJournal(() -> dir, null);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        for (int t = 0; t < 8; t++) pool.submit(() -> { for (int i = 0; i < 250; i++) journal.publish("tools", "x", Map.of("i", 1)); });
        pool.shutdown(); pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        Path f = dir.resolve("monitor/tools.jsonl");
        for (int i = 0; i < 300 && (!Files.exists(f) || Files.readAllLines(f).size() < 2000); i++) Thread.sleep(10);
        journal.close(); journal.awaitStopped(2000);
        var seqs = new ArrayList<Long>();
        for (String line : Files.readAllLines(f)) seqs.add(JsonParser.parseString(line).getAsJsonObject().get("event_seq").getAsLong());
        assertEquals(2000, seqs.size());
        assertEquals(2000, new HashSet<>(seqs).size());
        // Offer vs seq assignment can interleave; file order is not guaranteed, uniqueness is.
    }

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
