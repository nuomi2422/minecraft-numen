package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.RddDeathLedger;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.policy.AssetSemantics;
import com.dwinovo.numen.rdd.side.SideTask;
import com.dwinovo.numen.rdd.side.SideTaskEngine;
import com.dwinovo.numen.rdd.side.SideTaskWorldPort;
import com.dwinovo.numen.rdd.side.death.DeathPreparation;
import com.dwinovo.numen.rdd.side.death.DeathRecoverySideTaskTrigger;
import com.dwinovo.numen.rdd.side.death.DeathRecoveryDecision;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 2026-11-12: injectable execution of the existing death-preparation host path.
 * WHY: core-only tests skipped the host's latest-death admission and callback guard.
 * SideTaskHost calls this path; Access keeps MC registry/server-ticket/body operations
 * in the shell. 2026-11-13: separate the retained drop source from preparation's
 * current body batch after proven empty successors. A single latest/drop comparison
 * stranded PREPARE without a request; removing it would admit obsolete callbacks.
 * Reuse side data and bounded ledger proof; absent/nonempty lineage stays UNKNOWN.
 * Mainline tokens here certify chain/cursor/revision only, not world/server identity.
 */
final class DeathPreparationHost {
    interface Access {
        RddRuntime runtime(UUID id);
        Map<String, AssetSemantics> semantics(UUID id);
        void declareWearSlot(String key, Map<String, String> slots);
        DeathPreparation.Planner capturePlanner(UUID id);
        void stopBody();
        void persist();
    }

    private static final String PREPARATION_BATCH = "prepare_death_seq";

    private DeathPreparationHost() {}

    static void tick(UUID id, SideTaskEngine engine, SideTaskWorldPort port, Access host) {
        if (id == null || !DeathRecoverySideTaskTrigger.TYPE_ID.equals(
                engine.current(id).map(SideTask::typeId).orElse(null))) return;
        var rt = host.runtime(id);
        if (rt == null) return;
        var active = engine.current(id).orElse(null);
        var latestBatch = RddDeathLedger.latest(id);
        if (active == null || latestBatch == null) return;
        if (latestBatch != null && !String.valueOf(latestBatch.seq()).equals(active.data().get("death_seq"))) {
            if (!id.equals(active.companionId()) || !DeathPreparation.sameChain(active, rt.chain())
                    || !emptySuccessors(id, active, latestBatch)) return;
            String batch = String.valueOf(latestBatch.seq());
            if (!batch.equals(active.data().get(PREPARATION_BATCH))) {
                var data = new java.util.LinkedHashMap<>(active.data());
                data.put(PREPARATION_BATCH, batch);
                // A new body cannot execute or accept a plan requested before this death.
                for (String key : java.util.List.of("prepare_request", DeathPreparation.PLAN,
                        "prepare_descriptions", "prepare_index", "prepare_plan_state", "prepare_body_stopped"))
                    data.remove(key);
                engine.runtime().replace(active.withData(data));
            }
        }
        var semantics = host.semantics(id);
        Map<String, String> slots = new java.util.LinkedHashMap<>();
        for (var primary : rt.chain().goal().primaryGoals()) {
            for (var requirement : primary.waitFor()) host.declareWearSlot(requirement.assetKey(), slots);
            for (var sub : primary.subtasks()) if (sub.condition().get("asset_key") instanceof String key)
                host.declareWearSlot(key, slots);
        }
        for (var key : semantics.keySet()) host.declareWearSlot(key, slots);
        DeathPreparation.attach(engine, id, rt.chain(), semantics, slots);
        DeathPreparation.tick(engine, id, rt.chain(), port,
                (objective, hint, done) -> {
                    var planner = host.capturePlanner(id);
                    if (planner == null) { done.accept(java.util.List.of()); return; }
                    var chain = rt.chain();
                    var recovery = engine.current(id).orElse(null);
                    String deathSeq = recovery == null ? "" : recovery.data().getOrDefault(
                            PREPARATION_BATCH, recovery.data().getOrDefault("death_seq", ""));
                    String preparationHint = recovery != null && recovery.data().containsKey(PREPARATION_BATCH)
                            ? hint + "\n当前整备身体批次=" + deathSeq + "；掉落来源批次="
                                + recovery.data().get("death_seq") + "。掉落观察仅属于来源批次。" : hint;
                    planner.plan(objective, preparationHint, specs -> {
                        var live = host.runtime(id);
                        var latest = RddDeathLedger.latest(id);
                        if (live != null && live.chain() == chain
                                && (latest == null || String.valueOf(latest.seq()).equals(deathSeq))
                                && (recovery == null || !recovery.data().containsKey(PREPARATION_BATCH)
                                    || latest != null && emptySuccessors(id, recovery, latest))) {
                            done.accept(specs);
                            host.persist();
                        }
                    });
                });
        var preparing = engine.current(id).orElse(null);
        if (preparing != null && "true".equals(preparing.data().get("prepare_required"))
                && !"true".equals(preparing.data().get("prepare_body_stopped"))) {
            host.stopBody();
            engine.setData(id, "prepare_body_stopped", "true");
        }
    }

    static SideTaskWorldPort.PreparationFacts facts(UUID subject, UUID companionId, SideTask task,
                                                   Supplier<SideTaskWorldPort.PreparationFacts> read) {
        if (!subject.equals(companionId) || task == null || !companionId.equals(task.companionId()))
            return SideTaskWorldPort.PreparationFacts.unknown("subject_mismatch");
        var latest = RddDeathLedger.latest(companionId);
        if (latest == null || !String.valueOf(latest.seq()).equals(task.data().get("death_seq"))
                && !emptySuccessors(companionId, task, latest))
            return SideTaskWorldPort.PreparationFacts.unknown("stale_death_batch");
        // Re-prove lineage even for terminal/pause payloads, whose bounded core schema omits the marker.
        // The supplier is independent current SELF; the old death_seq is still only a drop source.
        return read.get();
    }

    /** No inference from a marker or an empty item map alone: every intervening batch must exist. */
    private static boolean emptySuccessors(UUID id, SideTask task, RddDeathLedger.Death latest) {
        if (!id.equals(task.companionId()) || !DeathRecoverySideTaskTrigger.TYPE_ID.equals(task.typeId())
                || !DeathPreparation.prepares(DeathRecoveryDecision.parse(task.data().get("decision")))) return false;
        int source;
        try { source = Integer.parseInt(task.data().getOrDefault("death_seq", "")); }
        catch (NumberFormatException invalid) { return false; }
        var deaths = RddDeathLedger.all(id);
        // The ledger is bounded: refuse an evicted source/gap rather than assuming it was empty.
        long distance = (long) latest.seq() - source;
        if (source <= 0 || distance <= 0 || distance >= deaths.size()) return false;
        RddDeathLedger.Death origin = null, previous = null;
        for (long seq = source; seq <= latest.seq(); seq++) {
            RddDeathLedger.Death found = null;
            for (var death : deaths) if (death.seq() == seq) { found = death; break; }
            if (found == null) return false;
            if (seq == source) origin = found;
            else if (found.lostEntries() != 0 || !found.lostItems().isEmpty()
                    || previous == null || found.gameTime() < previous.gameTime()) return false;
            previous = found;
        }
        return origin != null && !origin.lostItems().isEmpty()
                && origin.state() == RddDeathLedger.State.UNVERIFIED
                && previous != null && previous.equals(latest);
    }
}
