package com.dwinovo.numen.experience.core;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceMaturity;
import com.dwinovo.numen.experience.api.ExperienceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LC1（78 号 §2.2/§7）：同身份更新正文时，旧证据不许跟着新内容走。
 * 「verifiedCount 继承」是学习线当前最大的假可信源 —— 旧正文验证十次，
 * learn 一版新正文进来仍然显示 VERIFIED x10，谁也没验证过新命题。
 */
class ExperienceContentDriftTest {

    @TempDir
    Path dir;

    private ExperienceStore store() {
        return ExperienceStore.at(dir.resolve("drift.jsonl"));
    }

    private static ExperienceEntry entry(String title, String description) {
        return ExperienceEntry.builder()
                .type(ExperienceType.FAILURE).title(title).description(description)
                .mechanism("原始机制").build();
    }

    @Test
    void 正文漂移_验证计数重置_反例保留() {
        ExperienceStore s = store();
        ExperienceEntry e = s.learn(entry("假成功要重查", "工具回执成功但世界没变"));
        s.recordEvidence(e.id(), true, "第一次成功");
        s.recordEvidence(e.id(), true, "第二次成功");
        assertEquals(2, s.all().get(0).verifiedCount(), "前置：两次验证已入账");

        // 同 type+title（同身份）但 description 变了 = 新命题
        ExperienceEntry updated = s.learn(entry("假成功要重查", "工具回执成功但世界没变（修订版）"));

        assertEquals(0, updated.verifiedCount(), "★ 正文变了：旧验证不许跟着新内容走");
        assertEquals(0, updated.verifiedAt(), "验证时间戳一并重置");
        assertEquals(ExperienceMaturity.VERIFIED, updated.maturity(),
                "成熟度保留（身份级信任读数，修订不自动推翻 —— 推翻走反例连续失败路径）");
        // 反例保留：旧命题的反例记录是证据（此例没有反例，验证下一case）
    }

    @Test
    void 正文漂移_旧反例如实保留() {
        ExperienceStore s = store();
        ExperienceEntry e = s.learn(entry("岩浆边别硬挖", "描述甲"));
        s.recordEvidence(e.id(), false, "旧命题下失败过一次");
        assertEquals(1, s.all().get(0).counterexamples().size());

        ExperienceEntry updated = s.learn(entry("岩浆边别硬挖", "描述乙"));
        assertEquals(1, updated.counterexamples().size(),
                "★ 反例是对旧命题的记录，丢了才是丢证据 —— 保留");
        assertTrue(updated.counterexamples().contains("旧命题下失败过一次"));
    }

    @Test
    void 正文未变_证据照常累计() {
        ExperienceStore s = store();
        ExperienceEntry e = s.learn(entry("同版重复learn", "描述不变"));
        s.recordEvidence(e.id(), true, "成功");
        // 完全相同的正文再 learn（比如工具重试、重复提交）—— 证据不许被误重置
        ExperienceEntry again = s.learn(entry("同版重复learn", "描述不变"));
        assertEquals(1, again.verifiedCount(), "正文没漂移：证据照常累计，不许误重置");
        assertEquals(1, s.size());
    }

    @Test
    void 指纹重载后稳定_不产生假漂移() {
        Path file = dir.resolve("reload.jsonl");
        ExperienceStore w = ExperienceStore.at(file);
        ExperienceEntry e = w.learn(entry("重载稳定性", "描述甲"));
        String before = e.contentFingerprint();

        // 重启（新实例读同一文件）
        ExperienceStore r = ExperienceStore.at(file);
        assertEquals(before, r.all().get(0).contentFingerprint(),
                "指纹只由命题字段决定 —— 落盘/重载不许产生假漂移（否则每次重启都重置证据）");
    }

    @Test
    void 部分字段相同_七字段变化也算漂移() {
        ExperienceStore s = store();
        ExperienceEntry e = s.learn(entry("七字段漂移", "描述"));
        s.recordEvidence(e.id(), true, "成功");
        ExperienceEntry updated = s.learn(ExperienceEntry.builder()
                .type(ExperienceType.FAILURE).title("七字段漂移").description("描述")
                .mechanism("换掉的机制").build());
        assertEquals(0, updated.verifiedCount(), "七字段槽位变了 = 新命题，同样重置");
        assertNotEquals(e.contentFingerprint(), updated.contentFingerprint());
    }

    @Test
    void 合并重载路径同样隔离() throws Exception {
        // 磁盘上同 id 两条（历史脏数据形状），归一后合并 —— 合并本身不产生漂移重置
        Path file = dir.resolve("dup.jsonl");
        ExperienceStore w = ExperienceStore.at(file);
        ExperienceEntry a = w.learn(entry("重复条目", "描述甲"));
        // 第二条同 id 不同内容（模拟历史上写进两条）
        String json = a.toJson().toString().replace("描述甲", "描述乙");
        java.nio.file.Files.writeString(file, json + "\n",
                java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        ExperienceStore r = ExperienceStore.at(file);
        assertEquals(1, r.size(), "归一后合并");
        // 合并取新的正文（描述乙）—— 但这不该被当成「漂移后重置」：加载路径无验证计数可重置
        assertEquals("描述乙", r.all().get(0).description());
    }
}
