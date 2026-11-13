package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-13 A1 host wiring contracts. Run with the RDD test-only mutation lane.
 * WHY: the host classes initialize NeoForge's event bus; loading them in plain JVM
 * tests is not a Minecraft lifecycle test. These source pins supplement the real
 * RddMonitorJournalTest; they never claim that an MC callback or game tick ran.
 * No mock controller, second identity source or production test override is added.
 */
class RddPlanningObservationWiringTest {
    private static String source(String name) throws Exception {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            Path f = p.resolve("src/main/java/com/dwinovo/numen/plugins/rdd/" + name + ".java");
            if (Files.isRegularFile(f)) return Files.readString(f);
        }
        throw new AssertionError("cannot find source for " + name);
    }

    private static String between(String text, String start, String end) {
        int first = text.indexOf(start);
        assertTrue(first >= 0, "missing contract start: " + start);
        int last = text.indexOf(end, first + start.length());
        assertTrue(last > first, "missing contract end: " + end);
        return text.substring(first, last);
    }

    @Test void replanCapturesOriginalServerBeforeBudgetAndCarriesItAcrossCallback() throws Exception {
        String body = between(source("RddPlugin"),
                "RddSurvivalGate.Decision survivalDecision, Object survivalToken) {",
                "static void onDeathRecoverySettled(");
        int capture = body.indexOf("captureObservationToken(server)");
        int budget = body.indexOf("RddReplanTransition.Result transition");
        int dispatch = body.indexOf("RddDecomposer.decomposeSpecsWithHint");
        assertTrue(capture >= 0 && capture < budget && budget < dispatch,
                "capture original server before budget observation and async dispatch");
        assertEquals(1, body.split("ServerLifecycleHooks.getCurrentServer\\(\\)", -1).length - 1,
                "one original server object feeds capture and onServer");
        assertTrue(body.contains("park instead\"), observationToken)"));
        String callback = body.substring(body.indexOf("specs -> onServer(server, ticket, () -> {"));
        assertTrue(callback.contains("publishTaskSnapshot(companionId, \"replanned\", observationToken)"));
        assertTrue(callback.contains("chain.planRevision()), observationToken)"));
        assertTrue(callback.contains("publishTaskSnapshot(companionId, \"replan_fallback_resume\", observationToken)"));
        assertFalse(callback.contains("capture("));
        assertFalse(callback.contains("captureObservationToken("));
        assertFalse(callback.contains("getCurrentServer("));
        assertFalse(body.contains("\"requestId\""), "no time-based requestId reconstruction");
    }

    @Test void detectorUsesActualBodyServerForAcceptedSurvivalAndKickoff() throws Exception {
        String body = between(source("RddDetector"), "private static void survivalPreempt(", "// 仍是原60秒冷却");
        assertTrue(body.contains("captureObservationToken(ap.getServer())"));
        assertTrue(body.indexOf("captureObservationToken(ap.getServer())")
                < body.indexOf("RddPlugin.requestReplanForSurvival"));
        assertTrue(body.contains("\"free_slots\", free), observationToken)"));
        assertTrue(body.contains("\"companionId\", ap.getUUID().toString()"));
        assertTrue(body.indexOf("if (!accepted)") < body.indexOf("RddMonitor.publish(\"survival_preempt\""));
        assertTrue(source("RddDetector").contains(
                "RddPlugin.kickoffNudge(ap.getUUID(), chain, RddPlugin.captureObservationToken(ap.getServer()))"));
    }

    @Test void kickoffLegacyStaysUnknownAndEveryExitCarriesSuppliedToken() throws Exception {
        String body = between(source("RddPlugin"), "static boolean kickoffNudge(UUID", "/** rdd_submit 用");
        assertTrue(body.contains("return kickoffNudge(companionId, chain, null)"));
        assertTrue(body.contains("WorldContextStamp.Token observationToken)"));
        for (String reason : new String[]{"supervision_off_or_no_api", "body_dispatch_enabled",
                "primary_not_active", "already_kicked_off", "nudge_failed"}) {
            assertTrue(body.contains("\"" + reason + "\", observationToken)"), reason);
        }
        assertTrue(body.contains("\"subtask_not_running\", observationToken)"));
        assertTrue(body.contains("RddMonitor.publish(\"goal_kickoff\", data, observationToken)"));
        assertTrue(body.contains("\"apiBound\", numenApi != null), observationToken)"));
        assertFalse(body.contains("capture("));
        assertFalse(body.contains("getCurrentServer("));
    }

    @Test void singlePassCapturesBeforeAsyncDispatchAndReusesTokenForSnapshotAndKickoff() throws Exception {
        String body = between(source("RddPlugin"), "private static void decomposeSinglePass(", "public static void bind(");
        assertTrue(body.indexOf("captureObservationToken(server)") >= 0);
        assertTrue(body.indexOf("captureObservationToken(server)") < body.indexOf("RddDecomposer.decompose("));
        String callback = body.substring(body.indexOf("goal -> onServer("));
        assertTrue(callback.contains("publishTaskSnapshot(uuid, \"goal_decomposed\", observationToken)"));
        assertTrue(callback.contains("kickoffNudge(uuid, runtime.chain(), observationToken)"));
        assertFalse(callback.contains("captureObservationToken("));
    }

    @Test void captureHasNoGlobalFallbackAndPureGuardIsNotMigrated() throws Exception {
        String capture = between(source("RddPlugin"), "static com.dwinovo.numen.api.WorldContextStamp.Token captureObservationToken(",
                "public static void publishTaskSnapshot(UUID");
        assertTrue(capture.contains("WorldContextStamp.capture(server)"));
        assertTrue(capture.contains("RuntimeException | LinkageError"));
        assertTrue(capture.contains("return null"));
        assertFalse(capture.contains("WorldContextStamp.capture()"));
        assertFalse(capture.contains("getCurrentServer("));
        assertFalse(source("RddPlanGuard").contains("WorldContextStamp"));
    }
}
