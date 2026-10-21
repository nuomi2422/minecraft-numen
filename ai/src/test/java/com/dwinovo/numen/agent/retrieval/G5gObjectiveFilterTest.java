package com.dwinovo.numen.agent.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G5g-2：目录 provider 的 objective 过滤 —— 含中文触发词的回归守卫。
 *
 * <p>2026-10-22 实机教训：执行侧的 {@code current_task} 是中文（「走到…据点中心」），
 * 工具描述是英文（"Travel to ONE new destination…"），只比 title/summary **永远不命中**。
 * 修法是把中文触发词放进正文（不占块内显示位），并把正文也算进 haystack。
 */
class G5gObjectiveFilterTest {

    private static ExperienceEntryRead read(String ref, String title, String summary, String body) {
        return new ExperienceEntryRead(ref, EntryKind.PROCEDURE, title, summary,
                List.<String>of(), List.<String>of(), List.<String>of(),
                Maturity.VERIFIED, 0, false, body, "h-" + ref, "tool-catalog", true, true);
    }

    private static ToolCatalogRetrievalProvider provider() {
        return new ToolCatalogRetrievalProvider(() -> List.of(
                read("tool:goto", "goto", "Travel to ONE new destination",
                        "goto\nTravel to ONE new destination\n中文触发词: 走,过去,前往,去,到,靠近,走到"),
                read("tool:sleep", "sleep", "Get into a bed you are already standing next to",
                        "sleep\nGet into a bed\n中文触发词: 睡,睡觉,床,过夜"),
                read("tool:craft", "craft", "Craft something",
                        "craft\nCraft something\n中文触发词: 合成,做,制作,造")),
                true);
    }

    private static RetrievalQuery q(String objective) {
        return new RetrievalQuery("q-1", "c-1", "w-1", RetrievalRole.EXECUTOR, objective,
                List.<String>of(), List.<String>of(), List.<String>of(), 1, "",
                new RetrievalBudget(6, 0, 0, 0, 800, 0, "none"), "ACTIVE");
    }

    @Test
    void chineseObjectiveMatchesViaBodyKeywords() {
        List<RetrievedEntry> hits = provider().search(q("走到据点中心并停留")).entries();
        assertFalse(hits.isEmpty(), "中文任务必须能命中（这正是实机踩到的坑）");
        assertEquals("tool:goto", hits.get(0).descriptor().entryRef());
        assertTrue(hits.get(0).descriptor().viewOnly(), "命中归命中，仍然是只读目录");
    }

    @Test
    void chineseObjectiveMatchesSleepToo() {
        List<RetrievedEntry> hits = provider().search(q("天黑了，去睡觉")).entries();
        assertFalse(hits.isEmpty(), "「睡觉」要能命中 sleep");
        assertEquals("tool:sleep", hits.get(0).descriptor().entryRef());
    }

    @Test
    void unrelatedObjectiveYieldsNothing() {
        assertTrue(provider().search(q("今天天气不错")).entries().isEmpty(),
                "不相关就一条都不放（防止挤掉经验）");
    }

    @Test
    void emptyObjectiveYieldsNothing() {
        assertTrue(provider().search(q("")).entries().isEmpty(),
                "没有 objective（老执行侧）⇒ 一条都不放");
    }

    @Test
    void atMostTwoEntries() {
        List<RetrievedEntry> hits = provider().search(q("走过去睡觉然后合成东西")).entries();
        assertTrue(hits.size() <= 2, "硬上限 2 条，实得 " + hits.size());
    }
}
