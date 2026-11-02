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
    void objectiveRelevanceCountsMatchingTerms() {
        // 2026-11-02：TRIAL 并列时按 title 字典序截断，mine 教训被切掉 —— 相关度进排序。
        EntryDescriptor mine = new EntryDescriptor("exp:mine", EntryKind.EXPERIENCE,
                "failure|mine 的 found 64 diamond_ore but could not reach — no path out",
                "found 是世界里存在的数量，gathered 才是能采到的",
                List.of(), List.of("mine 目标不可达"), List.of(), List.of(), "b", false,
                Maturity.OBSERVED, 0, false, true);
        EntryDescriptor craft = new EntryDescriptor("exp:craft", EntryKind.EXPERIENCE,
                "execution|craft 的工作台判定", "半径 4 格内有 crafting_table",
                List.of(), List.of(), List.of(), List.of(), "b", false,
                Maturity.OBSERVED, 0, false, true);
        int mineScore = ExperienceRetrievalProvider.relevanceScore("mine diamond_ore 挖矿 no path out", mine);
        int craftScore = ExperienceRetrievalProvider.relevanceScore("mine diamond_ore 挖矿 no path out", craft);
        assertTrue(mineScore > craftScore, "mine 目标下 mine 条目相关度应更高：" + mineScore + " vs " + craftScore);
        assertEquals(0, ExperienceRetrievalProvider.relevanceScore("", mine));
    }

    /**
     * 2026-11-03（外部学习者）：中文目标的相关度不能恒 0。
     * 实机背景：夜间死亡场景下，库里"夜间露天站桩"的条目排不进 5 个槽位，因为
     * 旧口径把中文整句当一个 token（goal 169 字 → 22 token，仅 1 个命中任意条目）。
     */
    /**
     * 2026-11-03（外部学习者 #475/#481）：条件写紧凑事实表达式时，按 objective 里的
     * <state_facts/> 比较；缺键不许猜成满足。
     */
    @Test
    void compactFactConditionsAreComparedAgainstStateFacts() {
        java.util.Map<String, String> facts = ExperienceRetrievalProvider.stateFacts(
                "<state_facts hp=5 dmg=1 night=0 hostile=1/>");
        assertEquals("5", facts.get("hp"), facts.toString());
        assertTrue(ExperienceRetrievalProvider.factHolds("hp<=6", facts), "hp=5 应满足 hp<=6");
        assertTrue(!ExperienceRetrievalProvider.factHolds("hp>=15", facts), "hp=5 不满足 hp>=15");
        assertTrue(ExperienceRetrievalProvider.factHolds("hostile=1", facts), "hostile=1 应等值命中");
        assertTrue(ExperienceRetrievalProvider.factHolds("dmg=1", facts), "dmg=1 应等值命中");
        assertTrue(!ExperienceRetrievalProvider.factHolds("armor=0", facts), "缺 armor 键 = 不知道 = 不满足");
        assertTrue(!ExperienceRetrievalProvider.factHolds("这是散文条件", facts), "散文条件交回逐字匹配");
    }

    @Test
    void chineseObjectiveRanksTheRelevantEntryHigher() {
        EntryDescriptor night = new EntryDescriptor("exp:night", EntryKind.EXPERIENCE,
                "failure|夜间在露天且方块光很低时停留，僵尸会贴身",
                "身上没有武器也没有护甲，伤害按 tick 结算",
                List.of(), List.of(), List.of(), List.of(), "b", false,
                Maturity.OBSERVED, 0, false, true);
        EntryDescriptor craft = new EntryDescriptor("exp:craft", EntryKind.EXPERIENCE,
                "execution|craft 的工作台判定", "半径 4 格内有 crafting_table",
                List.of(), List.of(), List.of(), List.of(), "b", false,
                Maturity.OBSERVED, 0, false, true);

        String objective = "夜间无甲站在露天，附近有敌对生物";
        int nightScore = ExperienceRetrievalProvider.relevanceScore(objective, night);
        int craftScore = ExperienceRetrievalProvider.relevanceScore(objective, craft);
        assertTrue(nightScore > 0, "中文目标必须能产出非零相关度：" + nightScore);
        assertTrue(nightScore > craftScore, "夜间场景应把夜间条目排在无关条目之前：" + nightScore + " vs " + craftScore);
        assertFalse(ExperienceRetrievalProvider.cjkBigrams(objective).isEmpty(), "中文二元组不应为空");
        assertTrue(ExperienceRetrievalProvider.cjkBigrams("abc 123").isEmpty(), "英文/数字不切二元组");
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

    // G8-K22：TRIAL + OBSERVED 必须能进 EXECUTOR（解环的判据）
    @Test
    void trialWithObservedMaturity_isVisibleToExecutor() {
        ExperienceEntryRead trialObserved = new ExperienceEntryRead(
                "trial-observed", EntryKind.EXPERIENCE, "minecraft:torch 能用来做什么", "火把照明",
                List.of(), List.of(), List.of("ev/1"), Maturity.OBSERVED, 0, false,
                "body", "hash-x", "fake-source", false, true, true);
        ExperienceRetrievalProvider p = new ExperienceRetrievalProvider("exp", sourceOf(trialObserved));
        SearchPage page = p.search(query(RetrievalRole.EXECUTOR, "火把 torch", 20));
        assertEquals(1, page.entries().size(), "TRIAL+OBSERVED 应该被 EXECUTOR 看到，实际 " + page.entries().size());
    }

    // ---- A6（98 号）：audience 收窄 EXECUTOR ----

    @Test
    void audiencePlannerOnly_hiddenFromExecutor_visibleToPlanner() {
        ExperienceEntryRead plannerOnly = new ExperienceEntryRead(
                "planner-only", EntryKind.EXPERIENCE, "规划经验", "summary",
                List.of(), List.of(), List.of("ev/1"), Maturity.VERIFIED, 1, false,
                "body", "hash-1", "fake-source", true, true, false, List.of("PLANNER"));
        ExperienceRetrievalProvider p = new ExperienceRetrievalProvider("exp", sourceOf(plannerOnly));
        assertEquals(0, p.search(query(RetrievalRole.EXECUTOR, "", 20)).entries().size(),
                "audience 不含 EXECUTOR：执行视图不可见");
        assertEquals(1, p.search(query(RetrievalRole.PLANNER, "", 20)).entries().size(),
                "规划视图仍可见");
    }

    @Test
    void legacyEntryWithoutAudience_keepsExecutorVisibility() {
        ExperienceRetrievalProvider p = new ExperienceRetrievalProvider("exp", sourceOf(
                entry("legacy", "旧经验", Maturity.VERIFIED, false, List.of(), List.of())));
        assertEquals(1, p.search(query(RetrievalRole.EXECUTOR, "", 20)).entries().size(),
                "旧条目（无 audience）不得因 A6 被收窄");
    }
}