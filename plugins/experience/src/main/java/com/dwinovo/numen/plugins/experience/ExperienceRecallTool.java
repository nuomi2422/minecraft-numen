package com.dwinovo.numen.plugins.experience;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.experience.api.ExperienceHit;
import com.dwinovo.numen.experience.api.ExperienceMaturity;
import com.dwinovo.numen.experience.core.ExperienceMemory;
import com.dwinovo.numen.experience.core.PresentationReceipt;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 按当前任务/失败/异常检索相关经验，把命中的“现象/根因/推荐处理/成熟度”带回给 LLM。
 */
final class ExperienceRecallTool implements NumenTool {

    private static final Gson GSON = new Gson();

    /**
     * ★ E7：把「这批需要回报」讲给 AI 听。
     *
     * <p>此前 AI 拿到这批经验就结束了，<b>没有任何地方告诉它「用完了要回来报告」</b>
     * —— 于是 {@code experience_verify} 永远等不到调用，回流通道形同虚设。
     * 这句话是整条回流链路的最后一环。</p>
     *
     * <p>⚠️ 刻意<b>不</b>在这里做批量确认：一次召回可能带回 10 条，
     * 而 AI 往往只对其中两条有结论 ⇒ 自动全应用会给不相干的经验加反例甚至降级。</p>
     */
    private static final String REPORT_HINT =
            "These experiences were presented to you now, so the library can record whether they held up. "
                    + "When you know the real-world outcome of one of them, call experience_verify with that id "
                    + "and success=true/false. Only report the ones you actually tried - a call with no id "
                    + "just lists what is still waiting for a verdict.";

    @Override
    public String name() {
        return "experience_recall";
    }

    @Override
    public String description() {
        return "Search the maid's experience memory for lessons relevant to the current situation. "
                + "Pass what you are trying to do / what failed / the tool name. "
                + "Returns experience hits with root cause and recommended response.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("query", "The current task, failure symptom, or situation to search for.")
                .optionalInteger("limit", "Max results 1-20 (default 5).", 1, 20)
                .optionalEnum("min_maturity", "Only return experiences at least this verified: "
                        + "OBSERVED|ATTEMPTED|VERIFIED|GENERALIZED.", "OBSERVED", "ATTEMPTED", "VERIFIED", "GENERALIZED")
                .optionalStringArray("tags", "Only return experiences carrying one of these tags.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            Input in = GSON.fromJson(args, Input.class);
            String query = in == null ? "" : in.query();
            if (query == null || query.isBlank()) {
                reply.accept(TaskResult.fail("experience_recall requires a non-empty query").toJson());
                return;
            }
            int limit = in != null && in.limit() != null && in.limit() > 0 ? Math.min(in.limit(), 20) : 5;
            ExperienceMaturity min = parseMaturity(in == null ? null : in.min_maturity());
            List<String> tags = in != null && in.tags() != null ? in.tags() : List.of();

// ★ E7：reportable=true —— 这是唯一「AI 主动要」的召回点，
            //   只有它进待回报清单（L0 目录与规划知识是每轮自动印的，AI 可能压根没看，
            //   让它们进清单会产出满屏「本任务没有结论」的噪音）。
            ExperienceMemory memory = ExperiencePlugin.memory(companion.getUUID());
            PresentationReceipt receipts = memory.receipts();
            int before = receipts.pendingCount();
            List<ExperienceHit> hits = memory.recall(
                    query, limit, min, tags, PresentationReceipt.SURFACE_RECALL_TOOL, true);
            List<Map<String, Object>> results = new ArrayList<>();
            for (ExperienceHit hit : hits) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", hit.entry().id());
                item.put("title", hit.entry().title());
                item.put("type", hit.entry().type() == null ? "" : hit.entry().type().name());
                item.put("maturity", hit.entry().maturity().name());
                item.put("verified_count", hit.entry().verifiedCount());
                item.put("score", hit.score());
                item.put("matched_terms", hit.matchedTerms());
                item.put("description", hit.entry().description());
                item.put("root_cause", hit.entry().rootCause());
                item.put("recommended_response", hit.entry().recommendedResponse());
                // 之前被呈现过几次 —— 让 AI 知道这条是老相识还是这次新翻出来的。
                item.put("presented_before", receipts.presentedCount(hit.entry().id()));
                results.add(item);
            }
            ExperienceMonitor.publish("recalled", Map.of(
                    "companion", companion.getUUID().toString(),
                    "query_chars", query.length(),
                    "hits", hits.size(),
                    "pending_before", before,
                    "pending_after", receipts.pendingCount()));
            reply.accept(TaskResult.ok("experience recall returned " + results.size() + " hit(s)",
                    Map.of("hits", results,
                            "receipt", receipts.readout(),
                            "report_hint", REPORT_HINT)).toJson());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("experience_recall failed: " + ex.getMessage()).toJson());
        }
    }

    private static ExperienceMaturity parseMaturity(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        for (ExperienceMaturity m : ExperienceMaturity.values()) {
            if (m.name().equalsIgnoreCase(s)) {
                return m;
            }
        }
        return null;
    }

    private record Input(String query, Integer limit, String min_maturity, List<String> tags) {}
}
