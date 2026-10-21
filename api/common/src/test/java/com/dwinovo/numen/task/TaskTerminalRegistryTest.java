package com.dwinovo.numen.task;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * AC-B12b-3 回归（2026-10-21 长跑实测）：<b>终态表必须独立于 brain 存活</b>。
 *
 * <p>死亡时 {@code CompanionTickDispatcher.onCompanionRemoved} 会 {@code BRAINS.remove(id)}；
 * 修前终态记录住在 brain 里，于是复活后的探针按 id 查不到 → stale-null → UNKNOWN。
 * 这里钉住：表按 UUID 独立，<b>没有 brain 也查得到</b>。
 */
class TaskTerminalRegistryTest {

    /** TaskRecord 是 abstract；测试只关心 id/终态，造一个最小子类（同 RecentFinishedTasksTest）。 */
    private static final class FakeRecord extends TaskRecord {
        FakeRecord() {
            super("goto", "call-" + System.nanoTime(), 0L);
        }
    }

    private static TaskRecord rec(TaskState state) {
        TaskRecord r = new FakeRecord();
        r.setState(state);
        return r;
    }

    private final UUID companion = UUID.randomUUID();

    @AfterEach
    void cleanup() {
        TaskTerminalRegistry.clear(companion);
    }

    @Test
    void 没有brain也能按id查到终态() {
        TaskRecord t = rec(TaskState.CANCELLED);
        TaskTerminalRegistry.remember(companion, t);

        // 关键：整个测试里没有创建任何 CompanionBrain（BRAINS 里没有这个 uuid）
        TaskRecord found = CompanionTickDispatcher.finishedTaskFor(companion, t.publicId());
        assertNotNull(found, "brain 不在时（死亡后）必须仍查得到终态");
        assertSame(t, found);
        assertEquals(TaskState.CANCELLED, found.getState());
    }

    @Test
    void 不同同伴互不串() {
        UUID other = UUID.randomUUID();
        try {
            TaskRecord a = rec(TaskState.SUCCESS);
            TaskRecord b = rec(TaskState.FAILED);
            TaskTerminalRegistry.remember(companion, a);
            TaskTerminalRegistry.remember(other, b);

            assertSame(a, TaskTerminalRegistry.find(companion, a.publicId()));
            assertNull(TaskTerminalRegistry.find(companion, b.publicId()), "别的同伴的记录不许串进来");
            assertSame(b, TaskTerminalRegistry.find(other, b.publicId()));
            assertNull(TaskTerminalRegistry.find(other, a.publicId()));
        } finally {
            TaskTerminalRegistry.clear(other);
        }
    }

    @Test
    void 每个同伴上限32条_最老被淘汰() {
        TaskRecord first = null;
        TaskRecord last = null;
        for (int i = 0; i < TaskTerminalRegistry.MAX_PER_COMPANION + 5; i++) {
            TaskRecord r = rec(TaskState.SUCCESS);
            if (i == 0) first = r;
            last = r;
            TaskTerminalRegistry.remember(companion, r);
        }
        assertEquals(TaskTerminalRegistry.MAX_PER_COMPANION, TaskTerminalRegistry.size(companion), "表必须有界");
        assertNull(TaskTerminalRegistry.find(companion, first.publicId()), "最老的记录应被淘汰");
        assertNotNull(TaskTerminalRegistry.find(companion, last.publicId()), "最新记录必须还在");
    }

    @Test
    void 空参数不制造假命中() {
        TaskTerminalRegistry.remember(null, rec(TaskState.SUCCESS));
        TaskTerminalRegistry.remember(companion, null);
        assertEquals(0, TaskTerminalRegistry.size(companion));
        assertNull(TaskTerminalRegistry.find(null, "t1"));
        assertNull(TaskTerminalRegistry.find(companion, null));
        assertNull(TaskTerminalRegistry.find(companion, "  "));
    }
}
