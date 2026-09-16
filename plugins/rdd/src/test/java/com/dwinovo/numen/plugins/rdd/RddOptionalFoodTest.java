package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.Subtask;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RddOptionalFoodTest {
    @Test void skipsOnlyVarietyWithVerifiedAlternativeReserve() {
        var carrot = Subtask.hardCoded("s", "get carrots", Map.of("asset_key", "minecraft:carrot"));
        assertTrue(RddOptionalFood.canSkip(carrot, Map.of("minecraft:bread", 18)));
        assertFalse(RddOptionalFood.canSkip(carrot, Map.of("minecraft:bread", 15)));
        assertFalse(RddOptionalFood.canSkip(carrot, Map.of("minecraft:carrot", 32)));
        assertFalse(RddOptionalFood.canSkip(carrot, Map.of("minecraft:wheat", 64)));
        assertFalse(RddOptionalFood.canSkip(Subtask.hardCoded("b", "bread", Map.of("asset_key", "minecraft:bread")), Map.of("minecraft:carrot", 32)));
        assertFalse(RddOptionalFood.canSkip(Subtask.hardCoded("b", "required carrot", Map.of("asset_key", "minecraft:carrot", "optional", false)), Map.of("minecraft:bread", 32)));
    }
}
