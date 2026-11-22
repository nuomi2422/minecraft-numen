package com.dwinovo.numen.plugins.rdd;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;

import java.util.Map;

/**
 * 规划 AI 的「现状事实包」：**我拥有什么 / 我在哪 / 世界里有什么**。
 *
 * <h2>为什么要有它（2026-10-25 主人指出）</h2>
 * 2026-10-07 为了省 token，规划提示词里**停掉了世界资产块与村庄事实**，只留「目标 + 手上持有」。
 * 后果是规划师三样全瞎：
 * <ol>
 *   <li><b>已拥有的东西不全面</b>——手上只算背包，存进箱子的等于消失；</li>
 *   <li><b>所在地区不知道</b>——不知道自己在地下还是在村里、在哪个生物群系；</li>
 *   <li><b>世界里的东西不知道</b>——自己建过什么、附近有没有村庄，全无。</li>
 * </ol>
 * 于是它会规划出「去挖铁」而箱子里已有 20 个铁锭，或者「去找村庄」而人就在村里。
 *
 * <h2>口径</h2>
 * <ul>
 *   <li>**只读事实，不给策略**（策略归经验库/风险规划，这层不抢戏）；</li>
 *   <li>**紧凑**：整块 ≤ {@link #MAX_CHARS} 字符，超了就截断并标注；</li>
 *   <li>容器只扫**身边 8 格**（与执行侧 {@link RddContainerMemory} 同一口径）；</li>
 *   <li>读不到就写「未观测」，**不拿空当没有**。</li>
 * </ul>
 */
final class RddPlannerFacts {

    /** 整块上限：够规划师看清三样事实，又不至于把 token 烧在清单上。 */
    static final int MAX_CHARS = 420;

    private RddPlannerFacts() {}

    /** 组装事实包；comapnion 不在线时返回带「未观测」的短块（不抛、不空）。 */
    static String block(ServerPlayer player, java.util.UUID companionId) {
        return block(player, companionId, null);
    }
    static String block(ServerPlayer player, java.util.UUID companionId, String objective) {
        StringBuilder sb = new StringBuilder();
        sb.append("<situation>\n");

        // ① 我在哪
        if (player == null) {
            sb.append("位置：未观测（同伴不在线）\n");
        } else {
            BlockPos pos = player.blockPosition();
            String dim = player.level().dimension().location().toString().replace("minecraft:", "");
            String biome = "?";
            try {
                if (player.level() instanceof ServerLevel level) {
                    var holder = level.getBiome(pos);
                    biome = holder.unwrapKey().map(k -> k.location().toString().replace("minecraft:", ""))
                            .orElse("?");
                }
            } catch (RuntimeException ignored) {
                biome = "?";
            }
            sb.append("位置：").append(dim).append(" (").append(pos.getX()).append(',')
                    .append(pos.getY()).append(',').append(pos.getZ()).append(") ")
                    .append(biome)
                    .append(pos.getY() < 40 ? " · 地下" : "")
                    .append("\n");

            // ② 我拥有什么（手上 + 身边容器）
            // 只读 tick 缓存（与执行侧同一份口径；上下文构建不读世界）
            String storage = RddContainerMemory.cached(companionId);
            if (!storage.isEmpty()) {
                String plain = storage.replaceAll("<[^>]+>", "").trim();
                sb.append("箱子里：").append(plain).append('\n');
            } else {
                sb.append("箱子里：附近容器未观测或未知（不能据此断定没有存货）\n");
            }
        }

        // ③ 世界里有什么（自己造过/登记过的世界资产）
        String world = "";
        try {
            if (companionId != null) world = RddAssetContext.renderForPlanner(RddPlugin.assets(companionId), 160, objective);
        } catch (RuntimeException ignored) {
            world = "";
        }
        String suffix = (world.isEmpty() ? "" : "世界里：" + world + "\n") + "</situation>";
        String marker = "…（前文已截断）\n";
        int room = MAX_CHARS - suffix.length();
        if (room < marker.length()) {
            suffix = "世界里：未投递\n</situation>";
            room = MAX_CHARS - suffix.length();
        }
        if (sb.length() > room) {
            int end = room - marker.length();
            if (end > 0 && Character.isHighSurrogate(sb.charAt(end - 1))) end--;
            sb.setLength(end);
            sb.append(marker);
        }
        return sb.append(suffix).toString();
    }

    /** 没有玩家对象时的退化版本（例如规划发生在离线恢复）。 */
    static String block(Map<String, Integer> held) {
        return block(held, false);
    }

    static String block(Map<String, Integer> held, boolean observed) {
        String hand = "";
        if (held != null && !held.isEmpty()) {
            StringBuilder h = new StringBuilder();
            int n = 0;
            for (Map.Entry<String, Integer> e : held.entrySet()) {
                if (n++ >= 8) { h.append("…"); break; }
                if (h.length() > 0) h.append('、');
                h.append(e.getKey().replace("minecraft:", "")).append('×').append(e.getValue());
            }
            hand = h.toString();
        }
        return "<situation>\n位置：未观测（规划时没有玩家对象）\n"
                + (hand.isEmpty() ? (observed ? "手上：最近一次扫描为空，当前需核实" : "手上：未观测")
                        : "手上：最近一次扫描 " + hand + "，当前需核实") + "\n"
                + "箱子里/世界里：未观测\n</situation>";
    }

}
