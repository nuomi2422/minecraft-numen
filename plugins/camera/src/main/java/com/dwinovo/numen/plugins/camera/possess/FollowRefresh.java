package com.dwinovo.numen.plugins.camera.possess;

import java.util.UUID;

/**
 * Pure safety decision for an already-due spectator position refresh (2026-11-12).
 * Called by Possession on the server queue before any camera/teleport mutation.
 * Why not distance: vanilla spectate can already co-locate the server body without
 * sending a LocalPlayer position packet. Eligibility is binding identity and live
 * spectator state, not separation. No Minecraft classes or alternate scheduler.
 */
final class FollowRefresh {
    record Binding(long version, UUID observer, UUID target) {}

    enum Action { NONE, POSITION_PACKET, CHANGE_DIMENSION }

    private FollowRefresh() {}

    static Action plan(boolean active, Binding scheduled, Binding current, UUID resolvedTarget,
                       boolean spectator, boolean targetAlive, boolean sameDimension) {
        if (!mayRefresh(active, scheduled, current, resolvedTarget, spectator, targetAlive)) return Action.NONE;
        return sameDimension ? Action.POSITION_PACKET : Action.CHANGE_DIMENSION;
    }

    static boolean mayRefresh(boolean active, Binding scheduled, Binding current,
                              UUID resolvedTarget, boolean spectator, boolean targetAlive) {
        return active && spectator && targetAlive && scheduled != null
                && scheduled.equals(current) && scheduled.observer() != null
                && scheduled.target() != null && !scheduled.observer().equals(scheduled.target())
                && scheduled.target().equals(resolvedTarget);
    }
}
