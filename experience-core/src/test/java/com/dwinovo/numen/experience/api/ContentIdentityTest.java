package com.dwinovo.numen.experience.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LC1（78 号 §3）：正文命题指纹 —— 「哪个版本被验证过」的版本号。
 * 钉死三件事：①命题字段变化 ⇒ 指纹变；②元数据/评价变化 ⇒ 指纹<b>不变</b>；
 * ③null 与空串同形（老条目槽位缺失不产生假版本差）。
 */
class ContentIdentityTest {

    private static ExperienceEntry.Builder base() {
        return ExperienceEntry.builder()
                .type(ExperienceType.FAILURE)
                .title("同一条经验")
                .description("工具回执成功但世界没变");
    }

    @Test
    void 命题字段变化会换指纹() {
        ExperienceEntry a = base().build();
        ExperienceEntry b = base().description("工具回执成功但世界变了").build();
        assertNotEquals(a.contentFingerprint(), b.contentFingerprint(), "description 变了 = 新命题");
    }

    @Test
    void 七字段变化会换指纹() {
        ExperienceEntry a = base().mechanism("旧机制").build();
        ExperienceEntry b = base().mechanism("新机制").build();
        assertNotEquals(a.contentFingerprint(), b.contentFingerprint());
    }

    @Test
    void 触发词变化会换指纹() {
        ExperienceEntry a = base().triggerStrings(List.of("假成功")).build();
        ExperienceEntry b = base().triggerStrings(List.of("假成功", "世界没变")).build();
        assertNotEquals(a.contentFingerprint(), b.contentFingerprint());
    }

    @Test
    void 评价与元数据变化不换指纹() {
        ExperienceEntry a = base().build();
        // 成熟度/计数/撤回/发布/优先级/tags/时间戳 —— 全是「对这条经验的评价」，不是命题
        ExperienceEntry b = a.withEvidence(ExperienceMaturity.VERIFIED, 5,
                System.currentTimeMillis(), List.of());
        ExperienceEntry c = b.withPublication(PublicationState.SUSPENDED);
        ExperienceEntry d = ExperienceEntry.builder().from(c).priority(99).build();
        ExperienceEntry e = ExperienceEntry.builder().from(d).tags(List.of("新标签")).build();
        ExperienceEntry f = e.retracted("理由");
        assertEquals(a.contentFingerprint(), b.contentFingerprint(), "验证计数不换版本");
        assertEquals(a.contentFingerprint(), c.contentFingerprint(), "发布状态不换版本");
        assertEquals(a.contentFingerprint(), d.contentFingerprint(), "优先级不换版本");
        assertEquals(a.contentFingerprint(), e.contentFingerprint(), "tags 不换版本");
        assertEquals(a.contentFingerprint(), f.contentFingerprint(), "撤回不换版本");
    }

    @Test
    void null与空串同形_trim容错() {
        ExperienceEntry a = base().mechanism(null).build();
        ExperienceEntry b = base().mechanism("").build();
        assertEquals(a.contentFingerprint(), b.contentFingerprint(),
                "null 与空串是同一版「没填」，不产生假版本差");
        ExperienceEntry c = base().mechanism("  正文  ").build();
        ExperienceEntry d = base().mechanism("正文").build();
        assertEquals(c.contentFingerprint(), d.contentFingerprint(), "首尾空白差异不算新版本");
    }

    @Test
    void null条目有稳定指纹() {
        assertEquals("c0", ContentIdentity.fingerprint(null));
        assertEquals("c0", ContentIdentity.fingerprint(null), "重复调用必须稳定");
    }

    @Test
    void sameContent按指纹判同版() {
        ExperienceEntry a = base().build();
        ExperienceEntry b = ExperienceEntry.builder().from(a).verifiedCount(9).build();
        assertTrue(a.sameContent(b), "只改评价字段 = 同版正文");
        ExperienceEntry c = base().rootCause("新根因").build();
        assertEquals(false, a.sameContent(c), "根因变了 = 跨版本");
    }
}
