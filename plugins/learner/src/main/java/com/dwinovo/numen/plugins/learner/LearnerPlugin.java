package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugin;
import com.dwinovo.numen.plugins.learner.core.MemoQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 学习者插件（第二批 v1，plan-only）。
 *
 * <p>宿主适配器：注册三个工具、按同伴隔离备忘录队列、把队列深度挂进运行时状态。
 *
 * <p><b>v1 不执行任何身体动作</b>——只写队列 + 只读复盘 + 出建议。
 * 依据是红线 RL-9「单驾驶员」：RDD 规划/监督层不得自动提交身体工具
 * （bodySubmissionEnabled 默认 false，RddPlugin.java:69）。学习者若自动调 AC
 * 就成了第二个发指令主体 = 破线（详见 28 号文档 §2）。
 *
 * <p>跨插件通信：与 rdd / experience / ac 三个插件互不可见（numen-plugin.gradle:37-44
 * 刻意封死跨插件 import）。所以学习者<b>不写经验库</b>，只把「建议写经验」的草稿
 * 交给 AI，由 AI 调既有的 experience_learn 落库。
 */
public final class LearnerPlugin implements NumenPlugin {

    private static final Logger LOG = LoggerFactory.getLogger(LearnerPlugin.class);

    private static final Map<UUID, MemoQueue> QUEUES = new ConcurrentHashMap<>();

    /** reviewId → 复盘状态（异步复盘在后台线程跑完，AI 靠这里查结果）。 */
    private static final Map<String, ReviewRecord> REVIEWS = new ConcurrentHashMap<>();
    private static final int MAX_REVIEW_RECORDS = 32;

    private static volatile Path configDir;
    private static volatile String lastReviewAt = "";
    private static volatile int lastVerdictCount;

    /** 一次复盘的可查询状态。 */
    record ReviewRecord(String reviewId, UUID companionId, String state, int reviewing,
                        int verdictCount, String reason, List<Map<String, Object>> verdicts) {}

    @Override
    public void setup(NumenApi numen) {
        configDir = numen.configDir();
        numen.registerTool(new LearnerNoteTool());
        numen.registerTool(new LearnerReviewTool());
        numen.registerTool(new LearnerStatusTool());
        // 运行时状态：让主 AI 知道「有多少条待复盘的备忘录」，从而自己决定何时调 learner_review
        numen.contributeState(companion -> {
            MemoQueue q = QUEUES.get(companion);
            if (q == null) {
                return "";
            }
            int depth;
            try {
                depth = q.size();
            } catch (RuntimeException e) {
                // 队列读失败（文件损坏）：如实报，不假装是 0
                return "<learner><pending_memos>UNKNOWN</pending_memos></learner>";
            }
            if (depth == 0) {
                return "";
            }
            return "<learner><pending_memos>" + depth + "</pending_memos></learner>";
        });
        LOG.info("[learner] plugin ready; plan-only v1 (no auto-execute)");
    }

    /** 取（或惰性创建）某同伴的备忘录队列。 */
    static MemoQueue queue(UUID companionId) {
        Path dir = configDir;
        if (dir == null) {
            throw new IllegalStateException("learner plugin not set up");
        }
        return QUEUES.computeIfAbsent(companionId,
                uuid -> new MemoQueue(dir.resolve("learner-memos-" + uuid + ".json")));
    }

    static void beginReview(String reviewId, UUID companionId, String state, int reviewing) {
        REVIEWS.put(reviewId, new ReviewRecord(reviewId, companionId, state, reviewing, 0, "", List.of()));
        evictOld();
    }

    static void finishReview(String reviewId, String state, int verdictCount) {
        REVIEWS.computeIfPresent(reviewId, (id, rec) ->
                new ReviewRecord(rec.reviewId(), rec.companionId(), state, rec.reviewing(),
                        verdictCount, state.startsWith("NO_") || state.startsWith("FAILED") ? state : rec.reason(),
                        rec.verdicts()));
    }

    static void setReviewVerdicts(String reviewId, List<Map<String, Object>> verdicts) {
        REVIEWS.computeIfPresent(reviewId, (id, rec) ->
                new ReviewRecord(rec.reviewId(), rec.companionId(), rec.state(), rec.reviewing(),
                        verdicts.size(), rec.reason(), List.copyOf(verdicts)));
    }

    static ReviewRecord review(String reviewId) {
        return REVIEWS.get(reviewId);
    }

    /** 复盘记录有界，防止长跑内存涨。 */
    private static void evictOld() {
        while (REVIEWS.size() > MAX_REVIEW_RECORDS) {
            String oldest = null;
            for (String k : REVIEWS.keySet()) {
                if (oldest == null || k.compareTo(oldest) < 0) {
                    oldest = k;
                }
            }
            if (oldest == null || REVIEWS.remove(oldest) == null) {
                return;
            }
        }
    }

    static void recordReview(String at, int verdictCount) {
        lastReviewAt = at;
        lastVerdictCount = verdictCount;
    }

    static String lastReviewAt() {
        return lastReviewAt;
    }

    static int lastVerdictCount() {
        return lastVerdictCount;
    }

    static Map<String, Object> reviewToMap(ReviewRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("review_id", r.reviewId());
        m.put("state", r.state());
        m.put("reviewing", r.reviewing());
        m.put("verdict_count", r.verdictCount());
        if (!r.reason().isBlank()) {
            m.put("reason", r.reason());
        }
        if (!r.verdicts().isEmpty()) {
            m.put("verdicts", r.verdicts());
        }
        return m;
    }
}
