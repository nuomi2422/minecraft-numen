package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「附近容器里有什么」——把**放进箱子里的东西**重新带回 AI 的上下文。
 *
 * <h2>为什么要有它（2026-10-25 主人当场指出）</h2>
 * AI 把铁锭/备用装备放进箱子后**就忘了**：<br>
 * ① 规划口径 {@code PlanningAssetSnapshot.available} 明确只认"手上持有"；<br>
 * ② 世界资产登记表不记"箱子里有几块铁"；<br>
 * ③ settlement 能扫容器，但那是手动一次性快照，没人喂回上下文。<br>
 * 于是"存起来"这个动作对 AI 和规划器**等于东西消失了**。
 *
 * <h2>怎么读（这条是踩坑换来的）</h2>
 * 自己写 {@code level.getBlockEntity(pos)} 在这台实机上**恒为 null**（同坐标 {@code Services.CAPS}
 * 直接 NPE），而工具 {@code inspect_block_storage} 却能读出来。所以这里**照抄工具那条路**
 * （{@code QueryExtraOps.inspectBlockStorage}），再解析它返回的文本。
 * 宁可解析一段文本，也不自己重写世界读取——那个方向已被实机证伪。
 *
 * <p>契约：只读、只报数、不改任何判定；只在身边 8 格内、容器非空时出现。
 */
final class RddContainerMemory {

    /** 扫描半径（格）。 */
    private static final int RADIUS = 8;
    /** 一次最多看几处容器。 */
    private static final int MAX_CONTAINERS = 3;
    /** 一处容器最多列几种物品。 */
    private static final int MAX_TYPES = 10;
    /** 工具返回行形如 "  slot 0: minecraft:iron_ingot x9"。 */
    private static final Pattern SLOT_LINE =
            Pattern.compile("([a-z0-9_.-]+:[a-z0-9_./-]+)\\s*x\\s*(\\d+)");

    private RddContainerMemory() {}

    /** 渲染成一段 &lt;storage&gt; 上下文；没东西可报时返回空串。 */
    static String render(ServerPlayer player) {
        if (!(player instanceof NumenPlayer body)) return "";
        ServerLevel level;
        try {
            level = body.serverLevel();
        } catch (RuntimeException ex) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        BlockPos origin = body.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -RADIUS; dx <= RADIUS && lines.size() < MAX_CONTAINERS; dx++) {
            for (int dy = -RADIUS; dy <= RADIUS && lines.size() < MAX_CONTAINERS; dy++) {
                for (int dz = -RADIUS; dz <= RADIUS && lines.size() < MAX_CONTAINERS; dz++) {
                    if (dx * dx + dy * dy + dz * dz > RADIUS * RADIUS) continue;
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!level.hasChunkAt(cursor)) continue;
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) continue;
                    if (!state.getBlock().getDescriptionId().contains("chest")) continue;
                    String dump;
                    try {
                        dump = new com.dwinovo.numen.core.tools.QueryExtraOps()
                                .inspectBlockStorage(cursor.getX(), cursor.getY(), cursor.getZ(), body);
                    } catch (RuntimeException ex) {
                        continue;
                    }
                    Map<String, Integer> counts = parseSlotLines(dump);
                    if (counts.isEmpty()) continue;
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
        StringBuilder out = new StringBuilder("<storage nearby=\"").append(lines.size()).append("\">")
                .append("你身边的容器里放着：");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out.append("；");
            out.append(lines.get(i));
        }
        out.append("。这些**不在背包里**，要用得先走过去取；别重复去造/挖已有的存货。</storage>");
        return out.toString();
    }

    /** 从工具文本里抽出「物品 id × 数量」，按 id 汇总（同一物品可能占多个 slot）。 */
    static Map<String, Integer> parseSlotLines(String dump) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (dump == null || dump.isBlank()) return counts;
        Matcher m = SLOT_LINE.matcher(dump);
        while (m.find()) {
            int n;
            try {
                n = Integer.parseInt(m.group(2));
            } catch (NumberFormatException ex) {
                continue;
            }
            if (n <= 0) continue;
            counts.merge(m.group(1), n, Integer::sum);
        }
        return counts;
    }
}
