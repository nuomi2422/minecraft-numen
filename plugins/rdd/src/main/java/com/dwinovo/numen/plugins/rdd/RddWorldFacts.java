package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.FunctionalBlockTypes;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.core.WorldFactConditions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.StatType;
import net.minecraft.stats.Stats;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/** Read-only, server-thread world evidence. Unknown/unloaded facts never pass. */
public final class RddWorldFacts {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(RddWorldFacts.class);

    /** 锚点判定的垂直容差：建造类目标只在"这一层附近"，不要球半径那么苛刻。 */
    private static final int ANCHOR_DY = 16;
    private RddWorldFacts() {}

    /**
     * 条件里「写了 {@code #tag}、但注册表里没有这个 tag」的字段名。空列表 = 都认得。
     *
     * <p>★ 为什么需要它（2026-10-22 实机，RL-18 同族事故）：形状校验
     * ({@link WorldFactConditions#valid}) 只看 {@code #x:y} 长得像不像，真正查注册表要等到
     * 判定那一刻；标签不存在 ⇒ 空集 ⇒ 恒假。于是「永远判不出来的条件」长成了
     * 「世界还没满足」，任务链静默卡死、重派一百次也一样。把这两个状态分开报出去，
     * 模型才能改条件而不是继续清怪/巡场。
     */
    public static List<String> unresolvedTags(NumenPlayer ap, Map<String, Object> condition) {
        List<String> out = new ArrayList<>();
        if (ap == null || condition == null) return out;
        for (String field : new String[]{"entity", "block", "item", "structure", "biome"}) {
            Object raw = condition.get(field);
            if (!WorldFactConditions.isTag(raw)) continue;
            String id = WorldFactConditions.tagId(raw);
            ResourceLocation key = id == null ? null : ResourceLocation.tryParse(id);
            if (key == null) {
                out.add(field + "=" + raw);
                continue;
            }
            boolean present = switch (field) {
                case "entity" -> BuiltInRegistries.ENTITY_TYPE
                        .getTag(TagKey.create(Registries.ENTITY_TYPE, key)).isPresent();
                case "block" -> BuiltInRegistries.BLOCK
                        .getTag(TagKey.create(Registries.BLOCK, key)).isPresent();
                case "item" -> BuiltInRegistries.ITEM
                        .getTag(TagKey.create(Registries.ITEM, key)).isPresent();
                default -> {
                    var access = ap.getServer() == null ? null : ap.getServer().registryAccess();
                    if (access == null) yield false;
                    // structure / biome 是动态注册表，走 server 的 registryAccess，不能读 BuiltInRegistries。
                    yield "structure".equals(field)
                            ? access.lookupOrThrow(Registries.STRUCTURE)
                            .get(TagKey.create(Registries.STRUCTURE, key)).isPresent()
                            : access.lookupOrThrow(Registries.BIOME)
                            .get(TagKey.create(Registries.BIOME, key)).isPresent();
                }
            };
            if (!present) out.add(field + "=" + raw);
        }
        return out;
    }

    /** Caller must be on the server thread; this method never schedules work or loads chunks. */
    /**
     * 建造类条件（block_nearby / container_nearby）若没有显式 anchor，就套上基地锚点。
     * 没有基地坐标时原样返回（探索/采集类不该被基地绑住）。
     */
    private static Map<String, Object> withBaseAnchor(NumenPlayer ap, Map<String, Object> condition) {
        if (ap == null || condition == null) return condition;
        if (!RddBaseAnchor.needsAnchor(condition)) return condition;
        net.minecraft.core.BlockPos base = RddBaseAnchor.get(ap.getUUID());
        if (base == null) return condition;
        Map<String, Object> copy = new java.util.LinkedHashMap<>(condition);
        copy.put("anchor", java.util.List.of(base.getX(), base.getY(), base.getZ()));
        LOG.info("[rdd] 基地锚点注入 {} -> ({},{},{}) 条件 type={}", ap.getUUID(), base.getX(), base.getY(), base.getZ(), condition.get("type"));
        return copy;
    }

