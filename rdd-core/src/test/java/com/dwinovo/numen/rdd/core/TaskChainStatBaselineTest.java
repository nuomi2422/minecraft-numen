package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 169 实机：统计类条件要按「本步增量」判，基线必须可拍、幂等、活过重启。 */
class TaskChainStatBaselineTest {

    private static TaskChain chain() {
        Subtask s = Subtask.hardCoded("s1", "craft bread",
                Map.of("type", "item_crafted", "item", "minecraft:bread", "minimum", 1));
        return new TaskChain(new Goal("g", "goal", List.of(new PrimaryGoal("p", "阶段", List.of(s)))));
    }

    @Test
    void baselineIsCapturedOnceAndReadBack() {
        TaskChain c = chain();
        c.captureStatBaseline("s1", 5);
        c.captureStatBaseline("s1", 99);   // 幂等：不许把基线推到"已经做完"的位置
        assertEquals(5, c.statBaseline("s1").get("value"));
        assertNull(c.statBaseline("missing"));
    }

    @Test
    void replanSameIdClearsStatBaseline() {
        TaskChain c = chain();
        c.captureStatBaseline("s1", 5);
        c.forgetAcquireBaseline("s1");
        assertNull(c.statBaseline("s1"), "同 ID 重跑必须清掉上一轮的 #stat 基线，否则会吃旧增量假通过");
    }

    @Test
    void newChainDefaultsToDeltaAndLegacyJsonStaysLifetime() {
        TaskChain c = chain();
        assertTrue(c.statDeltaEnabled(), "新建链默认 delta");
        com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(c.toJson()).getAsJsonObject();
        o.remove("statDeltaEnabled");
        TaskChain legacy = TaskChain.fromJson(o.toString());
        assertFalse(legacy.statDeltaEnabled(), "旧存档没有字段 → 继续 lifetime，不能突然改判");
    }

    @Test
    void baselineSurvivesRoundTrip() {
        TaskChain c = chain();
        c.captureStatBaseline("s1", 7);
        TaskChain r = TaskChain.fromJson(c.toJson());
        assertNotNull(r.statBaseline("s1"));
        assertEquals(7, r.statBaseline("s1").get("value"));
    }
}
