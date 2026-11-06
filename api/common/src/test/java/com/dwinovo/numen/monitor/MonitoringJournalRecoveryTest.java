package com.dwinovo.numen.monitor;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-07: missing platform initialization killed the async writer before configure.
 * Replace only the resolver; publish/queue/worker/files remain real.
 * Why not synchronous write: it hides dead-worker recovery failures.
 */
class MonitoringJournalRecoveryTest {
    @Test
    void linkageFailureBeforeConfigureStillAllowsAsyncDelivery(@TempDir Path root) throws Exception {
        CountDownLatch resolving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        MonitoringJournal journal = new MonitoringJournal(() -> {
            resolving.countDown();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("resolver not released");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            throw new ExceptionInInitializerError("platform absent");
        }, (thread, failure) -> uncaught.set(failure));
        try {
            journal.publish("events", "before_configure", Map.of());
            assertTrue(resolving.await(3, TimeUnit.SECONDS), "must actually invoke failing resolver");
            release.countDown();
            // configure synchronizes with write: resolution failure must finish first.
            journal.configure(root);
            journal.publish("events", "recovered", Map.of("marker", "after-linkage"));
            String line = awaitCompleteLine(root.resolve("monitor/events.jsonl"), uncaught);
            var envelope = JsonParser.parseString(line).getAsJsonObject();
            assertEquals("recovered", envelope.get("type").getAsString());
            assertEquals("after-linkage", envelope.getAsJsonObject("data").get("marker").getAsString());
            assertFalse(envelope.get("session").getAsString().isBlank());
            assertNull(uncaught.get(), "linkage must not escape worker");
        } finally {
            release.countDown();
            journal.close();
            assertTrue(journal.awaitStopped(3000), "stop fixture worker before temp cleanup");
        }
    }

    @Test
    void fatalVmErrorStillEscapesResolverBoundary() throws Exception {
        CountDownLatch died = new CountDownLatch(1);
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        OutOfMemoryError fatal = new OutOfMemoryError("synthetic; no allocation pressure");
        MonitoringJournal journal = new MonitoringJournal(() -> { throw fatal; }, (thread, failure) -> {
            uncaught.set(failure);
            died.countDown();
        });
        try {
            journal.publish("events", "fatal", Map.of());
            assertTrue(died.await(3, TimeUnit.SECONDS), "fatal must reach uncaught handler");
            assertSame(fatal, uncaught.get());
        } finally {
            journal.close();
            assertTrue(journal.awaitStopped(3000));
        }
    }

    private static String awaitCompleteLine(Path file, AtomicReference<Throwable> uncaught) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        do {
            if (uncaught.get() != null) throw new AssertionError("writer died", uncaught.get());
            if (Files.exists(file)) {
                String content = Files.readString(file);
                int newline = content.indexOf('\n');
                if (newline >= 0) return content.substring(0, newline);
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("no complete async record; exists=" + Files.exists(file)
                + "; content=" + (Files.exists(file) ? Files.readString(file) : "<missing>"));
    }
}
