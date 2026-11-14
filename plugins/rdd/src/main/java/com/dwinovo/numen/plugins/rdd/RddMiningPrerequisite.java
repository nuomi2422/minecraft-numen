package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.SubtaskSpec;
import java.util.Map;

/** 044 B1/B2 native evidence carrier (2026-11-14).
 * Red fixture correction: IDs cannot prove current datapack harvesting capability.
 * This seam carries actual shell evidence to the existing consumer; it does not
 * reject a plan or add static fallback behavior. The subsequent green adds the gate. */
final class RddMiningPrerequisite {
    /** Scoped native projection of actual stacks, not an item-id/default-stack guess. */
    record Evidence(Map<String, Integer> inventory, Map<String, Boolean> correctForDrops,
                    String source, long capturedAtMs, boolean complete) {
        Evidence {
            inventory = Map.copyOf(inventory);
            correctForDrops = Map.copyOf(correctForDrops);
        }
        boolean currentFor(Map<String, Integer> held, long nowMs) {
            return source != null && !source.isBlank() && capturedAtMs > 0 && capturedAtMs <= nowMs
                    && nowMs - capturedAtMs <= 3_000L && inventory.equals(held) && complete;
        }
    }

    static boolean needsCheck(SubtaskSpec spec) {
        return spec != null && "minecraft:obsidian".equals(spec.condition().get("asset_key"));
    }
}
