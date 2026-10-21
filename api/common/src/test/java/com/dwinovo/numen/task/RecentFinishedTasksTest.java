package com.dwinovo.numen.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * AC-B12b 复审回归：终态记录必须能按任务号查，不能被后来的任务覆盖。
 *
 * <p>为什么这条必须有：{@code AcxFinishedTask.map} 只是纯映射，它证明不了「探针拿到的
 * publicId 一定是被问的那个任务」。单槽 {@code lastFinished} 在 t1 成功后又被 t2 覆盖时，
 * 探针按 t1 查会得到 t2 → 把真正成功的任务报成失败（false negative）。这里钉住「按 id 查」。
 */
class RecentFinishedTasksTest {

    /** TaskRecord 是 abstract（由具体任务记录继承），测试只关心 id/终态，造一个最小子类。 */
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

    @Test
    void keepsEarlierSuccessWhenLaterTaskSettles() {
        RecentFinishedTasks recent = new RecentFinishedTasks();
        TaskRecord t1 = rec(TaskState.SUCCESS);
        TaskRecord t2 = rec(TaskState.FAILED);
        recent.remember(t1);
        recent.remember(t2);

        assertSame(t1, recent.find(t1.publicId()),
                "t1 成功后又被 t2 覆盖，按 t1 查仍必须拿到 t1");
        assertSame(t2, recent.find(t2.publicId()), "t2 自己的终态也要能查到");
        assertEquals(2, recent.size());
    }

    @Test
    void evictsOldestWhenOverLimit() {
        RecentFinishedTasks recent = new RecentFinishedTasks();
        TaskRecord first = null;
        TaskRecord last = null;
        for (int i = 0; i < RecentFinishedTasks.MAX_RECORDS + 1; i++) {
            TaskRecord r = rec(TaskState.SUCCESS);
            if (i == 0) first = r;
            last = r;
            recent.remember(r);
        }

        assertEquals(RecentFinishedTasks.MAX_RECORDS, recent.size(), "表必须有界");
        assertNull(recent.find(first.publicId()), "最老的记录应被淘汰");
        assertNotNull(recent.find(last.publicId()), "最新记录必须还在");
    }

    @Test
    void blankIdIsNeverRememberedOrFound() {
        RecentFinishedTasks recent = new RecentFinishedTasks();
        assertNull(recent.find(null));
        assertNull(recent.find(""));
        assertNull(recent.find("   "), "空任务号不许制造假命中");
    }
}
