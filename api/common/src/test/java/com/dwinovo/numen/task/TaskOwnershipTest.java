package com.dwinovo.numen.task;

import com.dwinovo.numen.task.TaskOwnership.Current;
import com.dwinovo.numen.task.TaskOwnership.Decision;
import com.dwinovo.numen.task.TaskOwnership.Owner;
import com.dwinovo.numen.task.TaskOwnership.Request;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 037/T5 第一轮 · OWN-01：先冻结"谁能顶替谁"，不接线。
 *
 * <p>这些用例就是竞争矩阵：空槽、同 owner 替换、不同 owner 竞争、主人抢占、安全强占、
 * 陈旧代次、没有 owner 的旧调用方。
 */
class TaskOwnershipTest {

    private static Request req(Owner owner, long gen, boolean emergency, String source) {
        return new Request("companion-1", "task-" + owner + "-" + gen, owner, gen, source, emergency);
    }

    private static Request plain(Owner owner) {
        return req(owner, 1, false, "unit-test");
    }

    @Test
    void emptySlotAlwaysAccepts() {
        assertEquals(Decision.ACCEPT, TaskOwnership.decide(null, plain(Owner.BUILTIN_BRAIN)).decision());
        assertEquals(Decision.ACCEPT, TaskOwnership.decide(null, plain(Owner.UNKNOWN)).decision(),
                "槽空时旧调用方也能干活，不该因为没 owner 就拒绝");
    }

    @Test
    void sameOwnerReplacesAndExplains() {
        var out = TaskOwnership.decide(
                new Current("old-task", Owner.BUILTIN_BRAIN, 1),
                req(Owner.BUILTIN_BRAIN, 2, false, "tool:walk_to"));
        assertEquals(Decision.REPLACE, out.decision(), "同一 owner 换任务是常态");
        assertTrue(out.reason().contains("old-task"), "替换必须说明旧 task: " + out.reason());
    }

    @Test
    void differentOwnerGetsBusyOrConflictNotSilentReplace() {
        var out = TaskOwnership.decide(
                new Current("brain-task", Owner.BUILTIN_BRAIN, 1),
                plain(Owner.EXTERNAL_BRAIN));
        assertTrue(out.decision() == Decision.BUSY || out.decision() == Decision.CONFLICT,
                "不同 owner 不许静默替换，实际=" + out.decision());
        assertNotNull(out.reason());
        assertFalse(out.reason().isBlank());
    }

    @Test
    void oldCallerWithoutOwnerNeverGetsTopAuthority() {
        var out = TaskOwnership.decide(
                new Current("brain-task", Owner.BUILTIN_BRAIN, 1),
                plain(Owner.UNKNOWN));
        assertTrue(out.decision() == Decision.BUSY || out.decision() == Decision.CONFLICT,
                "没有 owner 的旧调用方不能顶替有名有姓的占位者，实际=" + out.decision());
    }

    @Test
    void ownerPreemptionIsExplicit() {
        var out = TaskOwnership.decide(
                new Current("brain-task", Owner.BUILTIN_BRAIN, 1),
                req(Owner.OWNER, 2, false, "panel:stop"));
        assertEquals(Decision.REPLACE, out.decision(), "主人明确抢占必须能生效");
        assertTrue(out.reason().toLowerCase().contains("owner"), out.reason());
    }

    @Test
    void emergencyNeedsAnExplicitSource() {
        var withSource = TaskOwnership.decide(
                new Current("brain-task", Owner.BUILTIN_BRAIN, 1),
                req(Owner.SAFETY, 2, true, "safety:fall"));
        assertEquals(Decision.REPLACE, withSource.decision(), "有显式来源的安全强占可以顶替");
        assertTrue(withSource.reason().toLowerCase().contains("safety"), withSource.reason());

        var withoutSource = TaskOwnership.decide(
                new Current("brain-task", Owner.BUILTIN_BRAIN, 1),
                req(Owner.SAFETY, 2, true, "  "));
        assertTrue(withoutSource.decision() == Decision.CONFLICT,
                "紧急通道不给来源就是不可审计的强占，实际=" + withoutSource.decision());
    }

    @Test
    void staleGenerationIsRejected() {
        var out = TaskOwnership.decide(
                new Current("task-9", Owner.BUILTIN_BRAIN, 5),
                req(Owner.BUILTIN_BRAIN, 4, false, "tool:late"));
        assertEquals(Decision.CONFLICT, out.decision(), "迟到的旧代次不许顶替新代");
        assertTrue(out.reason().contains("generation") || out.reason().contains("代次"), out.reason());
    }

    @Test
    void replacingNeverClaimsTheOldTaskCompleted() {
        var out = TaskOwnership.decide(
                new Current("old-task", Owner.BUILTIN_BRAIN, 1),
                req(Owner.BUILTIN_BRAIN, 2, false, "tool:walk_to"));
        assertEquals(Decision.REPLACE, out.decision());
        assertFalse(out.reason().contains("completed"), "替换不是完成: " + out.reason());
    }

    @Test
    void nullRequestConflictsInsteadOfGuessing() {
        assertEquals(Decision.CONFLICT, TaskOwnership.decide(null, null).decision());
    }
}
