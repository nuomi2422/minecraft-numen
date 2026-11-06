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
    @Override public String name() { return SugarCaneTaskRecord.TOOL_NAME; }

    @Override public String description() {
        return "Harvest sugar cane nearby with a bounded background task. Breaks at most count upper blocks TOTAL "
                + "(not per column; highest cells first), keeps the bottom root of each column; no manual drops. "
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
        var parsed = com.dwinovo.numen.core.tools.work.SugarCaneHarvestArgs.parse(args, System.currentTimeMillis());
        if (parsed.record() == null) {
            reply.accept(com.dwinovo.numen.task.TaskResult.fail(parsed.error()).toJson());
            return;
        }
        var record = parsed.record();
        setTask(companion, record, args, reply);
    }
}