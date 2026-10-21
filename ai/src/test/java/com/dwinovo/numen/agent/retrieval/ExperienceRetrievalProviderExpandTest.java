package com.dwinovo.numen.agent.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G5f 判据 X1–X3（provider 侧）：按 ref 展开只读正文。
 *
 * <p>重点不是「能找到」，而是<b>找不到时如实报错</b>——空正文冒充成功会让 AI 以为这条经验没内容。
 */
class ExperienceRetrievalProviderExpandTest {

    private static ExperienceEntryRead entry(String ref, String body, String hash) {
        return new ExperienceEntryRead(
                ref, EntryKind.EXPERIENCE, "标题-" + ref, "摘要",
                List.of(), List.of(), List.of(),
                Maturity.VERIFIED, 2, false,
                body, hash, "experience");
    }

    private static ExpansionRequest req(int maxChars) {
        return new ExpansionRequest("q", RetrievalRole.EXECUTOR, maxChars, "");
    }

    @Test
    void 命中时返回正文与hash() {
        ExperienceRetrievalProvider p = new ExperienceRetrievalProvider("experience",
                () -> List.of(entry("exp:e1", "全文-1", "h1")));
        Result<EntryBody> r = p.expand(new EntryRef("exp:e1"), req(0));
        Result.Ok<EntryBody> ok = assertInstanceOf(Result.Ok.class, r);
        assertEquals("exp:e1", ok.value().entryRef());
        assertEquals("h1", ok.value().contentHash());
        assertEquals("全文-1", ok.value().content());
        assertFalse(ok.value().truncated());
    }

    @Test
    void 超过maxChars时截断并标记() {
        ExperienceRetrievalProvider p = new ExperienceRetrievalProvider("experience",
                () -> List.of(entry("exp:e1", "0123456789", "h1")));
        Result.Ok<EntryBody> ok = assertInstanceOf(Result.Ok.class,
                p.expand(new EntryRef("exp:e1"), req(4)));
        assertTrue(ok.value().truncated());
        assertTrue(ok.value().content().startsWith("0123"));
        assertTrue(ok.value().content().endsWith("…"));
    }

    @Test
    void 找不到返回NOT_FOUND_不造假正文() {
        ExperienceRetrievalProvider p = new ExperienceRetrievalProvider("experience",
                () -> List.of(entry("exp:e1", "全文", "h1")));
        Result.Err<EntryBody> err = assertInstanceOf(Result.Err.class,
                p.expand(new EntryRef("exp:nope"), req(0)));
        assertEquals("NOT_FOUND", err.code());
    }

    @Test
    void source返回null时报SOURCE_NULL() {
        ExperienceRetrievalProvider p = new ExperienceRetrievalProvider("experience", () -> null);
        Result.Err<EntryBody> err = assertInstanceOf(Result.Err.class,
                p.expand(new EntryRef("exp:e1"), req(0)));
        assertEquals("SOURCE_NULL", err.code());
    }

    @Test
    void 每次expand只读一次source() {
        AtomicInteger reads = new AtomicInteger();
        ExperienceRetrievalProvider p = new ExperienceRetrievalProvider("experience", () -> {
            reads.incrementAndGet();
            return List.of(entry("exp:e1", "全文", "h1"));
        });
        p.expand(new EntryRef("exp:e1"), req(0));
        assertEquals(1, reads.get(), "expand 不许反复扫库");
    }
}
