package com.dwinovo.numen.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskCompletionTextTest {

    @Test void appendsStopHintOnce() {
        assertTrue(TaskCompletionText.withStopHint("reached the cell").endsWith(TaskCompletionText.STOP_HINT));
        assertEquals(TaskCompletionText.STOP_HINT, TaskCompletionText.withStopHint(null));
        assertEquals(TaskCompletionText.STOP_HINT, TaskCompletionText.withStopHint(""));
        String once = TaskCompletionText.withStopHint("done");
        assertEquals(once, TaskCompletionText.withStopHint(once), "hint must be idempotent");
    }
}
