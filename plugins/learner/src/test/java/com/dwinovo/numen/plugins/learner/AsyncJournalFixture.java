package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.monitor.MonitoringJournal;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Existing singleton, real bounded queue/writer, isolated root only. Never writes a tool-result row itself. */
final class AsyncJournalFixture implements AutoCloseable {
    private final MonitoringJournal journal = MonitoringJournal.get();
    private final Path config;
    private final Field root;
    private final Method closeWriters;
    private final Object previousRoot;
    AsyncJournalFixture(Path config) throws Exception {
        this.config = config;
        root = MonitoringJournal.class.getDeclaredField("root"); root.setAccessible(true);
        closeWriters = MonitoringJournal.class.getDeclaredMethod("closeWriters"); closeWriters.setAccessible(true);
        barrierAtCurrentRoot();
        synchronized (journal) {
            previousRoot = root.get(journal);
            closeWriters.invoke(journal);
            journal.configure(config);
        }
    }
    private void barrierAtCurrentRoot() throws Exception {
        Object current = root.get(journal);
        if (current == null) return;
        awaitMarker((Path) current);
    }
    private void awaitMarker(Path monitor) throws Exception {
        String marker = UUID.randomUUID().toString();
        assertTrue(journal.tryPublish("fixture", "async_bridge_barrier", Map.of("marker", marker), null),
                "real journal queue must accept fixture barrier");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        Path file = monitor.resolve("fixture.jsonl");
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(file) && Files.readString(file).contains(marker)) return;
            Thread.sleep(10);
        }
        fail("real journal did not persist fixture barrier; SETUP failure, not evidence bridge business red");
    }
    void barrier() throws Exception { awaitMarker(config.resolve("monitor")); }
    @Override public void close() throws Exception {
        try { barrier(); }
        finally {
            synchronized (journal) {
                closeWriters.invoke(journal);
                root.set(journal, previousRoot);
            }
        }
    }
}
