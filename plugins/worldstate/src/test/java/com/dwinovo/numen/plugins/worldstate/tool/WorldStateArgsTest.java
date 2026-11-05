package com.dwinovo.numen.plugins.worldstate.tool;

import com.dwinovo.numen.worldstate.core.model.Topic;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 042 R1-5：**schema 允许的输入 → 解析结果/报错**（不需要 NumenPlayer）。
 *
 * <p>为什么要有：onServerCall 依赖 MC，离线起不来，于是"公布出去的 schema"和"实际解析"
 * 之间一直没有测试。这里用 Gson 直接构造模型按 schema 会发来的那种 JSON，断言落到正确分支。
 */
class WorldStateArgsTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static final String BOX = """
            "x1": 0, "y1": 60, "z1": 0, "x2": 16, "y2": 80, "z2": 16
            """;

    @Test
    void missingTopicFailsWithActionableMessage() {
        var out = WorldStateArgs.forReadRefresh(null, json("{\"action\":\"read\"}"), "minecraft:overworld");
        assertFalse(out.ok());
        assertTrue(out.error().contains("topic is required"), out.error());
        assertTrue(out.error().contains("read|refresh"), "要说清只对这两个动作必需：" + out.error());
    }

    @Test
    void environmentNeedsNothingElse() {
        var out = WorldStateArgs.forReadRefresh(Topic.ENVIRONMENT,
                json("{\"action\":\"read\",\"topic\":\"environment\"}"), "minecraft:overworld");
        assertTrue(out.ok(), out.error());
        assertNotNull(out.request());
    }

    @Test
    void containersWithoutTargetNamesTheField() {
        var out = WorldStateArgs.forReadRefresh(Topic.CONTAINERS,
                json("{\"action\":\"read\",\"topic\":\"containers\"}"), "minecraft:overworld");
        assertFalse(out.ok());
        assertTrue(out.error().contains("container=block:"), "要直接给用法，别只说参数非法：" + out.error());
    }

    @Test
    void containersWithBadTargetFails() {
        var out = WorldStateArgs.forReadRefresh(Topic.CONTAINERS,
                json("{\"action\":\"read\",\"topic\":\"containers\",\"container\":\"nonsense\"}"),
                "minecraft:overworld");
        assertFalse(out.ok(), "非法容器目标必须失败，不能猜");
        assertTrue(out.error().contains("container="), out.error());
    }

    @Test
    void blocksWithoutBoxFails() {
        var out = WorldStateArgs.forReadRefresh(Topic.BLOCKS,
                json("{\"action\":\"read\",\"topic\":\"blocks\",\"block\":\"minecraft:stone\"}"),
                "minecraft:overworld");
        assertFalse(out.ok());
        assertTrue(out.error().contains("needs a box"), out.error());
    }

    @Test
    void partialBoxIsNotGuessed() {
        var out = WorldStateArgs.forReadRefresh(Topic.BLOCKS,
                json("{ \"action\":\"read\", \"topic\":\"blocks\", \"block\":\"minecraft:stone\", \"x1\":0, \"y1\":60, \"z1\":0 }"),
                "minecraft:overworld");
        assertFalse(out.ok(), "只给一角时不许用默认值补齐");
    }

    @Test
    void entitiesWithFullBoxIsAccepted() {
        var out = WorldStateArgs.forReadRefresh(Topic.ENTITIES,
                json("{ \"action\":\"read\", \"topic\":\"entities\", " + BOX + " }"),
                "minecraft:overworld");
        assertTrue(out.ok(), out.error());
        assertNotNull(out.request());
    }

    @Test
    void dimensionIsCarriedThrough() {
        var out = WorldStateArgs.forReadRefresh(Topic.ENVIRONMENT,
                json("{\"action\":\"read\",\"topic\":\"environment\",\"dimension\":\"minecraft:the_nether\"}"),
                "minecraft:the_nether");
        assertTrue(out.ok(), out.error());
        assertEquals("minecraft:the_nether", out.request().dimension());
    }
}
