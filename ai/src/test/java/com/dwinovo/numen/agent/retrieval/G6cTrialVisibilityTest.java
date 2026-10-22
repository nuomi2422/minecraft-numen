package com.dwinovo.numen.agent.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G6c 判据 C1：**TRIAL 要能被用到**。
 *
 * <p>G6c 之前 TRIAL 被 `published=false` 挡在执行视图外 —— 于是试用条目永远不会被用到，
 * 也就永远攒不出试用证据（死状态）。现在它进得去，但标题上带「（试用中）」，
 * 且 DRAFT / SUSPENDED / REVOKED 仍然不进。
 */
class G6cTrialVisibilityTest {

    private static ExperienceEntryRead read(String ref, boolean published, boolean trial, boolean retrievable) {
        return new ExperienceEntryRead(ref, EntryKind.EXPERIENCE, "标题 " + ref, "摘要",
                List.<String>of(), List.<String>of(), List.<String>of(),
                Maturity.VERIFIED, 1, false, "正文", "h-" + ref, "fake",
                published, retrievable, trial);
    }

    private static ExperienceRetrievalProvider provider() {
        return new ExperienceRetrievalProvider("experience", () -> List.of(
                read("exp:published", true, false, true),
                read("exp:trial", false, true, true),
                read("exp:draft", false, false, true),
                read("exp:suspended", false, false, true),
                read("exp:revoked", false, false, false)));
    }

    private static RetrievalQuery q(RetrievalRole role) {
        return new RetrievalQuery("q-1", "c-1", "w-1", role, "目标",
                List.<String>of(), List.<String>of(), List.<String>of(), 1, "",
                new RetrievalBudget(10, 0, 0, 0, 4000, 0, "none"), "ACTIVE");
    }

    @Test
    void trialReachesTheExecutorView() {
        List<RetrievedEntry> hits = provider().search(q(RetrievalRole.EXECUTOR)).entries();
        List<String> refs = hits.stream().map(h -> h.descriptor().entryRef()).collect(Collectors.toList());
        assertTrue(refs.contains("exp:trial"), "TRIAL 必须进执行视图，否则试用永远没有证据");
        assertTrue(refs.contains("exp:published"), "PUBLISHED 照常");
        assertEquals(2, refs.size(), "执行视图只该有这两条：" + refs);
    }

    @Test
    void trialIsMarkedInTheTitle() {
        List<RetrievedEntry> hits = provider().search(q(RetrievalRole.EXECUTOR)).entries();
        String trialTitle = hits.stream()
                .filter(h -> "exp:trial".equals(h.descriptor().entryRef()))
                .map(h -> h.descriptor().title())
                .findFirst().orElseThrow();
        assertTrue(trialTitle.startsWith("（试用中）"), "试用条目要一眼看得出来：" + trialTitle);
    }

    @Test
    void draftAndSuspendedStillStayOut() {
        List<String> refs = provider().search(q(RetrievalRole.EXECUTOR)).entries().stream()
                .map(h -> h.descriptor().entryRef()).collect(Collectors.toList());
        assertTrue(!refs.contains("exp:draft"), "DRAFT 不许进执行视图");
        assertTrue(!refs.contains("exp:suspended"), "SUSPENDED 不许进执行视图");
        assertTrue(!refs.contains("exp:revoked"), "REVOKED 任何视图都不给");
    }

    @Test
    void plannerStillSeesEverythingButRevoked() {
        List<String> refs = provider().search(q(RetrievalRole.PLANNER)).entries().stream()
                .map(h -> h.descriptor().entryRef()).collect(Collectors.toList());
        assertTrue(refs.contains("exp:draft"), "规划视图要看见待核实线索");
        assertTrue(!refs.contains("exp:revoked"), "REVOKED 连规划也不给");
    }
}
