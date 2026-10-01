package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.plugins.learner.core.Memo;
import com.dwinovo.numen.plugins.learner.core.MemoQueue;
import com.dwinovo.numen.plugins.learner.core.Verdict;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * {@code learner_review}：学习者领取队列里的备忘录、逐条复盘、出结构化判定。
 *
 * <p><b>plan-only</b>：判定是「建议」，本工具不执行任何身体动作
 * （不调 AC、不写经验库、不自编译）。写经验由 AI 调既有 experience_learn，
 * 调 AC 由 AI 调既有 ac_execute。这样学习者不会成为第二个驾驶员（红线 RL-9）。
 *
 * <p><b>必须异步</b>（2026-09-29 P0 修复）：本工具在 body tool 的
 * {@code onServerCall} 里跑，而那是 <b>服务器主线程入口</b>
 * （{@code ExecuteToolPayload.handleCompanion()} → {@code tool.onServerCall}）。
 * 在这里 {@code .join()} 等 LLM 会把服务器主线程卡住最多 120 秒 —— 实机表现为
 * 整局卡死。照抄 {@code AcExecuteTool} 的后台线程模式：立刻回执 RUNNING，
 * 复盘在后台线程跑完，再按原服务器身份回主线程投递结果。
 *
 * <p><b>世界代际校验</b>：发起时捕获 server 实例，完成时校验
 * {@code ServerLifecycleHooks.getCurrentServer() == captured}；换档/重连后
 * 迟到的回调直接丢弃，绝不把旧世界的判定投递给新世界。
 *
 * <p>取走语义：领取只读快照，<b>复盘成功且判定与 memoId 严格一一对应</b>才 commit；
 * 否则 restore 回队列（不丢数据）。
 */
final class LearnerReviewTool implements NumenTool {

    private static final int DEFAULT_MAX = 5;
    private static final int HARD_MAX = 20;
    private static final int LLM_TIMEOUT_SEC = 120;

    @Override
    public String name() {
        return "learner_review";
    }

    @Override
    public NumenTool.Residency residency() {
        // 低频动作（复盘），不占每轮请求位置
        return NumenTool.Residency.DEFERRED;
    }

    @Override
    public String description() {
        return "Ask the learner to review queued memos and return structured verdicts. "
                + "Takes up to max_memos pending memos and reviews them asynchronously. "
                + "Returns review_id immediately; poll learner_status (or learner_review_result) for the "
                + "verdicts: actions (WRITE_EXPERIENCE / USE_AC / USE_CARRIER / SELF_COMPILE / "
                + "NO_ACTION / NEEDS_HUMAN) plus draft text. "
                + "It only PRODUCES suggestions; you decide whether to act on them "
                + "(e.g. call experience_learn or ac_execute yourself).";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalInteger("max_memos", "How many memos to review now (1-20).", 1, HARD_MAX)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        final UUID id;
        try {
            id = companion.getUUID();
        } catch (RuntimeException e) {
            reply.accept(TaskResult.fail("cannot resolve companion: " + e.getMessage()).toJson());
            return;
        }

        int max = DEFAULT_MAX;
        try {
            if (args != null && args.has("max_memos") && args.get("max_memos").isJsonPrimitive()) {
                int requested = args.get("max_memos").getAsInt();
                if (requested > 0) {
                    max = Math.min(requested, HARD_MAX);
                }
            }
        } catch (RuntimeException ignored) {
            // 参数坏掉就用默认值，不因此拒绝整次复盘
        }

        // 必须在主线程上读队列；此刻我们确实在主线程
        MemoQueue queue = LearnerPlugin.queue(id);
        List<Memo> taken = queue.drain(max);
        if (taken.isEmpty()) {
            reply.accept(TaskResult.ok("no pending memos to review", Map.of(
                    "reviewed", 0, "queue_depth", queue.size())).toJson());
            return;
        }

        MinecraftServer capturedServer = ServerLifecycleHooks.getCurrentServer();
        if (capturedServer == null) {
            // 没有服务器上下文就没法安全回主线程；诚实拒绝并把备忘录放回去
            int restored = queue.restore(taken);
            reply.accept(TaskResult.fail("no server context; memos restored (queue_depth=" + restored + ")")
                    .toJson());
            return;
        }

        final List<Memo> batch = taken;
        final String reviewId = "rev-" + System.currentTimeMillis() + "-" + Math.abs(batch.get(0).id().hashCode() % 1000);
        LearnerPlugin.beginReview(reviewId, id, "RUNNING", batch.size());

        // 立刻回执，绝不在主线程等 LLM
        reply.accept(TaskResult.ok("learner review started; it runs in background", Map.of(
                "review_id", reviewId,
                "status", "RUNNING",
                "reviewing", batch.size(),
                "queue_depth_after", queue.size(),
                "next", "poll learner_status with review_id=" + reviewId)).toJson());

        // 后台线程做 LLM 复盘（阻塞等待只发生在这里，不在服务器主线程）
        Thread.ofVirtual().name("learner-review-" + reviewId).start(() -> {
            LearnerReviewer.ReviewOutcome outcome = LearnerReviewer.review(batch, LLM_TIMEOUT_SEC).join();

            // 回主线程前校验世界代际：换档后迟到结果直接丢弃
            MinecraftServer current = ServerLifecycleHooks.getCurrentServer();
            if (current != capturedServer) {
                queue.restore(batch);
                LearnerPlugin.finishReview(reviewId, "STALE_WORLD", 0);
                LearnerMonitor.publish("review_stale", Map.of(
                        "review_id", reviewId, "companion", id.toString(), "restored", batch.size()));
                return;
            }
            capturedServer.execute(() -> completeOnServer(queue, id, reviewId, batch, outcome));
        });
    }

