package com.dwinovo.numen.agent.retrieval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** G5a 判据 S2：注册表的同 id 拒绝 / 空白拒收 / size 正确。 */
class RetrievalRegistryTest {

    private static RetrievalProvider p(String id) {
        return new RetrievalProvider() {
            @Override public String providerId() { return id; }
            @Override public SearchPage search(RetrievalQuery q) {
                return SearchPage.empty(id, q == null ? "" : q.queryId());
            }
        };
    }

    @Test
    void duplicateProviderIdIsRejectedNotSilentlyOverwritten() {
        RetrievalRegistry r = new RetrievalRegistry();
        assertTrue(r.register(p("a")).ok());
        Registration second = r.register(p("a"));
        assertFalse(second.ok(), "重复 providerId 必须被拒绝");
        assertTrue(second.message().contains("重复"), "拒绝原因要说清是重复：" + second.message());
        assertEquals(1, r.size(), "拒绝之后不许把旧 provider 悄悄换成新 provider");
        assertEquals("a", r.byId("a").orElseThrow().providerId());
    }

    @Test
    void blankOrNullProviderIdIsRejected() {
        RetrievalRegistry r = new RetrievalRegistry();
        assertFalse(r.register(p("")).ok(), "空白 id 拒收");
        assertFalse(r.register(null).ok(), "null provider 拒收");
        assertEquals(0, r.size());
    }

    @Test
    void missingIdIsEmptyOptional() {
        RetrievalRegistry r = new RetrievalRegistry();
        assertTrue(r.byId("nope").isEmpty());
    }

    @Test
    void manyProvidersCoexist() {
        RetrievalRegistry r = new RetrievalRegistry();
        assertTrue(r.register(p("a")).ok());
        assertTrue(r.register(p("b")).ok());
        assertTrue(r.register(p("c")).ok());
        assertEquals(3, r.size());
        assertTrue(r.remove("b"));
        assertEquals(2, r.size());
        assertTrue(r.byId("b").isEmpty());
    }
}