    public static boolean matches(NumenPlayer ap, Map<String, Object> condition) {
        // 2026-10-25（主人在实机上指出）：规划器把"回到基地再办"写在**文字**里，
        // 而条件里没有 anchor ⇒ 判定圆心是"同伴现在站的地方" ⇒ 它就近放下也算完成。
        // 这里给建造类条件补上基地锚点：第一次放下床的位置 = 基地。
        RddCompanionBody.remember(ap);
        condition = withBaseAnchor(ap, condition);
        if (ap == null || condition == null || ap.getServer() == null
                || !ap.getServer().isSameThread()) return false;
        try {
            return switch (String.valueOf(condition.get("type"))) {
                case "advancement" -> advancement(ap, condition);
                case "entity_killed" -> killed(ap, condition);
                case "structure" -> structure(ap, condition);
                case "base" -> base(ap, condition);
                case "biome" -> biome(ap, condition);
                case "block_nearby" -> blockNearby(ap, condition);
                case "container_nearby" -> containerNearby(ap, condition);
                case "block_mined" -> statBlockMined(ap, condition);
                case "item_crafted" -> statItem(ap, Stats.ITEM_CRAFTED, condition);
                case "item_used" -> statItem(ap, Stats.ITEM_USED, condition);
                case "item_picked_up" -> statItem(ap, Stats.ITEM_PICKED_UP, condition);
                case "dimension" -> dimensionReached(ap, condition);
                case "y_below" -> yBelow(ap, condition);
                case "position_at" -> positionAt(ap, condition);
                case "entity_nearby" -> entityNearby(ap, condition);
                case "block_at" -> blockAt(ap, condition);
                case "container_at" -> containerAt(ap, condition);
                default -> false;
            };
        } catch (IllegalArgumentException exception) {
            // Malformed IDs/conditions are unproven, never inventory fallbacks.
            return false;
        }
    }

    private static ResourceLocation id(Map<String, Object> condition, String key) {
        Object raw = condition.get(key);
        if (!(raw instanceof String value) || !value.contains(":")) return null;
        return ResourceLocation.tryParse(value);
    }

    private static boolean dimension(ServerLevel level, Map<String, Object> condition) {
        if (!condition.containsKey("dimension")) return true;
        ResourceLocation wanted = id(condition, "dimension");
        return wanted != null && level.dimension().location().equals(wanted);
    }

    private static boolean advancement(NumenPlayer ap, Map<String, Object> condition) {
        ResourceLocation key = id(condition, "advancement");
        if (key == null) return false;
        var advancement = ap.getServer().getAdvancements().get(key);
        return advancement != null && ap.getAdvancements().getOrStartProgress(advancement).isDone();
    }

    private static boolean killed(NumenPlayer ap, Map<String, Object> condition) {
        ResourceLocation key = id(condition, "entity");
        Object threshold = condition.getOrDefault("minimum", 1);
        if (!(threshold instanceof Number number)) return false;
        double minimum = number.doubleValue();
        if (!Double.isFinite(minimum) || minimum < 1 || minimum > Integer.MAX_VALUE
                || minimum != Math.rint(minimum) || key == null
                || !BuiltInRegistries.ENTITY_TYPE.containsKey(key)) return false;
        // Lifetime kills credited to THIS companion, not disappearance or the owner's stats.
        return ap.getStats().getValue(Stats.ENTITY_KILLED,
                BuiltInRegistries.ENTITY_TYPE.get(key)) >= minimum;
    }

    private static boolean structure(NumenPlayer ap, Map<String, Object> condition) {
        ServerLevel level = ap.serverLevel();
        if (!dimension(level, condition)) return false;
        Object raw = condition.get("structure");
        // 先剥 # 再解析：#minecraft:village 直接喂给 ResourceLocation.tryParse 会炸。
        ResourceLocation key = ResourceLocation.tryParse(WorldFactConditions.tagId(raw));
        if (key == null) return false;
        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        BlockPos pos = ap.blockPosition();
        var here = level.structureManager().getAllStructuresAt(pos);

        // #tag：原版里「一个东西好几个变体」是常态（五个村庄 id、冷/暖两种海底废墟…）。
        // 规划器逐个枚举变体必漏，漏一个就静默判假 → 所以 tag 与精确 id 同等支持。
        if (WorldFactConditions.isTag(raw)) {
            var set = registry.get(TagKey.create(Registries.STRUCTURE, key));
            if (set.isEmpty()) return false;
            Set<Structure> members = new HashSet<>();
            set.get().forEach(holder -> members.add(holder.value()));
            for (Structure found : here.keySet()) {
                if (members.contains(found)) return true;
            }
            return false;
        }

        var holder = registry.get(ResourceKey.create(Registries.STRUCTURE, key));
        if (holder.isEmpty()) return false;
        var wanted = holder.get().value();
        if (here.containsKey(wanted)) return true;
        LevelChunk current = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (current == null) return false;
        if (contains(current.getStartForStructure(wanted), pos)) return true;
        var references = current.getAllReferences().get(wanted);
        if (references == null) return false;
        // StructureManager#getStructureAt may load start chunks. Resolve only already-loaded starts.
        for (long reference : references) {
            LevelChunk origin = level.getChunkSource().getChunkNow(
                    ChunkPos.getX(reference), ChunkPos.getZ(reference));
            if (origin != null && contains(origin.getStartForStructure(wanted), pos)) return true;
        }
        return false;
    }

