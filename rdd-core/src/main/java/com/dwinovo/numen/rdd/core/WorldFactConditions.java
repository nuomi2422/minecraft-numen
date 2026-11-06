package com.dwinovo.numen.rdd.core;

import java.util.Map;
import java.util.Set;

/**
 * Schema only. Actual evidence is read by the Minecraft host, never by the core.
 *
 * <h2>加一个 type 必须同步四处</h2>
 * <pre>
 *   ① 本类（schema）        ② {@code RddWorldFacts.matches}（真身）        ③ {@code RddDecomposer} 提示词（告知规划器）
 *   ④ {@code WorldFactConditionsPinTest}（钉死清单，防漏）
 * </pre>
 * 漏任何一处都会得到 RL-18 同族事故：规划器生成了一个永远判不出来的条件，
 * 整条链静默卡死且不产生失败。更隐蔽的是 {@code RddDecomposer.parseOne}
 * 对未知 type 是<b>静默丢弃整条二级、连报错都没有</b>。
 *
 * <h2>为什么结构 / 群系 / 方块都允许 {@code #tag}</h2>
 * 原版注册表里「一个东西有好几个变体」是常态：村庄有五个 id、海底废墟有冷暖两版、
 * 「任意森林」是一整个 biome tag。规划器如果必须逐个枚举变体，漏一个就静默判假。
 * 精确 id 与 tag 都接受，才让「到了任意村庄」这种话能变成可判定的条件。
 */
public final class WorldFactConditions {
    private WorldFactConditions() {}

    /** 本构建认识的世界事实类型全集。加 type 时本清单必须同步改。 */
    private static final Set<String> TYPES = Set.of(
            "advancement", "structure", "entity_killed", "base",
            "biome", "block_nearby", "container_nearby",
            // A 组：原版 Stats（服务器权威、跨重启、每同伴终身）——判“真的做了 N 次”
            "block_mined", "item_crafted", "item_used", "item_picked_up",
            // B 组：位置 / 维度
            "dimension", "position_at", "y_below",
            // C 组：实体 / 精确坐标
            "entity_nearby", "block_at", "container_at",
            // D 组：基地分级（2026-10-25 主人预设：绑床只是第一级，有屋/有存货/有灯才算后续）
            "shelter", "stocked", "lit",
            // E 组：装备槽实穿（2026-11-03：obtain_armor 成就只证明“拿到过”，不证明四槽穿上）
            "equipment");

    /** 判定半径上限。判定只读一个邻域，永不遍历世界 —— 这是它能每秒跑一次的前提。 */
    public static final int MAX_NEARBY_RADIUS = 16;
    public static final int DEFAULT_BLOCK_RADIUS = 8;
    public static final int DEFAULT_CONTAINER_RADIUS = 8;
    /** position_at 的默认容差半径（格）。 */
    public static final int DEFAULT_POSITION_RADIUS = 4;

    public static boolean valid(Map<String, Object> condition) {
        if (condition == null || condition.containsKey("asset_key") || condition.containsKey("group")) return false;
        Object type = condition.get("type");
        if (!(type instanceof String name)) return false;
        if (condition.containsKey("dimension") && !id(condition.get("dimension"))) return false;
        return switch (name) {
            case "advancement" -> id(condition.get("advancement"));
            case "structure" -> tag(condition.get("structure"));
            case "entity_killed" -> id(condition.get("entity")) && positive(condition.getOrDefault("minimum", 1));
            case "base" -> true;
            case "biome" -> tag(condition.get("biome"));
            case "block_nearby" -> (tag(condition.get("block")) || blockList(condition.get("blocks")))
                    && radius(condition.get("radius"), MAX_NEARBY_RADIUS)
                    && optionalPositive(condition.get("minimum"))
                    && optionalAnchor(condition.get("anchor"));
            case "container_nearby" -> tag(condition.get("item"))
                    && radius(condition.get("radius"), MAX_NEARBY_RADIUS)
                    && positive(condition.getOrDefault("minimum", 1));
            case "block_mined" -> tag(condition.get("block")) && optionalPositive(condition.get("minimum"))
                    && scopeOk(condition);
            case "item_crafted", "item_used", "item_picked_up" ->
                    tag(condition.get("item")) && optionalPositive(condition.get("minimum"))
                    && scopeOk(condition);
            case "dimension" -> id(condition.get("dimension"));
            case "y_below" -> integer(condition.get("y"));
            case "position_at" -> coord(condition) && radius(condition.get("radius"), MAX_NEARBY_RADIUS);
            case "entity_nearby" -> tag(condition.get("entity"))
                    && radius(condition.get("radius"), MAX_NEARBY_RADIUS)
                    && optionalPositive(condition.get("minimum"))
                    && optionalAnchor(condition.get("anchor"));
            case "block_at" -> coord(condition) && tag(condition.get("block"));
            case "container_at" -> coord(condition) && tag(condition.get("item"))
                    && optionalPositive(condition.get("minimum"));
            // D 组：基地分级。全部以重生床为锚点（与 base 同源），没有床时一律判 false。
            //   shelter：床周围 RADIUS 格内"围合"程度达标——四面墙块数 ≥ minimum（默认 20）且头顶 ≤2 格内有顶盖。
            //   stocked：床 RADIUS 格内所有容器里的物品**总数** ≥ minimum（默认 10）——判"真放了东西"，不只是"有个空箱"。
            //   lit：床 RADIUS 格内光源（火把/灯笼/萤石等 tag）≥ minimum（默认 4）——夜里不刷怪。
            case "shelter" -> optionalRadius(condition.get("radius")) && optionalPositive(condition.get("minimum"));
            case "stocked" -> optionalRadius(condition.get("radius")) && optionalPositive(condition.get("minimum"));
            case "lit" -> optionalRadius(condition.get("radius")) && optionalPositive(condition.get("minimum"));
            case "equipment" -> equipmentValid(condition);
            default -> false;
        };
    }

