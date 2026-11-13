package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * 2026-11-13 C1 · 已记录待审 delivery → 现 MemoQueue 普通待审 memo 的最小消费者。
 *
 * <p>2026-11-13：原 20 项取得 11 条真实断言红后实现；只消费已落账事实。
 * 当前尚未接生产 tick；宿主预算和持久 journal 适配留后批，本类不会自行触发扫描。
 * 设计边界（父 2026-11-13 口述）：
 * <ul>
 *   <li>只读 {@link UsageLedger} 的 skill PRESENTED/UNKNOWN 行；已有 RESULT 的同 artifactId 不再当“未复核”；</li>
 *   <li>scope（world/server/epoch/companion）全等才收，旧账本跨世界不入当前队列；</li>
 *   <li>幂等键 = `sd-` + sha256(artifactId)，复用既有 EnqueuedJournal（跨重启）；</li>
 *   <li>预算由调用方传既有上限（LearnerAutoIntake.MAX_ENQUEUE_PER_SCAN），本类不新增阈值；</li>
 *   <li>admission 用 StormGuard.admitIfEnqueued 单一事务：允许时在锁内 append+验证，
 *       只有验证成功才提交冷却；拒绝不调 append；失败/异常不吃冷却，下一轮可立即重试；</li>
 *   <li>只入队：不调 LLM、不 beginReview、不写 ADOPTED/RESULT/SUCCESS。</li>
 * </ul>
 */
public final class SkillDeliveryIntake {

    /** 当前宿主身份（缺任一项 = 不完整，扫描直接跳过，不猜 world）。 */
    public record Scope(String worldId, String serverSession, long epoch, String companionId) {
        public boolean complete() {
            return worldId != null && !worldId.isBlank()
                    && serverSession != null && !serverSession.isBlank()
                    && epoch > 0
                    && companionId != null && !companionId.isBlank();
        }
    }

    /**
     * 一次扫描读数。{@code enqueued} 只计 queue+journal 持久成功；
     * {@code physicalAdded} 计本轮 append 前不存在、append 后确认存在的 memo，journal 失败也占预算。
     * 修复 journal、历史 seen、满队列与准入拒绝不算物理新增。
     *
     * <p>{@code resultChannel} = 交付已有同 artifact 的最新 RESULT，或完整原任务身份精确匹配 RESULT：
     * 只说明这条有动作结果、**不代表学习者已经复审过**；它不再走“交付候选”，避免旧 PRESENTED 重入。
     */
    public record Scan(int enqueued, int foreignScope, int duplicate, int stormSuppressed,
                       int failed, int resultChannel, int physicalAdded) {}

    /** 既有持久去重；remember 须先持久化、后更新 contains，失败抛异常（宿主接线属后批）。 */
    public interface EnqueuedJournal {
        boolean contains(String memoId);
        void remember(String memoId);
    }

    /**
     * 节流授权：与 StormGuard.admitIfEnqueued 同形。
     * 允许时执行 once（append+contains），只有 once 返回 true 才算真正入队并提交冷却。
     */
    public interface Admission {
        boolean admitIfEnqueued(String companionId, String fingerprint, long now, BooleanSupplier once);
    }

    private SkillDeliveryIntake() {}

    /**
     * 纯函数：从账本行挑出可入队的候选；不 mutate 入参、不写队列。
     *
     * <p>入参可为 {@link UsageLedger#all()} 全历史；实现必须**先按 (companion, artifactId) 取最新结论**
     * （与 {@link UsageLedger#conclusions()} 同规则：阶段 rank 高者胜、同等取后写者），
     * 只有最新结论是 skill PRESENTED/UNKNOWN 才进入候选；最新是 RESULT 的记 resultChannel。
     */
    public static List<UsageLedger.Entry> selectDue(List<UsageLedger.Entry> rows, Scope scope,
                                                    Set<String> alreadyEnqueued,
                                                    Set<String> reviewedArtifactIds, int budget) {
        if (budget <= 0) return List.of();
        List<UsageLedger.Entry> out = new ArrayList<>();
        for (Candidate candidate : classify(rows, scope, false).due()) {
            if (!(candidate instanceof DeliveryCandidate)) continue;
            Delivery delivery = ((DeliveryCandidate) candidate).delivery();
            String id = memoIdOf(delivery.row());
            if (alreadyEnqueued != null && alreadyEnqueued.contains(id)) continue;
            if (reviewedArtifactIds != null && reviewedArtifactIds.contains(delivery.row().artifactId())) continue;
            out.add(delivery.row());
            if (out.size() == budget) break;
        }
        return List.copyOf(out);
    }

