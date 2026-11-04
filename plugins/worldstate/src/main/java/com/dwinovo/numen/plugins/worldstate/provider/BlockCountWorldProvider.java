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

    /**
     * 单次请求的最大体积（方块数）。
     *
     * <p>2026-11-05 调高到 262144（64³）：作物扫描这类"球形半径 24"的盒子明明是空心多、
     * 体积却 49³=117649，原来的 32768 会直接拒掉 → 消费者只能回退本地扫描（实机 `sampled=false`）。
     * 读方块状态很便宜（无区块加载），一次几毫秒级，按需调用可以承受。
     */
    static final long MAX_VOLUME = 274625L;   // 65³：与实体采集同一口径

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
        List<String> specs = parseIds(request.filter());
        if (specs.isEmpty()) {
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
        for (String spec : specs) counts.put(spec, 0);
        // 位置样本（039 U5#8 收尾）：每规格最多 SAMPLE_LIMIT 个，供"要去哪一格"用。
        // 只有请求里带了 samples=N 才收集，默认不收集——不把位置当默认输出。
        int samplesPerSpec = Math.max(0, Math.min(32, selectorSamples(request)));
        java.util.Map<String, java.util.List<String>> samples = new LinkedHashMap<>();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    cursor.set(x, y, z);
                    if (!level.hasChunkAt(cursor)) continue;   // 未加载的列不数，覆盖里已记账
                    BlockState state = level.getBlockState(cursor);
                    if (state == null || state.isAir()) continue;
                    String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    for (String spec : specs) {
                        // 同一个方块可能同时命中多条规格（无状态 spec 与前缀），都记上
                        if (matches(state, id, spec)) {
                            counts.merge(spec, 1, Integer::sum);
                            if (samplesPerSpec > 0) {
                                var list = samples.computeIfAbsent(spec, k -> new java.util.ArrayList<>());
                                BlockSpec.insertNearest(list,
                                        spec + "@" + cursor.getX() + "," + cursor.getY() + "," + cursor.getZ(),
                                        samplesPerSpec,
                                        (box.minX() + box.maxX()) / 2,
                                        (box.minY() + box.maxY()) / 2,
                                        (box.minZ() + box.maxZ()) / 2);
                            }
                        }
                    }
                }
            }
        }
        java.util.List<String> flatSamples = new java.util.ArrayList<>();
        for (var e : samples.entrySet()) flatSamples.addAll(e.getValue());
        // 按到盒子中心的距离排序（中心≈请求者位置），让"最近几格"可用；上限仍然是每规格 N 个。
        flatSamples = new java.util.ArrayList<>(BlockSpec.sortByDistance(flatSamples,
                (box.minX() + box.maxX()) / 2, (box.minY() + box.maxY()) / 2, (box.minZ() + box.maxZ()) / 2));
        Coverage coverage = skipped == 0 ? Coverage.COMPLETE : Coverage.PARTIAL;
        BlockCountPayload payload = new BlockCountPayload(request.dimension(), box, specs, counts, scanned, skipped,
                flatSamples);
        return ProviderResult.of(payload, coverage);
    }

    /**
     * 一条规格是否命中这个方块状态。
     *
     * <p>规格形如 `minecraft:wheat` 或 `minecraft:wheat[age=7]`（可带多个属性，逗号分隔）。
     * 属性名/值不认就**不命中**——不猜、不忽略未知属性（否则"按状态过滤"会静默退化成按 id 过滤）。
     */
    static boolean matches(BlockState state, String blockId, String spec) {
        if (state == null || spec == null) return false;
        String[] parts = BlockSpec.split(spec);
        if (parts == null) return false;
        String id = parts[0];
        Map<String, String> props = BlockSpec.parseProps(parts[1]);
        if (props == null) return false;
        if (!id.equals(blockId)) return false;
        if (props.isEmpty()) return true;
        var def = state.getBlock().getStateDefinition();
        for (Map.Entry<String, String> e : props.entrySet()) {
            var prop = def.getProperty(e.getKey());
            if (prop == null) return false;                 // 方块没有这个属性 → 不命中（不忽略）
            String actual = String.valueOf(state.getValue(prop));
            if (!actual.equalsIgnoreCase(e.getValue())) return false;
        }
        return true;
    }

    /**
     * 位置样本上限从哪来：采集器只认 {@link ObservationRequest#target()} 里的
     * `samples=N` 记号（blocks 的 target 目前是 `box=…`）。
     *
     * <p>为什么不用 selector：`ObservationRequest` 没有 selector，只有 target/filter 两个串；
     * 把采样上限拼进 target 是这里能拿到的最少改动。
     */
    private static int selectorSamples(ObservationRequest request) {
        String t = request == null ? "" : request.target();
        if (t == null) return 0;
        int i = t.indexOf("samples=");
        if (i < 0) return 0;
        int end = i + "samples=".length();
        int j = end;
        while (j < t.length() && Character.isDigit(t.charAt(j))) j++;
        return j == end ? 0 : Integer.parseInt(t.substring(end, j));
    }

    private static void addSpec(List<String> out, String raw) {
        String spec = raw == null ? "" : raw.trim();
        if (spec.isEmpty() || out.contains(spec)) return;
        out.add(spec);
    }

    static List<String> parseIds(String filter) {
        return BlockSpec.parseIds(filter);
    }
}
