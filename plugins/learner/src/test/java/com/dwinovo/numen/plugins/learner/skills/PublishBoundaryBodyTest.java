package com.dwinovo.numen.plugins.learner.skills;

import com.dwinovo.numen.plugins.learner.contract.ArtifactDraft;
import com.dwinovo.numen.plugins.learner.contract.ArtifactKind;
import com.dwinovo.numen.plugins.learner.contract.ReviewDraft;
import com.dwinovo.numen.plugins.learner.contract.Submission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-12 046-P: exercise public register/promote and the real Registrar path.
 * Reuses the old 88870ca7/b3728616 body-boundary cases against bb0 public methods.
 * Files live only in TempDir; legacy states come from JSON, never a bypass writer.
 * Serial tests do not establish multi-process atomicity or a body-read/write lock.
 */
class PublishBoundaryBodyTest {
    @AfterEach void resetRegistry() { SkillRegistrar.resetForTest(); }

    private static ArtifactDraft artifact(String body) {
        return new ArtifactDraft(ArtifactKind.EXPERIENCE, "night shelter", "seek shelter", body,
                List.of("night"), List.of(), List.of(), "LOW", List.of(), List.of("memo-1"), "EXECUTOR");
    }

    private static Submission.Draft submission(ArtifactDraft a) {
        return new Submission.Draft(Submission.Mode.INTERNAL, "memo-1",
                new ReviewDraft.Draft("memo-1", List.of()), List.of(a));
    }

    private static SkillRegistry open(Path dir) throws IOException {
        return SkillRegistry.open(dir.resolve("skills.jsonl"));
    }

    private static String body(Path dir, String id, String text) {
        String ref = SkillBodyStore.write(dir, id, 1, Integer.toHexString(text.hashCode()), text);
        assertNotNull(ref, "fixture body must actually be written");
        assertEquals(text, SkillBodyStore.read(dir, ref), "fixture must pass the real reader");
        return ref;
    }

    private static Path bodyFile(Path dir, String ref) {
        return dir.resolve("skill-bodies").resolve(ref.substring("skill-body:".length()));
    }

    /** Original JSON shape: loading old state must not require a new public bypass API. */
    private static void legacy(Path dir, SkillStatus status, String ref) throws IOException {
        String json = """
                {"v":1,"skillId":"old","version":3,"kind":"EXPERIENCE",
                 "name":"night shelter","purpose":"seek shelter","audience":"EXECUTOR",
                 "triggerConditions":["night"],"excludeConditions":[],"preconditions":[],
                 "risk":"LOW","evidenceLevel":"UNKNOWN","status":"%s","bodyRef":"%s",
                 "validationSignals":[],"foundingSamples":["memo-1"],
                 "supersedes":["old@v1","old@v2"],"conflicts":[]}
                """.formatted(status.name(), ref);
        Files.writeString(dir.resolve("skills.jsonl"), json);
    }

    private static void unchanged(Path dir, SkillRegistry reg, SkillEntry before, String disk) throws IOException {
        assertEquals(before, reg.get(before.skillId()).orElseThrow());
        assertEquals(before, open(dir).get(before.skillId()).orElseThrow());
        assertEquals(disk, Files.readString(dir.resolve("skills.jsonl")), "rejection must not rewrite history");
    }

    @Test void nullBodyCannotEnterTrial(@TempDir Path dir) throws IOException {
        SkillRegistry reg = open(dir);
        var d = reg.register(artifact("body"), "sk-null", null, true);
        assertEquals(SkillRegistry.Outcome.REGISTERED, d.outcome());
        assertEquals(SkillStatus.DRAFT, d.entry().status());
        assertTrue(d.reason().contains("body-unreadable"), d.reason());
        assertTrue(reg.retrievable().isEmpty());
        assertEquals(SkillStatus.DRAFT, open(dir).get("sk-null").orElseThrow().status());
    }

    @Test void blankLegacyAndMissingReferencesCannotEnterTrial(@TempDir Path dir) throws IOException {
        List<String> refs = List.of("", "  ", "draft:old:abc", "body:old", "skill-body:missing@v1-acde.md");
        for (int i = 0; i < refs.size(); i++) {
            Path caseDir = Files.createDirectory(dir.resolve("case-" + i));
            SkillRegistry reg = open(caseDir);
            var d = reg.register(artifact("body"), "sk-bad", refs.get(i), true);
            assertEquals(SkillStatus.DRAFT, d.entry().status(), refs.get(i));
            assertTrue(d.reason().contains("body-unreadable"), d.reason());
            assertTrue(reg.retrievable().isEmpty());
        }
    }

    @Test void tamperedBodyCannotEnterTrial(@TempDir Path dir) throws IOException {
        String ref = body(dir, "sk-tampered", "original");
        Files.writeString(bodyFile(dir, ref), "changed after write");
        assertNull(SkillBodyStore.read(dir, ref));
        var d = open(dir).register(artifact("original"), "sk-tampered", ref, true);
        assertEquals(SkillStatus.DRAFT, d.entry().status());
        assertTrue(d.reason().contains("body-unreadable"), d.reason());
    }

