package com.dwinovo.numen.plugins.learner.skills;

import com.dwinovo.numen.plugins.learner.contract.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillRegistryDeclarationSnapshotTest {
    @TempDir Path dir;
    private ArtifactDraft draft(String body, List<String> preconditions) {
        return new ArtifactDraft(ArtifactKind.EXPERIENCE,"snapshot method","snapshot purpose",body,
                List.of("test"),List.of(),preconditions,"LOW",List.of(),List.of("sample"),"EXECUTOR");
    }
    private SkillRegistry.Decision register(SkillRegistry registry,String body,List<String> preconditions) {
        return registry.register(draft(body,preconditions),"snapshot",SkillBodyStore.write(dir,"snapshot",1,Integer.toHexString(body.hashCode()),body),true);
    }
    @Test void persistentCommitsPublishImmutableVersionedDeclarationsAndNoopDoesNot() throws Exception {
        SkillRegistry registry=SkillRegistry.open(dir.resolve("skills.jsonl"));
        var initial=registry.declarationSnapshot();
        assertTrue(initial.complete()); assertTrue(initial.entries().isEmpty());
        assertTrue(register(registry,"original",List.of("scene:test:package","focus:environment@v1")).stored());
        var committed=registry.declarationSnapshot();
        assertTrue(committed.revision()>initial.revision());
        assertEquals(1,SkillRegistry.open(dir.resolve("skills.jsonl")).get("snapshot").orElseThrow().version());
        assertThrows(UnsupportedOperationException.class,()->committed.entries().clear());
        assertThrows(UnsupportedOperationException.class,()->committed.entries().get("snapshot").preconditions().clear());
        assertTrue(initial.entries().isEmpty(),"old snapshot is detached");
        assertEquals(SkillRegistry.Outcome.REUSE,register(registry,"original",List.of("scene:test:package","focus:environment@v1")).outcome());
        assertEquals(committed.revision(),registry.declarationSnapshot().revision());
        assertEquals(SkillRegistry.Outcome.REVISE,register(registry,"original",List.of("scene:test:package","focus:environment@v1","requires:skill:component@v1")).outcome());
        assertTrue(registry.declarationSnapshot().revision()>committed.revision());
        long revision=registry.declarationSnapshot().revision();
        assertTrue(registry.retire("snapshot","test withdrawal").stored());
        assertTrue(registry.declarationSnapshot().revision()>revision);
        assertEquals(SkillStatus.REVOKED,registry.declarationSnapshot().entries().get("snapshot").status());
        assertEquals(SkillStatus.TRIAL,committed.entries().get("snapshot").status());
    }
    @Test void failedDurableReplacementDoesNotPublishRevisionOrUncommittedIndexes() throws Exception {
        SkillRegistry registry=SkillRegistry.open(dir.resolve("skills.jsonl"));
        assertTrue(register(registry,"original",List.of()).stored());
        var before=registry.declarationSnapshot();
        Files.createDirectory(dir.resolve("skills.jsonl.tmp"));
        assertEquals(SkillRegistry.Outcome.CONFLICT,registry.retire("snapshot","cannot persist").outcome());
        assertSame(before,registry.declarationSnapshot());
        assertEquals(SkillRegistry.Outcome.CONFLICT,register(registry,"replacement",List.of()).outcome());
        assertSame(before,registry.declarationSnapshot());
        assertEquals(before.entries().get("snapshot"),registry.get("snapshot").orElseThrow());
        assertEquals(before.entries().get("snapshot"),SkillRegistry.open(dir.resolve("skills.jsonl")).get("snapshot").orElseThrow());
    }
    @Test void failedRefreshCannotBeClearedByAnUnrelatedManagedWrite() throws Exception {
        SkillRegistry registry=SkillRegistry.open(dir.resolve("skills.jsonl"));
        assertTrue(register(registry,"original",List.of()).stored());
        String original=Files.readString(dir.resolve("skills.jsonl"));
        String unread=original+"{bad-json\n";
        Files.writeString(dir.resolve("skills.jsonl"),unread);
        assertFalse(registry.refresh());
        var unknown=registry.declarationSnapshot();
        assertFalse(unknown.complete());
        assertEquals(SkillRegistry.Outcome.CONFLICT,registry.retire("snapshot","not a recovery").outcome());
        assertSame(unknown,registry.declarationSnapshot(),"ordinary write must not publish an authority recovery");
        assertEquals(unread,Files.readString(dir.resolve("skills.jsonl")),"do not overwrite unread external content with old diagnostic entries");
        assertEquals(SkillRegistry.Outcome.CONFLICT,register(registry,"replacement",List.of()).outcome());
        assertSame(unknown,registry.declarationSnapshot());
        Files.writeString(dir.resolve("skills.jsonl"),original);
        assertTrue(registry.refresh());
        assertTrue(registry.retire("snapshot","after complete refresh").stored());
    }

    @Test void explicitBadRefreshPublishesUnknownAndExactRepairPublishesNextRevision() throws Exception {
        SkillRegistry registry=SkillRegistry.open(dir.resolve("skills.jsonl"));
        assertTrue(register(registry,"original",List.of()).stored());
        var committed=registry.declarationSnapshot();
        String original=Files.readString(dir.resolve("skills.jsonl"));
        Files.writeString(dir.resolve("skills.jsonl"),original+"{bad-json\n");
        assertFalse(registry.refresh());
        var unknown=registry.declarationSnapshot();
        assertTrue(unknown.revision()>committed.revision()); assertFalse(unknown.complete());
        assertEquals(committed.entries(),unknown.entries(),"last committed entries are diagnostic, not partial new content");
        assertTrue(unknown.availableBodies().isEmpty());
        Files.writeString(dir.resolve("skills.jsonl"),original);
        assertTrue(registry.refresh());
        var restored=registry.declarationSnapshot();
        assertTrue(restored.revision()>unknown.revision()); assertTrue(restored.complete());
        assertEquals(committed.entries(),restored.entries());
        assertTrue(restored.availableBodies().contains("snapshot"));
    }
    @Test void bodyAvailabilityIsVerifiedOnManagedRefreshNotGuessedFromBodyReference() throws Exception {
        SkillRegistry registry=SkillRegistry.open(dir.resolve("skills.jsonl"));
        assertTrue(register(registry,"original",List.of()).stored());
        var snapshot=registry.declarationSnapshot();
        assertTrue(snapshot.availableBodies().contains("snapshot"));
        assertThrows(UnsupportedOperationException.class,()->snapshot.availableBodies().clear());
        String ref=snapshot.entries().get("snapshot").bodyRef();
        Path body=dir.resolve("skill-bodies").resolve(ref.substring("skill-body:".length()));
        String original=Files.readString(body);
        Files.writeString(body,"corrupt replacement");
        assertSame(snapshot,registry.declarationSnapshot(),"external filesystem change is not automatically watched");
        assertTrue(registry.refresh());
        var changed=registry.declarationSnapshot();
        assertTrue(changed.complete());
        assertFalse(changed.availableBodies().contains("snapshot"),"bodyRef exists but body fails integrity");
        Files.writeString(body,original);
        assertTrue(registry.refresh());
        assertTrue(registry.declarationSnapshot().availableBodies().contains("snapshot"));
    }
    @Test void unreadableManagedFirstLoadRemainsUnknownAndCanRefreshSameInstance() throws Exception {
        Files.createDirectory(dir.resolve("skills.jsonl"));
        try {
            SkillRegistry registry=SkillRegistrar.registryFor(dir);
            assertFalse(registry.declarationSnapshot().complete());
            assertFalse(registry.refresh());
            long revision=registry.declarationSnapshot().revision();
            Files.delete(dir.resolve("skills.jsonl")); // verified temporary test path created above
            Files.writeString(dir.resolve("skills.jsonl"),"");
            assertTrue(SkillRegistrar.refreshManaged(dir));
            assertSame(registry,SkillRegistrar.registryFor(dir));
            assertTrue(registry.declarationSnapshot().complete());
            assertTrue(registry.declarationSnapshot().revision()>revision);
        } finally { SkillRegistrar.resetForTest(); }
    }
    @Test void peekManagedDoesNotInitializeOrReadRegistryForAnUnknownPath() throws Exception {
        SkillRegistrar.resetForTest();
        try {
            Files.createDirectory(dir.resolve("skills.jsonl"));
            assertTrue(SkillRegistrar.peekManaged(dir).isEmpty());
            assertTrue(SkillRegistrar.peekManaged(null).isEmpty());
            SkillRegistry managed=SkillRegistrar.registryFor(dir);
            assertSame(managed,SkillRegistrar.peekManaged(dir).orElseThrow());
            assertFalse(managed.declarationSnapshot().complete());
        } finally { SkillRegistrar.resetForTest(); }
    }
    @Test void firstLoadWithBadRowsIsNotACompleteManagedDeclaration() throws Exception {
        Files.writeString(dir.resolve("skills.jsonl"),"{bad-json\n");
        var snapshot=SkillRegistry.open(dir.resolve("skills.jsonl")).declarationSnapshot();
        assertFalse(snapshot.complete()); assertTrue(snapshot.availableBodies().isEmpty());
    }

    @Test void managedRevisionAndEntryBecomeVisibleTogetherAcrossThreads() throws Exception {
        SkillRegistry registry=SkillRegistry.open(dir.resolve("skills.jsonl"));
        assertTrue(register(registry,"original",List.of()).stored());
        long revision=registry.declarationSnapshot().revision();
        try(var executor=Executors.newSingleThreadExecutor()) {
            Future<SkillRegistry.DeclarationSnapshot> visible=executor.submit(()->{
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(System.nanoTime()<deadline){
                    var snapshot=registry.declarationSnapshot();
                    if(snapshot.revision()>revision)return snapshot;
                    Thread.onSpinWait();
                }
                throw new AssertionError("committed revision not visible");
            });
            assertTrue(registry.retire("snapshot","visible withdrawal").stored());
            var snapshot=visible.get(4,TimeUnit.SECONDS);
            assertEquals(SkillStatus.REVOKED,snapshot.entries().get("snapshot").status());
        }
    }
}
