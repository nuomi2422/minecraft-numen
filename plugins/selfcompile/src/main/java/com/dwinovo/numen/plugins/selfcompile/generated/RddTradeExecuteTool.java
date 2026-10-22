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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 执行一笔村民交易：按报价序号，扣掉需求物品、拿到结果物品、记一次使用。
 *
 * <p>为什么需要：`rdd_villager_trades` 只能「看」，看完换不了。村庄经济要产出绿宝石，
 * 必须有「换」这一步，否则永远停在观察。
 *
 * <p><b>这是写操作</b>（会移动物品），所以规则写死：
 * <ul>
 *   <li>材料不够 → 直接拒绝，<b>不做部分支付</b>；</li>
 *   <li>背包放不下结果 → 拒绝（不把东西丢在地上等它消失）；</li>
 *   <li>成交后返回「扣了什么、拿到什么、还剩几次」——回执必须能被外部核对。</li>
 * </ul>
 */
public final class RddTradeExecuteTool implements NumenTool {

    @Override
    public String name() {
        return "rdd_trade_execute";
    }

    @Override
    public String description() {
        return "执行一笔村民交易：给 entity_id 与 offer_index（从 rdd_villager_trades 的排序取，0 起），"
                + "扣掉需求物品并拿到结果。材料不足或背包放不下会拒绝，不会部分支付。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("entity_id", Map.of("type", "integer", "minimum", 1,
                "description", "村民实体 id（用 rdd_nearby_entities 现查）"));
        props.put("offer_index", Map.of("type", "integer", "minimum", 0,
                "description", "报价序号，0 = 第一条（与 rdd_villager_trades 的顺序一致）"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", java.util.List.of("entity_id", "offer_index"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                             Consumer<String> reply) {
        if (args == null || !args.has("entity_id") || !args.has("offer_index")) {
            reply.accept(TaskResult.fail("需要 entity_id 与 offer_index").toJson());
            return;
        }
        int entityId = args.get("entity_id").getAsInt();
        int index = args.get("offer_index").getAsInt();
        if (!(companion.level() instanceof ServerLevel level)) {
            reply.accept(TaskResult.fail("需要服务端世界").toJson());
            return;
        }
        Entity e = level.getEntity(entityId);
        if (!(e instanceof Villager villager)) {
            reply.accept(TaskResult.fail("id=" + entityId + " 不是村民").toJson());
            return;
        }
        MerchantOffers offers = villager.getOffers();
        if (index < 0 || index >= offers.size()) {
            reply.accept(TaskResult.fail("offer_index 越界：共 " + offers.size() + " 条").toJson());
            return;
        }
        MerchantOffer offer = offers.get(index);
        ItemStack costA = offer.getCostA().copy();
        ItemStack costB = offer.getCostB().copy();
        ItemStack result = offer.getResult().copy();

        int haveA = countItem(companion, costA);
        int haveB = costB.isEmpty() ? Integer.MAX_VALUE : countItem(companion, costB);
        if (haveA < costA.getCount() || haveB < (costB.isEmpty() ? 0 : costB.getCount())) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("need", describe(costA) + (costB.isEmpty() ? "" : " + " + describe(costB)));
            data.put("have", "cost1=" + haveA + (costB.isEmpty() ? "" : " cost2=" + haveB));
            reply.accept(TaskResult.fail("材料不足，未交易（不做部分支付）", data).toJson());
            return;
        }
        int freeSlots = countFreeSlots(companion, result);
        if (freeSlots <= 0) {
            reply.accept(TaskResult.fail("背包放不下 " + describe(result) + "，未交易").toJson());
            return;
        }
        removeItem(companion, costA);
        if (!costB.isEmpty()) removeItem(companion, costB);
        ItemStack toGive = result.copy();
        boolean added = companion.getInventory().add(toGive);
        if (!added) {
            // 极端情况：加不进去。把结果丢在脚下，但明确告知（不静默吞掉）
            companion.drop(toGive, false);
        }
        villager.notifyTrade(offer);   // 让村民自己记这一笔（uses/统计/音效），不手改内部字段
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paid", describe(costA) + (costB.isEmpty() ? "" : " + " + describe(costB)));
        data.put("got", describe(result));
        data.put("dropped_if_full", !added);
        data.put("uses", offer.getUses());
        data.put("max_uses", offer.getMaxUses());
        reply.accept(TaskResult.ok("交易完成：" + describe(costA) + " → " + describe(result), data).toJson());
    }

    private static int countItem(NumenPlayer p, ItemStack proto) {
        int n = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, proto)) n += s.getCount();
        }
        return n;
    }

    private static void removeItem(NumenPlayer p, ItemStack proto) {
        int left = proto.getCount();
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !ItemStack.isSameItemSameComponents(s, proto)) continue;
            int take = Math.min(left, s.getCount());
            s.shrink(take);
            left -= take;
            if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
        }
    }

    private static int countFreeSlots(NumenPlayer p, ItemStack result) {
        var inv = p.getInventory();
        int free = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) free++;
            else if (ItemStack.isSameItemSameComponents(s, result)
                    && s.getCount() + result.getCount() <= s.getMaxStackSize()) free++;
        }
        return free;
    }

    private static String describe(ItemStack s) {
        if (s == null || s.isEmpty()) return "empty";
        return BuiltInRegistries.ITEM.getKey(s.getItem()) + " x" + s.getCount();
    }
}
