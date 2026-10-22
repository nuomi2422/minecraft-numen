package com.dwinovo.numen.plugins.selfcompile.generated;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 列出同伴附近有哪些实体（只读、无副作用）。
 *
 * <p>为什么需要：村庄经济（交易/刷职业/繁殖）第一步是「知道村民在哪」。
 * 现有能力里没有实体读取（`look_around` 只有地形符号，`entities` 是内部 worldstate provider，
 * 不在 MCP 工具面），所以补这个最小的只读工具。
 *
 * <p>边界：只返回**已加载**实体；范围上限 64，条数上限 60，避免一次拉爆上下文。
 */
public final class RddNearbyEntitiesTool implements NumenTool {

    private static final int MAX_RADIUS = 64;
    private static final int MAX_LIMIT = 60;

    @Override
    public String name() {
        return "rdd_nearby_entities";
    }

    @Override
    public String description() {
        return "列出同伴附近半径内的实体（类型、实体 id、坐标、距离）。找村民/铁傀儡/动物时用。"
                + "可选参数 radius（默认 32，上限 64）与 limit（默认 40，上限 60）。只读。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("radius", Map.of("type", "integer", "minimum", 1, "maximum", MAX_RADIUS,
                "description", "搜索半径（方块），默认 32"));
        props.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", MAX_LIMIT,
                "description", "最多返回多少条，默认 40"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                             Consumer<String> reply) {
        int radius = 32;
        int limit = 40;
        if (args != null) {
            if (args.has("radius") && args.get("radius").isJsonPrimitive()) {
                radius = Math.max(1, Math.min(MAX_RADIUS, args.get("radius").getAsInt()));
            }
            if (args.has("limit") && args.get("limit").isJsonPrimitive()) {
                limit = Math.max(1, Math.min(MAX_LIMIT, args.get("limit").getAsInt()));
            }
        }
        if (!(companion.level() instanceof ServerLevel level)) {
            reply.accept(TaskResult.fail("nearby_entities 需要服务端世界").toJson());
            return;
        }
        AABB box = companion.getBoundingBox().inflate(radius);
        List<Entity> found = level.getEntities(companion, box, e -> e != companion && e.isAlive());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Entity e : found) {
            if (rows.size() >= limit) break;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
            row.put("id", e.getId());
            row.put("x", Math.round(e.getX() * 10.0) / 10.0);
            row.put("y", Math.round(e.getY() * 10.0) / 10.0);
            row.put("z", Math.round(e.getZ() * 10.0) / 10.0);
            row.put("distance", Math.round(e.distanceTo(companion) * 10.0) / 10.0);
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("radius", radius);
        data.put("count", rows.size());
        data.put("entities", rows);
        reply.accept(TaskResult.ok("附近实体 " + rows.size() + " 个", data).toJson());
    }
}
