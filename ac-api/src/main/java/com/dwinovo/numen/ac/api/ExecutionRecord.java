package com.dwinovo.numen.ac.api;

import java.util.Map;

/**
 * 一次执行尝试的终态快照。{@code runId} 是本次尝试的唯一主键；
 * {@code resume} 仅在 {@code PAUSED} 时非空，携带执行身份与断点，
 * 供 {@code resume} 从真实暂停步骤继续。
 */
public record ExecutionRecord(
        String runId,
        String acName,
        Status status,
        int completedStepIndex,
        String currentStepId,
        String message,
        Map<String, Object> output,
        long startedAt,
        long finishedAt,
        ResumeContext resume) {

    public enum Status { SUCCESS, PAUSED, FAILED }

    public ExecutionRecord {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId required");
        if (acName == null || acName.isBlank()) throw new IllegalArgumentException("acName required");
        output = output == null ? Map.of() : Map.copyOf(output);
    }

    /** 兼容旧 9 字段构造（无 resume 上下文）。 */
    public ExecutionRecord(String runId, String acName, Status status, int completedStepIndex,
                           String currentStepId, String message, Map<String, Object> output,
                           long startedAt, long finishedAt) {
        this(runId, acName, status, completedStepIndex, currentStepId, message,
                output, startedAt, finishedAt, null);
    }
}
