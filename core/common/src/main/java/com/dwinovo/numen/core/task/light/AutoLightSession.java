package com.dwinovo.numen.core.task.light;

import net.minecraft.core.BlockPos;
import java.util.List;
import java.util.Map;

/**
 * Incremental decision state for ONE build job (2026-11-07). No placer, world cache or rescan loop.
 * Reference: TartaricAcid/TouhouLittleMaid MaidTorchMoveTask, MIT,
 * commit 207647c85740b1d0971de6040d6dd57cea62528c; see THIRD_PARTY_NOTICES.md.
 * Uses current raw brightness <9, not permanent night protection. Production delegates all decisions here.
 */
public final class AutoLightSession {
    public interface Port extends LightDeficitPlanner.World {
        Object worldIdentity();
        boolean permitted(BlockPos pos);
        boolean hasMaterial();
        /** Dispatch-time creative privilege must still be held; survival material checks remain separate. */
        boolean materialPrivilegeValid();
        /** 0 waiting, 1 arrived, -1 unreachable; must not modify terrain. */
        int approach(BlockPos pos);
        /** Existing build native useItemOn only; no manual rollback or inventory repair. */
        void place(BlockPos pos);
    }
    private final AutoLightRequest request;
    private final Object identity;
    private int cursor, ticks, placed, denied, unloaded, unreachable, attempts, cooldown;
    private BlockPos pending;
    private String reason = "running";
    public AutoLightSession(AutoLightRequest request, Object identity) { this.request=request; this.identity=identity; }
    public String reason() { return reason; }
    public boolean done() { return !reason.equals("running"); }
    public boolean complete() { return reason.equals("completed") || reason.equals("no_candidates"); }
    public void stop(String why) { if (!done()) reason=why; }
    public void tick(Port port) {
        if (done()) return;
        if (port.worldIdentity() != identity) { stop("world_changed"); return; }
        if (!port.materialPrivilegeValid()) { stop("material_privilege_lost"); return; }
        if (++ticks > request.maxTicks()) { stop("time_budget"); return; }
        if (cooldown > 0) { cooldown--; return; }
        if (placed >= request.maxLights()) { stop("placement_budget"); return; }
        if (pending == null) {
            for (int n=0; n<64 && cursor<request.volume() && cursor<request.maxScans(); n++) {
                BlockPos p=request.at(cursor++);
                if (!port.loaded(p)) { unloaded++; continue; }
                if (eligible(p,port)) { pending=p; break; }
            }
            if (pending == null) {
                if (cursor >= request.volume()) stop(unloaded+denied+unreachable > 0 ? "incomplete" : placed==0 ? "no_candidates" : "completed");
                else if (cursor >= request.maxScans()) stop("scan_budget");
                return;
            }
        }
        int approach=port.approach(pending);
        if (approach==0) return;
        if (approach<0) { unreachable++; pending=null; return; }
        // Navigation and light propagation may have changed every premise. Never trust the scan snapshot.
        BlockPos p=pending; pending=null;
        if (!port.loaded(p)) { unloaded++; return; }
        if (!eligible(p,port)) return;
        if (!port.permitted(p)) { denied++; return; }
        if (!port.hasMaterial()) { stop("materials_exhausted"); return; }
        attempts++;
        port.place(p);
        // InteractionResult SUCCESS/CONSUME alone is not evidence of a placed torch.
        if (port.loaded(p) && port.alreadyTorch(p)) placed++; else denied++;
        cooldown=4; // Let the server light engine propagate before reading the next candidate.
    }
    private boolean eligible(BlockPos p, Port port) {
        return !LightDeficitPlanner.plan(List.of(p),1,port).lights().isEmpty();
    }
    public Map<String,Object> data() {
        return Map.of("mode","auto_light","placed",placed,"scanned",cursor,"attempts",attempts,
                "denied",denied,"unloaded",unloaded,"unreachable",unreachable,"stop_reason",reason,
                "complete",complete(),"remaining_unscanned",request.volume()-cursor);
    }
}
