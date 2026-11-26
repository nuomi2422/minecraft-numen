package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.core.combat.Menace;
import com.dwinovo.numen.core.combat.ThreatFacts;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Query tool implementations — the business half of {@code LookupRecipeTool},
 * {@code ScanNearbyEntitiesTool} and {@code InspectBlockStorageTool}.
 */
public final class QueryExtraOps {

    // ---- scan_nearby_entities ----

    private static final int MAX_RESULTS = 20;
    private static final int MAX_RAY_POSITIONS = 32;
    private static final double MIN_RADIUS = 1.0;
    private static final double MAX_RADIUS = 64.0;

    public String scanNearbyEntities(
double radius,
String type_filter,
            NumenPlayer self) {
        radius = Math.clamp(radius, MIN_RADIUS, MAX_RADIUS);
        String filter = readEnum("type_filter", type_filter,
                List.of("hostile", "passive", "player", "all"));

        AABB box = self.getBoundingBox().inflate(radius);
        List<Entity> raw = self.level().getEntities(self, box);

        List<ScoredEntity> matched = new ArrayList<>(raw.size());
        for (Entity e : raw) {
            // 2026-10-29（末影龙坐标全 0）：末影龙是多部件实体，
            //   getEntities 会把每个 EnderDragonPart 也当成独立实体返回（同一条龙出现 3~4 次，
            //   而且部件的 getX/getY/getZ 全是 0）⇒ shoot 没有瞄准点。这里跳过所有部件，只保留主实体。
            if (e instanceof net.minecraft.world.entity.boss.EnderDragonPart) continue;
            String cat = categorise(e);
            if (!matches(filter, cat)) continue;
            matched.add(new ScoredEntity(e, cat, self.distanceTo(e)));
        }
        matched.sort(Comparator.comparingDouble(s -> s.distance));

        JsonArray entities = new JsonArray();
        int limit = Math.min(matched.size(), MAX_RESULTS);
        for (int i = 0; i < limit; i++) {
            ScoredEntity s = matched.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("id", s.entity.getId());
            o.addProperty("type", s.entity.getType().getDescriptionId());
            o.addProperty("category", s.category);
            JsonObject pos = new JsonObject();
            pos.addProperty("x", s.entity.getX());
            pos.addProperty("y", s.entity.getY());
            pos.addProperty("z", s.entity.getZ());
            o.add("position", pos);
            o.addProperty("distance", s.distance);
            if (s.entity instanceof LivingEntity le) {
                o.addProperty("hp", le.getHealth());
                o.addProperty("max_hp", le.getMaxHealth());
                o.addProperty("armor", le.getArmorValue());
                JsonObject equipment = new JsonObject();
                addEquipment(equipment, EquipmentSlot.MAINHAND, le);
                addEquipment(equipment, EquipmentSlot.OFFHAND, le);
                addEquipment(equipment, EquipmentSlot.HEAD, le);
                addEquipment(equipment, EquipmentSlot.CHEST, le);
                addEquipment(equipment, EquipmentSlot.LEGS, le);
                addEquipment(equipment, EquipmentSlot.FEET, le);
                o.add("equipment", equipment);

                boolean targeting = le instanceof net.minecraft.world.entity.Mob mob
                        && mob.getTarget() == self;
                boolean hostile = Menace.hostile(le) || targeting;
                boolean explosive = Menace.explodes(le);
                boolean armed = Menace.armed(le);
                boolean lineOfSight = self.hasLineOfSight(le);
                int solidBlocks = countSolidBlocks(
                        blockPos -> self.level().getBlockState(blockPos).isSolid(),
                        self.getEyePosition(), le.getEyePosition());
                double dangerRadius = Menace.dangerRadius(le, self);
                o.addProperty("targeting", targeting);
                o.addProperty("line_of_sight", lineOfSight);
                o.addProperty("solid_blocks_between", solidBlocks);
                o.addProperty("threat", ThreatFacts.classify(
                        hostile, targeting, explosive, armed, lineOfSight, solidBlocks,
                        self.distanceToSqr(le), dangerRadius * dangerRadius).name());
            }
            entities.add(o);
        }

        JsonObject root = new JsonObject();
        root.add("entities", entities);
        root.addProperty("total_found", matched.size());
        root.addProperty("truncated", matched.size() > MAX_RESULTS);
        root.addProperty("radius_searched", radius);
        root.addProperty("filter", filter);
        // 2026-10-29 实机（末地）：她扫出了 end_crystal（id=112，12.9 格），然后就停住了 ——
        //   数据齐全、字段名也对，缺的是"扫完了下一步该干什么"。经验库里的三步她没检索到，
        //   所以在**需要的那一刻**把下一步塞进回执里（tool_result 是即时通道，不依赖检索）。
        if (!matched.isEmpty()) {
            root.addProperty("hint",
                    "先看 threat 和 line_of_sight：IMMEDIATE 才需立即应对；OCCLUDED 表示有威胁但被墙挡，"
                            + "REMOTE 不要仅凭数量冲锋；solid_blocks_between 是射线上的实心方块数（最多计 32 个）。"
                            + "要打其中某个：attack(entity_ids=[它的 id])（attack 会自己决定靠近砍还是拉弓射）；"
                            + "id 每帧可能变，扫完就立刻打，不要隔回合再用旧 id。");
        }
        return root.toString();
    }

