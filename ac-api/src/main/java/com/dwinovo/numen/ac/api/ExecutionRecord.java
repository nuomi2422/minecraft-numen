package com.dwinovo.numen.ac.api;

import java.util.Map;

public record ExecutionRecord(String runId, String acName, Status status, int completedStepIndex, String currentStepId, String message, Map<String,Object> output, long startedAt, long finishedAt) {
    public enum Status { SUCCESS, PAUSED, FAILED }
    public ExecutionRecord { output = output == null ? Map.of() : Map.copyOf(output); }
}
