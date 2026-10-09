package com.dwinovo.numen.settlement.core.logic;

import com.dwinovo.numen.settlement.core.model.BlockBox;
import com.dwinovo.numen.settlement.core.model.CellKey;
import com.dwinovo.numen.settlement.core.model.DimAnchor;
import com.dwinovo.numen.settlement.core.model.FacilityTemplate;
import com.dwinovo.numen.settlement.core.model.LocalPoint;
import com.dwinovo.numen.settlement.core.model.PlatformPlan;

import java.util.ArrayList;
import java.util.List;

/**
 * 「选格放置」的坐标数学（纯函数）：把 {@code (格, 模板, 旋转)} 换算成世界坐标。
 *
 * <p>AI 只说"在 B2 放一个牧场"，本类负责算出蓝图锚点、实际占地盒、入口内外站位、
 * 以及占用哪些格。<b>原点不随同伴当前位置漂移</b>——一切以已登记的基地基准为准，
 * 这正是用户点名的要求。
 *
 * <p>对齐约定（V1，确定性优先）：蓝图 min 角与<b>占地格的最小角</b>对齐。
 * 蓝图比占地小时多出来的是留白/通行空间（如 7×7 蓝图落在 2×2 格 = 10×10 占地里，
 * 右侧/南侧各留 3 格）。
 */
public final class PlacementMath {

    private PlacementMath() {}

    /** 一次放置的完整坐标解算结果。 */
    public record PlacementPlan(
            String templateId,
            CellKey cell,
            int rotationQuarters,
            /** 占地格覆盖的世界 XZ 盒（floor 层，高 1 格）。 */
            BlockBox footprintBox,
            /** 旋转后蓝图外廓的世界盒。 */
            BlockBox structureBox,
            /** 蓝图锚点（最低角，喂给 {@code blueprint action=build}）。 */
            DimAnchor anchor,
            /** 入口内侧站位。 */
            DimAnchor entranceInside,
            /** 入口外侧站位。 */
            DimAnchor entranceOutside,
            /** 占用的网格格。 */
            List<CellKey> cells) { }

    /** 占地格覆盖的世界盒（只算 XZ 与 floor 层）。 */
    public static BlockBox footprintBox(PlatformPlan plan, CellKey cell, FacilityTemplate template) {
        int x0 = plan.originX() + cell.cx() * plan.stride();
        int z0 = plan.originZ() + cell.cz() * plan.stride();
        int x1 = x0 + template.footprintCellsX() * plan.cellSize() - 1;
        int z1 = z0 + template.footprintCellsZ() * plan.cellSize() - 1;
        return BlockBox.of(x0, plan.floorY(), z0, x1, plan.floorY() + template.sizeY() - 1, z1);
    }

    /** 占用哪些格（从 cell 起向东南铺 footprintCells）。 */
    public static List<CellKey> occupiedCells(PlatformPlan plan, CellKey cell, FacilityTemplate template) {
        List<CellKey> out = new ArrayList<>();
        for (int dx = 0; dx < template.footprintCellsX(); dx++) {
            for (int dz = 0; dz < template.footprintCellsZ(); dz++) {
                out.add(CellKey.of(cell.cx() + dx, cell.cz() + dz));
            }
        }
        return out;
    }

    /**
     * 解算一次放置。不做任何合法性判断（那是 {@link PlacementValidator} 的事）——
     * 这里只负责"算出坐标"，两边分开才能把"算错"和"不许放"分清。
     */
    public static PlacementPlan resolve(PlatformPlan plan, CellKey cell, FacilityTemplate template,
                                        int rotationQuarters) {
        int q = RotationMath.normalizeQuarters(rotationQuarters);
        BlockBox footprint = footprintBox(plan, cell, template);
        int ax = footprint.minX();
        // 蓝图锚点 Y：各生成器约定不同（栅栏在脚层 / 地板在脚层 / 农田支撑在脚下一层），
        // 由模板显式声明 anchorYOffset，不靠猜。
        int ay = plan.floorY() + template.anchorYOffset();
        int az = footprint.minZ();

        int rotSizeX = RotationMath.rotatedSizeX(template.sizeX(), template.sizeZ(), q);
        int rotSizeZ = RotationMath.rotatedSizeZ(template.sizeX(), template.sizeZ(), q);
        BlockBox structure = BlockBox.of(ax, ay, az,
                ax + rotSizeX - 1, ay + template.sizeY() - 1, az + rotSizeZ - 1);

        LocalPoint in = template.entranceLocal();
        LocalPoint out = outsidePoint(in, template.entranceFacing());
        RotationMath.Point2 rin = RotationMath.rotateLocal(in.x(), in.z(),
                template.sizeX(), template.sizeZ(), q);
        RotationMath.Point2 rout = RotationMath.rotateLocal(out.x(), out.z(),
                template.sizeX(), template.sizeZ(), q);

        DimAnchor entranceInside = DimAnchor.of(plan.dimension(), ax + rin.x(), ay, az + rin.z());
        DimAnchor entranceOutside = DimAnchor.of(plan.dimension(), ax + rout.x(), ay, az + rout.z());

        return new PlacementPlan(template.id(), cell, q, footprint, structure,
                DimAnchor.of(plan.dimension(), ax, ay, az),
                entranceInside, entranceOutside, occupiedCells(plan, cell, template));
    }

    /** 门外站位 = 入口格沿朝向再走一格（局部坐标；可能越界，这是允许的）。 */
    public static LocalPoint outsidePoint(LocalPoint entrance, String facing) {
        String f = facing == null ? "south" : facing.toLowerCase(java.util.Locale.ROOT);
        return switch (f) {
            case "north" -> LocalPoint.of(entrance.x(), entrance.z() - 1);
            case "south" -> LocalPoint.of(entrance.x(), entrance.z() + 1);
            case "east" -> LocalPoint.of(entrance.x() + 1, entrance.z());
            case "west" -> LocalPoint.of(entrance.x() - 1, entrance.z());
            default -> LocalPoint.of(entrance.x(), entrance.z() + 1);
        };
    }

    /** 门外净空条（沿朝向从门外站位再往外 clearance 格），用于检查是否被别的设施堵住。 */
    public static BlockBox clearanceBox(PlacementMath.PlacementPlan plan, FacilityTemplate template) {
        DimAnchor o = plan.entranceOutside();
        int n = Math.max(1, template.clearanceOutside());
        int dx = 0;
        int dz = 0;
        String f = template.entranceFacing() == null ? "south"
                : template.entranceFacing().toLowerCase(java.util.Locale.ROOT);
        switch (f) {
            case "north" -> dz = -1;
            case "south" -> dz = 1;
            case "east" -> dx = 1;
            case "west" -> dx = -1;
            default -> dz = 1;
        }
        int x1 = o.x() + dx * (n - 1);
        int z1 = o.z() + dz * (n - 1);
        return BlockBox.of(o.x(), o.y(), o.z(), x1, o.y(), z1);
    }
}
