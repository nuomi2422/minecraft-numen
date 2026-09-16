package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.InventoryGroups;
import java.util.Map;
import java.util.Set;

/** Compatibility for old food-variety plans, gated by an existing food reserve. */
final class RddOptionalFood {
    private static final Set<String> VARIETY = Set.of("minecraft:carrot", "minecraft:potato",
            "minecraft:beetroot", "minecraft:baked_potato");
    static boolean canSkip(Subtask task, Map<String, Integer> inventory) {
        if (task == null || inventory == null) return false;
        Object key = task.condition().get("asset_key");
        if (!(key instanceof String item) || !VARIETY.contains(item)) return false;
        if (Boolean.FALSE.equals(task.condition().get("optional"))) return false;
        // Exclude the target itself: failure to collect more is not evidence of an alternative.
        Map<String, Integer> alternatives = new java.util.HashMap<>(inventory);
        alternatives.remove(item);
        return InventoryGroups.count("food", alternatives) >= 16;
    }
}
