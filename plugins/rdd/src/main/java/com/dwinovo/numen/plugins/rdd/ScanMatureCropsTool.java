package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 只读：找附近【成熟】的作物（判断方块 age 是否到顶），最近优先，最多 32 个。
 *
 * <p>种田 AC 的“扫成熟作物”这一步。未成熟的<b>不返回</b>，所以照着它去割永远不会误伤还在长的作物
 * —— 这是现有 {@code scan_blocks}（只按 block id）判不出来的那件事。
 *
 * <p>输出形状对齐 {@code scan_blocks}：裸 JSON {@code {crops:[{x,y,z,block,distance}]}}，
 * 便于 ACX 用 {@code $filter/$pick nearest/$take} 或 {@code for} 直接消费。
 */
final class ScanMatureCropsTool implements NumenTool {

    static final int MAX_RADIUS = 32;
    static final int MAX_RESULTS = 32;

    private static final Gson GSON = new Gson();

    @Override
    public String name() {
        return "scan_mature_crops";
    }

    @Override
    public String description() {
        return "Read-only: find MATURE (fully-grown) crops near you — wheat, carrots, potatoes, beetroots, "
                + "nether_wart, sweet_berry_bush, cocoa — nearest first, up to 32. Only fully-grown crops are "
                + "returned, so harvesting them never destroys a still-growing one. Output "
                + "{crops:[{x,y,z,block,distance}]}. Survey only; to gather, walk there and use mine.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("radius", "Spherical search radius in blocks (1-" + MAX_RADIUS + ").", 1, MAX_RADIUS)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        int radius = 16;
        try {
            if (args != null && args.has("radius") && !args.get("radius").isJsonNull()) {
                radius = args.get("radius").getAsInt();
            }
        } catch (RuntimeException ignore) {
            // keep default
        }
        radius = Math.max(1, Math.min(MAX_RADIUS, radius));

        if (!(self.level() instanceof ServerLevel level)) {
            reply.accept("{\"crops\":[],\"count\":0,\"note\":\"not on a server level\"}");
            return;
        }
        BlockPos center = self.blockPosition();
        // 039 U5#8：先试统一观测（一次 blocks 查询点名 7 种"满熟"规格 + 样本），
        // 拿到坐标后本地算距离/排序；观测不可用再回退本地扫描（工具级兜底，不是第二真源）。
        List<Hit> obsHits = viaObservation(self, center, radius);
        boolean fromObservation = obsHits != null;
        List<Hit> hits = fromObservation ? obsHits : localScan(level, center, radius);
        hits.sort(Comparator.comparingDouble(Hit::distance));
        int limit = Math.min(hits.size(), MAX_RESULTS);
        JsonArray arr = new JsonArray();
        for (int i = 0; i < limit; i++) {
            Hit h = hits.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("x", h.pos().getX());
            o.addProperty("y", h.pos().getY());
            o.addProperty("z", h.pos().getZ());
            o.addProperty("block", h.block());
            if (h.seed() != null) {
                o.addProperty("seed", h.seed());
            }
            o.addProperty("distance", h.distance());
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("crops", arr);
        root.addProperty("count", hits.size());
        root.addProperty("radius_searched", radius);
        root.addProperty("truncated", hits.size() > MAX_RESULTS);
        // 观测路径给的是"每规格最近 N 个"的样本合并，count 是**观测到的**数量而不是全量计数
        root.addProperty("sampled", fromObservation);
        JsonObject c = new JsonObject();
        c.addProperty("x", center.getX());
        c.addProperty("y", center.getY());
        c.addProperty("z", center.getZ());
        root.add("center", c);
        reply.accept(GSON.toJson(root));
    }

    /** 满熟规格清单（每种作物的 age 顶值不同）。 */
    static final String MATURE_SPECS = String.join(",",
            "minecraft:wheat[age=7]",
            "minecraft:carrots[age=7]",
            "minecraft:potatoes[age=7]",
            "minecraft:beetroots[age=3]",
            "minecraft:nether_wart[age=3]",
            "minecraft:sweet_berry_bush[age=3]",
            "minecraft:cocoa[age=2]");

    /**
     * 走观测拿坐标样本；不可用返回 null（调用方回退本地）。
     *
     * <p>一次查询点名全部 7 种规格：采集器按规格分别给「最近 N 个」样本，
     * 合并后再本地按距离排序取前 32——比调 7 次省 7 倍的扫描。
     */
    private static List<Hit> viaObservation(NumenPlayer self, BlockPos center, int radius) {
        try {
            var svc = com.dwinovo.numen.api.NumenPlugins.capability(
                    com.dwinovo.numen.api.observation.WorldObservationService.class);
            if (svc == null) return null;
            String box = (center.getX() - radius) + "," + (center.getY() - radius) + "," + (center.getZ() - radius)
                    + "," + (center.getX() + radius) + "," + (center.getY() + radius) + "," + (center.getZ() + radius);
            String dim = self.level().dimension().location().toString();
            var req = new com.dwinovo.numen.api.observation.WorldObservationService.Request(
                    "rdd-crops", dim, "blocks",
                    java.util.Map.of("box", box, "blocks", MATURE_SPECS, "samples", String.valueOf(MAX_RESULTS)),
                    0L, 64, 0L);
            var resp = svc.read(req);
            if (resp == null || resp.status() == com.dwinovo.numen.api.observation.WorldObservationService.Status.PENDING) {
                if (resp != null) resp = svc.refresh(req);   // 只在"没有观测"时刷一次
            }
            if (resp == null
                    || resp.status() == com.dwinovo.numen.api.observation.WorldObservationService.Status.ERROR
                    || resp.status() == com.dwinovo.numen.api.observation.WorldObservationService.Status.PENDING) {
                return null;
            }
            List<Hit> out = new ArrayList<>();
            for (var item : resp.items()) {
                Object block = item.values().get("block");
                Object samples = item.values().get("samples");
                if (!(block instanceof String spec) || !(samples instanceof List<?> list)) continue;
                String blockId = spec.contains("[") ? spec.substring(0, spec.indexOf('[')) : spec;
                for (Object s : list) {
                    if (!(s instanceof String pos)) continue;
                    String[] xyz = pos.split(",");
                    if (xyz.length != 3) continue;
                    int x, y, z;
                    try {
                        x = Integer.parseInt(xyz[0].trim());
                        y = Integer.parseInt(xyz[1].trim());
                        z = Integer.parseInt(xyz[2].trim());
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    double d = Math.sqrt(center.distSqr(new BlockPos(x, y, z)));
                    if (d > radius) continue;      // 盒是立方、工具要球形：本地按距离过滤
                    out.add(new Hit(new BlockPos(x, y, z), blockId, seedForId(blockId), d));
                }
            }
            return out;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** 本地扫描（观测不可用时的兜底）。 */
    private static List<Hit> localScan(ServerLevel level, BlockPos center, int radius) {
        List<Hit> hits = new ArrayList<>();
        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int planar = dx * dx + dz * dz;
                    if (planar > r2 || planar + dy * dy > r2) continue;
                    cur.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!level.hasChunkAt(cur)) continue;
                    BlockState st = level.getBlockState(cur);
                    if (!isMature(st)) continue;
                    hits.add(new Hit(cur.immutable(),
                            BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString(),
                            seedItemFor(st),
                            Math.sqrt(center.distSqr(cur))));
                }
            }
        }
        return hits;
    }

