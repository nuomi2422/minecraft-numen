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

    private static String spec(String desc, String type, String rec) {
        return "{\"description\":\"" + desc + "\",\"condition\":{\"asset_key\":\"minecraft:wheat\",\"minimum\":1,\"optional\":true}"
                + (type == null ? "" : ",\"node_type\":\"" + type + "\"")
                + (rec == null ? "" : ",\"recurrence\":" + rec) + "}";
    }

    @Test void maintenanceNodesAreDaily() {
        String json = "{\"subtasks\":["
                + spec("收割成熟小麦并补种", "DAILY", "{\"intervalGameDays\":1,\"missedPolicy\":\"SKIP\",\"requiresFarmBuilt\":true}")
                + "," + spec("喂养已养动物", "DAILY", "{\"intervalGameDays\":1}")
                + "," + spec("存粮/照明巡检", "DAILY", "{\"intervalGameDays\":1}") + "]}";
        var specs = RddDecomposer.parse(json);
        assertEquals(3, specs.size());
        for (var sp : specs) assertEquals(NodeType.DAILY, sp.nodeType(), sp.description());
        assertTrue(specs.get(0).recurrence().requiresFarmBuilt());
    }

    @Test void constructionNodesStayMain() {
        String json = "{\"subtasks\":["
                + spec("开垦一块农田并初始播种", "MAIN", null) + ","
                + spec("建一个储物仓", "MAIN", null) + "]}";
        var specs = RddDecomposer.parse(json);
        assertEquals(2, specs.size());
        for (var sp : specs) assertEquals(NodeType.MAIN, sp.nodeType(), sp.description());
    }

    @Test void promptAndSchemaDeclareDailyContract() {
        String prompt = RddDecomposer.systemPrompt();
        assertTrue(prompt.contains("DAILY") && prompt.contains("重复维护"), prompt);
        assertTrue(prompt.contains("至少 1 条 node_type=DAILY"), prompt);
        var schema = RddDecomposer.decomposeSchema().toString();
        assertTrue(schema.contains("node_type") && schema.contains("recurrence")
                && schema.contains("requiresFarmBuilt"), schema);
    }
}
