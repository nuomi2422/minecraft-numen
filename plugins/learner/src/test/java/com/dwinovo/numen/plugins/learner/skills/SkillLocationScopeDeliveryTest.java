package com.dwinovo.numen.plugins.learner.skills;

import com.dwinovo.numen.plugins.learner.contract.*;
import com.dwinovo.numen.plugins.learner.core.ArtifactOutbox;
import com.dwinovo.numen.plugins.learner.core.Verdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-12 046: real verdict -> contract -> disk registry -> model body, no MC/build. */
class SkillLocationScopeDeliveryTest {
    @TempDir Path dir;
    @AfterEach void reset() { SkillRegistrar.resetForTest(); }
    private static String body(String params) {
        return "{\"name\":\"goto_crafting_table_then_craft\",\"version\":\"1\","
            + "\"description\":\"历史 craft nearest 2,88,-3；目标须取当前观测\","
            + "\"triggerConditions\":[\"craft needs a crafting table\"],"
            + "\"evidence\":[\"event:tools-1794128794750-0\",\"block:2,88,-3\"],"
            + "\"steps\":[{\"id\":\"to_table\",\"block\":\"goto\",\"params\":" + params + "},"
            + "{\"id\":\"craft_pickaxe\",\"block\":\"craft\",\"params\":{\"item_id\":\"minecraft:stone_pickaxe\",\"count\":1}}]}";
    }
    private static Submission.Draft generated(String body) {
        var verdict = new Verdict("tf-craft-d3f42996-tools-1794128794750-0", List.of(Verdict.Action.USE_AC),
            .9, "observed craft failure then recovery", null, body, "", "", List.of(), List.of());
        return ReviewContractAdapter.toSubmission(verdict, "sample", Submission.Mode.INTERNAL);
    }
    private void legacy(String id, String body) {
        var art = generated(body).artifacts().get(0);
        var ref = SkillBodyStore.write(dir, id, 1, art.contentHash(), body);
        assertNotNull(ref);
        SkillRegistrar.registryFor(dir).register(art, id, ref, true);
    }
    @Test void generatedStaticGotoIsRejectedBeforeRegistration() {
        var draft = generated(body("{\"x\":2,\"y\":88,\"z\":-3,\"may_alter_terrain\":true}"));
        assertFalse(ArtifactValidator.validate(draft.artifacts().get(0)).accepted());
        var report = SkillRegistrar.register(dir, draft, true, Set.of(0));
        assertEquals(0, report.total());
        assertFalse(report.ok());
        assertEquals(0, SkillRegistrar.registryFor(dir).size());
    }
    @Test void legacyStaticGotoIsNotDeliveredOrReceiptedAndBodyIsPreserved() {
        String original = body("{\"x\":2,\"y\":88,\"z\":-3}");
        legacy("legacy", original);
        var entry = SkillRegistrar.registryFor(dir).get("legacy").orElseThrow();
        for (String role : List.of("EXECUTOR")) {
            var fragment = new SkillsKnowledgeContributor(dir, role, 0).contribute(UUID.randomUUID(), "craft");
            assertNotNull(fragment);
            assertTrue(fragment.text().isEmpty());
            assertTrue(fragment.refs().isEmpty());
            assertEquals(1, fragment.gapCounts().get("location-binding-required"));
        }
        assertEquals(original, SkillBodyStore.read(dir, entry.bodyRef()));
        assertEquals(1, SkillRegistrar.registryFor(dir).get("legacy").orElseThrow().version());
    }
    @Test void parameterizedProcedureKeepsExactBodyVersionAndHistoricalEvidence() {
        String original = body("{\"x\":\"$input.table.x\",\"y\":\"$input.table.y\",\"z\":\"$input.table.z\"}");
        var draft = generated(original);
        assertTrue(ArtifactValidator.validate(draft.artifacts().get(0)).accepted());
        assertEquals(1, SkillRegistrar.register(dir, draft, true, Set.of(0)).registered());
        var fragment = new SkillsKnowledgeContributor(dir, "EXECUTOR", 0).contribute(UUID.randomUUID(), "craft");
        assertNotNull(fragment);
        assertTrue(fragment.text().contains(original));
        assertEquals(1, fragment.refs().size());
        assertEquals("v1", fragment.refs().get(0).version());
        assertTrue(fragment.gapCounts().isEmpty());
    }
    @Test void stringCoordinatesAndMixedBindingCannotBypassTheGate() {
        for (String params : List.of("{\"x\":\"2\",\"y\":\"88\",\"z\":\"-3\"}",
                "{\"x\":\"$input.table.x\",\"y\":88,\"z\":\"$input.table.z\"}")) {
            legacy("mixed-" + params.hashCode(), body(params));
        }
        var fragment = new SkillsKnowledgeContributor(dir, "EXECUTOR", 0).contribute(UUID.randomUUID(), "craft");
        assertNotNull(fragment);
        assertTrue(fragment.refs().isEmpty());
        assertEquals(2, fragment.gapCounts().get("location-binding-required"));
    }
    @Test void historicalNumbersOutsideExecutableGotoRemainDeliverable() {
        String original = body("{\"x\":\"$input.table.x\",\"y\":\"$input.table.y\",\"z\":\"$input.table.z\"}");
        legacy("history", original);
        var fragment = new SkillsKnowledgeContributor(dir, "EXECUTOR", 0).contribute(UUID.randomUUID(), "craft");
        assertTrue(fragment.text().contains("block:2,88,-3"));
        assertTrue(fragment.text().contains("\"count\":1"));
        assertEquals(1, fragment.refs().size());
    }
    @Test void staticGotoCannotEnterTheAcPublicationOutbox() {
        var outbox = new ArtifactOutbox(dir.resolve("artifact-outbox"));
        var delivery = outbox.submit(ArtifactOutbox.Kind.AC_SCRIPT, UUID.randomUUID(), "review", "memo",
            "table", body("{\"x\":2,\"y\":88,\"z\":-3}"));
        assertEquals("REJECTED", delivery.status());
        assertTrue(outbox.pending(ArtifactOutbox.Kind.AC_SCRIPT).isEmpty());
    }
    @Test void parameterizedAcStillEntersExistingOutboxUnchanged() {
        var outbox = new ArtifactOutbox(dir.resolve("artifact-outbox"));
        String original = body("{\"x\":\"$input.table.x\",\"y\":\"$input.table.y\",\"z\":\"$input.table.z\"}");
        assertTrue(outbox.submit(ArtifactOutbox.Kind.AC_SCRIPT, UUID.randomUUID(), "review", "memo", "table", original).landed());
        assertEquals(original, outbox.pending(ArtifactOutbox.Kind.AC_SCRIPT).get(0).get("body").getAsString());
    }
}
