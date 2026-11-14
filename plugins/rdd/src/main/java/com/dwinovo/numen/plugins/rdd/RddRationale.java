package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.RationaleContext;
import com.google.gson.JsonObject;
import com.dwinovo.numen.rdd.api.PrimarySpec;
import com.dwinovo.numen.rdd.core.TaskChain;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Shared parser for planner-produced rationale; absent fields stay absent, never invented. */
final class RddRationale {
    private RddRationale() {}

    static boolean fresh(long rationaleGeneration, long chainStateVersion) {
        return rationaleGeneration == chainStateVersion;
    }

    static RationaleContext.Entry from(JsonObject args, String defaultSource) {
        String why = str(args, "reason");
        String risks = str(args, "risks");
        String source = str(args, "rationale_source");
        String conditions = str(args, "conditions");
        return new RationaleContext.Entry(why, risks, source.isBlank() ? defaultSource : source, conditions);
    }

    /** Real pending-to-Stage-A callback seam; host dispatch still validates its original ticket. */
    static void planSubmission(UUID id, Map<UUID, RationaleContext.Entry> pending,
                               BiConsumer<RationaleContext.Entry, Consumer<List<PrimarySpec>>> plan,
                               BiConsumer<List<PrimarySpec>, RationaleContext.Entry> accept) {
        RationaleContext.Entry why = pending.remove(id);
        plan.accept(why, stages -> accept.accept(stages, null));
    }

    /** Called immediately after the accepted chain is installed, before its first context is published. */
    static void bound(UUID id, TaskChain accepted, RationaleContext.Entry why, Supplier<TaskChain> current) {
        // Baseline: submission reasons only reached Stage-A, not the newly bound chain.
    }

    /** The host execution-context consumer declares its actual chain, then removes stale history. */
    static void refreshContext(UUID id, TaskChain chain) {
        RationaleContext.noteCurrentChain(id, chain.chainInstanceId());
        if (RationaleContext.get(id) != null
                && !RationaleContext.applicable(id, chain.planRevision(), chain.chainInstanceId())) {
            RationaleContext.clear(id);
        }
        // Baseline unexpanded context discarded even an otherwise applicable chain reason.
        if (chain.currentSubtask() == null) RationaleContext.clear(id);
    }

    /** Actual lazy-plan installation seam; a failed install must not claim a successful new reason. */
    static void expand(UUID id, TaskChain owner, Supplier<TaskChain> current, Runnable install) {
        install.run();
    }

    private static String str(JsonObject args, String key) {
        return args != null && args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString().trim() : "";
    }
}
