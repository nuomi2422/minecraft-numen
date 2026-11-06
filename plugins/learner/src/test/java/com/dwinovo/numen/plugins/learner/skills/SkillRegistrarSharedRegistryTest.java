package com.dwinovo.numen.plugins.learner.skills;

import com.dwinovo.numen.plugins.learner.contract.ArtifactDraft;
import com.dwinovo.numen.plugins.learner.contract.ArtifactKind;
import com.dwinovo.numen.plugins.learner.contract.EvidenceRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单一写者（2026-11-06 丢失更新事故的回归）。
 *
 * <p>事故：管理侧的 retire/promote 各开一份新目录改完写盘，复盘侧用的是缓存旧实例，
 * 下一次登记把整份内存图写回，**撤回/升格被静默覆盖**（实测 10:32 的 6 retire + 3 promote
 * 全没落盘，事件却都发了）。这里钉住：同一 configDir 只能有一个目录实例。
 */
class SkillRegistrarSharedRegistryTest {

    @AfterEach
    void reset() {
        SkillRegistrar.resetForTest();
    }

    private static ArtifactDraft artifact(String purpose) {
        return new ArtifactDraft(ArtifactKind.EXPERIENCE, "夜跑", purpose, "body",
                List.of("夜间"), List.of("白天"), List.of(), "",
                List.of(EvidenceRef.parse("block:dc-1")), List.of("s-1"), "EXECUTOR");
    }

    @Test
    void 同一配置目录返回同一实例(@TempDir Path dir) {
        assertTrue(SkillRegistrar.registryFor(dir) == SkillRegistrar.registryFor(dir),
                "两次取必须是同一个实例，否则两个写者互相覆盖");
    }

    @Test
    void 管理侧改动不会被随后的登记覆盖(@TempDir Path dir) throws IOException {
        // 先把目录缓存起来（模拟复盘侧早早就持有实例）
        SkillRegistry shared = SkillRegistrar.registryFor(dir);
        shared.register(artifact("夜里在洞口停留会被围"), "sk-1", "body:1");

        // 管理侧升格（与复盘共用同一实例）
        assertEquals(SkillRegistry.Outcome.REVISE, shared.promote("sk-1", "ok").outcome());

        // 复盘再来登记一条别的：不能把上面那次升格写回 DRAFT
        SkillRegistrar.register(dir, new com.dwinovo.numen.plugins.learner.contract.Submission.Draft(
                com.dwinovo.numen.plugins.learner.contract.Submission.Mode.INTERNAL, "s-9",
                new com.dwinovo.numen.plugins.learner.contract.ReviewDraft.Draft("s-9", List.of()),
                List.of(artifact("别的教训"))), true);

        SkillRegistry reread = SkillRegistry.open(dir.resolve("skills.jsonl"));
        assertEquals(SkillStatus.TRIAL, reread.get("sk-1").orElseThrow().status(),
                "升格结果必须落盘且不被后来的登记覆盖");
        assertTrue(Files.exists(dir.resolve("skills.jsonl")));
    }
}
