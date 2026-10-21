package com.dwinovo.numen.agent.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** G5a 判据 S5/S6/S7：装配器的必知区/预算/DENIED/去重。 */
class MinimalContextAssemblerTest {

    private static RetrievedEntry e(String ref, String title, String body) {
        EntryDescriptor d = new EntryDescriptor(ref, EntryKind.EXPERIENCE, title, title + " 摘要",
                List.of(RetrievalRole.PLANNER), List.<String>of(), List.<String>of(), List.<String>of(),
                ref, false, Maturity.VERIFIED, 2, false);
        EntryBody b = new EntryBody(ref, "h-" + ref, "text/plain", body, false, "fake");
        return new RetrievedEntry(d, b, Applicability.UNKNOWN);
    }

    private static RetrievalQuery q(String objective, int maxChars, int maxCandidates) {
        return new RetrievalQuery("q-1", "c-1", "w-1", RetrievalRole.PLANNER, objective,
                List.of(), List.of(), List.of(), 1, "",
                new RetrievalBudget(maxCandidates, 0, 0, 0, maxChars, 0, "none"), "ACTIVE");
    }

    @Test
    void mandatoryObjectiveAlwaysCarried() {
        ContextSnapshot s = MinimalContextAssembler.buildContext("snap-1",
                q("去海边找一艘沉船", 2000, 5),
                List.of(e("e1", "a", "body1")));
        assertEquals("去海边找一艘沉船", s.mandatoryConstraints().get(0), "必知区不截断");
        assertEquals(ContextSnapshot.Status.OK, s.status());
        assertEquals(1, s.directory().size());
    }

    @Test
    void mandatoryOverflowIsDeniedButObjectiveIsVisible() {
        ContextSnapshot s = MinimalContextAssembler.buildContext("snap-2",
                q("这是一段很长的必知目标超过字符预算二十字", /*maxChars*/ 10, 5),
                List.of(e("e1", "a", "body")));
        assertEquals(ContextSnapshot.Status.DENIED, s.status());
        assertTrue(s.mandatoryConstraints().contains("这是一段很长的必知目标超过字符预算二十字"),
                "DENIED 不吞危险信息：objective 原文仍可见");
        assertTrue(s.gaps().stream().anyMatch(g -> "CONSTRAINT_OVERFLOW".equals(g.code())),
                "DENIED 要带机器可读的 reasonCode");
    }

    @Test
    void directoryOverflowMarksPartialAndKeepsTrack() {
        // 三条各约 14 字符（title 3 + summary 6 + body 5 = 14）；
        // maxChars=40 → 两条就能装下 28，第三条会顶爆 → 截第三条。
        ContextSnapshot s = MinimalContextAssembler.buildContext("snap-3",
                q("", 40, 3),
                List.of(e("e1", "标题一", "body1"), e("e2", "标题二", "body2"), e("e3", "标题三", "body3")));
        assertEquals(ContextSnapshot.Status.PARTIAL, s.status(), "可选目录有截断 → PARTIAL（不是 OK）");
        assertEquals(2, s.directory().size());
        assertTrue(s.truncationReasons().size() > 0, "截断原因要可追溯");
        assertTrue(s.gaps().size() > 0);
    }

    @Test
    void duplicateEntryRefEntersOnce() {
        ContextSnapshot s = MinimalContextAssembler.buildContext("snap-4",
                q("", 5000, 5),
                List.of(e("dup", "第一条", "b1"), e("dup", "第二条", "b2")));
        assertEquals(1, s.directory().size(), "同 entryRef 只进一条（ assembler 级去重）");
    }

    @Test
    void nullQueryIsDeniedWithGapNotNpe() {
        ContextSnapshot s = MinimalContextAssembler.buildContext("snap-5", null, List.of());
        assertEquals(ContextSnapshot.Status.DENIED, s.status());
        assertTrue(s.gaps().stream().anyMatch(g -> "QUERY_NULL".equals(g.code())));
    }

    @Test
    void emptyCandidatesIsOkNotPartial() {
        ContextSnapshot s = MinimalContextAssembler.buildContext("snap-6",
                q("", 500, 5), List.of());
        assertEquals(ContextSnapshot.Status.OK, s.status(), "真没召回 ≠ 装不下；PARTIAL 只在「截过」时用");
        assertTrue(s.directory().isEmpty());
        assertTrue(s.truncationReasons().isEmpty());
    }
}
