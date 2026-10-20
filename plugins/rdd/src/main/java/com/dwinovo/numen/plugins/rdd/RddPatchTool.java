package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.core.PlanPatch;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code rdd_patch}：给<b>当前一级</b>的待执行节点打补丁（G3d 宿主接入）。
 *
 * <p><b>为什么需要它</b>：规划器改计划只有一条路——{@code replaceCurrentSubtasks}
 * （整段作废、删旧状态与基线）。可实际需求常常是「只把后面两个还没做的节点换掉」，
 * 走整段替换会把已经积累的 attempt 计数与基线一起删掉（75号 §15.3-4 明确禁止）。
 *
 * <p><b>安全边界（本轮刻意窄）</b>：
 * <ul>
 *   <li>只能改<b>当前一级</b>里 <b>PENDING 且位于当前指针及之后</b>的节点——
 *       改到指针之前会让 {@code subtaskIndex} 错位（那不是补丁，是重排）；</li>
 *   <li>已完成/在跑/暂停节点一律拒绝（{@code NODE_NOT_PENDING}）；</li>
 *   <li>提交走 {@code stateVersion} CAS：调用方给 {@code base_state_version}（= 上一次
 *       {@code rdd_status}/快照里看到的值），版本不符直接 {@code STALE_VERSION}，不会覆盖别人的改动；</li>
 *   <li>不做整链重规划：换掉整条链仍走规划器原路（本工具只补「增量改计划」这一格）。</li>
 * </ul>
 *
 * <p>返回里带 {@code state_version}（提交后的新版本）与失败原因码，便于调用方决定重读还是放弃。
 */
final class RddPatchTool implements NumenTool {

    private static final Gson GSON = new Gson();

    @Override public String name() { return "rdd_patch"; }

    @Override public String description() {
        return "Patch the CURRENT primary goal of the RDD task chain incrementally, instead of replacing the whole "
                + "plan. Three operations on not-yet-started nodes: ADD_NODE (append a new pending node at the end), "
                + "REPLACE_PENDING (same node id, new description/condition, keeps its body instruction), DROP_PENDING "
                + "(remove a pending node). Only nodes that are PENDING and at or after the current pointer may be "
                + "touched — completed, running or paused nodes are refused (NODE_NOT_PENDING), and so are nodes before "
                + "the pointer (NODE_BEFORE_CURSOR). The commit is a compare-and-set on the chain's stateVersion: pass "
                + "base_state_version from a fresh rdd_status/snapshot; a mismatch returns STALE_VERSION and nothing is "
                + "changed. This tool never rewrites the whole chain — full replanning still belongs to the planner.";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("patch_id", "Stable id for this patch (idempotency key; a committed id cannot be reused).")
                .integer("base_state_version", "The state_version you observed (CAS base).",
                        Integer.MIN_VALUE, Integer.MAX_VALUE)
                .string("reason", "Why this patch is needed (goes into the receipt and logs).")
                .string("ops", "JSON array, each: {\"op\":\"ADD_NODE|REPLACE_PENDING|DROP_PENDING\","
                        + "\"node_id\":\"...\",\"description\":\"...\",\"condition\":{...}}. "
                        + "description/condition required for ADD and REPLACE; ignored for DROP.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            RddRuntime rt = RddPlugin.runtime(companion.getUUID());
            if (rt == null) {
                reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                        "no active RDD task chain; nothing to patch").toJson());
                return;
            }
            TaskChain chain = rt.chain();
            String patchId = str(args, "patch_id");
            String reason = str(args, "reason");
            String opsRaw = str(args, "ops");
            long base = args != null && args.has("base_state_version") && !args.get("base_state_version").isJsonNull()
                    ? args.get("base_state_version").getAsLong() : -1L;
            if (patchId.isBlank() || opsRaw.isBlank()) {
                reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                        "rdd_patch requires patch_id and ops").toJson());
                return;
            }
            List<PlanPatch.Op> ops = new ArrayList<>();
            JsonArray arr = GSON.fromJson(opsRaw, JsonArray.class);
            if (arr == null || arr.isEmpty()) {
                reply.accept(com.dwinovo.numen.task.TaskResult.fail("rdd_patch ops is empty").toJson());
                return;
            }
            for (JsonElement el : arr) {
                if (el == null || !el.isJsonObject()) {
                    reply.accept(com.dwinovo.numen.task.TaskResult.fail("rdd_patch ops must be objects").toJson());
                    return;
                }
                JsonObject o = el.getAsJsonObject();
                String kind = o.has("op") ? o.get("op").getAsString() : "";
                String nodeId = o.has("node_id") ? o.get("node_id").getAsString() : "";
                String desc = o.has("description") && !o.get("description").isJsonNull()
                        ? o.get("description").getAsString() : "";
                Map<String, Object> condition = new LinkedHashMap<>();
                if (o.has("condition") && o.get("condition").isJsonObject()) {
                    for (var e : o.getAsJsonObject("condition").entrySet()) {
                        condition.put(e.getKey(), GSON.fromJson(e.getValue(), Object.class));
                    }
                }
                PlanPatch.OpKind k = switch (kind) {
                    case "ADD_NODE" -> PlanPatch.OpKind.ADD_NODE;
                    case "REPLACE_PENDING" -> PlanPatch.OpKind.REPLACE_PENDING;
                    case "DROP_PENDING" -> PlanPatch.OpKind.DROP_PENDING;
                    default -> null;
                };
                if (k == null) {
                    reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                            "unknown op: " + kind + " (expected ADD_NODE / REPLACE_PENDING / DROP_PENDING)").toJson());
                    return;
                }
                ops.add(new PlanPatch.Op(k, nodeId, desc, condition));
            }
            PlanPatch patch = new PlanPatch(patchId,
                    chain.currentPrimary() == null ? "" : chain.currentPrimary().id(),
                    base, ops, reason);
            // 先 propose（只校验），再 commit（CAS + 原子应用）——两条分开调用，
            // 回执里能说清楚「是方案不合法」还是「版本已过期」。
            PlanPatch.Receipt proposed = chain.proposePatch(patch);
            if (!proposed.ok()) {
                reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                        "rdd_patch rejected (" + proposed.code() + "): " + proposed.why()).toJson());
                return;
            }
            PlanPatch.Receipt committed = chain.commitPatch(patch, base);
            if (!committed.ok()) {
                reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                        "rdd_patch commit rejected (" + committed.code() + "): " + committed.why()).toJson());
                return;
            }
            RddMonitor.publish("plan_patch_committed", Map.of(
                    "companionId", companion.getUUID().toString(),
                    "patchId", patchId,
                    "primary", patch.primaryId(),
                    "ops", String.valueOf(ops.size()),
                    "stateVersion", String.valueOf(committed.stateVersion()),
                    "reason", reason));
            RddPlugin.publishTaskSnapshot(companion.getUUID(), "plan_patch_committed");
            reply.accept(com.dwinovo.numen.task.TaskResult.ok("plan patch committed", Map.of(
                    "patch_id", patchId,
                    "ops", ops.size(),
                    "state_version", committed.stateVersion())).toJson());
        } catch (RuntimeException ex) {
            reply.accept(com.dwinovo.numen.task.TaskResult.fail("rdd_patch failed: " + ex.getMessage()).toJson());
        }
    }

    private static String str(JsonObject args, String key) {
        return args != null && args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : "";
    }
}
