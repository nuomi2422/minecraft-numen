package com.dwinovo.numen.plugins.acx;

import com.dwinovo.numen.acx.api.AcxGameThreadGate;
import com.dwinovo.numen.acx.api.AcxStepOutcome;
import com.dwinovo.numen.acx.api.AcxTool;
import com.dwinovo.numen.acx.core.AcxSessionManager;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;

/** Recheck the captured session at the existing host gate's actual dispatch point. */
final class AcxBoundHostCall {
    private AcxBoundHostCall() { }

    static AcxStepOutcome call(AcxGameThreadGate gate, AcxSessionManager.Session session,
                              AcxTool.AcxCallContext ctx, BooleanSupplier originalBodyAvailable,
                              Callable<AcxStepOutcome> action) throws Exception {
        if (session == null || ctx == null || !session.runId().equals(ctx.runId())) {
            throw new IllegalArgumentException("调用不属于原会话");
        }
        long attemptId = session.attemptId();
        return gate.call(() -> {
            if (!session.isRunning() || session.attemptId() != attemptId) {
                throw new IllegalStateException("旧执行轮次已结束，拒绝迟到宿主派发");
            }
            if (session.isBodyInvalidated()) {
                throw new IllegalStateException("原身体已失效，未派发工具");
            }
            if (session.isCancelRequested()) {
                return AcxStepOutcome.paused("运行已取消，未派发工具", java.util.Map.of());
            }
            if (ctx.isCancelRequested()) {
                throw new IllegalStateException("执行上下文已失效，未派发工具");
            }
            if (!originalBodyAvailable.getAsBoolean()) {
                throw new IllegalStateException("原身体已不可用，拒绝把旧运行交给新身体");
            }
            return action.call();
        });
    }
}
