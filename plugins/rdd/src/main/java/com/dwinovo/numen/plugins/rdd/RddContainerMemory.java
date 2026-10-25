package com.dwinovo.numen.plugins.rdd;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「附近容器里有什么」——把**放进箱子里的东西**重新带回 AI 的上下文。
 *
 * <h2>为什么要有它（2026-10-25 主人当场指出）</h2>
 * AI 把铁锭/备用装备放进箱子后**就忘了**：<br>
 * ① 规划口径 {@code PlanningAssetSnapshot.available} 明确只认"手上持有"（实时背包扫描）；
 * ② 世界资产登记表只记"世界里有什么"，不记"箱子里有几块铁"；
 * ③ settlement 插件能扫容器，但那是**手动一次性快照**，没人把它喂回上下文。<br>
 * 于是"存起来"这个动作对 AI 和规划器**等于东西消失了**，它会重复去挖已经存好的东西。
 *
 * <h2>口径（刻意保守）</h2>
 * <ul>
 *   <li>只读、不搬；**只报数**，不改任何判定；</li>
 *   <li>只在**身边 8 格内**有容器且容器非空时出现，避免每轮全图扫描；</li>
 *   <li>容器区块未加载 → 跳过（不拿空当"没有"）；</li>
 *   <li>每轮最多列 {@link #MAX_TYPES} 种物品，防止上下文被一箱子杂物撑爆。</li>
 * </ul>
 *
 * <p>它回答的是"我刚放进去的东西还在不在"，不是"我有多少资产"——后者仍以背包为准。
 */
final class RddContainerMemory {

    /** 扫描半径（格）。 */
    private static final int RADIUS = 8;
    /** 一次最多看几处容器。 */
    private static final int MAX_CONTAINERS = 3;
    /** 一处容器最多列几种物品。 */
    private static final int MAX_TYPES = 10;

    private RddContainerMemory() {}

    static boolean isContainer(BlockState state) {
        return state.getBlock() instanceof ChestBlock
                || state.getBlock() instanceof BarrelBlock
                || state.getBlock() instanceof ShulkerBoxBlock;
    }

    /** 渲染成一段 &lt;storage&gt; 上下文；没东西可报时返回空串。 */
    static String render(ServerPlayer player) {
        if (player == null) return "";
        if (!(player.level() instanceof ServerLevel level)) return "";
        List<String> lines = new ArrayList<>();
        BlockPos origin = player.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int seen = 0;
        for (int dx = -RADIUS; dx <= RADIUS && seen < MAX_CONTAINERS; dx++) {
            for (int dy = -RADIUS; dy <= RADIUS && seen < MAX_CONTAINERS; dy++) {
                for (int dz = -RADIUS; dz <= RADIUS && seen < MAX_CONTAINERS; dz++) {
                    if (dx * dx + dy * dy + dz * dz > RADIUS * RADIUS) continue;
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!level.hasChunkAt(cursor)) continue;
                    BlockState state = level.getBlockState(cursor);
                    if (!isContainer(state)) continue;
                    BlockEntity be = level.getBlockEntity(cursor);
                    if (!(be instanceof Container container)) continue;
                    Map<String, Integer> counts = new LinkedHashMap<>();
                    int total = 0;
                    for (int i = 0; i < container.getContainerSize(); i++) {
                        ItemStack stack = container.getItem(i);
                        if (stack == null || stack.isEmpty()) continue;
                        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                        counts.merge(id, stack.getCount(), Integer::sum);
                        total += stack.getCount();
                    }
                    if (total <= 0) continue;
                    seen++;
                    StringBuilder sb = new StringBuilder();
                    int listed = 0;
                    for (Map.Entry<String, Integer> e : counts.entrySet()) {
                        if (listed >= MAX_TYPES) { sb.append("…"); break; }
                        if (listed > 0) sb.append('、');
                        sb.append(e.getKey().replace("minecraft:", "")).append('×').append(e.getValue());
                        listed++;
                    }
                    lines.add("(" + cursor.getX() + "," + cursor.getY() + "," + cursor.getZ() + ") " + sb);
                }
            }
        }
        if (lines.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        out.append("<storage nearby=\"").append(lines.size()).append("\">")
                .append("你身边的容器里放着：");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out.append("；");
            out.append(lines.get(i));
        }
        out.append("。这些**不在背包里**，用的是它们要先走过去取；别重复去造/挖已有的存货。</storage>");
        return out.toString();
    }
}
