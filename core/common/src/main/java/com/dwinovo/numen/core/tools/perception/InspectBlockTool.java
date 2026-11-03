package com.dwinovo.numen.core.tools.perception;
import com.dwinovo.numen.core.tools.PerceptionOps;

import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Query tool (raw NumenTool): inspect the single block at the given coordinates. */
public final class InspectBlockTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final PerceptionOps impl = new PerceptionOps();

    /**
     * 2026-11-03（外部学习者 P0）：支持一次查多格。
     *
     * <p>实机 13:00–13:28：为给「放床」找位置，{@code inspect_block} 被调 **56 次**（46 个不同坐标，
     * 单轮最多 10 个并行），而那 28 分钟里总共跑了 198 轮 LLM、**759 万输入 token**
     * （约 38k/轮）⇒ 逐点枚举约等于 56 轮 ≈ 215 万 token、8 分钟，只为确认一块 3×2 空间。
     *
     * <p>所以加可选 {@code points: ["x,y,z", …]}：一次调用返回全部格子。老用法
     * （只传 x/y/z）行为完全不变。
     */
    private record Args(Integer x, Integer y, Integer z, java.util.List<String> points) {}

    /** 一次最多查多少格（防模型一口气要几百格把回执撑爆）。 */
    static final int MAX_POINTS = 64;

    @Override
    public String name() {
        return "inspect_block";
    }

    /** 常驻:单点确认,动手前后都要用。 */
    @Override
    public Residency residency() {
        return Residency.RESIDENT;
    }

    @Override
    public String description() {
        return "Inspect a block (or several blocks at once). Returns block id, its "
                + "block-state properties when any (e.g. an end_portal_frame's has_eye/facing), "
                + "hardness, whether you have the correct tool in hand, an estimated dig-tick count, "
                + "and whether the block is in your 4.5-block mining reach. Call this before mine "
                + "to confirm the operation will succeed, or to check which end_portal_frame cells "
                + "still need an ender_eye. "
                + "For SEVERAL points pass points:[\"x,y,z\", …] (max " + MAX_POINTS + ") in ONE call instead of "
                + "calling this tool once per block — enumerating a 3x2 space or a wall cell by cell costs a full "
                + "round trip per block. Use scan_blocks when you want to search a radius by block id.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalInteger("x", "Block X (single-block form).", -30000000, 30000000)
                .optionalInteger("y", "Block Y (single-block form).", -2048, 2048)
                .optionalInteger("z", "Block Z (single-block form).", -30000000, 30000000)
                .optionalStringArray("points", "Multi-block form: list of \"x,y,z\" strings, e.g. [\"-364,82,772\",\"-363,82,772\"].")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        if (a != null && a.points() != null && !a.points().isEmpty()) {
            java.util.List<String> parts = new java.util.ArrayList<>();
            for (String raw : a.points()) {
                if (parts.size() >= MAX_POINTS) break;
                int[] p = parsePoint(raw);
                if (p == null) {
                    reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                            "bad point \"" + raw + "\" — expected \"x,y,z\" with three integers").toJson());
                    return;
                }
                parts.add(impl.inspectBlock(p[0], p[1], p[2], self));
            }
            reply.accept("{\"count\":" + parts.size() + ",\"blocks\":[" + String.join(",", parts) + "]}");
            return;
        }
        if (a == null || a.x() == null || a.y() == null || a.z() == null) {
            reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                    "need either x,y,z (single block) or points:[\"x,y,z\", …] (several blocks)").toJson());
            return;
        }
        reply.accept(impl.inspectBlock(a.x(), a.y(), a.z(), self));
    }

    /** 解析 {@code "x,y,z"}；兼容带空格的写法。纯函数，便于单测。 */
    static int[] parsePoint(String raw) {
        if (raw == null) return null;
        String[] ps = raw.split(",");
        if (ps.length != 3) return null;
        try {
            return new int[]{Integer.parseInt(ps[0].trim()), Integer.parseInt(ps[1].trim()), Integer.parseInt(ps[2].trim())};
        } catch (NumberFormatException bad) {
            return null;
        }
    }
}
