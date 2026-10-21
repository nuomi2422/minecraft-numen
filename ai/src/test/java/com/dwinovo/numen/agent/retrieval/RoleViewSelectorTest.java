package com.dwinovo.numen.agent.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** G5b 判据 S3/S4/S5：三视图不同；EXECUTOR 只见当前有效；LEARNER 含撤回。 */
class RoleViewSelectorTest {

    private static RetrievedEntry e(String ref, EntryKind kind, Maturity m, boolean retracted,
                                    List<RetrievalRole> roles) {
        EntryDescriptor d = new EntryDescriptor(ref, kind, "标题 " + ref, "摘要 " + ref,
                roles, List.<String>of(), List.<String>of(), List.<String>of(),
                ref, retracted, m, 1, false);
        EntryBody b = new EntryBody(ref, "h-" + ref, "text/plain", "body", false, "fake");
        return new RetrievedEntry(d, b, Applicability.UNKNOWN);
    }

    private static ContextSnapshot snapshot() {
        return new ContextSnapshot(
                "snap-1", "q-1", RetrievalRole.PLANNER,
                List.of(),
                List.of(
                        e("verified", EntryKind.EXPERIENCE, Maturity.VERIFIED, false,
                                List.of(RetrievalRole.PLANNER, RetrievalRole.EXECUTOR, RetrievalRole.LEARNER)),
                        e("observed", EntryKind.EXPERIENCE, Maturity.OBSERVED, false,
                                List.of(RetrievalRole.PLANNER, RetrievalRole.LEARNER)),
                        e("retracted", EntryKind.EXPERIENCE, Maturity.VERIFIED, true,
                                List.of(RetrievalRole.PLANNER, RetrievalRole.LEARNER)),
                        e("warning", EntryKind.WARNING, Maturity.OBSERVED, false,
                                List.of(RetrievalRole.PLANNER, RetrievalRole.EXECUTOR, RetrievalRole.LEARNER))
                ),
                List.of(), List.of(), ContextSnapshot.Status.OK);
    }

    private static Set<String> refs(ContextSnapshot s) {
        return s.directory().stream().map(r -> r.descriptor().entryRef()).collect(Collectors.toSet());
    }

    @Test
    void threeRolesYieldDifferentViews() {
        ContextSnapshot base = snapshot();
        Set<String> planner = refs(RoleViewSelector.select(base, RetrievalRole.PLANNER));
        Set<String> executor = refs(RoleViewSelector.select(base, RetrievalRole.EXECUTOR));
        Set<String> learner = refs(RoleViewSelector.select(base, RetrievalRole.LEARNER));
        // PLANNER/LEARNER 都能见四条（同一个 base）；EXECUTOR 少两条（撤回 + OBSERVED + WARNING）
        assertEquals(planner, learner, "PLANNER 和 LEARNER 最小实现里同视野（含待核实线索）");
        assertTrue(executor.size() < planner.size(), "EXECUTOR 视图必须窄");
        assertTrue(!executor.contains("observed"), "EXECUTOR 不得见 OBSERVED");
        assertTrue(!executor.contains("retracted"), "EXECUTOR 不得见撤回");
        assertTrue(!executor.contains("warning"), "EXECUTOR 警告条目不进目录");
        assertTrue(planner.contains("warning"), "PLANNER 应见 WARNING");
        assertTrue(learner.contains("verified"), "LEARNER 应见 VERIFIED");
    }

    @Test
    void executorViewIsExecutorOnly() {
        ContextSnapshot ex = RoleViewSelector.select(snapshot(), RetrievalRole.EXECUTOR);
        Set<String> refs = refs(ex);
        assertEquals(Set.of("verified"), refs,
                "EXECUTOR 最终只留「MATURITY≥VERIFIED 且未撤回且 kind≠WARNING」这一条");
        assertEquals(RetrievalRole.EXECUTOR, ex.role(), "select 后 role 要被覆写到 EXECUTOR");
    }

    @Test
    void learnerViewKeepsAllHints() {
        ContextSnapshot lr = RoleViewSelector.select(snapshot(), RetrievalRole.LEARNER);
        Set<String> refs = refs(lr);
        assertTrue(refs.contains("observed"), "LEARNER 应见 OBSERVED（复盘线索）");
        assertTrue(refs.contains("retracted"), "LEARNER 应见撤回（复盘教训）");
    }

    @Test
    void nullArgsThrow() {
        try {
            RoleViewSelector.select(null, RetrievalRole.PLANNER);
            throw new AssertionError("snapshot=null 应抛异常");
        } catch (NullPointerException actual) { /* expected */ }
        try {
            RoleViewSelector.select(snapshot(), null);
            throw new AssertionError("role=null 应抛异常");
        } catch (NullPointerException actual) { /* expected */ }
    }
}
