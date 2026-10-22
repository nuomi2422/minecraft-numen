package com.dwinovo.numen.plugins.selfcompile.generated;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 熔炉两步操作（因为服务端不能 sleep，拆成 load / take 两次调用，中间让真实时间过去）。
 *
 * <ul>
 *   <li>{@code action=load}：把输入与燃料放进指定坐标的熔炉（找不到就报坐标不存在）；</li>
 *   <li>{@code action=take}：把已烧好的产物收进背包，并返回「收了多少 / 还剩多少」。</li>
 * </ul>
 *
 * <p><b>为什么需要</b>：游戏里没有 `smelt` 工具，`craft` 只管合成台配方。
 * 没有这一步就跑不通「烧铁 → 铁锭 → 铁镐」，主线卡在铁上。
 *
 * <p>规则：只操作**已存在**的熔炉（不凭空生成）；背包放不下就不硬塞，回执里明说。
 */
public final class RddFurnaceTool implements NumenTool {

    private static final int SLOT_INPUT = 0;
    private static final int SLOT_FUEL = 1;
    private static final int SLOT_OUTPUT = 2;

    @Override
    public String name() {
        return "rdd_furnace";
    }

    @Override
    public String description() {
        return "操作指定坐标的熔炉：action=load 放入输入(item)+燃料(fuel)，action=take 收取产物。"
                + "两步之间要等真实时间（每件约 10 秒）。只操作已存在的熔炉。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("action", Map.of("type", "string", "enum", List.of("load", "take"),
                "description", "load=放料，take=收货"));
        props.put("x", Map.of("type", "integer", "description", "熔炉 x"));
        props.put("y", Map.of("type", "integer", "description", "熔炉 y"));
        props.put("z", Map.of("type", "integer", "description", "熔炉 z"));
        props.put("item", Map.of("type", "string", "description", "load 时的输入物品 id（如 minecraft:raw_iron）"));
        props.put("fuel", Map.of("type", "string", "description", "load 时的燃料 id（默认 minecraft:coal）"));
        props.put("count", Map.of("type", "integer", "minimum", 1, "maximum", 64,
                "description", "load 时放入的输入数量，默认 1"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("action", "x", "y", "z"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                             Consumer<String> reply) {
        if (args == null || !args.has("action")) {
            reply.accept(TaskResult.fail("需要 action=load|take").toJson());
            return;
        }
        String action = args.get("action").getAsString();
        int x = args.get("x").getAsInt();
        int y = args.get("y").getAsInt();
        int z = args.get("z").getAsInt();
        if (!(companion.level() instanceof ServerLevel level)) {
            reply.accept(TaskResult.fail("需要服务端世界").toJson());
            return;
        }
        BlockPos pos = new BlockPos(x, y, z);
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof AbstractFurnaceBlockEntity furnace)) {
            reply.accept(TaskResult.fail("该坐标不是熔炉：" + x + "," + y + "," + z).toJson());
            return;
        }
        if ("load".equals(action)) {
            String itemId = args.has("item") ? args.get("item").getAsString() : "minecraft:raw_iron";
            String fuelId = args.has("fuel") ? args.get("fuel").getAsString() : "minecraft:coal";
            int count = args.has("count") ? Math.min(64, Math.max(1, args.get("count").getAsInt())) : 1;
            ItemStack inputProto = stackOf(itemId, count);
            ItemStack fuelProto = stackOf(fuelId, 1);
            if (inputProto == null || fuelProto == null) {
                reply.accept(TaskResult.fail("物品 id 不合法").toJson());
                return;
            }
            int putInput = moveInto(companion, furnace, SLOT_INPUT, inputProto, count);
            int putFuel = moveInto(companion, furnace, SLOT_FUEL, fuelProto, 1);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("input_added", putInput);
            data.put("fuel_added", putFuel);
            data.put("furnace_input", furnace.getItem(SLOT_INPUT).getCount());
            data.put("furnace_fuel", furnace.getItem(SLOT_FUEL).getCount());
            reply.accept(TaskResult.ok("放料：输入 +" + putInput + "，燃料 +" + putFuel, data).toJson());
            return;
        }
        if ("take".equals(action)) {
            ItemStack out = furnace.getItem(SLOT_OUTPUT);
            if (out.isEmpty()) {
                reply.accept(TaskResult.fail("熔炉里还没有产物（再等等）").toJson());
                return;
            }
            ItemStack copy = out.copy();
            boolean added = companion.getInventory().add(copy);
            if (!added) {
                reply.accept(TaskResult.fail("背包放不下，产物留在熔炉里：" + describe(out)).toJson());
                return;
            }
            furnace.setItem(SLOT_OUTPUT, copy.isEmpty() ? ItemStack.EMPTY : copy);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("collected", describe(out));
            data.put("left_in_furnace", furnace.getItem(SLOT_OUTPUT).getCount());
            reply.accept(TaskResult.ok("收到 " + describe(out), data).toJson());
            return;
        }
        reply.accept(TaskResult.fail("action 只能是 load 或 take").toJson());
    }

    private static ItemStack stackOf(String id, int count) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        var item = BuiltInRegistries.ITEM.get(rl);
        if (item == null || item == Items.AIR) return null;
        return new ItemStack(item, count);
    }

    /** 从背包搬 count 个到熔炉槽位，返回实际搬入数量。 */
    private static int moveInto(NumenPlayer p, AbstractFurnaceBlockEntity furnace, int slot,
                                ItemStack proto, int count) {
        ItemStack existing = furnace.getItem(slot);
        int room = existing.isEmpty() ? Math.min(count, proto.getMaxStackSize())
                : (ItemStack.isSameItemSameComponents(existing, proto)
                        ? Math.min(count, existing.getMaxStackSize() - existing.getCount()) : 0);
        if (room <= 0) return 0;
        int taken = takeFromInventory(p, proto, room);
        if (taken <= 0) return 0;
        if (existing.isEmpty()) {
            furnace.setItem(slot, new ItemStack(proto.getItem(), taken));
        } else {
            existing.grow(taken);
            furnace.setItem(slot, existing);
        }
        return taken;
    }

    private static int takeFromInventory(NumenPlayer p, ItemStack proto, int want) {
        int left = want;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !ItemStack.isSameItemSameComponents(s, proto)) continue;
            int take = Math.min(left, s.getCount());
            s.shrink(take);
            left -= take;
            if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
        }
        return want - left;
    }

    private static String describe(ItemStack s) {
        if (s == null || s.isEmpty()) return "empty";
        return BuiltInRegistries.ITEM.getKey(s.getItem()) + " x" + s.getCount();
    }
}
