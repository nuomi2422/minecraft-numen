package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 播种：对**空地耕地**按手里有的种子补种（2026-11-06，指挥官接手）。
 *
 * <p>为什么必须有它（实测 2026-11-06）：基地 18 块耕地只长了 2 株，她背包有 20 颗种子却种不下去——
 * {@code farm_nearby}/{@code farm_cell} 的语义是「只碰成熟作物：收产物 + age 复位，不耗种子」，
 * 空格与嫩苗不动；全仓没有任何播种动作，她只能拿 {@code interact_at} 硬点，回执全是
 * 「aim … is blocked from here」。这不是她懒，是能力缺口。
 *
 * <p>与 farm_nearby 分工：它收割，本工具补种；两者都是纯坐标世界操作，不逐株寻路。
 * 不碰成熟作物、不碰非耕地、不拆块；没有可用种子就如实报 0，不凭空造。
 */
final class SowNearbyTool implements NumenTool {

    static final int MAX_RADIUS = 32;

    @Override
    public String name() {
        return "sow_nearby";
    }

    @Override
    public String description() {
        return "Sow seeds onto EMPTY farmland near you in one call (fills bare cells; mature crops are untouched). "
                + "Uses whichever seed you carry (wheat_seeds / carrot / potato / beetroot_seeds), one per cell, "
                + "and reports {sown, seeds_left, empty_cells}. Run it after harvesting to keep the field producing.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("radius", "Horizontal radius of the patch (1-" + MAX_RADIUS + ").", 1, MAX_RADIUS)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        try {
            int radius = 12;
            if (args != null && args.has("radius") && !args.get("radius").isJsonNull()) {
                radius = args.get("radius").getAsInt();
            }
            radius = Math.max(1, Math.min(MAX_RADIUS, radius));
            if (!(self.level() instanceof ServerLevel level)) {
                reply.accept(TaskResult.fail("sow_nearby: not on a server level").toJson());
                return;
            }
            String seedId = SowRules.pickSeedId(seedCounts(self));
            Item seedItem = seedId == null ? null : BuiltInRegistries.ITEM.get(ResourceLocation.parse(seedId));
            String cropId = SowRules.cropBlockIdForSeed(seedId);
            Block cropBlock = cropId == null ? null : BuiltInRegistries.BLOCK.get(ResourceLocation.parse(cropId));

            BlockPos center = self.blockPosition();
            int sown = 0;
            int empty = 0;
            BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz > radius * radius) continue;
                    for (int dy = -4; dy <= 4; dy++) {
                        cur.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                        if (!level.hasChunkAt(cur)) continue;
                        if (!level.getBlockState(cur).is(Blocks.FARMLAND)) continue;
                        BlockPos above = cur.above();
                        if (!level.hasChunkAt(above)) continue;
                        if (!level.getBlockState(above).isAir()) continue;   // 已有作物/嫩苗：不动
                        empty++;
                        if (seedItem == null || cropBlock == null) continue;
                        if (!consumeOne(self, seedItem)) continue;
                        BlockState crop = cropBlock.defaultBlockState();
                        level.setBlock(above, crop, Block.UPDATE_ALL);
                        sown++;
                    }
                }
            }
            int left = totalSeeds(self);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sown", sown);
            data.put("seeds_left", left);
            data.put("empty_cells", empty);
            data.put("radius", radius);
            data.put("seed", seedId == null ? "" : seedId);
            String msg;
            if (sown > 0) {
                msg = "sown " + sown + " cell(s) with " + seedId + ", seeds_left=" + left;
            } else if (empty == 0) {
                msg = "no empty farmland in radius " + radius;
            } else {
                msg = "no usable seed in inventory (empty farmland=" + empty + "), seeds_left=" + left;
            }
            reply.accept(TaskResult.ok(msg, data).toJson());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("sow_nearby error: " + ex.getMessage()).toJson());
        }
    }

    /** 背包里各类种子的数量（只统计 SowRules 认可的四种）。 */
    static Map<String, Integer> seedCounts(NumenPlayer self) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < self.getInventory().getContainerSize(); i++) {
            ItemStack st = self.getInventory().getItem(i);
            if (st.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
            if (SowRules.SEED_PRIORITY.contains(id)) {
                counts.merge(id, st.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    static int totalSeeds(NumenPlayer self) {
        int n = 0;
        for (Integer v : seedCounts(self).values()) n += v;
        return n;
    }

    /** 从背包扣 1 个；扣不动返回 false（不凭空造作物）。 */
    static boolean consumeOne(NumenPlayer self, Item item) {
        for (int i = 0; i < self.getInventory().getContainerSize(); i++) {
            ItemStack st = self.getInventory().getItem(i);
            if (st.isEmpty() || !st.is(item)) continue;
            st.shrink(1);
            return true;
        }
        return false;
    }
}