    /** 执行一轮：事务内 append → 验证 → 才 remember。budget 由调用方传既有上限。 */
    public static Scan run(List<UsageLedger.Entry> rows, Scope scope, MemoQueue queue,
                           EnqueuedJournal journal, Admission admission, long now, int budget) {
        // Delivery-only legacy contract stays exactly as the verified 98-test loop.
        return execute(classify(rows, scope, false), scope, queue, journal, admission, now, budget);
    }

    /**
     * 2026-11-13 C1 RESULT merge seam: delivery and task-result candidates share one original loop,
     * one physical budget and one unconfirmed-write break. Splitting them would double-spend or lose
     * the cross-type stop; this method is the only entry that admits task results.
     */
    public static Scan runWithTaskResults(List<UsageLedger.Entry> rows, Scope scope, MemoQueue queue,
                                          EnqueuedJournal journal, Admission admission, long now, int budget) {
        return execute(classify(rows, scope, true), scope, queue, journal, admission, now, budget);
    }

    private static Scan execute(Selection selection, Scope scope, MemoQueue queue, EnqueuedJournal journal,
                                Admission admission, long now, int budget) {
        int enqueued = 0, duplicate = 0, suppressed = 0, failed = 0, physicalAdded = 0;
        if (budget > 0) for (Candidate candidate : selection.due()) {
            if (physicalAdded >= budget) break;
            String id = candidate.id();
            boolean[] added = {false}, unconfirmed = {false};
            try {
                if (journal.contains(id)) { duplicate++; continue; }
                boolean[] attempted = {false}, existing = {false}, committed = {false};
                boolean admitted = admission.admitIfEnqueued(scope.companionId(), candidate.fingerprint(), now, () -> {
                    attempted[0] = true;
                    // Queue write may have succeeded before an earlier journal failure. Repair only;
                    // do not append another memo, spend budget or commit a second cooldown.
                    if (queue.contains(id)) {
                        remember(journal, id);
                        existing[0] = true;
                        return false;
                    }
                    Memo memo = candidate.memo(now);
                    boolean appended;
                    unconfirmed[0] = true;
                    try {
                        appended = queue.append(memo);
                    } finally {
                        // 2026-11-13: count a write even if append throws after persisting it.
                        added[0] = queue.contains(id);
                        unconfirmed[0] = false;
                    }
                    if (!appended) return false;
                    if (!added[0]) {
                        existing[0] = true; // append=true can mean historical seen, not a current entry.
                        return false;
                    }
                    remember(journal, id);
                    committed[0] = true;
                    return true;
                });
                if (admitted && committed[0]) enqueued++;
                else if (existing[0]) duplicate++;
                else if (!attempted[0]) suppressed++;
                else failed++;
            } catch (RuntimeException failure) {
                failed++; // Keep the ledger candidate retryable; no journal or cooldown success claim.
            } finally {
                if (added[0]) physicalAdded++; // Spend the original budget before the next candidate.
            }
            if (unconfirmed[0]) break; // An unreadable post-write queue cannot authorize more writes.
        }
        return new Scan(enqueued, selection.foreignScope(), duplicate, suppressed, failed,
                selection.resultChannel(), physicalAdded);
    }

    /**
     * Stable typed boundary for the result intake and review gate (2026-11-13).
     * Full original Entry plus decoded identity; body fields retain the selected original version.
     */
    public record TaskResultFact(UsageLedger.Entry entry, Scope scope, String ref, String version,
                                 String taskId, String taskCall, String bodyRef, String bodyDigest) {}

