package com.dwinovo.numen.core.task.move;

import com.dwinovo.numen.task.TaskRecord;

/**
 * Typed task descriptor for the {@code goto} tool. The goal type is chosen
 * by WHICH inputs are supplied: the LLM picks its intent by filling only the
 * fields it means.
 * <ul>
 *   <li>{@code x} + {@code z} (no {@code y}) → {@link Kind#COLUMN}:
 *       walk to that location, Y auto-resolved to the surface.
 *       The default "go there" — a guessed Y can never make it unreachable.</li>
 *   <li>{@code x} + {@code y} + {@code z} → {@link Kind#BLOCK}:
 *       one exact cell (a verified-reachable spot).</li>
 *   <li>{@code y} only → {@link Kind#YLEVEL}:
 *       change elevation to that height.</li>
 *   <li>{@code block} only (no coordinates) → {@link Kind#FIND}:
 *       scan for the nearest block of that kind and walk up beside it,
 *       never touching it.</li>
 * </ul>
 * Coordinates are nullable ({@code null} = "not supplied"); the deadline-based
 * timeout is handled by the base class.
 *
 * <p>{@code mayAlterTerrain} is the model's explicit consent to dig through, bridge
 * or pillar on the way. Without it the walk never changes a block (see
 * {@code TerrainPermit}); when the only route would, the failure lists exactly which
 * blocks and the model decides whether to re-send with consent.
 */
public final class MoveToTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "goto";

    public enum Kind { BLOCK, COLUMN, YLEVEL, FIND }

    /** Nullable: {@code null} means the LLM did not supply this axis. */
    public final Double x;
    public final Double y;
    public final Double z;
    /** Namespaced block id to walk to the nearest of; null when coordinates drive. */
    public final String block;
    public final Kind kind;
    /** Consent to dig / bridge / pillar en route. False = the walk leaves every block as it was. */
    public final boolean mayAlterTerrain;

    public MoveToTaskRecord(String toolCallId, long deadlineGameTime,
                            Double x, Double y, Double z, String block, boolean mayAlterTerrain) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.x = x;
        this.y = y;
        this.z = z;
        this.block = block == null || block.isBlank() ? null : block.trim();
        this.kind = resolveKind(x, y, z, this.block);
        this.mayAlterTerrain = mayAlterTerrain;
    }

    /**
     * Map supplied inputs → goal kind (arity decides intent, expressed here as
     * named nullable fields). Throws a teaching error for ambiguous
     * combos so the LLM learns the valid shapes.
     */
    private static Kind resolveKind(Double x, Double y, Double z, String block) {
        boolean hasX = x != null, hasY = y != null, hasZ = z != null;
        if (block != null) {
            if (hasX || hasY || hasZ) {
                // 2026-10-02（stage 20261002-104007 候选1）：旧文案只说 "goto its location (x+z)"，
                // **漏了 y 的后果**——实测模型照做后带着 y=14 走到悬在半空的刷怪笼正下方 24 格，
                // 撞上候选2（到达判定只算水平距离），空转两轮。互斥规则本身是对的、也确实在
                // 教模型（它后来真的改成裸坐标了），所以这里保留规则，只把「改成什么」讲全：
                // x+z = 走到那一列的**地表**；x+y+z = 站进那个**格子**（会掏掉里面的方块）。
                throw new IllegalArgumentException(
                        "block means 'walk to the nearest one of these' — so it cannot be combined"
                                + " with coordinates. You asked for block=" + block
                                + " AND a position, which is the one combination I cannot express."
                                + " Pick the one you actually mean:"
                                + " (a) I want the NEAREST such block, let her choose → send block=\""
                                + block + "\" alone, no coordinates;"
                                + " (b) I want THAT specific one at " + coords(x, y, z)
                                + " → send its location. Use x+z ONLY to walk to that column's"
                                + " surface (y auto-resolves to the ground); if that block is up in"
                                + " the air, surface-walking lands you UNDER it and you will need"
                                + " interact or a vertical move, not a plain goto;"
                                + " send x+y+z ONLY if you want her standing IN that exact cell"
                                + " (whatever occupies it gets dug out — never aim this at something"
                                + " you want kept, like a chest or a spawner).");
            }
            return Kind.FIND;
        }
        if (hasX && hasZ) {
            return hasY ? Kind.BLOCK : Kind.COLUMN;
        }
        if (hasY && !hasX && !hasZ) {
            return Kind.YLEVEL;
        }
        throw new IllegalArgumentException(
                "goto needs either x+z (a location; omit y to auto-resolve the "
                + "surface), x+y+z (one exact cell), y alone (a target height), "
                + "or block alone (walk to the nearest block of that kind). "
                + "Got " + (hasX ? "x" : "") + (hasY ? "y" : "") + (hasZ ? "z" : ""));
    }

    /**
     * 坐标串，只拼<b>实际给了</b>的那几轴。
     *
     * <p>2026-10-02 实机抓到：直接把 boxed {@code Double} 拼进文案，没给 y 时会印出字面量
     * {@code -878.0,null,-908.0}——把「y 会自动解析到地表」这个意思变成了一个假坐标，
     * 模型照抄这个字符串就会去 y=0。**报错文案里出现 null 就是在教模型填错值。**
     */
    private static String coords(Double x, Double y, Double z) {
        StringBuilder sb = new StringBuilder();
        if (x != null) sb.append(x);
        if (y != null) { if (sb.length() > 0) sb.append(','); sb.append(y); }
        if (z != null) { if (sb.length() > 0) sb.append(','); sb.append(z); }
        return sb.toString();
    }

    @Override
    /**
     * 一行人话 —— 这是<b>给主人看的</b>:头顶气泡、面板、task_status 印的都是它。
     * 工具 id 不写进来,需要它的地方(运行时状态的 tool 属性、派发回执)本来就有。
     */
    public String describe() {
        String where = switch (kind) {
            case BLOCK -> "走向 " + (int) (double) x + "," + (int) (double) y + "," + (int) (double) z;
            case COLUMN -> "走向 x=" + (int) (double) x + " z=" + (int) (double) z;
            case YLEVEL -> "下到 y=" + (int) (double) y;
            case FIND -> "去找 " + block;
        };
        return mayAlterTerrain ? where + "(可开路)" : where;
    }
}
