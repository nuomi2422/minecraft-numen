package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.api.observation.WorldObservationService;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-07：entities 超大盒在参数层拒绝并给明确上限；合法盒不受影响。 */
class EntityBoxBudgetTest {
    private static WorldObservationService.Request entities(String box) {
        return new WorldObservationService.Request("test", "minecraft:overworld", "entities",
                Map.of("box", box, "category", "hostile"), 0L, 64, 0L);
    }

    @Test void oversizedBoxRejectedWithHint() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> ObservationCapability.toCore(entities("-5000,-5000,-5000,5000,5000,5000")));
        assertTrue(e.getMessage().contains("274625"), "要给出上限: " + e.getMessage());
        assertTrue(e.getMessage().contains("65"), "要给出建议盒: " + e.getMessage());
    }

    @Test void legalBoxUnaffected() {
        var core = ObservationCapability.toCore(entities("-32,0,-32,32,64,32"));
        assertNotNull(core);
    }

    @Test void exactlyMaxVolumeAllowed() {
        var core = ObservationCapability.toCore(entities("0,0,0,64,64,64"));
        assertNotNull(core);
    }
}
