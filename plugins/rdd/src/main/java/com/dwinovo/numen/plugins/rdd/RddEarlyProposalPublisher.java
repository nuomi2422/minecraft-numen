package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.core.TaskChain;

/** 早完成建议的发布与只读通知唯一所有者；Detector 只保留原入口委托。 */
final class RddEarlyProposalPublisher {
    private RddEarlyProposalPublisher() {}

    private static final java.util.Map<java.util.UUID, String> EARLY_PROPOSAL_SENT = new java.util.concurrent.ConcurrentHashMap<>();
    private record EarlyLatest(com.dwinovo.numen.rdd.core.RddEarlyCompletionProposal.Proposal p,
                               String world, String session) {}
    private static final java.util.Map<java.util.UUID, EarlyLatest> EARLY_PROPOSAL_LATEST = new java.util.concurrent.ConcurrentHashMap<>();

    /** Planner-visible read-only notice; mismatched/expired identity yields UNKNOWN and no old ids. */
    static String notice(java.util.UUID id, String currentWorld, String currentSession,
                         String currentChainId, long currentRevision, long currentStateVersion) {
        EarlyLatest latest = id == null ? null : EARLY_PROPOSAL_LATEST.get(id);
        if (latest == null) return "";
        long now = System.currentTimeMillis();
        if (currentWorld == null || currentSession == null
                || !java.util.Objects.equals(latest.world(), currentWorld)
                || !java.util.Objects.equals(latest.session(), currentSession)
                || !java.util.Objects.equals(latest.p().chainInstanceId(), currentChainId)
                || latest.p().planRevision() != currentRevision
                || latest.p().stateVersion() != currentStateVersion) {
            return com.dwinovo.numen.rdd.core.RddEarlyCompletionProposal.render(
                    latest.p(), latest.world(), latest.session(), null, null, now);
        }
        return com.dwinovo.numen.rdd.core.RddEarlyCompletionProposal.render(
                latest.p(), latest.world(), latest.session(), currentWorld, currentSession, now);
    }

    private static com.dwinovo.numen.api.ServerIdentityProvider.Snapshot identity(NumenPlayer ap) {
        try {
            if (ap == null || ap.level() == null || ap.level().getServer() == null) return null;
            var provider = com.dwinovo.numen.api.NumenPlugins.capability(
                    com.dwinovo.numen.api.ServerIdentityProvider.class);
            return provider == null ? null : provider.snapshot(ap.level().getServer());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Propose only; never settle/skip. Re-verifies each hit with the existing server-side fact predicate. */
    static void publish(NumenPlayer ap, TaskChain chain, java.util.Map<String, Integer> counts) {
        try {
            var proposal = com.dwinovo.numen.rdd.core.RddEarlyCompletionProposal.propose(chain, counts);
            if (proposal == null) return;
            var verified = proposal.subtaskIds().stream()
                    .filter(id -> chain.pendingSubtasks().stream().anyMatch(s -> s.id().equals(id)
                            && RddDetector.conditionMatches(ap, s, counts)))
                    .toList();
            if (verified.size() != proposal.subtaskIds().size() || verified.isEmpty()) return;
            var identity = identity(ap);
            if (identity == null) return;   // no request-server identity: UNKNOWN, do not publish
            String world = identity.worldId();
            String session = identity.serverSession();
            String key = ap.getUUID() + "|" + world + "|" + session + "|" + proposal.chainInstanceId()
                    + "|" + proposal.planRevision() + "|" + proposal.stateVersion() + "|" + verified;
            if (key.equals(EARLY_PROPOSAL_SENT.put(ap.getUUID(), key))) return;
            java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("companionId", ap.getUUID().toString());
            data.put("worldId", world);
            data.put("serverSession", session);
            data.put("chainInstanceId", proposal.chainInstanceId());
            data.put("planRevision", proposal.planRevision());
            data.put("stateVersion", proposal.stateVersion());
            data.put("subtasks", verified);
            data.put("evidence", proposal.evidence());
            data.put("sampledAtMillis", System.currentTimeMillis());
            data.put("source", "early_completion_proposal");
            RddMonitor.publish("early_completion_proposal", data);
            EARLY_PROPOSAL_LATEST.put(ap.getUUID(), new EarlyLatest(proposal, world, session));
        } catch (RuntimeException ignored) { }
    }
}