package com.dwinovo.numen.plugins.worldstate.provider;

import com.dwinovo.numen.plugins.worldstate.McCollectionContext;
import com.dwinovo.numen.worldstate.core.model.Bounds;
import com.dwinovo.numen.worldstate.core.model.Coverage;
import com.dwinovo.numen.worldstate.core.model.FailReason;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.payload.BlockCountPayload;
import com.dwinovo.numen.worldstate.core.provider.CollectionContext;
import com.dwinovo.numen.worldstate.core.provider.ProviderException;
import com.dwinovo.numen.worldstate.core.provider.ProviderResult;
import com.dwinovo.numen.worldstate.core.provider.WorldProvider;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 有界方块计数采集器（039 U2b）：点名若干方块 id，数范围内各有多少个。
 *
 * <p>覆盖口径与 {@link EntityCountWorldProvider} 一致：逐列看区块是否已加载，<b>不触发加载</b>；
 * 一列都没加载就明确失败，绝不用 0 冒充。
 *
 * <p>过滤串格式：{@code id=minecraft:chest,minecraft:lava}（逗号分隔，去重保序）。
 */
public final class BlockCountWorldProvider implements WorldProvider {

    /** 单次请求的最大体积（方块数）。 */
    private static final long MAX_VOLUME = 32768L;

    @Override public Topic topic() { return Topic.BLOCKS; }

    @Override public String id() { return "blocks"; }

    @Override public int dataVersion() { return 1; }

    @Override public long maxVolume() { return MAX_VOLUME; }

    @Override
    public ProviderResult collect(CollectionContext ctx, ObservationRequest request) {
        McCollectionContext mc = McCollectionContexts.require(ctx);
        Bounds box = request.bounds();
        if (box == null) {
            throw new ProviderException(FailReason.INVALID_REQUEST, "blocks request needs bounds");
        }
        ServerLevel level = mc.levelOf(request.dimension());
        if (level == null) {
            throw new ProviderException(FailReason.DIMENSION_MISSING,
                    "dimension not loaded: " + request.dimension());
        }
        List<String> ids = parseIds(request.filter());
        if (ids.isEmpty()) {
            throw new ProviderException(FailReason.INVALID_REQUEST, "blocks request needs id=… filter");
        }

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

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String id : ids) counts.put(id, 0);
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    cursor.set(x, y, z);
                    if (!level.hasChunkAt(cursor)) continue;   // 未加载的列不数，覆盖里已记账
                    BlockState state = level.getBlockState(cursor);
                    if (state == null || state.isAir()) continue;
                    String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    Integer prev = counts.get(id);
                    if (prev != null) counts.put(id, prev + 1);
                }
            }
        }
        Coverage coverage = skipped == 0 ? Coverage.COMPLETE : Coverage.PARTIAL;
        BlockCountPayload payload = new BlockCountPayload(request.dimension(), box, ids, counts, scanned, skipped);
        return ProviderResult.of(payload, coverage);
    }

    static List<String> parseIds(String filter) {
        List<String> out = new ArrayList<>();
        if (filter == null || filter.isBlank()) return out;
        String body = filter.trim();
        int eq = body.indexOf('=');
        if (eq < 0) return out;
        // 与 ObservationRequest.blocks 的规范串保持一致（它发 block=），同时容忍 id= 的写法。
        String key = body.substring(0, eq).trim();
        if (!"id".equals(key) && !"block".equals(key)) return out;
        for (String raw : body.substring(eq + 1).split(",")) {
            String id = raw.trim();
            if (id.isEmpty() || out.contains(id)) continue;
            out.add(id);
        }
        return out;
    }
}
