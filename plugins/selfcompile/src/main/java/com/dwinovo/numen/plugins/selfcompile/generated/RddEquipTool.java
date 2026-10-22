package com.dwinovo.numen.plugins.selfcompile.generated;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 把手里的某件物品换到**主手**（或副手）。
 *
 * <p>为什么需要（2026-10-23）：`mine`/`interact_at` 都按「手上拿的东西」决定能不能挖/破。
 * 同伴经常背包里有镐、手上却是别的东西（或空手），于是 `mine` 一次次 `FAILED`，
 * 而 MCP 侧看不出是「没工具」还是「够不着」。这个工具就是补上「换到手上」这一步。
 *
 * <p>边界：只在背包里找**已有**物品，不会凭空给东西；找不到就明确报「背包里没有 X」。
 */
public final class RddEquipTool implements NumenTool {

    @Override
    public String name() {
        return "rdd_equip";
    }

    @Override
    public String description() {
        return "把背包里的物品换到主手（offhand=true 则副手）。参数 item 用名字化 id，如 minecraft:wooden_pickaxe。"
                + "挖矿/破坏方块的工具必须在手上，先用这个。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("item", Map.of("type", "string", "description", "物品 id，如 minecraft:wooden_pickaxe"));
        props.put("offhand", Map.of("type", "boolean", "description", "true=副手，默认主手"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("item"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                             Consumer<String> reply) {
        if (args == null || !args.has("item")) {
            reply.accept(TaskResult.fail("需要 item（如 minecraft:wooden_pickaxe）").toJson());
            return;
        }
        String id = args.get("item").getAsString();
        boolean offhand = args.has("offhand") && args.get("offhand").getAsBoolean();
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) {
            reply.accept(TaskResult.fail("物品 id 不合法：" + id).toJson());
            return;
        }
        Item item = BuiltInRegistries.ITEM.get(rl);
        if (item == null || item == Items.AIR) {
            reply.accept(TaskResult.fail("未知物品：" + id).toJson());
            return;
        }
        var inv = companion.getInventory();
        int slot = -1;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() == item) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            reply.accept(TaskResult.fail("背包里没有 " + id).toJson());
            return;
        }
        ItemStack stack = inv.getItem(slot);
        ItemStack previous = offhand ? companion.getOffhandItem().copy()
                : companion.getMainHandItem().copy();
        // 与背包槽交换：把手上的东西放回该槽，避免凭空多出物品
        inv.setItem(slot, previous);
        companion.setItemInHand(offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, stack);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("now_holding", BuiltInRegistries.ITEM.getKey(companion.getMainHandItem().getItem()).toString());
        data.put("previous", previous.isEmpty() ? "empty"
                : BuiltInRegistries.ITEM.getKey(previous.getItem()).toString());
        data.put("hand", offhand ? "off" : "main");
        reply.accept(TaskResult.ok("已把 " + id + " 换到" + (offhand ? "副手" : "主手"), data).toJson());
    }
}
