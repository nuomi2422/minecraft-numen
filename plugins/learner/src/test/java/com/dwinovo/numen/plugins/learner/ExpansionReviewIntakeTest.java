package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.plugins.learner.core.MemoQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-08 C1 bounded/idempotent expansion review consumer. */
class ExpansionReviewIntakeTest {
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000dd");

    private static void experience(Path dir, String id, boolean retracted) throws Exception {
        Files.writeString(dir.resolve("experience-" + C + ".jsonl"),
                "{\"id\":\"" + id + "\",\"publication\":\"TRIAL\",\"retracted\":" + retracted + "}\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    private static String line(String ref, String use) {
        return "{\"companion\":\"" + C + "\",\"ref\":\"" + ref + "\",\"version\":\"v1\",\"use_id\":\"" + use + "\"}";
    }

    @Test void enqueuesAndRemoves(@TempDir Path dir) throws Exception {
        experience(dir, "e1", false);
        Files.writeString(dir.resolve(ExpansionReviewIntake.FILE), line("exp:e1", "u1") + "\n");
        MemoQueue q = new MemoQueue(dir.resolve("memos.json"));
        assertEquals(1, ExpansionReviewIntake.scan(dir, id -> q));
        assertEquals(1, q.size());
        assertEquals("", Files.readString(dir.resolve(ExpansionReviewIntake.FILE)).trim());
    }

    @Test void repeatedEntryDoesNotDuplicate(@TempDir Path dir) throws Exception {
        experience(dir, "e1", false);
        MemoQueue q = new MemoQueue(dir.resolve("memos.json"));
        Files.writeString(dir.resolve(ExpansionReviewIntake.FILE), line("exp:e1", "u1") + "\n");
        assertEquals(1, ExpansionReviewIntake.scan(dir, id -> q));
        Files.writeString(dir.resolve(ExpansionReviewIntake.FILE), line("exp:e1", "u1") + "\n");
        ExpansionReviewIntake.scan(dir, id -> q);
        assertEquals(1, q.size(), "同 ref/version/use 不得重复入队");
    }

    @Test void boundedPerScan(@TempDir Path dir) throws Exception {
        experience(dir, "e1", false);
        experience(dir, "e2", false);
        experience(dir, "e3", false);
        Files.writeString(dir.resolve(ExpansionReviewIntake.FILE),
                line("exp:e1", "u1") + "\n" + line("exp:e2", "u2") + "\n" + line("exp:e3", "u3") + "\n");
        MemoQueue q = new MemoQueue(dir.resolve("memos.json"));
        assertEquals(2, ExpansionReviewIntake.scan(dir, id -> q));
        assertEquals(2, q.size());
        assertEquals(1, Files.readAllLines(dir.resolve(ExpansionReviewIntake.FILE)).size(), "剩余一条留到下轮");
    }

    @Test void retractedEntryIsSkippedNotEnqueued(@TempDir Path dir) throws Exception {
        experience(dir, "e1", true);
        Files.writeString(dir.resolve(ExpansionReviewIntake.FILE), line("exp:e1", "u1") + "\n");
        MemoQueue q = new MemoQueue(dir.resolve("memos.json"));
        assertEquals(0, ExpansionReviewIntake.scan(dir, id -> q));
        assertEquals(0, q.size());
        assertEquals("", Files.readString(dir.resolve(ExpansionReviewIntake.FILE)).trim(), "撤回条目应被消费不再重试");
    }

    @Test void missingEntryIsSkipped(@TempDir Path dir) throws Exception {
        experience(dir, "other", false);
        Files.writeString(dir.resolve(ExpansionReviewIntake.FILE), line("exp:e1", "u1") + "\n");
        MemoQueue q = new MemoQueue(dir.resolve("memos.json"));
        assertEquals(0, ExpansionReviewIntake.scan(dir, id -> q));
        assertEquals(0, q.size());
    }
}