    /** 按方块 id 反查补种物品（观测路径只有 id，没有 BlockState）。 */
    static String seedForId(String blockId) {
        return switch (blockId) {
            case "minecraft:wheat" -> "minecraft:wheat_seeds";
            case "minecraft:carrots" -> "minecraft:carrot";
            case "minecraft:potatoes" -> "minecraft:potato";
            case "minecraft:beetroots" -> "minecraft:beetroot_seeds";
            case "minecraft:nether_wart" -> "minecraft:nether_wart";
            case "minecraft:sweet_berry_bush" -> "minecraft:sweet_berries";
            case "minecraft:cocoa" -> "minecraft:cocoa_beans";
            default -> null;
        };
    }

    /** 成熟判据：作物方块 age 到顶才算。只认“会再长/割了有产出”的作物。 */
    static boolean isMature(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof CropBlock crop) {
            return crop.isMaxAge(s);
        }
        if (b instanceof NetherWartBlock && s.hasProperty(NetherWartBlock.AGE)) {
            return s.getValue(NetherWartBlock.AGE) >= 3;
        }
        if (b instanceof SweetBerryBushBlock && s.hasProperty(SweetBerryBushBlock.AGE)) {
            return s.getValue(SweetBerryBushBlock.AGE) >= 3;
        }
        if (b instanceof CocoaBlock && s.hasProperty(CocoaBlock.AGE)) {
            return s.getValue(CocoaBlock.AGE) >= 2;
        }
        return false;
    }

    /** 该作物补种回去要用哪个物品（给 interact_at 的 item_id）；认不出返回 null。 */
    static String seedItemFor(BlockState s) {
        Block b = s.getBlock();
        if (b == Blocks.WHEAT) return "minecraft:wheat_seeds";
        if (b == Blocks.CARROTS) return "minecraft:carrot";
        if (b == Blocks.POTATOES) return "minecraft:potato";
        if (b == Blocks.BEETROOTS) return "minecraft:beetroot_seeds";
        if (b == Blocks.NETHER_WART) return "minecraft:nether_wart";
        if (b == Blocks.SWEET_BERRY_BUSH) return "minecraft:sweet_berries";
        if (b == Blocks.COCOA) return "minecraft:cocoa_beans";
        return null;
    }

    private record Hit(BlockPos pos, String block, String seed, double distance) {
    }
}
