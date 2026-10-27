package com.dwinovo.numen.core.tools.work;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code animal_lure} —— 把「引动物」这件事变成一次可判的准备动作（2026-10-27，D-12）。
 *
 * <h2>为什么需要（实测缺口）</h2>
 * 全仓此前**没有任何 lead/lure/breed/tame 工具**：想引羊只能靠模型自己知道
 * 「羊跟着手里的小麦走」并且自己切手持槽位。实测后果（#148）：
 * 引羊/繁殖这一步从来没被真正做过——基地食物供给那条链到「种地」就断了。
 *
 * <h2>它做什么、不做什么</h2>
 * <b>做</b>：按动物种类查出对应的诱饵（羊/牛→小麦，鸡→种子，猪→胡萝卜/马铃薯/甜菜根），
 * 检查背包有没有，把它切到手持，然后报告附近有几只、最近多远、在哪。
 * <b>不做</b>：不移动动物、不寻路、不抓。**动物跟着拿诱饵的人走**——走路是执行 AI 的事
 * （它可以用 {@code goto}）。这样分工与既有纪律一致：工具准备条件，身体动作归执行侧。
 *
 * <p><b>为什么不是「把动物传送过来」</b>：那会绕过寻路与地形，等于给了一个作弊口，
 * 而且圈舍没建好时传送只会让动物跑散。
 */
public final class AnimalLureTool implements NumenTool {

    private static final Gson GSON = new Gson();

    /** 各种动物吃什么（原版繁殖食物；同时就是"跟着走"的诱饵）。 */
    private static final Map<String, String> LURE = new LinkedHashMap<>();

    static {
        LURE.put("minecraft:sheep", "minecraft:wheat");
        LURE.put("minecraft:cow", "minecraft:wheat");
        LURE.put("minecraft:mooshroom", "minecraft:wheat");
        LURE.put("minecraft:goat", "minecraft:wheat");
        LURE.put("minecraft:chicken", "minecraft:wheat_seeds");
        LURE.put("minecraft:pig", "minecraft:carrot");
        LURE.put("minecraft:rabbit", "minecraft:carrot");
        LURE.put("minecraft:horse", "minecraft:golden_apple");
        LURE.put("minecraft:donkey", "minecraft:golden_apple");
        LURE.put("minecraft:llama", "minecraft:wheat");
    }

    /** 猪也能用马铃薯/甜菜根：主诱饵拿不到时按顺序兜底。 */
    private static final Map<String, List<String>> ALTERNATIVES = Map.of(
            "minecraft:pig", List.of("minecraft:carrot", "minecraft:potato", "minecraft:beetroot"));

    private record Args(String animal, Integer radius, Integer want) {}

    @Override
    public String name() {
        return "animal_lure";
    }