    /** 站在什么群系里。原版「任何森林/任何海洋/任何山地」都是 biome tag，不是具体 id。 */
    private static boolean biome(NumenPlayer ap, Map<String, Object> condition) {
        ServerLevel level = ap.serverLevel();
        if (!dimension(level, condition)) return false;
        Object raw = condition.get("biome");
        ResourceLocation key = ResourceLocation.tryParse(WorldFactConditions.tagId(raw));
        if (key == null || !dimension(level, condition)) return false;
        Holder<Biome> here = level.getBiome(ap.blockPosition());
        if (WorldFactConditions.isTag(raw)) {
            // 「任何森林/任何海洋/任何山地」在原版是 biome tag，不是具体 id。
            TagKey<Biome> tag = TagKey.create(Registries.BIOME, key);
            var registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
            if (registry.get(tag).isEmpty()) return false;
            return here.is(tag);
        }
        return key.equals(here.unwrapKey().map(k -> k.location()).orElse(null));
    }

    /** 附近有没有某类方块。矿石 / 刷怪笼 / 干草捆 都不带方块实体，观察器看不见它们，这里能。 */
    private static boolean blockNearby(NumenPlayer ap, Map<String, Object> condition) {
        ServerLevel level = ap.serverLevel();
        if (!dimension(level, condition)) return false;
        java.util.List<Predicate<BlockState>> hits = new java.util.ArrayList<>();
        Object list = condition.get("blocks");
        if (list instanceof java.util.List<?> many) {
            for (Object one : many) {
                Predicate<BlockState> single = blockPredicate(one);
                if (single == null) return false;
                hits.add(single);
            }
        } else {
            Predicate<BlockState> single = blockPredicate(condition.get("block"));
            if (single == null) return false;
            hits.add(single);
        }
        Predicate<BlockState> hit = state -> {
            for (Predicate<BlockState> one : hits) {
                if (one.test(state)) return true;
            }
            return false;
        };
        int radius = WorldFactConditions.radiusOf(condition, WorldFactConditions.DEFAULT_BLOCK_RADIUS);
        int need = WorldFactConditions.minimumOf(condition, 1);
        // 2026-10-25 实机：锚点用**球**半径时，同伴在 y 上差 40 格就永远够不到（goto 只在水平面走）。
        // 建造类目标应当是"这块地的这一层附近"——锚点存在时改成**圆柱**：水平用 radius，垂直给 ±ANCHOR_DY。

        // 2026-10-25：锚点可选。建造类目标（插火把/围栏/盖房）必须锚在**那块地**上，
        // 不能锚在会走动的同伴身上 —— 否则人一走，早先插的火把滑出半径，已满足的条件又变假。
        net.minecraft.core.BlockPos origin = ap.blockPosition();
        Object anchor = condition.get("anchor");
        boolean anchored = false;
        if (anchor instanceof java.util.List<?> xyz && xyz.size() == 3) {
            anchored = true;
            try {
                origin = new net.minecraft.core.BlockPos(
                        ((Number) xyz.get(0)).intValue(),
                        ((Number) xyz.get(1)).intValue(),
                        ((Number) xyz.get(2)).intValue());
            } catch (RuntimeException ignored) {
                origin = ap.blockPosition();
            }
        }
        // 2026-10-25：床判定成功 ⇒ 把这张床的位置记成基地（之后所有建造类条件以它为圆心）。
        if (isBedCondition(condition)) {
            net.minecraft.core.BlockPos bed = findNearest(level, origin, radius, hit);
            if (bed != null) {
                RddBaseAnchor.remember(ap.getUUID(), bed);
                LOG.info("[rdd] 基地锚点已记录 {} -> ({},{},{})", ap.getUUID(), bed.getX(), bed.getY(), bed.getZ());
            }
        }
        if (need > 1) {
            if (anchored) {
                return countCylinder(level, origin, radius, ANCHOR_DY, hit, need) >= need;
            }
            return countNeighbourhood(level, origin, radius, hit, need) >= need;
        }
        if (anchored) {
            return countCylinder(level, origin, radius, ANCHOR_DY, hit, 1) >= 1;
        }
        return scanNeighbourhood(level, origin, radius, hit);
    }

