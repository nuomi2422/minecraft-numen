package com.dwinovo.numen.plugins.learner;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/** Real file/Gson controls for the extracted owner; no game or copied persistence logic. */
class ReviewStateFileConsumerTest {
    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @TempDir Path dir;

    @Test void mixedCompletionAndTwoCompanionWritesPreserveUnknownFields() throws Exception {
        JsonObject seed = JsonParser.parseString("""
                {"future_root":{"keep":[1,true,"opaque"]},"companions":{
                  "%s":{"future_owner":{"keep":"first"}},
                  "%s":{"future_owner":["second",2]}
                }}
                """.formatted(FIRST, SECOND)).getAsJsonObject();
        Files.writeString(stateFile(), seed.toString(), StandardCharsets.UTF_8);

        assertTrue(LearnerPlugin.rememberNudge(dir, FIRST, 101L));
        ReviewStateFile.saveReviewState(dir, "2026-11-16T00:00:00Z", 2);
        assertTrue(LearnerPlugin.rememberReviewStarted(dir, SECOND, 202L));
        assertTrue(LearnerPlugin.rememberAutoReview(dir, FIRST, 103L));
        assertTrue(LearnerPlugin.rememberNudge(dir, SECOND, 201L));
        ReviewStateFile.saveReviewState(dir, "2026-11-17T00:00:00Z", 4);
        assertTrue(LearnerPlugin.rememberReviewStarted(dir, FIRST, 102L));
        assertTrue(LearnerPlugin.rememberAutoReview(dir, SECOND, 203L));

        JsonObject out = JsonParser.parseString(
                Files.readString(stateFile(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertAll(
                () -> assertEquals("2026-11-17T00:00:00Z", out.get("last_review_at").getAsString()),
                () -> assertEquals(4, out.get("last_verdict_count").getAsInt()),
                () -> assertEquals(seed.get("future_root"), out.get("future_root")),
                () -> assertEquals(seed.getAsJsonObject("companions").getAsJsonObject(FIRST.toString()).get("future_owner"),
                        out.getAsJsonObject("companions").getAsJsonObject(FIRST.toString()).get("future_owner")),
                () -> assertEquals(seed.getAsJsonObject("companions").getAsJsonObject(SECOND.toString()).get("future_owner"),
                        out.getAsJsonObject("companions").getAsJsonObject(SECOND.toString()).get("future_owner")));
        assertTimes(FIRST, 101L, 103L, 102L);
        assertTimes(SECOND, 201L, 203L, 202L);
    }

    @Test void facadeWriterWaitsForOriginalHostLockThenCompletes() throws Exception {
        String original = "{\"future_root\":true}";
        Files.writeString(stateFile(), original, StandardCharsets.UTF_8);
        ExecutorService writer = Executors.newSingleThreadExecutor();
        CountDownLatch enteringWriter = new CountDownLatch(1);
        try {
            Future<Boolean> result;
            synchronized (LearnerPlugin.REVIEW_STATE_LOCK) {
                result = writer.submit(() -> {
                    enteringWriter.countDown();
                    return LearnerPlugin.rememberNudge(dir, FIRST, 301L);
                });
                assertTrue(enteringWriter.await(5, TimeUnit.SECONDS), "writer must start");
                assertThrows(TimeoutException.class, () -> result.get(250, TimeUnit.MILLISECONDS),
                        "the production writer must wait while the original host lock is held");
                assertEquals(original, Files.readString(stateFile(), StandardCharsets.UTF_8));
            }
            assertTrue(result.get(5, TimeUnit.SECONDS), "writer must finish after releasing the host lock");
            assertTimes(FIRST, 301L, 0L, 0L);
        } finally {
            // The synchronized block has released the monitor even when an assertion fails.
            writer.shutdownNow();
            assertTrue(writer.awaitTermination(5, TimeUnit.SECONDS), "writer executor must be cleaned up");
        }
    }

    @Test void nonemptyStateDirectoryRefusesWriteAndPreservesEvidenceBeforeMove() throws Exception {
        Files.createDirectory(stateFile());
        Path evidence = stateFile().resolve("evidence.txt");
        Files.writeString(evidence, "keep this evidence", StandardCharsets.UTF_8);

        // Reading the existing root fails before a temp write/move. This is not a
        // simulation of an unsupported ATOMIC_MOVE or a failure after staging.
        assertFalse(LearnerPlugin.rememberNudge(dir, FIRST, 401L));
        assertAll(
                () -> assertTrue(Files.isDirectory(stateFile())),
                () -> assertEquals("keep this evidence", Files.readString(evidence, StandardCharsets.UTF_8)),
                () -> assertFalse(Files.exists(dir.resolve("learner-review-state.json.tmp"))));
    }

    private Path stateFile() {
        return dir.resolve("learner-review-state.json");
    }

    private void assertTimes(UUID companion, long nudge, long auto, long started) {
        LearnerPlugin.ReviewStateRead read = ReviewStateFile.readReviewTimes(dir, companion);
        assertTrue(read.known());
        assertEquals(new LearnerPlugin.ReviewTimes(nudge, auto, started), read.times());
    }
}
