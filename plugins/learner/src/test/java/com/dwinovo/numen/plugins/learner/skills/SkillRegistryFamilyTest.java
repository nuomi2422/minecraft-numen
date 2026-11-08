package com.dwinovo.numen.plugins.learner.skills;

import com.dwinovo.numen.plugins.learner.contract.ArtifactDraft;
import com.dwinovo.numen.plugins.learner.contract.ArtifactKind;
import com.dwinovo.numen.plugins.learner.contract.EvidenceRef;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-08 bug-11: family key catches retry-budget variants text similarity misses. */
class SkillRegistryFamilyTest {
    private static ArtifactDraft draft(String name, String purpose, List<String> triggers) {
        return new ArtifactDraft(ArtifactKind.EXPERIENCE, name, purpose, "body",
                triggers, List.of(), List.of(), "", List.of(EvidenceRef.parse("block:t")),
                List.of("s"), "PLANNER");
    }

    @Test void familyBeatsLowTextScore() {
        SkillRegistry r = new SkillRegistry();
        var retained = draft("引擎的 replan 重试预算按同一子任务重启记账",
                "same subtask restarted beyond retry budget attempts=3 threshold=3 fromNegotiation=false blocked",
                List.of("same subtask restarted beyond retry budget"));
        r.register(retained, "sk-ret", "body:ret");
        var variant = draft("只读轮询不推进", "reason=same subtask restarted beyond retry budget attempts=2 task 未变",
                List.of("reason=same subtask restarted beyond retry budget"));
        assertTrue(r.isDuplicate(variant, "sk-ret"), "family 必须命中，即使文本分低");
        assertEquals("retry-budget/subtask-restart", SkillRegistry.family(variant));
    }

    @Test void unrelatedSkillsAreNotFamilyDuplicates() {
        SkillRegistry r = new SkillRegistry();
        r.register(draft("夜间别出门", "夜里在洞口停留会被围", List.of("夜间")), "sk-ret", "body:1");
        var mine = draft("mine 不负责开路", "封闭腔体里 found iron_ore but no path out", List.of("tool=mine"));
        assertFalse(r.isDuplicate(mine, "sk-ret"));
        assertNull(SkillRegistry.family(mine));
    }

    @Test void sameFamilyAdditionIsRejectedAsDuplicate() {
        SkillRegistry r = new SkillRegistry();
        var first = draft("重试预算家族 1", "same subtask restarted attempts=1 重试预算",
                List.of("same subtask restarted beyond retry budget"));
        assertEquals(SkillRegistry.Outcome.REGISTERED, r.register(first, "sk-f1", "body:f1").outcome());
        var second = draft("重试预算家族 2", "same subtask restarted attempts=2 重试预算",
                List.of("same subtask restarted beyond retry budget"));
        var decision = r.register(second, "sk-f2", "body:f2");
        assertEquals(SkillRegistry.Outcome.LIMIT, decision.outcome(), "同族第二条必须先归并，不得直接写");
        assertFalse(decision.stored());
    }
}
