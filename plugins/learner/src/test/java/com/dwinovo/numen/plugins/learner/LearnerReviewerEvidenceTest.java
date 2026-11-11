package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.plugins.learner.core.ExperienceSevenFieldsTest;
import com.dwinovo.numen.plugins.learner.core.Verdict;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-12：把 learner.jsonl L30480 原响应接到真实补证判断。
 *
 * <p>用法：后续登记后走受管 TestOnly；本批只准备，不运行。
 * 原因：模型已有非空数组引用，却在 Verdict/Experience 解析后被当成缺证。
 * 为什么不只 new Experience：那会跳过真正丢数组的解析点；不反射私有提示词，
 * 不引入 Minecraft loader 或模拟框架。引用保留不代表引用已被核验。</p>
 */
class LearnerReviewerEvidenceTest {

    @Test
    void originalResponseReferencesDoNotTriggerBlankEvidenceRetry() {
        List<Verdict> verdicts = new ArrayList<>();
        ExperienceSevenFieldsTest.originalEvidenceBatch().forEach(raw -> {
            JsonObject one = raw.getAsJsonObject();
            Verdict verdict = Verdict.parse(one.get("memo_id").getAsString(), one.toString());
            assertNotNull(verdict);
            verdicts.add(verdict);
        });
        assertFalse(LearnerReviewer.hasBlankEvidence(LearnerReviewer.ReviewOutcome.ok(verdicts)),
                "原响应已带 event 数组，解析后不能误触发补证");
    }

    @Test
    void oneActuallyMissingEvidenceStillTriggersRetryInTheOriginalBatch() {
        List<Verdict> verdicts = new ArrayList<>();
        ExperienceSevenFieldsTest.originalEvidenceBatch().forEach(raw -> {
            JsonObject one = raw.getAsJsonObject();
            verdicts.add(Verdict.parse(one.get("memo_id").getAsString(), one.toString()));
        });
        JsonObject missing = ExperienceSevenFieldsTest.originalEvidenceBatch().get(1).getAsJsonObject();
        missing.getAsJsonObject("experience").remove("evidence");
        verdicts.set(1, Verdict.parse(missing.get("memo_id").getAsString(), missing.toString()));
        assertTrue(LearnerReviewer.hasBlankEvidence(LearnerReviewer.ReviewOutcome.ok(verdicts)));
    }
}
