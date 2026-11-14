package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.plugins.learner.core.Memo;
import com.dwinovo.numen.plugins.learner.core.MemoQueue;
import com.dwinovo.numen.plugins.learner.core.Verdict;
import java.util.*;
import java.util.function.Function;

/** 2026-11-11 C1: same completion decision gates artifacts and queue settlement.
 * Terminal skips are not learned verdicts. Only the existing gate's three definite reasons retire a memo.
 */
final class ReviewCompletion {
    private final Map<String, ReviewTargetGate.Check> targets = new LinkedHashMap<>();
    ReviewCompletion(List<Memo> batch, Function<Memo, ReviewTargetGate.Check> check) {
        for (Memo memo : batch) targets.put(memo.id(), check.apply(memo));
    }
    boolean permits(String memoId) {
        var target = targets.get(memoId);
        return target != null && target.ok();
    }
    boolean terminal(String memoId) {
        var target = targets.get(memoId);
        return target != null && target.terminal();
    }
    ReviewTargetGate.Check target(String memoId) { return targets.get(memoId); }
    /** Production uses this before contract/register side effects and verdict rendering. */
    boolean effective(String memoId, Set<String> judged) {
        return permits(memoId) && judged.contains(memoId);
    }
    record Settlement(int committed, int restored, int terminalSkipped) {}
    Settlement settle(MemoQueue queue, List<Memo> batch, Set<String> judged) {
        List<Memo> valid = new ArrayList<>(), retired = new ArrayList<>(), retry = new ArrayList<>();
        for (Memo memo : batch) {
            if (terminal(memo.id())) retired.add(memo);
            else if (permits(memo.id()) && judged.contains(memo.id())) valid.add(memo);
            else retry.add(memo);
        }
        int committed = valid.isEmpty() ? 0 : queue.commitDrain(valid);
        int terminalSkipped = retired.isEmpty() ? 0 : queue.commitDrain(retired);
        int restored = retry.isEmpty() ? 0 : queue.restore(retry);
        return new Settlement(committed, restored, terminalSkipped);
    }
    /** Original production selection -> contract/register -> settlement; no Minecraft dependency. */
    Consumption consume(java.nio.file.Path configDir, MemoQueue queue, List<Memo> batch,
                        List<com.dwinovo.numen.plugins.learner.core.Verdict> verdicts,
                        java.util.function.BiConsumer<String, Map<String, Object>> publish) {
        Set<String> judged = new HashSet<>();
        // ★ G6b Z2/Z3：写经验之前必须先看过库里有什么，且决策要过机核
        //   （REUSE/REVISE 指向检索到的条目；有高相似命中不许 NEW）。
        //   不过闸的 memo **不 commit**（下面会 restore 回去），拒因随事件落盘。
        java.util.List<String> retrievalRejected = new java.util.ArrayList<>();
        for (Verdict v : verdicts) {
            if (v.memoId() == null || v.memoId().isBlank()) {
                continue;
            }
            String gate = com.dwinovo.numen.plugins.learner.core.RetrievalGate.problem(v);
            if (gate != null) {
                retrievalRejected.add(v.memoId() + ": " + gate);
                continue;
            }
            if (!permits(v.memoId())) {
                var target = target(v.memoId());
                retrievalRejected.add(v.memoId() + ": target:" + (target == null ? "NOT_IN_BATCH" : target.reason()));
                continue;
            }
            judged.add(v.memoId());
        }
        // ── E 段：复盘契约闸门（默认 shadow）──────────────────────────────────
        // 它不改旧判定语义，只把「按新契约会不会被拒」量出来；配置成 enforce 才真的拦。
        // 默认 shadow 的理由写在 ReviewContractGate 类注释里：旧格式没有结构化证据，
        // 直接 ENFORCE 会让学习线当场停摆，而且没人知道为什么。
        java.util.List<Map<String, Object>> contractRows = new java.util.ArrayList<>();
        com.dwinovo.numen.plugins.learner.contract.ReviewContractGate.Mode contractMode =
                com.dwinovo.numen.plugins.learner.contract.ReviewContractGate.mode(configDir);
        if (contractMode != com.dwinovo.numen.plugins.learner.contract.ReviewContractGate.Mode.OFF) {
            for (Verdict v : verdicts) {
                // C1: invalid targets must not reach contract/register side effects (even shadow mode).
                if (!effective(v.memoId(), judged)) continue;
                if (v.memoId() == null || v.memoId().isBlank()) {
                    continue;
                }
                // F9：只有写明来路、且不是 client 注入的 memo，才把 window_first 作为证据；
                // 其余留空（质量门继续判 claim_without_evidence）。
                com.dwinovo.numen.plugins.learner.core.Memo srcMemo = batch.stream()
                        .filter(m -> v.memoId().equals(m.id()))
                        .findFirst()
                        .orElse(null);
                String memoSnapshot = srcMemo == null ? "" : srcMemo.snapshot();
                String memoStage = srcMemo == null ? "" : srcMemo.stage();
                String windowFirst = com.dwinovo.numen.plugins.learner.contract.ReviewContractAdapter
                        .evidenceAllowed(memoSnapshot, memoStage)
                        ? com.dwinovo.numen.plugins.learner.contract.ReviewContractAdapter
                                .windowFirstOf(memoSnapshot)
                        : "";
                // 2026-11-06（监督者实测）：提交侧带来的结构化证据优先，且**必须能在日志里查到**。
                // 形状合法但不存在的事件 id 一样能过契约校验 ⇒ 那会把"有证据"变成假象。
                java.util.List<String> memoEvidence = srcMemo == null ? java.util.List.of() : srcMemo.evidence();
                java.util.List<String> unresolved = com.dwinovo.numen.plugins.learner.core.EvidenceJournal
                        .unresolved(configDir.resolve("monitor"), memoEvidence);
                java.util.List<String> verifiedEvidence = new java.util.ArrayList<>(memoEvidence);
                verifiedEvidence.removeAll(unresolved);
                if (!unresolved.isEmpty()) {
                    try {
                        publish.accept("evidence_unresolved", Map.of(
                                "memo_id", v.memoId(), "provided", memoEvidence, "unresolved", unresolved));
                    } catch (RuntimeException ignored) {
                        // 观测失败不影响复盘
                    }
                }
                if (verifiedEvidence.isEmpty() && (windowFirst == null || windowFirst.isBlank())) {
                    try {
                        publish.accept("evidence_missing", Map.of(
                                "memo_id", v.memoId(), "reason", "missing_window_first"));
                    } catch (RuntimeException ignored) {
                        // 同上
                    }
                }
                var draft = com.dwinovo.numen.plugins.learner.contract.ReviewContractAdapter.toSubmission(
                        v, v.memoId(), com.dwinovo.numen.plugins.learner.contract.Submission.Mode.INTERNAL,
                        windowFirst, verifiedEvidence);
                var dec = com.dwinovo.numen.plugins.learner.contract.ReviewContractGate.evaluate(
                        contractMode, draft, null);
                if (dec == null) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("memo_id", v.memoId());
                row.put("status", dec.status().name());
                row.put("accepted", dec.result().accepted());
                row.put("codes", dec.codes());
                row.put("blocked", dec.blocked());
                // 只有**通过契约校验**的产物才进 Skill 目录（158 §7：质量门之后才登记）。
                // 没过门也登记，目录就成了草稿堆，而检索看到的是目录。
                // 权限矩阵判成 trial_only 的产物，登记时状态记 TRIAL（不是 DRAFT）——
                // 否则"矩阵判了、下游不知道"，可见范围照样一样。
                java.util.Set<Integer> trialIndexes = new java.util.LinkedHashSet<>();
                for (String code : dec.result().trialOnly()) {
                    int hash = code.indexOf('#');
                    int colon = code.indexOf(':', hash + 1);
                    if (hash > 0 && colon > hash) {
                        try {
                            trialIndexes.add(Integer.parseInt(code.substring(hash + 1, colon)));
                        } catch (NumberFormatException ignore) {
                            // 码形不对就不猜：宁可不标试用，也不乱标。
                        }
                    }
                }
                // 2026-11-06：近重复只提示、不拦截（正文相似度不足以自动合并）。
                // 先读一次目录算候选，再把事件落到 learner.jsonl；失败不影响注册。
                try {
                    var snapshot = com.dwinovo.numen.plugins.learner.skills.SkillRegistry.open(
                            configDir.resolve("skills.jsonl"));
                    for (var a : draft.artifacts()) {
                        var near = snapshot.nearDuplicates(a, 0.25);
                        if (near.isEmpty()) continue;
                        var best = near.getFirst();
                        java.util.Map<String, Object> ev = new LinkedHashMap<>();
                        ev.put("new_id", com.dwinovo.numen.plugins.learner.skills.SkillRegistrar.skillIdOf(a));
                        ev.put("existing_id", best.existingId());
                        ev.put("score", best.score());
                        ev.put("tool", String.join(",", com.dwinovo.numen.plugins.learner.skills.SkillRegistry.tokens(a.triggerConditions())));
                        publish.accept("near_duplicate_candidate", ev);
                    }
                } catch (RuntimeException | java.io.IOException ignored) {
                    // 观测失败不许影响注册
                }
                var regReport = com.dwinovo.numen.plugins.learner.skills.SkillRegistrar.register(
                        configDir, draft, dec.result().accepted(), trialIndexes);
                if (regReport.total() > 0 || !regReport.ok()) {
                    row.put("skills", java.util.Map.of(
                            "registered", regReport.registered(),
                            "reused", regReport.reused(),
                            "revised", regReport.revised(),
                            "conflicted", regReport.conflicted(),
                            "error", regReport.error()));
                }
                contractRows.add(row);
                // 2026-11-02：契约判定落成事件。reviewed 的 verdict 里没有 dec，
                // F9 的「post-F9 memo 带 event: 证据 + PASS_WITH_UNRESOLVED」以前只能靠推断；
                // 这里把 status/codes/accepted/window_first 一起落盘，活链可查。
                Map<String, Object> contractEv = new LinkedHashMap<>(row);
                contractEv.put("window_first", windowFirst);
                publish.accept("contract_checked", contractEv);
                if (dec.blocked()) {
                    // enforce：与检索闸门同样处理 —— 不 commit，memo 退回队列。
                    retrievalRejected.add(v.memoId() + ": contract:" + String.join(";", dec.codes()));
                    judged.remove(v.memoId());
                }
            }
        }

        var settlement = settle(queue, batch, judged);
        return new Consumption(judged, retrievalRejected, contractRows, contractMode, settlement,
                verdicts.stream().filter(v -> effective(v.memoId(), judged)).toList());
    }
    record Consumption(Set<String> judged, List<String> retrievalRejected,
                       List<Map<String, Object>> contractRows,
                       com.dwinovo.numen.plugins.learner.contract.ReviewContractGate.Mode contractMode,
                       Settlement settlement, List<com.dwinovo.numen.plugins.learner.core.Verdict> verdicts) {}
}
