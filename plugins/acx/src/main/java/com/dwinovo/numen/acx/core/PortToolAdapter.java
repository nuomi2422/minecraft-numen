package com.dwinovo.numen.acx.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import com.dwinovo.numen.acx.api.AcxBodyAction;
import com.dwinovo.numen.acx.api.AcxCompletionProbe;
import com.dwinovo.numen.acx.api.AcxPortSchema;
import com.dwinovo.numen.acx.api.AcxStatus;
import com.dwinovo.numen.acx.api.AcxStepOutcome;
import com.dwinovo.numen.acx.api.AcxTool;
import com.dwinovo.numen.acx.api.AcxToolPort;
import com.dwinovo.numen.acx.api.AcxToolSchema;

/**
 * 把 {@link AcxToolPort} 适配成引擎积木。
 *
 * <p><b>受理账本</b>：ACCEPTED 不是 SUCCESS，而是 PAUSED + 挂账。
 * 挂账按 runId、定义指纹和结构调用位置隔离；显式 resume 保留同一身份。
 * 已派发但未确认回包时，同一槽先查原回包句柄；没有真实 taskId，不能查完成探针。
 * 已确认的成功或将被忽略的迟到失败保留在原挂账中，直到执行器提交恢复位置。
 * 旧直接调用未启用延后消费时仍按原契约即时销账。</p>
 *
 * <p><b>完成两条路</b>：拉（{@link AcxCompletionProbe} 每次 resume 查一次）/
 * 推（宿主收 task_finished 后调 {@link #markCompleted} / {@link #markFailed}）。</p>
 *
 * <p>引擎不注入 {@code ignore_failure}（它由 runner 消费），但 runner 会把
 * 整份 params 原样传进来，所以适配层先剥掉控制键再校验 / 下发。</p>
 */
public final class PortToolAdapter implements AcxTool {

    public static final String CONTROL_IGNORE_FAILURE = "ignore_failure";

    /** G4c-s109b B-M2：探针连续 UNKNOWN 超过这个时长才转 FAILED，避免永久挂起。 */
    public static final long COMPLETION_UNKNOWN_TIMEOUT_MS = 30_000L;

    private final AcxToolPort port;
    private final AcxCompletionProbe probe;
    private final AcxToolSchema toolSchema;
    private final LongSupplier clock;
    private final boolean awaitAccepted;
    private final Map<CallKey, Acceptance> pendingByStep = new ConcurrentHashMap<>();
    private final Map<String, AcxToolPort.Result> pushedByTask = new ConcurrentHashMap<>();
    /** 每个挂账键首次 UNKNOWN 的时刻；只用于「不能判断」的宽限计时。 */
    private final Map<CallKey, Long> unknownSince = new ConcurrentHashMap<>();

    private record CallKey(String runId, String fingerprint, String acName, String path) { }

    private record Acceptance(String taskId, boolean standing, String paramsSignature,
                              Map<String, Object> data, AcxStepOutcome terminal,
                              AcxToolPort.ReplyReceipt reply, AcxBodyAction.Ref bodyAction) {
        Acceptance withTerminal(AcxStepOutcome result) {
            return new Acceptance(taskId, standing, paramsSignature, data, result, null, bodyAction);
        }
    }

    private static CallKey callKey(AcxCallContext ctx) {
        if (ctx == null) return new CallKey("", "", "", "");
        String path = ctx.invocationPath();
        if (path.isEmpty()) path = ctx.stepId() + "|" + ctx.loopIter();
        return new CallKey(ctx.runId(), ctx.definitionFingerprint(), ctx.acName(), path);
    }

    @Override
    public void checkpointCommitted(AcxCallContext ctx) {
        consumeCompletedScope(ctx);
    }

    @Override
    public void recoveryPositionCommitted(AcxCallContext ctx) {
        consumeCompletedScope(ctx);
    }

    private void consumeCompletedScope(AcxCallContext ctx) {
        CallKey scope = callKey(ctx);
        pendingByStep.entrySet().removeIf(entry -> {
            CallKey key = entry.getKey();
            boolean consumed = key.runId().equals(scope.runId())
                    && key.fingerprint().equals(scope.fingerprint())
                    && key.acName().equals(scope.acName())
                    && key.path().startsWith(scope.path()) && entry.getValue().terminal() != null;
            if (consumed) unknownSince.remove(key);
            return consumed;
        });
    }

    @Override
    public void discardRunReceipts(String runId) {
        pendingByStep.entrySet().removeIf(entry -> {
            CallKey key = entry.getKey();
            if (!key.runId().equals(runId)) return false;
            unknownSince.remove(key);
            pushedByTask.remove(entry.getValue().taskId());
            return true;
        });
    }

