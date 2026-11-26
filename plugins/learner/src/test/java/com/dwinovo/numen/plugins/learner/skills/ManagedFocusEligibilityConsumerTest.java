package com.dwinovo.numen.plugins.learner.skills;

import com.dwinovo.numen.api.LearnedSkillBodyResolver;
import com.dwinovo.numen.plugins.learner.contract.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real managed registry and resolver; no Minecraft runtime or model. */
class ManagedFocusEligibilityConsumerTest {
    @TempDir Path dir;
    @Test void managedRetirementAndUnknownRefreshReachActualResolver() throws Exception {
        SkillRegistrar.resetForTest();
        var registry=SkillRegistrar.registryFor(dir);
        var draft=new ArtifactDraft(ArtifactKind.EXPERIENCE,"focus method","purpose","body",List.of("test"),List.of(),
                List.of("scene:test:package","focus:environment@v1"),"LOW",List.of(),List.of("sample"),"EXECUTOR");
        var body=SkillBodyStore.write(dir,"focus",1,Integer.toHexString("body".hashCode()),"body");
        assertTrue(registry.register(draft,"focus",body,true).stored());
        var resolver=new LearnedSkillBodyResolverImpl(dir); var id=UUID.randomUUID(); var view=LearnedSkillBodyResolver.View.EXECUTOR;
        var before=resolver.managedFocusEligibility(id,"skill:focus@v1",view);
        assertTrue(before.declaration().available()); assertEquals(before.revision(),resolver.managedRevision());
        String original=Files.readString(dir.resolve("skills.jsonl"));
        Files.writeString(dir.resolve("skills.jsonl"),original+"{bad-json\n"); assertFalse(registry.refresh());
        var unknown=resolver.managedFocusEligibility(id,"skill:focus@v1",view);
        assertTrue(unknown.revision()>before.revision()); assertFalse(unknown.declaration().available()); assertEquals("managed-snapshot-unknown",unknown.declaration().gap());
        Files.writeString(dir.resolve("skills.jsonl"),original); assertTrue(registry.refresh());
        assertTrue(resolver.managedFocusEligibility(id,"skill:focus@v1",view).declaration().available());
        assertTrue(registry.retire("focus","test").stored());
        var withdrawn=resolver.managedFocusEligibility(id,"skill:focus@v1",view);
        assertFalse(withdrawn.declaration().available()); assertTrue(withdrawn.revision()>unknown.revision());
    }
}
