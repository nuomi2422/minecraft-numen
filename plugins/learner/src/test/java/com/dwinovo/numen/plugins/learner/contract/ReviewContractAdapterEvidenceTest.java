package com.dwinovo.numen.plugins.learner.contract;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 取证优先级：memo 自带证据 → snapshot window_first（2026-11-06，监督者实测后补）。
 *
 * <p>背景：人工用 learner_note 提交的 memo 没有 window_first，契约证据恒空，
 * 四条真实教训连续被判 claim_without_evidence。这里钉住“结构化证据优先、坏条只丢自己”。
 */
class ReviewContractAdapterEvidenceTest {

    @Test
    void memoEvidenceWinsOverWindowFirst() {
        List<EvidenceRef> out = ReviewContractAdapter.evidenceOf(
                List.of("tools-1-0"), "tools-2-0");
        assertEquals(2, out.size(), out.toString());
        assertTrue(out.get(0).id().contains("tools-1-0"), out.toString());
    }

    @Test
    void fallsBackToWindowFirstWhenNoMemoEvidence() {
        List<EvidenceRef> out = ReviewContractAdapter.evidenceOf(List.of(), "tools-2-0");
        assertEquals(1, out.size());
    }

    @Test
    void dropsOnlyTheBadEntry() {
        List<EvidenceRef> out = ReviewContractAdapter.evidenceOf(
                List.of("", "   ", "tools-good-0"), null);
        assertEquals(1, out.size(), out.toString());
        assertTrue(out.get(0).id().contains("tools-good-0"), out.toString());
    }

    @Test
    void dedupesSameId() {
        List<EvidenceRef> out = ReviewContractAdapter.evidenceOf(
                List.of("event:tools-1-0", "tools-1-0"), "tools-1-0");
        assertEquals(1, out.size(), out.toString());
    }

    @Test
    void capsAtEight() {
        List<String> raw = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) raw.add("tools-" + i + "-0");
        assertEquals(8, ReviewContractAdapter.evidenceOf(raw, null).size());
    }
}
