package com.dwinovo.numen.agent.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G5a 判据 S3/S4/S7：experience provider 的 Role 过滤 / 空结果不带假成功 /
 * maxCandidates 截断可追溯 / entryRef 去重。
 */
class ExperienceRetrievalProviderTest {

    private static ExperienceEntryRead entry(String ref, String title, Maturity m, boolean retracted,
                                             List<String> applicable, List<String> excluded) {
        return new ExperienceEntryRead(ref, EntryKind.EXPERIENCE, title, "summary " + title,
                applicable, excluded, List.of("ev/" + ref), m, 1, retracted,
                "body of " + title, "hash-" + ref, "fake-source", true, true);
    }

    private static ExperienceEntrySource sourceOf(ExperienceEntryRead... entries) {
        return () -> List.of(entries);
    }

    private static RetrievalQuery query(RetrievalRole role, String objective, int maxCandidates) {
        return new RetrievalQuery("q-1", "c-1", "w-1", role, objective,
                List.of(), List.of(), List.of(), 1, "",
                new RetrievalBudget(maxCandidates, 0, 0, 0, 2000, 0, "none"), "ACTIVE");
    }

    @Test
    void executorSeesOnlyVerifiedNonRetracted() {
        ExperienceRetrievalProvider p = new ExperienceRetrievalProvider("exp",
                sourceOf(
                        entry("verified", "已验证经验", Maturity.VERIFIED, false, List.of(), List.of()),
                        entry("observed", "观察经验", Maturity.OBSERVED, false, List.of(), List.of()),
                        entry("retracted", "撤回经验", Maturity.VERIFIED, true, List.of(), List.of())));
        SearchPage page = p.search(query(RetrievalRole.EXECUTOR, "", 20));
        assertEquals(1, page.entries().size(), "EXECUTOR 只能见 ≥VERIFIED 且未撤回");
        assertEquals("verified", page.entries().get(0).descriptor().entryRef());
        assertFalse(page.partial());
    }

    @Test
    void plannerSeesDraftAndRetractedAsHints() {
        ExperienceRetrievalProvider p = new ExperienceRetrievalProvider("exp",
                sourceOf(
                        entry("observed", "观察经验", Maturity.OBSERVED, false, List.of(), List.of()),
                        entry("retracted", "撤回经验", Maturity.VERIFIED, true, List.of(), List.of())));
        SearchPage page = p.search(query(RetrievalRole.PLANNER, "", 20));
        assertEquals(2, page.entries().size(), "PLANNER 见全部（当待核实线索，不冒充可执行）");
        assertEquals("observed", page.entries().get(0).descriptor().entryRef(),
                "排序按成熟度，VERIFIED 的撤回应排 OBSERVED 之后");
    }

    @Test
    void nullSourceReturnsGapNotSilentEmpty() {
        ExperienceRetrievalProvider p = new ExperienceRetrievalProvider("exp", () -> null);
        SearchPage page = p.search(query(RetrievalRole.PLANNER, "", 20));
        assertTrue(page.entries().isEmpty());
        assertTrue(page.partial(), "读失败 ≠ 成功的空结果（IC09）");
        assertEquals(1, page.gaps().size());
        assertEquals("SOURCE_NULL", page.gaps().get(0).code());
    }

    @Test
    void maxCandidatesTruncationEmitsGap() {
        ExperienceRetrievalProvider p = new ExperienceRetrievalProvider("exp", sourceOf(
                entry("e1", "a", Maturity.VERIFIED, false, List.of(), List.of()),
                entry("e2", "b", Maturity.VERIFIED, false, List.of(), List.of()),
                entry("e3", "c", Maturity.VERIFIED, false, List.of(), List.of())));
        SearchPage page = p.search(query(RetrievalRole.PLANNER, "", 2));
        assertEquals(2, page.entries().size());
        assertTrue(page.partial(), "截过条目必须留痕（不许无声丢）");
        assertTrue(page.gaps().stream().anyMatch(g -> "MAX_CANDIDATES".equals(g.code())));
    }

