package com.dwinovo.numen.plugins.worldstate.focus;

import java.util.UUID;

/** world_state pin/unpin 的工具逻辑（纯类，供离线测试与工具共用；不依赖 MC）。 */
public final class FocusTools {
    private FocusTools() {}

    public static FocusPin pin(FocusPinStore pins, UUID companion, String world, String session,
                               String dimension, String topic, long nowMs, long ttlMs, int limit) {
        if (pins == null) return null;
        return pins.pin(companion, world, session, dimension, topic, nowMs, ttlMs, 0L, limit, 0);
    }

    public static boolean unpin(FocusPinStore pins, UUID companion, String pinId) {
        return pins != null && pins.unpin(companion, pinId);
    }
}
