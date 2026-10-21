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
}