    @Test
    void duplicateEntryRefIsCollapsedInsideProvider() {
        ExperienceRetrievalProvider p = new ExperienceRetrievalProvider("exp", sourceOf(
                entry("dup", "第一条", Maturity.VERIFIED, false, List.of(), List.of()),
                entry("dup", "第二条（同 ref）", Maturity.OBSERVED, false, List.of(), List.of())));
        SearchPage page = p.search(query(RetrievalRole.PLANNER, "", 20));
        assertEquals(1, page.entries().size(), "同 entryRef 在 provider 内只留一次");
    }

    @Test
    void applicabilityPassOrUnknownNeverScopedToExecutor() {
        ExperienceRetrievalProvider p = new ExperienceRetrievalProvider("exp", sourceOf(
                entry("pass", "命中适用", Maturity.VERIFIED, false, List.of("挖矿"), List.of()),
                entry("fail", "命中排除", Maturity.VERIFIED, false, List.of(), List.of("下界")),
                entry("unknown", "啥都没配", Maturity.VERIFIED, false, List.of(), List.of())));
        SearchPage page = p.search(query(RetrievalRole.PLANNER, "我想去下界挖矿", 20));
        assertEquals(Applicability.UNKNOWN, byRef(page, "unknown"));
        assertEquals(Applicability.FAIL, byRef(page, "fail"));
        assertEquals(Applicability.PASS, byRef(page, "pass"));
    }

    private static Applicability byRef(SearchPage page, String ref) {
        return page.entries().stream().filter(e -> ref.equals(e.descriptor().entryRef())).findFirst()
                .orElseThrow().applicability();
    }

    @Test
    void missingEntryRefIsRecordedAsGap() {
        ExperienceRetrievalProvider p = new ExperienceRetrievalProvider("exp", sourceOf(
                entry(null, "没有ref", Maturity.OBSERVED, false, List.of(), List.of())));
        SearchPage page = p.search(query(RetrievalRole.PLANNER, "", 20));
        assertTrue(page.entries().isEmpty());
        assertTrue(page.gaps().stream().anyMatch(g -> "MISSING_REF".equals(g.code())));
    }

    @Test
    void 未发布的已验证经验不进执行视图但进规划视图() {
        ExperienceEntryRead draft = new ExperienceEntryRead("exp:draft", EntryKind.EXPERIENCE, "草稿经验", "s",
                List.of(), List.of(), List.of(), Maturity.VERIFIED, 3, false,
                "body", "hash", "fake", false, true);
        ExperienceRetrievalProvider p = new ExperienceRetrievalProvider("exp", sourceOf(draft));

        assertTrue(p.search(query(RetrievalRole.EXECUTOR, "", 8)).entries().isEmpty(),
                "G6a：未发布（published=false）不许进执行视图，哪怕已验证");
        assertFalse(p.search(query(RetrievalRole.PLANNER, "", 8)).entries().isEmpty(),
                "规划视图仍应看到它（待核实线索）");

        ExperienceEntryRead published = new ExperienceEntryRead("exp:pub", EntryKind.EXPERIENCE, "已发布", "s",
                List.of(), List.of(), List.of(), Maturity.VERIFIED, 3, false,
                "body", "hash", "fake", true, true);
        assertEquals(1, new ExperienceRetrievalProvider("exp", sourceOf(published))
                .search(query(RetrievalRole.EXECUTOR, "", 8)).entries().size(), "已发布的应可见");
    }

    @Test
    void 撤回的条目三种视图都看不见() {
        ExperienceEntryRead revoked = new ExperienceEntryRead("exp:rev", EntryKind.EXPERIENCE, "已撤回", "s",
                List.of(), List.of(), List.of(), Maturity.VERIFIED, 3, false,
                "body", "hash", "fake", true, false);
        ExperienceRetrievalProvider p = new ExperienceRetrievalProvider("exp", sourceOf(revoked));
        for (RetrievalRole role : new RetrievalRole[]{RetrievalRole.PLANNER, RetrievalRole.EXECUTOR, RetrievalRole.LEARNER}) {
            assertTrue(p.search(query(role, "", 8)).entries().isEmpty(),
                    "G6a-2：REVOKED（retrievable=false）不许出现在 " + role + " 视图");
        }
    }
}