    /**
     * 装备事实只表示当前槽位内容，不证明本步穿戴动作或完整套装。
     * 未实现的字段（例如 mode=acquire）不能悄悄退化成当前持有。
     */
    private static boolean equipmentValid(Map<String, Object> condition) {
        if (!Set.of("type", "item", "slot", "minimum", "dimension", "optional")
                .containsAll(condition.keySet())) return false;
        if (!tag(condition.get("item"))) return false;
        if (condition.containsKey("slot") && equipmentSlotIndex(condition.get("slot")) == -2) return false;
        if (condition.containsKey("minimum") && !positive(condition.get("minimum"))) return false;
        return !condition.containsKey("optional") || condition.get("optional") instanceof Boolean;
    }

    /** 0=feet, 1=legs, 2=chest, 3=head, 4=mainhand, 5=offhand；-1=any（含主副手），-2=非法。 */
    private static int equipmentSlotIndex(Object raw) {
        if (raw == null) return -2;
        if (!(raw instanceof String s)) return -2;
        return switch (s.toLowerCase(java.util.Locale.ROOT)) {
            case "feet", "boots" -> 0;
            case "legs", "leggings" -> 1;
            case "chest", "chestplate" -> 2;
            case "head", "helmet" -> 3;
            case "mainhand" -> 4;
            case "offhand" -> 5;
            case "any" -> -1;
            default -> -2;
        };
    }

    /** 四个护甲布尔量的旧入口（兼容既有调用/测试）；主副手按未命中处理。
     *  2026-11-06：`slot:any` 原先只统计四护甲槽，主手钻石镐/钻石剑实测 2/2 判 false
     *  （见记录 006）；`any` 的契约是“任一装备槽”，故补 6 槽入口。 */
    public static boolean equipmentSlotsSatisfied(Map<String, Object> condition,
                                                   boolean feet, boolean legs, boolean chest, boolean head) {
        return equipmentSlotsSatisfied(condition, feet, legs, chest, head, false, false);
    }

    /** 六个布尔量须来自同一次服务端采样；`any` 覆盖主手、副手与四护甲槽。 */
    public static boolean equipmentSlotsSatisfied(Map<String, Object> condition,
                                                   boolean feet, boolean legs, boolean chest, boolean head,
                                                   boolean mainhand, boolean offhand) {
        if (!valid(condition) || !"equipment".equals(condition.get("type"))) return false;
        int slot = condition.containsKey("slot") ? equipmentSlotIndex(condition.get("slot")) : -1;
        boolean[] matched = {feet, legs, chest, head, mainhand, offhand};
        int count = 0;
        for (int i = 0; i < matched.length; i++) {
            if ((slot == -1 || slot == i) && matched[i]) count++;
        }
        return count >= minimumOf(condition, 1);
    }

    /**
     * 2026-11-03 实机：判据写 `white_bed`，现场是 `purple_bed` → 永远判假。
     * 床的颜色只是外观变体，任何 `*_bed`（含历史泛化 id `minecraft:bed`）都视为同一类方块。
     */
    public static boolean isBedBlockId(String id) {
        if (id == null) return false;
        return id.equals("minecraft:bed") || id.endsWith("_bed");
    }

    /** 统计类条件的口径：delta（默认，本步增量）/ lifetime（终身累计）；其它值拒绝。 */
    private static boolean scopeOk(Map<String, Object> condition) {
        Object raw = condition.get("scope");
        return raw == null || "delta".equals(raw) || "lifetime".equals(raw);
    }

    /**
     * 只有容器类条件支持 `#minecraft:food` 的 FOOD 组件回退；统计类仍按 tag 是否存在判 UNKNOWN，
     * 不能因为同名字段就假装可解析（2026-11-03 审计：P8l 最初无 type 过滤，stat 类被误标可解析）。
     */
    /** 原版没有 food 物品 tag；`minecraft:food` 与 `minecraft:is_food` 都按"可直接吃"语义回退。 */
    public static boolean isFoodTag(String id) {
        return "minecraft:food".equals(id) || "minecraft:is_food".equals(id);
    }

    public static boolean supportsFoodTagFallback(Object conditionType) {
        return "container_at".equals(conditionType) || "container_nearby".equals(conditionType);
    }

