package com.dwinovo.numen.ac.core;

import com.dwinovo.numen.ac.api.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public final class AcExecutor {
    private final ToolRegistry registry;
    private final List<ExecutionRecord> records = new CopyOnWriteArrayList<>();
    private final List<ExecutionListener> listeners = new CopyOnWriteArrayList<>();
    public AcExecutor(ToolRegistry registry) { this.registry = Objects.requireNonNull(registry); }
    public void addListener(ExecutionListener listener) { listeners.add(Objects.requireNonNull(listener)); }
    public List<ExecutionRecord> records() { return List.copyOf(records); }
    public ExecutionRecord execute(AcDefinition ac, Map<String,Object> input, ExecutionContext context) { return executeFrom(ac, input, context, 0); }
    public ExecutionRecord resume(AcDefinition ac, ExecutionRecord paused, ExecutionContext context) { if (paused == null || paused.status() != ExecutionRecord.Status.PAUSED) throw new IllegalArgumentException("record is not paused"); return executeFrom(ac, Map.of(), context, paused.completedStepIndex()); }
    private ExecutionRecord executeFrom(AcDefinition ac, Map<String,Object> input, ExecutionContext context, int start) {
        long started = System.currentTimeMillis(); String runId = UUID.randomUUID().toString(); Map<String,Object> output = new LinkedHashMap<>();
        int completed = start; String current = null; StepResult result = StepResult.success(Map.of());
        for (int i = start; i < ac.steps().size(); i++) {
            AcDefinition.AcStep step = ac.steps().get(i); current = step.id();
            AcTool tool = registry.find(step.tool()).orElse(null);
            if (tool == null) { result = StepResult.failed("unknown tool: " + step.tool()); break; }
            result = tool.execute(step.parameters(), context);
            if (result.output() != null) output.putAll(result.output());
            if (result.status() == StepResult.Status.SUCCESS) { completed = i + 1; continue; }
            break;
        }
        ExecutionRecord.Status status = result.status() == StepResult.Status.SUCCESS ? ExecutionRecord.Status.SUCCESS : result.status() == StepResult.Status.PAUSED ? ExecutionRecord.Status.PAUSED : ExecutionRecord.Status.FAILED;
        ExecutionRecord record = new ExecutionRecord(runId, ac.name(), status, completed, current, result.message(), output, started, System.currentTimeMillis());
        records.add(record); listeners.forEach(l -> l.recorded(record)); return record;
    }
}
