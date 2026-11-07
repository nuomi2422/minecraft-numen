package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.NodeType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RddDecomposerNodeTypeTest {
    @Test void parsesMainDailySideAndDefaultsToMain() {
        String json = "{\"subtasks\":["
                + "{\"description\":\"开田\",\"condition\":{\"asset_key\":\"minecraft:wheat\",\"minimum\":9},\"node_type\":\"MAIN\"},"
                + "{\"description\":\"每日收麦\",\"condition\":{\"asset_key\":\"minecraft:wheat\",\"minimum\":1},\"node_type\":\"DAILY\",\"recurrence\":{\"intervalGameDays\":1,\"missedPolicy\":\"SKIP\",\"requiresFarmBuilt\":true}},"
                + "{\"description\":\"捡东西\",\"condition\":{\"asset_key\":\"minecraft:stick\",\"minimum\":1},\"node_type\":\"SIDE\"},"
                + "{\"description\":\"旧节点\",\"condition\":{\"asset_key\":\"minecraft:dirt\",\"minimum\":1}}]}";
        var specs = RddDecomposer.parse(json);
        assertEquals(4, specs.size(), String.valueOf(specs));
        assertEquals(NodeType.MAIN, specs.get(0).nodeType());
        assertEquals(NodeType.DAILY, specs.get(1).nodeType());
        assertTrue(specs.get(1).recurrence().requiresFarmBuilt());
        assertEquals(NodeType.SIDE, specs.get(2).nodeType());
        assertEquals(NodeType.MAIN, specs.get(3).nodeType(), "旧 JSON 缺字段必须默认 MAIN");
        assertNull(specs.get(3).recurrence());
    }
}
