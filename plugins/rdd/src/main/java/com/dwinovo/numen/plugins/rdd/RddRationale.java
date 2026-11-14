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


    /** Same task-file payload for ordinary saves and frozen completion intents; no second store. */
    static String taskJson(UUID id, TaskChain owner, TaskChain payload) {
        String json = payload.toJson();
        long revision = owner.planRevision(); String chain = owner.chainInstanceId();
        RationaleContext.Entry why = RationaleContext.get(id);
        if (why == null || !chain.equals(payload.chainInstanceId()) || revision != payload.planRevision()
                || !RationaleContext.renderEntry(why).equals(RationaleContext.renderApplicable(id, revision, chain))
                || RationaleContext.get(id) != why) return json;
        JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        JsonObject reason = new JsonObject();
        reason.addProperty("companionId", id.toString()); reason.addProperty("chainInstanceId", chain);
        reason.addProperty("planRevision", revision);
        reason.addProperty("why", why.why() == null ? "" : why.why());
        reason.addProperty("risks", why.risks() == null ? "" : why.risks());
        reason.addProperty("source", why.source() == null ? "" : why.source());
        reason.addProperty("conditions", why.conditions() == null ? "" : why.conditions());
        // All four fields came from one immutable Entry. A changed scope/entry cannot authorise its payload.
        if (!RationaleContext.renderEntry(why).equals(RationaleContext.renderApplicable(id, revision, chain))
                || RationaleContext.get(id) != why) return json;
        root.add("rationale", reason);
        return root.toString();
    }

    /** Called only after the runtime is installed; bad optional metadata must not break chain restoration. */
    static boolean restore(UUID id, TaskChain accepted, String json, Supplier<TaskChain> current) {
        if (id == null || accepted == null || current.get() != accepted) return false;
        bound(id, accepted, null, current); // Existing accepted-owner declaration; never infer a reason.
        try {
            JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("rationale") || !root.get("rationale").isJsonObject()) return false;
            JsonObject reason = root.getAsJsonObject("rationale");
            for (String field : List.of("companionId", "chainInstanceId", "why", "risks", "source", "conditions")) {
                var value = reason.get(field);
                if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return false;
            }
            var revision = reason.get("planRevision"); var rootRevision = root.get("planRevision");
            var rootChain = root.get("chainInstanceId");
            if (revision == null || !revision.isJsonPrimitive() || !revision.getAsJsonPrimitive().isNumber()
                    || rootRevision == null || !rootRevision.isJsonPrimitive() || !rootRevision.getAsJsonPrimitive().isNumber()
                    || rootChain == null || !rootChain.isJsonPrimitive() || !rootChain.getAsJsonPrimitive().isString()
                    || !id.toString().equals(reason.get("companionId").getAsString())
                    || !accepted.chainInstanceId().equals(rootChain.getAsString())
                    || !accepted.chainInstanceId().equals(reason.get("chainInstanceId").getAsString())
                    || accepted.planRevision() != rootRevision.getAsBigDecimal().longValueExact()
                    || accepted.planRevision() != revision.getAsBigDecimal().longValueExact()) return false;
            var why = new RationaleContext.Entry(reason.get("why").getAsString(), reason.get("risks").getAsString(),
                    reason.get("source").getAsString(), reason.get("conditions").getAsString());
            bound(id, accepted, why, current);
            return RationaleContext.get(id) == why
                    && RationaleContext.applicable(id, accepted.planRevision(), accepted.chainInstanceId());
        } catch (RuntimeException malformed) {
            return false; // Optional reason UNKNOWN; the already decoded/installed chain remains valid.
        }
    }

    private static String str(JsonObject args, String key) {
        return args != null && args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString().trim() : "";
    }
}
