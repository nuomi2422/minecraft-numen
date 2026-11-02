package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.plugins.learner.core.MemoQueue;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code learner_status}：查队列深度、上次复盘、以及某次复盘（review_id）的结果。
 *
 * <p>纯只读。因为 {@code learner_review} 是异步的（不能在服务器主线程等 LLM），
 * 结果要靠这个工具取回。
 */
final class LearnerStatusTool implements NumenTool {

    @Override
    public String name() {
        return "learner_status";
    }

    @Override
    public NumenTool.Residency residency() {
        return NumenTool.Residency.DEFERRED;
    }

    @Override
    public String description() {
        return "Read-only learner status: pending memo count, last review time, and the verdicts "
                + "of a specific review_id returned by learner_review. Use it to poll an "
                + "asynchronous review until its state is no longer RUNNING.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalString("review_id", "Which review to fetch results for.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            MemoQueue q = LearnerPlugin.queue(companion.getUUID());
            int depth;
            try {
                depth = q.size();
            } catch (RuntimeException e) {
                // 队列文件坏了：如实报 UNKNOWN，不假装 0（假装 0 会让 AI 以为「没东西可复盘」）
                reply.accept(TaskResult.fail("memo queue unreadable: " + e.getMessage()).toJson());
                return;
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("queue_depth", depth);
            data.put("queue_capacity", MemoQueue.MAX_QUEUE);
            data.put("last_review_at", LearnerPlugin.lastReviewAt());
            data.put("last_verdict_count", LearnerPlugin.lastVerdictCount());

            // B6/S2：投递箱现状。**「落地 ≠ 生效」**，所以把消费者情况一并说清楚：
            // 只有计数的话，AI 会把「有一堆 LANDED」误读成「都已被下游采纳」。
            var ob = LearnerPlugin.outbox();
            if (ob != null) {
                Map<String, Object> box = new LinkedHashMap<>(ob.stats());
                box.put("root", ob.root().toString());
                box.put("AC_SCRIPT_consumer", "acx.AcxArtifactAdopter（发布为 GENERATED，仍需人工 approve）");
                // ★ 这行以前写「NONE_YET（只有落点，无消费者）」—— 那是审批流还没建时写的，
                //   审批流建好后它就成了**撒谎的状态**：草稿其实有人接了。
                //   状态项撒谎比注释撒谎更坏（出事时第一个查的就是它），所以必须跟着实现一起改。
                box.put("CARRIER_consumer",
                        "learner.CarrierArtifactAdopter → 登记为候选；**要 learner_carrier approve 才进生效链**");
                // ★ 2026-10-27（权利缺口①）：SELF_COMPILE 有消费者了 ——
                //   SelfCompileRequests（取件契约：list/ack，回执落 ack 目录）+
                //   harness 的 selfcompile-drain.js（人/工程流用）。以前写 NONE_YET
                //   是撒谎的状态：请求其实一直躺在那没人看得见。
                box.put("SELF_COMPILE_REQUEST_consumer",
                        "SelfCompileRequests 取件契约（ack 目录）+ harness/bin/selfcompile-drain.js；"
                                + "取件状态见 selfcompile_requests 列");
                try {
                    var scr = new com.dwinovo.numen.plugins.learner.core.SelfCompileRequests(
                            ob, com.dwinovo.numen.plugins.learner.LearnerPlugin.configDir());
                    java.util.List<java.util.Map<String, Object>> reqs = new java.util.ArrayList<>();
                    for (var rq : scr.list()) {
                        java.util.Map<String, Object> one = new java.util.LinkedHashMap<>();
                        one.put("artifact_id", rq.artifactId());
                        one.put("state", rq.state().name());
                        one.put("taken_by", rq.takenBy());
                        one.put("note", rq.note());
                        one.put("memo_id", rq.memoId());
                        reqs.add(one);
                    }
                    box.put("selfcompile_requests", reqs);
                } catch (Throwable t) {
                    box.put("selfcompile_requests", "UNREADABLE: " + t);
                }
                box.put("note", "落地 ≠ 生效：以 *_consumer 为准，别把 LANDED 当成已被采纳");
                data.put("artifact_outbox", box);
            } else {
                data.put("artifact_outbox", "UNSET（插件 setup 没跑完？）");
            }

            // 样本层（158/159 C）：这段只说"现在是什么状态"，不说"学到了什么"。
            // note 会明确写「关闭/开启」，防止把接线读成已生效。
            try {
                java.nio.file.Path cfg = LearnerPlugin.configDir();
                if (cfg == null) {
                    data.put("samples", java.util.Map.of("unreadable", true, "note", "UNSET（插件 setup 没跑完？）"));
                } else {
                    data.put("samples", com.dwinovo.numen.plugins.learner.sample.SampleStatusView.render(
                            com.dwinovo.numen.plugins.learner.sample.SampleIntakeRuntime.shared(cfg).stats()));
                }
            } catch (Throwable t) {
                // 面板读不出来不许影响状态查询本身：其余字段照常返回。
                data.put("samples", java.util.Map.of("unreadable", true, "error", String.valueOf(t)));
            }

            // Skill 目录（159 G）：空目录也要能一眼看见，而不是去文件系统里考古。
            try {
                java.nio.file.Path cfg = LearnerPlugin.configDir();
                data.put("skills", com.dwinovo.numen.plugins.learner.skills.SkillCatalogView.stats(cfg));
            } catch (Throwable t) {
                data.put("skills", java.util.Map.of("readable", false, "error", String.valueOf(t)));
            }

            // 2026-11-03（外部学习者，#464/#465）：needs_review 一直「有写没读者」——
            //   至少把「最近 24 小时里带未复核项的判定」摆出来，让积压可见。
            try {
                data.put("review_backlog", reviewBacklog(LearnerPlugin.configDir(), System.currentTimeMillis()));
            } catch (Throwable t) {
                data.put("review_backlog", java.util.Map.of("unreadable", true, "error", String.valueOf(t)));
            }

            String reviewId = null;
            try {
                if (args != null && args.has("review_id") && args.get("review_id").isJsonPrimitive()) {
                    reviewId = args.get("review_id").getAsString().trim();
                }
            } catch (RuntimeException ignored) {
                // 参数坏掉就只回概览
            }
            if (reviewId != null && !reviewId.isBlank()) {
                LearnerPlugin.ReviewRecord rec = LearnerPlugin.review(reviewId);
                if (rec == null) {
                    data.put("review_id", reviewId);
                    data.put("state", "NOT_FOUND");
                    reply.accept(TaskResult.fail("unknown review_id: " + reviewId
                            + " (records are capped at 32; older ones are evicted)").toJson());
                    return;
                }
                data.putAll(LearnerPlugin.reviewToMap(rec));
            }

            reply.accept(TaskResult.ok("learner status", data).toJson());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("learner_status failed: " + ex.getMessage()).toJson());
        }
    }

    /**
     * 最近 24 小时的未复核积压（纯读文件，不猜）。
     * 判据：reviewed 事件的 verdicts[].quality_gate.needs_review 非空即计入，按检查项 id 汇总。
     * 读不到就报 UNREADABLE —— 与队列深度同一条纪律（不假装 0）。
     */
    static java.util.Map<String, Object> reviewBacklog(java.nio.file.Path cfg, long nowMillis) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (cfg == null) {
            out.put("status", "UNKNOWN");
            out.put("why", "configDir 未就绪");
            return out;
        }
        java.nio.file.Path f = cfg.resolve("monitor/learner.jsonl");
        if (!java.nio.file.Files.isRegularFile(f)) {
            out.put("status", "MISSING");
            out.put("path", f.toString());
            return out;
        }
        long since = nowMillis - 24L * 3600_000L;
        int verdicts = 0;
        int withUnreviewed = 0;
        java.util.Map<String, Integer> byCheck = new java.util.LinkedHashMap<>();
        try {
            for (String line : java.nio.file.Files.readAllLines(f, java.nio.charset.StandardCharsets.UTF_8)) {
                if (!line.contains("\"reviewed\"")) continue;
                com.google.gson.JsonObject o;
                try {
                    o = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
                } catch (RuntimeException e) {
                    continue;
                }
                if (!"reviewed".equals(str(o, "type"))) continue;
                long ts;
                try {
                    ts = java.time.Instant.parse(str(o, "timestamp")).toEpochMilli();
                } catch (RuntimeException e) {
                    continue;
                }
                if (ts < since) continue;
                com.google.gson.JsonObject d = o.has("data") && o.get("data").isJsonObject()
                        ? o.getAsJsonObject("data") : null;
                if (d == null || !d.has("verdicts") || !d.get("verdicts").isJsonArray()) continue;
                for (com.google.gson.JsonElement el : d.getAsJsonArray("verdicts")) {
                    if (!el.isJsonObject()) continue;
                    verdicts++;
                    com.google.gson.JsonObject v = el.getAsJsonObject();
                    com.google.gson.JsonObject g = v.has("quality_gate") && v.get("quality_gate").isJsonObject()
                            ? v.getAsJsonObject("quality_gate") : null;
                    if (g == null || !g.has("needs_review") || !g.get("needs_review").isJsonArray()) continue;
                    boolean any = false;
                    for (com.google.gson.JsonElement idEl : g.getAsJsonArray("needs_review")) {
                        String id = idEl.isJsonPrimitive() ? idEl.getAsString() : "";
                        if (id.isBlank()) continue;
                        any = true;
                        byCheck.merge(id, 1, Integer::sum);
                    }
                    if (any) withUnreviewed++;
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            out.put("status", "UNREADABLE");
            out.put("error", String.valueOf(e));
            return out;
        }
        out.put("status", "OK");
        out.put("window", "last 24h");
        out.put("verdicts", verdicts);
        out.put("with_unreviewed", withUnreviewed);
        out.put("by_check", byCheck);
        out.put("note", "needs_review 目前没有消费者（#464）：这些判定停在 REVIEW_REQUIRED，没人复核");
        return out;
    }

    private static String str(com.google.gson.JsonObject o, String k) {
        if (o == null || !o.has(k) || !o.get(k).isJsonPrimitive()) return "";
        try {
            return o.get(k).getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }
}
