package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RddSettleStageFlagsTest {
    private static JsonObject args(Boolean verified, Boolean finalGoal) {
        JsonObject o = new JsonObject();
        if (verified != null) o.addProperty("verified", verified);
        if (finalGoal != null) o.addProperty("verify_final_goal", finalGoal);
        return o;
    }

    private static TaskChain chain() {
        Subtask a = Subtask.hardCoded("a", "a", Map.of("type", "item", "item", "minecraft:diamond", "minimum", 1));
        return new TaskChain(new Goal("g", "g", List.of(new PrimaryGoal("p", "p", List.of(a)))));
    }

    @Test void missingFieldsKeepOldDefaultFalse() {
        assertEquals("", RddSettleStageTool.verifyFlagError(args(null, null)));
        assertEquals("", RddSettleStageTool.verifyFlagError((JsonObject) null));
    }

    @Test void finalGoalWithoutVerifiedIsRejected() {
        String err = RddSettleStageTool.verifyFlagError(args(false, true));
        assertTrue(err.contains("requires verified=true"));
        assertTrue(err.contains("no facts were checked"));
        assertFalse(RddSettleStageTool.verifyFlagError(args(false, false)).length() > 0);
        assertFalse(RddSettleStageTool.verifyFlagError(args(true, true)).length() > 0);
    }

    @Test void statusGenerationRoundTripsWithoutStringCoercion() {
        TaskChain c = chain();
        Map<String, Object> fields = RddStatusTool.generationFields(c);
        assertEquals(c.chainInstanceId(), fields.get("chain_instance_id"));
        assertInstanceOf(Number.class, fields.get("state_version"));
        assertInstanceOf(Number.class, fields.get("plan_revision"));
        JsonObject serialized = com.google.gson.JsonParser.parseString(
                new com.google.gson.Gson().toJson(fields)).getAsJsonObject();
        assertTrue(serialized.get("state_version").getAsJsonPrimitive().isNumber());
        assertTrue(serialized.get("plan_revision").getAsJsonPrimitive().isNumber());
        assertEquals("", RddSettleStageTool.generationError(c, serialized));
        assertTrue(RddStatusTool.generationFields(null).isEmpty());
    }

    @Test void statusProjectsExistingRequirementsWithoutInventingSatisfactionOrRisk() {
        Subtask acquire = Subtask.hardCoded("get", "get iron", Map.of(
                "asset_key", "minecraft:iron_ingot", "minimum", 3, "mode", "acquire"));
        TaskChain c = new TaskChain(new Goal("g", "g", List.of(new PrimaryGoal("p", "p",
                List.of(acquire), List.of(new AssetRequirement("minecraft:shield", 1))))));
        String before = c.toJson();
        var fields = RddStatusTool.projectChain(c).data();
        assertEquals("MAIN", fields.get("node_type"));
        Map<?, ?> requirements = (Map<?, ?>) fields.get("requirements");
        assertNotNull(requirements, "status must expose the existing requirement projection");
        assertEquals("RddRequirementDetector.forPrimary", requirements.get("source"));
        assertEquals("UNKNOWN", requirements.get("satisfaction"));
        assertEquals("UNKNOWN", requirements.get("completeness"));
        var declared = (List<?>) requirements.get("declared");
        assertEquals(RddRequirementDetector.forPrimary(c.currentPrimary(), "g").requirements(), declared);
        var shield = (com.dwinovo.numen.rdd.policy.RequirementManifest.Requirement) declared.get(0);
        assertEquals(com.dwinovo.numen.rdd.policy.RequirementManifest.Importance.CRITICAL, shield.importance());
        assertFalse(fields.containsKey("risk"), "importance is not a risk assessment");
        assertEquals(before, c.toJson(), "read-only status cannot open the dependency gate");
    }

    @Test void missingRequirementDeclarationIsUnknownNotEmptyVerified() {
        TaskChain c = chain(); // existing item-only condition has no detector asset_key
        var fields = RddStatusTool.projectChain(c).data();
        Map<?, ?> requirements = (Map<?, ?>) fields.get("requirements");
        assertNotNull(requirements);
        assertEquals("UNKNOWN", requirements.get("completeness"));
        assertEquals("UNKNOWN", requirements.get("satisfaction"));
        assertTrue(((List<?>) requirements.get("declared")).isEmpty());
        assertFalse(((String) requirements.get("reason")).isBlank());
        TaskChain lazy = new TaskChain(new Goal("g", "g", List.of(PrimaryGoal.unexpanded("p", "p", null))));
        var lazyRequirements = (Map<?, ?>) RddStatusTool.projectChain(lazy).data().get("requirements");
        assertEquals("UNKNOWN", lazyRequirements.get("completeness"));
        assertFalse(RddStatusTool.projectChain(lazy).data().containsKey("node_type"));
    }

    @Test void statusProjectionDistinguishesActiveAwaitingAndCompleted() {
        TaskChain active = chain();
        var live = RddStatusTool.projectChain(active);
        assertEquals(true, live.data().get("active"));
        assertEquals("a", live.data().get("subtask"));
        assertNotNull(live.data().get("state_version"));

        TaskChain waiting = new TaskChain(new Goal("gw", "gw", List.of(
                PrimaryGoal.unexpanded("lazy", "lazy stage", null))));
        var lazy = RddStatusTool.projectChain(waiting);
        assertNull(lazy.current());
        assertFalse(lazy.data().containsKey("subtask"));
        assertEquals(waiting.chainInstanceId(), lazy.data().get("chain_instance_id"));

        active.startCurrent();
        active.applyHardCodedResult("a", true);
        active.applySupervisorDecision(new SupervisorDecision(
                SupervisorDecisionType.CONFIRM, "p", "verified"));
        var done = RddStatusTool.projectChain(active);
        assertEquals(true, done.completed());
        assertEquals(false, done.data().get("active"));
        assertEquals(true, done.data().get("completed"));
        assertEquals(active.planRevision(), done.data().get("plan_revision"));
    }

    @Test void statusSeparatesCompletionFromWaiver() {
        Subtask a = Subtask.hardCoded("a", "a", Map.of("type", "item", "item", "minecraft:diamond", "minimum", 1));
        TaskChain c = new TaskChain(new Goal("g", "g", List.of(
                new PrimaryGoal("p", "first", List.of(a)),
                PrimaryGoal.unexpanded("later", "later", null))));
        var before = RddStatusTool.projectChain(c).data();
        assertEquals(0, ((java.util.List<?>) before.get("satisfied_stages")).size());
        assertEquals(0, ((java.util.List<?>) before.get("waived_stages")).size());
        c.startCurrent();
        c.settlePrimaryByFact("p", "改用现有装备", false);
        var afterWaiver = RddStatusTool.projectChain(c).data();
        assertEquals(0, ((java.util.List<?>) afterWaiver.get("satisfied_stages")).size(),
                "a waiver must not appear as a completion fact");
        assertEquals(1, ((java.util.List<?>) afterWaiver.get("waived_stages")).size());
        // The chain snapshot (monitor/judge side) must agree with the tool view; otherwise the
        // only way to see a waiver would be to infer it from the cursor moving on.
        Map<String, String> snapshotWaived = (Map<String, String>) c.snapshot().get("waivedStages");
        assertEquals(1, snapshotWaived.size());
        assertEquals("p", snapshotWaived.keySet().iterator().next());
        assertEquals(snapshotWaived.keySet(), new java.util.HashSet<>((java.util.List<?>) afterWaiver.get("waived_stages")));
    }

    @Test void settledStagesAreVisibleForBeforeAfterComparison() {
        TaskChain c = chain();
        assertTrue(((Map<?, ?>) RddStatusTool.projectChain(c).data().get("settled_stages")).isEmpty());
        c.startCurrent();
        long before = c.stateVersion();
        c.settlePrimaryByFact("p", "verified fixture: stage no longer needed");
        var after = RddStatusTool.projectChain(c).data();
        Map<?, ?> settled = (Map<?, ?>) after.get("settled_stages");
        assertEquals(1, settled.size());
        assertEquals("verified fixture: stage no longer needed", settled.get("p"));
        assertTrue(((Number) after.get("state_version")).longValue() > before);
    }

    @Test void verifiedRequiresExactGeneration() {
        TaskChain c = chain();
        JsonObject missing = args(true, false);
        assertTrue(RddSettleStageTool.generationError(c, missing).contains("requires chain_instance_id"));
        missing.addProperty("chain_instance_id", c.chainInstanceId());
        assertTrue(RddSettleStageTool.generationError(c, missing).contains("requires state_version"));

        JsonObject exact = args(true, false);
        exact.addProperty("chain_instance_id", c.chainInstanceId());
        exact.addProperty("state_version", c.stateVersion());
        exact.addProperty("plan_revision", c.planRevision());
        assertEquals("", RddSettleStageTool.generationError(c, exact));

        TaskChain replacement = chain();
        assertEquals(c.stateVersion(), replacement.stateVersion());
        assertEquals(c.planRevision(), replacement.planRevision());
        assertTrue(RddSettleStageTool.generationError(replacement, exact).contains("stale chain_instance_id"));

        JsonObject stale = args(true, false);
        stale.addProperty("chain_instance_id", c.chainInstanceId());
        stale.addProperty("state_version", c.stateVersion() + 1);
        stale.addProperty("plan_revision", c.planRevision());
        assertTrue(RddSettleStageTool.generationError(c, stale).contains("stale generation"));
    }
}
