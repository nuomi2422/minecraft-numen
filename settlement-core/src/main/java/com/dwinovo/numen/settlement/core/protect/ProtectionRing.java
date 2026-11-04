package com.dwinovo.numen.settlement.core.protect;

import com.dwinovo.numen.settlement.core.model.BlockBox;
import com.dwinovo.numen.settlement.core.model.ProtectionZone;

import java.util.ArrayList;
import java.util.List;

/**
 * 设施保护环的**唯一生成器**（037/T4 第一轮 · ZONE-01，2026-11-04）。
 *
 * <p>为什么要有它：同一个"占地四周记结构、入口那一列留通道"的规则，两条路径各写了一份，
 * 而且两种写法结果不同 ——
 * <ul>
 *   <li>蓝图落地那条（SettlementTool）先铺满四面墙，再<b>额外</b>叠一个 SPACE；
 *       门列同时落在墙体 STRUCTURE 里，于是"通道能拆"永远不生效；</li>
 *   <li>手工登记那条（SettlementRegisterTool）把南墙按门列切开，门列只有 SPACE —— 这条才对。</li>
 * </ul>
 * 现在两份都改成调这里：墙按门列切开，门格只有 SPACE；门在角上时，相邻那面墙也要让开那一格。
 *
 * <p><b>高度由调用方显式传入</b>（{@code y0/y1}）：两条路径的墙高本来就不一样（蓝图取建筑全高、
 * 登记取墙高 2），本批<b>不</b>把它们悄悄统一。
 *
 * <p>无效几何明确拒绝（返回 rejection），绝不生成反向/负尺寸盒：
 * 占地小于 2x2、入口坐标落在该侧范围之外、高度区间反了。
 */
public final class ProtectionRing {

    /** 入口所在的那一侧。 */
    public enum Side { NORTH, SOUTH, EAST, WEST }

    /**
     * @param zones     生成的保护区（失败时为空表）
     * @param rejection 拒绝原因；成功时为 null
     */
    public record Result(List<ProtectionZone> zones, String rejection) {
        public boolean ok() {
            return rejection == null;
        }
    }

    private ProtectionRing() {}

    /**
     * 生成一圈保护：四条边为 STRUCTURE，入口那一格为 SPACE。
     *
     * @param bounds        设施占地（只用 X/Z 与最小/最大列；Y 由 y0/y1 决定）
     * @param y0            墙的最低层（含）
     * @param y1            墙的最高层（含）
     * @param side          入口所在侧
     * @param entranceCoord NORTH/SOUTH 时是门列的 X，EAST/WEST 时是门列的 Z；{@code null} 表示"这栋没有开口"，
     *                      四面墙完整生成、不产生 SPACE 区（用于模板入口不在占地环上的旧数据）
     */
    public static Result ring(BlockBox bounds, int y0, int y1, Side side, Integer entranceCoord) {
        if (bounds == null) return reject("bounds required");
        if (side == null) return reject("entrance side required");
        if (y0 > y1) return reject("invalid height range: y0=" + y0 + " > y1=" + y1);
        if (bounds.sizeX() < 2 || bounds.sizeZ() < 2) {
            return reject("footprint too small for a ring: " + bounds.sizeX() + "x" + bounds.sizeZ() + " (need >= 2x2)");
        }
        boolean alongX = side == Side.NORTH || side == Side.SOUTH;
        int lo = alongX ? bounds.minX() : bounds.minZ();
        int hi = alongX ? bounds.maxX() : bounds.maxZ();
        if (entranceCoord != null && (entranceCoord < lo || entranceCoord > hi)) {
            return reject("entrance " + entranceCoord + " is outside " + lo + ".." + hi + " on side " + side);
        }

        List<ProtectionZone> zones = new ArrayList<>(8);
        if (alongX) {
            int gateZ = side == Side.SOUTH ? bounds.maxZ() : bounds.minZ();
            // 有门的那面墙：按门列切开
            addWallAlongX(zones, bounds.minX(), bounds.maxX(), gateZ, y0, y1, entranceCoord);
            // 对侧整面
            int oppositeZ = side == Side.SOUTH ? bounds.minZ() : bounds.maxZ();
            addWallAlongX(zones, bounds.minX(), bounds.maxX(), oppositeZ, y0, y1, null);
            // 左右墙；门在角上时，相邻墙要让开那一格
            addWallAlongZ(zones, bounds.minX(), bounds.minZ(), bounds.maxZ(), y0, y1,
                    entranceCoord != null && entranceCoord == bounds.minX() ? gateZ : null);
            addWallAlongZ(zones, bounds.maxX(), bounds.minZ(), bounds.maxZ(), y0, y1,
                    entranceCoord != null && entranceCoord == bounds.maxX() ? gateZ : null);
            if (entranceCoord != null) {
                zones.add(ProtectionZone.space(BlockBox.of(entranceCoord, y0, gateZ, entranceCoord, y1, gateZ)));
            }
        } else {
            int gateX = side == Side.EAST ? bounds.maxX() : bounds.minX();
            addWallAlongZ(zones, gateX, bounds.minZ(), bounds.maxZ(), y0, y1, entranceCoord);
            int oppositeX = side == Side.EAST ? bounds.minX() : bounds.maxX();
            addWallAlongZ(zones, oppositeX, bounds.minZ(), bounds.maxZ(), y0, y1, null);
            addWallAlongX(zones, bounds.minX(), bounds.maxX(), bounds.minZ(), y0, y1,
                    entranceCoord != null && entranceCoord.intValue() == bounds.minZ() ? gateX : null);
            addWallAlongX(zones, bounds.minX(), bounds.maxX(), bounds.maxZ(), y0, y1,
                    entranceCoord != null && entranceCoord.intValue() == bounds.maxZ() ? gateX : null);
            if (entranceCoord != null) {
                zones.add(ProtectionZone.space(BlockBox.of(gateX, y0, entranceCoord, gateX, y1, entranceCoord)));
            }
        }
        return new Result(List.copyOf(zones), null);
    }

    /** 沿 X 的一面墙（固定 z）；{@code gapX} 非空时在该列断开。 */
    private static void addWallAlongX(List<ProtectionZone> out, int minX, int maxX, int z,
                                      int y0, int y1, Integer gapX) {
        if (gapX == null) {
            out.add(ProtectionZone.structure(BlockBox.of(minX, y0, z, maxX, y1, z)));
            return;
        }
        if (gapX - 1 >= minX) {
            out.add(ProtectionZone.structure(BlockBox.of(minX, y0, z, gapX - 1, y1, z)));
        }
        if (gapX + 1 <= maxX) {
            out.add(ProtectionZone.structure(BlockBox.of(gapX + 1, y0, z, maxX, y1, z)));
        }
    }

    /** 沿 Z 的一面墙（固定 x）；{@code gapZ} 非空时在该行断开。 */
    private static void addWallAlongZ(List<ProtectionZone> out, int x, int minZ, int maxZ,
                                      int y0, int y1, Integer gapZ) {
        if (gapZ == null) {
            out.add(ProtectionZone.structure(BlockBox.of(x, y0, minZ, x, y1, maxZ)));
            return;
        }
        if (gapZ - 1 >= minZ) {
            out.add(ProtectionZone.structure(BlockBox.of(x, y0, minZ, x, y1, gapZ - 1)));
        }
        if (gapZ + 1 <= maxZ) {
            out.add(ProtectionZone.structure(BlockBox.of(x, y0, gapZ + 1, x, y1, maxZ)));
        }
    }

    private static Result reject(String reason) {
        return new Result(List.of(), reason);
    }
}
