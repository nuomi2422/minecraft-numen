package com.dwinovo.numen.agent.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G5g 判据 Y1 / Y2 / Y4：
 * <ul>
 *   <li>Y1：`tool:` 前缀**强制** viewOnly=true（不靠调用方记性）；经验条目恒 false。</li>
 *   <li>Y2：只读目录条目进 EXECUTOR 视图（它没有成熟度概念），但 OBSERVED 经验仍然不进。</li>
 *   <li>Y4：执行入口对 viewOnly 条目恒拒（{@code EXECUTION_FROM_VIEW_ONLY_REF}），
 *       对非只读条目也没有通道（{@code NO_EXECUTION_PATH}）；ref 与元数据打架时按拒处理。</li>
 * </ul>
 */
class G5gViewOnlyAndGateTest {

    private static RetrievedEntry e(String ref, EntryKind kind, Maturity m, List<RetrievalRole> roles) {
        EntryDescriptor d = new EntryDescriptor(ref, kind, "标题 " + ref, "摘要 " + ref, roles,
                List.<String>of(), List.<String>of(), List.<String>of(), ref, false, m, 1, false);
        EntryBody b = new EntryBody(ref, "h-" + ref, "text/plain", "body", false, "fake");
        return new RetrievedEntry(d, b, Applicability.UNKNOWN);
    }

    private static EntryDescriptor descriptor(String ref, EntryKind kind, Maturity m, boolean viewOnly) {
        return new EntryDescriptor(ref, kind, "t", "s", List.of(RetrievalRole.EXECUTOR),
                List.<String>of(), List.<String>of(), List.<String>of(), ref, false, m, 1, viewOnly);
    }

    @Test
    void toolPrefixForcesViewOnly() {
        EntryDescriptor d = descriptor("tool:goto", EntryKind.PROCEDURE, Maturity.OBSERVED, false);
        assertTrue(d.viewOnly(), "tool: 前缀必须把 viewOnly 强制成 true（调用方传 false 也不行）");
    }

    @Test
    void experienceIsNotViewOnly() {
        EntryDescriptor d = descriptor("exp:mining", EntryKind.EXPERIENCE, Maturity.VERIFIED, false);
        assertFalse(d.viewOnly(), "经验条目不是只读目录");
    }

    @Test
    void executorKeepsToolEntriesButStillFiltersObservedExperience() {
        ContextSnapshot base = new ContextSnapshot("s-1", "q-1", RetrievalRole.PLANNER, List.of(),
                List.of(
                        e("tool:goto", EntryKind.PROCEDURE, Maturity.OBSERVED,
                                List.of(RetrievalRole.PLANNER, RetrievalRole.EXECUTOR)),
                        e("exp:old", EntryKind.EXPERIENCE, Maturity.OBSERVED,
                                List.of(RetrievalRole.PLANNER, RetrievalRole.EXECUTOR))),
                List.of(), List.of(), ContextSnapshot.Status.OK);

        Set<String> refs = RoleViewSelector.select(base, RetrievalRole.EXECUTOR).directory().stream()
                .map(x -> x.descriptor().entryRef())
                .collect(Collectors.toSet());

        assertTrue(refs.contains("tool:goto"), "只读目录条目要进 EXECUTOR（它没有成熟度概念）");
        assertFalse(refs.contains("exp:old"), "OBSERVED 经验仍然不进 EXECUTOR（成熟度门不变）");
    }

    @Test
    void gateRejectsViewOnlyEntries() {
        EntryDescriptor tool = descriptor("tool:goto", EntryKind.PROCEDURE, Maturity.VERIFIED, true);
        ExecutionGate.Verdict v = ExecutionGate.check(new EntryRef("tool:goto"), tool);
        assertFalse(v.allowed(), "只读目录条目不许执行");
        assertEquals(ExecutionGate.CODE_VIEW_ONLY, v.code());
        assertTrue(v.message().contains("只读"), "拒绝要说清原因，不能空口");
    }

    @Test
    void gateHasNoPathForExperienceEither() {
        EntryDescriptor exp = descriptor("exp:mining", EntryKind.EXPERIENCE, Maturity.VERIFIED, false);
        ExecutionGate.Verdict v = ExecutionGate.check(new EntryRef("exp:mining"), exp);
        assertFalse(v.allowed(), "本批没有任何「从检索结果执行」的通道");
        assertEquals(ExecutionGate.CODE_NO_PATH, v.code());
    }

    @Test
    void gateRejectsWhenRefAndDescriptorDisagree() {
        EntryDescriptor lying = descriptor("exp:mining", EntryKind.EXPERIENCE, Maturity.VERIFIED, false);
        ExecutionGate.Verdict v = ExecutionGate.check(new EntryRef("tool:goto"), lying);
        assertFalse(v.allowed(), "两份证据打架时按「不许执行」");
        assertEquals(ExecutionGate.CODE_VIEW_ONLY, v.code());
    }

    @Test
    void gateRejectsMissingDescriptor() {
        ExecutionGate.Verdict v = ExecutionGate.check(new EntryRef("tool:goto"), null);
        assertFalse(v.allowed());
        assertEquals("BAD_DESCRIPTOR", v.code());
    }
}
