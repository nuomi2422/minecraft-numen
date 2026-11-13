package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.WorldContextStamp;
import com.dwinovo.numen.plugins.learner.core.SkillDeliveryEvidence;
import com.dwinovo.numen.plugins.learner.core.UsageLedger;

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
 * 而不依赖 LearnerPlugin 的静态加载。只做字段映射，不做任何成功/采用判断。
 */
final class DeliveryContextSink {

    private DeliveryContextSink() {}

    /** setup 与测试共用的注册体。 */
    static Consumer<NumenPlugins.DeliveredContext> forLedger(UsageLedger ledger) {
        return ctx -> accept(ctx, ledger);
    }

    static void accept(NumenPlugins.DeliveredContext ctx, UsageLedger ledger) {
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
        SkillDeliveryEvidence.record(new SkillDeliveryEvidence.Context(
                ctx.requestId(), worldId, serverSession, epoch,
                ctx.companion() == null ? "" : ctx.companion().toString(),
                ctx.role(), ctx.phase(), ctx.taskId(), ctx.taskToolCallId(), refs), ledger);
    }
}