    @Override
    public String description() {
        return "Prepare to lure animals: picks the right food for that species out of your OWN inventory, "
                + "puts it in your hand, and reports how many are nearby. Animals follow a player who holds "
                + "their food — this tool does NOT move them; walk with `goto` and they follow. "
                + "Use it before leading a herd back to a pen, or before breeding (feed two adults). "
                + "Fails with an explicit reason when you carry no suitable food — get the food first.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("animal", "Entity id, e.g. minecraft:sheep / minecraft:cow / minecraft:chicken.")
                .optionalInteger("radius", "Search radius in blocks (default 16, max 32).", 1, 32)
                .optionalInteger("want", "Stop counting after this many animals (default 8).", 1, 64)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            Args a = GSON.fromJson(args, Args.class);
            String animal = a == null || a.animal() == null ? "" : a.animal().trim();
            if (animal.isBlank()) {
                reply.accept(TaskResult.fail("animal_lure needs an animal id, e.g. minecraft:sheep").toJson());
                return;
            }
            List<String> foods = new ArrayList<>();
            String primary = LURE.get(animal);
            if (primary != null) foods.add(primary);
            foods.addAll(ALTERNATIVES.getOrDefault(animal, List.of()));
            if (foods.isEmpty()) {
                reply.accept(TaskResult.fail("I don't know what lures " + animal
                        + "; known: " + String.join(", ", LURE.keySet())
                        + " (or use feed/lead on a modded mob)").toJson());
                return;
            }
            // 从背包里挑一样真有的诱饵（按优先级），并切到手持。
            var inv = companion.getInventory();
            String chosen = null;
            int slot = -1;
            int have = 0;
            for (String food : foods) {
                var wanted = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .get(net.minecraft.resources.ResourceLocation.tryParse(food));
                if (wanted == null || wanted == net.minecraft.world.item.Items.AIR) continue;
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    var stack = inv.getItem(i);
                    if (!stack.isEmpty() && stack.is(wanted)) {
                        chosen = food;
                        have += stack.getCount();
                        // 优先放到快捷栏（0..8），否则记录槽位后再调。
                        if (slot < 0) slot = i;
                    }
                }
                if (chosen != null) break;
            }
            if (chosen == null) {
                reply.accept(TaskResult.fail("no lure food for " + animal + " in my inventory (need "
                        + String.join(" or ", foods) + "). Get it first — luring without the food does nothing.")
                        .toJson());
                return;
            }
            // 手持：快捷栏直接选；在背包其余槽位就先挪到快捷栏再用。
            if (slot >= 9) {
                int hotbarFree = -1;
                for (int i = 0; i < 9; i++) {
                    if (inv.getItem(i).isEmpty()) { hotbarFree = i; break; }
                }
                if (hotbarFree >= 0) {
                    inv.setItem(hotbarFree, inv.getItem(slot));
                    inv.setItem(slot, net.minecraft.world.item.ItemStack.EMPTY);
                    slot = hotbarFree;
                }
            }
            boolean inHand = slot >= 0 && slot < 9;
            if (inHand) {
                inv.selected = slot;
            }
            int radius = a.radius() == null ? 16 : Math.max(1, Math.min(32, a.radius()));
            int want = a.want() == null ? 8 : Math.max(1, Math.min(64, a.want()));
            int found = 0;
            double nearest = Double.MAX_VALUE;
            List<Map<String, Object>> spots = new ArrayList<>();
            var box = companion.getBoundingBox().inflate(radius);
            for (var e : companion.level().getEntities(companion, box)) {
                if (e.getType() == null) continue;
                String id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                        .getKey(e.getType()).toString();
                if (!animal.equals(id)) continue;
                found++;
                double d = e.distanceTo(companion);
                nearest = Math.min(nearest, d);
                if (spots.size() < 3) {
                    var p = e.blockPosition();
                    spots.add(Map.of("at", p.getX() + "," + p.getY() + "," + p.getZ(),
                            "distance", Math.round(d)));
                }
                if (found >= want) break;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("animal", animal);
            data.put("food", chosen);
            data.put("food_count", have);
            data.put("in_hand", inHand);
            data.put("found", found);
            data.put("radius", radius);
            if (found > 0) data.put("nearest_distance", Math.round(nearest));
            if (!spots.isEmpty()) data.put("nearby", spots);
            if (found == 0) {
                reply.accept(TaskResult.ok("holding " + chosen + " ×" + have
                        + " but no " + animal + " within " + radius + " blocks — walk somewhere else first "
                        + "(goto), then call this again", data).toJson());
                return;
            }
            reply.accept(TaskResult.ok("holding " + chosen + " ×" + have + "; " + found + " " + animal
                    + " within " + radius + " blocks (nearest " + Math.round(nearest) + " blocks). "
                    + "They will follow you while you hold it — walk with goto, don't sprint away. "
                    + "To breed instead: stand close and use feed on two adults.", data).toJson());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("animal_lure failed: " + ex).toJson());
        }
    }
}
