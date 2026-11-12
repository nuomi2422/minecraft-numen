package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-12: pins MC shell calls around the behavior-tested core flow; no fake host world. */
class RddDeathPreparationWiringTest {
    private static String source(String name) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("plugins/rdd/src/main/java"))) root = root.getParent();
        assertNotNull(root,"repository root");
        return Files.readString(root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/"+name+".java"));
    }
    @Test void acceptedStrategyUsesRecoveryPlannerAndNeverReplacesTradingPlan() throws Exception {
        String host=source("SideTaskHost");
        assertTrue(host.contains("DeathPreparation.choose(ENGINE, companionId, parsed)"));
        assertTrue(host.contains("tickDeathPreparation(ap, port)"));
        assertTrue(host.contains("DeathPreparation.attach(ENGINE, id, rt.chain(), semantics, slots)"));
        assertTrue(host.contains("RddDecomposer.decomposeSpecsWithHint(id, objective, 0, java.util.List.of(), hint"));
        assertTrue(host.contains("RddPlugin.onServer(server, ticket"));
        assertTrue(host.contains("live.chain() == chain"));
        assertTrue(host.contains("String.valueOf(latest.seq()).equals(deathSeq)"));
        assertFalse(host.contains("replaceCurrentSubtasks"));
    }
    @Test void terminalCommitRechecksPreparationAndPauseHasNoOrdinaryConditionTimeoutBypass() throws Exception {
        String plugin=source("RddPlugin"), detector=source("RddDetector"), host=source("SideTaskHost");
        assertTrue(plugin.contains("SideTaskHost.deathPreparationResult(companionId, preparePayload)"));
        assertTrue(plugin.contains("DeathPreparation.settle(rt.chain(), preparePayload, prepareVerified, prepareReason)"));
        assertTrue(plugin.contains("boolean shouldReplan = !recovered && !preparationHandled"));
        assertTrue(detector.indexOf("SideTaskHost.holdRecoveryPause(ap, chain)") < detector.indexOf("if (conditionMatches(ap, cur, counts))"));
        assertTrue(host.contains("RddDeathEquipment.read(service, ap.getUUID(), worldId()"));
    }
}
