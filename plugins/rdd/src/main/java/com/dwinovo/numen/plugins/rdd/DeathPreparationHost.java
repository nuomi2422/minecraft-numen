package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.RddDeathLedger;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.policy.AssetSemantics;
import com.dwinovo.numen.rdd.side.SideTask;
import com.dwinovo.numen.rdd.side.SideTaskEngine;
import com.dwinovo.numen.rdd.side.SideTaskWorldPort;
import com.dwinovo.numen.rdd.side.death.DeathPreparation;
import com.dwinovo.numen.rdd.side.death.DeathRecoverySideTaskTrigger;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 2026-11-12: injectable execution of the existing death-preparation host path.
 * WHY: core-only tests skipped the host's latest-death admission and callback guard.
 * SideTaskHost calls this path; Access keeps MC registry/server-ticket/body operations
 * in the shell. No alternate recovery policy, identity store or eligibility rule:
 * original comparisons and call order are retained, including stale-batch UNKNOWN.
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

    private DeathPreparationHost() {}

    static void tick(UUID id, SideTaskEngine engine, SideTaskWorldPort port, Access host) {
        if (id == null || !DeathRecoverySideTaskTrigger.TYPE_ID.equals(
                engine.current(id).map(SideTask::typeId).orElse(null))) return;
        var rt = host.runtime(id);
        if (rt == null) return;
        var active = engine.current(id).orElse(null);
        var latestBatch = RddDeathLedger.latest(id);
        if (active == null || latestBatch != null
                && !String.valueOf(latestBatch.seq()).equals(active.data().get("death_seq"))) return;
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
                    String deathSeq = recovery == null ? "" : recovery.data().getOrDefault("death_seq", "");
                    planner.plan(objective, hint, specs -> {
                        var live = host.runtime(id);
                        var latest = RddDeathLedger.latest(id);
                        if (live != null && live.chain() == chain
                                && (latest == null || String.valueOf(latest.seq()).equals(deathSeq))) {
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
        if (!subject.equals(companionId)) return SideTaskWorldPort.PreparationFacts.unknown("subject_mismatch");
        var latest = RddDeathLedger.latest(companionId);
        if (latest != null && !String.valueOf(latest.seq()).equals(task.data().get("death_seq")))
            return SideTaskWorldPort.PreparationFacts.unknown("stale_death_batch");
        return read.get();
    }
}
