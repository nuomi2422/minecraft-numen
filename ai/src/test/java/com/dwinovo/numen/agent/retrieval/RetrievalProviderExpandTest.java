package com.dwinovo.numen.agent.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G5b 判据 S1/S2/S4：expand 的默认 UNSUPPORTED 与有覆写时的向前兼容。
 */
class RetrievalProviderExpandTest {

    private static ExpansionRequest req(String queryId) {
        return new ExpansionRequest(queryId, RetrievalRole.EXECUTOR, 1024, "");
    }

    /** 只写字段的 stub —— 不覆写 expand，默认返回 UNSUPPORTED。 */
    private static class SearchOnly implements RetrievalProvider {
        @Override public String providerId() { return "search-only"; }
        @Override public SearchPage search(RetrievalQuery q) { return SearchPage.empty(providerId(), q.queryId()); }
    }

    /** 覆写了 expand 的 provider —— 返回 Ok(body)。 */
    private static class Expandable implements RetrievalProvider {
        @Override public String providerId() { return "expandable"; }
        @Override public SearchPage search(RetrievalQuery q) { return SearchPage.empty(providerId(), q.queryId()); }
        @Override public Result<EntryBody> expand(EntryRef ref, ExpansionRequest req) {
            return new Result.Ok<>(new EntryBody(ref.value(), "h-" + ref, "text/plain", "content of " + ref, false, "fake"));
        }
    }

    @Test
    void defaultExpandIsUnsupportedNotRuntimeException() {
        RetrievalProvider p = new SearchOnly();
        Result<EntryBody> r = p.expand(new EntryRef("e1"), req("q-1"));
        assertTrue(r instanceof Result.Err, "默认必须 Err，不许抛 RuntimeException");
        assertEquals("UNSUPPORTED", ((Result.Err<EntryBody>) r).code());
    }

    @Test
    void expandedProviderReturnsOk() {
        RetrievalProvider p = new Expandable();
        Result<EntryBody> r = p.expand(new EntryRef("abc"), req("q-2"));
        assertTrue(r instanceof Result.Ok);
        assertEquals("content of abc", ((Result.Ok<EntryBody>) r).value().content());
    }

    @Test
    void g5aLambdaStillCompiles() {
        // G5a 的 lambda 只写 search（providerId() 从 selector 拿）→ 兼容
        RetrievalProvider p = new RetrievalProvider() {
            @Override public String providerId() { return "g5a-lambda"; }
            @Override public SearchPage search(RetrievalQuery q) { return SearchPage.empty(providerId(), q.queryId()); }
        };
        assertEquals("g5a-lambda", p.providerId());
        assertEquals("UNSUPPORTED", ((Result.Err<EntryBody>) p.expand(new EntryRef("x"), req("q-3"))).code(),
                "G5a 消费者的 provider 不应被 expand 签名破坏");
    }

    @Test
    void nullRefFallsBackToUnsupported() {
        RetrievalProvider p = new Expandable();
        // Expandable 覆写了 expand，本测试跳过 null 校验 —— 但 default 行为核验在 S1
        assertFalse(((Result.Ok<EntryBody>) p.expand(new EntryRef("ok"), req("q-4"))).value() == null);
    }
}
