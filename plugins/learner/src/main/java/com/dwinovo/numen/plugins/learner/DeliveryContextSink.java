package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.WorldContextStamp;
import com.dwinovo.numen.plugins.learner.core.SkillDeliveryEvidence;
import com.dwinovo.numen.plugins.learner.core.UsageLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 2026-11-13 C1: 父 API 的 {@link NumenPlugins.DeliveredContext} → 中性 Context →
 * 现有 {@link UsageLedger} 的 PRESENTED/UNKNOWN 证据。
 *
 * <p>单独成类（同包、无 MC 依赖），让 {@code LearnerPlugin.setup} 与测试注册的是**同一段映射**；
 * 这样集成测试可以走真实的 {@code NumenPlugins.deliveryDispatched → context sink → UsageLedger}
 * 而不依赖 LearnerPlugin 的静态加载。只做字段映射和 IO 失败诊断，不做成功/采用判断。
 * <p>2026-11-13：record 返回 ioFailed 而不抛异常，不能只等外层派发器记录异常；
 * 在消费结果处报告 WARN，不另建重试队列或恢复已消费的请求 CAS。
 */
final class DeliveryContextSink {
    private static final Logger LOG = LoggerFactory.getLogger(DeliveryContextSink.class);

    private DeliveryContextSink() {}

    /** setup 与测试共用的注册体。 */
    static Consumer<NumenPlugins.DeliveredContext> forLedger(UsageLedger ledger) {
        return forLedger(ledger, LOG);
    }

    /** Same registered consumer; the logger port keeps IO diagnostics observable without MC bootstrap. */
    static Consumer<NumenPlugins.DeliveredContext> forLedger(UsageLedger ledger, Logger diagnostics) {
        return ctx -> accept(ctx, ledger, diagnostics);
    }

    static void accept(NumenPlugins.DeliveredContext ctx, UsageLedger ledger) {
        accept(ctx, ledger, LOG);
    }

    private static void accept(NumenPlugins.DeliveredContext ctx, UsageLedger ledger, Logger diagnostics) {
        if (ctx == null || ledger == null) return;
        WorldContextStamp.Token token = ctx.worldToken();
        String worldId = "";
        String serverSession = "";
        long epoch = -1L;
        if (token != null) {
            Map<String, String> identity = token.identity();
            if (identity != null) {
                worldId = identity.getOrDefault("world_id", "");
                serverSession = identity.getOrDefault("server_session", "");
            }
            epoch = token.epoch();
        }
        List<SkillDeliveryEvidence.Ref> refs = new ArrayList<>();
        if (ctx.refs() != null) {
            for (NumenPlugins.DeliveredRef r : ctx.refs()) {
                if (r != null) {
                    refs.add(new SkillDeliveryEvidence.Ref(r.entryRef(), r.version()));
                }
            }
        }
        SkillDeliveryEvidence.Result result = SkillDeliveryEvidence.record(new SkillDeliveryEvidence.Context(
                ctx.requestId(), worldId, serverSession, epoch,
                ctx.companion() == null ? "" : ctx.companion().toString(),
                ctx.role(), ctx.phase(), ctx.taskId(), ctx.taskToolCallId(), refs), ledger);
        if (result.ioFailed()) {
            diagnostics.warn("[learner] skill delivery evidence IO failed (ioFailed=true; request continues): "
                            + "requestId={} companionId={} ledger={} written={} skipped={}; no automatic retry",
                    ctx.requestId(), ctx.companion(), ledger.file(), result.written(), result.skipped());
        }
    }
}
