package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.FunctionalBlockTypes;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.rdd.api.AssetScope;
import com.dwinovo.numen.rdd.api.Observation;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.policy.WorldAssetRetention;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bounded server-thread observer for reusable world assets. It only inspects
 * loaded surroundings and a loaded respawn-base area; it never locates or
 * force-loads distant chunks.
 */
final class RddWorldAssetObserver {
    private static final int MACHINE_RADIUS = 8;
    private static final int MACHINE_Y_RADIUS = 4;
    private static final int MAX_MACHINES_PER_SCAN = 32;
    private static final int MAX_WORLD_ASSETS = 128;
    private static final Set<String> NOTABLE_ENTITIES = Set.of(
            "minecraft:villager", "minecraft:iron_golem", "minecraft:allay",
            "minecraft:horse", "minecraft:donkey", "minecraft:mule",
            "minecraft:cow", "minecraft:sheep", "minecraft:pig", "minecraft:chicken", "minecraft:bee");

    private RddWorldAssetObserver() {}

    static Result observe(NumenPlayer companion, AssetRegistry registry) {
        if (companion == null || registry == null || companion.getServer() == null
                || !companion.getServer().isSameThread()) return new Result(0, 0, 0, 0);
        int bases = observeBase(companion, registry);
        int structures = observeStructures(companion, registry);
        observeVillages(companion, registry);   // P2.3：村庄资源节点（供规划查“这村庄有啥”）
        int machines = observeMachines(companion, registry);
        int entities = observeEntities(companion, registry);
        trim(companion, registry);
        return new Result(bases, structures, machines, entities);
    }

    private static int observeBase(NumenPlayer companion, AssetRegistry registry) {
        RddWorldFacts.BaseSnapshot base = RddWorldFacts.inspectBase(companion, Map.of());
        if (base == null) return 0;
        BlockPos pos = base.spawn();
        Map<String, Object> value = common("base", "个人重生基地", base.dimension(), pos, "LAZY");
        value.put("complete", base.complete());
        value.put("facilities", base.facilities());
        value.put("important_items", topCounts(base.storedItems(), 24));
        value.put("summary", "respawn bed; facilities=" + base.facilities().size()
                + "; stored item types=" + base.storedItems().size()
                + "; completion=" + base.complete());
        apply(companion.getUUID(), registry, "base|" + base.dimension() + "|" + pos.asLong(), "world_base", base.dimension(), value);
        return 1;
    }

    private static int observeStructures(NumenPlayer companion, AssetRegistry registry) {
        if (!(companion.level() instanceof ServerLevel level)) return 0;
        BlockPos pos = companion.blockPosition();
        var structureRegistry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        int count = 0;
        for (Structure structure : level.structureManager().getAllStructuresAt(pos).keySet()) {
            var id = structureRegistry.getKey(structure);
            if (id == null) continue;
            Map<String, Object> value = common("location", id.toString(),
                    level.dimension().location().toString(), pos, "STATIC");
            value.put("structure", id.toString());
            value.put("summary", "companion was physically inside this structure");
            String key = "structure|" + value.get("dimension") + "|" + id + "|" + (pos.getX() >> 4) + "," + (pos.getZ() >> 4);
            apply(companion.getUUID(), registry, key, "world_structure", String.valueOf(value.get("dimension")), value);
            count++;
        }
        return count;
    }

    private static int observeMachines(NumenPlayer companion, AssetRegistry registry) {
        if (!(companion.level() instanceof ServerLevel level)) return 0;
        BlockPos origin = companion.blockPosition();
        String dimension = level.dimension().location().toString();
        int count = 0;
        Set<String> clustered = new java.util.HashSet<>();
        outer:
        for (BlockPos cursor : BlockPos.betweenClosed(origin.offset(-MACHINE_RADIUS, -MACHINE_Y_RADIUS, -MACHINE_RADIUS),
                origin.offset(MACHINE_RADIUS, MACHINE_Y_RADIUS, MACHINE_RADIUS))) {
            var state = level.getBlockState(cursor);
            String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            boolean tracked = FunctionalBlockTypes.isTracked(blockId);
            String capabilities = null;
            if (state.hasBlockEntity()) {
                try {
                    capabilities = Services.CAPS.describe(level, cursor);
                } catch (RuntimeException ignored) {
                    capabilities = null;
                }
            }
            if (!tracked && (capabilities == null || capabilities.isBlank())) continue;
            BlockPos pos = cursor.immutable();
            String type = FunctionalBlockTypes.stationType(blockId);
            boolean portal = type.equals("nether_portal") || type.equals("end_portal") || type.equals("end_portal_frame");
            String cluster = type + "|" + (pos.getX() >> 4) + "," + (pos.getZ() >> 4);
            if (portal && !clustered.add(cluster)) continue;
            Map<String, Object> value = common("machine", blockId, dimension, pos, "LAZY");
            value.put("block", blockId);
            if (capabilities != null && !capabilities.isBlank()) {
                value.put("summary", capabilities.length() > 500 ? capabilities.substring(0, 500) : capabilities);
            }
            String key = portal ? "machine|" + dimension + "|" + cluster
                    : "machine|" + dimension + "|" + blockId + "|" + pos.asLong();
            apply(companion.getUUID(), registry, key, "world_machine", dimension, value);
            if (++count >= MAX_MACHINES_PER_SCAN) break outer;
        }
        return count;
    }

