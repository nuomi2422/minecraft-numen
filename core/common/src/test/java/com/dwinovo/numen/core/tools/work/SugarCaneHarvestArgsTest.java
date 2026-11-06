package com.dwinovo.numen.core.tools.work;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-07：生产同源解析——越界拒绝（不 clamp）、类型严格、默认值、有限 deadline。 */
class SugarCaneHarvestArgsTest {
    private static SugarCaneHarvestArgs.Parsed parse(String json) {
        return SugarCaneHarvestArgs.parse(JsonParser.parseString(json).getAsJsonObject(), 1000L);
    }
    @Test void missingOrInvalidCountFails() {
        assertNull(parse("{}").record());
        assertNull(parse("{\"count\":0}").record());
        assertNull(parse("{\"count\":65}").record());
        assertNull(parse("{\"count\":-1}").record());
        assertNull(parse("{\"count\":\"3\"}").record());
        assertNull(parse("{\"count\":3.5}").record());
    }
    @Test void boundsAndDefaults() {
        var a = parse("{\"count\":3}");
        assertNotNull(a.record());
        assertEquals(3, a.record().count);
        assertEquals(8, a.record().maxTargets);
        assertEquals(12, a.record().radius);
        assertEquals(1000 + 6000, a.record().deadlineMs);
        var b = parse("{\"count\":64}");
        assertEquals(120_000, b.record().deadlineMs - 1000);
        assertEquals(1, parse("{\"count\":1}").record().count);
        assertEquals(64, parse("{\"count\":64}").record().count);
    }
    @Test void outOfRangeTargetsRadiusRejectedNotClamped() {
        assertNull(parse("{\"count\":3,\"max_targets\":0}").record());
        assertNull(parse("{\"count\":3,\"max_targets\":17}").record());
        assertNull(parse("{\"count\":3,\"radius\":0}").record());
        assertNull(parse("{\"count\":3,\"radius\":17}").record());
    }
}