    /** 在服务器主线程上收尾：commit 或 restore，然后把结果挂到可查询的状态里。 */
    private void completeOnServer(MemoQueue queue, UUID id, String reviewId,
                                  List<Memo> batch, LearnerReviewer.ReviewOutcome outcome) {
        if (!outcome.ok()) {
            int restored = queue.restore(batch);
            LearnerPlugin.finishReview(reviewId, outcome.reason(), 0);
            LearnerMonitor.publish("review_failed", Map.of(
                    "review_id", reviewId, "companion", id.toString(),
                    "reason", outcome.reason(), "restored", restored,
                    "queue_depth", restored));
            return;
        }

        // P1 修复：只 commit「确实拿到判定」的条目，其余 restore 回去。
        // 绝不能因为「至少解析出一条」就删掉整批。
        java.util.Set<String> judged = new java.util.HashSet<>();
        for (Verdict v : outcome.verdicts()) {
            if (v.memoId() != null && !v.memoId().isBlank()) {
                judged.add(v.memoId());
            }
        }
        List<Memo> toCommit = new ArrayList<>();
        List<Memo> toRestore = new ArrayList<>();
        for (Memo m : batch) {
            if (judged.contains(m.id())) {
                toCommit.add(m);
            } else {
                toRestore.add(m);
            }
        }

        int removed = toCommit.isEmpty() ? 0 : queue.commitDrain(toCommit);
        int restored = toRestore.isEmpty() ? 0 : queue.restore(toRestore);
        int depth = queue.size();

        List<Map<String, Object>> rendered = new ArrayList<>();
        for (Verdict v : outcome.verdicts()) {
            if (v.memoId() == null || !v.memoId().startsWith("m-")) {
                // 防御：绝不允许 "?" 这类占位 id 流进监测台/下游
                continue;
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("memo_id", v.memoId());
            one.put("actions", v.actions().stream().map(Enum::name).toList());
            one.put("confidence", v.confidence());
            one.put("reasoning", v.reasoning());
            if (v.experience() != null) {
                // B21：只放真值；缺失的字段不出现；关键字段不齐要**说出来**
                Map<String, Object> ex = new java.util.LinkedHashMap<>();
                for (String f : com.dwinovo.numen.plugins.learner.core.Experience.FIELDS) {
                    String val = v.experience().field(f);
                    if (!val.isBlank()) {
                        ex.put(f, val);
                    }
                }
                one.put("experience", ex);
                one.put("experience_fields_filled", v.experience().filledCount());
                one.put("experience_acceptable", v.experience().acceptable());
                if (!v.experience().acceptable()) {
                    one.put("experience_unacceptable_reason", v.experience().unacceptableReason());
                    one.put("experience_missing_fields",
                            String.join(",", v.experience().missingFields()));
                }
            }
            if (!v.acScriptDraft().isBlank()) {
                one.put("ac_script_draft", v.acScriptDraft());
            }
            if (!v.rewrittenQuery().isEmpty()) {
                one.put("rewritten_query", v.rewrittenQuery());
            }
            rendered.add(one);

            // 携带器只携带不存储：把三段分级结论写进观测，不落任何状态文件
            batch.stream().filter(m -> v.memoId().equals(m.id())).findFirst().ifPresent(m -> {
                // B21（缺失的表达方式）：与 LearnerNoteTool 同一个边界的两处泄漏，一起修。
                // 契约在 core 的 Memo.carrierSignal()（纯 JVM，单测跑得到），本类只做拼装 + 换键名。
                // 旧写法无条件发 hp=-1 / target="UNKNOWN" / carry=[]，
                // 下游分不清「真判出 UNKNOWN」与「压根没快照可判」。
                Map<String, Object> sig = m.carrierSignal();
                Map<String, Object> ev = new java.util.LinkedHashMap<>();
                ev.put("memo_id", m.id());
                ev.put("snapshot_present", sig.get("snapshot_present"));
                if (Boolean.TRUE.equals(sig.get("snapshot_present"))) {
                    ev.put("target", sig.get("carrier_target"));
                    ev.put("carry", sig.get("carry_list"));
                    if (sig.containsKey("carrier_hp")) {
                        ev.put("hp", sig.get("carrier_hp"));
                    }
                    ev.put("why", m.carrierPreview());
                } else {
                    // 刻意不发 why：它含内部措辞「无环境快照，无法分级」，对下游没有可执行信息
                    ev.put("carry", sig.get("carry_list"));
                    ev.put("carry_meaning", sig.get("carry_list_meaning"));
                }
                LearnerMonitor.publish("carrier_assessment", ev);
            });
        }

        String at = Instant.now().toString();
        String state = rendered.isEmpty() ? "NO_VERDICT" : "DONE";
        LearnerPlugin.finishReview(reviewId, state, rendered.size());
        LearnerPlugin.recordReview(at, rendered.size());

        LearnerMonitor.publish("reviewed", Map.of(
                "review_id", reviewId, "companion", id.toString(),
                "reviewed", rendered.size(), "committed", removed, "restored", restored,
                "queue_depth", depth, "verdicts", rendered));
        LearnerPlugin.setReviewVerdicts(reviewId, rendered);
    }
}