    private PortToolAdapter(AcxToolPort port, AcxCompletionProbe probe, LongSupplier clock, boolean awaitAccepted) {
        this.port = port;
        this.probe = probe;
        this.toolSchema = toToolSchema(port.schema());
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.awaitAccepted = awaitAccepted;
    }

    public static Builder builder(AcxToolPort port) {
        return new Builder(port);
    }

    public AcxToolPort port() {
        return port;
    }

    @Override
    public String name() {
        return port.name();
    }

    @Override
    public AcxToolSchema schema() {
        return toolSchema;
    }

    private AcxStepOutcome cancelledBeforeDispatch() {
        return AcxStepOutcome.paused(name() + " 看到取消请求，未发起调用",
                Map.of("_accepted", false, "_pause_reason", "cancel_requested"));
    }

    @Override
    public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
        AcxStepOutcome outcome = executeOnce(params, ctx);
        if (!awaitAccepted || ctx == null) return outcome;
        // This is the original run worker, not a server-thread loop or another resume attempt.
        // Only read the original receipt. Guard, body-busy, standing and unknown are not waits.
        CallKey key = callKey(ctx);
        while (outcome != null && outcome.status() == AcxStatus.PAUSED
                && java.util.Set.of("async_accepted", "host_reply_pending", "body_active")
                        .contains(outcome.output().get("_pause_reason"))) {
            if (ctx.isCancelRequested()) return AcxStepOutcome.paused(
                    "原运行取消或预算已到，保留原受理回执且不重发", Map.of("_pause_reason", "await_cancelled", "_accepted", true));
            Acceptance original = pendingByStep.get(key);
            if (original == null) return outcome; // a retired owner cannot recreate a dispatch
            if (original.standing() || (original.reply() == null && original.taskId().isEmpty())) return outcome;
            if (original.reply() == null && original.bodyAction() == null && probe == null
                    && !pushedByTask.containsKey(original.taskId())) return outcome;
            try { Thread.sleep(50L); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return AcxStepOutcome.paused("原异步等待被中断，保留回执", Map.of("_pause_reason", "await_interrupted"));
            }
            if (ctx.isCancelRequested()) continue;
            if (pendingByStep.get(key) != original) return AcxStepOutcome.paused(
                    "原受理身份已变，不自动续接", Map.of("_pause_reason", "receipt_identity_changed"));
            outcome = resolvePending(key, original, ctx.defersReceiptConsumption(), ctx);
        }
        return outcome;
    }

    private AcxStepOutcome executeOnce(Map<String, Object> params, AcxCallContext ctx) {
        Map<String, Object> clean = stripControlKeys(params);

        if (ctx != null && ctx.isCancelRequested()) {
            return cancelledBeforeDispatch();
        }

        CallKey key = callKey(ctx);
        boolean defer = ctx != null && ctx.defersReceiptConsumption();
        Acceptance acc = pendingByStep.get(key);
        // Resolve an original reply/body before validating arguments: this does not dispatch.
        // On resume the old $prev value may no longer be visible at the checkpoint.
        if (acc != null && (acc.reply() != null || acc.bodyAction() != null
                || (ctx != null && ctx.isResuming()))) {
            return resolvePending(key, acc, defer, ctx);
        }

        List<String> errors = port.schema().validate(clean);
        if (!errors.isEmpty()) {
            return AcxStepOutcome.failed(name() + " 参数校验失败: " + String.join("; ", errors));
        }

        String signature = String.valueOf(clean);
        if (acc != null && !acc.paramsSignature().equals(signature)) {
            // 同一 AC 同一步骤（同一迭代）换了参数 = 换目标：旧任务的账不能算在新目标头上。
            pendingByStep.remove(key);
            unknownSince.remove(key);
            acc = null;
        }
        if (acc != null) {
            return resolvePending(key, acc, defer, ctx);
        }

        AcxToolPort.Result r;
        try {
            r = port.invoke(clean, ctx);
        } catch (AcxToolPort.CancelledBeforeDispatchException e) {
            return cancelledBeforeDispatch();
        } catch (AcxToolPort.ReplyPendingAfterDispatchException e) {
            pendingByStep.put(key, new Acceptance("", false, signature, Map.of(), null, e.receipt(), null));
            return waitingForReply();
        } catch (Throwable t) {
            return AcxStepOutcome.failed(name() + " 执行异常: " + t);
        }
        if (r == null) {
            return AcxStepOutcome.failed(name() + " 返回 null（端口契约违规）");
        }
        return applyDispatchResult(key, signature, r, defer);
    }

    private AcxStepOutcome applyDispatchResult(CallKey key, String signature, AcxToolPort.Result r, boolean defer) {
        switch (r.outcome()) {
            case COMPLETED: {
                AcxStepOutcome done = AcxStepOutcome.success(r.data(), r.message());
                if (defer) pendingByStep.put(key,
                        new Acceptance("", false, signature, r.data(), done, null, null));
                return done;
            }
            case BODY_BUSY:
                return AcxStepOutcome.paused(r.message(), Map.of("_pause_reason", "body_busy",
                        "_accepted", false, "blocking_task_id", String.valueOf(r.data().getOrDefault("blocking_task_id", ""))));
            case FAILED:
                return AcxStepOutcome.failed(name() + " 失败: " + r.message());
            case ACCEPTED: {
                Acceptance fresh = new Acceptance(r.taskId(), r.standing(), signature, r.data(), null, null, r.bodyAction());
                pendingByStep.put(key, fresh);
                unknownSince.remove(key);
                return AcxStepOutcome.paused(acceptMessage(r), acceptedOutput(fresh, r.standing()));
            }
            default:
                throw new IllegalStateException("未覆盖的结果: " + r.outcome());
        }
    }

    private AcxStepOutcome waitingForReply() {
        return AcxStepOutcome.paused(name() + " 已离开派发等待，原工具回包尚未确认",
                Map.of("_pause_reason", "host_reply_pending", "_completed", false));
    }

    private AcxStepOutcome resolvePending(CallKey key, Acceptance acc, boolean defer, AcxCallContext ctx) {
        if (acc.reply() != null) {
            AcxToolPort.Result reply;
            try {
                reply = acc.reply().poll().orElse(null);
            } catch (RuntimeException e) {
                return waitingForReply(); // A lookup error cannot establish that the tool failed.
            }
            if (reply == null) return waitingForReply();
            boolean retainFailure = reply.isFailed() && defer && ctx.defersIgnoredFailureReceiptConsumption();
            if (reply.outcome() == AcxToolPort.Outcome.BODY_BUSY
                    || (reply.isFailed() && !retainFailure) || (reply.isCompleted() && !defer)) {
                pendingByStep.remove(key, acc);
            }
            AcxStepOutcome outcome = applyDispatchResult(key, acc.paramsSignature(), reply, defer);
            if (retainFailure) pendingByStep.replace(key, acc, acc.withTerminal(outcome));
            return outcome;
        }
        if (acc.terminal() != null) {
            if (!defer && acc.terminal().status() != AcxStatus.PAUSED) {
                pendingByStep.remove(key, acc);
            }
            return acc.terminal();
        }
        if (acc.bodyAction() != null) return resolveBodyAction(key, acc, defer, ctx);
        if (!acc.taskId().isEmpty()) {
            AcxToolPort.Result pushed = pushedByTask.remove(acc.taskId());
            if (pushed != null) {
                return settled(key, acc, pushed, defer, ctx);
            }
        }
        if (acc.standing()) {
            return AcxStepOutcome.paused(name() + " 是常驻任务（standing），没有完成终点；等待宿主 task_stop",
                    acceptedOutput(acc, true));
        }
        if (probe == null) {
            return AcxStepOutcome.paused(name() + " 异步任务仍在进行（无 completion probe，等待宿主完成通知）",
                    acceptedOutput(acc, false));
        }
        // AC-B12b 复审（2026-10-21）：没有 task_id 就**不许问探针** —— 探针只能按 id 精确回答，
        // 空 id 会让它跳过 id 校验，把「别的任务的 SUCCESS」当成本任务完成（假成功）。
        // 这里保持挂账、等宿主终态推送或 deadline，与 :126/:175 的空 id 守卫保持一致。
        if (acc.taskId().isEmpty()) {
            return AcxStepOutcome.paused(name() + " 受理回执没有 task_id，无法确认完成（保留挂账，等宿主终态或 deadline）",
                    acceptedOutput(acc, false));
        }
        AcxCompletionProbe.Snapshot s;
        try {
            s = probe.probe(acc.taskId(), ctx);
        } catch (Throwable t) {
            return AcxStepOutcome.paused(name() + " 完成探针异常（保留挂账，等待重试）",
                    acceptedOutput(acc, false));
        }
        if (s == null) {
            return AcxStepOutcome.paused(name() + " 完成探针返回 null（保留挂账）",
                    acceptedOutput(acc, false));
        }
        switch (s.state()) {
            case RUNNING:
                return AcxStepOutcome.paused(name() + " 异步任务进行中: " + s.message(),
                        acceptedOutput(acc, false));
            case DONE:
                return settled(key, acc, AcxToolPort.Result.completed(s.data(), s.message()), defer, ctx);
            case FAILED:
                return settled(key, acc, AcxToolPort.Result.failed(s.message()), defer, ctx);
            case UNKNOWN: {
                // B-M2：探针说「不知道」——保留挂账，给 30 秒宽限等宿主补终态；
                // 超过宽限才按失败删账（避免永久挂起，也避免刚查不到就重发物理动作）。
                long now = clock.getAsLong();
                long first = unknownSince.computeIfAbsent(key, k -> now);
                if (now - first >= COMPLETION_UNKNOWN_TIMEOUT_MS) {
                    // An unknown terminal is not an ignorable tool failure. Stay behind the
                    // original receipt until the host confirms what actually happened.
                    if (defer && ctx.defersIgnoredFailureReceiptConsumption()) {
                        return AcxStepOutcome.paused(name() + " 完成探针超过宽限仍无法判定（保留原回执）: " + s.message(),
                                unknownPauseOutput(acc));
                    }
                    pendingByStep.remove(key);
                    unknownSince.remove(key);
                    return AcxStepOutcome.failed(name() + " 完成探针连续 " + (now - first)
                            + "ms 无法判定（按失败处理，避免永久挂起）");
                }
                return AcxStepOutcome.paused(
                        name() + " 完成探针暂时无法判定（保留挂账，等待重试）: " + s.message(),
                        unknownPauseOutput(acc));
            }
            default:
                throw new IllegalStateException("未覆盖的探针状态: " + s.state());
        }
    }

    private AcxStepOutcome resolveBodyAction(CallKey key, Acceptance acc, boolean defer, AcxCallContext ctx) {
        AcxBodyAction.Snapshot found = null;
        if (probe != null && acc.taskId().equals(acc.bodyAction().taskId())) {
            try {
                found = probe.bodyAction(acc.bodyAction(), ctx);
            } catch (RuntimeException unavailable) {
                // Missing evidence stays unknown; the legacy timeout cannot prove release.
            }
        }
        if (found == null || !found.matches(acc.bodyAction())) found = AcxBodyAction.Snapshot.unknown();
        Map<String, Object> details = new LinkedHashMap<>(acceptedOutput(acc, false));
        details.put("body_action", found.output());
        return switch (found.state()) {
            case DONE -> {
                Map<String, Object> result = new LinkedHashMap<>(found.data());
                result.put("body_action", found.output());
                yield settled(key, acc, AcxToolPort.Result.completed(result, "原身体任务已结算"), defer, ctx);
            }
            case FAILED, TIMEOUT -> {
                details.put("_pause_reason", "body_terminal_reobserve");
                AcxStepOutcome reobserve = AcxStepOutcome.paused("原身体任务已结算，先重新观察再选动作", details);
                // Re-entering this checkpoint cannot re-dispatch the original body call.
                pendingByStep.replace(key, acc, acc.withTerminal(reobserve));
                yield reobserve;
            }
            case UNKNOWN, ACTIVE -> {
                details.put("_pause_reason", found.state() == AcxBodyAction.State.UNKNOWN
                        ? "body_terminal_unknown" : "body_active");
                yield AcxStepOutcome.paused("等待原身体任务终态；进展、路径按原证据回报", details);
            }
        };
    }

    private AcxStepOutcome settled(CallKey key, Acceptance acc, AcxToolPort.Result result,
                                   boolean defer, AcxCallContext ctx) {
        unknownSince.remove(key);
        AcxStepOutcome outcome = settle(acc, result);
        if (defer && (outcome.isSuccess() || ctx.defersIgnoredFailureReceiptConsumption())) {
            pendingByStep.replace(key, acc, acc.withTerminal(outcome));
        } else {
            pendingByStep.remove(key, acc);
        }
        return outcome;
    }

    private AcxStepOutcome settle(Acceptance acc, AcxToolPort.Result r) {
        if (r.isFailed()) {
            return AcxStepOutcome.failed(name() + " 失败: " + r.message());
        }
        Map<String, Object> out = new LinkedHashMap<>(acc.data());
        out.putAll(r.data());
        out.put("_accepted", true);
        out.put("_completed", true);
        if (!acc.taskId().isEmpty()) {
            out.put("task_id", acc.taskId());
        }
        return AcxStepOutcome.success(out, r.message());
    }

    private Map<String, Object> acceptedOutput(Acceptance acc, boolean standing) {
        Map<String, Object> out = new LinkedHashMap<>(acc.data());
        out.put("_accepted", true);
        out.put("_completed", false);
        if (acc.bodyAction() != null) out.put("body_action", AcxBodyAction.Snapshot.unknown().output());
        if (!acc.taskId().isEmpty()) {
            out.put("task_id", acc.taskId());
        }
        if (standing) {
            out.put("_standing", true);
            out.put("_pause_reason", "async_standing");
        } else {
            out.put("_pause_reason", "async_accepted");
        }
        return out;
    }

    /** UNKNOWN 的暂停输出：让外部能分清「还在跑」（async_accepted）与「探针不知道」（completion_unknown）。 */
    private Map<String, Object> unknownPauseOutput(Acceptance acc) {
        Map<String, Object> out = new LinkedHashMap<>(acceptedOutput(acc, false));
        out.put("_pause_reason", "completion_unknown");
        return out;
    }

    private String acceptMessage(AcxToolPort.Result r) {
        if (r.standing()) {
            return name() + " 已受理（常驻任务，无完成终点）: taskId=" + r.taskId();
        }
        return name() + " 已受理，后台执行中: taskId=" + r.taskId();
    }

    /** 宿主收 task_finished(done) 后回调：提供终态，断点提交后再销账。 */
    public void markCompleted(String taskId, Map<String, Object> data) {
        if (taskId != null && !taskId.isBlank()) {
            pushedByTask.put(taskId, AcxToolPort.Result.completed(data, "task_finished: done"));
        }
    }

    /** 宿主收 task_finished(failed/timeout/stopped) 后回调。 */
    public void markFailed(String taskId, String message) {
        if (taskId != null && !taskId.isBlank()) {
            pushedByTask.put(taskId, AcxToolPort.Result.failed(message));
        }
    }

    /** 在途受理的账本大小（测试 / 巡检用）。 */
    public int pendingCount() {
        return pendingByStep.size();
    }

    private static Map<String, Object> stripControlKeys(Map<String, Object> params) {
        Map<String, Object> clean = new LinkedHashMap<>();
        if (params == null) {
            return clean;
        }
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (!CONTROL_IGNORE_FAILURE.equals(e.getKey())) {
                clean.put(e.getKey(), e.getValue());
            }
        }
        return clean;
    }

    private static AcxToolSchema toToolSchema(AcxPortSchema s) {
        AcxToolSchema.Builder b = AcxToolSchema.builder();
        for (Map.Entry<String, AcxPortSchema.Param> e : s.params().entrySet()) {
            b.param(e.getKey(), mapType(e.getValue().type()));
        }
        // AcxToolSchema.outputFields() 返回 params.keySet()（现有契约），
        // 所以输出字段要并入 params 才能让 loader 的 $step.field 校验看得见
        for (String f : s.outputFields()) {
            if (!s.params().containsKey(f)) {
                b.param(f, AcxToolSchema.ParamType.ANY);
            }
        }
        for (String r : s.required()) {
            b.required(r);
        }
        return b.build();
    }

    private static AcxToolSchema.ParamType mapType(AcxPortSchema.Type t) {
        return switch (t) {
            case STRING -> AcxToolSchema.ParamType.STRING;
            case INTEGER -> AcxToolSchema.ParamType.INTEGER;
            case NUMBER -> AcxToolSchema.ParamType.NUMBER;
            case BOOLEAN -> AcxToolSchema.ParamType.BOOLEAN;
            default -> AcxToolSchema.ParamType.ANY;
        };
    }

    public static final class Builder {
        private final AcxToolPort port;
        private AcxCompletionProbe probe;
        private LongSupplier clock = System::currentTimeMillis;
        private boolean awaitAccepted;

        private Builder(AcxToolPort port) {
            if (port == null) {
                throw new IllegalArgumentException("port 不能为空");
            }
            this.port = port;
        }

        public Builder probe(AcxCompletionProbe v) {
            this.probe = v;
            return this;
        }

        /** 测试用：注入可控时钟（UNKNOWN 宽限计时）。 */
        public Builder clock(LongSupplier v) {
            this.clock = v == null ? System::currentTimeMillis : v;
            return this;
        }

        /** Hosted runs await their own confirmed receipts; direct legacy adapters stay manual. */
        public Builder awaitAccepted(boolean enabled) { this.awaitAccepted = enabled; return this; }

        public PortToolAdapter build() {
            return new PortToolAdapter(port, probe, clock, awaitAccepted);
        }
    }
}
