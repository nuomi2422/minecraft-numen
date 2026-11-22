package com.dwinovo.numen.core.task.base;

import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.task.TaskState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AbstractCompanionTaskResultTest {
    private static final class Record extends TaskRecord {
        Record() { super("test", "call", 100); }
    }

    private static class StatefulTask extends AbstractCompanionTask<Record> {
        final List<String> events = new ArrayList<>();
        StatefulTask() { super(null, new Record()); }
        @Override protected void onStart() { events.add("start"); }
        @Override protected TaskState onTick() { events.add("tick"); return TaskState.RUNNING; }
        @Override protected void cleanup() { events.add("cleanup"); super.cleanup(); }
        @Override protected String successMessage() { return "success"; }
        @Override protected Map<String, Object> resultData(TaskState state) {
            events.add("data:" + state);
            return Map.of("state", state.name());
        }
    }

    @Test
    void lifecyclePassesEachTerminalStateAndPreservesResultEnvelope() {
        for (TaskState state : List.of(TaskState.SUCCESS, TaskState.FAILED,
                TaskState.TIMEOUT, TaskState.CANCELLED)) {
            StatefulTask task = new StatefulTask();
            task.start(null);
            assertEquals(TaskState.RUNNING, task.tick(null));
            TaskResult result = task.result(state);
            assertEquals(Map.of("state", state.name()), result.data());
            assertEquals(state == TaskState.SUCCESS, result.success());
            assertEquals(state == TaskState.TIMEOUT, result.timedOut());
            assertEquals(state == TaskState.CANCELLED, result.interrupted());
            assertEquals(switch (state) {
                case SUCCESS -> "success";
                case TIMEOUT -> "timed out";
                case CANCELLED -> "interrupted";
                default -> "done";
            }, result.message());
            assertEquals(List.of("start", "tick", "cleanup", "data:" + state), task.events);
        }
    }

    @Test
    void repeatedResultsReinvokeCleanupAndReceiveTheirOwnState() {
        StatefulTask task = new StatefulTask();
        task.start(null);
        assertEquals(TaskState.RUNNING, task.tick(null));
        assertEquals("SUCCESS", task.result(TaskState.SUCCESS).data().get("state"));
        assertEquals("FAILED", task.result(TaskState.FAILED).data().get("state"));
        assertEquals("FAILED", task.result(TaskState.FAILED).data().get("state"));
        assertEquals(List.of("start", "tick", "cleanup", "data:SUCCESS",
                "cleanup", "data:FAILED", "cleanup", "data:FAILED"), task.events);
    }

    private static final class LegacyTask extends AbstractCompanionTask<Record> {
        int calls;
        LegacyTask() { super(null, new Record()); }
        @Override protected TaskState onTick() { return TaskState.RUNNING; }
        @Override protected String successMessage() { return "legacy"; }
        @Override protected Map<String, Object> resultData() { return Map.of("calls", ++calls); }
    }

    @Test
    void legacyNoArgumentOverrideStillWorksForEveryTerminalState() {
        LegacyTask task = new LegacyTask();
        task.start(null);
        assertEquals(TaskState.RUNNING, task.tick(null));
        int calls = 0;
        for (TaskState state : List.of(TaskState.SUCCESS, TaskState.FAILED,
                TaskState.TIMEOUT, TaskState.CANCELLED)) {
            assertEquals(Map.of("calls", ++calls), task.result(state).data());
        }
    }
}
