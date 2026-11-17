package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.PrimaryGoalStatus;
import com.dwinovo.numen.rdd.core.TaskChain;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Death-settlement → planner handoff, held per companion until a legal admission.
 *
 * <p>WHY this owner exists: settlement may run before the expanded primary is admitted
 * (death50), so the request must survive PENDING/RECOVERING instead of resuming the old plan.
 * It must then be claimed <b>exactly once</b> by the real owner chain, and it must be
 * <b>dropped</b> — never retried — as soon as chain/revision/death identity changed.
 *
 * <p>Boundaries: this class owns the pending map and that decision only. Building the request,
 * the expansion gates, requestReplan, the rejection side-effect and the monitor event stay in
 * {@code RddPlugin}. A non-current caller or a non-ACTIVE primary keeps the handoff; only an
 * identity mismatch drops it.
 */
final class RddDeferredDeathReplan {
    private RddDeferredDeathReplan() {}

    record Handoff(TaskChain owner, String primaryId, int planRevision, int deathSeq, String reason) {}

    private static final Map<UUID, Handoff> PENDING = new ConcurrentHashMap<>();

    /** Keep expanded work until initial or restored admission; never resume it from settlement. */
    static void defer(UUID companionId, TaskChain chain, int deathSeq, String reason) {
        PENDING.put(companionId, new Handoff(chain, chain.currentPrimary().id(),
                chain.planRevision(), deathSeq, reason));
    }

    /** Read the current handoff once; a later claim compares identity against this exact value. */
    static Handoff peek(UUID companionId) {
        return PENDING.get(companionId);
    }

    /**
     * Claim once, or drop a stale handoff.
     *
     * <p>The caller has already rejected a non-current chain; that read stays in the host where the
     * runtime lives, so this owner never re-reads it.
     *
     * @param pending         the value observed by {@link #peek(UUID)}
     * @param latestDeathSeq  latest ledger seq, or {@link Integer#MIN_VALUE} when there is no death record
     * @return the claimed handoff, or null when nothing is claimable now
     */
    static Handoff claim(UUID companionId, Handoff pending, TaskChain owner, int latestDeathSeq) {
        if (pending == null) return null;
        if (owner != pending.owner() || owner.currentPrimary() == null
                || !pending.primaryId().equals(owner.currentPrimary().id())
                || pending.planRevision() != owner.planRevision()
                || latestDeathSeq != pending.deathSeq()) {
            PENDING.remove(companionId, pending);
            return null;
        }
        if (owner.primaryStatus() != PrimaryGoalStatus.ACTIVE) return null;
        if (!PENDING.remove(companionId, pending)) return null;
        return pending;
    }

    static void discard(UUID companionId) {
        PENDING.remove(companionId);
    }

    static void clearAll() {
        PENDING.clear();
    }
}