    /** One typed parser: the existing private taskResult identity checks are the only algorithm. */
    public static TaskResultFact taskResultOf(UsageLedger.Entry row) {
        if (row == null || !completeEntry(row)) return null;
        TaskUse use = taskResult(row);
        if (use == null) return null;
        try {
            JsonObject detail = JsonParser.parseString(row.detail()).getAsJsonObject();
            return new TaskResultFact(row, use.scope(), use.ref(), use.version(), use.taskId(), use.taskCall(),
                    optionalText(detail, "body_ref"), optionalText(detail, "body_digest"));
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /** Snapshot adapter to the same row parser; the nested Entry JSON must round-trip without loss. */
    public static TaskResultFact taskResultFromSnapshot(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) return null;
        try {
            return taskResultOf(entryFromSnapshot(JsonParser.parseString(snapshot).getAsJsonObject()));
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /** Entry -> snapshot JSON; detail is the parsed object, never a re-escaped or hand-built string. */
    private static String entrySnapshot(UsageLedger.Entry row) {
        JsonObject out = new JsonObject();
        out.addProperty("artifact_id", row.artifactId());
        out.addProperty("kind", row.kind());
        out.addProperty("name", row.name());
        out.addProperty("phase", row.phase() == null ? null : row.phase().name());
        out.addProperty("outcome", row.outcome() == null ? null : row.outcome().name());
        out.add("detail", JsonParser.parseString(row.detail()).getAsJsonObject());
        out.addProperty("source", row.source());
        out.addProperty("at", row.at());
        out.addProperty("validity_claimed", row.validityClaimed());
        out.addProperty("companion_id", row.companionId());
        out.addProperty("evidence", row.evidence() == null ? null : row.evidence().name());
        return out.toString();
    }

    private static UsageLedger.Entry entryFromSnapshot(JsonObject row) {
        JsonElement detail = row.get("detail");
        if (detail == null || !detail.isJsonObject()) throw new IllegalArgumentException("detail");
        JsonElement claimed = row.get("validity_claimed");
        if (claimed == null || !claimed.isJsonPrimitive() || !claimed.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException("validity_claimed");
        String phase = requiredText(row, "phase"), outcome = requiredText(row, "outcome");
        return new UsageLedger.Entry(requiredText(row, "artifact_id"), requiredText(row, "kind"),
                requiredText(row, "name"),
                UsageLedger.Phase.valueOf(phase),
                UsageLedger.Outcome.valueOf(outcome),
                detail.getAsJsonObject().toString(), requiredText(row, "source"), requiredText(row, "at"),
                claimed.getAsBoolean(), requiredText(row, "companion_id"),
                UsageLedger.EvidenceLevel.valueOf(requiredText(row, "evidence")));
    }

    /** Shared Entry completeness: typed rows and snapshot round-trips must both pass before any fact. */
    private static boolean completeEntry(UsageLedger.Entry row) {
        return row.phase() != null && row.outcome() != null && row.evidence() != null
                && !blank(row.artifactId()) && !blank(row.kind()) && !blank(row.name())
                && !blank(row.at()) && !blank(row.source()) && !blank(row.companionId());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** Strict: missing/null/non-string/blank metadata is not a complete Entry; never coerce or default. */
    private static String requiredText(JsonObject row, String name) {
        JsonElement value = row.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("missing or non-string " + name);
        String text = value.getAsString();
        if (text.isBlank()) throw new IllegalArgumentException("blank " + name);
        return text;
    }

    private interface Candidate {
        String id();
        String fingerprint();
        Memo memo(long now);
    }

    private record DeliveryCandidate(Delivery delivery) implements Candidate {
        public String id() { return memoIdOf(delivery.row()); }
        public String fingerprint() { return SkillDeliveryIntake.fingerprint(delivery); }
        public Memo memo(long now) {
            return new Memo(id(), "完整正文已交付：" + delivery.ref() + "@" + delivery.version(),
                    "skill_delivery", "仅为正文交付待审；未证明采用、原任务结果或有效性。",
                    delivery.row().detail(), now);
        }
    }

    private record ResultCandidate(UsageLedger.Entry row, TaskResultFact fact) implements Candidate {
        public String id() { return resultMemoIdOf(row); }
        public String fingerprint() { return "skill_task_result:" + row.artifactId(); }
        public Memo memo(long now) {
            return new Memo(id(), "原任务结果待审：" + fact.ref() + "@" + fact.version(),
                    "skill_task_result", "完整原任务结果；未证明采用或有效性。",
                    entrySnapshot(row), now);
        }
    }

    /** 幂等键：sr- + sha256(artifactId)。 */
    public static String resultMemoIdOf(UsageLedger.Entry row) {
        if (row == null || row.artifactId() == null || row.artifactId().isBlank()) return "";
        return "sr-" + sha256(row.artifactId());
    }

    /** 幂等键：sd- + sha256(artifactId)。 */
    public static String memoIdOf(UsageLedger.Entry row) {
        if (row == null || row.artifactId() == null || row.artifactId().isBlank()) return "";
        return "sd-" + sha256(row.artifactId());
    }

    private record LedgerKey(String companionId, String artifactId) {}
    private record TaskUse(Scope scope, String ref, String version, String taskId, String taskCall) {}
    private record Delivery(UsageLedger.Entry row, Scope scope, String ref, String version,
                            String taskId, String taskCall) {
        TaskUse taskUse() {
            return taskId.isEmpty() ? null : new TaskUse(scope, ref, version, taskId, taskCall);
        }
    }
    private record Selection(List<Candidate> due, int foreignScope, int resultChannel) {}

    private static Selection classify(List<UsageLedger.Entry> rows, Scope scope, boolean includeResults) {
        if (scope == null || !scope.complete() || rows == null) return new Selection(List.of(), 0, 0);
        Map<LedgerKey, UsageLedger.Entry> latest = new LinkedHashMap<>();
        for (UsageLedger.Entry row : rows) {
            if (row == null || row.phase() == null) continue;
            LedgerKey key = new LedgerKey(row.companionId(), row.artifactId());
            UsageLedger.Entry old = latest.get(key);
            if (old == null || row.phase().rank() >= old.phase().rank()) latest.put(key, row);
        }
        // 2026-11-13: real feedback uses #task= and the skill's kind, not #k= or kind=SKILL.
        // Join only complete original facts in memory; do not rewrite either producer's artifact format.
        Set<TaskUse> results = new HashSet<>();
        for (UsageLedger.Entry row : latest.values()) {
            TaskUse use = taskResult(row);
            if (use != null) results.add(use);
        }
        List<Candidate> due = new ArrayList<>();
        int foreign = 0, resultChannel = 0;
        for (UsageLedger.Entry row : latest.values()) {
            Delivery delivery = delivery(row);
            if (delivery != null) {
                if (!scope.equals(delivery.scope())) { foreign++; continue; }
                if (row.phase() == UsageLedger.Phase.RESULT
                        || results.contains(delivery.taskUse())) {
                    resultChannel++;
                } else if (row.phase() == UsageLedger.Phase.PRESENTED
                        && row.outcome() == UsageLedger.Outcome.UNKNOWN) {
                    due.add(new DeliveryCandidate(delivery));
                }
                continue;
            }
            if (!includeResults) continue;
            TaskResultFact fact = taskResultOf(row);
            if (fact == null) continue;
            if (!scope.equals(fact.scope())) { foreign++; continue; }
            due.add(new ResultCandidate(row, fact));
        }
        return new Selection(List.copyOf(due), foreign, resultChannel);
    }

    private static Delivery delivery(UsageLedger.Entry row) {
        if (!SkillDeliveryEvidence.KIND.equals(row.kind())) return null;
        try {
            JsonObject detail = JsonParser.parseString(row.detail()).getAsJsonObject();
            String ref = text(detail, "skill_ref"), version = text(detail, "version");
            if (!canonical(ref, version)) return null;
            String request = text(detail, "request_id");
            if (!row.source().equals(SkillDeliveryEvidence.SOURCE_PREFIX + request)) return null;
            String prefix = ref + "@" + version + "#k=";
            if (!row.artifactId().startsWith(prefix) || row.artifactId().length() == prefix.length()) return null;
            Scope scope = rowScope(detail, row.companionId());
            String task = optionalText(detail, "task_id"), call = optionalText(detail, "tool_call_id");
            if (task.isBlank() != call.isBlank()) return null;
            String useKey = text(detail, "use_key");
            boolean episode = bool(detail, "delivery_only");
            if (task.isBlank()) {
                if (!episode || !"episode".equals(useKey)) return null;
                task = ""; call = "";
            } else if (episode || !"task".equals(useKey)) return null;
            return new Delivery(row, scope, ref, version, task, call);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static TaskUse taskResult(UsageLedger.Entry row) {
        if (row.phase() != UsageLedger.Phase.RESULT || row.source() == null
                || !row.source().startsWith("task-skill:")) return null;
        try {
            JsonObject detail = JsonParser.parseString(row.detail()).getAsJsonObject();
            String ref = text(detail, "skill_ref"), version = text(detail, "version");
            if (!canonical(ref, version) || !row.companionId().equals(text(detail, "companion_id"))) return null;
            Scope scope = rowScope(detail, row.companionId());
            String task = text(detail, "task_id"), call = text(detail, "task_tool_call_id");
            JsonArray identity = new JsonArray();
            identity.add(scope.worldId()); identity.add(scope.serverSession()); identity.add(scope.epoch());
            identity.add(scope.companionId()); identity.add(task); identity.add(call);
            if (!identity.equals(detail.getAsJsonArray("task_identity"))) return null;
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(identity.toString().getBytes(StandardCharsets.UTF_8));
            if (!row.artifactId().equals(ref + "@" + version + "#task=" + encoded)) return null;
            String terminal = text(detail, "terminal_state");
            UsageLedger.Outcome expected = switch (terminal) {
                case "SUCCESS" -> UsageLedger.Outcome.SUCCESS;
                case "FAILED", "TIMEOUT" -> UsageLedger.Outcome.FAIL;
                case "CANCELLED" -> UsageLedger.Outcome.CANCELED;
                default -> null;
            };
            if (expected == null || row.outcome() != expected
                    || !row.source().equals("task-skill:" + text(detail, "task_fact_key") + ":" + terminal)) return null;
            return new TaskUse(scope, ref, version, task, call);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static boolean canonical(String ref, String version) {
        return ref.matches(SkillDeliveryEvidence.REF_PATTERN) && version.matches(SkillDeliveryEvidence.VERSION_PATTERN);
    }

    private static Scope rowScope(JsonObject detail, String companion) {
        var value = detail.getAsJsonPrimitive("epoch");
        if (value == null || !value.isNumber()) throw new IllegalArgumentException("missing epoch");
        Scope scope = new Scope(text(detail, "world_id"), text(detail, "server_session"),
                value.getAsBigDecimal().longValueExact(), companion);
        if (!scope.complete()) throw new IllegalArgumentException("incomplete scope");
        return scope;
    }

    private static String text(JsonObject detail, String name) {
        String value = optionalText(detail, name);
        if (value.isBlank()) throw new IllegalArgumentException("missing " + name);
        return value;
    }

    private static String optionalText(JsonObject detail, String name) {
        var value = detail.get(name);
        if (value == null || value.isJsonNull()) return "";
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("non-string " + name);
        return value.getAsString();
    }

    private static boolean bool(JsonObject detail, String name) {
        var value = detail.getAsJsonPrimitive(name);
        if (value == null || !value.isBoolean()) throw new IllegalArgumentException("missing " + name);
        return value.getAsBoolean();
    }

    private static void remember(EnqueuedJournal journal, String id) {
        journal.remember(id);
        if (!journal.contains(id)) throw new IllegalStateException("journal did not confirm " + id);
    }

    private static String fingerprint(Delivery delivery) {
        JsonArray key = new JsonArray();
        key.add(delivery.ref()); key.add(delivery.version());
        key.add(delivery.scope().worldId()); key.add(delivery.scope().serverSession()); key.add(delivery.scope().epoch());
        return "skill_delivery:" + key;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
