package com.dwinovo.numen.api;

import java.util.Objects;

/**
 * Immutable rendered runtime-state contribution (2026-11-06).
 * Use an explicit owner and id for budget identity, never a registration index.
 * Legacy callbacks have opaque process-local ids and are always mandatory.
 * Priority is a consumer hint (larger means more important); this API never truncates.
 * No world reads, caching or freshness policy are introduced here.
 * Why not just String: optional playbooks must not displace required safety state.
 */
public record StateFragment(String owner, String id, String text, boolean mandatory, int priority) {
    public StateFragment {
        requireName(owner, "owner");
        requireName(id, "id");
        Objects.requireNonNull(text, "text");
    }

    static void requireName(String value, String name) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException(name + " must match [A-Za-z0-9_.-]+");
        }
    }
}
