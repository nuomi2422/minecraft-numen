package com.dwinovo.numen.ac.core;

import com.dwinovo.numen.ac.api.*;
import org.junit.jupiter.api.Test;
import java.io.StringReader;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AcExecutorTest {
    private static final ExecutionContext CTX = Map::of;
    @Test void executesAndRecords() {
        DefaultToolRegistry r = new DefaultToolRegistry();
        r.register("step", (p, c) -> StepResult.success(Map.of("value", p.get("value"))));
        var ac = new AcDefinition("demo", List.of(new AcDefinition.AcStep("one", "step", Map.of("value", 1))));
        var record = new AcExecutor(r).execute(ac, Map.of(), CTX);
        assertEquals(ExecutionRecord.Status.SUCCESS, record.status()); assertEquals(1, record.completedStepIndex()); assertEquals(1.0, record.output().get("value"));
    }
    @Test void pausedExecutionResumesFromNextStep() {
        DefaultToolRegistry r = new DefaultToolRegistry();
        r.register("ok", (p,c) -> StepResult.success(Map.of()));
        var shouldPause = new boolean[] { true };
        r.register("pause", (p,c) -> shouldPause[0] ? StepResult.paused("needs approval", Map.of("paused", true)) : StepResult.success(Map.of("resumed", true)));
        var ac = new AcDefinition("pause-demo", List.of(new AcDefinition.AcStep("a", "ok", Map.of()), new AcDefinition.AcStep("b", "pause", Map.of()), new AcDefinition.AcStep("c", "ok", Map.of())));
        var executor = new AcExecutor(r); var paused = executor.execute(ac, Map.of(), CTX);
        assertEquals(ExecutionRecord.Status.PAUSED, paused.status()); assertEquals(1, paused.completedStepIndex());
        shouldPause[0] = false;
        var resumed = executor.resume(ac, paused, CTX);
        assertEquals(ExecutionRecord.Status.SUCCESS, resumed.status()); assertEquals(3, resumed.completedStepIndex());
    }
    @Test void loadsAndValidatesJson() {
        var ac = AcJson.load(new StringReader("{\"name\":\"json\",\"steps\":[{\"id\":\"s\",\"tool\":\"ok\",\"parameters\":{\"n\":2}}]}"));
        assertEquals("json", ac.name()); assertEquals("ok", ac.steps().get(0).tool());
        assertThrows(IllegalArgumentException.class, () -> AcJson.load(new StringReader("{\"name\":\"bad\"}")));
    }
}
