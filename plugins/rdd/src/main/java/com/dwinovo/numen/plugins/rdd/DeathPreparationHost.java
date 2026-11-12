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

    /** Shared with the actual plugin commit boundary; current SELF cannot authenticate an old event. */
    static boolean settlementIdentity(UUID id, int dropSeq, SideTask task, RddRuntime runtime) {
        return task != null && runtime != null && id.equals(task.companionId())
                && DeathPreparation.sameChain(task, runtime.chain())
                && String.valueOf(dropSeq).equals(task.data().get("death_seq"))
                && currentBody(id, task, RddDeathLedger.latest(id));
    }

    static String settlementKey(UUID id, int dropSeq, boolean preparationHandled, SideTask task) {
        return id + "|" + dropSeq + (preparationHandled && task != null
                ? "|prepare:" + task.data().getOrDefault(PREPARATION_BATCH, task.data().getOrDefault("death_seq", ""))
                    + "|" + task.instanceId() + "|" + task.generation()
                    + "|" + task.data().getOrDefault("prepare_retries", "0") : "");
    }

    /** 2026-11-13: preserve the actual paused host order so successor liveness can be tested offline. */
    static boolean holdRecoveryPause(UUID id, SideTaskEngine engine, com.dwinovo.numen.rdd.core.TaskChain chain,
                                     SideTaskWorldPort port, Runnable retryActivated) {
        if (!DeathPreparation.holdPaused(chain, id, port)) return false;
        if (DeathPreparation.reopenPaused(engine, id, chain, port)) retryActivated.run();
        return true;
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
        if (active == null || latestBatch == null || !id.equals(active.companionId())
                || !DeathPreparation.sameChain(active, rt.chain())) return;
        int bodySeq = bodySeq(active), dropSeq = sequence(active.data().get("death_seq"));
        if (bodySeq < dropSeq || bodySeq > latestBatch.seq()) return;
        boolean successor = !String.valueOf(latestBatch.seq()).equals(active.data().get("death_seq"));
        if (successor && !emptySuccessors(id, active, latestBatch)) return;
        var semantics = host.semantics(id);
        Map<String, String> slots = new java.util.LinkedHashMap<>();
        for (var primary : rt.chain().goal().primaryGoals()) {
            for (var requirement : primary.waitFor()) host.declareWearSlot(requirement.assetKey(), slots);
            for (var sub : primary.subtasks()) if (sub.condition().get("asset_key") instanceof String key)
                host.declareWearSlot(key, slots);
        }
        for (var key : semantics.keySet()) host.declareWearSlot(key, slots);
        DeathPreparation.attach(engine, id, rt.chain(), semantics, slots);
        if (successor) {
            var current = engine.current(id).orElseThrow();
            String batch = String.valueOf(latestBatch.seq());
            if (!batch.equals(current.data().get(PREPARATION_BATCH))) {
                var data = new java.util.LinkedHashMap<>(current.data());
                data.put(PREPARATION_BATCH, batch);
                // Project a candidate identity for independent SELF evidence; UNKNOWN must not mutate live state.
                var evidence = port.deathPreparationFacts(id, current.withData(data));
                if (evidence.state() == SideTaskWorldPort.PreparationState.UNKNOWN) return;
                for (String key : java.util.List.of("prepare_request", DeathPreparation.PLAN,
                        "prepare_descriptions", "prepare_index", "prepare_plan_state")) data.remove(key);
                if (evidence.state() == SideTaskWorldPort.PreparationState.MISSING) data.remove("prepare_body_stopped");
                engine.runtime().replace(current.withData(data));
            }
        }
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
                                && recovery != null && currentBody(id, recovery, latest)) {
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
        if (!currentBody(companionId, task, latest))
            return SideTaskWorldPort.PreparationFacts.unknown("stale_death_batch");
        // An explicit body marker survives terminal/pause serialization. No latest-body reinterpretation of old payloads.
        // The supplier is independent current SELF; the old death_seq is still only a drop source.
        return read.get();
    }

    private static int sequence(String raw) {
        try { return Integer.parseInt(raw); }
        catch (NumberFormatException invalid) { return -1; }
    }

    private static int bodySeq(SideTask task) {
        return sequence(task.data().getOrDefault(PREPARATION_BATCH, task.data().get("death_seq")));
    }

    private static boolean currentBody(UUID id, SideTask task, RddDeathLedger.Death latest) {
        if (task == null || latest == null || !id.equals(task.companionId())
                || !DeathRecoverySideTaskTrigger.TYPE_ID.equals(task.typeId()) || bodySeq(task) != latest.seq()) return false;
        return String.valueOf(latest.seq()).equals(task.data().get("death_seq"))
                || emptySuccessors(id, task, latest);
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
        // The plugin can legitimately close the drop source before its final current-equipment check.
        return origin != null && !origin.lostItems().isEmpty()
                && previous != null && previous.equals(latest);
    }
}
