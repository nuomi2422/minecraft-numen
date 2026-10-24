package com.dwinovo.numen.experience.core;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceType;
import com.dwinovo.numen.experience.api.PublicationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G6a 判据 G3a（离线）：发布资格迁移落库、非法迁移不改状态、REVOKED 是终态。
 *
 * <p>为什么这些必须在 store 层钉住：工具层只做参数解析，真正的「状态机是不是摆设」在这里。
 */
class ExperiencePublicationStoreTest {

    @TempDir
    Path dir;

    private ExperienceStore store() {
        return ExperienceStore.at(dir.resolve("exp.jsonl"));
    }

    private static ExperienceEntry draft(String title) {
        return ExperienceEntry.builder()
                .type(ExperienceType.EXECUTION)
                .title(title)
                .description("d")
                .publication(PublicationState.DRAFT)
                .build();
    }

    @Test
    void 合法迁移落库_重新加载后仍是新状态() {
        ExperienceStore s = store();
        ExperienceEntry e = s.learn(draft("发布测试一"));

        ExperienceStore.PublishResult r = s.publish(e.id(), PublicationState.PUBLISHED, "首测发布");
        assertTrue(r.ok(), r.code() + " / " + r.message());
        assertEquals(PublicationState.PUBLISHED, r.entry().publication());

        // 重新从磁盘加载：迁移必须真的落盘，不是只改内存
        ExperienceStore reloaded = ExperienceStore.at(dir.resolve("exp.jsonl"));
        ExperienceEntry after = reloaded.all().stream().filter(x -> x.id().equals(e.id())).findFirst().orElse(null);
        assertNotNull(after);
        assertEquals(PublicationState.PUBLISHED, after.publication());
    }

    @Test
    void 非法迁移不改状态并如实报错() {
        ExperienceStore s = store();
        ExperienceEntry e = s.learn(draft("发布测试二"));
        s.publish(e.id(), PublicationState.PUBLISHED, "先发布");

        ExperienceStore.PublishResult back = s.publish(e.id(), PublicationState.DRAFT, "想退回草稿");
        assertFalse(back.ok());
        assertEquals("ILLEGAL_TRANSITION", back.code());
        assertEquals(PublicationState.PUBLISHED, back.entry().publication(), "非法迁移不许改状态");
    }

    @Test
    void REVOKED是终态() {
        ExperienceStore s = store();
        ExperienceEntry e = s.learn(draft("发布测试三"));
        assertTrue(s.publish(e.id(), PublicationState.REVOKED, "撤回").ok());

        ExperienceStore.PublishResult revive = s.publish(e.id(), PublicationState.PUBLISHED, "想复活");
        assertFalse(revive.ok());
        assertEquals("ILLEGAL_TRANSITION", revive.code());
        assertEquals(PublicationState.REVOKED, revive.entry().publication());
    }

    @Test
    void 不存在的id返回NOT_FOUND() {
        ExperienceStore.PublishResult r = store().publish("execution|没有这条", PublicationState.PUBLISHED, "");
        assertFalse(r.ok());
        assertEquals("NOT_FOUND", r.code());
    }

    @Test
    void 老条目默认PUBLISHED_可以正常迁移() {
        ExperienceStore s = store();
        // 模拟老条目：publication 缺省（builder 不给 → PUBLISHED）
        ExperienceEntry legacy = ExperienceEntry.builder()
                .type(ExperienceType.EXECUTION).title("老经验").description("d").build();
        ExperienceEntry stored = s.learn(legacy);
        assertEquals(PublicationState.PUBLISHED, stored.publication());

        ExperienceStore.PublishResult r = s.publish(stored.id(), PublicationState.SUSPENDED, "先暂停");
        assertTrue(r.ok(), r.code());
        assertEquals(PublicationState.SUSPENDED, r.entry().publication());
    }