    /** 该统计条件是否显式要求终身口径（检测器据此跳过增量基线）。 */
    public static boolean lifetimeScope(Object condition) {
        return condition instanceof Map<?, ?> m && "lifetime".equals(m.get("scope"));
    }

    public static boolean knownType(Object type) {
        return type instanceof String s && TYPES.contains(s);
    }

    // ── 宿主侧读取用的解析器（形状已由 valid 把关，这里不再失败，只给默认值）──────

    /** 邻域判定半径；未给或非法时回落到缺省值（合法路径上 {@link #valid} 已经挡过非法值）。 */
    /** 多个方块 id（2026-10-25）：{@code blocks} 允许"torch 或 wall_torch"一起算，
     *  因为 AI 会按最省事的方式贴墙插，而只认一种 id 会把合理做法判成没做。 */
    /** 可选固定锚点：三个整数坐标（建造类目标用，避免"人一走条件就变假"）。 */
    public static boolean optionalAnchor(Object value) {
        if (value == null) return true;
        if (!(value instanceof java.util.List<?> xyz) || xyz.size() != 3) return false;
        for (Object one : xyz) {
            if (!(one instanceof Number n) || !Double.isFinite(n.doubleValue())) return false;
        }
        return true;
    }

    public static boolean blockList(Object value) {
        if (!(value instanceof java.util.List<?> list) || list.isEmpty()) return false;
        for (Object one : list) {
            if (!tag(one)) return false;
        }
        return true;
    }

    public static int radiusOf(Map<String, Object> condition, int fallback) {
        Object raw = condition == null ? null : condition.get("radius");
        return raw instanceof Number n && n.intValue() >= 1 && n.intValue() <= MAX_NEARBY_RADIUS
                ? n.intValue() : fallback;
    }

    public static int minimumOf(Map<String, Object> condition, int fallback) {
        Object raw = condition == null ? null : condition.get("minimum");
        return raw instanceof Number n && n.intValue() > 0 ? n.intValue() : fallback;
    }

    /** 去掉 {@code #} 前缀后的注册表 id；不是合法形状返回 null。 */
    public static String tagId(Object value) {
        if (!(value instanceof String raw) || raw.isBlank()) return null;
        String body = raw.startsWith("#") ? raw.substring(1) : raw;
        return id(body) ? body : null;
    }

    /** 该条件写的是 tag（{@code #x:y}）还是精确 id（{@code x:y}）。 */
    public static boolean isTag(Object value) {
        return value instanceof String raw && raw.startsWith("#");
    }

    private static boolean id(Object value) {
        return value instanceof String s && s.matches("[a-z0-9_.-]+:[a-z0-9_./-]+");
    }

    private static boolean tag(Object value) {
        if (value instanceof String s && s.startsWith("#")) return id(s.length() > 1 ? s.substring(1) : null);
        return id(value);
    }

    private static boolean positive(Object value) {
        return value instanceof Number n && n.intValue() > 0 && n.doubleValue() == n.intValue();
    }

    /** 可选 minimum：缺省合法；给了必须是 >0 整数。 */
    private static boolean optionalPositive(Object value) {
        return value == null || positive(value);
    }

    /** 任意整数（可为负），用于坐标 / 高度。 */
    private static boolean integer(Object value) {
        return value instanceof Number n && n.doubleValue() == n.intValue();
    }

    /** 水平坐标上限（世界边界 3000 万格；目标点写到百万格外基本就是写错了）。 */
    private static final int MAX_HORIZONTAL = 30_000_000;
    /** 世界最低可放置高度（主世界 -64）。 */
    private static final int MIN_Y = -64;
    /** 世界最高可放置高度（主世界 320）。 */
    private static final int MAX_Y = 320;

    /**
     * 必须有整数 x/y/z 三元，**且在可玩世界范围内**。
     *
     * <p>2026-10-28 实机抓到：模型把结构坐标 "(320,848)"（其实只有 x/z 两维）填成了
     * {@code position_at{x:320,y:848,z:848}} —— y=848 超出世界高度上限，这个条件
     * **永远不可能成立**，那一步就一直 STALLED、整条链停住。
     *
     * <p>判无效会走既有的「不可执行 → 重新拆步」路径，比让它悄悄永远为假好得多。
     */
    private static boolean coord(Map<String, Object> condition) {
        if (!(integer(condition.get("x")) && integer(condition.get("y")) && integer(condition.get("z")))) {
            return false;
        }
        int x = ((Number) condition.get("x")).intValue();
        int y = ((Number) condition.get("y")).intValue();
        int z = ((Number) condition.get("z")).intValue();
        if (Math.abs(x) > MAX_HORIZONTAL || Math.abs(z) > MAX_HORIZONTAL) return false;
        return y >= MIN_Y && y <= MAX_Y;
    }

    private static boolean radius(Object value, int cap) {
        if (value == null) return true;
        return value instanceof Number n
                && n.intValue() >= 1 && n.intValue() <= cap
                && n.doubleValue() == n.intValue();
    }

    /** D 组（shelter/stocked/lit）的 radius 是可选的；给了必须合法。 */
    private static boolean optionalRadius(Object value) {
        return radius(value, MAX_NEARBY_RADIUS);
    }
}