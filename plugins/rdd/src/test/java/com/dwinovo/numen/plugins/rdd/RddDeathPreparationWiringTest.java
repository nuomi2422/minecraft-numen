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
        assertTrue(host.contains("DeathPreparation.review(ENGINE, companionId, rt.chain()"));
        assertTrue(source("RddChooseDeathRecoveryTool").contains(".optionalString(\"review\""));
        assertTrue(source("RddChooseDeathRecoveryTool").contains("parsed.name(), reason, review, instance, generation, deathSeq"));
    }
    @Test void pausedRecoveryProjectionAndBoundedRecheckAreWiredToFinalHostConsumers() throws Exception {
        String plugin=source("RddPlugin"),host=source("SideTaskHost"),seam=source("DeathPreparationHost");
        int render=plugin.indexOf("private static String renderStateContext");
        assertTrue(render>=0);
        String renderer=plugin.substring(render,plugin.indexOf("private static String carryHint",render));
        assertTrue(renderer.contains("DeathPreparation.pausedContext(chain)"),"final runtime provider must project recovery, not instruct continued trade");
        assertTrue(renderer.indexOf("DeathPreparation.pausedContext(chain)")<renderer.indexOf("retrievalTaskFields(chain)"));
        assertTrue(host.contains("DeathPreparationHost.holdRecoveryPause(ap.getUUID(), ENGINE, chain, port"));
        assertTrue(seam.contains("DeathPreparation.reopenPaused(engine, id, chain, port)"),"existing paused recheck must have an actual bounded recovery consumer");
    }
    @Test void terminalCommitRechecksPreparationAndPauseHasNoOrdinaryConditionTimeoutBypass() throws Exception {
        String plugin=source("RddPlugin"), detector=source("RddDetector"), host=source("SideTaskHost");
        assertTrue(plugin.contains("DeathPreparationHost.settlementIdentity(companionId, seq, task, rt)"));
        assertTrue(plugin.contains("DeathPreparationHost.settlementKey(companionId, seq, preparationHandled, prepareTask)"));
        assertTrue(plugin.contains("SideTaskHost.deathPreparationResult(companionId, preparePayload)"));
        assertTrue(plugin.contains("DeathPreparationHost.settlePreparation(rt.chain(), preparePayload, prepareReason, evidence)"));
        assertTrue(plugin.contains("if (!preparation.accepted()) return false"));
        assertTrue(plugin.indexOf("SideTaskHost.deathPreparationResult(companionId, preparePayload)")
                < plugin.indexOf("DeathPreparationHost.settlePreparation(rt.chain(), preparePayload, prepareReason, evidence)"));
        assertTrue(plugin.contains("return preparationHandled && preparationIdentityValid && prepareVerified"));
        assertTrue(plugin.contains("DEATH_SETTLEMENTS.remove(claimedKey)"));
        assertTrue(plugin.indexOf("\"preparation_settlement_in_progress\"") < plugin.indexOf("RddRepairDispatch.recomputeAfterDeath(companionId, counts, confirmedLost"));
        assertEquals(3L, host.lines().filter(line -> line.contains("data.put(\"prepareSettlementAccepted\", RddPlugin.onDeathRecoverySettled")).count(),
                "normal, decline and cancel copies must all return permission to the original terminal data");
        assertTrue(detector.indexOf("handlePaused(ap, rt, chain, counts)", detector.indexOf("// Recheck recovery before wait_for"))
                < detector.indexOf("if (ps == PrimaryGoalStatus.PENDING || ps == PrimaryGoalStatus.WAITING)"));
        assertTrue(plugin.contains("boolean shouldReplan = !recovered && !preparationHandled"));
        assertTrue(detector.indexOf("SideTaskHost.holdRecoveryPause(ap, chain)") < detector.indexOf("if (conditionMatches(ap, cur, counts))"));
        assertTrue(host.contains("RddDeathEquipment.read(service, ap.getUUID(), worldId()"));
    }
}
