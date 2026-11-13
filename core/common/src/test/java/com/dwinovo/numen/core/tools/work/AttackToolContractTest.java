package com.dwinovo.numen.core.tools.work;

import org.junit.jupiter.api.Test;
import java.util.Locale;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-13: real public tool description/schema must describe the existing defensive default.
 * V02 zero-defeated DONE is not a sweep of every scanned hostile; explicit ids retain targeting.
 * Managed core:common exact test; no world, target authorization change or combat success is simulated.
 */
class AttackToolContractTest {
    @Test void descriptionDistinguishesSelfDefenseFromAnAllHostileSweep() {
        String text = new AttackTool().description().toLowerCase(Locale.ROOT);
        assertTrue(text.contains("self-defense"), text);
        assertTrue(text.contains("targeting you"), text);
        assertTrue(text.contains("last hurt you"), text);
        assertTrue(text.contains("not a sweep"), text);
        assertTrue(text.contains("zero defeated"), text);
        assertFalse(text.contains("every hostile near you"), text);
        assertTrue(text.contains("specific"), "Explicit runtime-id targeting remains documented");
    }

    @Test void entityIdsHelpUsesTheSameDefensiveDefault() {
        Map<?, ?> properties = (Map<?, ?>) new AttackTool().parameterSchema().get("properties");
        Map<?, ?> ids = (Map<?, ?>) properties.get("entity_ids");
        String text = ids.get("description").toString().toLowerCase(Locale.ROOT);
        assertTrue(text.contains("self-defense"), text);
        assertTrue(text.contains("not all nearby hostiles"), text);
        assertTrue(text.contains("runtime entity ids"), text);
        assertFalse(text.contains("every hostile near you"), text);
    }
}
