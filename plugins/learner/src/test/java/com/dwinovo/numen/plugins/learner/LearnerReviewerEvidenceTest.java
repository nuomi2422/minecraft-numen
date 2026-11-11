package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.plugins.learner.core.Experience;
import com.dwinovo.numen.plugins.learner.core.ExperienceSevenFieldsTest;
import com.dwinovo.numen.plugins.learner.core.Verdict;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-12：把 learner.jsonl L30480 原响应接到真实补证判断。
 *
 * <p>用法：精确登记后走受管 TestOnly；源码候选与实际测试结果分开记录。
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

    @Test
    void actualSystemUsesStringExperienceEvidenceAndKeepsAcEvidenceContractSeparate() {
        String system = LearnerReviewer.systemPrompt();
        assertTrue(system.contains(Experience.promptSpec()), "实际 SYSTEM 必须含真实七字段规范");
        assertTrue(Experience.promptSpec().contains("experience.evidence 必须是非空字符串"));
        assertTrue(system.contains("experience.evidence 必须是非空字符串"));
        assertFalse(system.contains("必须是非空数组"), "经验提示不能再命令数组");
        int format = system.indexOf("输出格式：");
        int end = system.indexOf("★ memo_id", format);
        assertTrue(format >= 0 && end > format);
        JsonObject sample = JsonParser.parseString(system.substring(format + "输出格式：".length(), end).trim())
                .getAsJsonObject().getAsJsonArray("verdicts").get(0).getAsJsonObject()
                .getAsJsonObject("experience");
        assertTrue(sample.getAsJsonPrimitive("evidence").isString());
        assertTrue(system.contains("\"evidence\":[\"event:<memo 快照里的 window_first>\"]"),
                "AC 产物自己的结构化 evidence 数组不受影响");
    }

    @Test
    void actualEvidenceRetryKeepsSuppliedSystemAndRequiresExistingStringReferences() {
        String system = LearnerReviewer.systemPrompt() + "\n已注册积木: fixture-only";
        String retry = LearnerReviewer.evidenceRetrySystem(system);
        assertEquals(system, retry.substring(0, system.length()), "保留生产传入的动态上下文");
        String instruction = retry.substring(system.length());
        assertTrue(instruction.contains("experience.evidence 必须是非空字符串"));
        assertTrue(instruction.contains("不要输出数组"));
        assertTrue(instruction.contains("只引用现场已有"));
        assertTrue(instruction.contains("没有证据就留空"));
        assertTrue(instruction.contains("不许编造或猜补"));
        assertFalse(instruction.contains("非空数组"));
    }
}
