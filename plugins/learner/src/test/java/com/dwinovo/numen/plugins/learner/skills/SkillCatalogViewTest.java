package com.dwinovo.numen.plugins.learner.skills;

import com.dwinovo.numen.plugins.learner.contract.ArtifactDraft;
import com.dwinovo.numen.plugins.learner.contract.ArtifactKind;
import com.dwinovo.numen.plugins.learner.contract.EvidenceRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 目录读数：空目录要说"还没有条目"，不能报错也不能装成有。 */
class SkillCatalogViewTest {

    private static ArtifactDraft artifact(String name) {
        // ⚠ 名字必须不同：同名不同指纹按设计判 CONFLICT（不落库），两条会被合并成一条。
        return new ArtifactDraft(ArtifactKind.EXPERIENCE, name, "purpose-" + name, "body",
                List.of("夜间"), List.of("白天"), List.of(), "",
                List.of(EvidenceRef.parse("block:dc-1")), List.of("s-1"), "EXECUTOR");
    }

    @Test
    void 没有文件时说清楚是还没有条目(@TempDir Path dir) {
        Map<String, Object> s = SkillCatalogView.stats(dir);
        assertEquals(true, s.get("readable"));
        assertEquals(false, s.get("exists"));
        assertEquals(0, s.get("entries"));
        assertTrue(String.valueOf(s.get("note")).contains("还没有条目"),
                "空目录不等于坏目录，措辞要点明「还没有条目」");
    }

    @Test
    void 有条目时按状态分开数(@TempDir Path dir) throws IOException {
        SkillRegistry reg = SkillRegistry.open(dir.resolve("skills.jsonl"));
        reg.register(artifact("夜间别出门"), "sk-1", "body:1");
        reg.register(artifact("白天别下矿"), "sk-2", "body:2", true);   // 试用

        Map<String, Object> s = SkillCatalogView.stats(dir);
        assertEquals(true, s.get("exists"));
        assertEquals(2, s.get("entries"));
        assertEquals(1, s.get("trial"));
        assertEquals(1, s.get("draft"));
        assertEquals(1, s.get("retrievable"), "只有 trial 参与检索，draft 不算");
    }

    @Test
    void 空文件也算存在但零条(@TempDir Path dir) throws IOException {
        java.nio.file.Files.writeString(dir.resolve("skills.jsonl"), "");
        Map<String, Object> s = SkillCatalogView.stats(dir);
        assertEquals(true, s.get("exists"));
        assertEquals(0, s.get("entries"));
        assertTrue(String.valueOf(s.get("note")).contains("文件存在"), "存在但空 与 不存在 要能分开");
    }

    @Test
    void 配置目录为空不抛(@TempDir Path dir) {
        Map<String, Object> s = SkillCatalogView.stats(null);
        assertEquals(false, s.get("readable"));
        assertFalse(String.valueOf(s.get("error")).isBlank());
    }
}
