package com.dwinovo.numen.plugins.learner.skills;

import com.dwinovo.numen.api.LearnedSkillBodyResolver;
import com.dwinovo.numen.plugins.learner.contract.ArtifactKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Original JSONL -> current entry -> checked body: offline fixtures never modify live skills. */
class LearnedSkillBodyResolverTest {
    private static final UUID COMPANION = UUID.fromString("00000000-0000-0000-0000-000000000017");
    private static final String ID = "sk-food";
    private static final String REF = "skill:" + ID + "@v7";
    private static final String FOOD = "Check current edible stock; convert available wheat to bread at an observed workbench.";
    @TempDir Path dir;

    private SkillEntry append(String id, int version, SkillStatus status, String audience, String body) throws Exception {
        String bodyRef = SkillBodyStore.write(dir, id, version, Integer.toHexString(body.hashCode()), body);
        assertNotNull(bodyRef);
        var entry = new SkillEntry(id, version, ArtifactKind.EXPERIENCE, "Offline " + id, "Supply fixture " + id,
                audience, List.of("edible stock insufficient"), List.of("stock sufficient"), List.of(),
                "UNKNOWN", "UNKNOWN", status, bodyRef, List.of(), List.of("offline-fixture"), List.of(), List.of());
        Files.writeString(dir.resolve("skills.jsonl"), SkillRegistry.toJson(entry) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return entry;
    }

    private LearnedSkillBodyResolver.Resolution read(String ref) {
        return new LearnedSkillBodyResolverImpl(dir).resolve(COMPANION, ref, LearnedSkillBodyResolver.View.EXECUTOR);
    }

    private Path bodyFile(SkillEntry entry) {
        return dir.resolve("skill-bodies").resolve(entry.bodyRef().substring("skill-body:".length()));
    }

    private void refused(String ref, String gap) {
        var result = read(ref);
        assertFalse(result.available());
        assertEquals(ref, result.exactRef());
        assertEquals("", result.body(), "a refused read must not leak body text");
        assertEquals(gap, result.gap());
    }

    @Test
    void trialAndPublishedReadExactBodyWithoutWritingOrAdopting() throws Exception {
        for (var status : List.of(SkillStatus.TRIAL, SkillStatus.PUBLISHED)) {
            String id = "sk-food-" + status.name().toLowerCase(java.util.Locale.ROOT);
            var entry = append(id, 7, status, "EXECUTOR,PLANNER", FOOD);
            byte[] metadata = Files.readAllBytes(dir.resolve("skills.jsonl"));
            byte[] body = Files.readAllBytes(bodyFile(entry));
            var result = read("skill:" + id + "@v7");
            assertTrue(result.available(), result.gap());
            assertEquals(FOOD, result.body());
            assertEquals("skill:" + id + "@v7", result.exactRef());
            assertArrayEquals(metadata, Files.readAllBytes(dir.resolve("skills.jsonl")));
            assertArrayEquals(body, Files.readAllBytes(bodyFile(entry)));
            try (var files = Files.list(dir)) {
                assertEquals(java.util.Set.of("skill-bodies", "skills.jsonl"),
                        files.map(f -> f.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
            }
        }
    }

    @Test
    void draftSuspendedAndRevokedAreExcludedEvenWhenBodyExists() throws Exception {
        for (var status : List.of(SkillStatus.DRAFT, SkillStatus.SUSPENDED, SkillStatus.REVOKED)) {
            String id = "sk-" + status.name().toLowerCase(java.util.Locale.ROOT);
            append(id, 7, status, "EXECUTOR", FOOD);
            refused("skill:" + id + "@v7", "not-retrievable");
        }
    }

    @Test
    void currentRegistryVersionWinsWithoutFallingBackToOldBodyOrLatest() throws Exception {
        append(ID, 7, SkillStatus.TRIAL, "EXECUTOR", FOOD);
        assertTrue(read(REF).available());
        append(ID, 8, SkillStatus.PUBLISHED, "EXECUTOR", "Revised supply method");
        refused(REF, "version-mismatch");
        assertEquals("Revised supply method", read("skill:" + ID + "@v8").body());
        refused("skill:" + ID, "invalid-reference");
        refused("skill:" + ID + "@v9", "version-mismatch");
    }

    @Test
    void realRetirementImmediatelyInvalidatesThePreviouslyReadableReference() throws Exception {
        append(ID, 7, SkillStatus.TRIAL, "EXECUTOR", FOOD);
        var resolver = new LearnedSkillBodyResolverImpl(dir);
        assertTrue(resolver.resolve(COMPANION, REF, LearnedSkillBodyResolver.View.EXECUTOR).available());
        assertEquals(SkillRegistry.Outcome.REVISE,
                SkillRegistry.open(dir.resolve("skills.jsonl")).retire(ID, "offline withdrawal fixture").outcome());
        assertFalse(resolver.resolve(COMPANION, REF, LearnedSkillBodyResolver.View.EXECUTOR).available());
        refused("skill:" + ID + "@v8", "not-retrievable");
    }

    @Test
    void audienceUsesTheOriginalExactTokenRules() throws Exception {
        append(ID, 7, SkillStatus.TRIAL, "PLANNER", FOOD);
        refused(REF, "audience-mismatch");
        assertTrue(new LearnedSkillBodyResolverImpl(dir)
                .resolve(COMPANION, REF, LearnedSkillBodyResolver.View.PLANNER).available());
        append(ID, 8, SkillStatus.TRIAL, "BOTH", FOOD);
        refused("skill:" + ID + "@v8", "audience-mismatch");
        append(ID, 9, SkillStatus.TRIAL, "planner, executor", FOOD);
        assertTrue(read("skill:" + ID + "@v9").available());
        append(ID, 10, SkillStatus.TRIAL, "", FOOD);
        assertTrue(read("skill:" + ID + "@v10").available());
    }

    @Test
    void missingDamagedAndBlankBodiesAreNotDelivered() throws Exception {
        var entry = append(ID, 7, SkillStatus.TRIAL, "EXECUTOR", FOOD);
        Files.writeString(bodyFile(entry), FOOD + " changed after registration");
        refused(REF, "body-missing-or-invalid");
        Files.writeString(bodyFile(entry), "");
        refused(REF, "body-missing-or-invalid");
        Files.delete(bodyFile(entry));
        refused(REF, "body-missing-or-invalid");
    }

    @Test
    void executableFixedLocationIsRejectedWhileDynamicBindingRemainsReadable() throws Exception {
        String fixed = "{\"steps\":[{\"block\":\"goto\",\"params\":{\"x\":2,\"y\":88,\"z\":-3}}]}";
        append(ID, 7, SkillStatus.TRIAL, "EXECUTOR", fixed);
        refused(REF, "location-binding-required");
        String dynamic = "{\"steps\":[{\"block\":\"goto\",\"params\":{\"x\":\"$input.table.x\",\"y\":\"$input.table.y\",\"z\":\"$input.table.z\"}}]}";
        append(ID, 8, SkillStatus.TRIAL, "EXECUTOR", dynamic);
        assertEquals(dynamic, read("skill:" + ID + "@v8").body());
    }

    @Test
    void packageIsLiteralUntilItsExactComponentReferenceIsExplicitlyRead() throws Exception {
        append(ID, 7, SkillStatus.TRIAL, "EXECUTOR", FOOD);
        String pack = "Choose only needed components: " + REF + "; optional skill:sk-missing@v1.";
        append("sk-supply-pack", 1, SkillStatus.TRIAL, "EXECUTOR", pack);
        var independent = read(REF);
        var packageBody = read("skill:sk-supply-pack@v1");
        assertEquals(pack, packageBody.body());
        assertFalse(packageBody.body().contains(FOOD), "opening a package is not recursive component delivery");
        // This is the caller's explicit choice of one listed reference, not a new package interpreter.
        var chosenComponent = read(REF);
        assertEquals(independent, chosenComponent);
        assertEquals(FOOD, chosenComponent.body());
        refused("skill:sk-missing@v1", "skill-missing");
    }

    @Test
    void invalidIdentityOrReferenceCannotReadAndAbsentRegistryIsNotCreated() {
        var resolver = new LearnedSkillBodyResolverImpl(dir);
        assertEquals("companion-missing", resolver.resolve(null, REF, LearnedSkillBodyResolver.View.EXECUTOR).gap());
        assertEquals("consumer-view-missing", resolver.resolve(COMPANION, REF, null).gap());
        for (String ref : List.of("", "sk-food", "skill:sk-food", "skill:sk-food@v0", "skill:sk-food@v01",
                "skill:../skills@v1", "skill:sk-food@v2147483648")) {
            refused(ref, "invalid-reference");
        }
        refused(REF, "skill-missing");
        assertFalse(Files.exists(dir.resolve("skills.jsonl")));
    }

    @Test
    void registryReadFailureIsAnExplicitGap() throws Exception {
        Files.createDirectory(dir.resolve("skills.jsonl"));
        refused(REF, "registry-read-failed");
    }
}
