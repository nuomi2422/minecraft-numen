package com.dwinovo.numen.plugins.experience;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceType;
import com.dwinovo.numen.plugins.learner.ExpansionReviewIntake;
import com.dwinovo.numen.plugins.learner.ReviewTargetGate;
import com.dwinovo.numen.plugins.learner.core.MemoQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-08 C1: learn -> expand queue -> learner intake -> commit gate. */
class ExpansionDeliveryChainE2ETest {
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

    private static void writeEntry(Path dir, ExperienceEntry e) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("experience-" + C + ".jsonl"), e.toJson().toString() + "\n");
    }

    @Test void chainMatchesThenMismatchesOnContentChange(@TempDir Path dir) throws Exception {
        ExperienceEntry e = ExperienceEntry.builder().type(ExperienceType.POLICY)
                .title("C1 e2e").description("body-v1").build();
        writeEntry(dir, e);
        String version = e.contentFingerprint();
        assertTrue(ExpansionReviewQueue.enqueue(dir, C, "exp:" + e.id(), version, "use-1"));

        MemoQueue memos = new MemoQueue(dir.resolve("learner-memos.json"));
        assertEquals(1, ExpansionReviewIntake.scan(dir, id -> memos));
        var memo = memos.all().get(0);
        System.out.println("[e2e-memo] stage=" + memo.stage() + " snapshot=" + memo.snapshot());
        var first = ReviewTargetGate.check(dir, C, memo);
        assertTrue(first.ok(), first.reason());

        ExperienceEntry v2 = ExperienceEntry.builder().type(ExperienceType.POLICY)
                .title("C1 e2e").description("body-v2").id(e.id()).build();
        writeEntry(dir, v2);
        var g = ReviewTargetGate.check(dir, C, memo);
        assertFalse(g.ok(), "内容变更后旧 review 不得归给新版本");
        assertEquals("VERSION_MISMATCH", g.reason());
        assertNotEquals(version, v2.contentFingerprint());
    }

    @Test void retractedAfterExpandIsRejected(@TempDir Path dir) throws Exception {
        ExperienceEntry e = ExperienceEntry.builder().type(ExperienceType.POLICY)
                .title("C1 retract").description("body").build();
        writeEntry(dir, e);
        assertTrue(ExpansionReviewQueue.enqueue(dir, C, "exp:" + e.id(), e.contentFingerprint(), "use-2"));
        MemoQueue memos = new MemoQueue(dir.resolve("learner-memos.json"));
        assertEquals(1, ExpansionReviewIntake.scan(dir, id -> memos));
        ExperienceEntry retracted = ExperienceEntry.fromJson(
                com.google.gson.JsonParser.parseString(e.toJson().toString()).getAsJsonObject()).retracted("test");
        writeEntry(dir, retracted);
        assertFalse(ReviewTargetGate.check(dir, C, memos.all().get(0)).ok());
    }
}
