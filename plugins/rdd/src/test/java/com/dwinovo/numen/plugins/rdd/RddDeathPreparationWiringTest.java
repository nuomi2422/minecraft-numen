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
        String host=source("SideTaskHost"), seam=source("DeathPreparationHost");
        assertTrue(host.contains("DeathPreparation.choose(ENGINE, companionId, parsed)"));
        assertTrue(host.contains("tickDeathPreparation(ap, port)"));
        assertTrue(host.contains("DeathPreparationHost.tick(ap.getUUID(), ENGINE, port"));
        assertTrue(seam.contains("DeathPreparation.attach(engine, id, rt.chain(), semantics, slots)"));
        assertTrue(host.contains("RddDecomposer.decomposeSpecsWithHint("));
        assertTrue(host.contains("RddPlugin.onServer(server, ticket"));
        assertTrue(seam.contains("live.chain() == chain"));
        assertTrue(seam.contains("String.valueOf(latest.seq()).equals(deathSeq)"));
        assertFalse(host.contains("replaceCurrentSubtasks"));
    }
    @Test void pausedRecoveryProjectionAndBoundedRecheckAreWiredToFinalHostConsumers() throws Exception {
        String plugin=source("RddPlugin"),host=source("SideTaskHost");
        int render=plugin.indexOf("private static String renderStateContext");
        assertTrue(render>=0);
        String renderer=plugin.substring(render,plugin.indexOf("private static String carryHint",render));
        assertTrue(renderer.contains("DeathPreparation.pausedContext(chain)"),"final runtime provider must project recovery, not instruct continued trade");
        assertTrue(renderer.indexOf("DeathPreparation.pausedContext(chain)")<renderer.indexOf("retrievalTaskFields(chain)"));
        assertTrue(host.contains("DeathPreparation.reopenPaused(ENGINE, ap.getUUID(), chain"),"existing paused recheck must have an actual bounded recovery consumer");
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