    private static int observeEntities(NumenPlayer companion, AssetRegistry registry) {
        if (!(companion.level() instanceof ServerLevel level)) return 0;
        AABB area = companion.getBoundingBox().inflate(24, 12, 24);
        Map<String, List<Entity>> grouped = new HashMap<>();
        for (Entity entity : level.getEntities(companion, area, entity -> {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            return NOTABLE_ENTITIES.contains(id);
        })) {
            grouped.computeIfAbsent(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), ignored -> new ArrayList<>())
                    .add(entity);
        }
        String dimension = level.dimension().location().toString();
        int count = 0;
        for (var group : grouped.entrySet()) {
            Entity sample = group.getValue().getFirst();
            BlockPos pos = sample.blockPosition();
            Map<String, Object> value = common("entity", group.getKey(), dimension, pos, "LAZY");
            value.put("entity", group.getKey());
            value.put("count", group.getValue().size());
            value.put("summary", "last seen nearby; entities may move or die");
            String key = "entity|" + dimension + "|" + group.getKey() + "|"
                    + (companion.blockPosition().getX() >> 4) + "," + (companion.blockPosition().getZ() >> 4);
            apply(companion.getUUID(), registry, key, "world_entity", dimension, value);
            count++;
        }
        return count;
    }

    private static int observeVillages(NumenPlayer companion, AssetRegistry registry) {
        if (!(companion.level() instanceof ServerLevel level)) return 0;
        BlockPos pos = companion.blockPosition();
        var structureRegistry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        String villageId = null;
        for (Structure structure : level.structureManager().getAllStructuresAt(pos).keySet()) {
            var id = structureRegistry.getKey(structure);
            if (id != null && id.toString().startsWith("minecraft:village")) { villageId = id.toString(); break; }
        }
        if (villageId == null) return 0;
        // 有界资源扫描（半径内，不强制加载远处区块）：村民/铁傀儡（实体）+ 箱子/作物/书架（方块）。
        int villagers = 0, golems = 0;
        AABB area = companion.getBoundingBox().inflate(24, 12, 24);
        for (Entity e : level.getEntities(companion, area, ent -> true)) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            if ("minecraft:villager".equals(id)) villagers++;
            else if ("minecraft:iron_golem".equals(id)) golems++;
        }
        int chests = 0, crops = 0, bookshelves = 0;
        final int R = 16, RY = 6, MAX_STEPS = 40000;
        int steps = 0;
        outer:
        for (BlockPos c : BlockPos.betweenClosed(pos.offset(-R, -RY, -R), pos.offset(R, RY, R))) {
            if (++steps > MAX_STEPS) break outer;
            var b = level.getBlockState(c).getBlock();
            if (b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST || b == Blocks.BARREL) chests++;
            else if (b == Blocks.BOOKSHELF) bookshelves++;
            else if (b == Blocks.WHEAT || b == Blocks.CARROTS || b == Blocks.POTATOES || b == Blocks.BEETROOTS) crops++;
        }
        Map<String, Object> value = common("village", villageId,
                level.dimension().location().toString(), pos, "LAZY");
        value.put("structure", villageId);
        value.put("state", "SCANNED");
        value.put("resources", Map.of("villagers", villagers, "iron_golem", golems,
                "chests", chests, "crops", crops, "bookshelves", bookshelves));
        value.put("summary", "village scanned: villagers=" + villagers + " golems=" + golems
                + " chests=" + chests + " crops=" + crops + " bookshelves=" + bookshelves);
        String key = "village|" + value.get("dimension") + "|" + (pos.getX() >> 4) + "," + (pos.getZ() >> 4);
        apply(companion.getUUID(), registry, key, "world_village", String.valueOf(value.get("dimension")), value);
        return 1;
    }

    private static Map<String, Object> common(String kind, String label, String dimension,
                                               BlockPos pos, String refreshPolicy) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", kind);
        value.put("label", label);
        value.put("dimension", dimension);
        value.put("x", pos.getX());
        value.put("y", pos.getY());
        value.put("z", pos.getZ());
        value.put("refresh_policy", refreshPolicy);
        return value;
    }

    /**
     * 记一条世界观测，**同时**记下"这一拍看见它了"的游戏 tick。
     *
     * <p>为什么 tick 不进 {@link Observation}：那会改落盘形状与所有消费者；
     * 而 TTL 只需要"多久没被重新看见"，侧表就够。重启后侧表为空 → 该资产
     * {@code observed=false} → 本拍不接受淘汰（宽限一整天，见 RetentionPolicy）。
     */
    private static void apply(UUID companionId, AssetRegistry registry, String assetId, String type,
                              String environmentId, Map<String, Object> value) {
        registry.apply(new Observation("obs-world-" + System.nanoTime(), type,
                        "rdd_world_observer", environmentId, System.currentTimeMillis(), value),
                assetId, AssetScope.GLOBAL, null);
        if (companionId != null) {
            SEEN_TICK.computeIfAbsent(companionId, k -> new ConcurrentHashMap<>())
                    .put(assetId, RddInstrumentation.currentGameTimeTicks());
        }
    }

    private static Map<String, Integer> topCounts(Map<String, Integer> counts, int limit) {
        Map<String, Integer> out = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(limit).forEach(entry -> out.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(out);
    }

    /** 每个同伴的"资产 → 上次看见的 tick"。进程内、有界（资产上限 128），重启即空（设计如此）。 */
    private static final Map<UUID, Map<String, Long>> SEEN_TICK = new ConcurrentHashMap<>();

    /**
     * 裁剪：不再"按时间丢最旧"，而是走 {@link WorldAssetRetention} 的受害者顺序 + 实体 TTL。
     *
     * <p>本方法只在服务端线程被 {@link #observe} 调；读的是一份快照，不改世界、不扫区块。
     * 只在真的有动作时发事件（平稳期零噪音）。
     */
    private static void trim(NumenPlayer companion, AssetRegistry registry) {
        UUID companionId = companion.getUUID();
        long now = RddInstrumentation.currentGameTimeTicks();
        Map<String, Long> seen = SEEN_TICK.computeIfAbsent(companionId, k -> new ConcurrentHashMap<>());

        List<WorldAssetRetention.Item> items = new ArrayList<>();
        for (var entry : RddAssetContext.worldAssets(registry)) {
            Long tick = seen.get(entry.assetId());
            items.add(new WorldAssetRetention.Item(entry.assetId(),
                    WorldAssetRetention.kindOf(entry.observation().type()),
                    tick == null ? 0L : tick, tick != null));
        }

        List<WorldAssetRetention.Decision> decisions = WorldAssetRetention.decide(
                items, RddWorldAssetObserver.MAX_WORLD_ASSETS, now,
                WorldAssetRetention.DEFAULT_ENTITY_TTL_TICKS);

        int expired = 0;
        int evicted = 0;
        List<Map<String, Object>> sample = new ArrayList<>();
        for (var d : decisions) {
            if (d.action() == WorldAssetRetention.Action.KEEP) {
                if (!d.reason().equals("within_capacity")) {
                    // 宽限条目：第一次看到时补上 tick，从此开始计龄（不一口气全删）。
                    if (d.reason().equals("age_unknown_grace")) seen.put(d.assetId(), now);
                }
                continue;
            }
            registry.forget(d.assetId());
            seen.remove(d.assetId());
            if (d.action() == WorldAssetRetention.Action.EXPIRE_TTL) expired++;
            else evicted++;
            if (sample.size() < 8) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("assetId", d.assetId());
                one.put("kind", d.kind().name());
                one.put("action", d.action().name());
                one.put("ageTicks", d.ageTicks());
                one.put("reason", d.reason());
                sample.add(one);
            }
        }
        if (expired + evicted == 0) return;
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("companionId", companionId.toString());
        ev.put("expired", expired);
        ev.put("evicted", evicted);
        ev.put("activeAfter", items.size() - expired - evicted);
        ev.put("cap", RddWorldAssetObserver.MAX_WORLD_ASSETS);
        ev.put("entityTtlTicks", WorldAssetRetention.DEFAULT_ENTITY_TTL_TICKS);
        ev.put("policy", "victim_order=entity>machine>village>structure>other; base pinned");
        ev.put("sample", sample);
        RddMonitor.publish("asset_retention", ev);
    }

    record Result(int bases, int structures, int machines, int entityGroups) {
        int total() { return bases + structures + machines + entityGroups; }
    }
}
