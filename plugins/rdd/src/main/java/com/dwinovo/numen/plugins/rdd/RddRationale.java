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

/** Rationale parsing and accepted-owner handoff; absent fields stay absent, never invented. */
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
        plan.accept(why, stages -> accept.accept(stages, why));
    }

    /** Called immediately after the accepted chain is installed, before its first context is published. */
    static void bound(UUID id, TaskChain accepted, RationaleContext.Entry why, Supplier<TaskChain> current) {
        if (current.get() != accepted) return;
        RationaleContext.noteCurrentChain(id, accepted.chainInstanceId());
        if (why != null) RationaleContext.set(id, why, accepted.planRevision(), accepted.chainInstanceId());
        else if (RationaleContext.get(id) != null
                && !RationaleContext.applicable(id, accepted.planRevision(), accepted.chainInstanceId())) {
            RationaleContext.clear(id);
            RationaleContext.noteCurrentChain(id, accepted.chainInstanceId());
        }
    }

    /** The host execution-context consumer declares its actual chain, then removes stale history. */
    static void refreshContext(UUID id, TaskChain chain) {
        RationaleContext.noteCurrentChain(id, chain.chainInstanceId());
        if (RationaleContext.get(id) != null
                && !RationaleContext.applicable(id, chain.planRevision(), chain.chainInstanceId())) {
            RationaleContext.clear(id);
        }
        // An unexpanded primary still belongs to this accepted objective; no body task is
        // not a reason to discard its otherwise current rationale.
    }

    /** Actual lazy-plan installation seam; a failed install must not claim a successful new reason. */
    static void expand(UUID id, TaskChain owner, Supplier<TaskChain> current, Runnable install) {
        long revision = owner.planRevision();
        RationaleContext.Entry why = RationaleContext.get(id);
        boolean carry = why != null && current.get() == owner
                && RationaleContext.applicable(id, revision, owner.chainInstanceId())
                && RationaleContext.get(id) == why;
        install.run(); // Throwing/rejected installs never advance the reason.
        if (carry && current.get() == owner && owner.planRevision() == revision + 1L
                && RationaleContext.applicable(id, revision, owner.chainInstanceId())
                && RationaleContext.get(id) == why) {
            RationaleContext.set(id, why, owner.planRevision(), owner.chainInstanceId());
        }
    }

    private static String str(JsonObject args, String key) {
        return args != null && args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString().trim() : "";
    }
}
