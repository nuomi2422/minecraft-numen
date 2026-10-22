package com.dwinovo.numen.plugins.selfcompile.generated;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 只读：读取某个村民当前的交易报价（换什么、要什么、还能换几次）。
 *
 * <p>为什么需要：村庄经济的第一块拼图是「看见报价」。
 * `interact_entity` 只能点开村民，看不见内容，也换不了；没有报价就写不出「自动交易」。
 *
 * <p>边界：**只读**。不改 world、不扣物品、不刷新交易。实体 id 用 `rdd_nearby_entities` 现查（id 会变）。
 */
public final class RddVillagerTradesTool implements NumenTool {

    private static final int MAX_OFFERS = 20;

    @Override
    public String name() {
        return "rdd_villager_trades";
    }

    @Override
    public String description() {
        return "读取指定村民/流浪商人的交易报价（只读）：每条含 需求物品1/需求物品2/给到的物品/已用次数/上限。"
                + "参数 entity_id 用 rdd_nearby_entities 现查（实体 id 会变，不要缓存）。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("entity_id", Map.of("type", "integer", "minimum", 1,
                "description", "目标村民的实体 id（来自 rdd_nearby_entities）"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("entity_id"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                             Consumer<String> reply) {
        if (args == null || !args.has("entity_id")) {
            reply.accept(TaskResult.fail("需要 entity_id（先用 rdd_nearby_entities 查）").toJson());
            return;
        }
        int entityId = args.get("entity_id").getAsInt();
        if (!(companion.level() instanceof ServerLevel level)) {
            reply.accept(TaskResult.fail("需要服务端世界").toJson());
            return;
        }
        Entity e = level.getEntity(entityId);
        if (e == null) {
            reply.accept(TaskResult.fail("实体不存在或不在加载范围：id=" + entityId).toJson());
            return;
        }
        if (!(e instanceof Villager villager)) {
            reply.accept(TaskResult.fail("id=" + entityId + " 不是村民（是 "
                    + BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()) + "）").toJson());
            return;
        }
        MerchantOffers offers = villager.getOffers();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MerchantOffer o : offers) {
            if (rows.size() >= MAX_OFFERS) break;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cost1", describe(o.getCostA()));
            if (!o.getCostB().isEmpty()) {
                row.put("cost2", describe(o.getCostB()));
            }
            row.put("result", describe(o.getResult()));
            row.put("uses", o.getUses());
            row.put("max_uses", o.getMaxUses());
            row.put("price_multiplier", Math.round(o.getPriceMultiplier() * 100.0) / 100.0);
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entity_id", entityId);
        data.put("profession", villager.getVillagerData().getProfession().toString());
        data.put("level", villager.getVillagerData().getLevel());
        data.put("count", rows.size());
        data.put("offers", rows);
        reply.accept(TaskResult.ok("村民报价 " + rows.size() + " 条", data).toJson());
    }

    private static String describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "empty";
        return BuiltInRegistries.ITEM.getKey(stack.getItem()) + " x" + stack.getCount();
    }
}
