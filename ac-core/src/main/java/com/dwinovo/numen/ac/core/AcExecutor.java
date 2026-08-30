package com.dwinovo.numen.ac.core;

import com.dwinovo.numen.ac.api.AcDefinition;
import com.dwinovo.numen.ac.api.AcTool;
import com.dwinovo.numen.ac.api.ExecutionContext;
import com.dwinovo.numen.ac.api.ExecutionRecord;
import com.dwinovo.numen.ac.api.ResumeContext;
import com.dwinovo.numen.ac.api.StepResult;
import com.dwinovo.numen.ac.api.ToolRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AC 执行器 — 顺序执行步骤，PAUSED 后从真实断点 resume。
 *
 * <h3>身份契约</h3>
 * 一次执行绑定 AC 的 {@code name + version + fingerprint}（见 {@link AcFingerprint}）。
 * resume 只接受同一身份的 PAUSED 记录：版本或内容变化一律明确拒绝（需迁移/重规划），
 * 不静默从头重跑。resume 默认<b>重试暂停的那一步</b>（{@code completedStepIndex}
 * 指向它），已完成步骤不重复；原始 input 与已完成输出被保留并沿用。
 */
public final class AcExecutor {

    private final ToolRegistry registry;
    private final List<ExecutionRecord> records = new CopyOnWriteArrayList<>();

    public AcExecutor(ToolRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** 本次执行器已产生的结果快照（含每次 attempt 的记录）。 */
    public List<ExecutionRecord> records() {
        return List.copyOf(records);
    }

    /** 从头执行一个 AC。 */
    public ExecutionRecord execute(AcDefinition ac, Map<String, Object> input, ExecutionContext context) {
        Objects.requireNonNull(ac, "ac");
        Objects.requireNonNull(context, "context");
        String executionId = UUID.randomUUID().toString();
        return executeSession(executionId, ac, input == null ? Map.of() : input, context,
                0, Map.of(), 0, null);
    }

    /**
     * 从暂停记录继续执行。校验执行身份（AC name/version/fingerprint）与断点边界；
     * 保留原始 input 与已完成输出，从暂停的那一步继续（重试）。
     *
     * @throws IllegalArgumentException 记录非 PAUSED / 无 resume 上下文 / AC 身份变化 /
     *                                  断点越界时
     */
    public ExecutionRecord resume(AcDefinition ac, ExecutionRecord paused, ExecutionContext context) {
        Objects.requireNonNull(ac, "ac");
        Objects.requireNonNull(context, "context");
        if (paused == null || paused.status() != ExecutionRecord.Status.PAUSED) {
            throw new IllegalArgumentException("cannot resume a non-PAUSED record: "
                    + (paused == null ? "null" : paused.status()));
        }
        ResumeContext rc = paused.resume();
        if (rc == null) throw new IllegalArgumentException("paused record has no resume context");

        String fp = AcFingerprint.of(ac);
        if (!rc.acName().equals(ac.name())) {
            throw new IllegalArgumentException("resume AC name mismatch: " + rc.acName() + " vs " + ac.name());
        }
        if (!rc.acVersion().equals(ac.version())) {
            throw new IllegalArgumentException("resume AC version changed: " + rc.acVersion()
                    + " vs " + ac.version() + " → 需迁移/重规划，不静默续跑");
        }
        if (!rc.acFingerprint().equals(fp)) {
            throw new IllegalArgumentException("resume AC content changed (fingerprint mismatch) → 需迁移/重规划，不静默续跑");
        }
        int start = rc.resumeStepIndex();
        if (start < 0 || start >= ac.steps().size()) {
            throw new IllegalArgumentException("resume step index out of range: " + start
                    + " for " + ac.steps().size() + " steps");
        }
        return executeSession(rc.executionId(), ac, rc.input(), context,
                start, rc.state(), rc.attempt() + 1, paused.runId());
    }

    private ExecutionRecord executeSession(String executionId, AcDefinition ac, Map<String, Object> input,
                                           ExecutionContext context, int start,
                                           Map<String, Object> initialState, int attempt, String parentRunId) {
        long started = System.currentTimeMillis();
        String runId = UUID.randomUUID().toString();
        Map<String, Object> state = new LinkedHashMap<>(initialState);
        int completed = start;
        String current = null;
        String pausedReason = null;
        StepResult result = StepResult.success(Map.of());
        for (int i = start; i < ac.steps().size(); i++) {
            AcDefinition.AcStep step = ac.steps().get(i);
            current = step.id();
            AcTool tool = registry.find(step.tool()).orElse(null);
            if (tool == null) {
                result = StepResult.failed("unknown tool: " + step.tool());
                break;
            }
            result = tool.execute(step.parameters(), context);
            if (result.output() != null && !result.output().isEmpty()) {
                state.putAll(result.output());
            }
            if (result.status() == StepResult.Status.SUCCESS) {
                completed = i + 1;
                continue;
            }
            if (result.status() == StepResult.Status.PAUSED) {
                pausedReason = result.message();
            }
            break;
        }
        ExecutionRecord.Status status = switch (result.status()) {
            case SUCCESS -> ExecutionRecord.Status.SUCCESS;
            case PAUSED -> ExecutionRecord.Status.PAUSED;
            case FAILED -> ExecutionRecord.Status.FAILED;
        };
        ResumeContext rc = (status == ExecutionRecord.Status.PAUSED)
                ? new ResumeContext(executionId, attempt, ac.name(), ac.version(),
                        AcFingerprint.of(ac), Map.copyOf(input), Map.copyOf(state),
                        current, completed, pausedReason)
                : null;
        ExecutionRecord record = new ExecutionRecord(runId, ac.name(), status, completed, current,
                result.message(), Map.copyOf(state), started, System.currentTimeMillis(), rc);
        records.add(record);
        return record;
    }
}