    private static String categorise(Entity e) {
        if (e instanceof Player) return "player";
        if (e instanceof Monster) return "hostile";
        return "passive";
    }

    private static boolean matches(String filter, String category) {
        if ("all".equals(filter)) return true;
        return filter.equals(category);
    }

    private static void addEquipment(JsonObject equipment, EquipmentSlot slot, LivingEntity entity) {
        ItemStack stack = entity.getItemBySlot(slot);
        if (!stack.isEmpty()) {
            equipment.addProperty(slot.getName(), BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
    }

    /**
     * Grid DDA along one eye-to-eye segment, never a volume scan. At most 32 distinct block
     * positions are inspected per entity; vanilla line-of-sight remains authoritative for cover.
     */
    static int countSolidBlocks(Predicate<BlockPos> solid, Vec3 start, Vec3 end) {
        BlockPos startPos = BlockPos.containing(start);
        BlockPos endPos = BlockPos.containing(end);
        if (startPos.equals(endPos)) {
            return 0;
        }

        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double dz = end.z - start.z;
        int stepX = Double.compare(dx, 0.0);
        int stepY = Double.compare(dy, 0.0);
        int stepZ = Double.compare(dz, 0.0);
        double tMaxX = stepX > 0 ? (startPos.getX() + 1 - start.x) / dx
                : stepX < 0 ? (start.x - startPos.getX()) / -dx : Double.POSITIVE_INFINITY;
        double tMaxY = stepY > 0 ? (startPos.getY() + 1 - start.y) / dy
                : stepY < 0 ? (start.y - startPos.getY()) / -dy : Double.POSITIVE_INFINITY;
        double tMaxZ = stepZ > 0 ? (startPos.getZ() + 1 - start.z) / dz
                : stepZ < 0 ? (start.z - startPos.getZ()) / -dz : Double.POSITIVE_INFINITY;
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dx);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dy);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dz);

