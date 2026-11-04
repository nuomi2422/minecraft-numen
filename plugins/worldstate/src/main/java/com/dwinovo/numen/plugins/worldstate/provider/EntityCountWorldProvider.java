package com.dwinovo.numen.plugins.worldstate.provider;

import com.dwinovo.numen.plugins.worldstate.McCollectionContext;
import com.dwinovo.numen.worldstate.core.model.Bounds;
import com.dwinovo.numen.worldstate.core.model.Coverage;
import com.dwinovo.numen.worldstate.core.model.FailReason;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.payload.EntityCountPayload;
import com.dwinovo.numen.worldstate.core.provider.CollectionContext;
import com.dwinovo.numen.worldstate.core.provider.ProviderException;
import com.dwinovo.numen.worldstate.core.provider.ProviderResult;
import com.dwinovo.numen.worldstate.core.provider.WorldProvider;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.AABB;

/**
 * 有界实体计数采集器：范围内的动物/实体计数。<b>只读、不强制加载区块</b>。
 *
 * <h2>三种结局必须分清</h2>
 * <ul>
 *   <li>全部列都已加载 → {@link Coverage#COMPLETE}，此时 {@code count=0} 才是"确认没有"。</li>
 *   <li>部分列未加载 → {@link Coverage#PARTIAL}，观察到的是<b>下限</b>，不能当总数。</li>
 *   <li>一列都没加载 → 抛 {@link FailReason#CHUNK_UNLOADED}，明确失败，<b>不拿 0 冒充</b>。</li>
 * </ul>
 *
 * <p>手里已经有羊圈/牛圈，这个采集器直接服务它们，用来验证世界观测架构不是空架子。
 */
public final class EntityCountWorldProvider implements WorldProvider {

    /**
     * 单次请求的最大体积（方块数）。
     *
     * <p>2026-11-05 调高到 65536：实体采集不逐格扫，只做一次 AABB 查询 + 逐列判区块加载，
     * 16 格半径（33³ = 35937）这种"危险扫描"盒子是常用值；原来的 32768 会把 16 格盒直接拒掉
     * （实机表现为 `entities FAILED`，危险块从此再也亮不起来）。
     */
    static final long MAX_VOLUME = 274625L;   // 65³：半径 32 的球盒

    @Override public Topic topic() { return Topic.ENTITIES; }

    @Override public String id() { return "entities"; }

    @Override public int dataVersion() { return 1; }

    @Override public long maxVolume() { return MAX_VOLUME; }

    @Override
    public ProviderResult collect(CollectionContext ctx, ObservationRequest request) {
        McCollectionContext mc = McCollectionContexts.require(ctx);
        Bounds box = request.bounds();
        if (box == null) {
            throw new ProviderException(FailReason.INVALID_REQUEST, "entities request needs bounds");
        }
        ServerLevel level = mc.levelOf(request.dimension());
        if (level == null) {
            throw new ProviderException(FailReason.DIMENSION_MISSING,
                    "dimension not loaded: " + request.dimension());
        }

        // 覆盖估算：逐列看区块加载状态，不触发加载。锚点 y 取盒底即可（列加载与 y 无关）。
        int scanned = 0;
        int skipped = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                cursor.set(x, box.minY(), z);
                if (level.hasChunkAt(cursor)) scanned++;
                else skipped++;
            }
        }
        if (scanned == 0) {
            throw new ProviderException(FailReason.CHUNK_UNLOADED,
                    "no loaded chunk in " + box.canonical() + "; go there first");
        }

        String[] filter = parseFilter(request.filter());
        String entityType = filter[0];
        String category = filter[1];

        AABB area = new AABB(box.minX(), box.minY(), box.minZ(),
                box.maxX() + 1.0, box.maxY() + 1.0, box.maxZ() + 1.0);
        int count = 0;
        java.util.Map<String, Integer> typeCounts = new java.util.LinkedHashMap<>();
        // 123 号：排除多部位实体的部位（同形判断共 4 处，见 count_entities_in_box 注释）。
        for (Entity entity : level.getEntities((Entity) null, area, e -> !(e instanceof net.minecraft.world.entity.boss.EnderDragonPart))) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            if (entityType != null) {
                if (!entityType.equals(id)) continue;
            } else if ("passive".equals(category) && !(entity instanceof Animal)) {
                continue;
            } else if ("hostile".equals(category)
                    && !(entity instanceof net.minecraft.world.entity.monster.Monster)) {
                continue;   // 039 U5#7：怪物类，给"危险实体"用
            }
            count++;
            if (entityType == null) typeCounts.merge(id, 1, Integer::sum);
        }

        Coverage coverage = skipped == 0 ? Coverage.COMPLETE : Coverage.PARTIAL;
        EntityCountPayload payload = new EntityCountPayload(request.dimension(), box, entityType,
                entityType == null ? category : null, count, scanned, skipped, typeCounts);
        return ProviderResult.of(payload, coverage);
    }

    /** 解析 {@code type=...} / {@code category=...}，返回 [entityType, category]。 */
    static String[] parseFilter(String filter) {
        String f = filter == null ? "" : filter.trim();
        if (f.startsWith("type=")) {
            return new String[]{f.substring("type=".length()), null};
        }
        if (f.startsWith("category=")) {
            return new String[]{null, f.substring("category=".length())};
        }
        return new String[]{null, "all"};
    }
}
