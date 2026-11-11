package com.dwinovo.numen.api;

import com.dwinovo.numen.entity.NumenPlayer;
import java.util.Map;

/** 2026-11-11 external owner-controlled current-world reset; never a model tool. */
@FunctionalInterface
public interface CompanionWorldReset {
    Map<String, Object> reset(NumenPlayer companion) throws Exception;
}