    @Test void readableButBlankFileCannotEnterTrial(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve("skill-bodies"));
        Files.writeString(dir.resolve("skill-bodies/blank.md"), " \n ");
        var d = open(dir).register(artifact("body"), "sk-blank", "skill-body:blank.md", true);
        assertEquals(SkillStatus.DRAFT, d.entry().status());
        assertTrue(d.reason().contains("body-unreadable"), d.reason());
    }

    @Test void readableBodyEntersTrialAndIsDelivered(@TempDir Path dir) throws IOException {
        String ref = body(dir, "sk-ok", "real steps");
        SkillRegistry reg = open(dir);
        var d = reg.register(artifact("real steps"), "sk-ok", ref, true);
        assertEquals(SkillStatus.TRIAL, d.entry().status());
        assertEquals(1, reg.retrievable().size());
        var fragment = new SkillsKnowledgeContributor(dir, "EXECUTOR", 0).contribute(UUID.randomUUID(), "night");
        assertNotNull(fragment);
        assertEquals(List.of("skill:sk-ok"), fragment.refs().stream().map(r -> r.entryRef()).toList());
        assertTrue(fragment.text().contains("real steps"));
    }

    @Test void memoryOnlyRegistryCannotClaimBodyReadable() {
        SkillRegistry reg = new SkillRegistry();
        var d = reg.register(artifact("body"), "sk-memory", "skill-body:any.md", true);
        assertEquals(SkillStatus.DRAFT, d.entry().status());
        assertEquals(SkillRegistry.Outcome.CONFLICT, reg.promote("sk-memory", "no config").outcome());
        assertEquals(1, reg.get("sk-memory").orElseThrow().version());
    }

    @Test void promoteMissingBodyRejectsWithoutChangingVersionOrDisk(@TempDir Path dir) throws IOException {
        SkillRegistry reg = open(dir);
        SkillEntry before = reg.register(artifact("body"), "sk-missing", "skill-body:missing.md").entry();
        String disk = Files.readString(dir.resolve("skills.jsonl"));
        var d = reg.promote("sk-missing", "reviewed");
        assertEquals(SkillRegistry.Outcome.CONFLICT, d.outcome());
        assertTrue(d.reason().contains("body-unreadable"), d.reason());
        unchanged(dir, reg, before, disk);
    }

    @Test void promoteTamperedBodyRejectsWithoutMutation(@TempDir Path dir) throws IOException {
        String ref = body(dir, "sk", "original");
        SkillRegistry reg = open(dir);
        SkillEntry before = reg.register(artifact("original"), "sk", ref).entry();
        Files.writeString(bodyFile(dir, ref), "tampered");
        String disk = Files.readString(dir.resolve("skills.jsonl"));
        assertEquals(SkillRegistry.Outcome.CONFLICT, reg.promote("sk", "reviewed").outcome());
        unchanged(dir, reg, before, disk);
    }

    @Test void readablePromoteAndRevisionKeepSequentialHistory(@TempDir Path dir) throws IOException {
        SkillRegistry reg = open(dir);
        String first = body(dir, "sk", "first steps");
        reg.register(artifact("first steps"), "sk", first);
        assertEquals(SkillRegistry.Outcome.REVISE, reg.promote("sk", "reviewed").outcome());
        assertEquals(2, reg.get("sk").orElseThrow().version());
        assertEquals(SkillRegistry.Outcome.REUSE, reg.promote("sk", "again").outcome());
        String second = body(dir, "sk", "second steps");
        var revised = reg.register(artifact("second steps"), "sk", second, true);
        assertEquals(SkillRegistry.Outcome.REVISE, revised.outcome());
        assertEquals(3, revised.entry().version());
        assertEquals(SkillStatus.TRIAL, revised.entry().status());
        assertEquals(List.of("sk@v1", "sk@v2"), revised.entry().supersedes());
        assertEquals(revised.entry(), open(dir).get("sk").orElseThrow());
        assertEquals("first steps", SkillBodyStore.read(dir, first));
        assertEquals("second steps", SkillBodyStore.read(dir, second));
    }

    @Test void unknownAndRevokedPromoteRejectWithoutMutation(@TempDir Path dir) throws IOException {
        SkillRegistry reg = open(dir);
        assertEquals(SkillRegistry.Outcome.CONFLICT, reg.promote("unknown", "x").outcome());
        String ref = body(dir, "sk", "body");
        reg.register(artifact("body"), "sk", ref, true);
        SkillEntry before = reg.retire("sk", "withdrawn").entry();
        String disk = Files.readString(dir.resolve("skills.jsonl"));
        assertEquals(SkillRegistry.Outcome.CONFLICT, reg.promote("sk", "x").outcome());
        unchanged(dir, reg, before, disk);
    }

    @Test void legacyDraftCannotBePromoted(@TempDir Path dir) throws IOException {
        legacy(dir, SkillStatus.DRAFT, "draft:old:abc");
        SkillRegistry reg = open(dir);
        SkillEntry before = reg.get("old").orElseThrow();
        String disk = Files.readString(dir.resolve("skills.jsonl"));
        assertEquals(SkillRegistry.Outcome.CONFLICT, reg.promote("old", "x").outcome());
        unchanged(dir, reg, before, disk);
    }

    @Test void loadedTrialAndPublishedRemainIdempotentButBadBodyIsNotDelivered(@TempDir Path dir) throws IOException {
        for (SkillStatus status : List.of(SkillStatus.TRIAL, SkillStatus.PUBLISHED)) {
            Path caseDir = Files.createDirectory(dir.resolve(status.name()));
            legacy(caseDir, status, "draft:old:abc");
            SkillRegistry reg = open(caseDir);
            SkillEntry before = reg.get("old").orElseThrow();
            String disk = Files.readString(caseDir.resolve("skills.jsonl"));
            assertEquals(status, before.status());
            assertEquals(SkillRegistry.Outcome.REUSE, reg.promote("old", "idempotent").outcome());
            unchanged(caseDir, reg, before, disk);
            var f = new SkillsKnowledgeContributor(caseDir, "EXECUTOR", 0).contribute(UUID.randomUUID(), "night");
            assertNotNull(f);
            assertTrue(f.refs().isEmpty());
            assertEquals(1, f.gapCounts().getOrDefault("body-missing", 0));
        }
    }

    @Test void badRevisionCannotReplaceDeliverableEntryEvenWithoutTrialFlag(@TempDir Path dir) throws IOException {
        for (SkillStatus status : List.of(SkillStatus.DRAFT, SkillStatus.TRIAL, SkillStatus.PUBLISHED)) {
            Path caseDir = Files.createDirectory(dir.resolve(status.name()));
            String ref = body(caseDir, "old", "valid previous steps");
            legacy(caseDir, status, ref);
            SkillRegistry reg = open(caseDir);
            SkillEntry before = reg.get("old").orElseThrow();
            String disk = Files.readString(caseDir.resolve("skills.jsonl"));
            for (boolean trial : List.of(false, true)) {
                var d = reg.register(artifact("bad new steps"), "old", "draft:fake:body", trial);
                assertEquals(SkillRegistry.Outcome.CONFLICT, d.outcome());
                assertTrue(d.reason().contains("body-unreadable"), d.reason());
                unchanged(caseDir, reg, before, disk);
            }
        }
    }

    @Test void repeatedSameBodyDoesNotAddVersions(@TempDir Path dir) throws IOException {
        String ref = body(dir, "sk", "body");
        SkillRegistry reg = open(dir);
        SkillEntry before = reg.register(artifact("body"), "sk", ref, true).entry();
        String disk = Files.readString(dir.resolve("skills.jsonl"));
        assertEquals(SkillRegistry.Outcome.REUSE, reg.register(artifact("body"), "sk", ref, true).outcome());
        unchanged(dir, reg, before, disk);
    }

    @Test void registrarUsesSharedRegistryAndPreservesRetirementAcrossRevisions(@TempDir Path dir) throws IOException {
        SkillRegistry shared = SkillRegistrar.registryFor(dir);
        assertSame(shared, SkillRegistrar.registryFor(dir));
        var first = SkillRegistrar.register(dir, submission(artifact("first")), true, Set.of(0));
        assertTrue(first.ok(), first.error());
        assertEquals(1, first.registered());
        String id = SkillRegistrar.skillIdOf(artifact("first"));
        assertEquals(SkillStatus.TRIAL, shared.get(id).orElseThrow().status());
        var second = SkillRegistrar.register(dir, submission(artifact("second")), true, Set.of(0));
        assertTrue(second.ok(), second.error());
        assertEquals(1, second.revised());
        assertEquals("second", SkillBodyStore.read(dir, shared.get(id).orElseThrow().bodyRef()));
        shared.retire(id, "withdrawn");
        SkillRegistrar.register(dir, submission(artifact("third")), true, Set.of(0));
        SkillEntry current = shared.get(id).orElseThrow();
        assertEquals(SkillStatus.REVOKED, current.status(), "new registration cannot undo retirement");
        assertEquals(current, open(dir).get(id).orElseThrow());
    }

    @Test void registrarBodyWriteFailureIsVisibleAndDoesNotRegister(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("skill-bodies"), "block directory creation");
        var r = SkillRegistrar.register(dir, submission(artifact("body")), true, Set.of(0));
        assertFalse(r.ok());
        assertTrue(r.error().contains("body_missing=1"), r.error());
        assertEquals(0, r.total());
        assertEquals(0, SkillRegistrar.registryFor(dir).size());
        assertFalse(Files.exists(dir.resolve("skills.jsonl")));
    }
}
