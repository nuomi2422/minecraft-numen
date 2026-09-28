package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.plugins.learner.core.Memo;
import com.dwinovo.numen.plugins.learner.core.MemoQueue;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * {@code learner_note}：干活 AI 遇到问题时写一条原始备忘录。
 *
 * <p>只往队列写一条带环境快照的纸条，<b>不做任何判定、不写经验库</b>。
 * 判定留给 learner_review。
 */
final class LearnerNoteTool implements NumenTool {

    private static final Gson GSON = new Gson();

    @Override
    public String name() {
        return "learner_note";
    }

    @Override
    public String description() {
        return "Record a raw memo about a problem you hit, for the learner to review later. "
                + "Call this when you are STUCK: a tool failed, you retried repeatedly, an action had "
                + "no effect, or you had to abandon a sub-task. "
                + "Required: problem, tried. Optional: stage, snapshot (JSON-ish text with hp/armor/nearby). "
                + "This only appends to a queue; it does NOT fix anything by itself.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("problem", "What went wrong or blocked you (concrete, one or two sentences).")
                .string("tried", "What you already tried before giving up on this.")
                .optionalString("stage", "Which stage/phase this happened in.")
                .optionalString("snapshot", "Environment snapshot as text, e.g. hp=6/20, armor=none, nearby=zombie,dim=overworld.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            Input in = GSON.fromJson(args, Input.class);
            if (in == null || blank(in.problem()) || blank(in.tried())) {
                reply.accept(TaskResult.fail("learner_note requires problem and tried").toJson());
                return;
            }
            UUID id = companion.getUUID();
            MemoQueue q = LearnerPlugin.queue(id);
            String memoId = "m-" + System.currentTimeMillis() + "-" + Math.abs(in.problem().hashCode() % 1000);
            Memo memo = new Memo(memoId, in.problem().trim(), nz(in.stage()), in.tried().trim(),
                    nz(in.snapshot()), System.currentTimeMillis());

            if (!q.append(memo)) {
                // 分不清是「满了」还是「字段太长」时不猜：两种都如实报，并给出当前深度
                int depthNow;
                try {
                    depthNow = q.size();
                } catch (RuntimeException e) {
                    depthNow = -1;
                }
                reply.accept(TaskResult.fail("memo not queued: queue full (" + MemoQueue.MAX_QUEUE
                        + ") or field too long (" + MemoQueue.MAX_FIELD_CHARS
                        + " chars); run learner_review to drain first; current depth=" + depthNow).toJson());
                return;
            }

            int depth = q.size();
            Memo.CarrierAssessment assess = memo.assessCarrier();
            LearnerMonitor.publish("noted", Map.of(
                    "memo_id", memoId,
                    "companion", id.toString(),
                    "queue_depth", depth,
                    "carrier_target", assess.target(),
                    "carrier_hp", assess.hp()));

            reply.accept(TaskResult.ok("memo queued for learner review", Map.of(
                    "memo_id", memoId,
                    "queue_depth", depth,
                    "carrier_preview", assess.why(),
                    "carry_list", assess.carryList())).toJson());
        } catch (RuntimeException ex) {
            // 队列读失败会抛 IllegalStateException（刻意不按空队列覆盖，见 MemoQueue.readAll）
            reply.accept(TaskResult.fail("learner_note failed: " + ex.getMessage()).toJson());
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private record Input(String problem, String tried, String stage, String snapshot) {}
}