    @Test
    void 验证成功一次后发布状态不许丢() {
        // G6a-3 实机踩到：from() 不搬 publication ⇒ recordEvidence 把 DRAFT 抹回 PUBLISHED
        ExperienceStore s = store();
        ExperienceEntry e = s.learn(draft("发布状态保持测试"));
        assertEquals(PublicationState.DRAFT, e.publication());

        ExperienceEntry verified = s.recordEvidence(e.id(), true, "一次成功");
        assertEquals(PublicationState.DRAFT, verified.publication(),
                "验证只改成熟度，不许顺手把发布状态抹回默认");
        assertEquals(com.dwinovo.numen.experience.api.ExperienceMaturity.VERIFIED, verified.maturity());
    }

    // ===== LC3（78 号 §7）：反例阻止错误晋级 =====

    /**
     * 近期连续失败 ≥2 的经验，不许迁到 PUBLISHED（执行视图）。
     * 这是负向门：只否决「有反例还放行」，不做「攒够成功自动放行」。
     */
    @Test
    void 连续失败两次不许发布到执行视图() {
        ExperienceStore s = store();
        ExperienceEntry e = s.learn(draft("反例拦截测试"));
        s.publish(e.id(), PublicationState.TRIAL, "先进试用");
        s.recordEvidence(e.id(), false, "第一次照做失败");
        s.recordEvidence(e.id(), false, "第二次又失败");

        ExperienceStore.PublishResult r = s.publish(e.id(), PublicationState.PUBLISHED, "想直接发布");
        assertFalse(r.ok());
        assertEquals("COUNTER_EXAMPLES_BLOCK", r.code(), "反例门必须拦: " + r.message());
        assertEquals(PublicationState.TRIAL, r.entry().publication(), "状态不许被改");
        assertTrue(r.message().contains("连续失败"), "拒绝理由要说清是反例拦的: " + r.message());
    }

    /** 一次失败没到阈值：不拦（单次失败不构成模式，与 T3「一次是意外」同哲学）。 */
    @Test
    void 一次失败不触发反例门() {
        ExperienceStore s = store();
        ExperienceEntry e = s.learn(draft("单败放行测试"));
        s.publish(e.id(), PublicationState.TRIAL, "试用");
        s.recordEvidence(e.id(), false, "就失败了一次");

        ExperienceStore.PublishResult r = s.publish(e.id(), PublicationState.PUBLISHED, "发布");
        assertTrue(r.ok(), "单次失败不该拦住发布（阈值2）: " + r.code() + " " + r.message());
    }

    /** 成功把连续计数归零后，反例门放行 —— 门拦的是「近期模式」，不是终身记录。 */
    @Test
    void 成功归零后反例门放行() {
        ExperienceStore s = store();
        ExperienceEntry e = s.learn(draft("归零后放行测试"));
        s.publish(e.id(), PublicationState.TRIAL, "试用");
        s.recordEvidence(e.id(), false, "失败甲");
        s.recordEvidence(e.id(), false, "失败乙");
        assertFalse(s.publish(e.id(), PublicationState.PUBLISHED, "先确认被拦").ok(), "前置：两次失败在拦");

        s.recordEvidence(e.id(), true, "后来成功了");   // consecutiveFailures 归零

        ExperienceStore.PublishResult r = s.publish(e.id(), PublicationState.PUBLISHED, "再试发布");
        assertTrue(r.ok(), "成功归零连续计数后必须放行: " + r.code() + " " + r.message());
    }

    /** 停用方向永远不被反例卡：紧急刹车（SUSPENDED/REVOKED）的权力高于学习判定。 */
    @Test
    void 反例不拦停用方向() {
        ExperienceStore s = store();
        ExperienceEntry e = s.learn(draft("刹车优先测试"));
        s.publish(e.id(), PublicationState.TRIAL, "试用");
        s.recordEvidence(e.id(), false, "失败甲");
        s.recordEvidence(e.id(), false, "失败乙");
        // 反例在身也必须能 SUSPENDED（人工叫停）和 REVOKED（终态撤回）
        assertTrue(s.publish(e.id(), PublicationState.SUSPENDED, "主人叫停").ok(),
                "★ 反例不许拦停用方向——主人的刹车权高于一切学习判定");
        assertTrue(s.publish(e.id(), PublicationState.REVOKED, "彻底撤").ok(),
                "★ REVOKED 是终态撤回，反例更不许拦");
    }
}