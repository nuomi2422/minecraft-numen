package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 证据 id 的真伪核对（2026-11-06，监督者实测后补）。
 *
 * <p>形状合法但根本不存在的事件 id 一样能过契约校验，那会把“有证据”变成假象；
 * 这里守住“必须在日志里查得到”。
 */
class EvidenceJournalTest {

    @Test
    void findsIdPresentInJournalAndReportsMissing(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("tools.jsonl"),
                "{\"event_id\":\"tools-111-0\",\"type\":\"tool_result\"}\n");
        List<String> unresolved = EvidenceJournal.unresolved(dir,
                List.of("tools-111-0", "tools-999-0"));
        assertEquals(List.of("tools-999-0"), unresolved);
    }

    @Test
    void scansRotatedFilesToo(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("tools-1700000000000.jsonl"),
                "{\"event_id\":\"tools-old-0\"}\n");
        Files.writeString(dir.resolve("tools.jsonl"),
                "{\"event_id\":\"tools-new-0\"}\n");
        assertEquals(List.of(), EvidenceJournal.unresolved(dir, List.of("tools-old-0", "tools-new-0")));
    }

    @Test
    void missingDirectoryMeansNothingCanBeVerified(@TempDir Path dir) {
        Path absent = dir.resolve("nope");
        assertEquals(List.of("a", "b"), EvidenceJournal.unresolved(absent, List.of("a", "b")));
    }

    @Test
    void emptyInputIsUnresolvedEmpty(@TempDir Path dir) {
        assertTrue(EvidenceJournal.unresolved(dir, List.of()).isEmpty());
    }
}
