package com.dwinovo.numen.plugins.learner.contract;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 产物模板：每类必备字段、指纹与正文解耦（158 §3.2 / §6，161 §6.3）。
 */
class ArtifactValidatorTest {

    private static ArtifactDraft experience(String body) {
        return new ArtifactDraft(ArtifactKind.EXPERIENCE, "夜间别出门",
                "夜里在洞口停留会被僵尸围住", body,
                List.of("夜间在洞口附近"), List.of("白天"),
                List.of(), "", List.of(EvidenceRef.parse("block:dc-turn-3")),
                List.of("s-1"), "EXECUTOR");
    }

    private static ArtifactDraft autoAction(List<String> preconditions) {
        return new ArtifactDraft(ArtifactKind.AUTO_ACTION, "低血撤退",
                "血量低于4时撤回安全屋", "if hp<4 -> retreat",
                List.of("hp<4"), List.of("已在安全屋"), preconditions,
                "MEDIUM", List.of(EvidenceRef.parse("block:dc-turn-3")),
                List.of("s-1"), "EXECUTOR");
    }

    @Test
    void 完整经验产物通过() {
        ArtifactValidator.Verdict v = ArtifactValidator.validate(experience("正文"));
        assertTrue(v.accepted(), "不该有问题：" + v.codes());
        assertTrue(v.violations().isEmpty());
    }

    @Test
    void 没有证据的产物是硬失败() {
        ArtifactDraft d = new ArtifactDraft(ArtifactKind.EXPERIENCE, "x", "p", "b",
                List.of("t"), List.of(), List.of(), "", List.of(), List.of(), "EXECUTOR");
        ArtifactValidator.Verdict v = ArtifactValidator.validate(d);
        assertFalse(v.accepted());
        assertTrue(v.codes().contains("artifact_without_evidence"));
    }

    @Test
    void 自动动作缺前置条件是硬失败() {
        ArtifactValidator.Verdict v = ArtifactValidator.validate(autoAction(List.of()));
        assertFalse(v.accepted(), "没有前置条件 = 任何时刻都可能触发，那是真事故不是文档瑕疵");
        assertTrue(v.codes().contains("missing_field:preconditions"));
    }

    @Test
    void 缺多个字段就报多个() {
        ArtifactDraft d = new ArtifactDraft(ArtifactKind.AUTO_ACTION, "x", "p", "",
                List.of(), List.of(), List.of(), "", List.of(), List.of(), "");
        ArtifactValidator.Verdict v = ArtifactValidator.validate(d);
        assertTrue(v.codes().contains("missing_field:preconditions"));
        assertTrue(v.codes().contains("missing_field:risk"));
        assertTrue(v.codes().contains("missing_field:excludeConditions"));
    }

    @Test
    void 没有名字也算问题() {
        ArtifactDraft d = new ArtifactDraft(ArtifactKind.TOOL_NOTE, "  ", "p", "b",
                List.of(), List.of(), List.of(), "", List.of(EvidenceRef.parse("block:dc-1")),
                List.of(), "EXECUTOR");
        assertTrue(ArtifactValidator.validate(d).codes().contains("no_name"));
    }

    @Test
    void 指纹与正文无关() {
        // 161 §6.3：正文一改就是新产物，与「同义产物进 reuse/revise/conflict」打架
        ArtifactDraft a = experience("第一版正文");
        ArtifactDraft b = experience("第二版正文");
        assertEquals(a.fingerprint(), b.fingerprint(), "改正文是修订，不是新产物");
        assertNotEquals(a.contentHash(), b.contentHash(), "内容摘要该变");
    }

    @Test
    void 指纹随用途与触发条件变化() {
        ArtifactDraft a = experience("同正文");
        ArtifactDraft b = new ArtifactDraft(ArtifactKind.EXPERIENCE, "夜间别出门",
                "夜里在洞口停留会被僵尸围住", "同正文",
                List.of("夜间在沙漠"), List.of("白天"),
                List.of(), "", List.of(EvidenceRef.parse("block:dc-turn-3")),
                List.of("s-1"), "EXECUTOR");
        assertNotEquals(a.fingerprint(), b.fingerprint(), "触发条件变了就是另一个产物");
    }

    @Test
    void 指纹归一化全角与空白() {
        ArtifactDraft a = new ArtifactDraft(ArtifactKind.EXPERIENCE, "n", "ＡＢ　Ｃ", "b",
                List.of(" t1 ", "t2"), List.of(), List.of(), "",
                List.of(EvidenceRef.parse("block:x")), List.of(), "executor");
        ArtifactDraft b = new ArtifactDraft(ArtifactKind.EXPERIENCE, "n", "ab c", "b",
                List.of("t1", "t2"), List.of(), List.of(), "",
                List.of(EvidenceRef.parse("block:x")), List.of(), "EXECUTOR");
        assertEquals(a.fingerprint(), b.fingerprint());
    }
}
