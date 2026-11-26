package com.dwinovo.numen.core.tools.work;
import com.dwinovo.numen.core.tools.BlockActionOps;

import static com.dwinovo.numen.task.TaskDispatch.*;

import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** World-action tool (raw NumenTool): gather blocks by type and quantity. */
public final class AutoMineTool implements NumenTool {

    private final BlockActionOps impl = new BlockActionOps();

    private record Args(List<String> block_ids, int count) {}

    @Override
    public String name() {
        return "mine";
    }

    @Override
    public String description() {
        return "Gather blocks by type and count. Give block id(s) and how many ITEMS you want — it finds "
                + "the nearest matches, travels to each with full terrain-traversing navigation (digs to "
                + "buried ores, pillars up cliffs, bridges gaps), mines, and repeats until `count` NEW items "
                + "are gained or none remain nearby. No coordinates or goto needed. count is items, not "
                + "blocks (redstone_ore drops ~4). Include all variants in block_ids (iron_ore AND "
                + "deepslate_iron_ore). Optional target_bounds limits only ore target positions; movement, "
                + "occluder handling and item pickup may still occur outside those bounds. It is not a route "
                + "or terrain-modification boundary. Only mines what its tools actually harvest, and stops naming the "
                + "needed tier if nothing qualifies (to destroy blocks regardless of drops, use "
                + "break_block). BACKGROUND: a successful call is already running; do not call mine/goto "
                + "again while <current_task> exists and do not poll. task_finished status=done means the task "
                + "ended, not necessarily that the requested count was reached: partial collection can also end "
                + "normally. Check the gathered/requested summary and, when present, partial/shortfall in the "
                + "result. If fewer items were gathered, observe the remaining targets and inventory before "
                + "deciding what to do next; do not blindly resend the same arguments.";
    }


    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> schema = Schema.object()
                .stringArray("block_ids", "Namespaced block id(s) to gather; include all variants.", 1)
                .integer("count", "How many ITEMS to gather (not blocks) — a block may drop several, and it "
                        + "counts only items gained on top of what you already hold.", 1, 256)
                .build();
        Map<String, Object> coordinates = new java.util.LinkedHashMap<>();
        for (String key : List.of("x1", "y1", "z1", "x2", "y2", "z2")) {
            Map<String, Object> coordinate = new java.util.LinkedHashMap<>();
            coordinate.put("type", "integer");
            if (key.startsWith("x") || key.startsWith("z")) {
                coordinate.put("minimum", -30000000);
                coordinate.put("maximum", 29999999);
            }
            coordinates.put(key, coordinate);
        }
        Map<String, Object> bounds = new java.util.LinkedHashMap<>();
        bounds.put("type", List.of("object", "null"));
        bounds.put("properties", coordinates);
        bounds.put("required", List.of("x1", "y1", "z1", "x2", "y2", "z2"));
        bounds.put("additionalProperties", false);
        bounds.put("description", "Optional inclusive ore target bounds, min <= max; Y must be legal in the current dimension. "
                + "Movement, occluder handling and item pickup may occur outside. Not a path or terrain boundary.");
        @SuppressWarnings("unchecked") Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        properties.put("target_bounds", bounds);
        return schema;
    }

    private static BlockPos[] parseTargetBounds(JsonObject args) {
        var raw = args.get("target_bounds");
        if (raw == null || raw.isJsonNull()) return null;
        if (!raw.isJsonObject()) throw new IllegalArgumentException("target_bounds must be an object or null");
        JsonObject bounds = raw.getAsJsonObject();
        java.util.Set<String> keys = java.util.Set.of("x1", "y1", "z1", "x2", "y2", "z2");
        if (!bounds.keySet().equals(keys))
            throw new IllegalArgumentException("target_bounds requires exactly x1,y1,z1,x2,y2,z2");
        int x1 = targetCoordinate(bounds, "x1"), y1 = targetCoordinate(bounds, "y1"), z1 = targetCoordinate(bounds, "z1");
        int x2 = targetCoordinate(bounds, "x2"), y2 = targetCoordinate(bounds, "y2"), z2 = targetCoordinate(bounds, "z2");
        if (x1 > x2 || y1 > y2 || z1 > z2)
            throw new IllegalArgumentException("target_bounds minimum coordinates must not exceed maximum coordinates");
        return new BlockPos[]{new BlockPos(x1,y1,z1), new BlockPos(x2,y2,z2)};
    }

    private static int targetCoordinate(JsonObject bounds, String key) {
        var value = bounds.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("target_bounds." + key + " must be an integer JSON number");
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new IllegalArgumentException("target_bounds." + key + " must be a finite integer in the supported coordinate range");
        }
    }


    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        // These constraints are not wired through scan, path and digging yet. Never silently
        // discard one and start an unconstrained task. JSON null means no constraint.
        for (String key : List.of("x1", "y1", "z1", "x2", "y2", "z2", "min_y", "max_y", "path_bounds")) {
            if (args.has(key) && !args.get(key).isJsonNull()) {
                throw new IllegalArgumentException("mine does not support " + key
                        + " yet; no mining task was started. Do not rely on this constraint being applied.");
            }
        }
        BlockPos[] bounds = parseTargetBounds(args);
        if (bounds != null && (!companion.level().isInWorldBounds(bounds[0])
                || !companion.level().isInWorldBounds(bounds[1])))
            throw new IllegalArgumentException("target_bounds corners must be within this dimension's legal block coordinates");
        Args a = ToolArgs.fromJson(args, Args.class);
        setTask(companion, impl.autoMine(a.block_ids(), a.count(),
                ctx(toolCallId, companion), bounds == null ? null : bounds[0], bounds == null ? null : bounds[1]), args, reply);
    }
}
