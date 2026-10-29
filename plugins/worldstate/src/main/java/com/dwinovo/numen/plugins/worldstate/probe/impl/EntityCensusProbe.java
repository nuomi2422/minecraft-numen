package com.dwinovo.numen.plugins.worldstate.probe.impl;

import com.dwinovo.numen.plugins.worldstate.probe.ProbeContext;
import com.dwinovo.numen.plugins.worldstate.probe.ProbeResult;
import com.dwinovo.numen.plugins.worldstate.probe.WorldProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 附近实体分类计数。只读、不强制加载区块。
 *
 * <p>覆盖三态与 worldstate 的实体计数一致：全加载 COMPLETE、部分 PARTIAL、
 * 一列都没加载 CHUNK_UNLOADED。分类用 {@link Enemy}/{@link Animal}，
 * 不按实体名猜。
 */
public final class EntityCensusProbe implements WorldProbe {

    private static final int MAX_RADIUS = 64;
    private static final int DEFAULT_RADIUS = 32;

    @Override public String id() { return "entities"; }

    @Override public String title() { return "Nearby entity census"; }

    @Override public String description() {
        return "Count entities around a point by category: hostile (Enemy), passive (Animal), other. "
                + "Optional radius (default 32, max 64), category filter and exact entity type. "
                + "Reports loaded/skipped columns; never forces chunk loading.";
    }

    @Override public List<String> params() {
        return List.of("radius", "category", "type", "x", "y", "z");
    }

    @Override public List<String> returns() {
        return List.of("center", "radius", "total", "hostile", "passive", "other",
                "selected", "matching", "scanned_columns", "skipped_columns");
    }

    @Override
    public ProbeResult collect(ProbeContext ctx) {
        ServerLevel level = ctx.level();
        if (level == null) {
            return ProbeResult.failed("DIMENSION_MISSING", "dimension not loaded: " + ctx.dimension());
        }
        String category = ctx.category() == null || ctx.category().isBlank()
                ? null : ctx.category().trim().toLowerCase(Locale.ROOT);
        if (category != null && !List.of("hostile", "passive", "all").contains(category)) {
            return ProbeResult.failed("INVALID_REQUEST",
                    "category must be hostile|passive|all, got '" + ctx.category() + "'");
        }
        int radius = Math.min(MAX_RADIUS, Math.max(1, ctx.radiusOrDefault(DEFAULT_RADIUS)));
        int cx = ctx.x();
        int cy = ctx.y();
        int cz = ctx.z();
        int minY = Math.max(level.getMinBuildHeight(), cy - radius);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, cy + radius);

        int scanned = 0;
        int skipped = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                cursor.set(x, cy, z);
                if (level.hasChunkAt(cursor)) scanned++;
                else skipped++;
            }
        }
        if (scanned == 0) {
            return ProbeResult.failed("CHUNK_UNLOADED",
                    "no loaded chunk within " + radius + " blocks of " + cx + "," + cy + "," + cz
                            + "; go there first");
        }

        AABB area = new AABB(cx - radius, minY, cz - radius,
                cx + radius + 1.0, maxY + 1.0, cz + radius + 1.0);
        int total = 0, hostile = 0, passive = 0, other = 0, matching = 0;
        String typeFilter = ctx.type() == null || ctx.type().isBlank() ? null
                : ctx.type().trim().toLowerCase(Locale.ROOT);
        for (Entity e : level.getEntities((Entity) null, area, ignored -> true)) {
            total++;
            if (e instanceof Enemy) hostile++;
            else if (e instanceof Animal) passive++;
            else other++;
            if (typeFilter != null) {
                String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
                if (typeFilter.equals(id)) matching++;
            }
        }

        Map<String, Object> center = new LinkedHashMap<>();
        center.put("x", cx);
        center.put("y", cy);
        center.put("z", cz);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("center", center);
        out.put("radius", radius);
        out.put("total", total);
        out.put("hostile", hostile);
        out.put("passive", passive);
        out.put("other", other);
        if (category != null) {
            out.put("category", category);
            out.put("selected", switch (category) {
                case "hostile" -> hostile;
                case "passive" -> passive;
                case "all" -> total;
                default -> null;
            });
        }
        if (typeFilter != null) {
            out.put("entity_type", typeFilter);
            out.put("matching", matching);
        }
        out.put("scanned_columns", scanned);
        out.put("skipped_columns", skipped);
        return skipped == 0 ? ProbeResult.complete(out) : ProbeResult.partial(out);
    }
}