    /** 这条条件是不是"找床"（床 = 安家标志，第一次放下就是基地坐标）。 */
    private static boolean isBedCondition(Map<String, Object> condition) {
        Object one = condition.get("block");
        if (one instanceof String id && id.contains("_bed")) return true;
        Object many = condition.get("blocks");
        if (many instanceof java.util.List<?> list) {
            for (Object o : list) if (o instanceof String id && id.contains("_bed")) return true;
        }
        return false;
    }

    /** 半径内最近的一个命中方块（用来把"床"变成坐标）。 */
    private static net.minecraft.core.BlockPos findNearest(ServerLevel level, net.minecraft.core.BlockPos origin,
                                                          int radius, Predicate<BlockState> hit) {
        net.minecraft.core.BlockPos.MutableBlockPos cursor = new net.minecraft.core.BlockPos.MutableBlockPos();
        int squared = radius * radius;
        net.minecraft.core.BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int planar = dx * dx + dz * dz;
                    if (planar > squared || planar + dy * dy > squared) continue;
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!level.hasChunkAt(cursor)) continue;
                    if (!hit.test(level.getBlockState(cursor))) continue;
                    double dist = planar + dy * dy;
                    if (dist < bestDist) { bestDist = dist; best = cursor.immutable(); }
                }
            }
        }
        return best;
    }

    /** 圆柱扫描：水平半径 radius + 垂直 ±dy（锚点建造判定用）。 */
    private static int countCylinder(ServerLevel level, BlockPos origin, int radius, int dy,
                                     Predicate<BlockState> hit, int stopAt) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int squared = radius * radius;
        int found = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > squared) continue;
                for (int ddy = -dy; ddy <= dy; ddy++) {
                    cursor.set(origin.getX() + dx, origin.getY() + ddy, origin.getZ() + dz);
                    if (!level.hasChunkAt(cursor)) continue;
                    if (hit.test(level.getBlockState(cursor))) {
                        found++;
                        if (stopAt > 0 && found >= stopAt) return found;
                    }
                }
            }
        }
        return found;
    }

    /** 单个方块 id / tag → 判定谓词；非法返回 null。 */
    private static Predicate<BlockState> blockPredicate(Object raw) {
        ResourceLocation key = ResourceLocation.tryParse(WorldFactConditions.tagId(raw));
        if (key == null) return null;
        if (WorldFactConditions.isTag(raw)) {
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, key);
            return state -> state.is(tag);
        }
        if (!BuiltInRegistries.BLOCK.containsKey(key)) return null;
        var wanted = BuiltInRegistries.BLOCK.get(key);
        return state -> state.is(wanted);
    }

    /**
     * 附近容器里有没有某物 —— 就是 voyager 的 chest_memory：观测「那边有个箱子，里面有干草块」，
     * 摆到执行 AI 眼前让它自己决定要不要去拿。只读不派工，不改规划。
     */
    private static boolean containerNearby(NumenPlayer ap, Map<String, Object> condition) {
        ServerLevel level = ap.serverLevel();
        if (!dimension(level, condition)) return false;
        Object raw = condition.get("item");
        ResourceLocation key = ResourceLocation.tryParse(WorldFactConditions.tagId(raw));
        if (key == null || !dimension(level, condition)) return false;
        int minimum = WorldFactConditions.minimumOf(condition, 1);
        int radius = WorldFactConditions.radiusOf(condition, WorldFactConditions.DEFAULT_CONTAINER_RADIUS);
        Predicate<ItemStack> hit;
        if (WorldFactConditions.isTag(raw)) {
            TagKey<Item> tag = TagKey.create(Registries.ITEM, key);
            hit = stack -> stack.is(tag);
        } else {
            var wanted = BuiltInRegistries.ITEM.get(key);
            if (wanted == null || !BuiltInRegistries.ITEM.containsKey(key)) return false;
            hit = stack -> stack.is(wanted);
        }
        return countInNeighbourhood(level, ap.blockPosition(), radius, hit, minimum) >= minimum;
    }

    /**
     * 有界邻域扫描：只读已加载 chunk，绝不触发生成。判定每秒跑一次，所以半径被
     * {@link WorldFactConditions#MAX_NEARBY_RADIUS} 硬顶住。
     */
    /**
     * 数一数邻域里有几个匹配方块（2026-10-25）。
     * <p>为什么要有：{@code block_nearby} 原来只判"有没有"，于是"插满 8 支火把"这种
     * 目标写不成判据——插一支就算过。加上计数后，{@code minimum} 才真正有意义。
     */
    private static int countNeighbourhood(ServerLevel level, BlockPos origin, int radius,
                                          Predicate<BlockState> hit, int stopAt) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int squared = radius * radius;
        int found = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int planar = dx * dx + dz * dz;
                    if (planar > squared || planar + dy * dy > squared) continue;
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!level.hasChunkAt(cursor)) continue;
                    if (hit.test(level.getBlockState(cursor))) {
                        found++;
                        if (stopAt > 0 && found >= stopAt) return found;
                    }
                }
            }
        }
        return found;
    }

    private static boolean scanNeighbourhood(ServerLevel level, BlockPos origin, int radius,
                                             Predicate<BlockState> hit) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int squared = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int planar = dx * dx + dz * dz;
                    if (planar > squared || planar + dy * dy > squared) continue;
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!level.hasChunkAt(cursor)) continue;
                    if (hit.test(level.getBlockState(cursor))) return true;
                }
            }
        }
        return false;
    }

    private static int countInNeighbourhood(ServerLevel level, BlockPos origin, int radius,
                                            Predicate<ItemStack> hit, int stopAt) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int squared = radius * radius;
        int total = 0;
        for (int dx = -radius; dx <= radius && total < stopAt; dx++) {
            for (int dy = -radius; dy <= radius && total < stopAt; dy++) {
                for (int dz = -radius; dz <= radius && total < stopAt; dz++) {
                    int planar = dx * dx + dz * dz;
                    if (planar > squared || planar + dy * dy > squared) continue;
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!level.hasChunkAt(cursor)) continue;
                    if (!(level.getBlockEntity(cursor) instanceof Container container)) continue;
                    for (int slot = 0; slot < container.getContainerSize() && total < stopAt; slot++) {
                        ItemStack stack = container.getItem(slot);
                        if (stack.isEmpty()) continue;
                        if (hit.test(stack)) total += stack.getCount();
                    }
                }
            }
        }
        return total;
    }

    // ---- A 组：原版 Stats（判“真的做了 N 次”，不是“此刻背包里有”） ----

    private static boolean statBlockMined(NumenPlayer ap, Map<String, Object> condition) {
        ServerLevel level = ap.serverLevel();
        if (!dimension(level, condition)) return false;
        Object raw = condition.get("block");
        ResourceLocation key = ResourceLocation.tryParse(WorldFactConditions.tagId(raw));
        if (key == null) return false;
        int minimum = WorldFactConditions.minimumOf(condition, 1);
        return sumStat(ap, Stats.BLOCK_MINED, BuiltInRegistries.BLOCK, key,
                WorldFactConditions.isTag(raw)) >= minimum;
    }

    private static boolean statItem(NumenPlayer ap, StatType<Item> stat, Map<String, Object> condition) {
        ServerLevel level = ap.serverLevel();
        if (!dimension(level, condition)) return false;
        Object raw = condition.get("item");
        ResourceLocation key = ResourceLocation.tryParse(WorldFactConditions.tagId(raw));
        if (key == null) return false;
        int minimum = WorldFactConditions.minimumOf(condition, 1);
        return sumStat(ap, stat, BuiltInRegistries.ITEM, key,
                WorldFactConditions.isTag(raw)) >= minimum;
    }

    /** 汇总某注册表对象（或整个 #tag 成员）在该统计类型上的终身值。 */
    private static <T> int sumStat(NumenPlayer ap, StatType<T> stat, Registry<T> registry,
                                   ResourceLocation key, boolean isTag) {
        int total = 0;
        if (isTag) {
            var members = registry.getTag(TagKey.create(registry.key(), key));
            if (members.isEmpty()) return 0;
            for (Holder<T> holder : members.get()) {
                total += statValue(ap, stat, holder.value());
            }
        } else {
            T value = registry.get(key);
            if (value == null || !registry.containsKey(key)) return 0;
            total += statValue(ap, stat, value);
        }
        return total;
    }

    private static <T> int statValue(NumenPlayer ap, StatType<T> stat, T key) {
        try {
            return ap.getStats().getValue(stat, key);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    // ---- B 组：位置 / 维度 ----

    private static boolean dimensionReached(NumenPlayer ap, Map<String, Object> condition) {
        ResourceLocation wanted = id(condition, "dimension");
        return wanted != null && ap.serverLevel().dimension().location().equals(wanted);
    }

    private static boolean yBelow(NumenPlayer ap, Map<String, Object> condition) {
        ServerLevel level = ap.serverLevel();
        if (!dimension(level, condition)) return false;
        Object raw = condition.get("y");
        if (!(raw instanceof Number number)) return false;
        return ap.getY() <= number.doubleValue();
    }

    private static boolean positionAt(NumenPlayer ap, Map<String, Object> condition) {
        ServerLevel level = ap.serverLevel();
        if (!dimension(level, condition)) return false;
        BlockPos pos = coordOf(condition);
        if (pos == null) return false;
        int radius = WorldFactConditions.radiusOf(condition, WorldFactConditions.DEFAULT_POSITION_RADIUS);
        BlockPos me = ap.blockPosition();
        long dx = (long) me.getX() - pos.getX();
        long dy = (long) me.getY() - pos.getY();
        long dz = (long) me.getZ() - pos.getZ();
        return dx * dx + dy * dy + dz * dz <= (long) radius * radius;
    }

    private static BlockPos coordOf(Map<String, Object> condition) {
        Object x = condition.get("x");
        Object y = condition.get("y");
        Object z = condition.get("z");
        if (x instanceof Number nx && y instanceof Number ny && z instanceof Number nz
                && nx.doubleValue() == nx.intValue() && ny.doubleValue() == ny.intValue()
                && nz.doubleValue() == nz.intValue()) {
            return new BlockPos(nx.intValue(), ny.intValue(), nz.intValue());
        }
        return null;
    }

    // ---- C 组：实体 / 精确坐标 ----

    private static boolean entityNearby(NumenPlayer ap, Map<String, Object> condition) {
        ServerLevel level = ap.serverLevel();
        if (!dimension(level, condition)) return false;
        Object raw = condition.get("entity");
        ResourceLocation key = ResourceLocation.tryParse(WorldFactConditions.tagId(raw));
        if (key == null) return false;
        int radius = WorldFactConditions.radiusOf(condition, WorldFactConditions.DEFAULT_BLOCK_RADIUS);
        boolean isTag = WorldFactConditions.isTag(raw);
        Set<EntityType<?>> tagMembers = null;
        EntityType<?> exact = null;
        if (isTag) {
            var set = BuiltInRegistries.ENTITY_TYPE.getTag(TagKey.create(Registries.ENTITY_TYPE, key));
            if (set.isEmpty()) return false;
            tagMembers = new HashSet<>();
            for (Holder<EntityType<?>> holder : set.get()) tagMembers.add(holder.value());
        } else {
            exact = BuiltInRegistries.ENTITY_TYPE.get(key);
            if (exact == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(key)) return false;
        }
        final Set<EntityType<?>> members = tagMembers;
        final EntityType<?> exactType = exact;
        // 2026-10-25：和 block_nearby 对齐 —— 支持固定锚点 + 数量（交易站要判"这块地附近有村民"）。
        net.minecraft.world.phys.Vec3 center = ap.position();
        Object anchor = condition.get("anchor");
        if (anchor instanceof java.util.List<?> xyz && xyz.size() == 3) {
            try {
                center = new net.minecraft.world.phys.Vec3(
                        ((Number) xyz.get(0)).doubleValue(),
                        ((Number) xyz.get(1)).doubleValue(),
                        ((Number) xyz.get(2)).doubleValue());
            } catch (RuntimeException ignored) {
                center = ap.position();
            }
        }
        int need = WorldFactConditions.minimumOf(condition, 1);
        var box = new net.minecraft.world.phys.AABB(center.x - radius, center.y - radius, center.z - radius,
                center.x + radius, center.y + radius, center.z + radius);
        int found = 0;
        for (Entity entity : level.getEntities(ap, box, e -> e != ap && e.isAlive())) {
            if (isTag ? members.contains(entity.getType()) : entity.getType() == exactType) {
                found++;
                if (found >= need) return true;
            }
        }
        return false;
    }

    private static boolean blockAt(NumenPlayer ap, Map<String, Object> condition) {
        ServerLevel level = ap.serverLevel();
        if (!dimension(level, condition)) return false;
        BlockPos pos = coordOf(condition);
        if (pos == null || !level.hasChunkAt(pos)) return false;
        Object raw = condition.get("block");
        ResourceLocation key = ResourceLocation.tryParse(WorldFactConditions.tagId(raw));
        if (key == null) return false;
        BlockState state = level.getBlockState(pos);
        if (WorldFactConditions.isTag(raw)) {
            return state.is(TagKey.create(Registries.BLOCK, key));
        }
        Block wanted = BuiltInRegistries.BLOCK.get(key);
        return wanted != null && BuiltInRegistries.BLOCK.containsKey(key) && state.is(wanted);
    }

    private static boolean containerAt(NumenPlayer ap, Map<String, Object> condition) {
        ServerLevel level = ap.serverLevel();
        if (!dimension(level, condition)) return false;
        BlockPos pos = coordOf(condition);
        if (pos == null || !level.hasChunkAt(pos)) return false;
        if (!(level.getBlockEntity(pos) instanceof Container container)) return false;
        Object raw = condition.get("item");
        ResourceLocation key = ResourceLocation.tryParse(WorldFactConditions.tagId(raw));
        if (key == null) return false;
        Predicate<ItemStack> hit = itemHit(raw, key);
        if (hit == null) return false;
        int minimum = WorldFactConditions.minimumOf(condition, 1);
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize() && total < minimum; slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && hit.test(stack)) total += stack.getCount();
        }
        return total >= minimum;
    }

    /** 物品匹配谓词（精确 id 或 #tag）；非法返回 null。 */
    private static Predicate<ItemStack> itemHit(Object raw, ResourceLocation key) {
        if (WorldFactConditions.isTag(raw)) {
            TagKey<Item> tag = TagKey.create(Registries.ITEM, key);
            return stack -> stack.is(tag);
        }
        Item wanted = BuiltInRegistries.ITEM.get(key);
        if (wanted == null || !BuiltInRegistries.ITEM.containsKey(key)) return null;
        return stack -> stack.is(wanted);
    }

    private static boolean contains(StructureStart start, BlockPos pos) {
        return start != null && start.isValid() && start.getBoundingBox().isInside(pos);
    }

    private static boolean base(NumenPlayer ap, Map<String, Object> condition) {
        BaseSnapshot snapshot = inspectBase(ap, condition);
        return snapshot != null && snapshot.complete();
    }

    /** A valid personal respawn bed is a reusable base asset even before every facility is complete. */
    private static final org.slf4j.Logger BASELOG =
            org.slf4j.LoggerFactory.getLogger("rdd-base");

    static BaseSnapshot inspectBase(NumenPlayer ap, Map<String, Object> condition) {
        BlockPos spawn = ap.getRespawnPosition();
        BASELOG.info("[rdd-base] 检查：spawn={} dim={} 当前维度={}", spawn,
                ap.getRespawnDimension(), ap.level().dimension().location());
        // 未绑床时 getRespawnPosition() 可能返回 BlockPos.ZERO（哨兵）而非 null——
        // 必须显式挡掉，否则会拿 (0,0,0) 当"重生床"去查（语义错，且恰好在原点有床时会误判成功）。
        if (spawn == null || spawn.equals(BlockPos.ZERO)) {
            BASELOG.info("[rdd-base] 失败：未绑床（spawn=null/ZERO）");
            return null;
        }
        ServerLevel level = ap.getServer().getLevel(ap.getRespawnDimension());
        if (level == null || !dimension(level, condition) || !BedBlock.canSetSpawn(level)) {
            BASELOG.info("[rdd-base] 失败：level={} dimensionOk={} canSetSpawn={}", level,
                    level != null && dimension(level, condition), level != null && BedBlock.canSetSpawn(level));
            return null;
        }
        // A tiny bounded area, preflighted before any block/collision query. No chunk generation.
        for (int x = (spawn.getX() - 8) >> 4; x <= (spawn.getX() + 8) >> 4; x++) {
            for (int z = (spawn.getZ() - 8) >> 4; z <= (spawn.getZ() + 8) >> 4; z++) {
                if (level.getChunkSource().getChunkNow(x, z) == null) return null;
            }
        }
        var bed = level.getBlockState(spawn);
        if (!(bed.getBlock() instanceof BedBlock)) {
            BASELOG.info("[rdd-base] 失败：spawn 处不是床，实际={}", bed.getBlock());
            return null;
        }
        var facing = bed.getValue(BedBlock.FACING);
        BlockPos otherPos = spawn.relative(bed.getValue(BedBlock.PART) == BedPart.HEAD
                ? facing.getOpposite() : facing);
        var other = level.getBlockState(otherPos);
        if (!other.is(bed.getBlock()) || other.getValue(BedBlock.PART) == bed.getValue(BedBlock.PART)
                || other.getValue(BedBlock.FACING) != facing) {
            return null;
        }
        // 2026-10-25（主人）：**点一下床就算安家**。这里原来还要求
        //   BedBlock.findStandUpPosition(...).isEmpty() == false（床旁边得有地方站起来），
        //   斜坡/草丛边上经常判空 ⇒ 重生点明明绑好了，`base` 事实却是 false，
        //   于是 AI 以为自己没有基地、反复找地方重绑。站不站得起来只影响"这一觉"，不影响"家在哪"。
        boolean chest = false;
        boolean furnace = false;
        List<String> facilities = new ArrayList<>();
        Map<String, Integer> storedItems = new TreeMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(spawn.offset(-8, -4, -8), spawn.offset(8, 4, 8))) {
            var block = level.getBlockState(pos);
            chest |= block.is(Blocks.CHEST) || block.is(Blocks.TRAPPED_CHEST);
            furnace |= block.is(Blocks.FURNACE);
            String blockId = BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString();
            if (FunctionalBlockTypes.isTracked(blockId)) {
                facilities.add(blockId + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ());
            }
            if (level.getBlockEntity(pos) instanceof Container container) {
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    ItemStack stack = container.getItem(slot);
                    if (!stack.isEmpty()) {
                        storedItems.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                                stack.getCount(), Integer::sum);
                    }
                }
            }
        }
        // 2026-10-01 修（实机抓到的真死锁，用户第 5 轮实机观察）：
        // 原 complete = chest && furnace（床周围 8 格内必须同时有箱子+熔炉）。
        // 实机后果：规划器把 type=base 排成**第一个**二级 → 判据要求"基地已建成"
        // → 但造箱子/熔炉的二级排在它**后面** → base 永远不满足 → 第一个二级永远 STALLED
        // → 后面 5 个二级永远 PENDING → 整条链停摆（RL-2「依赖门不得死锁」的另一种形态：
        // 不是 WAITING 卡住，是**第一个目标自身不可达**）。
        //
        // 改法：让 complete 与本方法自己的注释一致 —— **一张已验证可用的重生床就是基地**
        //（上面已经把床本身完整校验过：双格齐全、朝向一致、旁边站得下人）。
        // 箱子/熔炉不再当硬门，但**继续扫、继续报**：缺了就写进 facilities 说明，
        // 让监测台能看出"基地还很空"，而不是只看到一个布尔值。
        //
        // 治本项另记（A2）：规划器不该把 base 排成第一个二级 —— 那是排期约束问题，
        // 要改规划器，下一批做。
        if (!chest || !furnace) {
            StringBuilder missing = new StringBuilder();
            if (!chest) missing.append("no chest within 8 blocks of the respawn bed");
            if (!furnace) {
                if (missing.length() > 0) missing.append("; ");
                missing.append("no furnace within 8 blocks of the respawn bed");
            }
            facilities.add("(missing) " + missing);
        }
        boolean complete = true;
        return new BaseSnapshot(level.dimension().location().toString(), spawn.immutable(),
                complete, List.copyOf(facilities), Map.copyOf(storedItems));
    }

    record BaseSnapshot(String dimension, BlockPos spawn, boolean complete,
                        List<String> facilities, Map<String, Integer> storedItems) {}
}