        Set<BlockPos> visited = new HashSet<>(MAX_RAY_POSITIONS * 2);
        BlockPos pos = startPos;
        while (visited.size() < MAX_RAY_POSITIONS) {
            visited.add(pos.immutable());
            if (pos.equals(endPos)) {
                break;
            }
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                pos = pos.relative(net.minecraft.core.Direction.EAST, stepX);
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                pos = pos.relative(net.minecraft.core.Direction.UP, stepY);
                tMaxY += tDeltaY;
            } else {
                pos = pos.relative(net.minecraft.core.Direction.SOUTH, stepZ);
                tMaxZ += tDeltaZ;
            }
            double nextT = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
            if (nextT > 1.0 && !pos.equals(endPos)) {
                break;
            }
        }
        int solids = 0;
        for (BlockPos checked : visited) {
            // Do not blame the block containing our own eyes (e.g. a slab we are standing in).
            if (!checked.equals(startPos) && solid.test(checked)) {
                solids++;
            }
        }
        return solids;
    }

    private record ScoredEntity(Entity entity, String category, double distance) {}

    private static String readEnum(String key, String value, List<String> allowed) {
        if (value == null) {
            throw new IllegalArgumentException("missing required argument: " + key);
        }
        String v = value;
        if (!allowed.contains(v)) {
            throw new IllegalArgumentException(
                    "argument '" + key + "' must be one of " + allowed + ", got: " + v);
        }
        return v;
    }

    // ---- lookup_recipe ----

    /** Cap recipes per lookup — enough variants to choose from without a token bomb. */
    private static final int MAX_RECIPES = 4;

    public String lookupRecipe(
String item_id,
            NumenPlayer self) {
        Item target = ToolArgs.parseItem(item_id);
        if (!(self.level() instanceof ServerLevel level)) {
            return TaskResult.fail("recipe lookup needs a server level.").toJson();
        }
        String name = BuiltInRegistries.ITEM.getKey(target).getPath();

        List<String> recipes = new ArrayList<>();
        for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
            if (recipes.size() >= MAX_RECIPES) {
                break;
            }
            // 每条配方自成一格:整合包里一条坏配方(产出为 null、输入表为 null)
            // 只丢它自己,绝不让它杀掉整个查询。见 RecipeProbe。
            try {
                Recipe<?> r = holder.value();
                if (r instanceof CraftingRecipe cr) {
                    // 产出依赖输入的配方(烟花、镶零件的装备)静态描述答不了;
                    // 1.21.1 没有 PlacementInfo,空输入表的老启发式一并保留。
                    if (cr.isSpecial() || cr.getIngredients().isEmpty()
                            || cr.getIngredients().stream().allMatch(Ingredient::isEmpty)) {
                        continue;
                    }
                    ItemStack result = RecipeProbe.resultOf(cr, level.registryAccess());
                    if (result.isEmpty() || result.getItem() != target) {
                        continue;
                    }
                    recipes.add("[crafting] " + format(cr, result));
                } else if (r instanceof AbstractCookingRecipe cook) {
                    ItemStack result = RecipeProbe.resultOf(cook, level.registryAccess());
                    if (result.isEmpty() || result.getItem() != target) {
                        continue;
                    }
                    recipes.add(formatCooking(cook, result));
                } else if (r instanceof StonecutterRecipe sc) {
                    ItemStack result = RecipeProbe.resultOf(sc, level.registryAccess());
                    if (result.isEmpty() || result.getItem() != target) {
                        continue;
                    }
                    recipes.add("[stonecutter] " + describeIngredient(sc.getIngredients().get(0))
                            + " -> makes " + result.getCount());
                } else if (r instanceof SmithingRecipe sm) {
                    // 锻造不走展示产出,保留空输入 assemble 的既有语义:变换配方
                    // (下界合金升级)照样给出产物,纹饰配方产出(空的)基底、自然排除。
                    ItemStack result = RecipeProbe.probe(() -> sm.assemble(new SmithingRecipeInput(
                            ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY), level.registryAccess()));
                    if (result.isEmpty() || result.getItem() != target) {
                        continue;
                    }
                    recipes.add(formatSmithing(sm, result));
                }
            } catch (RuntimeException broken) {
                com.dwinovo.numen.core.Constants.LOG.debug(
                        "[numen-recipe] 配方 {} 坏了,跳过: {}", holder.id(), broken.toString());
            }
        }

        if (recipes.isEmpty()) {
            return TaskResult.ok("no recipe for " + name + " — it's obtained another way (mine it, or "
                    + "trade), not crafted or smelted.").toJson();
        }
        return TaskResult.ok("recipe(s) for " + name + ":\n\n" + String.join("\n\n", recipes) + "\n\n"
                + "To make it —\n"
                + "• [crafting]: call craft {item_id, count} — it lays out the grid and takes the "
                + "result for you (a 3x3 recipe needs a crafting table within reach; 2x2 works "
                + "anywhere).\n"
                + "• [smelting|blasting|smoking]: interact_at the furnace, then transfer the input and "
                + "the fuel with NO `to` — the menu routes each to its slot. Wait, then transfer the "
                + "output back out.\n"
                + "• [stonecutter]: interact_at it, transfer the input (no `to` routes it in), take the "
                + "output. [smithing]: interact_at it, inspect_gui, then transfer template + base + "
                + "addition each into its own slot (give `to`).").toJson();
    }

    private static String format(CraftingRecipe recipe, ItemStack result) {
        int count = result.getCount();
        if (recipe instanceof ShapedRecipe shaped) {
            int w = shaped.getWidth();
            int h = shaped.getHeight();
            var cells = shaped.getIngredients();   // 1.21.1: NonNullList<Ingredient>, gaps = Ingredient.EMPTY
            StringBuilder sb = new StringBuilder("shaped " + w + "x" + h + ", makes " + count + ":");
            for (int r = 0; r < h; r++) {
                sb.append("\n  ");
                for (int c = 0; c < w; c++) {
                    Ingredient ing = cells.get(r * w + c);
                    sb.append(ing.isEmpty() ? "." : describeIngredient(ing));
                    if (c < w - 1) {
                        sb.append(" | ");
                    }
                }
            }
            return sb.toString();
        }
        // Shapeless: order doesn't matter, place anywhere.
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Ingredient ing : recipe.getIngredients()) {
            if (ing.isEmpty()) continue;
            counts.merge(describeIngredient(ing), 1, Integer::sum);
        }
        String list = counts.entrySet().stream()
                .map(e -> e.getValue() + "x " + e.getKey())
                .collect(Collectors.joining(", "));
        return "shapeless, makes " + count + ": " + list + " (place anywhere in the grid)";
    }

    /** A cooking recipe (one input → one output) labelled by its station. */
    private static String formatCooking(AbstractCookingRecipe recipe, ItemStack result) {
        RecipeType<?> type = recipe.getType();
        String station = type == RecipeType.BLASTING ? "blasting (blast furnace)"
                : type == RecipeType.SMOKING ? "smoking (smoker)"
                : type == RecipeType.CAMPFIRE_COOKING ? "campfire"
                : "smelting (furnace)";
        return "[" + station + "] " + describeIngredient(recipe.getIngredients().get(0)) + " -> makes "
                + result.getCount() + " (" + recipe.getCookingTime() + " ticks)";
    }

    /** A smithing recipe (smithing table). 1.21.1's SmithingRecipe exposes only is*Ingredient(stack)
     *  tests — no ingredient getters — so we can't enumerate the inputs; describe the station + result. */
    private static String formatSmithing(SmithingRecipe recipe, ItemStack result) {
        return "[smithing] (smithing table: template + base + addition) -> makes " + result.getCount();
    }

    /** Name an ingredient: a single item directly, a shared-suffix tag as "planks (any)", else a few
     *  members — so a category ingredient doesn't mislead the model into one specific item.
     *  Package-visible: the craft tool names its material shortfalls with the same vocabulary. */
    static String describeIngredient(Ingredient ing) {
        List<String> paths = java.util.Arrays.stream(ing.getItems())   // 1.21.1: getItems() -> ItemStack[]
                .map(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath())
                .distinct()
                .toList();
        if (paths.isEmpty()) {
            return "?";
        }
        if (paths.size() == 1) {
            return paths.get(0);
        }
        String suffix = commonSuffixToken(paths);
        if (suffix != null) {
            return suffix + "(any)";
        }
        return "any[" + paths.stream().limit(3).collect(Collectors.joining("/"))
                + (paths.size() > 3 ? "/…" : "") + "]";
    }

    private static String commonSuffixToken(List<String> paths) {
        String token = null;
        for (String p : paths) {
            int u = p.lastIndexOf('_');
            String t = u < 0 ? p : p.substring(u + 1);
            if (token == null) {
                token = t;
            } else if (!token.equals(t)) {
                return null;
            }
        }
        return token;
    }

    // ---- inspect_block_storage ----

    public String inspectBlockStorage(int x,
int y,
int z,
                                      NumenPlayer self) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = self.level().getBlockState(pos);
        String coord = x + "," + y + "," + z;
        if (state.isAir()) {
            return TaskResult.fail(InspectStorageText.airAt(coord)).toJson();
        }
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        String caps = Services.CAPS.describe(self.level(), pos);
        if (caps == null || caps.isBlank()) {
            return TaskResult.ok(id + " at " + coord + " exposes no item/fluid/energy storage "
                    + "(not a machine/tank/battery, or it keeps its state elsewhere). "
                    + "If it has a GUI, right-click it then use inspect_gui.").toJson();
        }
        return TaskResult.ok(id + " at " + coord + ":\n" + caps).toJson();
    }
}
