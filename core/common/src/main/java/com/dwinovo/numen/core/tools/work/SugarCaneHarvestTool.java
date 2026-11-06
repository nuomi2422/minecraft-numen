package com.dwinovo.numen.core.tools.work;

import static com.dwinovo.numen.task.TaskDispatch.setTask;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.core.task.sugarcane.SugarCaneTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Bounded sugar-cane harvest tool (2026-11-07, record 008 9.1-9.3). BACKGROUND task. */
public final class SugarCaneHarvestTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private static final long WALL_MS_PER_BLOCK = 2_000L;
    private static final long MAX_DEADLINE_MS = 120_000L;

    private record Args(Integer count, Integer max_targets, Integer radius) {}

    @Override public String name() { return SugarCaneTaskRecord.TOOL_NAME; }

    @Override public String description() {
        return "Harvest sugar cane nearby with a bounded background task. Breaks only the TOP count upper blocks "
                + "of each column (bottom root is kept); no manual drops - vanilla pickup/stat handles items. "
                + "Bounded: count 1..64, max_targets 1..16, radius 1..16, hard deadline <=120s. "
                + "NO_SHOT / occluder returns failure (target-only, no clearing); PROGRESSING is normal. "
                + "BACKGROUND task: returns task_id immediately; result arrives in task_finished.";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("count", "Upper sugar-cane blocks to destroy (not stalks, not items). 1..64.", 1, 64)
                .optionalInteger("max_targets", "Max sugar-cane columns to try (default 8).", 1, 16)
                .optionalInteger("radius", "Horizontal search radius (default 12).", 1, 16)
                .build();
    }

    @Override public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                                       Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        if (a == null || a.count() == null) {
            reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                    "sugar_cane_harvest requires count (upper blocks, 1..64); bounded tasks only").toJson());
            return;
        }
        int count = Math.clamp(a.count(), 1, 64);
        int maxTargets = a.max_targets() == null ? 8 : Math.clamp(a.max_targets(), 1, 16);
        int radius = a.radius() == null ? 12 : Math.clamp(a.radius(), 1, 16);
        long now = System.currentTimeMillis();
        long deadline = now + Math.min(MAX_DEADLINE_MS, count * WALL_MS_PER_BLOCK);
        var record = new SugarCaneTaskRecord(count, maxTargets, radius, deadline, now);
        setTask(companion, record, args, reply);
    }
}